// Java version of OnlineRecognizer from the sherpa-onnx Kotlin API,
// https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2/sherpa-onnx/kotlin-api
// Copyright (c) 2024 Xiaomi Corporation. Licensed under the Apache License 2.0.
// SoftKeys changes: Java, file paths only, and a factory for streaming transducer models.
package com.k2fsa.sherpa.onnx;

/** A streaming recognizer. Use it from one thread at a time, and call release when done. */
public final class OnlineRecognizer {
    static {System.loadLibrary("sherpa-onnx-jni");}
    private long ptr;

    private OnlineRecognizer(OnlineRecognizerConfig config){
        ptr=newFromFile(config);
        if(ptr==0)throw new IllegalArgumentException("The speech model could not be loaded");
    }

    /**
     * Loads a streaming transducer model (Zipformer or NeMo FastConformer) from files.
     * Endpoint detection splits speech at pauses so each segment can be finished and reset.
     */
    public static OnlineRecognizer transducer(String encoder,String decoder,String joiner,String tokens,int threads){
        OnlineRecognizerConfig config=new OnlineRecognizerConfig();
        config.modelConfig.transducer.encoder=encoder;
        config.modelConfig.transducer.decoder=decoder;
        config.modelConfig.transducer.joiner=joiner;
        config.modelConfig.tokens=tokens;
        config.modelConfig.numThreads=Math.max(1,threads);
        // A segment ends after 1.2 s of silence following speech, or after 20 s.
        config.endpointConfig.rule2=new EndpointRule(true,1.2f,0f);
        return new OnlineRecognizer(config);
    }

    public OnlineStream createStream(){return new OnlineStream(createStream(handle(),""));}
    public void reset(OnlineStream stream){reset(handle(),stream.handle());}
    public void decode(OnlineStream stream){decode(handle(),stream.handle());}
    public boolean isReady(OnlineStream stream){return isReady(handle(),stream.handle());}
    public boolean isEndpoint(OnlineStream stream){return isEndpoint(handle(),stream.handle());}
    public String text(OnlineStream stream){OnlineRecognizerResult result=getResult(handle(),stream.handle());return result==null?"":result.text;}
    public synchronized void release(){if(ptr!=0){delete(ptr);ptr=0;}}
    private long handle(){if(ptr==0)throw new IllegalStateException("Recognizer released");return ptr;}

    private native void delete(long ptr);
    private native long newFromFile(OnlineRecognizerConfig config);
    private native long createStream(long ptr,String hotwords);
    private native void reset(long ptr,long streamPtr);
    private native void decode(long ptr,long streamPtr);
    private native boolean isEndpoint(long ptr,long streamPtr);
    private native boolean isReady(long ptr,long streamPtr);
    private native OnlineRecognizerResult getResult(long ptr,long streamPtr);
}
