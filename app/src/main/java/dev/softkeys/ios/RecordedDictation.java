package dev.softkeys.ios;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Microphone capture shared by the two on-device engines.
 *
 * A capture thread reads 16 kHz mono audio in 50 ms chunks into an in-memory queue, and
 * reports the level of each chunk for the meter. A decode thread, written by the subclass,
 * takes the chunks from the queue. The microphone starts at once, so speech that starts
 * while the model loads waits in memory. Audio is never written to a file or sent anywhere.
 */
abstract class RecordedDictation implements DictationEngine {
    static final int RATE=16000;
    private static final float[] END=new float[0];
    final Context context;
    private final Listener listener;
    final Handler handler=new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<float[]> audio=new LinkedBlockingQueue<>();
    volatile boolean closed,stopping;
    private volatile boolean heardAudio;
    volatile String microphoneProblem;
    private boolean finished;
    private Thread capture,decoder;
    private final long finishLimit;
    private final Runnable finishTimeout=()->{if(!closed&&stopping)deliverError("Dictation took too long to finish. Tap Dictate to try again.","");};

    RecordedDictation(Context context,Listener listener,long finishLimit){
        this.context=context.getApplicationContext();this.listener=listener;this.finishLimit=finishLimit;
    }

    @Override public final boolean onDevice(){return true;}

    @Override public void start(){
        if(closed||capture!=null)return;
        capture=new Thread(this::record,"SoftKeys dictation audio");
        decoder=new Thread(this::decode,"SoftKeys dictation decoder");
        capture.start();decoder.start();
    }

    @Override public void stop(){
        if(closed||stopping)return;
        stopping=true;
        listener.status(this,"Transcribing…",true);
        // Long dictation on a slow phone can leave audio to decode, so allow time to catch up.
        handler.postDelayed(finishTimeout,finishLimit);
    }

    @Override public void close(){
        if(closed)return;
        closed=true;
        handler.removeCallbacksAndMessages(null);
        audio.clear();audio.offer(END);
        if(decoder!=null)decoder.interrupt();
    }

    private void record(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioRecord recorder=null;
        try{
            int minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(RATE*2,minimum)).build();
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone initialization failed");
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone is busy");
            short[] buffer=new short[SpeechSegmenter.CHUNK];
            while(!closed&&!stopping){
                int count=recorder.read(buffer,0,buffer.length);
                if(count<0)throw new IllegalStateException("Microphone read failed");
                if(count==0)continue;
                float[] samples=new float[count];
                for(int i=0;i<count;i++)samples[i]=buffer[i]/32768f;
                heardAudio=true;
                audio.offer(samples);
                float level=DictationMode.levelFromRms(SpeechSegmenter.rms(samples,0,count));
                post(()->listener.level(this,level));
            }
        }catch(SecurityException denied){
            microphoneFailed("Allow microphone access in SoftKeys settings.");
        }catch(RuntimeException unavailable){
            microphoneFailed("The microphone is unavailable. Check Android's microphone privacy switch or another recording app.");
        }finally{
            if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}recorder.release();}
            audio.offer(END);
        }
    }

    /** Reports at once when no audio arrived. Otherwise the decoder finishes the audio it has first. */
    private void microphoneFailed(String message){
        microphoneProblem=message;
        if(!heardAudio)post(()->deliverError(message,""));
    }

    /** The decode thread. */
    abstract void decode();

    /** The next chunk, or null when recording ended. Waits for audio. */
    final float[] next() throws InterruptedException {
        while(!closed){
            float[] samples=audio.poll(200,TimeUnit.MILLISECONDS);
            if(samples==null)continue;
            return samples==END?null:samples;
        }
        return null;
    }

    final void post(Runnable action){handler.post(()->{if(!closed&&!finished)action.run();});}

    final void notice(String text){if(!stopping)listener.status(this,text,false);}

    /** Delivers the transcript, or the microphone problem with the text recognized before it. */
    final void finish(String text){
        String problem=microphoneProblem;
        post(()->{if(problem!=null)deliverError(problem,text);else deliverResult(text);});
    }

    final void deliverResult(String raw){
        finished=true;handler.removeCallbacks(finishTimeout);
        close();
        listener.result(this,raw.trim());
    }

    final void deliverError(String message,String retained){
        finished=true;handler.removeCallbacks(finishTimeout);
        stopping=true;
        close();
        listener.error(this,message,retained.trim());
    }

    static void append(StringBuilder complete,String text){
        text=text.trim();
        if(text.isEmpty())return;
        // The seam mark tells the cleanup where one decoded part ends and the next begins.
        if(complete.length()>0)complete.append(' ').append(DictationCleanup.SEAM).append(' ');
        complete.append(text);
    }
}
