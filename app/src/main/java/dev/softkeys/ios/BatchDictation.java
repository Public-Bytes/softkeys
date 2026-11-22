package dev.softkeys.ios;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;
import java.util.Arrays;

/**
 * Dictation with the High accuracy model, NVIDIA Parakeet TDT, which decodes whole recordings.
 *
 * The audio stays in memory until the user taps Done, and then the recognizer decodes it in
 * one pass, as Hex does. For long dictation, {@link SpeechSegmenter} finds pauses after about
 * 20 seconds, and the decode thread decodes each finished part while the user keeps talking.
 * Done then waits only for the last part. The model loads on its own thread, so recording
 * never waits for it.
 */
final class BatchDictation extends RecordedDictation {
    /** Silence after the last word, so the model does not cut it short. */
    private static final int TAIL=RATE/4;
    private float[] pending=new float[RATE*30];
    private int length,measured;

    BatchDictation(Context context,Listener listener){super(context,listener,60_000);}

    @Override void decode(){
        StringBuilder complete=new StringBuilder();
        SpeechSegmenter segmenter=new SpeechSegmenter();
        if(!SpeechEngine.loaded(SpeechModel.ACCURATE))post(()->notice("Loading speech model…"));
        Loader loader=new Loader();
        loader.start();
        try{
            float[] samples;
            int cut=0;
            while((samples=next())!=null){
                add(samples);
                while(measured+SpeechSegmenter.CHUNK<=length){
                    int chunks=segmenter.add(SpeechSegmenter.rms(pending,measured,measured+SpeechSegmenter.CHUNK));
                    measured+=SpeechSegmenter.CHUNK;
                    cut+=chunks*SpeechSegmenter.CHUNK;
                }
                // Decode finished parts while the user keeps talking, once the model is ready.
                if(cut>0&&loader.ready()){append(complete,transcribe(loader.take(),cut,false));cut=0;}
            }
            if(closed)return;
            OfflineRecognizer recognizer=loader.take();
            if(cut>0)append(complete,transcribe(recognizer,cut,false));
            // Less than a fifth of a second is a tap, not speech.
            if(length>RATE/5)append(complete,transcribe(recognizer,length,true));
            finish(complete.toString());
        }catch(InterruptedException cancelled){
            // close() interrupts the thread to discard the session.
        }catch(LinkageError unsupported){
            post(()->deliverError("On-device dictation isn't supported on this phone. Choose Phone speech service in SoftKeys settings.",""));
        }catch(LoadFailure failed){
            post(()->deliverError("The speech model couldn't load. Delete it and download it again in SoftKeys settings.",""));
        }catch(RuntimeException failed){
            String text=complete.toString();
            post(()->deliverError("On-device dictation stopped. Tap Dictate to try again.",text));
        }finally{
            loader.release();
        }
    }

    private static final class LoadFailure extends RuntimeException {LoadFailure(Throwable cause){super(cause);}}

    /** Loads the recognizer. Whoever finishes last, the session or the load, returns it to SpeechEngine. */
    private final class Loader extends Thread {
        private OfflineRecognizer recognizer;
        private Throwable problem;
        private boolean finished,released;

        Loader(){super("SoftKeys model load");}

        @Override public void run(){
            OfflineRecognizer loaded=null;Throwable failure=null;
            try{loaded=SpeechEngine.acquireBatch(context);}catch(RuntimeException|LinkageError failed){failure=failed;}
            synchronized(this){
                if(released){if(loaded!=null)SpeechEngine.done();return;}
                recognizer=loaded;problem=failure;finished=true;notifyAll();
            }
        }

        synchronized boolean ready(){return finished;}

        synchronized OfflineRecognizer take() throws InterruptedException {
            while(!finished)wait();
            if(recognizer!=null)return recognizer;
            if(problem instanceof LinkageError linkage)throw linkage;
            throw new LoadFailure(problem);
        }

        synchronized void release(){
            if(released)return;
            released=true;
            if(recognizer!=null){recognizer=null;SpeechEngine.done();}
        }
    }

    private void add(float[] samples){
        if(length+samples.length>pending.length)pending=Arrays.copyOf(pending,Math.max(pending.length*2,length+samples.length));
        System.arraycopy(samples,0,pending,length,samples.length);
        length+=samples.length;
    }

    /** Decodes the first count samples and removes them from the buffer. */
    private String transcribe(OfflineRecognizer recognizer,int count,boolean last){
        float[] part=Arrays.copyOf(pending,count+(last?TAIL:0));
        if(last)Arrays.fill(part,count,part.length,0f);
        System.arraycopy(pending,count,pending,0,length-count);
        length-=count;measured=Math.max(0,measured-count);
        OfflineStream stream=recognizer.createStream();
        try{
            stream.acceptWaveform(part,RATE);
            recognizer.decode(stream);
            return recognizer.text(stream);
        }finally{stream.release();}
    }
}
