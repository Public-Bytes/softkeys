package dev.softkeys.ios;

import android.content.Context;
import android.os.Build;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Smart cleanup: an optional language model that removes the words a speaker took back.
 *
 * It runs Handy Editor 350M (SpeechModel.EDITOR, about 229 MB) with llama.cpp in
 * libsoftkeys-editor.so, on the phone, after the speech model has written the transcript.
 * It is off until the user turns it on and downloads the model. All work happens on one
 * background thread, so loading, editing, and releasing never overlap and never block the
 * keyboard.
 *
 * The rules in DictationCleanup run before and after it. Before, they remove fillers and
 * handle the corrections the model misses, such as "wheat" for "wait". The model then sees
 * lowercase words with no punctuation, the form it was trained on. SmartEditCheck rejects
 * an edit that changes or adds words or deletes text without a correction phrase near it,
 * and the rules-only text is used for that part instead. Afterwards the rules write
 * numbers, web addresses, lists, and capitals.
 */
final class SmartEditor {
    private SmartEditor(){}

    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"SoftKeys smart cleanup"));
    /** Parts longer than this are split at sentence ends, so each edit stays short. */
    private static final int PART_CHARACTERS=400;
    /** The most time one part may take, and all parts together, in milliseconds. */
    private static final long PART_TIMEOUT=4000,TOTAL_TIMEOUT=8000;
    private static long handle;
    private static boolean libraryLoaded,libraryFailed;

    private static native long nativeLoad(String path,int threads,int context);
    private static native byte[] nativeEdit(long handle,byte[] text,int maxTokens,long timeoutMs);
    private static native void nativeFree(long handle);

    /**
     * True when this phone can run the model: a 64-bit ARM processor with the dot product
     * instructions (asimddp) that the library was built for. Older phones never load it.
     */
    static boolean supported(){
        boolean arm64=false;
        for(String abi:Build.SUPPORTED_ABIS)arm64|=abi.equals("arm64-v8a");
        if(!arm64)return false;
        try(BufferedReader in=new BufferedReader(new FileReader("/proc/cpuinfo"))){
            String line;
            while((line=in.readLine())!=null)if(line.startsWith("Features")&&(" "+line+" ").contains(" asimddp "))return true;
        }catch(IOException unreadable){return false;}
        return false;
    }

    static boolean ready(Context context){return supported()&&SpeechModel.EDITOR.installed(context);}

    /** Loads the model in the background, so the first edit after a recording does not wait for it. */
    static void preload(Context context){
        Context app=context.getApplicationContext();
        worker.execute(()->load(app));
    }

    /** Frees the model's memory. Runs after any edit in progress. */
    static void release(){worker.execute(()->{if(handle!=0){nativeFree(handle);handle=0;}});}

    private static boolean load(Context context){
        if(handle!=0)return true;
        if(libraryFailed||!ready(context))return false;
        if(!libraryLoaded){
            try{System.loadLibrary("softkeys-editor");libraryLoaded=true;}
            catch(UnsatisfiedLinkError missing){libraryFailed=true;return false;}
        }
        int threads=Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()/2));
        handle=nativeLoad(SpeechModel.EDITOR.path(context,"handy-editor-350m-Q4_K_M.gguf"),threads,1024);
        return handle!=0;
    }

    /**
     * Edits a raw transcript in the background and passes the text to insert to done, on the
     * background thread. The text is the mix of accepted model edits and untouched raw parts,
     * ready for DictationCleanup.clean. When the model cannot run, done gets the raw text.
     */
    static void edit(Context context,String raw,DictationCleanup.Options options,Consumer<String> done){
        Context app=context.getApplicationContext();
        worker.execute(()->{
            String result=raw;
            try{if(load(app))result=editParts(raw,options);}
            catch(RuntimeException|LinkageError failed){result=raw;}
            done.accept(result);
        });
    }

    private static String editParts(String raw,DictationCleanup.Options options){
        long deadline=System.currentTimeMillis()+TOTAL_TIMEOUT;
        StringBuilder out=new StringBuilder();
        for(String part:parts(raw)){
            // Spoken punctuation would be lost when the model's input drops punctuation, so those parts keep the rules.
            String prepared=options.spokenPunctuation&&DictationCleanup.hasSpokenPunctuation(part)?"":DictationCleanup.prepareForEditor(part,options);
            String edited=null;
            long left=deadline-System.currentTimeMillis();
            if(!prepared.isEmpty()&&left>300){
                byte[] bytes=nativeEdit(handle,prepared.getBytes(StandardCharsets.UTF_8),Math.min(400,prepared.length()/2+64),Math.min(PART_TIMEOUT,left));
                if(bytes!=null)edited=new String(bytes,StandardCharsets.UTF_8).trim();
            }
            if(out.length()>0)out.append(' ');
            out.append(edited!=null&&SmartEditCheck.accept(prepared,edited)?edited:part);
        }
        return out.toString();
    }

    /** Splits text into parts of about PART_CHARACTERS at sentence ends. */
    static List<String> parts(String raw){
        List<String> parts=new ArrayList<>();
        StringBuilder part=new StringBuilder();
        for(String sentence:raw.split("(?<=[.!?])\\s+")){
            if(part.length()>0&&part.length()+sentence.length()>PART_CHARACTERS){parts.add(part.toString());part.setLength(0);}
            if(part.length()>0)part.append(' ');
            part.append(sentence);
        }
        if(part.length()>0)parts.add(part.toString());
        return parts;
    }
}
