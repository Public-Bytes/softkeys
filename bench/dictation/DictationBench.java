import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Measures word error rate, speed, model load time, and the delay between Done and text
 * for the on-device speech models, using the same Java binding classes as the app and the
 * sherpa-onnx Linux JNI library. See docs/dictation-benchmark.md.
 *
 * Usage: java DictationBench MODE MODEL_DIR THREADS LIST [LATENCY_LIST]
 *   MODE is "online" for the streaming model, "offline" for a model that decodes on Done, or
 *   "segmented" for that model with the long-recording splits that BatchDictation uses.
 *   LIST and LATENCY_LIST hold one "path.wav<TAB>reference text" line per 16 kHz mono recording.
 */
public final class DictationBench {
    private static final int RATE=16000;

    public static void main(String[] args) throws Exception {
        if(args.length<4){System.err.println("usage: DictationBench online|offline MODEL_DIR THREADS LIST [LATENCY_LIST]");System.exit(2);}
        boolean online=args[0].equals("online"),segmented=args[0].equals("segmented");
        String dir=args[1];int threads=Integer.parseInt(args[2]);
        List<String[]> items=read(args[3]);
        List<String[]> latencyItems=args.length>4?read(args[4]):List.of();

        long t0=System.nanoTime();
        Model model=online?new Streaming(dir,threads):segmented?new Segmented(new Batch(dir,threads)):new Batch(dir,threads);
        double load=(System.nanoTime()-t0)/1e9;
        long loadRss=memoryMb("VmRSS:");
        // One warm-up decode, as the app does by loading the model when the panel opens.
        model.transcribe(new float[RATE*2]);

        long errors=0,words=0;double audio=0,busy=0;
        for(String[] item:items){
            float[] samples=wav(item[0]);
            Result result=model.transcribe(samples);
            String[] ref=normalize(item[1]),hyp=normalize(result.text);
            int e=distance(ref,hyp);errors+=e;words+=ref.length;
            audio+=samples.length/(double)RATE;busy+=result.seconds;
            if(e>0)System.err.printf("%s  errors %d%n  ref: %s%n  hyp: %s%n",Path.of(item[0]).getFileName(),e,String.join(" ",ref),String.join(" ",hyp));
        }
        StringBuilder latency=new StringBuilder();
        for(String[] item:latencyItems){
            float[] samples=wav(item[0]);
            double[] runs=new double[5];
            for(int i=0;i<runs.length;i++)runs[i]=model.transcribe(samples).doneToText;
            Arrays.sort(runs);
            latency.append(String.format(Locale.US," done_%.1fs=%.3f",samples.length/(double)RATE,runs[runs.length/2]));
        }
        System.out.printf(Locale.US,"mode=%s threads=%d load=%.2f utterances=%d audio=%.1f busy=%.1f rtf=%.3f words=%d errors=%d wer=%.2f%%%s load_rss_mb=%d peak_rss_mb=%d%n",
            args[0],threads,load,items.size(),audio,busy,busy/Math.max(audio,1e-9),words,errors,100.0*errors/Math.max(words,1),latency,loadRss,memoryMb("VmHWM:"));
        model.release();
    }

    record Result(String text,double seconds,double doneToText){}

    interface Model {Result transcribe(float[] samples);void release();}

    /**
     * The streaming model as OnDeviceDictation runs it: 100 ms chunks, segments at endpoints,
     * then one second of silence and a final decode when the user taps Done. Only that final
     * step happens after Done, because the earlier audio was decoded while the user spoke.
     */
    static final class Streaming implements Model {
        final OnlineRecognizer recognizer;
        Streaming(String dir,int threads){recognizer=OnlineRecognizer.transducer(dir+"/encoder.int8.onnx",dir+"/decoder.int8.onnx",dir+"/joiner.int8.onnx",dir+"/tokens.txt",threads);}
        public Result transcribe(float[] samples){
            OnlineStream stream=recognizer.createStream();
            StringBuilder complete=new StringBuilder();
            long start=System.nanoTime();
            for(int i=0;i<samples.length;i+=RATE/10){
                stream.acceptWaveform(Arrays.copyOfRange(samples,i,Math.min(samples.length,i+RATE/10)),RATE);
                while(recognizer.isReady(stream))recognizer.decode(stream);
                if(recognizer.isEndpoint(stream)){append(complete,recognizer.text(stream));recognizer.reset(stream);}
            }
            long done=System.nanoTime();
            stream.acceptWaveform(new float[RATE],RATE);stream.inputFinished();
            while(recognizer.isReady(stream))recognizer.decode(stream);
            append(complete,recognizer.text(stream));
            long end=System.nanoTime();
            stream.release();
            return new Result(complete.toString(),(end-start)/1e9,(end-done)/1e9);
        }
        public void release(){recognizer.release();}
    }

