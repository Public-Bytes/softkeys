// Java version of OfflineStream from the sherpa-onnx Kotlin API,
// https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2/sherpa-onnx/kotlin-api
// Copyright (c) 2024 Xiaomi Corporation. Licensed under the Apache License 2.0.
package com.k2fsa.sherpa.onnx;

/** Audio for one offline recognition. Samples are mono floats from -1 to 1. */
public final class OfflineStream {
    static {System.loadLibrary("sherpa-onnx-jni");}
    private long ptr;

    OfflineStream(long ptr){
        if(ptr==0)throw new IllegalStateException("Failed to create a speech stream");
        this.ptr=ptr;
    }
    long handle(){if(ptr==0)throw new IllegalStateException("Stream released");return ptr;}
    public void acceptWaveform(float[] samples,int sampleRate){acceptWaveform(handle(),samples,sampleRate);}
    public void release(){if(ptr!=0){delete(ptr);ptr=0;}}

    private native void acceptWaveform(long ptr,float[] samples,int sampleRate);
    private native void delete(long ptr);
}
