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
 * Downloads, verifies, and deletes the on-device speech model.
 *
 * The download is the only network use in SoftKeys. It is a fixed HTTPS GET of public
 * model files from a pinned Hugging Face revision, started only from settings. No audio,
 * text, or device details are sent. Files go to app-private no-backup storage and are
 * checked against the sizes and SHA-256 digests below before they can be used.
 */
final class SpeechModel {
    static final String NAME="NVIDIA FastConformer streaming English, 480 ms, int8";
    static final String SOURCE="https://huggingface.co/csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8";
    private static final String REVISION="df8ed95e44a70924450381e610770f9d656d1e15";
    private static final String DIRECTORY="nemo-fastconformer-en-480ms-int8-"+REVISION.substring(0,8);
    /** Name, size in bytes, and SHA-256 of each file. */
    private static final Object[][] FILES={
        {"encoder.int8.onnx",131_507_603L,"100c5616929a131b5b3c8c8ab0d83aaba2cdcae163acd8d190b4e5ffa5f7d051"},
        {"decoder.int8.onnx",3_955_864L,"4f04431988472f8c7b815f942aa6901976929c9e22353029681fcdb262da0164"},
        {"joiner.int8.onnx",1_408_182L,"d2d8e7290a6a8245a83ed211aa0dd8cdf8effb8a7c64fe392399561111b52f30"},
        {"tokens.txt",11_896L,"618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131"},
    };
    static final long SIZE;
    static {long total=0;for(Object[] file:FILES)total+=(Long)file[1];SIZE=total;}

    enum State {ABSENT,DOWNLOADING,READY,FAILED}
    interface Listener {void changed();}

    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners=new ArrayList<>();
    private static State state;
    private static long received;
    private static String failure="";
    private static volatile boolean cancelled;
    private static Thread worker;

    private SpeechModel(){}

    static File directory(Context context){return new File(new File(context.getNoBackupFilesDir(),"speech"),DIRECTORY);}
    static String path(Context context,String name){return new File(directory(context),name).getAbsolutePath();}

    /** A quick check that every verified file is present with its expected size. */
    static boolean installed(Context context){
        File directory=directory(context);
        for(Object[] file:FILES){File f=new File(directory,(String)file[0]);if(!f.isFile()||f.length()!=(Long)file[1])return false;}
        return true;
    }

    static synchronized State state(Context context){
        if(state==null||state==State.READY||state==State.ABSENT)state=installed(context)?State.READY:State.ABSENT;
        return state;
    }
    static synchronized long received(){return received;}
    static synchronized String failure(){return failure;}

    static void addListener(Listener listener){synchronized(listeners){listeners.add(listener);}}
    static void removeListener(Listener listener){synchronized(listeners){listeners.remove(listener);}}
    private static void notifyListeners(){
        main.post(()->{List<Listener> copy;synchronized(listeners){copy=new ArrayList<>(listeners);}for(Listener listener:copy)listener.changed();});
    }

    static String megabytes(long bytes){return String.format(Locale.US,"%d MB",Math.round(bytes/1_000_000.0));}

    static synchronized void download(Context context){
        Context app=context.getApplicationContext();
        if(state==State.DOWNLOADING)return;
        if(installed(app)){state=State.READY;notifyListeners();return;}
        state=State.DOWNLOADING;received=0;failure="";cancelled=false;
        worker=new Thread(()->run(app),"SoftKeys model download");
        worker.start();
        notifyListeners();
    }

    static synchronized void cancel(){
        if(state!=State.DOWNLOADING)return;
        cancelled=true;
        if(worker!=null)worker.interrupt();
    }

    /** Deletes the model. The keyboard falls back to the phone's speech service afterwards. */
    static void delete(Context context){
        synchronized(SpeechModel.class){if(state==State.DOWNLOADING)return;}
        SpeechEngine.release();
        deleteTree(directory(context));
        deleteTree(new File(new File(context.getNoBackupFilesDir(),"speech"),".download"));
        synchronized(SpeechModel.class){state=State.ABSENT;received=0;failure="";}
        notifyListeners();
    }

    private static void run(Context app){
        File root=new File(app.getNoBackupFilesDir(),"speech");
        File temporary=new File(root,".download");
        String error=null;
        try{
            deleteTree(temporary);
            if(!temporary.mkdirs()&&!temporary.isDirectory())throw new IOException("Storage is unavailable");
            if(root.getUsableSpace()<SIZE+50_000_000L)throw new IOException("Not enough free storage. The model needs "+megabytes(SIZE)+".");
            long done=0;
            for(Object[] file:FILES){
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
        synchronized(SpeechModel.class){
            worker=null;
            if(error==null&&installed(app)){state=State.READY;received=SIZE;}
            else{state=cancelled||error==null?State.ABSENT:State.FAILED;failure=error==null?"":error;received=0;}
        }
        notifyListeners();
    }

    private static void fetch(String name,long size,String sha256,File destination,long offset) throws IOException {
        HttpsURLConnection connection=connect(new URL(SOURCE+"/resolve/"+REVISION+"/"+name));
        try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(destination)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[128*1024];
            long total=0,lastNotice=0;
            int count;
            while((count=input.read(buffer))!=-1){
                if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("Cancelled");
                total+=count;
                if(total>size)throw new IOException("Unexpected size");
                digest.update(buffer,0,count);output.write(buffer,0,count);
                long now=System.currentTimeMillis();
                if(now-lastNotice>250){lastNotice=now;synchronized(SpeechModel.class){received=offset+total;}notifyListeners();}
            }
            output.getFD().sync();
            if(total!=size)throw new IOException("Unexpected size");
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
