package dev.softkeys.ios;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable offline frequency dictionary shared by tap suggestions and stroke decoding. */
final class EnglishDictionary {
    static final class Entry {
        final String word; final double frequency;
        Entry(String word, long count) { this.word = word; frequency = Math.log1p(count); }
    }
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> exact = new HashMap<>();
    private static final Map<String,String> SPELLINGS;
    static {
        Map<String,String> spellings=new HashMap<>();
        spellings.put("i","I");
        spellings.put("im","I'm");
        spellings.put("ive","I've");
        spellings.put("ill","I'll");
        spellings.put("dont","don't");
        spellings.put("doesnt","doesn't");
        spellings.put("didnt","didn't");
        spellings.put("cant","can't");
        spellings.put("couldnt","couldn't");
        spellings.put("wouldnt","wouldn't");
        spellings.put("shouldnt","shouldn't");
        spellings.put("wont","won't");
        spellings.put("isnt","isn't");
        spellings.put("arent","aren't");
        spellings.put("wasnt","wasn't");
        spellings.put("werent","weren't");
        spellings.put("youre","you're");
        spellings.put("theyre","they're");
        spellings.put("thats","that's");
        spellings.put("theres","there's");
        spellings.put("whats","what's");
        spellings.put("lets","let's");
        spellings.put("teh","the");
        spellings.put("adn","and");
        spellings.put("hte","the");
        spellings.put("recieve","receive");
        spellings.put("definately","definitely");
        spellings.put("seperate","separate");
        SPELLINGS=Collections.unmodifiableMap(spellings);
    }

