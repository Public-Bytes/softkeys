package dev.softkeys.ios;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Dictation with the downloaded on-device model.
 *
 * A capture thread reads 16 kHz mono audio into memory, and a decode thread passes it to
 * the streaming recognizer. Audio is never written to a file or sent anywhere. Speech that
 * starts while the model is loading waits in memory. The recognizer splits the transcript
 * at pauses, and each finished segment is kept until Stop inserts the cleaned-up text.
 */
final class OnDeviceDictation implements DictationEngine {
    private static final int RATE=16000;
    private static final float[] END=new float[0];
    private final Context context;
    private final Listener listener;
    private final DictationCleanup.Options cleanup;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<float[]> audio=new LinkedBlockingQueue<>();
    private volatile boolean closed,stopping,heardAudio;
    private volatile String microphoneProblem;
    private boolean finished;
    private Thread capture,decoder;
    private final Runnable finishTimeout=()->{if(!closed&&stopping)deliverError("Dictation took too long to finish. Tap the microphone to try again.","");};

    /** @param cleanup the cleanup options, or null to insert the model's text unchanged */
    OnDeviceDictation(Context context,Listener listener,DictationCleanup.Options cleanup){
        this.context=context.getApplicationContext();this.listener=listener;this.cleanup=cleanup;
    }

    @Override public void start(){
        if(closed||capture!=null)return;
        capture=new Thread(this::record,"SoftKeys dictation audio");
        decoder=new Thread(this::decode,"SoftKeys dictation decoder");
        capture.start();decoder.start();
    }

    @Override public void stop(){
        if(closed||stopping)return;
        stopping=true;
        listener.status(this,"Finishing dictation…",true);
        // Long dictation on a slow phone can leave audio to decode, so allow time to catch up.
        handler.postDelayed(finishTimeout,30_000);
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
            short[] buffer=new short[RATE/10];
            while(!closed&&!stopping){
                int count=recorder.read(buffer,0,buffer.length);
                if(count<0)throw new IllegalStateException("Microphone read failed");
                if(count==0)continue;
                float[] samples=new float[count];
                for(int i=0;i<count;i++)samples[i]=buffer[i]/32768f;
                heardAudio=true;
                audio.offer(samples);
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

    private void decode(){
        OnlineRecognizer recognizer;
        try{
            if(!SpeechEngine.loaded())post(()->notice("Loading speech model…"));
            recognizer=SpeechEngine.acquire(context);
        }catch(LinkageError unsupported){
            post(()->deliverError("On-device dictation isn't supported on this phone. Choose Phone speech service in SoftKeys settings.",""));return;
        }catch(RuntimeException failed){
            post(()->deliverError("The on-device speech model couldn't load. Delete it and download it again in SoftKeys settings.",""));return;
        }
        OnlineStream stream=null;
        StringBuilder complete=new StringBuilder();
        try{
            stream=recognizer.createStream();
            if(closed)return;
            post(()->notice("Listening…"));
            String shown="";
            while(!closed){
                float[] samples=audio.poll(200,TimeUnit.MILLISECONDS);
                if(samples==null)continue;
                if(samples==END)break;
                stream.acceptWaveform(samples,RATE);
                while(!closed&&recognizer.isReady(stream))recognizer.decode(stream);
                String partial=recognizer.text(stream).trim();
                if(recognizer.isEndpoint(stream)){
                    append(complete,partial);partial="";
                    recognizer.reset(stream);
                }
                String text=join(complete,partial);
                if(!text.equals(shown)){shown=text;String preview=text;post(()->status(preview));}
            }
            if(closed)return;
            // Trailing silence lets the model finish the last word before the input ends.
            stream.acceptWaveform(new float[RATE],RATE);
            stream.inputFinished();
            while(!closed&&recognizer.isReady(stream))recognizer.decode(stream);
            append(complete,recognizer.text(stream).trim());
            String text=complete.toString(),problem=microphoneProblem;
            post(()->{if(problem!=null)deliverError(problem,text);else deliverResult(text);});
        }catch(InterruptedException cancelled){
            // close() interrupts the thread to discard the session.
        }catch(RuntimeException failed){
            String text=complete.toString();
            post(()->deliverError("On-device dictation stopped. Tap the microphone to try again.",text));
        }finally{
            if(stream!=null)stream.release();
            SpeechEngine.done();
        }
    }

    private static void append(StringBuilder complete,String text){
        if(text.isEmpty())return;
        if(complete.length()>0)complete.append(' ');
        complete.append(text);
    }
    private static String join(StringBuilder complete,String partial){
        return complete.length()==0?partial:partial.isEmpty()?complete.toString():complete+" "+partial;
    }

    private void post(Runnable action){handler.post(()->{if(!closed&&!finished)action.run();});}

    private void notice(String text){if(!stopping)listener.status(this,text,false);}

    private void status(String raw){
        if(stopping)return;
        String text=cleanup==null?raw:DictationCleanup.preview(raw,cleanup);
        if(text.isEmpty())text="Listening…";
        if(text.length()>100)text="…"+text.substring(text.length()-100);
        listener.status(this,text,false);
    }

    private String finalText(String raw){return cleanup==null?raw.trim():DictationCleanup.clean(raw,cleanup);}

    private void deliverResult(String raw){
        finished=true;handler.removeCallbacks(finishTimeout);
        String text=finalText(raw);
        close();
        listener.result(this,text);
    }

    private void deliverError(String message,String retained){
        finished=true;handler.removeCallbacks(finishTimeout);
        stopping=true;
        String text=finalText(retained);
        close();
        listener.error(this,message,text);
    }
}
