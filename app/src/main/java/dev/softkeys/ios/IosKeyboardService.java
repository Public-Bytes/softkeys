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
    private DictationEngine dictation;
    private boolean dictationFinishing;
    // The dictation keyboard. dictationPanel is true while the panel replaces the keys.
    // A handoff is a recording that is finishing after the user went back to the keys.
    private boolean dictationPanel,dictationHandoff,noPersonalizedLearning,fallbackNoticeShown;
    private DictationMode.Engine panelEngine=DictationMode.Engine.NONE;
    // A message in the panel, and its button: "permission", "settings", or "dictate".
    private String panelNotice,panelAction;
    private boolean panelNoticeIsError;
    private long recordingStart;
    private final DictationMode.Meter meter=new DictationMode.Meter(160);
    private final Runnable clearPanelNotice=()->{panelNotice=null;panelAction=null;panelNoticeIsError=false;if(updatePanel())rebuild();panelChanged();};
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
        if("learnedWords".equals(key))reloadLearnedWords();
    };
    private String[] suggestions={"I","the","I'm"};
    private String correctionOriginal,correctionReplacement;
    private int correctionCaret=-1,glideLength;
    private boolean decodingGlide;
    private final ArrayDeque<Runnable> pendingActions=new ArrayDeque<>();
    private String correctionPrefix="",cachedCorrection;
    private boolean correctionPending;
    // Touch positions of the letters in the word being typed. The dictionary uses them to judge near misses.
    private final StringBuilder tapLetters=new StringBuilder();
    private final ArrayList<Float> tapPoints=new ArrayList<>();
    private EnglishDictionary.Layout letterLayout;
    // The caret position right after a space that SoftKeys added after a glide or a chosen suggestion.
    private int autoSpaceCaret=-1;
    private final LinkedHashSet<String> learnedWords=new LinkedHashSet<>();
    private final Set<String> sessionWords=new HashSet<>();
    private final Runnable readContext=this::readContextAsync;
    private final Runnable suggestTask=this::computeSuggestions;

    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(){
        super.onCreate();prefs=KeyboardPreferences.open(this);
        vibrator=Build.VERSION.SDK_INT>=31?getSystemService(VibratorManager.class).getDefaultVibrator():(Vibrator)getSystemService(VIBRATOR_SERVICE);
        clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null)clipboard.addPrimaryClipChangedListener(clipboardListener);
        learnedWords.addAll(KeyboardPreferences.learnedWords(prefs));
        prefs.registerOnSharedPreferenceChangeListener(clipboardPreferenceListener);
        worker.execute(()->{
            try(java.io.InputStream words=getAssets().open("english.tsv");java.io.InputStream additions=getAssets().open("lexicon.tsv")){
                EnglishDictionary loaded=new EnglishDictionary(words,additions);loaded.setUserWords(KeyboardPreferences.learnedWords(prefs));dictionary=loaded;
            }catch(java.io.IOException ignored){}
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
        revision++;decodingGlide=false;pendingActions.clear();acknowledgements.clear();before.setLength(0);clearCorrection();glideLength=0;autoSpaceCaret=-1;tapLetters.setLength(0);tapPoints.clear();
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
        noPersonalizedLearning=(info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0;
        clearNotice();updatePanel();
        symbols=second=emoji=caps=manualShift=emojiSearching=false;emojiPage=0;emojiQuery="";lastShift=lastSpace=0;
        if(Build.VERSION.SDK_INT>=30){CharSequence initial=info.getInitialTextBeforeCursor(256,0);if(initial!=null)before.append(initial);}
        updateShift();refreshSuggestions();scheduleContextRead();rebuild();readClipboardSuggestion();
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){
        super.onStartInputView(info,restarting);palette();updateShift();
        // Microphone permission, models, and settings can change while the keyboard is hidden.
        boolean changed=updatePanel();
        if(keyboard!=null){if(keyboard.keys.isEmpty()||changed)rebuild();else keyboard.requestLayout();}
        if(!restarting)readClipboardSuggestion();
        preparePanel();
    }
    @Override public void onFinishInput(){cancelDictation();activeInput=false;decodingGlide=false;pendingActions.clear();revision++;handler.removeCallbacks(readContext);handler.removeCallbacks(suggestTask);clearCorrection();glideLength=0;super.onFinishInput();}
    @Override public void onFinishInputView(boolean finishing){cancelDictation();SpeechEngine.keep(false);if(panelNoticeIsError)clearNotice();decodingGlide=false;pendingActions.clear();revision++;handler.removeCallbacks(readContext);if(keyboard!=null)keyboard.cancelTouches();super.onFinishInputView(finishing);}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(dictation==null&&level>=TRIM_MEMORY_UI_HIDDEN)SpeechEngine.release();}
    @Override public void onDestroy(){cancelDictation();SpeechEngine.release();if(clipboard!=null)clipboard.removePrimaryClipChangedListener(clipboardListener);prefs.unregisterOnSharedPreferenceChangeListener(clipboardPreferenceListener);clipboardText=null;worker.shutdownNow();reader.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
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
        return dictation==null&&!dictationPanel&&activeInput&&clipboardText!=null&&age>=0&&age<CLIPBOARD_LIFETIME&&prefs.getBoolean("clipboardSuggestions",true)&&allowPredictions&&!privateField&&!numeric&&!emoji&&!emojiSearching&&selectionLength==0&&glideLength==0&&!decodingGlide&&(keyboard==null||!keyboard.cursorDragging())&&prefix().isEmpty();
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
    private boolean autoSpaced(){return autoSpaceCaret>=0&&caret==autoSpaceCaret&&selectionLength==0&&before.length()>0&&before.charAt(before.length()-1)==' ';}
    /** A space is needed before a word that follows a letter, a digit or closing punctuation. */
    private String leadSpace(){
        if(before.length()==0)return "";
        char c=before.charAt(before.length()-1);
        if(Character.isLetterOrDigit(c))return " ";
        return !emailField&&!uriField&&!terminalEditor&&".,!?;:)]}".indexOf(c)>=0?" ":"";
    }
    private float[] tapsFor(String word){
        String plain=EnglishDictionary.letters(word);
        if(plain.isEmpty()||!plain.contentEquals(tapLetters)||tapPoints.size()!=plain.length()*2)return null;
        float[] taps=new float[tapPoints.size()];for(int i=0;i<taps.length;i++)taps[i]=tapPoints.get(i);return taps;
    }
    private void reloadLearnedWords(){
        Set<String> saved=KeyboardPreferences.learnedWords(prefs);if(saved.equals(learnedWords))return;
        learnedWords.clear();learnedWords.addAll(saved);if(saved.isEmpty())sessionWords.clear();applyUserWords();
    }
    private void applyUserWords(){
        EnglishDictionary dict=dictionary;if(dict==null)return;
        List<String> words=new ArrayList<>(learnedWords);words.addAll(sessionWords);dict.setUserWords(words);correctionPrefix="";
    }
    /**
     * Stops correcting a spelling the user restored or chose. The first restore lasts until the keyboard
     * restarts. A second restore, or choosing the typed text in the strip, saves the word.
     */
    private void keepWord(String word,boolean save){
        EnglishDictionary dict=dictionary;
        if(dict==null||privateField||word.length()<2||word.length()>26||dict.contains(word))return;
        for(int i=0;i<word.length();i++){char c=word.charAt(i);if(!Character.isLetter(c)&&c!='\'')return;}
        String stored=word.substring(1).equals(word.substring(1).toLowerCase(Locale.US))?word.toLowerCase(Locale.US):word;
        if(save||!sessionWords.add(stored)){
            sessionWords.remove(stored);
            if(learnedWords.add(stored)){
                while(learnedWords.size()>2000){Iterator<String> oldest=learnedWords.iterator();oldest.next();oldest.remove();}
                prefs.edit().putStringSet("learnedWords",new HashSet<>(learnedWords)).apply();
            }
        }
        applyUserWords();
    }
    private void commit(String text){InputConnection ic=getCurrentInputConnection();if(ic==null)return;clearCorrection();glideLength=0;autoSpaceCaret=-1;ic.commitText(text,1);before.append(text);caret+=text.length();noteEdit();}
    private void replaceTail(int count,String text){
        InputConnection ic=getCurrentInputConnection();if(ic==null)return;count=Math.min(count,before.length());autoSpaceCaret=-1;
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
            String original=correctionOriginal;int count=correctionReplacement.length();clearCorrection();
            String word=original;while(!word.isEmpty()&&!Character.isLetter(word.charAt(word.length()-1)))word=word.substring(0,word.length()-1);
            keepWord(word,false);replaceTail(count,original);return;
        }
        // Backspace right after a glide removes the whole word, because the glide inserted it in one step.
        if(glideLength>0&&autoSpaced()&&before.length()>=glideLength){int count=glideLength;clearCorrection();glideLength=0;lastSpace=0;replaceTail(count,"");return;}
        clearCorrection();glideLength=0;lastSpace=0;autoSpaceCaret=-1;
        if(selectionLength>0){sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);noteEdit();return;}
        int count=1;
        if(before.length()>0){graphemes.setText(before.toString());int start=Math.max(0,graphemes.preceding(before.length()));count=before.length()-start;before.delete(start,before.length());}
        // DEL events reach native, WebView, terminal and custom editor key handlers.
        // Multi-code-unit clusters use an atomic surrounding-text delete when available.
        if(!rawEditor&&count>1){if(!ic.deleteSurroundingText(count,0))sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);}
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        caret=Math.max(0,caret-count);
        String plain=EnglishDictionary.letters(prefix());
        if(plain.length()<tapLetters.length()&&tapLetters.toString().startsWith(plain)){tapLetters.setLength(plain.length());while(tapPoints.size()>plain.length()*2)tapPoints.remove(tapPoints.size()-1);}
        noteEdit();
    }
    /** Removes the word before the cursor. Held Backspace switches to this after a run of single characters. */
    private void eraseWord(){
        InputConnection ic=getCurrentInputConnection();
        if(ic==null||decodingGlide||emojiSearching||rawEditor||selectionLength>0||before.length()==0){erase();return;}
        int i=before.length();
        while(i>0&&Character.isWhitespace(before.charAt(i-1)))i--;
        while(i>0&&!Character.isWhitespace(before.charAt(i-1)))i--;
        int count=before.length()-i;
        if(count<=1||!ic.deleteSurroundingText(count,0)){erase();return;}
        clearCorrection();glideLength=0;lastSpace=0;autoSpaceCaret=-1;
        before.delete(i,before.length());caret=Math.max(0,caret-count);noteEdit();
    }
    private void typeLetter(String text,float x,float y){
        cancelRecording();
        if(decodingGlide){pendingActions.add(()->typeLetter(text,x,y));return;}
        lastShift=lastSpace=0;
        if(emojiSearching){emojiQuery+=text.toLowerCase(Locale.US);keyboard.invalidateHeader();return;}
        if(prefix().isEmpty()){tapLetters.setLength(0);tapPoints.clear();}
        tapLetters.append(text.toLowerCase(Locale.US));tapPoints.add(x);tapPoints.add(y);
        commit(text);
    }
    private void showLetters(){if(symbols&&!numeric){symbols=second=false;rebuild();}}
    private void updateShift(){
        boolean previous=shift;if(caps)shift=true;else if(!manualShift){
            shift=autoShift(before);
        }
        if(previous!=shift&&keyboard!=null)keyboard.invalidateLetters();
    }
    /** True when the editor asks for a capital letter after this text and automatic capitalization is on. */
    private boolean autoShift(CharSequence text){
        if(emojiSearching||!prefs.getBoolean("autocaps",true)||privateField||numeric||terminalEditor)return false;
        if((inputType&InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)!=0)return true;
        if((inputType&InputType.TYPE_TEXT_FLAG_CAP_WORDS)!=0)return text.length()==0||Character.isWhitespace(text.charAt(text.length()-1));
        if((inputType&InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)==0)return false;
        int i=text.length()-1;
        if(i<0||text.charAt(i)=='\n')return true;
        if(!Character.isWhitespace(text.charAt(i)))return false;
        while(i>=0&&Character.isWhitespace(text.charAt(i))){if(text.charAt(i)=='\n')return true;i--;}
        return i<0||".!?".indexOf(text.charAt(i))>=0;
    }
    private String prefix(){if(privateField||selectionLength>0)return "";int i=before.length();while(i>0&&(Character.isLetter(before.charAt(i-1))||before.charAt(i-1)=='\''))i--;return before.substring(i);}
    private void refreshSuggestions(){
        if(glideLength>0)return;
        handler.removeCallbacks(suggestTask);correctionPending=false;
        if(!allowPredictions){suggestions=new String[0];if(keyboard!=null)keyboard.invalidateHeader();return;}
        String p=prefix();
        if(p.isEmpty())suggestions=new String[]{"I",shift?"The":"the","I'm"};
        else suggestions=new String[]{p,suggestions.length>1?suggestions[1]:"",suggestions.length>2?suggestions[2]:""};
        if(keyboard!=null)keyboard.invalidateHeader();if(!p.isEmpty()&&dictionary!=null)handler.postDelayed(suggestTask,65);
    }
    private void computeSuggestions(){
        EnglishDictionary dict=dictionary;if(dict==null||!allowPredictions||emojiSearching||glideLength>0)return;
        String p=prefix();long token=revision;float[] taps=tapsFor(p);EnglishDictionary.Layout layout=letterLayout;
        worker.execute(()->{
            if(token!=revision)return;
            EnglishDictionary.Analysis analysis=dict.analyze(p,taps,layout);
            handler.post(()->{
                if(token!=revision||!activeInput||glideLength>0)return;
                correctionPrefix=p;cachedCorrection=analysis.correction;suggestions=analysis.suggestions;
                correctionPending=analysis.correction!=null&&selectionLength==0&&prefs.getBoolean("autocorrect",true)&&canAutoCorrect(" ");
                if(keyboard!=null)keyboard.invalidateHeader();
            });
        });
    }
    private void chooseSuggestion(int i){
        if(clipboardVisible()){
            if(i==2){dismissClipboardSuggestion();return;}
            if(i==0){String text=clipboardText;dismissClipboardSuggestion();commit(text);}return;
        }
        if(i>=suggestions.length||suggestions[i].isEmpty())return;String selected=suggestions[i];int count=glideLength>0?glideLength:prefix().length();
        // Choosing the typed text over a pending correction teaches the keyboard that spelling.
        if(i==0&&glideLength==0&&correctionPending)keepWord(selected,true);
        String lead=count==0?leadSpace():"";
        clearCorrection();glideLength=0;replaceTail(count,lead+selected+" ");autoSpaceCaret=caret;
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
        float[] taps=tapsFor(p);EnglishDictionary.Layout layout=letterLayout;
        worker.execute(()->{String fixed=dict.analyze(p,taps,layout).correction;handler.post(()->{if(token!=revision||!activeInput)return;decodingGlide=false;applyCorrection(p,fixed,delimiter);drainPendingActions();});});
    }
    private void applyCorrection(String p,String fixed,String delimiter){
        if(fixed!=null&&!fixed.equals(p)){clearCorrection();glideLength=0;replaceTail(p.length(),fixed+delimiter);correctionOriginal=p;correctionReplacement=fixed+delimiter;correctionCaret=caret;}
        else commit(delimiter);
    }
    private void finishGlide(float[] path,EnglishDictionary.Layout layout,boolean upper){
        cancelRecording();
        dismissClipboardSuggestion();
        if(decodingGlide){pendingActions.add(()->finishGlide(path,layout,upper));return;}
        EnglishDictionary dict=dictionary;if(dict==null)return;long token=revision;decodingGlide=true;handler.removeCallbacks(readContext);
        worker.execute(()->{List<String> result=dict.glide(path,layout);handler.post(()->{
            if(token!=revision||!activeInput)return;
            decodingGlide=false;
            if(result.isEmpty()){drainPendingActions();return;}
            String lead=leadSpace();
            // The space added before the word can start a new sentence, which needs a capital.
            boolean capital=upper||!lead.isEmpty()&&!caps&&autoShift(before+lead);
            String first=capital?EnglishDictionary.matchCase(result.get(0),"A"):result.get(0);
            commit(lead+first+" ");glideLength=first.length()+1;autoSpaceCaret=caret;correctionPending=false;
            suggestions=result.stream().map(w->capital?EnglishDictionary.matchCase(w,"A"):w).toArray(String[]::new);keyboard.invalidateHeader();drainPendingActions();
        });});
    }
    private void drainPendingActions(){while(!decodingGlide&&!pendingActions.isEmpty())pendingActions.removeFirst().run();}
    private String actionLabel(){EditorInfo e=getCurrentInputEditorInfo();if(emojiSearching)return "search";if(e==null||(e.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)!=0)return "Return";return switch(e.imeOptions&EditorInfo.IME_MASK_ACTION){case EditorInfo.IME_ACTION_GO->"go";case EditorInfo.IME_ACTION_SEARCH->"search";case EditorInfo.IME_ACTION_SEND->"send";case EditorInfo.IME_ACTION_NEXT->"next";case EditorInfo.IME_ACTION_DONE->"done";default->"Return";};}
    private void searchEmoji(){emojiSearching=true;emoji=false;symbols=false;updateShift();rebuild();}
    private void showEmojiResults(){emojiSearching=false;emoji=true;emojiPage=0;rebuild();}
    private void press(String value){
        if(value.startsWith("dict:")){pressPanel(value);return;}
        if(dictationPanel){pressPanelEdit(value);return;}
        if(!value.startsWith("suggest:"))cancelRecording();
        if(decodingGlide){pendingActions.add(()->press(value));return;}
        if(value.startsWith("text:")){String text=value.substring(5);lastShift=lastSpace=0;if(emojiSearching){emojiQuery+=text.toLowerCase(Locale.US);keyboard.invalidateHeader();}else commit(text);return;}
        if(value.startsWith("suggest:")){chooseSuggestion(Integer.parseInt(value.substring(8)));return;}
        if(value.startsWith("cat:")){emojiCategory=Integer.parseInt(value.substring(4));emojiPage=0;emojiQuery="";rebuild();return;}
        if(value.startsWith("em:")){String text=value.substring(3);commit(text);if(emojiCatalog!=null)emojiCatalog.used(text,prefs);return;}
        switch(value){
            case "shift":{long now=SystemClock.uptimeMillis();if(caps)caps=shift=false;else if(now-lastShift<350)caps=shift=true;else shift=!shift;lastShift=now;manualShift=true;keyboard.invalidateLetters();return;}
            case "delete":erase();return;
            case "123":symbols=true;second=emoji=numeric=false;rebuild();return;
            case "ABC":symbols=second=emoji=numeric=emojiSearching=false;emojiQuery="";rebuild();return;
            case "more":second=!second;rebuild();return;
            case "voice":enterDictationKeyboard();return;
            case "emoji":emoji=true;emojiSearching=false;emojiPage=0;emojiQuery="";symbols=numeric=false;rebuild();return;
            case "emojinext":keyboard.settlePage(1);return;
            case "emojiprev":keyboard.settlePage(-1);return;
            case "emojisearch":searchEmoji();return;
            case "emojidone":showEmojiResults();return;
            case "emojiquery":return;
            case "space":{
                if(emojiSearching){emojiQuery+=" ";keyboard.invalidateHeader();return;}
                long now=SystemClock.uptimeMillis();int n=before.length();
                // A space after a symbol or a number returns to the letters, as on iOS.
                boolean wasSymbols=symbols;
                // The glide or the chosen suggestion already added this space.
                if(autoSpaced()){autoSpaceCaret=-1;glideLength=0;refreshSuggestions();lastSpace=now;}
                else if(prefs.getBoolean("doubleSpacePeriod",true)&&!privateField&&!terminalEditor&&!emailField&&!uriField&&now-lastSpace<700&&n>=2&&before.charAt(n-1)==' '&&(Character.isLetterOrDigit(before.charAt(n-2))||")]}\"'".indexOf(before.charAt(n-2))>=0)){clearCorrection();replaceTail(1,". ");lastSpace=0;}
                else{boundary(" ");lastSpace=now;}
                if(wasSymbols)showLetters();
                return;
            }
            case "return":{
                dismissClipboardSuggestion();
                if(emojiSearching){showEmojiResults();return;}
                enterKey(true);
                showLetters();return;
            }
            default:{
                lastShift=lastSpace=0;
                String text=!symbols&&!emoji&&shift?value.toUpperCase(Locale.US):value;
                if(emojiSearching){emojiQuery+=text.toLowerCase(Locale.US);keyboard.invalidateHeader();return;}
                // Punctuation ends a word on every layout, so the word before it is corrected.
                boolean ending=text.length()==1&&".,!?;:)]}\"".contains(text);
                // Punctuation right after an added space replaces that space.
                if(ending&&!text.equals("\"")&&autoSpaced()){glideLength=0;replaceTail(1,text);}
                else if(ending)boundary(text);
                else commit(text);
                // An apostrophe is followed by letters, so return to them.
                if(text.equals("'"))showLetters();
            }
        }
    }
    private boolean fieldAllowsDictation(){return activeInput&&DictationMode.fieldAllowsDictation(privateField,numeric,allowPredictions,noPersonalizedLearning);}
    private static SpeechModel model(DictationMode.Engine engine){return engine==DictationMode.Engine.FAST?SpeechModel.FAST:SpeechModel.ACCURATE;}
    private boolean preferDevice(){return "device".equals(prefs.getString("dictationEngine","system"));}
    private DictationMode.Engine chooseEngine(){
        boolean supported=SpeechEngine.supported(this);
        return DictationMode.engine(preferDevice(),prefs.getString("speechModel","accurate"),supported,
            supported&&SpeechModel.ACCURATE.installed(this),supported&&SpeechModel.FAST.installed(this),android.speech.SpeechRecognizer.isRecognitionAvailable(this));
    }
    private boolean microphoneAllowed(){return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    private void clearNotice(){handler.removeCallbacks(clearPanelNotice);panelNotice=null;panelAction=null;panelNoticeIsError=false;}
    private void setNotice(String message,String action){handler.removeCallbacks(clearPanelNotice);panelNotice=message;panelAction=action;panelNoticeIsError=false;}

    /**
     * Decides whether the dictation panel replaces the keys, which engine it uses, and whether
     * it shows a message in place of the Dictate button. Returns true when the panel appeared
     * or disappeared, so the layout must be rebuilt. The saved choice is not changed here.
     */
    private boolean updatePanel(){
        boolean show=DictationMode.showPanel(prefs.getBoolean("dictationKeyboard",false),fieldAllowsDictation());
        boolean changed=show!=dictationPanel;
        dictationPanel=show;
        if(!show)return changed;
        panelEngine=chooseEngine();
        if(dictation==null&&!panelNoticeIsError){
            if(panelEngine==DictationMode.Engine.NONE)setNotice(SpeechEngine.supported(this)?"Download a speech model in SoftKeys settings, or install a speech recognition service.":"No speech recognition service is installed. Check SoftKeys settings.","settings");
            else if(!microphoneAllowed())setNotice("Allow microphone access to dictate.","permission");
            else clearNotice();
        }
        return changed;
    }
    /** Loads the on-device model while the panel is open, so Dictate starts without waiting. */
    private void preparePanel(){
        if(!dictationPanel||!panelEngine.onDevice())return;
        SpeechEngine.keep(true);
        SpeechEngine.preload(this,model(panelEngine));
    }
    /** The microphone key. Shows the dictation keyboard and starts recording at once. */
    private void enterDictationKeyboard(){
        if(!activeInput||getCurrentInputConnection()==null)return;
        if(!fieldAllowsDictation()){Toast.makeText(this,"Dictation is unavailable in this field",Toast.LENGTH_SHORT).show();return;}
        prefs.edit().putBoolean("dictationKeyboard",true).apply();
        dismissClipboardSuggestion();clearCorrection();glideLength=0;
        symbols=second=emoji=emojiSearching=false;emojiQuery="";
        clearNotice();updatePanel();rebuild();preparePanel();
        startDictation();
    }
    /**
     * The keyboard button. The keys come back at once. A recording in progress is finished,
     * not discarded, and its text is inserted when it is ready. Keys typed meanwhile wait
     * and are applied after it, in order.
     */
    private void leaveDictationKeyboard(){
        prefs.edit().putBoolean("dictationKeyboard",false).apply();
        if(dictation!=null){dictationHandoff=true;decodingGlide=true;stopDictation();}
        clearNotice();SpeechEngine.keep(false);
        updatePanel();updateShift();refreshSuggestions();rebuild();readClipboardSuggestion();
    }
    private void openPermission(){
        try{startActivity(new Intent(this,MicrophonePermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException unavailable){Toast.makeText(this,"Allow microphone access in SoftKeys settings",Toast.LENGTH_LONG).show();}
    }
    private void openSettings(){
        try{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException unavailable){Toast.makeText(this,"Open the SoftKeys app to change settings",Toast.LENGTH_LONG).show();}
    }
    private void pressPanel(String value){
        switch(value){
            case "dict:start"->startDictation();
            case "dict:done"->{if(dictation!=null&&!dictationFinishing)stopDictation();}
            case "dict:cancel"->{if(dictation!=null){cancelDictation();announce("Dictation cancelled");}}
            case "dict:keys"->leaveDictationKeyboard();
            case "dict:style"->{
                boolean clean=!prefs.getBoolean("dictationCleanup",true);
                prefs.edit().putBoolean("dictationCleanup",clean).apply();
                panelChanged();announce(clean?"Clean style":"Verbatim style");
            }
            case "dict:action"->{
                if("permission".equals(panelAction))openPermission();
                else if("settings".equals(panelAction))openSettings();
                else{clearNotice();startDictation();}
            }
            default->{}
        }
    }
    /** Space, Delete, and Return in the panel. They do nothing while a recording is open, so its text lands where the user started. */
    private void pressPanelEdit(String value){
        if(dictation!=null)return;
        if(decodingGlide){pendingActions.add(()->press(value));return;}
        switch(value){
            case "space"->commit(" ");
            case "delete"->erase();
            case "return"->enterKey(false);
            default->{}
        }
    }
    /** Return runs the editor's action, such as Send or Search, when it has one. Otherwise it inserts a line break. */
    private void enterKey(boolean correct){
        EditorInfo e=getCurrentInputEditorInfo();InputConnection ic=getCurrentInputConnection();if(ic==null)return;
        int action=e==null?0:e.imeOptions&EditorInfo.IME_MASK_ACTION;
        if(e!=null&&(e.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0&&action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){ic.performEditorAction(action);clearCorrection();glideLength=0;}
        else if(rawEditor)sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);else if(correct)boundary("\n");else commit("\n");
    }
    private void startDictation(){
        if(dictation!=null||!dictationPanel||!activeInput||getCurrentInputConnection()==null)return;
        if(!microphoneAllowed()){setNotice("Allow microphone access to dictate.","permission");panelChanged();openPermission();return;}
        clearNotice();
        panelEngine=chooseEngine();
        if(panelEngine==DictationMode.Engine.NONE){updatePanel();panelChanged();return;}
        if(panelEngine==DictationMode.Engine.PHONE&&preferDevice()&&!fallbackNoticeShown){
            fallbackNoticeShown=true;
            Toast.makeText(this,SpeechEngine.supported(this)?"On-device model not downloaded. Using phone speech service.":"On-device dictation isn't supported here. Using phone speech service.",Toast.LENGTH_SHORT).show();
        }
        clearCorrection();glideLength=0;lastSpace=lastShift=0;
        final long token=revision;final InputConnection connection=getCurrentInputConnection();
        dictationFinishing=false;
        DictationEngine.Listener listener=new DictationEngine.Listener(){
            private boolean current(DictationEngine session){return dictation==session&&activeInput&&isInputViewShown()&&revision==token&&connection==getCurrentInputConnection();}
            public void status(DictationEngine session,String text,boolean finishing){
                if(!current(session)){if(dictation==session)cancelDictation();return;}
                if(finishing&&!dictationFinishing){dictationFinishing=true;panelChanged();if(dictationPanel)announce("Transcribing");}
            }
            public void level(DictationEngine session,float level){if(dictation==session)meter.target(level);}
            public void result(DictationEngine session,String text){
                if(!current(session)){if(dictation==session)cancelDictation();return;}
                boolean handoff=dictationHandoff;dictationHandoff=false;
                cancelDictation();
                commitDictation(dictatedText(session,text));
                if(handoff){decodingGlide=false;drainPendingActions();}
            }
            public void error(DictationEngine session,String message,String retainedText){
                if(!current(session)){if(dictation==session)cancelDictation();return;}
                boolean handoff=dictationHandoff;dictationHandoff=false;
                cancelDictation();
                commitDictation(dictatedText(session,retainedText));
                if(handoff){decodingGlide=false;drainPendingActions();Toast.makeText(IosKeyboardService.this,message,Toast.LENGTH_LONG).show();}
                else showDictationError(message);
            }
        };
        DictationEngine session=switch(panelEngine){
            case ACCURATE->new BatchDictation(this,listener);
            case FAST->new OnDeviceDictation(this,listener);
            default->new DictationSession(this,listener);
        };
        dictation=session;recordingStart=SystemClock.uptimeMillis();meter.reset();
        panelChanged();session.start();
        announce("Recording");
    }
    /** The text to insert. On-device text gets the chosen style, and phone speech service text is inserted as it arrives. */
    private String dictatedText(DictationEngine session,String raw){
        if(raw==null||raw.trim().isEmpty())return "";
        if(!session.onDevice())return raw.trim();
        DictationCleanup.Options options=cleanupOptions();
        return prefs.getBoolean("dictationCleanup",true)?DictationCleanup.clean(raw,options):DictationCleanup.verbatim(raw,options);
    }
    /** Cleanup options for on-device dictation at the current cursor position. */
    private DictationCleanup.Options cleanupOptions(){
        DictationCleanup.Options options=new DictationCleanup.Options();
        options.spokenPunctuation=prefs.getBoolean("spokenPunctuation",true);
        options.replacements=DictationCleanup.parseReplacements(prefs.getString("dictationReplacements",""));
        int end=before.length();
        while(end>0&&before.charAt(end-1)==' ')end--;
        char previous=end==0?'\n':before.charAt(end-1);
        options.startsSentence=!terminalEditor&&!emailField&&!uriField&&(previous=='\n'||".!?".indexOf(previous)>=0);
        return options;
    }
    private void commitDictation(String text){
        if(text.isEmpty())return;
        boolean space=!terminalEditor&&!emailField&&!uriField;
        char previous=before.length()==0?' ':before.charAt(before.length()-1);
        String lead=space&&selectionLength==0&&!Character.isWhitespace(previous)&&(Character.isLetterOrDigit(previous)||".!?,;:".indexOf(previous)>=0)?" ":"";
        commit(lead+text+(space?" ":""));
    }
    private void stopDictation(){if(dictation!=null)dictation.stop();}
    private void cancelDictation(){
        boolean changed=dictation!=null;
        DictationEngine previous=dictation;dictation=null;dictationFinishing=false;
        // A handoff that ends without text drops the keys typed while it waited.
        if(dictationHandoff){dictationHandoff=false;decodingGlide=false;pendingActions.clear();}
        if(previous!=null)previous.close();if(changed)panelChanged();
    }
    /** Typing cancels a recording, except one that is finishing after the user went back to the keys. Its text goes in first. */
    private void cancelRecording(){if(!dictationHandoff)cancelDictation();}
    private void showDictationError(String message){
        panelNotice=message;panelAction="dictate";panelNoticeIsError=true;
        handler.removeCallbacks(clearPanelNotice);handler.postDelayed(clearPanelNotice,8_000);
        panelChanged();announce(message);
    }
    private void announce(String message){if(keyboard!=null)keyboard.announceForAccessibility(message);}
    private void panelChanged(){if(keyboard!=null)keyboard.panelChanged();}
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
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),trailPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface normal=Typeface.create("sans-serif",Typeface.NORMAL);
        private final RectF rect=new RectF();private final Path iconPath=new Path(),surface=new Path();
        private int navBottom,hover=-1,focused=-1,emojiPages=1,cursorPointer=-1,emojiCols=7,emojiPageSize=21;
        // Horizontal shift of the emoji grid while a page is dragged or settling, in pixels.
        private float emojiOffset;
        private android.animation.ValueAnimator pageAnimator;
        private float rowH,suggestionH,width,categoryH,contentLeft,contentWidth,keyGapX,keyGapY,keyRadius,topRadius;
        // Dictation panel rows: the hint and style row, the recessed well, and the keyboard button with Space/Delete/Return.
        private float panelTopH,panelWellH,panelLeft,panelWidth;
        private final RectF well=new RectF();
        private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
        private boolean wide,shortScreen;
        private int layoutWidth=-1,layoutHeight=-1;
        private List<EmojiCatalog.Item> visibleEmoji=Collections.emptyList();
        private final Nodes nodes=new Nodes();
        private PopupWindow keyPreview;
        private TextView previewText;
        private int previewPointer=-1;
        private final Runnable dismissPreview=this::hidePreview;
        KeyboardView(){super(IosKeyboardService.this);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);setBackgroundColor(Color.TRANSPARENT);trailPaint.setColor(0xfffffdfa);trailPaint.setStrokeCap(Paint.Cap.ROUND);trailPaint.setStrokeJoin(Paint.Join.ROUND);trailPaint.setStyle(Paint.Style.STROKE);}
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
            panelTopH=dp(shortScreen?32:40);panelWellH=dp(shortScreen?56:68);
            panelWidth=wide?Math.min(contentWidth,dp(640)):contentWidth;panelLeft=(width-panelWidth)/2;
            // The dictation panel is shorter than the letters, so more of the app stays visible.
            int height=dictationPanel?Math.round(panelTopH+panelWellH+rowH+navBottom+dp(4))
                :Math.round(suggestionH+rowH*(4+numberRowOffset())+(emoji?categoryH:0)+navBottom+dp(4));
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
            if(dictationPanel){arrangePanel();invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);return;}
            if(emojiSearching){keys.add(new Key("emojiquery",0,0,width*.8f,suggestionH,true));keys.add(new Key("emojidone",width*.8f,0,width,suggestionH,true));}
            else if(!numeric&&!emoji&&allowPredictions){for(int i=0;i<3;i++)keys.add(new Key("suggest:"+i,width*i/3,0,width*(i+1)/3,suggestionH,true));}
            if(numeric){float w=Math.min(width,dp(420)),l=(width-w)/2;row(new String[]{"1","2","3"},0,l,w,null);row(new String[]{"4","5","6"},1,l,w,null);row(new String[]{"7","8","9"},2,l,w,null);row(new String[]{".","0","delete","return"},3,l,w,null);}
            else if(emoji){
                keys.add(new Key("emojisearch",0,0,width,suggestionH,true));
                visibleEmoji=emojiCatalog==null?Collections.emptyList():emojiCatalog.select(emojiCategory,emojiQuery,prefs);
                int cols=Math.max(7,(int)(contentWidth/dp(45))),pageSize=cols*3;emojiCols=cols;emojiPageSize=pageSize;emojiPages=Math.max(1,(visibleEmoji.size()+pageSize-1)/pageSize);emojiPage=Math.max(0,Math.min(emojiPage,emojiPages-1));int offset=emojiPage*pageSize;
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
            if(!symbols&&!emoji&&!numeric&&!emojiSearching||letterLayout==null&&!numeric&&!emoji){
                Map<Character,float[]> centers=new HashMap<>();
                for(Key k:keys)if(letter(k))centers.put(k.value.charAt(0),new float[]{k.box.centerX(),k.box.centerY()});
                if(!centers.isEmpty())letterLayout=new EnglishDictionary.Layout(centers,contentWidth/10,rowH);
            }
            invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        private void arrangePanel(){
            float left=panelLeft,right=panelLeft+panelWidth,wellTop=panelTopH,wellBottom=panelTopH+panelWellH;
            well.set(left+keyGapX,wellTop+dp(5),right-keyGapX,wellBottom-dp(5));
            keys.add(new Key("dict:style",right-dp(124),0,right,panelTopH,true));
            keys.add(new Key("dict:start",well.right-dp(132),wellTop,well.right,wellBottom,true));
            keys.add(new Key("dict:action",well.right-dp(132),wellTop,well.right,wellBottom,true));
            keys.add(new Key("dict:cancel",well.left,wellTop,well.left+dp(60),wellBottom,true));
            keys.add(new Key("dict:done",well.right-dp(104),wellTop,well.right,wellBottom,true));
            // The keyboard button shares the bottom row with Space, Delete, and Return.
            rowAt(new String[]{"dict:keys","space","delete","return"},wellBottom,left,panelWidth,new float[]{1.5f,4.4f,1.5f,1.9f});
        }
        private static final int IDLE=0,RECORDING=1,TRANSCRIBING=2,NOTICE=3;
        private int stage(){return dictation!=null?dictationFinishing?TRANSCRIBING:RECORDING:panelNotice!=null?NOTICE:IDLE;}
        private boolean panelKeyVisible(String value){
            int stage=stage();
            return switch(value){
                case "dict:style"->panelEngine.onDevice();
                case "dict:start"->stage==IDLE;
                case "dict:action"->stage==NOTICE;
                case "dict:cancel","dict:done"->stage==RECORDING||stage==TRANSCRIBING;
                default->true;
            };
        }
        void panelChanged(){
            if(focused>=0&&focused<keys.size()&&!keyVisible(keys.get(focused)))focused=-1;
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
        private boolean keyVisible(Key key){
            if(dictationPanel)return panelKeyVisible(key.value);
            return !clipboardVisible()||!key.value.equals("suggest:1");
        }
        private void keyBounds(Key key,RectF bounds){
            bounds.set(key.box);
            if(clipboardVisible()){
                if(key.value.equals("suggest:0"))bounds.right=width-dp(48);
                else if(key.value.equals("suggest:2"))bounds.left=width-dp(48);
            }
        }
        void invalidateLetters(){invalidate(0,(int)suggestionH,getWidth(),Math.round(suggestionH+rowH*(3+numberRowOffset())));}
        private void invalidateKey(Key key){invalidate((int)key.box.left-dp(2),(int)key.box.top-dp(2),(int)key.box.right+dp(2),(int)key.box.bottom+dp(2));}
        private boolean letter(Key k){return k!=null&&k.value.length()==1&&k.value.charAt(0)>='a'&&k.value.charAt(0)<='z'&&!symbols&&!emoji&&!numeric;}
        private String label(Key k){
            if(cursorDragging())return "";
            if(k.value.startsWith("suggest:")){int i=Integer.parseInt(k.value.substring(8));if(clipboardVisible())return i==0?"Paste "+clipboardPreview:i==2?"Dismiss clipboard suggestion":"";return i>=suggestions.length?"":i==0&&correctionPending?"\""+suggestions[i]+"\"":suggestions[i];}
            if(k.value.startsWith("em:"))return k.value.substring(3);
            if(k.value.startsWith("cat:"))return EmojiCatalog.ICONS[Integer.parseInt(k.value.substring(4))];
            return switch(k.value){case "shift"->caps?"Caps lock":"Shift";case "delete"->"Delete";case "space"->"Space";case "emoji"->"Emoji";case "voice"->"Start dictation";
                case "dict:style"->"Dictation style, "+(prefs.getBoolean("dictationCleanup",true)?"Clean":"Verbatim");
                case "dict:start"->"Dictate";
                case "dict:action"->panelNotice==null?actionText():panelNotice+" "+actionText();
                case "dict:cancel"->"Cancel dictation";
                case "dict:done"->dictationFinishing?"Transcribing":"Done";
                case "dict:keys"->"Show keyboard";case "return"->actionLabel();case "more"->second?"123":"#+=";case "emojinext"->"›";case "emojiprev"->"‹";case "emojisearch"->(emojiQuery.isEmpty()?EmojiCatalog.NAMES[emojiCategory]:"\""+emojiQuery+"\"")+"   "+(emojiPage+1)+"/"+emojiPages+"   ⌕";case "emojiquery"->emojiQuery.isEmpty()?"Search emoji":emojiQuery;case "emojidone"->"Search";default->!symbols&&!emoji&&shift?k.value.toUpperCase(Locale.US):k.value;};
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.save();canvas.clipPath(surface);paint.setColor(background);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);canvas.drawPath(surface,paint);paint.setTypeface(normal);
            if(dictationPanel)drawPanel(canvas);
            boolean sliding=emoji&&emojiOffset!=0;
            for(Key k:keys){
                if(sliding&&k.value.startsWith("em:"))continue;
                drawKey(canvas,k);
            }
            if(sliding){
                // Draw the current page shifted with the finger, and the page that is coming in beside it.
                canvas.save();canvas.clipRect(0,suggestionH,width,suggestionH+rowH*3);canvas.translate(emojiOffset,0);
                for(Key k:keys)if(k.value.startsWith("em:"))drawKey(canvas,k);
                int other=emojiPage+(emojiOffset<0?1:-1);
                if(other>=0&&other<emojiPages){canvas.translate(emojiOffset<0?width:-width,0);drawEmojiPage(canvas,other);}
                canvas.restore();
            }
            if(emoji&&visibleEmoji.isEmpty()){paint.setColor(foreground);paint.setAlpha(180);paint.setTextSize(dp(16));drawText(canvas,emojiCatalog==null?"Loading emoji…":"No emoji found",width/2,suggestionH+rowH*1.5f);}
            for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.gliding)drawTrail(canvas,t.points);}
            // The timer, the meter, and the Transcribing spinner move while a recording is open.
            if(dictationPanel&&dictation!=null)postInvalidateOnAnimation(0,0,getWidth(),Math.round(panelTopH+panelWellH));
            if(focused>=0&&focused<keys.size()&&keyVisible(keys.get(focused))){paint.setColor(0xff69a9ff);paint.setAlpha(255);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));keyBounds(keys.get(focused),rect);canvas.drawRoundRect(rect,keyRadius,keyRadius,paint);paint.setStyle(Paint.Style.FILL);}
            canvas.restore();
        }
        private void drawEmojiPage(Canvas canvas,int page){
            paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(shortScreen?27:31));
            int first=page*emojiPageSize;
            for(int i=0;i<emojiPageSize&&i+first<visibleEmoji.size();i++){
                EmojiCatalog.Item item=visibleEmoji.get(i+first);
                float cx=contentLeft+contentWidth*((i%emojiCols)+.5f)/emojiCols,top=suggestionH+(i/emojiCols)*rowH;
                paint.setColor(foreground);paint.setAlpha(255);drawText(canvas,item.emoji,cx,top+rowH/2);
                if(!item.variants.isEmpty()){paint.setAlpha(100);canvas.drawCircle(cx,top+rowH-dp(3),dp(1),paint);}
            }
        }
        private void invalidateEmoji(){invalidate(0,(int)suggestionH,getWidth(),(int)(suggestionH+rowH*3)+1);}
        /** Slides the emoji grid to the next page (1), the previous page (-1) or back into place (0). */
        void settlePage(int direction){
            if(pageAnimator!=null)pageAnimator.end();
            if(!emoji||direction!=0&&(emojiPage+direction<0||emojiPage+direction>=emojiPages))direction=0;
            if(direction==0&&emojiOffset==0)return;
            final int step=direction,category=emojiCategory;
            android.animation.ValueAnimator animator=android.animation.ValueAnimator.ofFloat(emojiOffset,-step*width);
            animator.setDuration(240);animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            animator.addUpdateListener(a->{emojiOffset=(float)a.getAnimatedValue();invalidateEmoji();});
            animator.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator a){
                    if(pageAnimator==a)pageAnimator=null;
                    emojiOffset=0;
                    if(step!=0&&emoji&&category==emojiCategory&&emojiPage+step>=0&&emojiPage+step<emojiPages){emojiPage+=step;rebuild();}
                    else invalidate();
                }
            });
            pageAnimator=animator;animator.start();
        }
        /**
         * Draws the recent part of a glide as one translucent stroke. It is widest under the finger
         * and narrows toward the older end, which then disappears.
         */
        private void drawTrail(Canvas canvas,ArrayList<Float> points){
            int n=points.size();if(n<4)return;
            float visible=dp(230),head=dp(15),tail=dp(2.5f),walked=0;
            boolean darkSurface=Color.red(background)*.299f+Color.green(background)*.587f+Color.blue(background)*.114f<128;
            trailPaint.setColor(darkSurface?0xffffffff:blend(background,foreground,.5f));trailPaint.setAlpha(255);
            // One layer keeps the stroke evenly translucent where its pieces overlap.
            canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),darkSurface?105:120);
            for(int j=n-2;j>=2&&walked<visible;j-=2){
                float x1=points.get(j),y1=points.get(j+1),x0=points.get(j-2),y0=points.get(j-1);
                trailPaint.setStrokeWidth(tail+(head-tail)*(1-walked/visible));
                canvas.drawLine(x0,y0,x1,y1,trailPaint);
                walked+=(float)Math.hypot(x1-x0,y1-y0);
            }
            canvas.restore();
        }
        private void drawKey(Canvas canvas,Key k){
            if(!keyVisible(k))return;
            boolean pressed=false;for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.highlight==k&&!t.gliding)pressed=true;}
            paint.setStyle(Paint.Style.FILL);paint.setColor(foreground);paint.setAlpha(255);
            if(k.value.startsWith("dict:")){drawPanelKey(canvas,k,pressed);return;}
            // Space, Delete, and Return wait while a recording is open, so they are dimmed.
            boolean dim=dictationPanel&&dictation!=null;
            if(dim)canvas.saveLayerAlpha(k.box.left,k.box.top,k.box.right,k.box.bottom,105);
            drawKeyFace(canvas,k,pressed);
            if(dim)canvas.restore();
        }
        private void drawKeyFace(Canvas canvas,Key k,boolean pressed){
            String label=label(k);boolean suggestion=k.value.startsWith("suggest:"),category=k.value.startsWith("cat:"),em=k.value.startsWith("em:");
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
                // The middle item is the word that the next space or punctuation mark will insert.
                if(suggestion&&correctionPending&&k.value.equals("suggest:1")&&!label.isEmpty()){paint.setColor(pressed?blend(keyColor,foreground,.12f):keyColor);rect.set(k.box);rect.inset(dp(7),dp(5));canvas.drawRoundRect(rect,dp(9),dp(9),paint);paint.setColor(foreground);}
                paint.setTextSize(dp(emoji?14:wide?14:15.5f));drawText(canvas,label,k.box.centerX(),k.box.centerY());
                if(suggestion&&!k.value.equals("suggest:2")){paint.setColor(divider);canvas.drawRect(k.box.right-dp(.5f),dp(10),k.box.right+dp(.5f),suggestionH-dp(10),paint);}return;
            }
            if(category){int i=Integer.parseInt(k.value.substring(4));if(i==emojiCategory&&emojiQuery.isEmpty()){paint.setColor(special);rect.set(k.box);rect.inset(dp(3),dp(2));canvas.drawRoundRect(rect,dp(6),dp(6),paint);}paint.setColor(foreground);paint.setTextSize(dp(20));drawText(canvas,label,k.box.centerX(),k.box.centerY());return;}
            rect.set(k.box);rect.inset(keyGapX,keyGapY);
            boolean bare=k.value.equals("emoji")||k.value.equals("voice");
            // iPhone Shift and Backspace leave a wider gap beside the seven letters.
            if(!wide&&!symbols&&!emoji&&!numeric&&!dictationPanel&&(k.value.equals("shift")||k.value.equals("delete"))){
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
            else if(dictationPanel){paint.setTextSize(dp(15));paint.setAlpha(150);drawText(canvas,"space",cx,cy);}
            if(em&&emojiCatalog!=null){EmojiCatalog.Item item=emojiCatalog.byEmoji.get(label);if(item!=null&&!item.variants.isEmpty()){paint.setColor(foreground);paint.setAlpha(100);canvas.drawCircle(k.box.centerX(),k.box.bottom-dp(3),dp(1),paint);}}
        }
        private String actionText(){return "permission".equals(panelAction)?"Allow":"settings".equals(panelAction)?"Settings":"Try again";}
        private boolean darkSurface(){return Color.red(background)*.299f+Color.green(background)*.587f+Color.blue(background)*.114f<128;}
        private int muted(){return blend(background,foreground,.58f);}
        /** The top row, the recessed well, and what the well holds in the current stage. The buttons are drawn as keys. */
        private void drawPanel(Canvas c){
            long now=SystemClock.uptimeMillis();
            int stage=stage();
            float cy=panelTopH/2,x=panelLeft+dp(10);
            paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.LEFT);
            if(stage==RECORDING){
                paint.setColor(0xffff453a);c.drawCircle(x+dp(4),cy,dp(4.5f),paint);
                paint.setColor(foreground);paint.setTextSize(dp(15));paint.setTypeface(medium);paint.setFontFeatureSettings("tnum");
                c.drawText(DictationMode.elapsed(now-recordingStart),x+dp(15),cy-(paint.ascent()+paint.descent())/2,paint);
                paint.setFontFeatureSettings(null);paint.setTypeface(normal);
            }else if(stage==TRANSCRIBING){
                paint.setColor(muted());paint.setTextSize(dp(13.5f));c.drawText("Transcribing…",x,cy-(paint.ascent()+paint.descent())/2,paint);
            }else{
                // A short hint: "Tap" and the keyboard symbol "for keys".
                paint.setColor(muted());paint.setTextSize(dp(13.5f));float baseline=cy-(paint.ascent()+paint.descent())/2;
                c.drawText("Tap",x,baseline,paint);x+=paint.measureText("Tap")+dp(13);
                drawIcon(c,"keyboard",x,cy,dp(19));x+=dp(13);
                paint.setColor(muted());c.drawText("for keys",x,baseline,paint);
            }
            paint.setColor(darkSurface()?0xff131315:blend(background,0xff000000,.075f));
            c.drawRoundRect(well,well.height()/2,well.height()/2,paint);
            float wy=well.centerY(),maxBar=well.height()-dp(22);
            switch(stage){
                case RECORDING->drawMeter(c,well.left+dp(58),well.right-dp(104),wy,maxBar,true,now);
                case TRANSCRIBING->drawMeter(c,well.left+dp(58),well.right-dp(104),wy,maxBar,false,now);
                case IDLE->drawMeter(c,well.left+dp(18),well.right-dp(140),wy,maxBar,false,now);
                default->{
                    paint.setColor(foreground);paint.setTextSize(dp(13));
                    int available=Math.max(1,Math.round(well.width()-dp(160)));
                    android.text.StaticLayout layout=android.text.StaticLayout.Builder.obtain(panelNotice,0,panelNotice.length(),new android.text.TextPaint(paint),available)
                        .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
                    c.save();c.translate(well.left+dp(18),wy-layout.getHeight()/2f);layout.draw(c);c.restore();
                }
            }
            paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
        }
        /**
         * The level meter. Each bar is one 50 ms step, the newest on the right, and the bars
         * scroll left smoothly between steps. Silence and the idle panel show a row of dots.
         */
        private void drawMeter(Canvas c,float left,float right,float cy,float maxHeight,boolean live,long now){
            if(right-left<dp(24))return;
            float spacing=dp(4.5f),bar=dp(2.6f),fade=dp(26);
            if(live)meter.advance(now);
            float shift=live?meter.fraction(now)*spacing:0;
            paint.setStyle(Paint.Style.FILL);paint.setColor(foreground);
            for(int i=0;i<meter.size();i++){
                float x=right-dp(3)-i*spacing-shift;
                if(x<left)break;
                float level=dictation!=null?meter.bar(i):0;
                float h=Math.max(bar,level*maxHeight);
                float edge=Math.max(0,Math.min(1,Math.min((x-left)/fade,(right-x)/dp(8)+.4f)));
                paint.setAlpha(Math.round(255*(live?.92f:.3f)*edge));
                c.drawRoundRect(x-bar/2,cy-h/2,x+bar/2,cy+h/2,bar/2,bar/2,paint);
            }
            paint.setAlpha(255);
        }
        private void drawPanelKey(Canvas c,Key k,boolean pressed){
            float cy=well.centerY(),half=dp(20);
            switch(k.value){
                case "dict:style"->{
                    paint.setColor(muted());paint.setTextSize(dp(14));paint.setTextAlign(Paint.Align.RIGHT);
                    float chevron=k.box.right-dp(12),mid=k.box.centerY();
                    String text=prefs.getBoolean("dictationCleanup",true)?"Clean":"Verbatim";
                    if(pressed){paint.setColor(blend(background,foreground,.12f));float w=paint.measureText(text)+dp(34);rect.set(k.box.right-w,mid-dp(14),k.box.right,mid+dp(14));c.drawRoundRect(rect,dp(14),dp(14),paint);paint.setColor(muted());}
                    c.drawText(text,chevron-dp(9),mid-(paint.ascent()+paint.descent())/2,paint);
                    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.6f));paint.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(chevron-dp(4),mid-dp(2),chevron,mid+dp(2),paint);c.drawLine(chevron,mid+dp(2),chevron+dp(4),mid-dp(2),paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                case "dict:start","dict:action"->{
                    rect.set(well.right-dp(6)-dp(120),cy-half,well.right-dp(6),cy+half);
                    paint.setColor(pressed?blend(keyColor,foreground,.12f):keyColor);c.drawRoundRect(rect,half,half,paint);
                    paint.setColor(foreground);paint.setTextSize(dp(15));paint.setTypeface(medium);paint.setTextAlign(Paint.Align.LEFT);
                    String text=k.value.equals("dict:start")?"Dictate":actionText();
                    float icon=k.value.equals("dict:start")?dp(18)+dp(6):0,w=icon+paint.measureText(text),x=rect.centerX()-w/2;
                    if(icon>0){drawIcon(c,"voice",x+dp(9),cy,dp(18));paint.setColor(foreground);paint.setTypeface(medium);}
                    c.drawText(text,x+icon,cy-(paint.ascent()+paint.descent())/2,paint);paint.setTypeface(normal);
                }
                case "dict:cancel"->{
                    float cx=well.left+dp(6)+half;
                    paint.setColor(pressed?blend(special,foreground,.14f):darkSurface()?special:keyColor);c.drawCircle(cx,cy,half,paint);
                    paint.setColor(foreground);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.8f));paint.setStrokeCap(Paint.Cap.ROUND);
                    float r=dp(5.5f);c.drawLine(cx-r,cy-r,cx+r,cy+r,paint);c.drawLine(cx+r,cy-r,cx-r,cy+r,paint);paint.setStyle(Paint.Style.FILL);
                }
                case "dict:done"->{
                    rect.set(well.right-dp(6)-dp(88),cy-half,well.right-dp(6),cy+half);
                    paint.setColor(pressed?blend(foreground,background,.18f):foreground);c.drawRoundRect(rect,half,half,paint);
                    paint.setColor(background);
                    if(dictationFinishing){
                        float r=dp(8),angle=(SystemClock.uptimeMillis()%900)/900f*360;
                        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);
                        c.drawArc(rect.centerX()-r,cy-r,rect.centerX()+r,cy+r,angle,270,false,paint);paint.setStyle(Paint.Style.FILL);
                    }else{
                        paint.setTextSize(dp(15));paint.setTypeface(medium);paint.setTextAlign(Paint.Align.CENTER);
                        c.drawText("Done",rect.centerX(),cy-(paint.ascent()+paint.descent())/2,paint);paint.setTypeface(normal);
                    }
                }
                case "dict:keys"->{
                    rect.set(k.box);rect.inset(keyGapX,keyGapY);
                    paint.setColor(pressed?blend(special,foreground,.12f):special);c.drawRoundRect(rect,keyRadius,keyRadius,paint);
                    paint.setColor(foreground);drawIcon(c,"keyboard",rect.centerX(),rect.centerY(),dp(25));
                }
                default->{}
            }
            paint.setTextAlign(Paint.Align.CENTER);
        }
        private int blend(int a,int b,float f){return Color.rgb(Math.round(Color.red(a)*(1-f)+Color.red(b)*f),Math.round(Color.green(a)*(1-f)+Color.green(b)*f),Math.round(Color.blue(a)*(1-f)+Color.blue(b)*f));}
        private void drawText(Canvas c,String s,float x,float y){paint.setTextAlign(Paint.Align.CENTER);float max=width/3-dp(16);if(s.length()>20&&paint.measureText(s)>max)s=android.text.TextUtils.ellipsize(s,new android.text.TextPaint(paint),max,android.text.TextUtils.TruncateAt.END).toString();c.drawText(s,x,y-(paint.ascent()+paint.descent())/2,paint);}
        private void drawIcon(Canvas c,String name,float x,float y,float size) {
            c.save();c.translate(x-size/2,y-size/2);c.scale(size/24,size/24);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);iconPath.reset();
            switch(name) {
                case "shift":iconPath.moveTo(12,3);iconPath.lineTo(3,12);iconPath.lineTo(8,12);iconPath.lineTo(8,21);iconPath.lineTo(16,21);iconPath.lineTo(16,12);iconPath.lineTo(21,12);iconPath.close();if(shift)paint.setStyle(Paint.Style.FILL);c.drawPath(iconPath,paint);paint.setStyle(Paint.Style.STROKE);if(caps)c.drawLine(8,23,16,23,paint);break;
                case "delete":iconPath.moveTo(9,5);iconPath.lineTo(22,5);iconPath.lineTo(22,19);iconPath.lineTo(9,19);iconPath.lineTo(2,12);iconPath.close();c.drawPath(iconPath,paint);c.drawLine(12,9,18,15,paint);c.drawLine(18,9,12,15,paint);break;
                case "emoji":c.drawCircle(12,12,9.5f,paint);c.drawArc(6,7,18,18,22,136,false,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(8.5f,9,1.1f,paint);c.drawCircle(15.5f,9,1.1f,paint);break;
                case "keyboard":c.drawRoundRect(2,5.5f,22,18.5f,2.5f,2.5f,paint);paint.setStyle(Paint.Style.FILL);for(int i=0;i<5;i++)c.drawCircle(5.5f+i*3.25f,9.4f,.95f,paint);for(int i=0;i<4;i++)c.drawCircle(7.1f+i*3.25f,12.3f,.95f,paint);paint.setStyle(Paint.Style.STROKE);c.drawLine(8,15.4f,16,15.4f,paint);break;
                case "voice":c.drawRoundRect(9,2,15,15,3,3,paint);c.drawArc(6,7,18,19,0,180,false,paint);c.drawLine(12,19,12,23,paint);c.drawLine(8,23,16,23,paint);break;
                case "return":iconPath.moveTo(20,5);iconPath.lineTo(20,11);iconPath.quadTo(20,15,16,15);iconPath.lineTo(4,15);iconPath.moveTo(9,10);iconPath.lineTo(4,15);iconPath.lineTo(9,20);c.drawPath(iconPath,paint);break;
            }
            paint.setStyle(Paint.Style.FILL);c.restore();
        }
        private Key hit(float x,float y){
            for(Key k:keys){if(!keyVisible(k))continue;keyBounds(k,rect);if(rect.contains(x,y))return k;}
            // The side margins and the indented middle row have no key under them. Use the nearest key there.
            if(dictationPanel||y<suggestionH||emoji)return null;
            Key best=null;float least=dp(40);
            // Prefer a key in the touched row, so the edge beside A gives A and not Q.
            for(Key k:keys){
                if(k.box.top<suggestionH-1||y<k.box.top||y>=k.box.bottom)continue;
                float distance=Math.max(k.box.left-x,x-k.box.right);
                if(distance<least){least=distance;best=k;}
            }
            if(best!=null)return best;
            least=dp(28);
            for(Key k:keys){
                if(k.box.top<suggestionH-1)continue;
                float dx=Math.max(0,Math.max(k.box.left-x,x-k.box.right)),dy=Math.max(0,Math.max(k.box.top-y,y-k.box.bottom)),distance=(float)Math.hypot(dx,dy);
                if(distance<least){least=distance;best=k;}
            }
            return best;
        }
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
            final Key key;Key highlight;final float x,y;final boolean upper;boolean held,committed,gliding,multi,cursor,paging;float velocity;long moveTime;
            float lastX,lastY,cursorX,cursorY;
            final ArrayList<Float> points=new ArrayList<>();Runnable hold;
            Touch(Key key,float x,float y){this.key=highlight=key;this.x=lastX=x;this.y=lastY=y;upper=shift;points.add(x);points.add(y);}
            void point(float x,float y){int n=points.size();if(n<2||Math.hypot(x-points.get(n-2),y-points.get(n-1))>=dp(2)){points.add(x);points.add(y);}}
        }
        private boolean cursorDragging(){return cursorPointer>=0;}
        private void startCursorDrag(Touch t,int id){
            if(touches.get(id)!=t)return;
            if(decodingGlide){pendingActions.add(()->startCursorDrag(t,id));return;}
            cancelRecording();
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
        private void commitTouch(Touch t){if(t.committed||t.held)return;t.committed=true;if(letter(t.key))typeLetter(t.upper?t.key.value.toUpperCase(Locale.US):t.key.value,t.x,t.y);else press(t.key.value);}
        private void hold(Touch t,int id){
            // Held keys in the panel wait for an open recording to finish, like taps.
            if(dictationPanel&&dictation!=null)return;
            String value=t.key.value;
            // Held Backspace removes characters first, then whole words.
            if(value.equals("delete"))t.hold=new Runnable(){int repeats;public void run(){if(touches.get(id)!=t)return;t.held=true;if(++repeats>16){eraseWord();handler.postDelayed(this,140);}else{erase();handler.postDelayed(this,50);}}};
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
                if(pageAnimator!=null)pageAnimator.end();
                boolean multiple=touches.size()>0;
                for(int i=0;i<touches.size();i++){Touch previous=touches.valueAt(i);previous.multi=true;if(previous.hold!=null)handler.removeCallbacks(previous.hold);if(!previous.gliding&&letter(previous.key))commitTouch(previous);}
                Key key=hit(e.getX(index),e.getY(index));if(key==null)return true;
                if(!dictationPanel&&!key.value.equals("voice")&&!key.value.startsWith("suggest:"))cancelRecording();
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
                    if(t.key.value.equals("123")&&symbols){
                        // Sliding from 123 to a symbol types it on release and returns to the letters.
                        Key target=hit(x,y);
                        if(target!=t.highlight){if(t.highlight!=null)invalidateKey(t.highlight);t.highlight=target;if(target!=null)invalidateKey(target);}
                        continue;
                    }
                    if(emoji&&t.key.value.startsWith("em:")&&!t.held&&!t.multi){
                        float total=x-t.x;
                        if(!t.paging&&Math.abs(total)>dp(10)&&Math.abs(total)>Math.abs(y-t.y)){
                            t.paging=true;t.highlight=null;t.moveTime=e.getEventTime();
                            if(t.hold!=null)handler.removeCallbacks(t.hold);
                            hidePreview();
                        }
                        if(t.paging){
                            // The grid follows the finger. It moves less when there is no page on that side.
                            long now=e.getEventTime();
                            if(now>t.moveTime)t.velocity=.6f*t.velocity+.4f*dx/(now-t.moveTime);
                            t.moveTime=now;
                            boolean edge=total>0&&emojiPage==0||total<0&&emojiPage+1>=emojiPages;
                            emojiOffset=edge?total*.3f:total;invalidateEmoji();continue;
                        }
                    }
                    if(distance>dp(16)&&t.hold!=null)handler.removeCallbacks(t.hold);
                    if(letter(t.key)&&!t.committed&&!t.held&&!t.multi&&!emojiSearching&&allowGlide&&prefs.getBoolean("glide",true)&&dictionary!=null){
                        for(int h=0;h<e.getHistorySize();h++)t.point(e.getHistoricalX(i,h),e.getHistoricalY(i,h));t.point(x,y);
                        Key target=hit(x,y);if(!t.gliding&&distance>Math.max(dp(22),t.key.box.width()*.55f)&&letter(target)&&target!=t.key)t.gliding=true;
                        if(t.gliding){hidePreview();t.highlight=target;invalidate();}
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
                    else if(t.key.value.equals("123")&&symbols){
                        Key target=hit(e.getX(index),e.getY(index));
                        if(target!=null&&!target.action&&target.value.length()==1){press(target.value);showLetters();}
                    }
                    else if(t.gliding){
                        t.point(e.getX(index),e.getY(index));float[] path=new float[t.points.size()];for(int i=0;i<path.length;i++)path[i]=t.points.get(i);
                        if(letterLayout!=null)finishGlide(path,letterLayout,t.upper);invalidate();
                    }else if(t.paging){
                        // A drag past a fifth of the width, or a quick flick, moves to the neighboring page.
                        int direction=emojiOffset<0&&(emojiOffset<-width*.2f||t.velocity<-dp(.45f))?1:emojiOffset>0&&(emojiOffset>width*.2f||t.velocity>dp(.45f))?-1:0;
                        settlePage(direction);
                    }
                    else if(t.key.value.startsWith("em:")&&(Math.abs(dx)>dp(36)||Math.abs(dy)>dp(36))){settlePage(dx<0||Math.abs(dy)>Math.abs(dx)&&dy<0?1:-1);}
                    else if(!t.held&&!t.committed)commitTouch(t);
                    touches.remove(id);invalidateKey(t.highlight==null?t.key:t.highlight);
                }
                performClick();return true;
            }
            if(action==MotionEvent.ACTION_CANCEL){cancelTouches();return true;}return true;
        }
        void cancelTouches(){hidePreview();for(int i=0;i<touches.size();i++){Touch t=touches.valueAt(i);if(t.hold!=null)handler.removeCallbacks(t.hold);invalidateKey(t.key);if(t.gliding)invalidate();}touches.clear();if(emojiOffset!=0&&pageAnimator==null)settlePage(0);if(cursorDragging())finishCursorDrag();}
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
