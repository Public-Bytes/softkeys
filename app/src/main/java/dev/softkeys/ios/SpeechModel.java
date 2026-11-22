package dev.softkeys.ios;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/**
 * Downloads, verifies, and deletes the on-device speech models.
 *
 * There are two speech models, and one text model for smart cleanup. High accuracy is NVIDIA Parakeet TDT 0.6B v2, which decodes the
 * whole recording after Done. Fast is the streaming FastConformer model from 1.11, which
 * decodes while the user speaks. See docs/dictation-benchmark.md for the measurements.
 *
 * The download is the only network use in SoftKeys. It is a fixed HTTPS GET of public
 * model files from a pinned Hugging Face revision, started only from settings. No audio,
 * text, or device details are sent. Files go to app-private no-backup storage and are
 * checked against the sizes and SHA-256 digests below before they can be used.
 */
final class SpeechModel {
    enum Kind {STREAMING,BATCH,EDITOR}
    enum State {ABSENT,DOWNLOADING,READY,FAILED}
    interface Listener {void changed();}

    static final SpeechModel ACCURATE=new SpeechModel("accurate","High accuracy","NVIDIA Parakeet TDT 0.6B v2, int8",Kind.BATCH,
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8","84b478d541cdbf0c296a7ab5f1e20a6f8f998114","parakeet-tdt-0.6b-v2-int8-",
        new Object[][]{
            {"encoder.int8.onnx",652_282_309L,"92a10bd9c23936ea0566c29f963bc57c2a5df4261b6a8921c39abea66557ca8b"},
            {"decoder.int8.onnx",7_257_753L,"4d097bae3bac5c96018d5293ba39f1b657296102b142affda40378f38b020f41"},
            {"joiner.int8.onnx",1_739_080L,"77a29f3e5ac7d37074e0af465391b80f314015d5b97f0f90c6c7767f1d56f428"},
            {"tokens.txt",9_384L,"ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"},
        });
    static final SpeechModel FAST=new SpeechModel("fast","Fast","NVIDIA FastConformer streaming English, 480 ms, int8",Kind.STREAMING,
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8","df8ed95e44a70924450381e610770f9d656d1e15","nemo-fastconformer-en-480ms-int8-",
        new Object[][]{
            {"encoder.int8.onnx",131_507_603L,"100c5616929a131b5b3c8c8ab0d83aaba2cdcae163acd8d190b4e5ffa5f7d051"},
            {"decoder.int8.onnx",3_955_864L,"4f04431988472f8c7b815f942aa6901976929c9e22353029681fcdb262da0164"},
            {"joiner.int8.onnx",1_408_182L,"d2d8e7290a6a8245a83ed211aa0dd8cdf8effb8a7c64fe392399561111b52f30"},
            {"tokens.txt",11_896L,"618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131"},
        });
    static final SpeechModel[] ALL={ACCURATE,FAST};
    /**
     * The optional smart cleanup model, not a speech model: Handy Editor 350M, a fine-tune of
     * LiquidAI LFM2.5-350M (LFM Open License v1.0) that edits dictated text. See SmartEditor.
     */
    static final SpeechModel EDITOR=new SpeechModel("editor","Smart cleanup","Handy Editor 350M (LFM2.5-350M fine-tune), Q4_K_M",Kind.EDITOR,
        "https://huggingface.co/MagicNoThief/handy-editor-lfm2.5-350m","f1f01e16ab10143f51ffb0fb180ac715dcef964e","handy-editor-350m-",
        new Object[][]{
            {"handy-editor-350m-Q4_K_M.gguf",229_315_072L,"eace6ae31325690abe7a731b02b6d6b92ca9ca8b479b94fe1a6f6bfbefb41356"},
        });

    /** The value saved in the speechModel preference. */
    final String id;
    /** Short name for settings, such as "High accuracy". */
    final String title;
    /** The model's full name. */
    final String name;
    final Kind kind;
    final String source;
    final long size;
    private final String revision,directory;
    /** Name, size in bytes, and SHA-256 of each file. */
    private final Object[][] files;

    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners=new ArrayList<>();
    private State state;
    private long received;
    private String failure="";
    private volatile boolean cancelled;
    private Thread worker;

    private SpeechModel(String id,String title,String name,Kind kind,String source,String revision,String prefix,Object[][] files){
        this.id=id;this.title=title;this.name=name;this.kind=kind;this.source=source;this.revision=revision;this.files=files;
        directory=prefix+revision.substring(0,8);
        long total=0;for(Object[] file:files)total+=(Long)file[1];size=total;
    }

    static SpeechModel byId(String id){return "fast".equals(id)?FAST:ACCURATE;}

    File directory(Context context){return new File(new File(context.getNoBackupFilesDir(),"speech"),directory);}
    String path(Context context,String name){return new File(directory(context),name).getAbsolutePath();}
    private File temporary(Context context){return new File(new File(context.getNoBackupFilesDir(),"speech"),".download-"+id);}

    /** A quick check that every verified file is present with its expected size. */
    boolean installed(Context context){
        File directory=directory(context);
        for(Object[] file:files){File f=new File(directory,(String)file[0]);if(!f.isFile()||f.length()!=(Long)file[1])return false;}
        return true;
    }

    synchronized State state(Context context){
        if(state==null||state==State.READY||state==State.ABSENT)state=installed(context)?State.READY:State.ABSENT;
        return state;
    }
    synchronized long received(){return received;}
    synchronized String failure(){return failure;}

