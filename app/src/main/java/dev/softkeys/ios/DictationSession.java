package dev.softkeys.ios;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** One bounded dictation request. All methods and callbacks run on the main thread. */
final class DictationSession implements RecognitionListener {
    interface Listener {
        void status(DictationSession session,String text,boolean finishing);
        void result(DictationSession session,String text);
        void error(DictationSession session,String message);
    }
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean closed,finishing;
    private final Runnable limit=this::stop;
    private final Runnable resultTimeout=()->fail("Dictation timed out. Tap the microphone to try again.");

    DictationSession(Context context,Listener listener){this.context=context;this.listener=listener;}
    void start(){
        if(closed)return;
        try{
            recognizer=SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(this);
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US");
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
            recognizer.startListening(request);
            handler.postDelayed(limit,45_000);
        }catch(SecurityException denied){fail("Allow microphone access in SoftKeys settings.");}
        catch(RuntimeException unavailable){fail("Speech recognition couldn't start. Check your phone's speech recognition service.");}
    }
    void stop(){
        if(closed||finishing)return;
        finishing=true;handler.removeCallbacks(limit);
        listener.status(this,"Finishing dictation…",true);
        if(closed)return;
        handler.postDelayed(resultTimeout,8_000);
        try{if(recognizer!=null)recognizer.stopListening();}
        catch(RuntimeException unavailable){fail("Dictation stopped. Tap the microphone to try again.");}
    }
    void close(){
        if(closed)return;
        closed=true;handler.removeCallbacksAndMessages(null);
        if(recognizer!=null){
            try{recognizer.cancel();}catch(RuntimeException ignored){}
            try{recognizer.destroy();}catch(RuntimeException ignored){}
            recognizer=null;
        }
    }
    private void fail(String message){if(closed)return;close();listener.error(this,message);}
    private String transcript(Bundle bundle){
        if(bundle==null)return "";
        ArrayList<String> words=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return words==null||words.isEmpty()||words.get(0)==null?"":words.get(0).trim();
    }
    @Override public void onReadyForSpeech(Bundle params){if(!closed&&!finishing)listener.status(this,"Listening…",false);}
    @Override public void onBeginningOfSpeech(){if(!closed&&!finishing)listener.status(this,"Listening…",false);}
    @Override public void onRmsChanged(float rms){}
    @Override public void onBufferReceived(byte[] buffer){}
    @Override public void onEndOfSpeech(){
        if(closed||finishing)return;
        finishing=true;handler.removeCallbacks(limit);handler.postDelayed(resultTimeout,8_000);
        listener.status(this,"Finishing dictation…",true);
    }
    @Override public void onPartialResults(Bundle partialResults){
        if(closed||finishing)return;
        String text=transcript(partialResults);if(!text.isEmpty())listener.status(this,text,false);
    }
    @Override public void onResults(Bundle results){
        if(closed)return;
        String text=transcript(results);close();
        if(text.isEmpty())listener.error(this,"No speech was recognized. Tap the microphone to try again.");
        else listener.result(this,text);
    }
    @Override public void onError(int error){
        fail(switch(error){
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"Allow microphone access in SoftKeys settings. Also check Android's microphone privacy switch.";
            case SpeechRecognizer.ERROR_AUDIO->"The microphone is unavailable. Check Android's microphone privacy switch or another recording app.";
            case SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"Dictation couldn't connect. Check your connection and try again.";
            case SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"No speech was recognized. Tap the microphone to try again.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY,SpeechRecognizer.ERROR_TOO_MANY_REQUESTS->"Speech recognition is busy. Wait a moment and try again.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"English (US) speech recognition isn't available. Check your speech recognition language downloads.";
            default->"Speech recognition stopped. Tap the microphone to try again.";
        });
    }
    @Override public void onEvent(int eventType,Bundle params){}
}
