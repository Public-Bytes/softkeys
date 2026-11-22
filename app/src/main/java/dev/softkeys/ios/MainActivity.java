package dev.softkeys.ios;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The SoftKeys settings screen. Settings are grouped in rounded cards. Each row has a title,
 * an optional description under it, and a switch, a value, or an arrow on the right. The
 * controls and sheets come from Ui, and the colors follow the phone's light or dark mode.
 */
public class MainActivity extends Activity {
    private LinearLayout page,card;
    private TextView status;
    private EditText sample;
    private TextView vibrationValue,engineValue,modelValue;
    private LinearLayout vocabularyList;
    private final TextView[] modelStatus=new TextView[SpeechModel.ALL.length],modelButton=new TextView[SpeechModel.ALL.length];
    private TextView editorStatus,editorButton;
    private Ui.Toggle smartSwitch;
    private final SpeechModel.Listener modelListener=this::updateModel;
    private Vibrator vibrator;
    private android.content.SharedPreferences prefs;
    private Ui.Palette p;
    int dp(float n){return Ui.dp(this,n);}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=KeyboardPreferences.open(this);vibrator=Build.VERSION.SDK_INT>=31?getSystemService(VibratorManager.class).getDefaultVibrator():(Vibrator)getSystemService(VIBRATOR_SERVICE);render();}
    @Override public void onResume(){super.onResume();updateStatus();SpeechModel.addListener(modelListener);updateModel();}
    @Override public void onPause(){SpeechModel.removeListener(modelListener);super.onPause();}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)updateStatus();}
    private void updateStatus(){
        if(status==null)return;
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled=false;
        for(android.view.inputmethod.InputMethodInfo method:manager.getEnabledInputMethodList())if(method.getPackageName().equals(getPackageName()))enabled=true;
        String selected="";
        try{selected=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);}catch(SecurityException ignored){}
        status.setText(selected!=null&&selected.contains(getPackageName())?"SoftKeys is your current keyboard.":enabled?"Enabled. Choose SoftKeys to start typing.":"Enable SoftKeys to start typing.");
    }

    TextView text(String label,float size,int color){TextView v=new TextView(this);v.setText(label);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");v.setLineSpacing(0,1.08f);return v;}
    void gap(int n){Space s=new Space(this);page.addView(s,new LinearLayout.LayoutParams(1,dp(n)));}
    private Typeface medium(){return Typeface.create("sans-serif-medium",Typeface.NORMAL);}

    // Cards and rows.

    /** Starts a card with a title above it. Rows added after this go into the card. */
    private void section(String title){
        gap(26);
        TextView label=text(title,14,p.muted);label.setTypeface(medium());label.setPadding(dp(16),0,0,dp(7));
        if(Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);
        page.addView(label);
        card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Ui.rounded(p.card,dp(16)));card.setClipToOutline(true);
        page.addView(card,new LinearLayout.LayoutParams(-1,-2));
    }
    /** A thin line between rows of a card, starting at the text's left edge. */
    private void separate(){
        if(card.getChildCount()==0)return;
        View line=new View(this);line.setBackgroundColor(p.separator);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.6f)));lp.leftMargin=dp(16);
        card.addView(line,lp);
    }
    /** A row with a title, an optional description, and a view on the right. */
    private LinearLayout row(String title,String detail,View end){
        separate();
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(54));row.setPadding(dp(16),dp(11),dp(14),dp(11));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title,17,p.ink));
        if(detail!=null){TextView d=text(detail,13.5f,p.muted);d.setPadding(0,dp(2),0,0);words.addView(d);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        if(end!=null){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(14);row.addView(end,lp);}
        card.addView(row,new LinearLayout.LayoutParams(-1,-2));
        return row;
    }
    private void pressable(View row){row.setBackground(Ui.pressable(p.card,p.pressed,0));row.setFocusable(true);}
    /** A switch row that saves its setting at once. */
    private Ui.Toggle switchRow(String title,String detail,String key,boolean fallback){
        Ui.Toggle toggle=new Ui.Toggle(this,p);toggle.setChecked(prefs.getBoolean(key,fallback),false);toggle.setContentDescription(title);
        toggle.setListener(checked->prefs.edit().putBoolean(key,checked).apply());
        LinearLayout row=row(title,detail,toggle);
        pressable(row);row.setOnClickListener(v->toggle.toggle());row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return toggle;
    }
    private TextView chevron(String value){
        TextView end=text(value==null?"›":value+"   ›",value==null?24:16.5f,p.muted);
        end.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        // A long value must not squeeze the title into a narrow column.
        end.setMaxWidth(dp(150));end.setMaxLines(1);end.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        return end;
    }
    /** A row that shows the chosen value and opens a sheet with the choices. */
    private TextView pickerRow(String title,String detail,String[] choices,IntSupplier current,IntConsumer choose){
        TextView value=chevron(choices[current.getAsInt()]);
        LinearLayout row=row(title,detail,value);pressable(row);
        row.setContentDescription(title+", "+choices[current.getAsInt()]);
        row.setOnClickListener(v->new Ui.Sheet(this,p).title(title).choices(choices,current.getAsInt(),which->{
            choose.accept(which);value.setText(choices[which]+"   ›");row.setContentDescription(title+", "+choices[which]);
        }).show());
        return value;
    }
    /** A row that runs an action, with an arrow or a value on the right. */
    private TextView actionRow(String title,String detail,String value,Runnable action){
        TextView end=chevron(value);
        LinearLayout row=row(title,detail,end);pressable(row);
        row.setOnClickListener(v->action.run());row.setContentDescription(title+(value==null?"":", "+value));
        return end;
    }
    /** A paragraph of help text inside the current card. */
    private void note(String words){
        separate();
        TextView t=text(words,13.5f,p.muted);t.setPadding(dp(16),dp(12),dp(16),dp(13));card.addView(t);
    }
    /** A rounded button. A primary button is filled with the accent color. */
    TextView button(ViewGroup parent,String label,Runnable action,boolean primary){
        TextView b=text(label,16.5f,primary?Color.WHITE:p.accent);b.setTypeface(medium());b.setGravity(Gravity.CENTER);
        b.setBackground(primary?Ui.pressable(p.accent,Ui.blend(p.accent,0xff000000,.15f),dp(14)):Ui.pressable(p.field,p.pressed,dp(14)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(10);
        if(parent==card){lp.leftMargin=lp.rightMargin=dp(16);}
        parent.addView(b,lp);
        b.setOnClickListener(v->action.run());b.setFocusable(true);
        b.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");}});
        return b;
    }
    private void confirm(String title,String message,String action,boolean destructive,Runnable run){
        new Ui.Sheet(this,p).title(title).message(message).action(action,destructive?Ui.Sheet.Style.DESTRUCTIVE:Ui.Sheet.Style.BOLD,run).show();
    }

    void render(){
        p=Ui.Palette.of(this);
        Ui.bars(this,p);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(p.background);scroll.setVerticalScrollBarEnabled(false);
        if(Build.VERSION.SDK_INT>=29)scroll.setEdgeEffectColor(p.muted);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),dp(28),dp(16),dp(36));scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            int bottom=insets.getSystemWindowInsetBottom();
            int top=insets.getSystemWindowInsetTop();
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                bottom=Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);
                top=bars.top;
            }
            v.setPadding(insets.getSystemWindowInsetLeft(),top,insets.getSystemWindowInsetRight(),bottom);
            View focus=getCurrentFocus();
            if(focus instanceof EditText&&bottom>dp(100))v.postDelayed(()->{
                int[] at=new int[2];focus.getLocationInWindow(at);int[] base=new int[2];scroll.getLocationInWindow(base);
                int visible=scroll.getHeight()-scroll.getPaddingTop()-scroll.getPaddingBottom();
                int focusBottom=at[1]-base[1]+scroll.getScrollY()+focus.getHeight();
                if(focusBottom>scroll.getScrollY()+visible)scroll.scrollTo(0,focusBottom-visible+dp(16));
            },100);
            return insets;
        });
        TextView title=text("SoftKeys",36,p.ink);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setLetterSpacing(-.01f);title.setPadding(dp(4),0,0,0);page.addView(title);gap(4);
        TextView tagline=text("An iOS-inspired keyboard for Android",16.5f,p.muted);tagline.setPadding(dp(5),0,0,0);page.addView(tagline);gap(22);
        int keyboard=p.dark?0xff2c2c2e:0xffd1d3d9,keyFace=p.dark?0xff4a4a4e:0xffffffff,keyText=p.dark?0xfff2f2f5:0xff1c1c1e;
        LinearLayout preview=new LinearLayout(this);preview.setOrientation(LinearLayout.VERTICAL);preview.setPadding(dp(8),dp(12),dp(8),dp(12));preview.setBackground(Ui.rounded(keyboard,dp(20)));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for(String letters:new String[]{"q w e r t y u i o p","a s d f g h j k l","⇧ z x c v b n m ⌫"}){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);
            for(String key:letters.split(" ")){
                TextView t=text(key,18,keyText);t.setGravity(Gravity.CENTER);t.setBackground(Ui.rounded(keyFace,dp(7)));t.setElevation(dp(.6f));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(36),1);lp.setMargins(dp(2.5f),dp(4),dp(2.5f),dp(4));row.addView(t,lp);
            }
            if(letters.startsWith("a")){row.setPadding(dp(14),0,dp(14),0);}
            preview.addView(row);
        }
        page.addView(preview);

        section("Setup");
        status=text("",15.5f,p.ink);status.setPadding(dp(16),dp(14),dp(16),dp(12));card.addView(status);
        button(card,"Enable SoftKeys",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),true);
        button(card,"Choose SoftKeys",()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(),false);
        card.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(6)));

        section("Try it");
        sample=new EditText(this);sample.setHint("Tap here to try your keyboard");sample.setTextSize(17);sample.setGravity(Gravity.TOP);
        Ui.field(sample,p);sample.setBackground(null);sample.setPadding(dp(16),dp(14),dp(16),dp(14));
        sample.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);sample.setMinLines(3);sample.setContentDescription("Try your keyboard");
        card.addView(sample,new LinearLayout.LayoutParams(-1,dp(112)));

        appearanceSettings();
        typingSettings();
        dictationSettings();
        styleSettings();
        smartSettings();
        vocabularySettings();

        section("About");
        actionRow("Open source licenses",null,null,this::showLicenses);
        note("English (US), autocorrect, glide typing, dictation, and emoji. Text predictions stay on your phone. On-device dictation keeps audio on your phone. The phone speech service may send audio to its provider.");
        setContentView(scroll);
    }

    private void appearanceSettings(){
        section("Appearance");
        String[] names={"Reference","Dark","Lavender","System"};int[] values={3,0,1,2};
        pickerRow("Theme","Reference uses light gray on narrow displays and lavender on wide ones",names,()->{
            int theme=prefs.getInt("theme",3);for(int i=0;i<values.length;i++)if(values[i]==theme)return i;return 0;
        },which->prefs.edit().putInt("theme",values[which]).apply());
        switchRow("Tablet number row","A number row on wide displays, including unfolded phones","tabletNumberRow",true);
        switchRow("Key popups","Show a larger letter above the key you touch","keyPopups",true);
    }

    private void typingSettings(){
        section("Typing");
        switchRow("Autocorrect","Press Backspace right after a correction to restore your spelling","autocorrect",true);
        switchRow("Glide typing","Slide through letters to type a word","glide",true);
        switchRow("Automatic capitalization","Capitalize sentences while typing and dictating","autocaps",true);
        switchRow("Double-space period","Two spaces type a period and a space","doubleSpacePeriod",true);
        switchRow("Clipboard suggestions","Copied text appears above the keys for two minutes. No history is saved.","clipboardSuggestions",true);
        switchRow("Haptic feedback","Vibrate lightly when you touch a key","haptic",true);
        vibrationValue=actionRow("Keypress vibration",null,vibrationLabel(),this::showVibrationSettings);
        separate();
        LinearLayout hold=new LinearLayout(this);hold.setOrientation(LinearLayout.VERTICAL);hold.setPadding(dp(16),dp(13),dp(16),dp(6));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("Touch and hold",17,p.ink),new LinearLayout.LayoutParams(0,-2,1));
        TextView holdValue=text(KeyboardPreferences.holdDelay(prefs)+" ms",16.5f,p.muted);heading.addView(holdValue);
        hold.addView(heading);
        TextView holdDetail=text("For accents, emoji variants, cursor control, Caps Lock, and repeated deletion",13.5f,p.muted);holdDetail.setPadding(0,dp(2),0,0);hold.addView(holdDetail);
        SeekBar holdDuration=new SeekBar(this);Ui.slider(holdDuration,p);holdDuration.setMax(34);holdDuration.setProgress((KeyboardPreferences.holdDelay(prefs)-150)/25);holdDuration.setContentDescription("Touch and hold, "+holdValue.getText());
        holdDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){int duration=150+progress*25;holdValue.setText(duration+" ms");bar.setContentDescription("Touch and hold, "+duration+" ms");if(fromUser)prefs.edit().putInt("holdDuration",duration).apply();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        hold.addView(holdDuration,new LinearLayout.LayoutParams(-1,dp(48)));
        card.addView(hold);
        actionRow("Reset learned words","Words you kept by restoring your spelling twice or choosing them in the suggestions",null,()->{
            int count=KeyboardPreferences.learnedWords(prefs).size();
            if(count==0){new Ui.Sheet(this,p).title("Reset learned words").message("SoftKeys has not learned any words yet.").cancelLabel("OK").show();return;}
            confirm("Reset learned words","SoftKeys will forget "+count+(count==1?" word":" words")+" that you chose to keep.","Reset",true,()->prefs.edit().remove("learnedWords").apply());
        });
    }

    private void dictationSettings(){
        section("Dictation");
        note("Tap the microphone on the keys for the dictation keyboard, or the microphone in the suggestion bar to dictate with the keys showing. Password, number, and private fields always show the keys.");
        String[] engines={"On-device","Phone service"};String[] engineIds={"device","system"};
        engineValue=pickerRow("Speech recognition","On-device keeps audio on this phone. The phone speech service may send audio to its provider.",engines,
            ()->"device".equals(prefs.getString("dictationEngine","system"))?0:1,which->prefs.edit().putString("dictationEngine",engineIds[which]).apply());
        String[] models=new String[SpeechModel.ALL.length];
        for(int i=0;i<models.length;i++)models[i]=SpeechModel.ALL[i].title;
        modelValue=pickerRow("Speech model",null,models,()->{
            String chosen=SpeechModel.byId(prefs.getString("speechModel","accurate")).id;
            for(int i=0;i<SpeechModel.ALL.length;i++)if(SpeechModel.ALL[i].id.equals(chosen))return i;return 0;
        },which->prefs.edit().putString("speechModel",SpeechModel.ALL[which].id).apply());
        String[] details={
            "Recommended. NVIDIA Parakeet TDT 0.6B v2 makes the fewest mistakes and adds punctuation and capitals. It transcribes after you tap Done, in about a second for a sentence on a recent phone. It uses about 0.9 GB of memory while loaded and up to about 1.4 GB while transcribing.",
            "NVIDIA FastConformer streaming. It needs less storage and memory and finishes almost instantly, but it makes more mistakes and writes no punctuation."};
        for(int i=0;i<SpeechModel.ALL.length;i++){
            SpeechModel model=SpeechModel.ALL[i];
            final int index=i;
            LinearLayout block=modelBlock(model.title+" model",SpeechModel.megabytes(model.size),details[i]);
            modelStatus[i]=(TextView)block.getTag();
            modelButton[i]=button(block,"",()->modelAction(index),false);
        }
        actionRow("Microphone access",null,null,()->startActivity(new Intent(this,MicrophonePermissionActivity.class)));
    }
    /** A model's name and size, its description, and a status line. The status view is the block's tag. */
    private LinearLayout modelBlock(String name,String size,String detail){
        separate();
        LinearLayout block=new LinearLayout(this);block.setOrientation(LinearLayout.VERTICAL);block.setPadding(dp(16),dp(13),dp(16),dp(4));
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(name,17,p.ink),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(text(size,16.5f,p.muted));
        block.addView(heading);
        TextView d=text(detail,13.5f,p.muted);d.setPadding(0,dp(3),0,dp(8));block.addView(d);
        TextView status=text("",14,p.ink);status.setPadding(0,0,0,dp(10));block.addView(status);
        block.setTag(status);
        card.addView(block);
        return block;
    }

    private void styleSettings(){
        section("Dictation style");
        pickerRow("Style","Clean removes um and uh, repeats, and words you take back, and writes numbers and web addresses. Verbatim keeps every word.",new String[]{"Clean","Verbatim"},
            ()->prefs.getBoolean("dictationCleanup",true)?0:1,which->prefs.edit().putBoolean("dictationCleanup",which==0).apply());
        switchRow("Spoken punctuation","Say \"comma\" or \"question mark\" for the mark","spokenPunctuation",true);
        switchRow("Format lists","Say \"first\", \"second\", or \"bullet\" for a list","formatLists",true);
        switchRow("End with a period","Off drops the final period and keeps ? and !","endPeriod",true);
        switchRow("Lowercase","Writes everything in lowercase letters","dictationLowercase",false);
        note("Styles apply to on-device dictation. Spoken punctuation and lists apply in the Clean style. Dictation stays active through pauses until you tap Done, with no time limit.");
    }

    private void smartSettings(){
        section("Smart cleanup");
        if(!SmartEditor.supported()){
            note("Smart cleanup needs a 64-bit ARM processor with dot product instructions, which this phone doesn't have. The Clean style still removes fillers and corrections with its rules.");
            return;
        }
        smartSwitch=switchRow("Smart cleanup","An optional language model that also removes words you take back","smartCleanup",false);
        smartSwitch.setListener(checked->{
            prefs.edit().putBoolean("smartCleanup",checked).apply();
            SpeechModel.State state=SpeechModel.EDITOR.state(this);
            if(checked&&state!=SpeechModel.State.READY&&state!=SpeechModel.State.DOWNLOADING)editorAction();
        });
        SpeechModel model=SpeechModel.EDITOR;
        LinearLayout block=modelBlock("Handy Editor 350M",SpeechModel.megabytes(model.size),
            "A small text model that runs on this phone after the speech model, for on-device dictation in the Clean style. It adds a short wait after Done and uses about 400 MB of memory while you dictate. If its edit adds or changes any word, SoftKeys ignores it and uses the normal Clean text.");
        editorStatus=(TextView)block.getTag();
        editorButton=button(block,"",this::editorAction,false);
    }
    private void editorAction(){
        SpeechModel model=SpeechModel.EDITOR;
        String size=SpeechModel.megabytes(model.size);
        switch(model.state(this)){
            case DOWNLOADING->model.cancel();
            case READY->confirm("Delete smart cleanup model","SoftKeys will remove the "+size+" model and turn smart cleanup off.","Delete",true,
                ()->{model.delete(this);smartSwitch.setChecked(false,true);});
            default->new Ui.Sheet(this,p).title("Download smart cleanup model")
                .message("SoftKeys will download "+model.name+" ("+size+") from Hugging Face and check it before use. Use Wi-Fi if your data plan is limited. The download sends no audio or text. The model is licensed under the LFM Open License v1.0.")
                .action("Download",Ui.Sheet.Style.BOLD,()->{model.download(this);smartSwitch.setChecked(true,true);})
                .onCancel(()->{if(!model.installed(this))smartSwitch.setChecked(false,true);}).show();
        }
    }

    private void vocabularySettings(){
        section("Custom vocabulary");
        switchRow("Custom vocabulary","Spell names and terms your way","vocabularyEnabled",true);
        separate();
        vocabularyList=new LinearLayout(this);vocabularyList.setOrientation(LinearLayout.VERTICAL);vocabularyList.setPadding(dp(16),dp(10),dp(16),0);card.addView(vocabularyList);
        EditText add=new EditText(this);add.setHint("Add a word or phrase");add.setTextSize(16.5f);add.setSingleLine(true);Ui.field(add,p);
        add.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);add.setContentDescription("Add a word or phrase to the custom vocabulary");
        add.setOnEditorActionListener((v,action,event)->{
            String word=add.getText().toString().trim().replaceAll("\\s+"," ");
            if(word.isEmpty())return false;
            List<String> words=vocabulary();
            boolean present=false;for(String w:words)present|=w.equalsIgnoreCase(word);
            if(!present){words.add(word);saveVocabulary(words);}
            add.setText("");showVocabulary();
            return true;
        });
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(16),0,dp(16),dp(14));card.addView(add,lp);
        note("Each entry fixes its spelling and capitals, so \"testflight\" becomes TestFlight and \"swift ui\" becomes SwiftUI. A name of 4 or more letters also fixes words that sound like it. It fixes spelling, not words the model mishears completely.");
        actionRow("Word replacements","Rules such as \"soft keys => SoftKeys\", for anything the list can't express",null,this::showReplacements);
        showVocabulary();
    }
    private List<String> vocabulary(){
        List<String> words=new ArrayList<>();
        for(String line:prefs.getString("dictationVocabulary","").split("\n"))if(!line.trim().isEmpty())words.add(line.trim());
        return words;
    }
    private void saveVocabulary(List<String> words){prefs.edit().putString("dictationVocabulary",String.join("\n",words)).apply();}
    private void showVocabulary(){
        vocabularyList.removeAllViews();
        for(String word:vocabulary()){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(Ui.rounded(p.field,dp(12)));row.setPadding(dp(14),0,dp(4),0);
            row.addView(text(word,16.5f,p.ink),new LinearLayout.LayoutParams(0,-2,1));
            TextView remove=text("✕",15,p.muted);remove.setGravity(Gravity.CENTER);remove.setContentDescription("Remove "+word);
            remove.setBackground(Ui.pressable(p.field,p.pressed,dp(20)));
            remove.setOnClickListener(v->{List<String> words=vocabulary();words.remove(word);saveVocabulary(words);showVocabulary();});
            row.addView(remove,new LinearLayout.LayoutParams(dp(40),dp(40)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(46));lp.bottomMargin=dp(8);vocabularyList.addView(row,lp);
        }
    }

    private void showLicenses(){
        try(java.io.InputStream source=getAssets().open("licenses/NOTICE.txt")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=source.read(buffer))!=-1)bytes.write(buffer,0,n);String notice=bytes.toString("UTF-8");
            TextView content=text(notice,13.5f,p.ink);android.text.util.Linkify.addLinks(content,android.text.util.Linkify.WEB_URLS);content.setLinkTextColor(p.accent);content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            new Ui.Sheet(this,p).title("Open source licenses").content(content).cancelLabel("Close").show();
        }catch(java.io.IOException ignored){}
    }
    private void updateModel(){
        if(editorStatus!=null)showModel(SpeechModel.EDITOR,editorStatus,editorButton,"smart cleanup model");
        for(int i=0;i<SpeechModel.ALL.length;i++){
            if(modelStatus[i]!=null)showModel(SpeechModel.ALL[i],modelStatus[i],modelButton[i],SpeechModel.ALL[i].title+" model");
        }
    }
    private void showModel(SpeechModel model,TextView status,TextView button,String name){
        String size=SpeechModel.megabytes(model.size);
        switch(model.state(this)){
            case READY->{status.setText("Downloaded. It uses "+size+" on this phone.");button.setText("Delete");button.setTextColor(p.red);}
            case DOWNLOADING->{long received=model.received();status.setText("Downloading… "+(received*100/model.size)+"% ("+SpeechModel.megabytes(received)+" of "+size+")");button.setText("Cancel download");button.setTextColor(p.accent);}
            case FAILED->{status.setText(model.failure());button.setText("Download");button.setTextColor(p.accent);}
            default->{status.setText("Not downloaded.");button.setText("Download");button.setTextColor(p.accent);}
        }
        button.setContentDescription(button.getText()+", "+name);
    }
    private void modelAction(int index){
        SpeechModel model=SpeechModel.ALL[index];
        String size=SpeechModel.megabytes(model.size);
        switch(model.state(this)){
            case DOWNLOADING->model.cancel();
            case READY->confirm("Delete "+model.title.toLowerCase(java.util.Locale.US)+" model","SoftKeys will remove the "+size+" model. Dictation will use the other downloaded model or the phone speech service until you download it again.","Delete",true,()->model.delete(this));
            default->confirm("Download "+model.title.toLowerCase(java.util.Locale.US)+" model","SoftKeys will download "+model.name+" ("+size+") from Hugging Face and check it before use. Use Wi-Fi if your data plan is limited. The download sends no audio or text.","Download",false,()->{
                model.download(this);
                // Downloading a model also chooses it and on-device recognition.
                prefs.edit().putString("dictationEngine","device").putString("speechModel",model.id).apply();
                engineValue.setText("On-device   ›");modelValue.setText(model.title+"   ›");
            });
        }
    }
    private void showReplacements(){
        EditText rules=new EditText(this);rules.setTextSize(16);rules.setGravity(Gravity.TOP);rules.setMinLines(5);rules.setHint("soft keys => SoftKeys");Ui.field(rules,p);
        rules.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        rules.setText(prefs.getString("dictationReplacements",""));rules.setContentDescription("Word replacement rules");
        new Ui.Sheet(this,p).title("Word replacements")
            .message("One rule per line, such as \"soft keys => SoftKeys\". Leave the right side empty to remove a word. A rule's written word also fixes words that sound like it. Rules apply to on-device dictation in the Clean style.")
            .content(rules).action("Save",Ui.Sheet.Style.BOLD,()->prefs.edit().putString("dictationReplacements",rules.getText().toString()).apply()).show();
    }
    private String vibrationLabel(){return KeyboardPreferences.vibrationDuration(prefs)+" ms";}
    private void previewVibration(int duration){if(prefs.getBoolean("haptic",true)&&vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createOneShot(duration,VibrationEffect.DEFAULT_AMPLITUDE));}
    private void showVibrationSettings(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView value=text(vibrationLabel(),34,p.ink);value.setTypeface(medium());value.setGravity(Gravity.CENTER);value.setFontFeatureSettings("tnum");
        content.addView(value,new LinearLayout.LayoutParams(-1,-2));
        SeekBar strength=new SeekBar(this);Ui.slider(strength,p);strength.setMax(99);strength.setProgress(KeyboardPreferences.vibrationDuration(prefs)-1);strength.setContentDescription("Keypress vibration, "+vibrationLabel());
        content.addView(strength,new LinearLayout.LayoutParams(-1,dp(52)));
        final long[] lastPreview={0};
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){int duration=progress+1;value.setText(duration+" ms");bar.setContentDescription("Keypress vibration, "+duration+" ms");if(fromUser){long now=SystemClock.uptimeMillis();if(now-lastPreview[0]>=80){previewVibration(duration);lastPreview[0]=now;}}}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){previewVibration(bar.getProgress()+1);}
        });
        new Ui.Sheet(this,p).title("Keypress vibration").message("Shorter vibrations feel lighter. Previews play while Haptic feedback is on.").content(content)
            .action("Save",Ui.Sheet.Style.BOLD,()->{int duration=strength.getProgress()+1;prefs.edit().putInt("vibrationDuration",duration).apply();vibrationValue.setText(vibrationLabel()+"   ›");})
            .action("Default ("+KeyboardPreferences.DEFAULT_VIBRATION_DURATION+" ms)",Ui.Sheet.Style.NORMAL,()->{prefs.edit().putInt("vibrationDuration",KeyboardPreferences.DEFAULT_VIBRATION_DURATION).apply();vibrationValue.setText(vibrationLabel()+"   ›");previewVibration(KeyboardPreferences.DEFAULT_VIBRATION_DURATION);})
            .show();
    }
}