    static void addListener(Listener listener){synchronized(listeners){listeners.add(listener);}}
    static void removeListener(Listener listener){synchronized(listeners){listeners.remove(listener);}}
    private static void notifyListeners(){
        main.post(()->{List<Listener> copy;synchronized(listeners){copy=new ArrayList<>(listeners);}for(Listener listener:copy)listener.changed();});
    }

    static String megabytes(long bytes){return String.format(Locale.US,"%d MB",Math.round(bytes/1_000_000.0));}

    synchronized void download(Context context){
        Context app=context.getApplicationContext();
        if(state==State.DOWNLOADING)return;
        if(installed(app)){state=State.READY;notifyListeners();return;}
        state=State.DOWNLOADING;received=0;failure="";cancelled=false;
        worker=new Thread(()->run(app),"SoftKeys model download");
        worker.start();
        notifyListeners();
    }

    synchronized void cancel(){
        if(state!=State.DOWNLOADING)return;
        cancelled=true;
        if(worker!=null)worker.interrupt();
    }

    /** Deletes the model. The keyboard uses the other model or the phone's speech service afterwards. */
    void delete(Context context){
        synchronized(this){if(state==State.DOWNLOADING)return;}
        if(kind==Kind.EDITOR)SmartEditor.release();else SpeechEngine.release();
        deleteTree(directory(context));
        deleteTree(temporary(context));
        synchronized(this){state=State.ABSENT;received=0;failure="";}
        notifyListeners();
    }

    private void run(Context app){
        File root=new File(app.getNoBackupFilesDir(),"speech");
        File temporary=temporary(app);
        String error=null;
        try{
            deleteTree(temporary);
            // 1.11 used one shared folder for unfinished downloads.
            deleteTree(new File(root,".download"));
            if(!temporary.mkdirs()&&!temporary.isDirectory())throw new IOException("Storage is unavailable");
            if(root.getUsableSpace()<size+50_000_000L)throw new IOException("Not enough free storage. The model needs "+megabytes(size)+".");
            long done=0;
            for(Object[] file:files){
                fetch((String)file[0],(Long)file[1],(String)file[2],new File(temporary,(String)file[0]),done);
                done+=(Long)file[1];
            }
            File target=directory(app);
            deleteTree(target);
            if(!temporary.renameTo(target))throw new IOException("Storage is unavailable");
        }catch(IOException|RuntimeException problem){
            error=cancelled?"":problem instanceof IOException&&problem.getMessage()!=null&&problem.getMessage().startsWith("Not enough")?problem.getMessage():"The download didn't finish. Check your connection and try again.";
        }finally{
            deleteTree(temporary);
        }
        synchronized(this){
            worker=null;
            if(error==null&&installed(app)){state=State.READY;received=size;}
            else{state=cancelled||error==null?State.ABSENT:State.FAILED;failure=error==null?"":error;received=0;}
        }
        notifyListeners();
    }

    private void fetch(String name,long expected,String sha256,File destination,long offset) throws IOException {
        HttpsURLConnection connection=connect(new URL(source+"/resolve/"+revision+"/"+name));
        try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(destination)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[128*1024];
            long total=0,lastNotice=0;
            int count;
            while((count=input.read(buffer))!=-1){
                if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("Cancelled");
                total+=count;
                if(total>expected)throw new IOException("Unexpected size");
                digest.update(buffer,0,count);output.write(buffer,0,count);
                long now=System.currentTimeMillis();
                if(now-lastNotice>250){lastNotice=now;synchronized(this){received=offset+total;}notifyListeners();}
            }
            output.getFD().sync();
            if(total!=expected)throw new IOException("Unexpected size");
            StringBuilder hex=new StringBuilder();
            for(byte b:digest.digest())hex.append(String.format(Locale.US,"%02x",b));
            if(!hex.toString().equals(sha256))throw new IOException("Checksum mismatch");
        }catch(java.security.NoSuchAlgorithmException missing){throw new IOException(missing);}
        finally{connection.disconnect();}
    }

    /** Follows redirects only to HTTPS Hugging Face hosts. */
    private static HttpsURLConnection connect(URL url) throws IOException {
        URL target=url;
        for(int hop=0;hop<6;hop++){
            String host=target.getHost().toLowerCase(Locale.US);
            if(!"https".equals(target.getProtocol())||target.getUserInfo()!=null||target.getPort()!=-1&&target.getPort()!=443||
                !(host.equals("huggingface.co")||host.endsWith(".huggingface.co")||host.endsWith(".hf.co")))throw new IOException("Unexpected model host");
            HttpsURLConnection http=(HttpsURLConnection)target.openConnection();
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(15_000);http.setReadTimeout(45_000);
            // Android's default user agent names the phone model and OS build.
            http.setRequestProperty("User-Agent","SoftKeysModelDownloader/1");
            http.setRequestProperty("Accept-Encoding","identity");
            int code=http.getResponseCode();
            if(code==200)return http;
            String location=http.getHeaderField("Location");
            http.disconnect();
            if(code/100!=3||location==null)throw new IOException("Model host returned "+code);
            target=new URL(target,location);
        }
        throw new IOException("Too many redirects");
    }

    private static void deleteTree(File file){
        if(file==null||!file.exists())return;
        File[] children=file.listFiles();
        if(children!=null)for(File child:children)deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
