package dev.softkeys.ios;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** A user-controlled dictation session containing one or more provider requests. */
final class DictationSession {
    interface Listener {
        void status(DictationSession session,String text,boolean finishing);
        void result(DictationSession session,String text);
        void error(DictationSession session,String message,String retainedText);
    }
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final StringBuilder complete=new StringBuilder();
    private Attempt attempt;
    private boolean closed,stopping,streaming=Build.VERSION.SDK_INT>=33;
    private String partial="";
    private final Runnable nextRequest=this::begin;
    // These deadlines apply only to an ended provider request or an explicit Stop.
    // Neither silence nor elapsed session time finishes dictation.
    private final Runnable endedRequest=()->{if(!closed&&!stopping){keepPartial();restart(150);}};
    private final Runnable finishTimeout=()->{if(!closed&&stopping){keepPartial();finish();}};

    DictationSession(Context context,Listener listener){this.context=context;this.listener=listener;}
    void start(){if(!closed)begin();}
    private void begin(){
        if(closed||stopping)return;
        releaseAttempt();partial="";
        Attempt current=new Attempt();attempt=current;
        try{
            current.recognizer=SpeechRecognizer.createSpeechRecognizer(context);
            current.recognizer.setRecognitionListener(current);
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US");
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
            if(streaming){
                current.audio=new DictationAudio();
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,current.audio.source);
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1);
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);
                request.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE);
                current.audio.start(brokenPipe->handler.post(()->{
                    if(!current.valid()||stopping)return;
                    if(brokenPipe){streaming=false;restart(250);}
                    else fail("The microphone is unavailable. Check Android's microphone privacy switch or another recording app.");
                }));
            }
            current.recognizer.startListening(request);
        }catch(SecurityException denied){fail("Allow microphone access in SoftKeys settings.");}
        catch(java.io.IOException|RuntimeException unavailable){fail("Speech recognition couldn't start. Check your phone's microphone and speech recognition service.");}
    }
    void stop(){
        if(closed||stopping)return;
        stopping=true;handler.removeCallbacks(nextRequest);handler.removeCallbacks(endedRequest);
        listener.status(this,"Finishing dictation…",true);
        if(closed)return;
        if(attempt==null){finish();return;}
        handler.postDelayed(finishTimeout,8_000);
        try{
            // Closing our audio stream tells a segmented recognizer the user is done.
            if(attempt.audio!=null)attempt.audio.stop();
            else attempt.recognizer.stopListening();
        }catch(RuntimeException unavailable){keepPartial();finish();}
    }
    private void append(String text){
        if(text.isEmpty())return;
        if(complete.length()>0)complete.append(' ');complete.append(text);
    }
    private void keepPartial(){append(partial);partial="";}
    private void showListening(){
        String text=partial.isEmpty()?"Listening…":partial;
        if(text.length()>100)text="…"+text.substring(text.length()-100);
        listener.status(this,text,stopping);
    }
    private void restart(long delay){
        if(closed)return;
        releaseAttempt();
        if(stopping){finish();return;}
        partial="";showListening();handler.removeCallbacks(nextRequest);handler.postDelayed(nextRequest,delay);
    }
    private void releaseAttempt(){
        handler.removeCallbacks(endedRequest);
        Attempt previous=attempt;attempt=null;
        if(previous!=null){
            if(previous.recognizer!=null){
                try{previous.recognizer.cancel();}catch(RuntimeException ignored){}
                try{previous.recognizer.destroy();}catch(RuntimeException ignored){}
            }
            if(previous.audio!=null)previous.audio.close();
        }
    }
    void close(){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);releaseAttempt();complete.setLength(0);partial="";}
    private void finish(){if(closed)return;String text=complete.toString();close();listener.result(this,text);}
    private void fail(String message){if(closed)return;String retained=complete.toString();close();listener.error(this,message,retained);}
    private String transcript(Bundle bundle){
        if(bundle==null)return "";
        ArrayList<String> words=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return words==null||words.isEmpty()||words.get(0)==null?"":words.get(0).trim();
    }
    private final class Attempt implements RecognitionListener {
        SpeechRecognizer recognizer;
        DictationAudio audio;
        boolean hasSegments;
        boolean valid(){return !closed&&attempt==this;}
        @Override public void onReadyForSpeech(Bundle params){if(valid()&&!stopping)showListening();}
        @Override public void onBeginningOfSpeech(){if(valid()&&!stopping)showListening();}
        @Override public void onRmsChanged(float rms){}
        @Override public void onBufferReceived(byte[] buffer){}
        @Override public void onEndOfSpeech(){
            if(!valid()||stopping||audio!=null)return;
            // An utterance boundary belongs to the provider, not the user's session.
            handler.removeCallbacks(endedRequest);handler.postDelayed(endedRequest,8_000);
        }
        @Override public void onPartialResults(Bundle results){if(valid()){partial=transcript(results);if(!stopping)showListening();}}
        @Override public void onSegmentResults(Bundle results){
            if(!valid())return;
            append(transcript(results));partial="";hasSegments=true;if(!stopping)showListening();
        }
        @Override public void onEndOfSegmentedSession(){
            if(!valid())return;
            if(stopping){keepPartial();finish();}else restart(150);
        }
        @Override public void onResults(Bundle results){
            if(!valid())return;
            if(!hasSegments){String text=transcript(results);append(text.isEmpty()&&stopping?partial:text);}partial="";
            if(stopping)finish();
            else{
                // A provider returning an ordinary final result did not retain the
                // segmented audio request. Continue with its standard requests.
                if(audio!=null)streaming=false;
                restart(150);
            }
        }
        @Override public void onError(int error){
            if(!valid())return;
            if(stopping){keepPartial();finish();return;}
            if(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){
                if(audio!=null)streaming=false;restart(250);return;
            }
            if(audio!=null&&error==SpeechRecognizer.ERROR_CLIENT&&!hasSegments){streaming=false;restart(250);return;}
            fail(switch(error){
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"Allow microphone access in SoftKeys settings. Also check Android's microphone privacy switch.";
                case SpeechRecognizer.ERROR_AUDIO->"The microphone is unavailable. Check Android's microphone privacy switch or another recording app.";
                case SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"Dictation couldn't connect. Check your connection and try again.";
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY,SpeechRecognizer.ERROR_TOO_MANY_REQUESTS->"Speech recognition is busy. Wait a moment and try again.";
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"English (US) speech recognition isn't available. Check your speech recognition language downloads.";
                default->"Speech recognition stopped. Tap the microphone to try again.";
            });
        }
        @Override public void onEvent(int eventType,Bundle params){}
    }
}
