package dev.softkeys.ios;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;

/**
 * Holds the loaded on-device recognizer between dictation sessions.
 *
 * Loading a model takes a moment and uses a lot of memory. The keyboard starts loading
 * the model when the dictation keyboard appears, and keeps it while that keyboard stays
 * open. Otherwise the recognizer is released 90 seconds after the last session, or when
 * Android asks the keyboard to free memory. Only one model is loaded at a time, and only
 * one session or preload uses it at a time.
 */
final class SpeechEngine {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Runnable idle=SpeechEngine::release;
    private static Object recognizer;
    private static SpeechModel loadedModel;
    private static boolean busy,loading,releaseWhenFree,keep;

    private SpeechEngine(){}

    /** True when Android installed the native speech library for this phone's ABI. */
    static boolean supported(Context context){
        String directory=context.getApplicationInfo().nativeLibraryDir;
        return directory!=null&&new java.io.File(directory,"libsherpa-onnx-jni.so").isFile();
    }

    static synchronized boolean loaded(SpeechModel model){return recognizer!=null&&loadedModel==model;}

    static OnlineRecognizer acquireStreaming(Context context){return (OnlineRecognizer)acquire(context,SpeechModel.FAST);}
    static OfflineRecognizer acquireBatch(Context context){return (OfflineRecognizer)acquire(context,SpeechModel.ACCURATE);}

    /** Loads the model if needed. Call from a background thread, and pair with {@link #done}. */
    private static Object acquire(Context context,SpeechModel model){
        synchronized(SpeechEngine.class){
            main.removeCallbacks(idle);
            // A model that is still loading takes as long as it takes. A cancelled session can
            // still be finishing its last decode step, so wait for it briefly.
            long deadline=System.currentTimeMillis()+5_000;
            while(busy){
                if(loading)deadline=System.currentTimeMillis()+5_000;
                long wait=deadline-System.currentTimeMillis();
                if(wait<=0)throw new IllegalStateException("The recognizer is in use");
                try{SpeechEngine.class.wait(Math.min(wait,500));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
            }
            busy=true;releaseWhenFree=false;
            if(recognizer!=null&&loadedModel==model)return recognizer;
            free();
            loading=true;
        }
        Object loaded;
        try{
            int threads=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2));
            String encoder=model.path(context,"encoder.int8.onnx"),decoder=model.path(context,"decoder.int8.onnx"),joiner=model.path(context,"joiner.int8.onnx"),tokens=model.path(context,"tokens.txt");
            loaded=model.kind==SpeechModel.Kind.BATCH?OfflineRecognizer.nemoTransducer(encoder,decoder,joiner,tokens,threads):OnlineRecognizer.transducer(encoder,decoder,joiner,tokens,threads);
        }catch(RuntimeException|LinkageError failed){
            synchronized(SpeechEngine.class){busy=loading=false;SpeechEngine.class.notifyAll();}
            throw failed;
        }
        synchronized(SpeechEngine.class){loading=false;recognizer=loaded;loadedModel=model;return loaded;}
    }

    static synchronized void done(){
        busy=false;
        SpeechEngine.class.notifyAll();
        if(releaseWhenFree)release();
        else scheduleIdle();
    }

    private static void scheduleIdle(){main.removeCallbacks(idle);if(!keep&&recognizer!=null)main.postDelayed(idle,90_000);}

    /** Keeps the loaded model while the dictation keyboard is open, or starts the idle timer again. */
    static synchronized void keep(boolean value){
        keep=value;
        if(value)main.removeCallbacks(idle);
        else if(!busy)scheduleIdle();
    }

    /** Starts loading a model in the background, so the first recording does not wait for it. */
    static void preload(Context context,SpeechModel model){
        synchronized(SpeechEngine.class){if(busy||loaded(model))return;}
        Context app=context.getApplicationContext();
        Thread thread=new Thread(()->{
            try{acquire(app,model);}catch(RuntimeException|LinkageError unavailable){return;}
            done();
        },"SoftKeys model preload");
        thread.start();
    }

    /** Frees the model now, or after the current session ends. */
    static synchronized void release(){
        main.removeCallbacks(idle);
        if(busy){releaseWhenFree=true;return;}
        releaseWhenFree=false;
        free();
    }

    private static void free(){
        if(recognizer instanceof OnlineRecognizer online)online.release();
        else if(recognizer instanceof OfflineRecognizer offline)offline.release();
        recognizer=null;loadedModel=null;
    }
}
