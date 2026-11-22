package dev.softkeys.ios;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;

/**
 * Dictation with the Fast streaming model.
 *
 * The decode thread passes each chunk to the streaming recognizer while the user speaks.
 * The recognizer splits the transcript at pauses, and each finished segment is kept. When
 * the user taps Done, only the last part of the audio is left to decode.
 */
final class OnDeviceDictation extends RecordedDictation {
    OnDeviceDictation(Context context,Listener listener){super(context,listener,30_000);}

    @Override void decode(){
        OnlineRecognizer recognizer;
        try{
            if(!SpeechEngine.loaded(SpeechModel.FAST))post(()->notice("Loading speech model…"));
            recognizer=SpeechEngine.acquireStreaming(context);
        }catch(LinkageError unsupported){
            post(()->deliverError("On-device dictation isn't supported on this phone. Choose Phone speech service in SoftKeys settings.",""));return;
        }catch(RuntimeException failed){
            post(()->deliverError("The speech model couldn't load. Delete it and download it again in SoftKeys settings.",""));return;
        }
        OnlineStream stream=null;
        StringBuilder complete=new StringBuilder();
        try{
            stream=recognizer.createStream();
            if(closed)return;
            post(()->notice("Listening…"));
            float[] samples;
            while((samples=next())!=null){
                stream.acceptWaveform(samples,RATE);
                while(!closed&&recognizer.isReady(stream))recognizer.decode(stream);
                if(recognizer.isEndpoint(stream)){
                    append(complete,recognizer.text(stream));
                    recognizer.reset(stream);
                }
            }
            if(closed)return;
            // Trailing silence lets the model finish the last word before the input ends.
            stream.acceptWaveform(new float[RATE],RATE);
            stream.inputFinished();
            while(!closed&&recognizer.isReady(stream))recognizer.decode(stream);
            append(complete,recognizer.text(stream));
            finish(complete.toString());
        }catch(InterruptedException cancelled){
            // close() interrupts the thread to discard the session.
        }catch(RuntimeException failed){
            String text=complete.toString();
            post(()->deliverError("On-device dictation stopped. Tap Dictate to try again.",text));
        }finally{
            if(stream!=null)stream.release();
            SpeechEngine.done();
        }
    }
}
