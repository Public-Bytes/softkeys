package dev.softkeys.ios;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
            Toast.makeText(this,"Microphone access is enabled. Tap the microphone on SoftKeys to dictate.",Toast.LENGTH_LONG).show();finish();return;
        }
        requesting=state!=null&&state.getBoolean("requesting",false);
        if(requesting)return;
        boolean asked=getPreferences(MODE_PRIVATE).getBoolean("asked",false);
        new AlertDialog.Builder(this).setTitle("Enable dictation")
            .setMessage("SoftKeys needs microphone access for dictation. Your phone's speech recognition service processes audio and may send it to its provider. After granting access, return to your text field and tap the microphone to begin.")
            .setPositiveButton(asked&&!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)?"Open settings":"Continue",(dialog,which)->{
                if(asked&&!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)){
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));finish();
                }else{
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked",true).apply();
                    requesting=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);
                }
            }).setNegativeButton("Cancel",(dialog,which)->finish()).setOnCancelListener(dialog->finish()).show();
    }
    @Override public void onSaveInstanceState(Bundle state){state.putBoolean("requesting",requesting);super.onSaveInstanceState(state);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==1){Toast.makeText(this,results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED?"Microphone enabled. Return to your text field and tap the microphone to dictate.":"Dictation needs microphone access. You can enable it in SoftKeys settings.",Toast.LENGTH_LONG).show();finish();}
    }
}
