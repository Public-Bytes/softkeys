// Java version of OfflineRecognizer from the sherpa-onnx Kotlin API,
// https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2/sherpa-onnx/kotlin-api
// Copyright (c) 2024 Xiaomi Corporation. Licensed under the Apache License 2.0.
// SoftKeys changes: Java, file paths only, and a factory for NeMo transducer models.
package com.k2fsa.sherpa.onnx;

/** A recognizer that decodes a whole recording at once. Use it from one thread at a time, and call release when done. */
public final class OfflineRecognizer {
    static {System.loadLibrary("sherpa-onnx-jni");}
    private long ptr;

    private OfflineRecognizer(OfflineRecognizerConfig config){
        ptr=newFromFile(config);
        if(ptr==0)throw new IllegalArgumentException("The speech model could not be loaded");
    }

    /** Loads a NeMo transducer model, such as Parakeet TDT, from files. */
    public static OfflineRecognizer nemoTransducer(String encoder,String decoder,String joiner,String tokens,int threads){
        OfflineRecognizerConfig config=new OfflineRecognizerConfig();
        config.modelConfig.transducer.encoder=encoder;
        config.modelConfig.transducer.decoder=decoder;
        config.modelConfig.transducer.joiner=joiner;
        config.modelConfig.tokens=tokens;
        config.modelConfig.modelType="nemo_transducer";
        config.modelConfig.numThreads=Math.max(1,threads);
        return new OfflineRecognizer(config);
    }

    public OfflineStream createStream(){return new OfflineStream(createStream(handle()));}
    public void decode(OfflineStream stream){decode(handle(),stream.handle());}
    public String text(OfflineStream stream){OfflineRecognizerResult result=getResult(stream.handle());return result==null?"":result.text;}
    public synchronized void release(){if(ptr!=0){delete(ptr);ptr=0;}}
    private long handle(){if(ptr==0)throw new IllegalStateException("Recognizer released");return ptr;}

    private native void delete(long ptr);
    private native long newFromFile(OfflineRecognizerConfig config);
    private native long createStream(long ptr);
    private native void decode(long ptr,long streamPtr);
    private native OfflineRecognizerResult getResult(long streamPtr);
}
