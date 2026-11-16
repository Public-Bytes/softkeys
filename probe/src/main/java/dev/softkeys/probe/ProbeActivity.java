package dev.softkeys.probe;
import android.app.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
public class ProbeActivity extends Activity {
    EditText field;TextView result;String mode="Text";
    @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,4,16,4);root.setBackgroundColor(0xfff5f3f8);LinearLayout tabs=new LinearLayout(this);
    for(String label:new String[]{"Text","Number","Password","Search"}){Button tab=new Button(this);tab.setText(label);tab.setTextSize(11);tabs.addView(tab,new LinearLayout.LayoutParams(0,90,1));tab.setOnClickListener(v->select(label));}root.addView(tabs);
    field=new EditText(this);field.setTextSize(18);field.setContentDescription("Test input");root.addView(field,new LinearLayout.LayoutParams(-1,100));result=new TextView(this);result.setTextSize(15);root.addView(result);field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){result.setText(mode.equals("Password")?"Password length: "+s.length():"Typed: "+s);}public void afterTextChanged(Editable e){}});
    field.setOnEditorActionListener((v,a,e)->{result.setText("Action: "+a+" / "+field.getText());return true;});setContentView(root);select("Text");}
    void select(String name){mode=name;int type=switch(name){case "Number"->InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL;case "Password"->InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD;case "Search"->InputType.TYPE_CLASS_TEXT;default->InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|InputType.TYPE_TEXT_FLAG_MULTI_LINE;};field.setInputType(type);field.setImeOptions(name.equals("Search")?EditorInfo.IME_ACTION_SEARCH:EditorInfo.IME_ACTION_DONE);field.setText("");field.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).restartInput(field);field.postDelayed(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT),250);}
}
