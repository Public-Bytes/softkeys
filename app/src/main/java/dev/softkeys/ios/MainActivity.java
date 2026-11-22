package dev.softkeys.ios;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

public class MainActivity extends Activity {
    private LinearLayout page;
    private TextView status;
    private EditText sample;
    private TextView vibrationSetting;
    private final TextView[] modelStatus=new TextView[SpeechModel.ALL.length],modelButton=new TextView[SpeechModel.ALL.length];
    private final SpeechModel.Listener modelListener=this::updateModel;
    private Vibrator vibrator;
    private android.content.SharedPreferences prefs;
    int ink=Color.rgb(34,34,39), muted=Color.rgb(107,105,117);
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state); prefs=KeyboardPreferences.open(this);vibrator=Build.VERSION.SDK_INT>=31?getSystemService(VibratorManager.class).getDefaultVibrator():(Vibrator)getSystemService(VIBRATOR_SERVICE);render();}
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
        status.setText(selected!=null&&selected.contains(getPackageName())?"SoftKeys is your current keyboard":enabled?"Enabled. Choose SoftKeys to start typing.":"Enable SoftKeys to start typing.");
    }

    TextView text(String label,int size,int color){TextView v=new TextView(this);v.setText(label);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    GradientDrawable bg(int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(radius));return b;}
    void gap(int n){Space s=new Space(this);page.addView(s,new LinearLayout.LayoutParams(1,dp(n)));}
    void render(){
        getWindow().setStatusBarColor(0xfff6f4fa);getWindow().setNavigationBarColor(0xfff6f4fa);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff6f4fa);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(24),dp(24),dp(28));scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            int bottom=insets.getSystemWindowInsetBottom();
            int top=insets.getSystemWindowInsetTop();
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                bottom=Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);
                top=bars.top;
            }
            v.setPadding(insets.getSystemWindowInsetLeft(),top,insets.getSystemWindowInsetRight(),bottom);
            if(sample!=null&&sample.hasFocus()&&bottom>dp(100))v.postDelayed(()->scroll.scrollTo(0,Math.max(0,sample.getBottom()-(scroll.getHeight()-scroll.getPaddingTop()-scroll.getPaddingBottom())+dp(16))),100);
            return insets;
        });
        TextView brand=text("SOFTKEYS",12,muted);brand.setLetterSpacing(.2f);page.addView(brand);gap(18);
        TextView title=text("SoftKeys",34,ink);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));page.addView(title);gap(10);
        page.addView(text("An iOS-inspired keyboard for Android.",16,muted));gap(24);
        LinearLayout preview=new LinearLayout(this);preview.setOrientation(LinearLayout.VERTICAL);preview.setPadding(dp(12),dp(14),dp(12),dp(14));preview.setBackground(bg(0xffdcd5e9,22));
        for(String letters:new String[]{"q w e r t y u i o p","a s d f g h j k l","⇧ z x c v b n m ⌫"}){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);for(String key:letters.split(" ")){TextView t=text(key,18,0xff3d3748);t.setGravity(Gravity.CENTER);t.setBackground(bg(0xfff1ebf8,7));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(34),1);lp.setMargins(dp(2),dp(3),dp(2),dp(3));row.addView(t,lp);}preview.addView(row);}page.addView(preview);gap(22);
        status=text("",14,muted);page.addView(status);gap(12);
        button("1   Enable SoftKeys",()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),true);
        button("2   Choose SoftKeys",()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(),false);gap(20);
        TextView appearance=text("Appearance",19,ink);appearance.setTypeface(null,Typeface.BOLD);page.addView(appearance);gap(8);
        RadioGroup themes=new RadioGroup(this);themes.setOrientation(LinearLayout.VERTICAL);
        String[] names={"Reference","Dark","Lavender","System"};int[] values={3,0,1,2};
        for(int i=0;i<names.length;i++){RadioButton radio=new RadioButton(this);radio.setText(names[i]);radio.setTextColor(ink);radio.setId(100+values[i]);themes.addView(radio,new RadioGroup.LayoutParams(-1,dp(44)));if(prefs.getInt("theme",3)==values[i])radio.setChecked(true);}
        themes.setOnCheckedChangeListener((g,id)->prefs.edit().putInt("theme",id-100).apply());page.addView(themes);
        page.addView(text("Reference uses light gray on narrow displays and lavender on wide displays.",13,muted));gap(8);
        toggle("Tablet number row", "tabletNumberRow", true);
        page.addView(text("Show a dedicated number row on wide displays, including unfolded phones.",13,muted));gap(8);
        toggle("Haptic feedback", "haptic", true);
        vibrationSetting=button(vibrationLabel(),this::showVibrationSettings,false);
        toggle("Key popups", "keyPopups", true);
        TextView holdLabel=text("Touch and hold duration: "+KeyboardPreferences.holdDelay(prefs)+" ms",16,ink);page.addView(holdLabel);gap(4);
        SeekBar holdDuration=new SeekBar(this);holdDuration.setMax(34);holdDuration.setProgress((KeyboardPreferences.holdDelay(prefs)-150)/25);holdDuration.setContentDescription(holdLabel.getText());
        holdDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){int duration=150+progress*25;holdLabel.setText("Touch and hold duration: "+duration+" ms");bar.setContentDescription(holdLabel.getText());if(fromUser)prefs.edit().putInt("holdDuration",duration).apply();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });page.addView(holdDuration,new LinearLayout.LayoutParams(-1,dp(48)));
        page.addView(text("Choose 150 to 1000 ms. Applies to accents, emoji variants, cursor control, Caps Lock, and repeated deletion.",13,muted));gap(12);
        dictationSettings();
        toggle("Autocorrect", "autocorrect", true);
        toggle("Glide typing", "glide", true);
        toggle("Clipboard suggestions", "clipboardSuggestions", true);
        page.addView(text("Recently copied text appears above the keys for up to two minutes. Paste it, dismiss it, or start typing to hide it. No clipboard history is saved.",13,muted));gap(8);
        toggle("Automatic capitalization", "autocaps", true);
        toggle("Double-space period", "doubleSpacePeriod", true);
        page.addView(text("Slide through letters to type a word. Tap a suggestion to change it. Press Backspace immediately after an automatic correction to restore your spelling.",13,muted));gap(12);
        button("Reset learned words",()->{
            int count=KeyboardPreferences.learnedWords(prefs).size();
            new android.app.AlertDialog.Builder(this).setTitle("Reset learned words").setMessage(count==0?"SoftKeys has not learned any words yet.":"SoftKeys will forget "+count+(count==1?" word":" words")+" that you chose to keep.")
                .setNegativeButton("Cancel",null).setPositiveButton("Reset",(dialog,which)->prefs.edit().remove("learnedWords").apply()).show();
        },false);
        page.addView(text("When you restore your own spelling twice, or choose it in the suggestions, SoftKeys saves that word on this phone and stops correcting it.",13,muted));gap(12);
        button("Open source licenses",()->{
            try(java.io.InputStream source=getAssets().open("licenses/NOTICE.txt")){
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=source.read(buffer))!=-1)bytes.write(buffer,0,n);String notice=bytes.toString("UTF-8");
                TextView content=text(notice,14,ink);content.setPadding(dp(20),dp(12),dp(20),dp(12));android.text.util.Linkify.addLinks(content,android.text.util.Linkify.WEB_URLS);content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                ScrollView licenseScroll=new ScrollView(this);licenseScroll.addView(content);new android.app.AlertDialog.Builder(this).setTitle("Open source licenses").setView(licenseScroll).setPositiveButton("Close",null).show();
            }catch(java.io.IOException ignored){}
        },false);gap(12);
        sample=new EditText(this);sample.setTextColor(ink);sample.setHintTextColor(muted);sample.setHint("Tap here to try your keyboard");sample.setTextSize(17);sample.setGravity(Gravity.TOP);sample.setPadding(dp(16),dp(15),dp(16),dp(15));sample.setBackground(bg(Color.WHITE,14));sample.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);sample.setMinLines(3);sample.setContentDescription("Try your keyboard");page.addView(sample,new LinearLayout.LayoutParams(-1,dp(112)));gap(16);
        page.addView(text("English (US), autocorrect, glide typing, dictation and emoji\nText predictions stay on your phone. On-device dictation keeps audio on your phone. The phone speech service may send audio to its provider.",12,muted));
        setContentView(scroll);
    }
    private void dictationSettings(){
        TextView title=text("Dictation",19,ink);title.setTypeface(null,Typeface.BOLD);page.addView(title);gap(8);
        page.addView(text("Tap the microphone on the keys to switch to the dictation keyboard, which has no letter keys. Tap Dictate to record and Done to insert the text. Tap the keyboard button to bring the keys back. SoftKeys keeps showing the one you used last. Password, number, and private fields always show the keys.",13,muted));gap(8);
        RadioGroup engines=new RadioGroup(this);engines.setOrientation(LinearLayout.VERTICAL);
        String[] names={"On-device (Hex-style)","Phone speech service"};String[] values={"device","system"};
        String engine=prefs.getString("dictationEngine","system");
        for(int i=0;i<names.length;i++){RadioButton radio=new RadioButton(this);radio.setText(names[i]);radio.setTextColor(ink);radio.setId(200+i);engines.addView(radio,new RadioGroup.LayoutParams(-1,dp(44)));if(values[i].equals(engine))radio.setChecked(true);}
        engines.setOnCheckedChangeListener((g,id)->prefs.edit().putString("dictationEngine",values[id-200]).apply());page.addView(engines);
        page.addView(text("On-device dictation uses a speech model that SoftKeys downloads once. Speech is recognized on this phone, and audio is never sent or saved. Until a model is downloaded, SoftKeys uses the phone speech service, which may send audio to its provider.",13,muted));gap(12);
        TextView models=text("Speech model",16,ink);models.setTypeface(null,Typeface.BOLD);page.addView(models);gap(4);
        String[] details={
            "Recommended. NVIDIA Parakeet TDT 0.6B v2 makes the fewest mistakes and adds punctuation and capitals. It transcribes after you tap Done, in about a second for a sentence on a recent phone. It uses about 0.9 GB of memory while loaded and up to about 1.4 GB while transcribing.",
            "NVIDIA FastConformer streaming. It needs less storage and memory and finishes almost instantly, but it makes more mistakes and writes no punctuation."};
        RadioGroup choice=new RadioGroup(this);choice.setOrientation(LinearLayout.VERTICAL);
        String chosen=SpeechModel.byId(prefs.getString("speechModel","accurate")).id;
        for(int i=0;i<SpeechModel.ALL.length;i++){
            SpeechModel model=SpeechModel.ALL[i];
            RadioButton radio=new RadioButton(this);radio.setText(model.title+" ("+SpeechModel.megabytes(model.size)+")");radio.setTextColor(ink);radio.setId(300+i);
            choice.addView(radio,new RadioGroup.LayoutParams(-1,dp(44)));if(model.id.equals(chosen))radio.setChecked(true);
            TextView detail=text(details[i],13,muted);detail.setPadding(dp(32),0,0,dp(4));choice.addView(detail);
            modelStatus[i]=text("",14,ink);modelStatus[i].setPadding(dp(32),dp(2),0,dp(6));choice.addView(modelStatus[i]);
            final int index=i;
            modelButton[i]=text("",15,ink);modelButton[i].setTypeface(null,Typeface.BOLD);modelButton[i].setGravity(Gravity.CENTER);modelButton[i].setBackground(bg(0xffe8e4ee,14));
            modelButton[i].setOnClickListener(v->modelAction(index));
            RadioGroup.LayoutParams lp=new RadioGroup.LayoutParams(-1,dp(48));lp.leftMargin=dp(32);lp.bottomMargin=dp(10);choice.addView(modelButton[i],lp);
        }
        choice.setOnCheckedChangeListener((g,id)->{if(id>=300)prefs.edit().putString("speechModel",SpeechModel.ALL[id-300].id).apply();});
        page.addView(choice);gap(8);
        TextView style=text("Style",16,ink);style.setTypeface(null,Typeface.BOLD);page.addView(style);gap(4);
        RadioGroup styles=new RadioGroup(this);styles.setOrientation(LinearLayout.VERTICAL);
        String[] styleNames={"Clean","Verbatim"};
        for(int i=0;i<styleNames.length;i++){RadioButton radio=new RadioButton(this);radio.setText(styleNames[i]);radio.setTextColor(ink);radio.setId(400+i);styles.addView(radio,new RadioGroup.LayoutParams(-1,dp(44)));}
        ((RadioButton)styles.getChildAt(prefs.getBoolean("dictationCleanup",true)?0:1)).setChecked(true);
        styles.setOnCheckedChangeListener((g,id)->prefs.edit().putBoolean("dictationCleanup",id==400).apply());page.addView(styles);
        page.addView(text("Clean removes um and uh, stutters, and repeated words, applies spoken punctuation and your word replacements, fixes spacing, and capitalizes sentences. Verbatim keeps every word and fixes only spacing and capitals. You can also switch the style on the dictation keyboard. Styles apply to on-device dictation.",13,muted));gap(8);
        toggle("Spoken punctuation","spokenPunctuation",true);
        page.addView(text("With spoken punctuation in the Clean style, say \"comma\", \"period\", \"question mark\", or \"new line\".",13,muted));gap(8);
        button("Word replacements",this::showReplacements,false);
        button("Microphone access",()->startActivity(new Intent(this,MicrophonePermissionActivity.class)),false);
        page.addView(text("Dictation stays active through pauses until you tap Done. There is no time limit.",13,muted));gap(8);
    }
    private void updateModel(){
        for(int i=0;i<SpeechModel.ALL.length;i++){
            if(modelStatus[i]==null)continue;
            SpeechModel model=SpeechModel.ALL[i];
            String size=SpeechModel.megabytes(model.size);
            switch(model.state(this)){
                case READY->{modelStatus[i].setText("Downloaded. It uses "+size+" on this phone.");modelButton[i].setText("Delete");}
                case DOWNLOADING->{long received=model.received();modelStatus[i].setText("Downloading… "+(received*100/model.size)+"% ("+SpeechModel.megabytes(received)+" of "+size+")");modelButton[i].setText("Cancel download");}
                case FAILED->{modelStatus[i].setText(model.failure());modelButton[i].setText("Download ("+size+")");}
                default->{modelStatus[i].setText("Not downloaded.");modelButton[i].setText("Download ("+size+")");}
            }
            modelButton[i].setContentDescription(modelButton[i].getText()+", "+model.title+" model");
        }
    }
    private void modelAction(int index){
        SpeechModel model=SpeechModel.ALL[index];
        String size=SpeechModel.megabytes(model.size);
        switch(model.state(this)){
            case DOWNLOADING->model.cancel();
            case READY->new android.app.AlertDialog.Builder(this).setTitle("Delete "+model.title.toLowerCase(java.util.Locale.US)+" model")
                .setMessage("SoftKeys will remove the "+size+" model. Dictation will use the other downloaded model or the phone speech service until you download it again.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete",(dialog,which)->model.delete(this)).show();
            default->new android.app.AlertDialog.Builder(this).setTitle("Download "+model.title.toLowerCase(java.util.Locale.US)+" model")
                .setMessage("SoftKeys will download "+model.name+" ("+size+") from Hugging Face and check it before use. Use Wi-Fi if your data plan is limited. The download sends no audio or text.")
                .setNegativeButton("Cancel",null).setPositiveButton("Download",(dialog,which)->{
                    model.download(this);
                    if(!"device".equals(prefs.getString("dictationEngine","system")))((RadioButton)findViewById(200)).setChecked(true);
                    ((RadioButton)findViewById(300+index)).setChecked(true);
                }).show();
        }
    }
    private void showReplacements(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(8),dp(24),dp(8));
        content.addView(text("Write one rule per line, such as \"soft keys => SoftKeys\". Leave the right side empty to remove a word. A line with only one word, such as \"Kaczmarek\", also works. Dictation then fixes any word that sounds like it, so one rule covers every way the model misspells a name. Rules apply to on-device dictation in the Clean style.",14,muted));
        EditText rules=new EditText(this);rules.setTextColor(ink);rules.setTextSize(16);rules.setGravity(Gravity.TOP);rules.setMinLines(5);rules.setHint("soft keys => SoftKeys");
        rules.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        rules.setText(prefs.getString("dictationReplacements",""));rules.setContentDescription("Word replacement rules");
        content.addView(rules,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setTitle("Word replacements").setView(scroll).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(dialog,which)->prefs.edit().putString("dictationReplacements",rules.getText().toString()).apply()).show();
    }
    void toggle(String label,String key,boolean fallback){Switch option=new Switch(this);option.setText(label);option.setTextColor(ink);option.setChecked(prefs.getBoolean(key,fallback));option.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean(key,checked).apply());page.addView(option,new LinearLayout.LayoutParams(-1,dp(52)));}
    private String vibrationLabel(){return "Keypress vibration: "+KeyboardPreferences.vibrationDuration(prefs)+" ms";}
    private void previewVibration(int duration){if(prefs.getBoolean("haptic",true)&&vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createOneShot(duration,VibrationEffect.DEFAULT_AMPLITUDE));}
    private void showVibrationSettings(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView explanation=text("Choose 1 to 100 ms. Shorter vibrations feel lighter. Previews play while Haptic feedback is on.",14,muted);content.addView(explanation);
        LinearLayout valueRow=new LinearLayout(this);valueRow.setGravity(Gravity.CENTER);EditText value=new EditText(this);value.setTextColor(ink);value.setTextSize(24);value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);value.setSingleLine(true);value.setSelectAllOnFocus(true);value.setGravity(Gravity.CENTER);value.setContentDescription("Vibration duration in milliseconds");value.setText(String.valueOf(KeyboardPreferences.vibrationDuration(prefs)));valueRow.addView(value,new LinearLayout.LayoutParams(dp(88),dp(56)));valueRow.addView(text("ms",18,muted));content.addView(valueRow);
        SeekBar strength=new SeekBar(this);strength.setMax(99);strength.setProgress(KeyboardPreferences.vibrationDuration(prefs)-1);strength.setContentDescription(vibrationLabel());content.addView(strength,new LinearLayout.LayoutParams(-1,dp(52)));
        final long[] lastPreview={0};
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){int duration=progress+1;bar.setContentDescription("Keypress vibration: "+duration+" ms");if(fromUser){value.setText(String.valueOf(duration));value.setSelection(value.length());long now=SystemClock.uptimeMillis();if(now-lastPreview[0]>=80){previewVibration(duration);lastPreview[0]=now;}}}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){previewVibration(bar.getProgress()+1);}
        });
        value.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){try{int duration=Integer.parseInt(s.toString());if(duration>=1&&duration<=100)strength.setProgress(duration-1);}catch(NumberFormatException ignored){}}public void afterTextChanged(android.text.Editable text){}});
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Keypress vibration").setView(content).setNeutralButton("Default",null).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{value.setText(String.valueOf(KeyboardPreferences.DEFAULT_VIBRATION_DURATION));previewVibration(KeyboardPreferences.DEFAULT_VIBRATION_DURATION);});
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{int duration;try{duration=Integer.parseInt(value.getText().toString());}catch(NumberFormatException invalid){value.setError("Enter 1 to 100 ms");return;}if(duration<1||duration>100){value.setError("Enter 1 to 100 ms");return;}prefs.edit().putInt("vibrationDuration",duration).apply();vibrationSetting.setText(vibrationLabel());previewVibration(duration);dialog.dismiss();});
        });dialog.show();
    }
    TextView button(String label,Runnable action,boolean primary){TextView b=text(label,16,primary?Color.WHITE:ink);b.setTypeface(null,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setBackground(bg(primary?ink:0xffe8e4ee,14));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.bottomMargin=dp(8);page.addView(b,lp);b.setOnClickListener(v->action.run());b.setFocusable(true);b.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");}});return b;}
}
