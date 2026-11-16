package dev.softkeys.ios;

import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class IosKeyboardService extends InputMethodService {
    private KeyboardView keyboard;
    private DictationSession dictation;
    private String dictationStatus="";
    private boolean dictationFinishing;
    private final Runnable clearDictationNotice=this::cancelDictation;
    private boolean shift, caps, symbols, second, emoji, numeric, privateField, manualShift;
    private boolean allowPredictions, allowGlide, emailField, uriField, rawEditor, terminalEditor, activeInput, emojiSearching;
    private int emojiPage, emojiCategory=1, inputType, fieldId;
    private String editorPackage="",emojiQuery="";
    private int background,keyColor,special,foreground,divider;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private volatile EnglishDictionary dictionary;
    private volatile EmojiCatalog emojiCatalog;
    private final StringBuilder before=new StringBuilder();
    private final ArrayDeque<Integer> acknowledgements=new ArrayDeque<>();
    private final android.icu.text.BreakIterator graphemes=android.icu.text.BreakIterator.getCharacterInstance(Locale.US);
    private long lastShift,lastSpace;
    private volatile long revision;
    private int caret,selectionLength;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private ClipboardManager clipboard;
    private String clipboardText,clipboardPreview="";
    private long clipboardTimestamp,dismissedClipboardTimestamp;
    private static final long CLIPBOARD_LIFETIME=120_000;
    private final Runnable expireClipboard=this::clearClipboardSuggestion;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener=()->handler.post(()->{if(isInputViewShown())readClipboardSuggestion();});
    private final SharedPreferences.OnSharedPreferenceChangeListener clipboardPreferenceListener=(settings,key)->{
        if("clipboardSuggestions".equals(key)){if(isInputViewShown())readClipboardSuggestion();else clearClipboardSuggestion();}
    };
    private String[] suggestions={"I","the","I'm"};
    private String correctionOriginal,correctionReplacement;
    private int correctionCaret=-1,glideLength;
    private boolean decodingGlide;
    private final ArrayDeque<Runnable> pendingActions=new ArrayDeque<>();
    private String correctionPrefix="",cachedCorrection="";
    private final Runnable readContext=this::readContextAsync;
    private final Runnable suggestTask=this::computeSuggestions;

    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(){
        super.onCreate();prefs=KeyboardPreferences.open(this);
        vibrator=Build.VERSION.SDK_INT>=31?getSystemService(VibratorManager.class).getDefaultVibrator():(Vibrator)getSystemService(VIBRATOR_SERVICE);
        clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null)clipboard.addPrimaryClipChangedListener(clipboardListener);
        prefs.registerOnSharedPreferenceChangeListener(clipboardPreferenceListener);
        worker.execute(()->{
            try{dictionary=new EnglishDictionary(this);}catch(java.io.IOException ignored){}
            try{emojiCatalog=new EmojiCatalog(this);}catch(java.io.IOException ignored){}
            handler.post(()->{refreshSuggestions();if(emoji)rebuild();});
        });
    }
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public View onCreateInputView(){keyboard=new KeyboardView();palette();return keyboard;}
    @Override public void onComputeInsets(InputMethodService.Insets insets){
        super.onComputeInsets(insets);
        if(keyboard!=null&&keyboard.getVisibility()==View.VISIBLE){int[] location=new int[2];keyboard.getLocationInWindow(location);insets.contentTopInsets=location[1];insets.visibleTopInsets=location[1];insets.touchableInsets=InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT;}
    }
    @Override public void onStartInput(EditorInfo info,boolean restarting){
        super.onStartInput(info,restarting);
        // Web and custom editors can restart the same input connection after every edit.
        // Keep the keyboard mode, touch state and cached context for that session.
        boolean same=activeInput&&Objects.equals(editorPackage,info.packageName)&&fieldId==info.fieldId&&inputType==info.inputType;
        if(restarting&&same){scheduleContextRead();return;}
        cancelDictation();
        activeInput=true;editorPackage=info.packageName;fieldId=info.fieldId;inputType=info.inputType;
        revision++;decodingGlide=false;pendingActions.clear();acknowledgements.clear();before.setLength(0);clearCorrection();glideLength=0;
        caret=Math.max(0,Math.min(info.initialSelStart,info.initialSelEnd));selectionLength=Math.abs(info.initialSelEnd-info.initialSelStart);
        int cls=info.inputType&InputType.TYPE_MASK_CLASS,variation=info.inputType&InputType.TYPE_MASK_VARIATION;
        numeric=cls==InputType.TYPE_CLASS_NUMBER||cls==InputType.TYPE_CLASS_PHONE||cls==InputType.TYPE_CLASS_DATETIME;
        rawEditor=info.inputType==InputType.TYPE_NULL;
        terminalEditor=rawEditor||isTerminalEditor(info);
        String fieldDescription=(String.valueOf(info.hintText)+" "+String.valueOf(info.fieldName)).toLowerCase(Locale.US);
        boolean secretHint=fieldDescription.contains("password")||fieldDescription.contains("passcode")||fieldDescription.contains("secret")||fieldDescription.contains("token")||fieldDescription.contains("pin code");
        privateField=cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD&&(!terminalEditor||secretHint)||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            ||cls==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        emailField=cls==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS||variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        uriField=cls==InputType.TYPE_CLASS_TEXT&&variation==InputType.TYPE_TEXT_VARIATION_URI;
        // Local predictions are a user keyboard preference. A host's NO_SUGGESTIONS
        // hint must not also disable gesture entry in ordinary text/search editors.
        allowPredictions=!privateField&&!numeric&&(cls==InputType.TYPE_CLASS_TEXT||rawEditor);
        allowGlide=allowPredictions;
        symbols=second=emoji=caps=manualShift=emojiSearching=false;emojiPage=0;emojiQuery="";lastShift=lastSpace=0;
        if(Build.VERSION.SDK_INT>=30){CharSequence initial=info.getInitialTextBeforeCursor(256,0);if(initial!=null)before.append(initial);}
        updateShift();refreshSuggestions();scheduleContextRead();rebuild();readClipboardSuggestion();
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);palette();updateShift();if(keyboard!=null){if(keyboard.keys.isEmpty())rebuild();else keyboard.requestLayout();}if(!restarting)readClipboardSuggestion();}
    @Override public void onFinishInput(){cancelDictation();activeInput=false;decodingGlide=false;pendingActions.clear();revision++;handler.removeCallbacks(readContext);handler.removeCallbacks(suggestTask);clearCorrection();glideLength=0;super.onFinishInput();}
    @Override public void onFinishInputView(boolean finishing){cancelDictation();decodingGlide=false;pendingActions.clear();revision++;handler.removeCallbacks(readContext);if(keyboard!=null)keyboard.cancelTouches();super.onFinishInputView(finishing);}
    @Override public void onDestroy(){cancelDictation();if(clipboard!=null)clipboard.removePrimaryClipChangedListener(clipboardListener);prefs.unregisterOnSharedPreferenceChangeListener(clipboardPreferenceListener);clipboardText=null;worker.shutdownNow();reader.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onUpdateSelection(int os,int oe,int ns,int ne,int cs,int ce){
        super.onUpdateSelection(os,oe,ns,ne,cs,ce);
        if(ns<0||ne<0)return;
        if(ns==ne&&acknowledgements.contains(ns)){while(!acknowledgements.isEmpty()&&acknowledgements.removeFirst()!=ns){}return;}
        if(ns==caret&&ne==caret+selectionLength)return;
        cancelDictation();
        decodingGlide=false;pendingActions.clear();caret=Math.min(ns,ne);selectionLength=Math.abs(ne-ns);acknowledgements.clear();manualShift=false;clearCorrection();glideLength=0;revision++;
        if(keyboard==null||!keyboard.cursorDragging())scheduleContextRead();
    }
    private void scheduleContextRead(){handler.removeCallbacks(readContext);if(activeInput&&(keyboard==null||!keyboard.cursorDragging()))handler.postDelayed(readContext,220);}
    private void clearClipboardSuggestion(){
        boolean changed=clipboardText!=null;clipboardText=null;clipboardPreview="";clipboardTimestamp=0;handler.removeCallbacks(expireClipboard);
        if(changed&&keyboard!=null)keyboard.clipboardChanged();
    }
    private void dismissClipboardSuggestion(){if(clipboardText!=null)dismissedClipboardTimestamp=clipboardTimestamp;clearClipboardSuggestion();}
    private void readClipboardSuggestion(){
        if(!activeInput||privateField||numeric||clipboard==null||!prefs.getBoolean("clipboardSuggestions",true)){clearClipboardSuggestion();return;}
        try{
            ClipDescription description=clipboard.getPrimaryClipDescription();
            if(description==null||description.getExtras()!=null&&description.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE,false)){clearClipboardSuggestion();return;}
            long stamp=description.getTimestamp(),age=System.currentTimeMillis()-stamp;
            if(stamp<=0||stamp==dismissedClipboardTimestamp||age<0||age>=CLIPBOARD_LIFETIME){clearClipboardSuggestion();return;}
            ClipData clip=clipboard.getPrimaryClip();
            CharSequence value=clip==null||clip.getItemCount()==0?null:clip.getItemAt(0).getText();
            if(value==null||value.length()==0||value.length()>65536){clearClipboardSuggestion();return;}
            String text=value.toString();if(text.trim().isEmpty()){clearClipboardSuggestion();return;}
            // Metadata and contents are separate binder reads. Do not suggest a
            // replacement clip with the timestamp or sensitivity of the old one.
            if(clip.getDescription().getTimestamp()!=stamp||clip.getDescription().getExtras()!=null&&clip.getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE,false)){clearClipboardSuggestion();return;}
            boolean changed=clipboardTimestamp!=stamp||!text.equals(clipboardText);
            clipboardText=text;clipboardTimestamp=stamp;clipboardPreview=text.substring(0,Math.min(120,text.length())).replaceAll("\\s+"," ").trim();
            handler.removeCallbacks(expireClipboard);handler.postDelayed(expireClipboard,CLIPBOARD_LIFETIME-age);
            if(changed&&keyboard!=null)keyboard.clipboardChanged();
        }catch(SecurityException ignored){clearClipboardSuggestion();}
    }
    private boolean clipboardVisible(){
        long age=System.currentTimeMillis()-clipboardTimestamp;
        return dictation==null&&dictationStatus.isEmpty()&&activeInput&&clipboardText!=null&&age>=0&&age<CLIPBOARD_LIFETIME&&prefs.getBoolean("clipboardSuggestions",true)&&allowPredictions&&!privateField&&!numeric&&!emoji&&!emojiSearching&&selectionLength==0&&glideLength==0&&!decodingGlide&&(keyboard==null||!keyboard.cursorDragging())&&prefix().isEmpty();
    }
    private boolean isTerminalEditor(EditorInfo info){
        String name=String.valueOf(info.packageName).toLowerCase(Locale.US);
        if(name.contains("terminus")||name.contains("termius")||name.contains("termux")||name.equals("com.server.auditor.ssh.client"))return true;
        try{String label=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(info.packageName,0)).toString().toLowerCase(Locale.US);return label.equals("terminus")||label.equals("termius")||label.equals("termux");}
        catch(android.content.pm.PackageManager.NameNotFoundException|SecurityException|NullPointerException ignored){return false;}
    }
    private void haptic(boolean longPress){
        if(!prefs.getBoolean("haptic",true))return;
        // Use the app's explicit vibration preference even when Android's general
        // touch feedback is off. No runtime permission prompt is needed for VIBRATE.
        if(vibrator!=null&&vibrator.hasVibrator()){
            vibrator.vibrate(VibrationEffect.createOneShot(KeyboardPreferences.vibrationDuration(prefs,longPress),VibrationEffect.DEFAULT_AMPLITUDE));
        }else if(keyboard!=null)keyboard.performHapticFeedback(longPress?HapticFeedbackConstants.LONG_PRESS:HapticFeedbackConstants.KEYBOARD_TAP);
    }
    private void readContextAsync(){
        InputConnection connection=getCurrentInputConnection();if(connection==null||!activeInput||rawEditor||privateField||keyboard!=null&&keyboard.cursorDragging())return;
        final long token=revision;
        reader.execute(()->{
            CharSequence text;try{text=connection.getTextBeforeCursor(256,0);}catch(RuntimeException ignored){return;}
            if(text==null)return;String snapshot=text.toString();
            handler.post(()->{if(!activeInput||revision!=token||connection!=getCurrentInputConnection())return;if(!before.toString().equals(snapshot)){before.setLength(0);before.append(snapshot);updateShift();refreshSuggestions();}});
        });
    }
    private void palette(){
        int theme=prefs.getInt("theme",3);
        boolean tablet=keyboard!=null?keyboard.wide:getResources().getConfiguration().screenWidthDp>=600;
        boolean dark=theme==0||theme==2&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        boolean lavender=theme==1||theme==3&&tablet;
        int oldBackground=background;
        background=dark?0xff202022:lavender?0xffc7b2d4:0xffdfe0e4;
        keyColor=dark?0xff48484a:lavender?0xffe0cfe9:0xfffefefe;
        special=dark?0xff414144:keyColor;
        foreground=dark?0xfff5f5f7:lavender?0xff493650:0xff171719;
        divider=dark?0xff45454a:lavender?0xffbca7ca:0xffc9cacf;
        if(getWindow()!=null){
            Window w=getWindow().getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setNavigationBarColor(background);
            if(Build.VERSION.SDK_INT>=28)w.setNavigationBarDividerColor(background);
            if(Build.VERSION.SDK_INT>=29)w.setNavigationBarContrastEnforced(false);
            w.getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if(keyboard!=null&&oldBackground!=background)keyboard.invalidate();
    }
    private void rebuild(){if(keyboard!=null){keyboard.arrange();keyboard.requestLayout();keyboard.invalidate();}}
    private void noteEdit(){
        dismissClipboardSuggestion();
        revision++;selectionLength=0;manualShift=false;acknowledgements.addLast(caret);while(acknowledgements.size()>64)acknowledgements.removeFirst();
        if(before.length()>256)before.delete(0,before.length()-256);scheduleContextRead();updateShift();refreshSuggestions();
    }
    private void clearCorrection(){correctionOriginal=correctionReplacement=null;correctionCaret=-1;}
    private void commit(String text){InputConnection ic=getCurrentInputConnection();if(ic==null)return;clearCorrection();glideLength=0;ic.commitText(text,1);before.append(text);caret+=text.length();noteEdit();}
    private void replaceTail(int count,String text){
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;count=Math.min(count,before.length());
        ic.beginBatchEdit();
        if(count>0){
            if(rawEditor){for(int i=0;i<count;i++)sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);}
            else if(!ic.deleteSurroundingText(count,0)){for(int i=0;i<count;i++)sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);}
        }
        ic.commitText(text,1);ic.endBatchEdit();
        before.delete(before.length()-count,before.length());before.append(text);caret=Math.max(0,caret-count)+text.length();noteEdit();
    }
    private void erase(){
        if(decodingGlide){pendingActions.add(this::erase);return;}
        if(emojiSearching){if(!emojiQuery.isEmpty())emojiQuery=emojiQuery.substring(0,emojiQuery.offsetByCodePoints(emojiQuery.length(),-1));keyboard.invalidateHeader();return;}
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        if(correctionOriginal!=null&&caret==correctionCaret&&before.toString().endsWith(correctionReplacement)){
            String original=correctionOriginal;int count=correctionReplacement.length();clearCorrection();replaceTail(count,original);return;
        }
        clearCorrection();glideLength=0;lastSpace=0;
        if(selectionLength>0){sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);noteEdit();return;}
        int count=1;
        if(before.length()>0){graphemes.setText(before.toString());int start=Math.max(0,graphemes.preceding(before.length()));count=before.length()-start;before.delete(start,before.length());}
        // DEL events reach native, WebView, terminal and custom editor key handlers.
        // Multi-code-unit clusters use an atomic surrounding-text delete when available.
        if(!rawEditor&&count>1){if(!ic.deleteSurroundingText(count,0))sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);}
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        caret=Math.max(0,caret-count);noteEdit();
    }
    private void updateShift(){
        boolean previous=shift;if(caps)shift=true;else if(!manualShift){
            boolean next=false;
            if(emojiSearching)next=false;
            else if(prefs.getBoolean("autocaps",true)&&!privateField&&!numeric&&!terminalEditor){
                if((inputType&InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)!=0)next=true;
                else if((inputType&InputType.TYPE_TEXT_FLAG_CAP_WORDS)!=0)next=before.length()==0||Character.isWhitespace(before.charAt(before.length()-1));
                else if((inputType&InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)!=0){int i=before.length()-1;if(i<0||before.charAt(i)=='\n')next=true;else if(Character.isWhitespace(before.charAt(i))){while(i>=0&&Character.isWhitespace(before.charAt(i))){if(before.charAt(i)=='\n'){next=true;break;}i--;}next|=i<0||".!?".indexOf(before.charAt(i))>=0;}}
            }
            shift=next;
        }
        if(previous!=shift&&keyboard!=null)keyboard.invalidateLetters();
    }
    private String prefix(){if(privateField||selectionLength>0)return "";int i=before.length();while(i>0&&(Character.isLetter(before.charAt(i-1))||before.charAt(i-1)=='\''))i--;return before.substring(i);}
    private void refreshSuggestions(){
        if(glideLength>0)return;
        handler.removeCallbacks(suggestTask);
        if(!allowPredictions){suggestions=new String[0];if(keyboard!=null)keyboard.invalidateHeader();return;}
        String p=prefix();
        if(p.isEmpty())suggestions=new String[]{"I",shift?"The":"the","I'm"};
        else suggestions=new String[]{p,suggestions.length>1?suggestions[1]:"",suggestions.length>2?suggestions[2]:""};
        if(keyboard!=null)keyboard.invalidateHeader();if(!p.isEmpty()&&dictionary!=null)handler.postDelayed(suggestTask,65);
    }
    private void computeSuggestions(){
        EnglishDictionary dict=dictionary;if(dict==null||!allowPredictions||emojiSearching||glideLength>0)return;
        String p=prefix();long token=revision;
        worker.execute(()->{if(token!=revision)return;List<String> result=dict.suggest(p);String correction=dict.correct(p);handler.post(()->{if(token!=revision||!activeInput||glideLength>0)return;correctionPrefix=p;cachedCorrection=correction;suggestions=result.toArray(new String[0]);keyboard.invalidateHeader();});});
    }
    private void chooseSuggestion(int i){
        if(dictation!=null){if(i==1)stopDictation();else if(i==2)cancelDictation();return;}
        if(!dictationStatus.isEmpty()){if(i==2)cancelDictation();return;}
        if(clipboardVisible()){
            if(i==2){dismissClipboardSuggestion();return;}
            if(i==0){String text=clipboardText;dismissClipboardSuggestion();commit(text);}return;
        }
        if(i>=suggestions.length||suggestions[i].isEmpty())return;String selected=suggestions[i];int count=glideLength>0?glideLength:prefix().length();
        clearCorrection();glideLength=0;replaceTail(count,selected+" ");
    }
    private boolean canAutoCorrect(String delimiter){
        if(!allowPredictions||emailField||terminalEditor)return false;
        int start=before.length();while(start>0&&!Character.isWhitespace(before.charAt(start-1)))start--;
        String token=before.substring(start);
        if(uriField&&(".".equals(delimiter)||":".equals(delimiter)||"/".equals(delimiter)))return false;
        // Keep typed addresses, paths, identifiers and mixed letter/number tokens literal.
        if(token.indexOf('@')>=0||token.indexOf('/')>=0||token.indexOf('\\')>=0||token.indexOf('_')>=0||token.indexOf(':')>=0||token.startsWith("www.")||token.matches(".*[a-zA-Z]\\.[a-zA-Z].*"))return false;
        for(int i=0;i<token.length();i++)if(Character.isDigit(token.charAt(i)))return false;
        return true;
    }
    private void moveCursor(int horizontal,int vertical){
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        clearCorrection();glideLength=0;lastSpace=0;handler.removeCallbacks(readContext);handler.removeCallbacks(suggestTask);
        boolean multiline=(inputType&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0||rawEditor;
        ic.beginBatchEdit();
        for(int i=0;i<Math.min(24,Math.abs(horizontal));i++)sendDownUpKeyEvents(horizontal<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT);
        if(multiline)for(int i=0;i<Math.min(8,Math.abs(vertical));i++)sendDownUpKeyEvents(vertical<0?KeyEvent.KEYCODE_DPAD_UP:KeyEvent.KEYCODE_DPAD_DOWN);
        ic.endBatchEdit();
    }
    private void boundary(String delimiter){
        if(!canAutoCorrect(delimiter)||!prefs.getBoolean("autocorrect",true)||dictionary==null||selectionLength>0){commit(delimiter);return;}
        String p=prefix();
        if(p.isEmpty()){commit(delimiter);return;}
        if(p.equals(correctionPrefix)){applyCorrection(p,cachedCorrection,delimiter);return;}
        // A fast typist can reach a word boundary before its suggestions finish.
        // Buffer later key presses while the same offline worker resolves that word.
        long token=revision;decodingGlide=true;handler.removeCallbacks(readContext);EnglishDictionary dict=dictionary;
        worker.execute(()->{String fixed=dict.correct(p);handler.post(()->{if(token!=revision||!activeInput)return;decodingGlide=false;applyCorrection(p,fixed,delimiter);drainPendingActions();});});
    }
    private void applyCorrection(String p,String fixed,String delimiter){
        if(!fixed.equals(p)){clearCorrection();glideLength=0;replaceTail(p.length(),fixed+delimiter);correctionOriginal=p;correctionReplacement=fixed+delimiter;correctionCaret=caret;}
        else commit(delimiter);
    }
    private void finishGlide(float[] path,Map<Character,float[]> centers,float keyWidth,boolean upper){
        cancelDictation();
        dismissClipboardSuggestion();
        if(decodingGlide){pendingActions.add(()->finishGlide(path,centers,keyWidth,upper));return;}
        EnglishDictionary dict=dictionary;if(dict==null)return;long token=revision;decodingGlide=true;handler.removeCallbacks(readContext);
        worker.execute(()->{List<String> result=dict.glide(path,centers,keyWidth);handler.post(()->{
            if(token!=revision||!activeInput)return;
            decodingGlide=false;
            if(result.isEmpty()){drainPendingActions();return;}
            String lead=before.length()>0&&Character.isLetterOrDigit(before.charAt(before.length()-1))?" ":"";
            String first=upper?EnglishDictionary.matchCase(result.get(0),"A"):result.get(0);
            commit(lead+first+" ");glideLength=first.length()+1;
            suggestions=result.stream().map(w->upper?EnglishDictionary.matchCase(w,"A"):w).toArray(String[]::new);keyboard.invalidateHeader();drainPendingActions();
        });});
    }
    private void drainPendingActions(){while(!decodingGlide&&!pendingActions.isEmpty())pendingActions.removeFirst().run();}
    private String actionLabel(){EditorInfo e=getCurrentInputEditorInfo();if(emojiSearching)return "search";if(e==null||(e.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)!=0)return "Return";return switch(e.imeOptions&EditorInfo.IME_MASK_ACTION){case EditorInfo.IME_ACTION_GO->"go";case EditorInfo.IME_ACTION_SEARCH->"search";case EditorInfo.IME_ACTION_SEND->"send";case EditorInfo.IME_ACTION_NEXT->"next";case EditorInfo.IME_ACTION_DONE->"done";default->"Return";};}
    private void searchEmoji(){emojiSearching=true;emoji=false;symbols=false;updateShift();rebuild();}
    private void showEmojiResults(){emojiSearching=false;emoji=true;emojiPage=0;rebuild();}
    private void press(String value){
        if(!value.equals("voice")&&!value.startsWith("suggest:"))cancelDictation();
        if(decodingGlide){pendingActions.add(()->press(value));return;}
        if(value.startsWith("text:")){String text=value.substring(5);lastShift=lastSpace=0;if(emojiSearching){emojiQuery+=text.toLowerCase(Locale.US);keyboard.invalidateHeader();}else commit(text);return;}
        if(value.startsWith("suggest:")){chooseSuggestion(Integer.parseInt(value.substring(8)));return;}
        if(value.startsWith("cat:")){emojiCategory=Integer.parseInt(value.substring(4));emojiPage=0;emojiQuery="";rebuild();return;}
        if(value.startsWith("em:")){String text=value.substring(3);commit(text);if(emojiCatalog!=null)emojiCatalog.used(text,prefs);return;}
        switch(value){
            case "shift":{long now=SystemClock.uptimeMillis();if(caps)caps=shift=false;else if(now-lastShift<350)caps=shift=true;else shift=!shift;lastShift=now;manualShift=true;keyboard.invalidateLetters();return;}
            case "delete":erase();return;
            case "123":symbols=true;emoji=numeric=false;rebuild();return;
            case "ABC":symbols=emoji=numeric=emojiSearching=false;emojiQuery="";rebuild();return;
            case "more":second=!second;rebuild();return;
            case "voice":startVoiceInput();return;
            case "emoji":emoji=true;emojiSearching=false;emojiPage=0;emojiQuery="";symbols=numeric=false;rebuild();return;
            case "emojinext":if(emojiPage+1<keyboard.emojiPages){emojiPage++;rebuild();}return;
            case "emojiprev":if(emojiPage>0){emojiPage--;rebuild();}return;
            case "emojisearch":searchEmoji();return;
            case "emojidone":showEmojiResults();return;
            case "emojiquery":return;
            case "space":{
                if(emojiSearching){emojiQuery+=" ";keyboard.invalidateHeader();return;}
                long now=SystemClock.uptimeMillis();int n=before.length();
                if(glideLength>0){glideLength=0;refreshSuggestions();lastSpace=now;return;}
                if(prefs.getBoolean("doubleSpacePeriod",true)&&!privateField&&!terminalEditor&&!emailField&&!uriField&&now-lastSpace<350&&n>=2&&before.charAt(n-1)==' '&&Character.isLetterOrDigit(before.charAt(n-2))){clearCorrection();replaceTail(1,". ");lastSpace=0;}
                else{boundary(" ");lastSpace=now;}return;
            }
            case "return":{
                dismissClipboardSuggestion();
                if(emojiSearching){showEmojiResults();return;}
                EditorInfo e=getCurrentInputEditorInfo();InputConnection ic=getCurrentInputConnection();if(ic==null)return;
                int action=e==null?0:e.imeOptions&EditorInfo.IME_MASK_ACTION;
                if(e!=null&&(e.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0&&action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){ic.performEditorAction(action);clearCorrection();glideLength=0;}
                else if(rawEditor)sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);else boundary("\n");return;
            }
            default:{
                lastShift=lastSpace=0;
                String text=!symbols&&!emoji&&shift?value.toUpperCase(Locale.US):value;
                if(emojiSearching){emojiQuery+=text.toLowerCase(Locale.US);keyboard.invalidateHeader();return;}
                if(!symbols&&".,!?;:".contains(text)&&text.length()==1){if(glideLength>0&&before.length()>0&&before.charAt(before.length()-1)==' '){glideLength=0;replaceTail(1,text);}else boundary(text);}
                else commit(text);
            }
        }
    }
    private void startVoiceInput(){
        if(dictation!=null){stopDictation();return;}
        cancelDictation();
        if(!activeInput||getCurrentInputConnection()==null||!isInputViewShown())return;
        if(privateField||numeric||!allowPredictions){Toast.makeText(this,"Dictation is unavailable in this field",Toast.LENGTH_SHORT).show();return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            try{startActivity(new Intent(this,MicrophonePermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
            catch(RuntimeException unavailable){Toast.makeText(this,"Allow microphone access in SoftKeys settings",Toast.LENGTH_LONG).show();}
            return;
        }
        if(!android.speech.SpeechRecognizer.isRecognitionAvailable(this)){showDictationError("No speech recognition service is installed");return;}
        dismissClipboardSuggestion();clearCorrection();glideLength=0;lastSpace=lastShift=0;
        final long token=revision;final InputConnection connection=getCurrentInputConnection();
        dictationFinishing=false;dictationStatus="Starting microphone…";
        DictationSession session=new DictationSession(this,new DictationSession.Listener(){
            private boolean current(DictationSession session){return dictation==session&&activeInput&&isInputViewShown()&&revision==token&&connection==getCurrentInputConnection();}
            public void status(DictationSession session,String text,boolean finishing){
                if(!current(session)){if(dictation==session)cancelDictation();return;}
                dictationStatus=text;dictationFinishing=finishing;voiceChanged();
            }
            public void result(DictationSession session,String text){
                if(!current(session)){if(dictation==session)cancelDictation();return;}
                cancelDictation();
                if(text.isEmpty())return;
                boolean space=!terminalEditor&&!emailField&&!uriField;
                char previous=before.length()==0?' ':before.charAt(before.length()-1);
                String lead=space&&selectionLength==0&&!Character.isWhitespace(previous)&&(Character.isLetterOrDigit(previous)||".!?".indexOf(previous)>=0)?" ":"";
                commit(lead+text+(space?" ":""));
            }
            public void error(DictationSession session,String message){if(dictation!=session)return;showDictationError(message);}
        });
        dictation=session;voiceChanged();session.start();
    }
    private void stopDictation(){if(dictation!=null)dictation.stop();}
    private void cancelDictation(){
        boolean changed=dictation!=null||!dictationStatus.isEmpty();
        DictationSession previous=dictation;dictation=null;dictationStatus="";dictationFinishing=false;handler.removeCallbacks(clearDictationNotice);
        if(previous!=null)previous.close();if(changed)voiceChanged();
    }
    private void showDictationError(String message){
        cancelDictation();dictationStatus=message;voiceChanged();handler.postDelayed(clearDictationNotice,8_000);
        if(keyboard!=null)keyboard.announceForAccessibility(message);
    }
    private void voiceChanged(){if(keyboard!=null){keyboard.clipboardChanged();for(Key key:keyboard.keys)if(key.value.equals("voice"))keyboard.invalidateKey(key);}}
    private String accents(String s){return switch(s){case "a"->"à á â ä æ ã å ā";case "e"->"è é ê ë ē";case "i"->"ì í î ï ī";case "o"->"ò ó ô ö õ ø œ ō";case "u"->"ù ú û ü ū";case "n"->"ñ ń";case "c"->"ç ć č";case "s"->"ß ś š";case "y"->"ý ÿ";default->"";};}
    private void showVariants(Key key,List<String> values,boolean replace){
        LinearLayout row=new LinearLayout(this);row.setPadding(dp(4),dp(4),dp(4),dp(4));android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(special);bg.setCornerRadius(dp(10));row.setBackground(bg);
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.addView(row);
        PopupWindow popup=new PopupWindow(scroll,Math.min(dp(values.size()*40+8),keyboard.getWidth()-dp(12)),dp(58),true);popup.setBackgroundDrawable(bg);popup.setElevation(dp(6));
        for(String text:values){TextView button=new TextView(this);button.setText(text);button.setTextColor(foreground);button.setTextSize(25);button.setGravity(Gravity.CENTER);button.setContentDescription(text);row.addView(button,new LinearLayout.LayoutParams(dp(40),-1));button.setOnClickListener(v->{if(replace)erase();if(emojiSearching){emojiQuery+=text;keyboard.invalidateHeader();}else commit(text);if(emoji&&emojiCatalog!=null)emojiCatalog.used(text,prefs);popup.dismiss();});}
        int[] xy=new int[2];keyboard.getLocationInWindow(xy);popup.showAtLocation(keyboard,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,Math.max(dp(8),xy[1]+(int)key.box.top-dp(60)));
    }

    private static class Key {
        final String value;final RectF box;final boolean action;
        Key(String value,float l,float t,float r,float b,boolean action){this.value=value;box=new RectF(l,t,r,b);this.action=action;}
    }
    private final class KeyboardView extends View {
        private final ArrayList<Key> keys=new ArrayList<>();
        private final android.util.SparseArray<Touch> touches=new android.util.SparseArray<>();
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),trailPaint=new Paint(Paint.ANTI_ALIAS_FLAG),trailShadow=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface normal=Typeface.create("sans-serif",Typeface.NORMAL);
        private final RectF rect=new RectF();private final Path iconPath=new Path(),trail=new Path(),surface=new Path();
        private int navBottom,hover=-1,focused=-1,emojiPages=1,cursorPointer=-1;
        private float rowH,suggestionH,width,categoryH,contentLeft,contentWidth,keyGapX,keyGapY,keyRadius,topRadius;
        private boolean wide,shortScreen;
        private int layoutWidth=-1,layoutHeight=-1;
        private List<EmojiCatalog.Item> visibleEmoji=Collections.emptyList();
        private final Nodes nodes=new Nodes();
        private PopupWindow keyPreview;
        private TextView previewText;
        private int previewPointer=-1;
        private final Runnable dismissPreview=this::hidePreview;
        KeyboardView(){super(IosKeyboardService.this);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);setBackgroundColor(Color.TRANSPARENT);trailPaint.setColor(0xfffffdfa);trailPaint.setStrokeCap(Paint.Cap.ROUND);trailPaint.setStrokeJoin(Paint.Join.ROUND);trailPaint.setStyle(Paint.Style.STROKE);trailShadow.set(trailPaint);trailShadow.setColor(0xff504b55);}
        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets){int bottom=insets.getSystemWindowInsetBottom();if(bottom!=navBottom){navBottom=bottom;requestLayout();}return insets;}
        @Override protected void onMeasure(int ws,int hs){
            width=MeasureSpec.getSize(ws);Configuration config=getResources().getConfiguration();
            float density=getResources().getDisplayMetrics().density,widthDp=width/density;
            shortScreen=config.screenHeightDp<500||config.orientation==Configuration.ORIENTATION_LANDSCAPE&&config.screenHeightDp<600;
            boolean wasWide=wide;wide=widthDp>=600;
            if(wide){
                rowH=dp(shortScreen?36:42);suggestionH=dp(shortScreen?27:32);
                contentLeft=Math.min(dp(26),width*.035f);
                keyGapX=dp(3.5f);keyGapY=dp(4.5f);keyRadius=dp(10);topRadius=dp(22);
            }else{
                rowH=dp(shortScreen?38:Math.max(44,Math.min(50,widthDp*.113f)));
                suggestionH=dp(shortScreen?30:42);
                contentLeft=width*.03f;
                keyGapX=Math.max(dp(2),width*.0058f);keyGapY=dp(shortScreen?4.5f:5.5f);
                keyRadius=dp(9.5f);topRadius=dp(24);
            }
            categoryH=dp(34);contentWidth=width-2*contentLeft;
            int height=Math.round(suggestionH+rowH*(4+numberRowOffset())+(emoji?categoryH:0)+navBottom+dp(4));
            setMeasuredDimension((int)width,height);
            if(wasWide!=wide)palette();
            if(layoutWidth!=(int)width||layoutHeight!=height){
                layoutWidth=(int)width;layoutHeight=height;
                surface.reset();surface.addRoundRect(0,0,width,height,new float[]{topRadius,topRadius,topRadius,topRadius,0,0,0,0},Path.Direction.CW);
                arrange();
            }
        }
        private void arrange(){
            hidePreview();
            keys.clear();if(width<=0)return;
            if(emojiSearching){keys.add(new Key("emojiquery",0,0,width*.8f,suggestionH,true));keys.add(new Key("emojidone",width*.8f,0,width,suggestionH,true));}
            else if(!numeric&&!emoji&&allowPredictions){for(int i=0;i<3;i++)keys.add(new Key("suggest:"+i,width*i/3,0,width*(i+1)/3,suggestionH,true));}
            if(numeric){float w=Math.min(width,dp(420)),l=(width-w)/2;row(new String[]{"1","2","3"},0,l,w,null);row(new String[]{"4","5","6"},1,l,w,null);row(new String[]{"7","8","9"},2,l,w,null);row(new String[]{".","0","delete","return"},3,l,w,null);}
            else if(emoji){
                keys.add(new Key("emojisearch",0,0,width,suggestionH,true));
                visibleEmoji=emojiCatalog==null?Collections.emptyList():emojiCatalog.select(emojiCategory,emojiQuery,prefs);
                int cols=Math.max(7,(int)(contentWidth/dp(45))),pageSize=cols*3;emojiPages=Math.max(1,(visibleEmoji.size()+pageSize-1)/pageSize);emojiPage=Math.max(0,Math.min(emojiPage,emojiPages-1));int offset=emojiPage*pageSize;
                for(int i=0;i<pageSize&&i+offset<visibleEmoji.size();i++){EmojiCatalog.Item item=visibleEmoji.get(i+offset);keys.add(new Key("em:"+item.emoji,contentLeft+contentWidth*(i%cols)/cols,suggestionH+(i/cols)*rowH,contentLeft+contentWidth*((i%cols)+1)/cols,suggestionH+(i/cols+1)*rowH,false));}
                float top=suggestionH+rowH*3;for(int i=0;i<EmojiCatalog.NAMES.length;i++)keys.add(new Key("cat:"+i,width*i/10,top,width*(i+1)/10,top+categoryH,true));
                rowAt(new String[]{"ABC","emojiprev","emojinext","space","delete","return"},top+categoryH,contentLeft,contentWidth,new float[]{1.5f,1,1,3.5f,1.5f,1.5f});
            }else{
                if(numberRowOffset()>0)rowAt(chars("1234567890"),suggestionH,contentLeft,contentWidth,null);
                if(!symbols){
                    row(chars("qwertyuiop"),0,contentLeft,contentWidth,null);
                    row(chars("asdfghjkl"),1,contentLeft+contentWidth*.05f,contentWidth*.9f,null);
                    row(new String[]{"shift","z","x","c","v","b","n","m","delete"},2,contentLeft,contentWidth,new float[]{1.5f,1,1,1,1,1,1,1,1.5f});
                }else{
                    row(second?new String[]{"[","]","{","}","#","%","^","*","+","="}:chars(numberRowOffset()>0?"!@#$%^&*()":"1234567890"),0,contentLeft,contentWidth,null);
                    row(second?new String[]{"_","\\","|","~","<",">","€","£","¥","•"}:numberRowOffset()>0?new String[]{"-","/",":",";","[","]","+","=","_","\""}:new String[]{"-","/",":",";","(",")","$","&","@","\""},1,contentLeft,contentWidth,null);
                    row(new String[]{"more",".",",","?","!","'","delete"},2,contentLeft,contentWidth,new float[]{1.7f,1,1,1,1,1,1.5f});
                }
                row(new String[]{"emoji",symbols?"ABC":"123","space","voice","return"},3,contentLeft,contentWidth,wide?new float[]{.7f,1.5f,5.6f,.7f,1.5f}:new float[]{1.1f,2,3.8f,1.1f,2});
            }
            invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        private String[] chars(String text){String[] a=new String[text.length()];for(int i=0;i<a.length;i++)a[i]=text.substring(i,i+1);return a;}
        private int numberRowOffset(){return wide&&!numeric&&!emoji&&prefs.getBoolean("tabletNumberRow",true)?1:0;}
        private void row(String[] labels,int n,float x,float w,float[] weights){rowAt(labels,suggestionH+(n+numberRowOffset())*rowH,x,w,weights);}
        private void rowAt(String[] labels,float y,float x,float w,float[] weights){
            float total=0;for(int i=0;i<labels.length;i++)total+=weights==null?1:weights[i];
            for(int i=0;i<labels.length;i++){float end=x+w*(weights==null?1:weights[i])/total;String s=labels[i];keys.add(new Key(s,x,y,end,y+rowH,s.length()>1&&!s.equals("space")));x=end;}
        }
        void invalidateHeader(){invalidate(0,0,getWidth(),(int)suggestionH+1);}
        void clipboardChanged(){
            if(focused>=0&&focused<keys.size()&&!keyVisible(keys.get(focused)))focused=-1;
            invalidateHeader();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        private boolean keyVisible(Key key){return !(clipboardVisible()||dictation==null&&!dictationStatus.isEmpty())||!key.value.equals("suggest:1");}
        private void keyBounds(Key key,RectF bounds){
            bounds.set(key.box);
            if(dictation!=null){
                if(key.value.equals("suggest:0"))bounds.right=width*.62f;
                else if(key.value.equals("suggest:1")){bounds.left=width*.62f;bounds.right=width*.81f;}
                else if(key.value.equals("suggest:2"))bounds.left=width*.81f;
            }else if(clipboardVisible()||!dictationStatus.isEmpty()){
                if(key.value.equals("suggest:0"))bounds.right=width-dp(48);
                else if(key.value.equals("suggest:2"))bounds.left=width-dp(48);
            }
        }
        void invalidateLetters(){invalidate(0,(int)suggestionH,getWidth(),Math.round(suggestionH+rowH*(3+numberRowOffset())));}
        private void invalidateKey(Key key){invalidate((int)key.box.left-dp(2),(int)key.box.top-dp(2),(int)key.box.right+dp(2),(int)key.box.bottom+dp(2));}
        private boolean letter(Key k){return k!=null&&k.value.length()==1&&k.value.charAt(0)>='a'&&k.value.charAt(0)<='z'&&!symbols&&!emoji&&!numeric;}
        private String label(Key k){
            if(cursorDragging())return "";
            if(k.value.startsWith("suggest:")){int i=Integer.parseInt(k.value.substring(8));if(dictation!=null)return i==0?dictationStatus:i==1?dictationFinishing?"Finishing":"Stop":"Cancel";if(!dictationStatus.isEmpty())return i==0?dictationStatus:i==2?"Dismiss dictation message":"";if(clipboardVisible())return i==0?"Paste "+clipboardPreview:i==2?"Dismiss clipboard suggestion":"";return i<suggestions.length?suggestions[i]:"";}
            if(k.value.startsWith("em:"))return k.value.substring(3);
            if(k.value.startsWith("cat:"))return EmojiCatalog.ICONS[Integer.parseInt(k.value.substring(4))];
            return switch(k.value){case "shift"->caps?"Caps lock":"Shift";case "delete"->"Delete";case "space"->"Space";case "emoji"->"Emoji";case "voice"->dictation!=null?"Stop dictation":"Start dictation";case "return"->actionLabel();case "more"->second?"123":"#+=";case "emojinext"->"›";case "emojiprev"->"‹";case "emojisearch"->(emojiQuery.isEmpty()?EmojiCatalog.NAMES[emojiCategory]:"\""+emojiQuery+"\"")+"   "+(emojiPage+1)+"/"+emojiPages+"   ⌕";case "emojiquery"->emojiQuery.isEmpty()?"Search emoji":emojiQuery;case "emojidone"->"Search";default->!symbols&&!emoji&&shift?k.value.toUpperCase(Locale.US):k.value;};
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.save();canvas.clipPath(surface);paint.setColor(background);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);canvas.drawPath(surface,paint);paint.setTypeface(normal);
            for(Key k:keys){
                drawKey(canvas,k);
            }
            if(emoji&&visibleEmoji.isEmpty()){paint.setColor(foreground);paint.setAlpha(180);paint.setTextSize(dp(16));drawText(canvas,emojiCatalog==null?"Loading emoji…":"No emoji found",width/2,suggestionH+rowH*1.5f);}
            for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(!t.gliding)continue;trail.reset();for(int j=0;j<t.points.size();j+=2){if(j==0)trail.moveTo(t.points.get(j),t.points.get(j+1));else trail.lineTo(t.points.get(j),t.points.get(j+1));}trailShadow.setStrokeWidth(dp(6));trailShadow.setAlpha(35);canvas.drawPath(trail,trailShadow);trailPaint.setStrokeWidth(dp(4));trailPaint.setAlpha(215);canvas.drawPath(trail,trailPaint);}
            if(focused>=0&&focused<keys.size()&&keyVisible(keys.get(focused))){paint.setColor(0xff69a9ff);paint.setAlpha(255);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));keyBounds(keys.get(focused),rect);canvas.drawRoundRect(rect,keyRadius,keyRadius,paint);paint.setStyle(Paint.Style.FILL);}
            canvas.restore();
        }
        private void drawKey(Canvas canvas,Key k){
            if(!keyVisible(k))return;
            String label=label(k);boolean suggestion=k.value.startsWith("suggest:"),category=k.value.startsWith("cat:"),em=k.value.startsWith("em:");
            boolean pressed=false;for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.highlight==k&&!t.gliding)pressed=true;}
            paint.setStyle(Paint.Style.FILL);paint.setColor(foreground);paint.setAlpha(255);
            if(suggestion&&!dictationStatus.isEmpty()){
                keyBounds(k,rect);paint.setTextSize(dp(wide?13:14));
                String status=android.text.TextUtils.ellipsize(dictation==null&&k.value.equals("suggest:2")?"×":label,new android.text.TextPaint(paint),Math.max(1,rect.width()-dp(16)),android.text.TextUtils.TruncateAt.END).toString();
                paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(status,rect.centerX(),rect.centerY()-(paint.ascent()+paint.descent())/2,paint);return;
            }
            if(suggestion&&clipboardVisible()){
                keyBounds(k,rect);
                if(k.value.equals("suggest:0")){
                    rect.inset(dp(10),dp(4));paint.setColor(pressed?blend(keyColor,foreground,.12f):keyColor);canvas.drawRoundRect(rect,dp(12),dp(12),paint);
                    paint.setColor(foreground);paint.setTextSize(dp(wide?14:15));paint.setTextAlign(Paint.Align.LEFT);
                    String preview=android.text.TextUtils.ellipsize("Paste  "+clipboardPreview,new android.text.TextPaint(paint),Math.max(1,rect.width()-dp(24)),android.text.TextUtils.TruncateAt.END).toString();
                    canvas.drawText(preview,rect.left+dp(12),rect.centerY()-(paint.ascent()+paint.descent())/2,paint);
                }else{paint.setTextSize(dp(22));drawText(canvas,"×",rect.centerX(),rect.centerY());}return;
            }
            if(suggestion||k.box.top==0){
                paint.setTextSize(dp(emoji?14:wide?14:15.5f));drawText(canvas,label,k.box.centerX(),k.box.centerY());
                if(suggestion&&!k.value.equals("suggest:2")){paint.setColor(divider);canvas.drawRect(k.box.right-dp(.5f),dp(10),k.box.right+dp(.5f),suggestionH-dp(10),paint);}return;
            }
            if(category){int i=Integer.parseInt(k.value.substring(4));if(i==emojiCategory&&emojiQuery.isEmpty()){paint.setColor(special);rect.set(k.box);rect.inset(dp(3),dp(2));canvas.drawRoundRect(rect,dp(6),dp(6),paint);}paint.setColor(foreground);paint.setTextSize(dp(20));drawText(canvas,label,k.box.centerX(),k.box.centerY());return;}
            rect.set(k.box);rect.inset(keyGapX,keyGapY);
            boolean bare=k.value.equals("emoji")||k.value.equals("voice");
            // iPhone Shift and Backspace leave a wider gap beside the seven letters.
            if(!wide&&!symbols&&!emoji&&!numeric&&(k.value.equals("shift")||k.value.equals("delete"))){
                float face=(contentWidth/10-2*keyGapX)*1.31f;
                if(k.value.equals("shift"))rect.right=rect.left+face;else rect.left=rect.right-face;
            }
            int color=k.value.equals("shift")&&shift?0xfffefefe:k.action?special:keyColor;
            if((!em&&!bare)||pressed){paint.setColor(pressed?blend(color,foreground,.12f):color);canvas.drawRoundRect(rect,keyRadius,keyRadius,paint);}
            if(cursorDragging())return;
            float cx=rect.centerX(),cy=rect.centerY();
            paint.setColor(k.value.equals("shift")&&shift?0xff171719:foreground);paint.setAlpha(bare?180:255);
            float size=em?dp(shortScreen?27:31):dp(wide?19:shortScreen?20:21);
            paint.setTextSize(em?size:k.value.equals("return")||label.length()>3?dp(15):label.equals("123")||label.equals("ABC")||label.equals("#+=")?dp(16):size);
            if(k.value.equals("shift")||k.value.equals("delete")||bare||k.value.equals("return")&&label.equals("Return"))drawIcon(canvas,k.value,cx,cy,dp(bare?23:k.value.equals("return")?25:22));
            else if(!k.value.equals("space"))drawText(canvas,label,cx,cy);
            if(em&&emojiCatalog!=null){EmojiCatalog.Item item=emojiCatalog.byEmoji.get(label);if(item!=null&&!item.variants.isEmpty()){paint.setColor(foreground);paint.setAlpha(100);canvas.drawCircle(k.box.centerX(),k.box.bottom-dp(3),dp(1),paint);}}
        }
        private int blend(int a,int b,float f){return Color.rgb(Math.round(Color.red(a)*(1-f)+Color.red(b)*f),Math.round(Color.green(a)*(1-f)+Color.green(b)*f),Math.round(Color.blue(a)*(1-f)+Color.blue(b)*f));}
        private void drawText(Canvas c,String s,float x,float y){paint.setTextAlign(Paint.Align.CENTER);float max=width/3-dp(16);if(s.length()>20&&paint.measureText(s)>max)s=android.text.TextUtils.ellipsize(s,new android.text.TextPaint(paint),max,android.text.TextUtils.TruncateAt.END).toString();c.drawText(s,x,y-(paint.ascent()+paint.descent())/2,paint);}
        private void drawIcon(Canvas c,String name,float x,float y,float size) {
            c.save();c.translate(x-size/2,y-size/2);c.scale(size/24,size/24);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);iconPath.reset();
            switch(name) {
                case "shift":iconPath.moveTo(12,3);iconPath.lineTo(3,12);iconPath.lineTo(8,12);iconPath.lineTo(8,21);iconPath.lineTo(16,21);iconPath.lineTo(16,12);iconPath.lineTo(21,12);iconPath.close();if(shift)paint.setStyle(Paint.Style.FILL);c.drawPath(iconPath,paint);paint.setStyle(Paint.Style.STROKE);if(caps)c.drawLine(8,23,16,23,paint);break;
                case "delete":iconPath.moveTo(9,5);iconPath.lineTo(22,5);iconPath.lineTo(22,19);iconPath.lineTo(9,19);iconPath.lineTo(2,12);iconPath.close();c.drawPath(iconPath,paint);c.drawLine(12,9,18,15,paint);c.drawLine(18,9,12,15,paint);break;
                case "emoji":c.drawCircle(12,12,9.5f,paint);c.drawArc(6,7,18,18,22,136,false,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(8.5f,9,1.1f,paint);c.drawCircle(15.5f,9,1.1f,paint);break;
                case "voice":if(dictation!=null){paint.setStyle(Paint.Style.FILL);c.drawRoundRect(5,5,19,19,3,3,paint);break;}c.drawRoundRect(9,2,15,15,3,3,paint);c.drawArc(6,7,18,19,0,180,false,paint);c.drawLine(12,19,12,23,paint);c.drawLine(8,23,16,23,paint);break;
                case "return":iconPath.moveTo(20,5);iconPath.lineTo(20,11);iconPath.quadTo(20,15,16,15);iconPath.lineTo(4,15);iconPath.moveTo(9,10);iconPath.lineTo(4,15);iconPath.lineTo(9,20);c.drawPath(iconPath,paint);break;
            }
            paint.setStyle(Paint.Style.FILL);c.restore();
        }
        private Key hit(float x,float y){for(Key k:keys){if(!keyVisible(k))continue;keyBounds(k,rect);if(rect.contains(x,y))return k;}return null;}
        private void showPreview(Touch touch,int pointer){
            handler.removeCallbacks(dismissPreview);
            Key key=touch.key;
            if(!prefs.getBoolean("keyPopups",true)||key.value.codePointCount(0,key.value.length())!=1||cursorDragging()){hidePreview();return;}
            if(keyPreview==null){
                previewText=new TextView(IosKeyboardService.this);previewText.setGravity(Gravity.CENTER);previewText.setTextSize(32);previewText.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                keyPreview=new PopupWindow(previewText,dp(58),dp(70),false);keyPreview.setTouchable(false);keyPreview.setOutsideTouchable(false);keyPreview.setClippingEnabled(true);keyPreview.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);keyPreview.setElevation(dp(5));keyPreview.setAnimationStyle(0);
            }
            previewText.setText(letter(key)&&touch.upper?key.value.toUpperCase(Locale.US):key.value);previewText.setTextColor(foreground);
            android.graphics.drawable.GradientDrawable bubble=new android.graphics.drawable.GradientDrawable();bubble.setColor(keyColor);bubble.setCornerRadius(dp(13));bubble.setStroke(dp(.5f),divider);keyPreview.setBackgroundDrawable(bubble);
            int w=Math.max(dp(52),Math.min(dp(80),Math.round(key.box.width()+dp(12)))),h=dp(70);
            int[] xy=new int[2];getLocationInWindow(xy);int x=xy[0]+Math.max(dp(3),Math.min(getWidth()-w-dp(3),Math.round(key.box.centerX()-w/2f))),y=Math.max(dp(4),xy[1]+Math.round(key.box.top)-h+dp(10));
            try{if(keyPreview.isShowing())keyPreview.update(x,y,w,h);else{keyPreview.setWidth(w);keyPreview.setHeight(h);keyPreview.showAtLocation(this,Gravity.TOP|Gravity.LEFT,x,y);}previewPointer=pointer;}
            catch(WindowManager.BadTokenException ignored){hidePreview();}
        }
        private void hidePreview(){handler.removeCallbacks(dismissPreview);previewPointer=-1;if(keyPreview!=null&&keyPreview.isShowing())keyPreview.dismiss();}
        @Override protected void onDetachedFromWindow(){hidePreview();super.onDetachedFromWindow();}
        private final class Touch {
            final Key key;Key highlight;final float x,y;final boolean upper;boolean held,committed,gliding,multi,cursor;
            float lastX,lastY,cursorX,cursorY;
            final ArrayList<Float> points=new ArrayList<>();Runnable hold;
            Touch(Key key,float x,float y){this.key=highlight=key;this.x=lastX=x;this.y=lastY=y;upper=shift;points.add(x);points.add(y);}
            void point(float x,float y){int n=points.size();if(n<2||Math.hypot(x-points.get(n-2),y-points.get(n-1))>=dp(2)){points.add(x);points.add(y);}}
        }
        private boolean cursorDragging(){return cursorPointer>=0;}
        private void startCursorDrag(Touch t,int id){
            if(touches.get(id)!=t)return;
            if(decodingGlide){pendingActions.add(()->startCursorDrag(t,id));return;}
            cancelDictation();
            t.held=t.cursor=true;cursorPointer=id;dismissClipboardSuggestion();revision++;acknowledgements.clear();clearCorrection();glideLength=0;lastSpace=0;hidePreview();
            handler.removeCallbacks(readContext);handler.removeCallbacks(suggestTask);
            haptic(true);invalidate();
        }
        private void finishCursorDrag(){
            cursorPointer=-1;revision++;acknowledgements.clear();clearCorrection();glideLength=0;
            // A host may have moved across a whole grapheme or a line. Read its final
            // context rather than guessing a UTF-16 cursor position from pixel motion.
            before.setLength(0);refreshSuggestions();handler.removeCallbacks(readContext);handler.post(readContext);invalidate();
        }
        private void commitTouch(Touch t){if(t.committed||t.held)return;t.committed=true;if(letter(t.key))press("text:"+(t.upper?t.key.value.toUpperCase(Locale.US):t.key.value));else press(t.key.value);}
        private void hold(Touch t,int id){
            String value=t.key.value;
            if(value.equals("delete"))t.hold=new Runnable(){public void run(){if(touches.get(id)!=t)return;t.held=true;erase();handler.postDelayed(this,50);}};
            else if(value.equals("space"))t.hold=()->startCursorDrag(t,id);
            else if(value.equals("shift"))t.hold=()->{t.held=true;caps=shift=true;haptic(true);invalidateLetters();};
            else if(letter(t.key)&&!accents(value).isEmpty())t.hold=()->{if(t.committed||t.gliding)return;t.held=true;hidePreview();haptic(true);List<String> variants=new ArrayList<>();for(String text:accents(value).split(" "))variants.add(t.upper?text.toUpperCase(Locale.US):text);showVariants(t.key,variants,false);};
            else if(value.startsWith("em:")&&emojiCatalog!=null){EmojiCatalog.Item item=emojiCatalog.byEmoji.get(value.substring(3));if(item!=null&&!item.variants.isEmpty())t.hold=()->{t.held=true;hidePreview();haptic(true);List<String> variants=new ArrayList<>();variants.add(item.emoji);variants.addAll(item.variants);showVariants(t.key,variants,false);};}
            if(t.hold!=null)handler.postDelayed(t.hold,KeyboardPreferences.holdDelay(prefs));
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            int action=e.getActionMasked(),index=e.getActionIndex(),id=e.getPointerId(index);
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
                if(cursorDragging())return true;
                boolean multiple=touches.size()>0;
                for(int i=0;i<touches.size();i++){Touch previous=touches.valueAt(i);previous.multi=true;if(previous.hold!=null)handler.removeCallbacks(previous.hold);if(!previous.gliding&&letter(previous.key))commitTouch(previous);}
                Key key=hit(e.getX(index),e.getY(index));if(key==null)return true;
                if(!key.value.equals("voice")&&!key.value.startsWith("suggest:"))cancelDictation();
                Touch t=new Touch(key,e.getX(index),e.getY(index));t.multi=multiple;touches.put(id,t);
                haptic(false);if(multiple)hidePreview();else showPreview(t,id);
                // Letters wait for release so a touch can become a glide without inserting its first letter.
                boolean defer=letter(key)||key.value.equals("space")||key.value.startsWith("em:");
                if(!defer){press(key.value);t.committed=true;}
                hold(t,id);invalidateKey(key);return true;
            }
            if(action==MotionEvent.ACTION_MOVE){
                for(int i=0;i<e.getPointerCount();i++){
                    Touch t=touches.get(e.getPointerId(i));if(t==null)continue;
                    float x=e.getX(i),y=e.getY(i),distance=(float)Math.hypot(x-t.x,y-t.y);
                    float dx=x-t.lastX,dy=y-t.lastY;t.lastX=x;t.lastY=y;
                    if(t.cursor){
                        t.cursorX+=dx;t.cursorY+=dy;
                        int horizontal=(int)(t.cursorX/dp(8)),vertical=(int)(t.cursorY/dp(22));
                        if(horizontal!=0||vertical!=0){t.cursorX-=horizontal*dp(8);t.cursorY-=vertical*dp(22);moveCursor(horizontal,vertical);}
                        continue;
                    }
                    if(distance>dp(16)&&t.hold!=null)handler.removeCallbacks(t.hold);
                    if(letter(t.key)&&!t.committed&&!t.held&&!t.multi&&!emojiSearching&&allowGlide&&prefs.getBoolean("glide",true)&&dictionary!=null){
                        for(int h=0;h<e.getHistorySize();h++)t.point(e.getHistoricalX(i,h),e.getHistoricalY(i,h));t.point(x,y);
                        Key target=hit(x,y);if(!t.gliding&&distance>Math.max(dp(22),t.key.box.width()*.55f)&&letter(target)&&target!=t.key)t.gliding=true;
                        if(t.gliding){hidePreview();t.highlight=target;invalidateLetters();}
                    }
                }
                return true;
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
                Touch t=touches.get(id);if(t!=null){
                    if(previewPointer==id)handler.postDelayed(dismissPreview,45);
                    if(t.hold!=null)handler.removeCallbacks(t.hold);
                    float dx=e.getX(index)-t.x,dy=e.getY(index)-t.y;
                    if(t.cursor){cursorPointer=-1;finishCursorDrag();}
                    else if(t.gliding){
                        t.point(e.getX(index),e.getY(index));float[] path=new float[t.points.size()];for(int i=0;i<path.length;i++)path[i]=t.points.get(i);
                        Map<Character,float[]> centers=new HashMap<>();for(Key k:keys)if(letter(k))centers.put(k.value.charAt(0),new float[]{k.box.centerX(),k.box.centerY()});
                        finishGlide(path,centers,contentWidth/10,t.upper);invalidateLetters();
                    }else if(t.key.value.startsWith("em:")&&(Math.abs(dx)>dp(36)||Math.abs(dy)>dp(36))){press(dx<0||Math.abs(dy)>Math.abs(dx)&&dy<0?"emojinext":"emojiprev");}
                    else if(!t.held&&!t.committed)commitTouch(t);
                    touches.remove(id);invalidateKey(t.highlight==null?t.key:t.highlight);
                }
                performClick();return true;
            }
            if(action==MotionEvent.ACTION_CANCEL){cancelTouches();return true;}return true;
        }
        void cancelTouches(){hidePreview();for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.hold!=null)handler.removeCallbacks(t.hold);invalidateKey(t.key);if(t.gliding)invalidateLetters();}touches.clear();if(cursorDragging())finishCursorDrag();}
        @Override public boolean performClick(){return super.performClick();}
        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){return nodes;}
        private String accessibilityLabel(Key key){
            if(key.value.startsWith("em:")&&emojiCatalog!=null){EmojiCatalog.Item item=emojiCatalog.byEmoji.get(key.value.substring(3));return item==null?label(key):item.name;}
            if(key.value.startsWith("cat:"))return EmojiCatalog.NAMES[Integer.parseInt(key.value.substring(4))];
            return switch(key.value){case "emojisearch"->"Search emoji";case "emojinext"->"Next emoji page";case "emojiprev"->"Previous emoji page";default->label(key);};
        }
        private void event(int id,int type){AccessibilityEvent e=AccessibilityEvent.obtain(type);e.setPackageName(getPackageName());e.setClassName("android.widget.Button");e.setSource(this,id);if(id>=0&&id<keys.size())e.getText().add(accessibilityLabel(keys.get(id)));if(getParent()!=null)getParent().requestSendAccessibilityEvent(this,e);}
        @Override public boolean dispatchHoverEvent(MotionEvent e){AccessibilityManager m=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);if(!m.isTouchExplorationEnabled())return super.dispatchHoverEvent(e);Key k=hit(e.getX(),e.getY());int id=k==null?-1:keys.indexOf(k);if(e.getAction()==MotionEvent.ACTION_HOVER_EXIT)id=-1;if(id!=hover){if(hover>=0)event(hover,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);hover=id;if(id>=0)event(id,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);}return true;}
        private final class Nodes extends AccessibilityNodeProvider {
            @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){
                if(id==View.NO_ID){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(KeyboardView.this);onInitializeAccessibilityNodeInfo(n);for(int i=0;i<keys.size();i++)if(keyVisible(keys.get(i)))n.addChild(KeyboardView.this,i);return n;}
                if(id<0||id>=keys.size()||!keyVisible(keys.get(id)))return null;Key k=keys.get(id);AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setSource(KeyboardView.this,id);n.setParent(KeyboardView.this);n.setPackageName(getPackageName());n.setClassName("android.widget.Button");n.setContentDescription(accessibilityLabel(k));n.setText(accessibilityLabel(k));n.setEnabled(true);n.setClickable(true);n.setFocusable(true);n.setVisibleToUser(isShown());n.setAccessibilityFocused(focused==id);n.addAction(AccessibilityNodeInfo.ACTION_CLICK);n.addAction(focused==id?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                if(k.value.equals("space")){n.setLongClickable(true);n.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);}
                if(k.value.startsWith("em:")&&emojiCatalog!=null){EmojiCatalog.Item item=emojiCatalog.byEmoji.get(k.value.substring(3));if(item!=null&&!item.variants.isEmpty()){n.setLongClickable(true);n.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);}}
                Rect r=new Rect();RectF bounds=new RectF();keyBounds(k,bounds);bounds.roundOut(r);n.setBoundsInParent(r);int[] xy=new int[2];getLocationOnScreen(xy);r.offset(xy[0],xy[1]);n.setBoundsInScreen(r);return n;
            }
            @Override public boolean performAction(int id,int action,Bundle args){if(id<0||id>=keys.size()||!keyVisible(keys.get(id)))return false;Key key=keys.get(id);if(action==AccessibilityNodeInfo.ACTION_CLICK){press(key.value);event(id,AccessibilityEvent.TYPE_VIEW_CLICKED);return true;}if(action==AccessibilityNodeInfo.ACTION_LONG_CLICK&&key.value.equals("space")){announceForAccessibility("Hold Space and drag to move the cursor");return true;}if(action==AccessibilityNodeInfo.ACTION_LONG_CLICK&&key.value.startsWith("em:")&&emojiCatalog!=null){EmojiCatalog.Item item=emojiCatalog.byEmoji.get(key.value.substring(3));if(item!=null&&!item.variants.isEmpty()){List<String> variants=new ArrayList<>();variants.add(item.emoji);variants.addAll(item.variants);showVariants(key,variants,false);return true;}}if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS){focused=id;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);invalidateKey(key);return true;}if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS){focused=-1;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);invalidateKey(key);return true;}return false;}
            @Override public AccessibilityNodeInfo findFocus(int focus){return focused<0?null:createAccessibilityNodeInfo(focused);}
        }
    }
}