    /** A model that decodes the whole recording after Done, as Hex does with Parakeet. */
    static final class Batch implements Model {
        final OfflineRecognizer recognizer;
        Batch(String dir,int threads){
            String encoder=Files.exists(Path.of(dir,"encoder.int8.onnx"))?"encoder.int8.onnx":"encoder.onnx";
            String decoder=encoder.contains("int8")?"decoder.int8.onnx":"decoder.onnx",joiner=encoder.contains("int8")?"joiner.int8.onnx":"joiner.onnx";
            recognizer=OfflineRecognizer.nemoTransducer(dir+"/"+encoder,dir+"/"+decoder,dir+"/"+joiner,dir+"/tokens.txt",threads);
        }
        public Result transcribe(float[] samples){
            long start=System.nanoTime();
            OfflineStream stream=recognizer.createStream();
            stream.acceptWaveform(samples,RATE);
            recognizer.decode(stream);
            String text=recognizer.text(stream);
            stream.release();
            double seconds=(System.nanoTime()-start)/1e9;
            return new Result(text,seconds,seconds);
        }
        public void release(){recognizer.release();}
    }

    /**
     * The offline model as BatchDictation runs it on long recordings: SpeechSegmenter splits
     * the audio at pauses, and the earlier parts are decoded while the user is still talking.
     * Only the last part, with 0.25 s of added silence, is decoded after Done.
     */
    static final class Segmented implements Model {
        final Batch batch;
        Segmented(Batch batch){this.batch=batch;}
        public Result transcribe(float[] samples){
            dev.softkeys.ios.SpeechSegmenter segmenter=new dev.softkeys.ios.SpeechSegmenter();
            StringBuilder complete=new StringBuilder();
            int start=0,chunk=dev.softkeys.ios.SpeechSegmenter.CHUNK;double busy=0;
            for(int i=0;i+chunk<=samples.length;i+=chunk){
                int cut=segmenter.add(dev.softkeys.ios.SpeechSegmenter.rms(samples,i,i+chunk));
                if(cut==0)continue;
                int end=start+cut*chunk;
                Result part=batch.transcribe(Arrays.copyOfRange(samples,start,end));
                append(complete,part.text);busy+=part.seconds;start=end;
                System.err.printf(Locale.US,"  part ends at %.1f s, decoded in %.2f s%n",end/(double)RATE,part.seconds);
            }
            float[] rest=Arrays.copyOf(Arrays.copyOfRange(samples,start,samples.length),samples.length-start+RATE/4);
            Result last=batch.transcribe(rest);
            append(complete,last.text);
            return new Result(complete.toString(),busy+last.seconds,last.seconds);
        }
        public void release(){batch.release();}
    }

    static void append(StringBuilder complete,String text){text=text.trim();if(text.isEmpty())return;if(complete.length()>0)complete.append(' ');complete.append(text);}

    /** Lowercase words without punctuation, so models with and without punctuation compare fairly. */
    static String[] normalize(String text){
        String plain=text.toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("[-—–]"," ").replaceAll("[^a-z0-9' ]"," ").replaceAll("(^|\\s)'+|'+(\\s|$)"," ").trim();
        return plain.isEmpty()?new String[0]:plain.split("\\s+");
    }

    /** Word-level edit distance: substitutions, deletions, and insertions. */
    static int distance(String[] a,String[] b){
        int[] previous=new int[b.length+1],current=new int[b.length+1];
        for(int j=0;j<=b.length;j++)previous[j]=j;
        for(int i=1;i<=a.length;i++){
            current[0]=i;
            for(int j=1;j<=b.length;j++)current[j]=Math.min(Math.min(current[j-1]+1,previous[j]+1),previous[j-1]+(a[i-1].equals(b[j-1])?0:1));
            int[] swap=previous;previous=current;current=swap;
        }
        return previous[b.length];
    }

    static List<String[]> read(String list) throws IOException {
        List<String[]> items=new ArrayList<>();
        for(String line:Files.readAllLines(Path.of(list))){int tab=line.indexOf('\t');if(tab>0)items.add(new String[]{line.substring(0,tab),line.substring(tab+1)});}
        return items;
    }

    static float[] wav(String path) throws IOException {
        byte[] bytes=Files.readAllBytes(Path.of(path));
        ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int position=12,rate=0,channels=0,bits=0,data=-1,length=0;
        while(position+8<=bytes.length){
            String id=new String(bytes,position,4,java.nio.charset.StandardCharsets.US_ASCII);int size=buffer.getInt(position+4);
            if(id.equals("fmt ")){channels=buffer.getShort(position+10);rate=buffer.getInt(position+12);bits=buffer.getShort(position+22);}
            if(id.equals("data")){data=position+8;length=Math.min(size,bytes.length-data);break;}
            position+=8+size+(size&1);
        }
        if(rate!=RATE||channels!=1||bits!=16||data<0)throw new IOException(path+": expected 16 kHz mono 16-bit PCM");
        float[] samples=new float[length/2];
        for(int i=0;i<samples.length;i++)samples[i]=buffer.getShort(data+2*i)/32768f;
        return samples;
    }

    /** A memory figure from /proc/self/status in MB: VmRSS is resident now, and VmHWM is the peak. Includes the JVM. */
    static long memoryMb(String field){
        try{for(String line:Files.readAllLines(Path.of("/proc/self/status")))if(line.startsWith(field))return Long.parseLong(line.replaceAll("\\D",""))/1024;}
        catch(IOException|RuntimeException ignored){}
        return -1;
    }
}
