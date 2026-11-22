package dev.softkeys.ios;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Requests permission without launching a voice keyboard or recognition activity. */
public class MicrophonePermissionActivity extends Activity {
    private boolean requesting;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setTitle("Dictation");
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"Microphone access is enabled. Tap Dictate on SoftKeys to dictate.",Toast.LENGTH_LONG).show();finish();return;
        }
        requesting=state!=null&&state.getBoolean("requesting",false);
        if(requesting)return;
        boolean asked=getPreferences(MODE_PRIVATE).getBoolean("asked",false);
        boolean settings=asked&&!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
        new Ui.Sheet(this,Ui.Palette.of(this)).title("Enable dictation")
            .message("SoftKeys needs microphone access for dictation. On-device dictation keeps audio on your phone. The phone speech service processes audio and may send it to its provider. After granting access, return to your text field and tap Dictate to begin.")
            .action(settings?"Open settings":"Continue",Ui.Sheet.Style.BOLD,()->{
                if(settings){
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));finish();
                }else{
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked",true).apply();
                    requesting=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);
                }
            }).onCancel(this::finish).show();
    }
    @Override public void onSaveInstanceState(Bundle state){state.putBoolean("requesting",requesting);super.onSaveInstanceState(state);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==1){Toast.makeText(this,results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED?"Microphone enabled. Return to your text field and tap Dictate.":"Dictation needs microphone access. You can enable it in SoftKeys settings.",Toast.LENGTH_LONG).show();finish();}
    }
}
