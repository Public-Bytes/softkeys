package dev.softkeys.ios;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.OutputStream;

/** Owns the microphone and streams PCM to a recognizer until explicitly stopped. */
final class DictationAudio {
    final ParcelFileDescriptor source;
    private final ParcelFileDescriptor sink;
    private final AudioRecord record;
    private volatile boolean running=true;
    private boolean started;

    interface Failure {void failed(boolean brokenPipe);}
    DictationAudio() throws IOException {
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        record=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(32000,minimum)).build();
        if(record.getState()!=AudioRecord.STATE_INITIALIZED){record.release();throw new IOException("Microphone initialization failed");}
        ParcelFileDescriptor[] pipe;
        try{pipe=ParcelFileDescriptor.createPipe();}catch(IOException error){record.release();throw error;}
        source=pipe[0];sink=pipe[1];
    }
    void start(Failure failure){
        started=true;
        new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(sink)){
                synchronized(record){if(running)record.startRecording();}
                byte[] buffer=new byte[2048];
                while(running){
                    int count=record.read(buffer,0,buffer.length);
                    if(count<0){if(running)failure.failed(false);break;}
                    if(count>0)output.write(buffer,0,count);
                }
            }catch(IOException brokenPipe){if(running)failure.failed(true);}
            catch(RuntimeException unavailable){if(running)failure.failed(false);}
            finally{synchronized(record){try{record.stop();}catch(RuntimeException ignored){}record.release();}}
        },"SoftKeys dictation audio").start();
    }
    void stop(){running=false;synchronized(record){try{record.stop();}catch(RuntimeException ignored){}}}
    void close(){
        stop();
        try{source.close();}catch(IOException ignored){}
        try{sink.close();}catch(IOException ignored){}
        if(!started)record.release();
    }
}
