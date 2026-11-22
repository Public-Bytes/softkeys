package dev.softkeys.ios;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;

/**
 * Holds the loaded on-device recognizer between dictation sessions.
 *
 * Loading the model takes a moment and uses a lot of memory, so the recognizer is kept
 * for 90 seconds after the last session and then released. Only one session uses it at
 * a time, and only from that session's decode thread.
 */
final class SpeechEngine {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Runnable idle=SpeechEngine::release;
    private static OnlineRecognizer recognizer;
    private static boolean busy,releaseWhenFree;

    private SpeechEngine(){}

    /** True when the APK contains the native speech library for this phone. */
    static boolean supported(){
        for(String abi:Build.SUPPORTED_ABIS)if(abi.equals("arm64-v8a")||abi.equals("armeabi-v7a")||abi.equals("x86_64"))return true;
        return false;
    }

    static synchronized boolean loaded(){return recognizer!=null;}

    /** Loads the model if needed. Call from a background thread, and pair with {@link #done}. */
    static OnlineRecognizer acquire(Context context){
        synchronized(SpeechEngine.class){
            main.removeCallbacks(idle);
            // A cancelled session can still be finishing its last decode step. Wait for it briefly.
            long deadline=System.currentTimeMillis()+5_000;
            while(busy){
                long wait=deadline-System.currentTimeMillis();
                if(wait<=0)throw new IllegalStateException("The recognizer is in use");
                try{SpeechEngine.class.wait(wait);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
            }
            busy=true;releaseWhenFree=false;
            if(recognizer!=null)return recognizer;
        }
        OnlineRecognizer loaded;
        try{
            int threads=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2));
            loaded=OnlineRecognizer.transducer(SpeechModel.path(context,"encoder.int8.onnx"),SpeechModel.path(context,"decoder.int8.onnx"),
                SpeechModel.path(context,"joiner.int8.onnx"),SpeechModel.path(context,"tokens.txt"),threads);
        }catch(RuntimeException|LinkageError failed){
            synchronized(SpeechEngine.class){busy=false;SpeechEngine.class.notifyAll();}
            throw failed;
        }
        synchronized(SpeechEngine.class){recognizer=loaded;return loaded;}
    }

    static synchronized void done(){
        busy=false;
        SpeechEngine.class.notifyAll();
        if(releaseWhenFree)release();
        else{main.removeCallbacks(idle);main.postDelayed(idle,90_000);}
    }

    /** Frees the model now, or after the current session ends. */
    static synchronized void release(){
        main.removeCallbacks(idle);
        if(busy){releaseWhenFree=true;return;}
        releaseWhenFree=false;
        if(recognizer!=null){recognizer.release();recognizer=null;}
    }
}
