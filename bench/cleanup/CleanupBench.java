import dev.softkeys.ios.DictationCleanup;
import dev.softkeys.ios.EnglishDictionary;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores the Clean style on the held-out split of the Handy dictation-editing corpus
 * (https://huggingface.co/datasets/MagicNoThief/handy-dictation-editing, CC-BY-4.0, built from
 * google-research-datasets/disfl_qa, nyralabs/disfluency_speech_english, and
 * amaai-lab/DisfluencySpeech). The data is downloaded, not stored in this repository.
 *
 *   mkdir -p build/cleanup-bench
 *   curl -L -o build/cleanup-bench/handy_eval.jsonl \
 *     https://huggingface.co/datasets/MagicNoThief/handy-dictation-editing/resolve/main/handy_eval.jsonl
 *   javac -d build/cleanup-bench bench/cleanup/CleanupBench.java app/src/main/java/dev/softkeys/ios/DictationCleanup.java \
 *     app/src/main/java/dev/softkeys/ios/DictationNumbers.java app/src/main/java/dev/softkeys/ios/EnglishDictionary.java
 *   java -cp build/cleanup-bench CleanupBench build/cleanup-bench/handy_eval.jsonl
 *
 * The corpus's targets also drop words such as "so" and "okay" and add commas, which Clean
 * does not do, so an exact match is the wrong measure. The bench compares words instead,
 * after both sides go through Clean and lose punctuation, capitals, and the discourse words
 * below. It reports two groups:
 *
 * Corrections are rows whose input contains a correction phrase. A row is right when Clean
 * keeps every word of the target and no word of the abandoned version.
 *
 * Keep rows are rows where the target has every input word except fillers. A row is right
 * when Clean deletes none of those words. This group catches rules that delete too much.
 *
 * The corpus inputs have no punctuation, but Parakeet sets off a phrase such as "make that"
 * with commas, and several rules need that pause. The "with pauses" run adds a comma before
 * and after each correction phrase to approximate Parakeet's output. That is an assumption,
 * so both runs are printed.
 */
public final class CleanupBench {
    private static final String[] CUES={"let me correct that","come to think of it","on second thoughts","on second thought","actually wait",
        "wait actually","wait sorry","no wait","wait no","actually no","no actually","oh wait","no sorry","sorry no","make that","i meant",
        "never mind","nevermind","ignore that","my mistake","correction","scratch that","strike that","delete that","sorry i mean","i mean",
        "or rather","actually","sorry","rather","wait"};
    private static final List<String> DISCOURSE=List.of("so","okay","ok","well","right","anyway","yeah","like","just","um","uh","er","erm",
        "hmm","mm","ah","oh","you","know","i","mean","actually","basically","obviously","sort","of","kind","let's","see","quick","note",
        "heads","up","following","for","the","record","as","discussed","one","more","thing","just","checking","and","a","an","to");

    public static void main(String[] args) throws Exception {
        EnglishDictionary dictionary=new EnglishDictionary(new FileInputStream("app/src/main/assets/english.tsv"),new FileInputStream("app/src/main/assets/lexicon.tsv"));
        DictationCleanup.Options options=new DictationCleanup.Options();
        options.lowercaseWord=dictionary::commonLowercase;options.knownWord=dictionary::contains;
        List<String[]> rows=new ArrayList<>();
        Pattern content=Pattern.compile("\"content\": \"((?:[^\"\\\\]|\\\\.)*)\"");
        for(String line:Files.readAllLines(Paths.get(args[0]))){
            Matcher m=content.matcher(line);
            if(!m.find())continue;String in=unescape(m.group(1));
            if(!m.find())continue;String out=unescape(m.group(1));
            rows.add(new String[]{in,out});
        }
        for(boolean pauses:new boolean[]{false,true}){
            int cut=0,cutRight=0,keep=0,keepRight=0;
            List<String> wrong=new ArrayList<>();
            for(String[] row:rows){
                String in=pauses?withPauses(row[0]):row[0];
                List<String> got=words(DictationCleanup.clean(in,options)),want=words(DictationCleanup.clean(row[1],options));
                List<String> input=words(DictationCleanup.clean(row[0].replaceAll("(?i)\\b(um|uh|er|erm|hmm|mm)\\b",""),options));
                if(hasCue(row[0])){
                    cut++;
                    if(missing(want,got).isEmpty()&&missing(got,want).isEmpty())cutRight++;
                    else if(wrong.size()<40)wrong.add(row[0]+"\n  got:  "+DictationCleanup.clean(in,options)+"\n  want: "+row[1]);
                }else if(missing(input,want).isEmpty()){
                    keep++;
                    List<String> lost=missing(want,got);
                    if(lost.isEmpty())keepRight++;
                    else if(wrong.size()<40)wrong.add("KEEP "+row[0]+"\n  got:  "+DictationCleanup.clean(in,options)+"\n  lost: "+lost);
                }
            }
            System.out.printf(Locale.ROOT,"%s: corrections %d/%d right (%.1f%%), keep rows %d/%d untouched (%.1f%%)%n",
                pauses?"with pauses":"as written",cutRight,cut,100.0*cutRight/cut,keepRight,keep,100.0*keepRight/keep);
            if(args.length>1&&args[1].equals("--show"))for(String w:wrong)System.out.println(w);
        }
    }

    private static String unescape(String s){return s.replace("\\\"","\"").replace("\\n","\n").replace("\\\\","\\");}

    private static boolean hasCue(String text){
        String lower=" "+text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]"," ").replaceAll("\\s+"," ")+" ";
        for(String cue:CUES)if(lower.contains(" "+cue+" "))return true;
        return false;
    }

    /** Puts a comma before and after the first correction phrase, as Parakeet usually does. */
    private static String withPauses(String text){
        for(String cue:CUES){
            Matcher m=Pattern.compile("(?i)(?<=\\w)\\s+("+Pattern.quote(cue)+")\\s+(?=\\w)").matcher(text);
            if(m.find())return text.substring(0,m.start())+", "+m.group(1)+", "+text.substring(m.end());
        }
        return text;
    }

    private static List<String> words(String text){
        List<String> list=new ArrayList<>();
        for(String w:text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9'$%:@.\"]+"," ").split(" ")){
            w=w.replaceAll("^[.'\"]+|[.'\"]+$","");
            if(!w.isEmpty()&&!DISCOURSE.contains(w))list.add(w);
        }
        return list;
    }

    /** Words of a, counted with repeats, that b lacks. */
    private static List<String> missing(List<String> a,List<String> b){
        Map<String,Integer> counts=new HashMap<>();
        for(String w:b)counts.merge(w,1,Integer::sum);
        List<String> out=new ArrayList<>();
        for(String w:a){Integer n=counts.get(w);if(n==null||n==0)out.add(w);else counts.put(w,n-1);}
        return out;
    }
}