    EnglishDictionary(Context context) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(context.getAssets().open("english.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] pair = line.split("\t"); Entry entry = new Entry(pair[0], Long.parseLong(pair[1]));
                entries.add(entry); exact.put(entry.word, entry);
            }
        }
    }
    static String matchCase(String text, String model) {
        if (model.length() > 1 && model.equals(model.toUpperCase(Locale.US))) return text.toUpperCase(Locale.US);
        if (!model.isEmpty() && Character.isUpperCase(model.charAt(0))) return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text;
    }
    static final class Ranked implements Comparable<Ranked> {
        final String word; final double score;
        Ranked(String word, double score) { this.word=word; this.score=score; }
        @Override public int compareTo(Ranked other) { return Double.compare(score,other.score); }
    }
    List<String> suggest(String typed) {
        if (typed.isEmpty()) return Arrays.asList("I", "the", "I'm");
        String lower = typed.toLowerCase(Locale.US);
        if (lower.length() > 26) return Arrays.asList(typed);
        List<Ranked> ranked = new ArrayList<>();
        String fix = SPELLINGS.get(lower);
        if (fix != null && !fix.equals(typed)) ranked.add(new Ranked(fix,-30));
        for (Entry e : entries) {
            if (e.word.equals(lower)) continue;
            if (e.word.startsWith(lower)) ranked.add(new Ranked(e.word,1.0 + (e.word.length()-lower.length())*.10-e.frequency*.13));
            else if (lower.length() >= 3 && Math.abs(e.word.length()-lower.length()) <= (lower.length()>=6?2:1)) {
                int d = distance(lower,e.word);
                if (d <= (lower.length()>=6?2:1)) ranked.add(new Ranked(e.word,d*1.7-e.frequency*.13));
            }
        }
        Collections.sort(ranked);
        LinkedHashSet<String> result = new LinkedHashSet<>(); result.add(typed);
        for (Ranked r : ranked) { result.add(matchCase(r.word,typed)); if(result.size()==3)break; }
        return new ArrayList<>(result);
    }
    String correct(String typed) {
        String lower=typed.toLowerCase(Locale.US), fix=SPELLINGS.get(lower);
        if (fix!=null) return matchCase(fix,typed);
        if (lower.length()<3 || lower.length()>24 || exact.containsKey(lower)) return typed;
        // Preserve acronyms and mixed case names.
        if (typed.length()>1 && !typed.substring(1).equals(typed.substring(1).toLowerCase(Locale.US))) return typed;
        Ranked best=null; int max=lower.length()>=6?2:1;
        for (Entry e : entries) {
            if (e.frequency<7.5 || Math.abs(e.word.length()-lower.length())>max) continue;
            int d=distance(lower,e.word); if(d>max)continue;
            double score=d*2-e.frequency*.15;
            if(best==null||score<best.score)best=new Ranked(e.word,score);
        }
        return best==null?typed:matchCase(best.word,typed);
    }
    private static int distance(String a,String b) {
        int n=a.length(),m=b.length(); int[] prev=new int[m+1],prev2=new int[m+1],row=new int[m+1];
        for(int j=0;j<=m;j++)prev[j]=j;
        for(int i=1;i<=n;i++) {
            row[0]=i;
            for(int j=1;j<=m;j++) {
                row[j]=Math.min(Math.min(prev[j]+1,row[j-1]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
                if(i>1&&j>1&&a.charAt(i-1)==b.charAt(j-2)&&a.charAt(i-2)==b.charAt(j-1))row[j]=Math.min(row[j],prev2[j-2]+1);
            }
            int[] reuse=prev2;prev2=prev;prev=row;row=reuse;
        }
        return prev[m];
    }
    /** Decode a complete path against the geometry of the visible, continuous keyboard. */
    List<String> glide(float[] path, Map<Character,float[]> centers, float keyWidth) {
        if(path.length<4)return Collections.emptyList();
        float[] gesture=resample(path,32); double length=pathLength(path);
        List<Ranked> ranked=new ArrayList<>();
        float sx=path[0],sy=path[1],ex=path[path.length-2],ey=path[path.length-1];
        double unit=Math.max(1,keyWidth*keyWidth);
        for(Entry e:entries) {
            String w=e.word.replace("'","");if(w.length()<2||w.length()>24)continue;
            float[] first=centers.get(w.charAt(0)),last=centers.get(w.charAt(w.length()-1));if(first==null||last==null)continue;
            double start=square(sx-first[0])+square(sy-first[1]),end=square(ex-last[0])+square(ey-last[1]);
            if(start>unit*1.6||end>unit*1.6)continue;
            float[] ideal=new float[w.length()*2];int count=0;char previous=0;boolean valid=true;
            for(int i=0;i<w.length();i++) {
                char c=w.charAt(i);float[] p=centers.get(c);if(p==null){valid=false;break;}if(c==previous)continue;
                ideal[count++]=p[0];ideal[count++]=p[1];previous=c;
            }
            if(!valid||count<4)continue;
            ideal=Arrays.copyOf(ideal,count);double idealLength=pathLength(ideal);
            if(idealLength>length*1.9+keyWidth||length>idealLength*2.0+keyWidth*2)continue;
            float[] model=resample(ideal,32);double shape=0;
            // Arc length samples preserve corners and tolerate different gesture speeds.
            for(int i=0;i<model.length;i+=2)shape+=(square(gesture[i]-model[i])+square(gesture[i+1]-model[i+1]))/unit;
            double score=shape/32+1.2*(start+end)/unit+Math.abs(Math.log((length+keyWidth)/(idealLength+keyWidth)))*.9-e.frequency*.085;
            ranked.add(new Ranked(e.word,score));
        }
        Collections.sort(ranked);List<String> results=new ArrayList<>();
        for(Ranked r:ranked){results.add(r.word);if(results.size()==3)break;}
        return results;
    }
    private static double square(double n){return n*n;}
    private static double pathLength(float[] points){double len=0;for(int i=2;i<points.length;i+=2)len+=Math.hypot(points[i]-points[i-2],points[i+1]-points[i-1]);return len;}
    private static float[] resample(float[] p,int n) {
        float[] result=new float[n*2];double total=pathLength(p),walked=0;int segment=2;
        for(int i=0;i<n;i++) {
            double target=total*i/(n-1);
            while(segment<p.length-2&&walked+Math.hypot(p[segment]-p[segment-2],p[segment+1]-p[segment-1])<target){walked+=Math.hypot(p[segment]-p[segment-2],p[segment+1]-p[segment-1]);segment+=2;}
            double length=Math.hypot(p[segment]-p[segment-2],p[segment+1]-p[segment-1]);double t=length==0?0:Math.min(1,(target-walked)/length);
            result[i*2]=(float)(p[segment-2]+t*(p[segment]-p[segment-2]));result[i*2+1]=(float)(p[segment-1]+t*(p[segment+1]-p[segment-1]));
        }
        return result;
    }
}
