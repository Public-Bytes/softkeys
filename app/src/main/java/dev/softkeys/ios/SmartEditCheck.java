package dev.softkeys.ios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether SoftKeys may use the smart cleanup model's edit of a transcript.
 *
 * The model is allowed to delete words and to change punctuation and capitals, and nothing
 * else. Every word it writes must appear in the input, in the same order. Each run of
 * deleted words must be one of three things: filler words such as "um" or "you know", a
 * repeat of the words that follow it, or a run next to a correction phrase such as "no
 * wait" or "I mean", which is the text the speaker took back. An edit that adds or changes
 * a word, deletes a sentence with no correction phrase near it, or deletes more than 60% of
 * the words other than fillers is rejected, and SoftKeys inserts the rules-only text instead.
 *
 * The class uses no Android classes, so tests/SmartEditCheckTest.java runs it on a plain JDK.
 */
public final class SmartEditCheck {
    private SmartEditCheck(){}

    private static final Set<String> FILLERS=Set.of("um","uh","er","erm","ah","hmm","mm","like","so","well","okay","ok","right","yeah",
        "anyway","basically","literally","you","know","sort","kind","of","i","mean","guess","let's","see","let","me");
    /** Words of correction phrases. A deleted run must touch one of these or contain one. */
    private static final Set<String> CUES=Set.of("wait","sorry","actually","mean","meant","scratch","strike","delete","mind","rather",
        "correction","mistake","ignore","thought","thoughts","make","no","correct","oops");
    /** Correction words that change the meaning when deleted alone. */
    private static final Set<String> SINGLE_KEEP=Set.of("actually","no","sorry","rather","make","mind","thought","mean","meant","correct","delete");
    /** Words that may be deleted only as part of a filler phrase, not alone. */
    private static final Set<String> PHRASE_ONLY=Set.of("you","know","sort","kind","of","i","mean","guess","let's","see","let","me");

    /** The words of a text in lowercase, without punctuation. Apostrophes stay. */
    static List<String> words(String text){
        List<String> list=new ArrayList<>();
        for(String w:text.toLowerCase(Locale.ROOT).replace('’','\'').split("[^a-z0-9']+")){
            w=w.replaceAll("^'+|'+$","");
            if(!w.isEmpty())list.add(w);
        }
        return list;
    }

    public static boolean accept(String input,String output){
        if(output==null)return false;
        List<String> in=words(input),out=words(output);
        if(in.isEmpty())return out.isEmpty();
        if(out.isEmpty())return false;
        boolean[] kept=align(in,out);
        if(kept==null)return false;
        // Filler words do not count toward the limit, so "um, you know" does not use it up.
        int deleted=0,words=0;
        for(int i=0;i<in.size();i++)if(!FILLERS.contains(in.get(i))){words++;if(!kept[i])deleted++;}
        if(deleted*10>words*6)return false;
        for(int i=0;i<in.size();){
            if(kept[i]){i++;continue;}
            int end=i;
            while(end<in.size()&&!kept[end])end++;
            if(!allowed(in,i,end))return false;
            i=end;
        }
        return true;
    }

    /** Marks the input words that the output keeps, or returns null when the output is not a subsequence of the input. */
    private static boolean[] align(List<String> in,List<String> out){
        int n=in.size(),m=out.size();
        // lcs[i][j] is the longest common subsequence of in[i..] and out[j..].
        int[][] lcs=new int[n+1][m+1];
        for(int i=n-1;i>=0;i--)for(int j=m-1;j>=0;j--)
            lcs[i][j]=in.get(i).equals(out.get(j))?lcs[i+1][j+1]+1:Math.max(lcs[i+1][j],lcs[i][j+1]);
        if(lcs[0][0]<m)return null;
        boolean[] kept=new boolean[n];
        int i=0,j=0;
        // Prefer deleting earlier words, so a correction keeps the later version.
        while(i<n&&j<m){
            if(in.get(i).equals(out.get(j))&&lcs[i][j]==lcs[i+1][j+1]+1&&lcs[i+1][j]<lcs[i][j]){kept[i]=true;i++;j++;}
            else i++;
        }
        return kept;
    }

    private static boolean allowed(List<String> in,int start,int end){
        // Filler words only, where a phrase word is not deleted on its own.
        boolean fillers=true;
        for(int k=start;k<end&&fillers;k++)fillers=FILLERS.contains(in.get(k));
        if(fillers&&!(end-start==1&&PHRASE_ONLY.contains(in.get(start))))return true;
        // A repeat of the words that follow: "i i think" or "we should we should".
        int length=end-start;
        if(end+length<=in.size()){
            boolean repeat=true;
            for(int k=0;k<length&&repeat;k++)repeat=in.get(start+k).equals(in.get(end+k));
            if(repeat)return true;
        }
        // One meaningful word on its own, as in "I actually like it" or "no, I don't".
        if(length==1&&SINGLE_KEEP.contains(in.get(start)))return false;
        // A run that contains a correction phrase word, or ends right before one.
        for(int k=start;k<end;k++)if(CUES.contains(in.get(k)))return true;
        return end<in.size()&&CUES.contains(in.get(end));
    }
}
