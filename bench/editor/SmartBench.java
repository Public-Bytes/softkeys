/**
 * Compares rules-only Clean with smart cleanup on the Handy dictation-editing corpus's held-out
 * split (see bench/cleanup/CleanupBench.java for the download and licence). It uses the desktop
 * harness bench/editor/editor_main.cpp, which runs the same C++ as the app, and a downloaded
 * copy of the smart cleanup model.
 *
 *   javac -d build/smartbench bench/editor/SmartBench.java app/src/main/java/dev/softkeys/ios/DictationCleanup.java \
 *     app/src/main/java/dev/softkeys/ios/DictationNumbers.java app/src/main/java/dev/softkeys/ios/EnglishDictionary.java \
 *     app/src/main/java/dev/softkeys/ios/SmartEditCheck.java
 *   java -cp build/smartbench SmartBench prepare build/cleanup-bench/handy_eval.jsonl 300
 *   build/editor-main handy-editor-350m-Q4_K_M.gguf 8 < build/smartbench/prepared.txt > build/smartbench/edited.txt
 *   java -cp build/smartbench SmartBench score build/cleanup-bench/handy_eval.jsonl 300 [show]
 *
 * It takes up to 300 rows with a correction phrase (with commas added around the phrase, as
 * Parakeet writes it) and up to 300 rows without one. It prints how many corrections each way
 * gets right, how many rows that need no change stay untouched, and how many model edits
 * SmartEditCheck accepted.
 */
import dev.softkeys.ios.*;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.regex.*;
public class SmartBench{
 static final String[] CUES={"let me correct that","come to think of it","on second thoughts","on second thought","actually wait","wait actually","wait sorry","no wait","wait no","actually no","no actually","oh wait","no sorry","sorry no","make that","i meant","never mind","nevermind","ignore that","my mistake","correction","scratch that","strike that","delete that","sorry i mean","i mean","or rather","actually","sorry","rather","wait"};
 static final List<String> DISCOURSE=List.of("so","okay","ok","well","right","anyway","yeah","like","just","um","uh","er","erm","hmm","mm","ah","oh","you","know","i","mean","actually","basically","obviously","sort","of","kind","let's","see","quick","note","heads","up","following","for","the","record","as","discussed","one","more","thing","just","checking","and","a","an","to");
 static String pauses(String t){for(String c:CUES){Matcher m=Pattern.compile("(?i)(?<=\\w)\\s+("+Pattern.quote(c)+")\\s+(?=\\w)").matcher(t);if(m.find())return t.substring(0,m.start())+", "+m.group(1)+", "+t.substring(m.end());}return t;}
 static boolean cue(String t){String l=" "+t.toLowerCase().replaceAll("[^a-z' ]"," ").replaceAll("\\s+"," ")+" ";for(String c:CUES)if(l.contains(" "+c+" "))return true;return false;}
 static List<String> words(String t){List<String> l=new ArrayList<>();for(String w:t.toLowerCase().replaceAll("[^a-z0-9'$%:@.\"]+"," ").split(" ")){w=w.replaceAll("^[.'\"]+|[.'\"]+$","");if(!w.isEmpty()&&!DISCOURSE.contains(w))l.add(w);}return l;}
 static List<String> missing(List<String> a,List<String> b){Map<String,Integer> c=new HashMap<>();for(String w:b)c.merge(w,1,Integer::sum);List<String> o=new ArrayList<>();for(String w:a){Integer n=c.get(w);if(n==null||n==0)o.add(w);else c.put(w,n-1);}return o;}
 public static void main(String[] a)throws Exception{
  EnglishDictionary d=new EnglishDictionary(new FileInputStream("app/src/main/assets/english.tsv"),new FileInputStream("app/src/main/assets/lexicon.tsv"));
  DictationCleanup.Options o=new DictationCleanup.Options();o.lowercaseWord=d::commonLowercase;o.knownWord=d::contains;
  Pattern p=Pattern.compile("\"content\": \"((?:[^\"\\\\]|\\\\.)*)\"");
  List<String[]> rows=new ArrayList<>();int cut=0,keep=0;int N=Integer.parseInt(a[2]);
  for(String line:Files.readAllLines(Paths.get(a[1]))){Matcher m=p.matcher(line);m.find();String in=m.group(1).replace("\\\"","\"");m.find();String out=m.group(1).replace("\\\"","\"");
   boolean c=cue(in);if(c&&cut<N){cut++;rows.add(new String[]{pauses(in),out,"cut",in});}else if(!c&&keep<N){keep++;rows.add(new String[]{in,out,"keep",in});}}
  if(a[0].equals("prepare")){try(PrintWriter w=new PrintWriter("build/smartbench/prepared.txt")){for(String[] r:rows){String prep=DictationCleanup.prepareForEditor(r[0],o);w.println(prep.isEmpty()?"empty":prep);}}return;}
  List<String> outs=new ArrayList<>();List<String> lines=Files.readAllLines(Paths.get("build/smartbench/edited.txt"));
  for(String l:lines)if(l.startsWith("   -> "))outs.add(l.substring(6));
  int[] right=new int[4],total=new int[4];int used=0,rejected=0;List<String> show=new ArrayList<>();
  for(int i=0;i<rows.size()&&i<outs.size();i++){String[] r=rows.get(i);String prep=DictationCleanup.prepareForEditor(r[0],o);String ed=outs.get(i);
   boolean ok=!ed.isEmpty()&&SmartEditCheck.accept(prep,ed);if(ok)used++;else rejected++;
   String smart=DictationCleanup.clean(ok?ed:r[0],o),rules=DictationCleanup.clean(r[0],o);
   List<String> want=words(DictationCleanup.clean(r[1],o));
   for(int v=0;v<2;v++){String got=v==0?rules:smart;List<String> g=words(got);int k=r[2].equals("cut")?v:2+v;total[k]++;
    if(r[2].equals("cut")){if(missing(want,g).isEmpty()&&missing(g,want).isEmpty())right[k]++;}
    else{List<String> input=words(DictationCleanup.clean(r[3].replaceAll("(?i)\\b(um|uh|er|erm|hmm|mm)\\b",""),o));if(!missing(input,want).isEmpty()){total[k]--;continue;}if(missing(want,g).isEmpty())right[k]++;}}
   if(a.length>3&&show.size()<30&&r[2].equals("cut"))show.add(r[0]+"\n  model: "+ed+(ok?"":"  [rejected]")+"\n  smart: "+smart+"\n  want:  "+r[1]);
  }
  System.out.printf("corrections: rules %d/%d, smart %d/%d%n",right[0],total[0],right[1],total[1]);
  System.out.printf("keep rows untouched: rules %d/%d, smart %d/%d%n",right[2],total[2],right[3],total[3]);
  System.out.printf("model edits used %d, rejected %d%n",used,rejected);
  for(String s:show)System.out.println(s);
 }}
