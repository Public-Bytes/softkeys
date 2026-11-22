package dev.softkeys.ios;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Cleans up on-device dictation text before SoftKeys inserts it.
 *
 * The steps run in this order: spoken punctuation, filler removal, repeated words,
 * the user's word replacements, and then spacing and capitalization. The class uses
 * no Android classes, so tests/DictationCleanupTest.java can run it on a plain JDK.
 */
public final class DictationCleanup {
    /** Settings for one dictation session. */
    public static final class Options {
        /** Turn spoken words such as "comma" and "new line" into punctuation. */
        public boolean spokenPunctuation=true;
        /** Capitalize the first word because the cursor is at the start of a sentence. */
        public boolean startsSentence=true;
        /**
         * The keyboard's Automatic capitalization setting. When it is off, sentences are not
         * capitalized, and a common word that the model capitalized at the start of a
         * sentence goes back to lowercase. "I", names, and acronyms keep their capitals.
         */
        public boolean capitalize=true;
        /** Turn "first ... second ..." and "bullet ... bullet ..." into a list, one item per line. */
        public boolean formatLists=true;
        /** Keep the period at the end of the text. When false, a final period is dropped, and ? and ! stay. */
        public boolean endWithPeriod=true;
        /** Write everything in lowercase letters. */
        public boolean lowercase=false;
        /** Pairs of {spoken phrase, written text}, from {@link #parseReplacements}. */
        public List<String[]> replacements=new ArrayList<>();
        /**
         * Words the user wants spelled exactly, from {@link #parseVocabulary}. A transcribed word
         * that sounds like one of them, and is not a known English word, is replaced with it.
         */
        public List<String> vocabulary=new ArrayList<>();
        /** True for known English words, which are never respelled. Sound matching is off when this is null. */
        public Predicate<String> knownWord;
        /**
         * True for common words that are normally written in lowercase, unlike "Monday" or a name.
         * At a seam, only such words lose a capital. Seams keep their capitals when this is null.
         */
        public Predicate<String> lowercaseWord;
    }

    private DictationCleanup(){}

    /**
     * Marks the place where two separately decoded parts of a long recording were joined. The
     * model starts every part with a capital, even in the middle of a sentence. The cleanup
     * uses the mark to fix that and then removes it. It is the invisible separator character.
     */
    public static final String SEAM="\u2063";

    private static final String NEWLINE="\n",PARAGRAPH="\n\n";
    // Hesitation sounds that are never words in dictated English.
    private static final Pattern SOUND=Pattern.compile("u+m+|u+h+|uhm+|e+r+m+|er|h+m+|m{2,}");
    // A word cut off by the speaker, such as "th-" before "the".
    private static final Pattern FRAGMENT=Pattern.compile("[a-z]{1,3}-");
    private static final String NUMBER_WORDS="zero oh one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen "+
        "eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred thousand million billion";
    private static final Set<String> NUMBERS=words(NUMBER_WORDS);
    // Words that people repeat on purpose, so a doubled copy is kept.
    private static final Set<String> REPEATABLE=words("that had is very really so no yes yeah bye ha haha hey now well ok okay "+
        "many much more far again too knock come go chop pretty long tut hush night ho la da dum blah bla tsk boo cha choo "+
        "double triple "+NUMBER_WORDS);
    // Words before "period", "comma", or "colon" that show the speaker means the noun.
    private static final Set<String> BEFORE_NOUN=words("a an the this that each every any my your his her our their its "+
        "grace trial waiting cooling time same first second third last next whole entire short long given");

    private static Set<String> words(String list){Set<String> set=new HashSet<>();for(String word:list.split(" "))set.add(word);return set;}

    /**
     * Reads the replacement list, one rule per line, written as "spoken = written",
     * "spoken -> written", or "spoken => written". Blank lines and lines starting with #
     * are skipped. An empty written side removes the spoken phrase.
     */
    public static List<String[]> parseReplacements(String text){
        List<String[]> rules=new ArrayList<>();
        if(text==null)return rules;
        for(String line:text.split("\\r?\\n")){
            line=line.trim();
            if(line.isEmpty()||line.startsWith("#"))continue;
            int at=-1,length=0;
            for(String arrow:new String[]{"=>","->","="}){int index=line.indexOf(arrow);if(index>0){at=index;length=arrow.length();break;}}
            if(at<0)continue;
            String spoken=line.substring(0,at).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
            String written=line.substring(at+length).trim();
            if(!spoken.isEmpty())rules.add(new String[]{spoken,written});
        }
        return rules;
    }

    /**
     * Reads the words the user wants spelled exactly. They are the written side of each
     * one-word rule, such as "Kaczmarek" in "kachmarek => Kaczmarek", and any line that holds a
     * single word with no arrow.
     */
    public static List<String> parseVocabulary(String text){
        List<String> words=new ArrayList<>();
        if(text==null)return words;
        Set<String> seen=new HashSet<>();
        for(String line:text.split("\\r?\\n")){
            line=line.trim();
            if(line.isEmpty()||line.startsWith("#"))continue;
            String word=line;
            for(String arrow:new String[]{"=>","->","="}){int index=line.indexOf(arrow);if(index>0){word=line.substring(index+arrow.length()).trim();break;}}
            if(word.length()>=4&&word.chars().allMatch(Character::isLetter)&&seen.add(lower(word)))words.add(word);
        }
        return words;
    }

    private static final Pattern SPOKEN_MARK=Pattern.compile("(?i)\\b(comma|period|question mark|exclamation (mark|point)|full stop|colon|semicolon|new ?line|next line|new paragraph)\\b");

    /** True when the text has a spoken punctuation word such as "comma" or "new line". */
    public static boolean hasSpokenPunctuation(String text){return SPOKEN_MARK.matcher(text).find();}

    /**
     * The input for the smart cleanup model: fillers, repeats, seams, and the corrections the
     * rules can find are removed, and the rest is written as lowercase words with no
     * punctuation, which is the form the model was trained on.
     */
    public static String prepareForEditor(String raw,Options options){
        if(raw==null)return "";
        List<String> tokens=tokenize(raw.trim());
        seams(tokens,null);
        removeFillers(tokens,false);
        corrections(tokens,options.lowercaseWord);
        collapseRepeats(tokens,false);
        StringBuilder out=new StringBuilder();
        for(String token:tokens){
            if(punctuation(token))continue;
            if(out.length()>0)out.append(' ');
            out.append(lower(token).replace('’','\''));
        }
        return out.toString();
    }

    /** Full cleanup for the text that will be inserted. */
    public static String clean(String raw,Options options){return run(raw,options,false,false);}

    /**
     * Verbatim style. The words stay as the model wrote them, with no filler removal, repeat
     * removal, spoken punctuation, or replacements. Only spacing around punctuation and
     * capital letters at the start of sentences and for "I" are fixed.
     */
    public static String verbatim(String raw,Options options){return run(raw,options,false,true);}

    /**
     * Lighter cleanup for the live preview. It skips phrase fillers and repeated phrases,
     * because the last words of a partial result can still change. New lines show as spaces.
     */
    public static String preview(String raw,Options options){return run(raw,options,true,false).replace('\n',' ').replaceAll(" {2,}"," ").trim();}

    /**
     * Spoken forms for the custom vocabulary. Each entry fixes its own capitals, so "testflight"
     * becomes "TestFlight", and a word written with inner capitals is also matched when spoken
     * as separate words, so "swift ui" becomes "SwiftUI" and "open code" becomes "OpenCode".
     * Returns pairs of {spoken phrase, written text}, like {@link #parseReplacements}.
     */
    public static List<String[]> vocabularyRules(List<String> vocabulary){
        List<String[]> rules=new ArrayList<>();
        if(vocabulary==null)return rules;
        for(String entry:vocabulary){
            String written=entry.trim().replaceAll("\\s+"," ");
            if(written.isEmpty())continue;
            String lower=written.toLowerCase(Locale.ROOT);
            if(!lower.equals(written))rules.add(new String[]{lower,written});
            // "SwiftUI" is spoken "swift ui", and "OpenCode" is spoken "open code".
            String split=written.replaceAll("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"," ").toLowerCase(Locale.ROOT);
            if(!split.equals(lower))rules.add(new String[]{split,written});
        }
        return rules;
    }

    private static String run(String raw,Options options,boolean light,boolean verbatim){
        if(raw==null)return "";
        String text=raw.trim();
        if(text.isEmpty())return "";
        // Some models write only capital letters. Lowercase them so capitalization can be rebuilt.
        if(text.chars().anyMatch(Character::isLetter)&&text.chars().noneMatch(Character::isLowerCase))text=text.toLowerCase(Locale.ROOT);
        List<String> tokens=tokenize(text);
        seams(tokens,options.lowercaseWord);
        if(!verbatim){
            if(options.spokenPunctuation)spokenPunctuation(tokens);
            removeFillers(tokens,light);
            // A partial result can still change, so the live preview never takes words back.
            if(!light)corrections(tokens,options.lowercaseWord);
            collapseRepeats(tokens,light);
            replace(tokens,options.replacements);
            soundAlike(tokens,options.vocabulary,options.knownWord);
            webAddresses(tokens);
            if(options.formatLists&&!light)lists(tokens);
            DictationNumbers.apply(tokens);
        }
        String out=join(tokens,options.startsSentence,options.capitalize,options.lowercaseWord);
        if(!options.endWithPeriod&&out.endsWith(".")&&!out.endsWith(".."))out=out.substring(0,out.length()-1);
        if(options.lowercase)out=out.toLowerCase(Locale.ROOT);
        return out;
    }

    /**
     * Removes the seam marks. When the part before a seam does not end a sentence, the first
     * word after it loses its capital if it is a word normally written in lowercase, so "with
     * What features" becomes "with what features". "I", names, and unknown words keep theirs.
     */
    private static void seams(List<String> tokens,Predicate<String> lowercaseWord){
        for(int i=0;i<tokens.size();){
            if(!tokens.get(i).equals(SEAM)){i++;continue;}
            tokens.remove(i);
            if(lowercaseWord==null||i>=tokens.size()||i==0||sentenceEnd(tokens,i-1)||tokens.get(i-1).equals(SEAM))continue;
            String word=tokens.get(i);
            String lower=lower(word).replace('’','\'');
            if(lower.equals("i")||lower.startsWith("i'"))continue;
            if(!word.equals(decapitalize(word))&&lowercaseWord.test(lower))tokens.set(i,decapitalize(word));
        }
    }

    // Phrases that take back what came before them. A strong phrase acts even when the
    // correction shares no words with the earlier text. A paused phrase, such as "make that",
    // is also ordinary English ("make that call"), so it must follow a comma or sentence end,
    // and it acts only when the correction matches earlier text. A weak phrase, such as
    // "I mean", is common in ordinary speech, so it acts only when the correction repeats at
    // least two earlier words. A delete phrase removes the sentence it ends.
    private static final String[][] STRONG={{"actually","wait"},{"wait","actually"},{"wait","sorry"},{"no","wait"},{"wait","no"},
        {"actually","no"},{"no","actually"},{"oh","wait"},{"no","sorry"},{"sorry","no"}};
    private static final String[][] PAUSED={{"let","me","correct","that"},{"come","to","think","of","it"},{"on","second","thoughts"},
        {"on","second","thought"},{"make","that"},{"i","meant"},{"never","mind"},{"nevermind"},{"ignore","that"},{"my","mistake"},
        {"correction"}};
    // A lone "wait", the words the model writes when it mishears it, and a lone "actually",
    // "sorry", or "rather" after a pause are weak phrases too.
    private static final String[][] WEAK={{"sorry","i","mean"},{"i","mean"},{"or","rather"},{"wait"},{"weight"},{"wheat"},{"weigh"},
        {"actually"},{"sorry"},{"rather"}};
    private static final Set<String> AFTER_PAUSE=words("actually sorry rather");
    private static final Set<String> LEADING=words("sorry actually wait no oh okay ok");
    private static final String[][] DELETE={{"scratch","that"},{"strike","that"},{"delete","that"}};
    private static final Set<String> SMALL_WORDS=words("the a an to and i it is of in on at he she we you they that this so but or for with my your");

    private static int trailing(List<String> tokens,int i){int n=0;while(i+n<tokens.size()&&punctuation(tokens.get(i+n))&&!newline(tokens.get(i+n)))n++;return n;}

    /** The index after a phrase that starts at i, with punctuation between and after its words, or -1. */
    private static int phrase(List<String> tokens,int i,String[] words){
        int j=i;
        for(String w:words){
            while(j<tokens.size()&&punctuation(tokens.get(j))&&j>i)j++;
            if(!word(tokens,j,w))return -1;
            j++;
        }
        // The model may end a sentence at the phrase, as in "more than wheat. I like coffee".
        while(j<tokens.size()&&punctuation(tokens.get(j))&&!newline(tokens.get(j)))j++;
        return j;
    }

    /**
     * Takes back what the speaker corrected. "there are two things, actually wait, there are
     * three things" keeps only the second version. First, it finds a correction phrase. Second,
     * it reads the words after the phrase and looks back through the current and previous
     * sentence for the latest place where the same words started. Third, it deletes from that
     * place through the phrase. When no earlier words match, a strong phrase followed by up to
     * three words replaces the same number of words before it, as in "at five, no wait, six".
     */
    private static void corrections(List<String> tokens,Predicate<String> lowercaseWord){
        for(int i=1;i<tokens.size();i++){
            if(punctuation(tokens.get(i)))continue;
            int end=-1,kind=0;
            for(String[] cue:DELETE)if(end<0&&(end=phrase(tokens,i,cue))>=0)kind=3;
            for(String[] cue:STRONG)if(end<0&&(end=phrase(tokens,i,cue))>=0)kind=2;
            for(String[] cue:PAUSED)if(end<0&&(end=phrase(tokens,i,cue))>=0)kind=4;
            for(String[] cue:WEAK)if(end<0&&(end=phrase(tokens,i,cue))>=0)kind=1;
            if(end<0)continue;
            // A phrase often follows another, as in "February, sorry, make that May". The earlier
            // words become part of the phrase.
            int cue=i;
            while(cue>=2){
                int k=cue-1;
                while(k>0&&tokens.get(k).equals(","))k--;
                if(!LEADING.contains(lower(tokens.get(k))))break;
                cue=k;
            }
            if(cue==0)continue;
            // "I actually like it" and "I'd rather go" are not corrections, so these words must follow a pause.
            boolean pause=punctuation(tokens.get(cue-1));
            if(kind==4&&!pause)continue;
            if(kind==1&&end==i+1+trailing(tokens,i+1)&&AFTER_PAUSE.contains(lower(tokens.get(i)))&&!pause)continue;
            int before=cue-1;
            while(before>=0&&tokens.get(before).equals(","))before--;
            if(before<0)continue;
            int start;
            // The model may end the sentence before the phrase: "forty chairs. Correction, nine chairs."
            if(kind!=3)while(before>=0&&punctuation(tokens.get(before))&&!newline(tokens.get(before)))before--;
            if(before<0)continue;
            if(kind==3){
                // Delete the sentence the phrase ends, or the one before it when the phrase starts a sentence.
                int from=sentenceEnd(tokens,before)?before-1:before;
                start=from+1;
                while(start>0&&!sentenceEnd(tokens,start-1))start--;
                if(end<tokens.size()&&sentenceEnd(tokens,end)&&!newline(tokens.get(end)))end++;
            }else{
                List<String> said=new ArrayList<>();
                for(int j=end;j<tokens.size()&&!punctuation(tokens.get(j));j++)said.add(lower(tokens.get(j)));
                if(said.isEmpty())continue;
                start=clause(tokens,before,said);
                if(start<0)start=anchor(tokens,before,said,kind==1?2:1);
                // The last few words before the phrase start with the same word as the correction:
                // "the red one, I mean the blue one" or "the session, sorry, the lab and I'll update it".
                for(int n=Math.min(4,said.size());start<0&&n>=2;n--){
                    int p=before-(n-1);
                    boolean same=p>=0&&lower(tokens.get(p)).equals(said.get(0));
                    for(int k=p;same&&k<=before;k++)same=!punctuation(tokens.get(k));
                    if(same)start=p;
                }
                // One word for another, with the sentence going on after it: "on Tuesday, on second
                // thought, Wednesday in the meantime". Only after a strong or paused phrase.
                if(start<0&&kind!=1&&said.size()>1&&!SMALL_WORDS.contains(said.get(0))&&!SMALL_WORDS.contains(lower(tokens.get(before)))
                    &&!NUMBERS.contains(said.get(0))&&sameCase(tokens.get(before),tokens.get(end)))start=before;
                if(start<0){
                    if(kind==1)continue;
                    start=cue;
                    int count=said.size();
                    // "at five, no wait, six o'clock" replaces only the number.
                    if(NUMBERS.contains(lower(tokens.get(before)))&&NUMBERS.contains(said.get(0))){
                        count=0;
                        while(before-count>=0&&NUMBERS.contains(lower(tokens.get(before-count))))count++;
                    }
                    if(count<=3){
                        int k=before,words=0;
                        while(k>=0&&!punctuation(tokens.get(k))&&words<count){k--;words++;}
                        if(words==count)start=k+1;
                    }
                    // Only a strong phrase is removed on its own when nothing before it matches.
                    if(start==cue&&kind==4)continue;
                }
            }
            for(int k=start;k<end;k++)tokens.remove(start);
            if(start<tokens.size()&&start>0&&!sentenceEnd(tokens,start-1)){
                String word=tokens.get(start);
                if(lowercaseWord!=null&&!word.equals(decapitalize(word))&&lowercaseWord.test(lower(word)))tokens.set(start,decapitalize(word));
            }
            i=Math.max(0,start-1);
        }
    }

    /**
     * A correction that ends with the same word as the phrase before it replaces that phrase,
     * as in "five hundred dollars, actually four hundred fifty dollars". It replaces as many
     * words as the correction has, but never goes back past a comma or the end of a sentence.
     * The correction may have up to 8 words. Returns the start of the replaced words, or -1.
     */
    private static int clause(List<String> tokens,int last,List<String> said){
        if(said.size()>8||last<0||punctuation(tokens.get(last))||!lower(tokens.get(last)).equals(said.get(said.size()-1)))return -1;
        int start=last;
        while(start>0&&!punctuation(tokens.get(start-1))&&last-start+1<said.size())start--;
        return start;
    }

    /** True when both words start with a capital, as "Tuesday" and "Wednesday" do, or neither does. */
    private static boolean sameCase(String a,String b){return Character.isUpperCase(a.charAt(0))==Character.isUpperCase(b.charAt(0));}

    /** The latest index at or before last where the words of said start, matching at least min words, or -1. */
    private static int anchor(List<String> tokens,int last,List<String> said,int min){
        // Search back to the start of the sentence before the current one.
        int lowest=last,ends=0;
        while(lowest>0){
            if(sentenceEnd(tokens,lowest-1)&&++ends==2)break;
            lowest--;
        }
        int best=-1,bestLength=0;
        for(int p=last;p>=Math.max(0,lowest);p--){
            int k=0;
            while(k<said.size()&&p+k<=last&&!punctuation(tokens.get(p+k))&&lower(tokens.get(p+k)).equals(said.get(k)))k++;
            if(k>bestLength){best=p;bestLength=k;}
        }
        if(bestLength<min||bestLength==1&&SMALL_WORDS.contains(said.get(0)))return -1;
        return best;
    }

    private static final String[] ORDINALS={"first","second","third","fourth","fifth","sixth","seventh","eighth","ninth","tenth"};
    private static final String[] NUMBERED={"one","two","three","four","five","six","seven","eight","nine","ten"};

    /** True when a list marker at i starts a clause: after punctuation, at the start, or after "and" or "then" that does. */
    private static boolean clauseStart(List<String> tokens,int i){
        if(i==0||punctuation(tokens.get(i-1)))return true;
        String previous=lower(tokens.get(i-1));
        return (previous.equals("and")||previous.equals("then"))&&(i==1||punctuation(tokens.get(i-2)));
    }

    /** The length of the list marker for item n (0 based) at i, or 0. */
    private static int marker(List<String> tokens,int i,int n,int style){
        String w=lower(tokens.get(i));
        if(style==3){
            // Numbers, which the speech model often writes as digits: "the plan 1. We pack the
            // car. 2 we drive home." A spoken number word needs a pause after it,
            // so "One day we left. Two weeks later" stays as it is.
            boolean digit=w.equals(String.valueOf(n+1));
            boolean word=w.equals(NUMBERED[n]);
            if(!digit&&!word)return 0;
            boolean pause=i+1<tokens.size()&&(tokens.get(i+1).equals(".")||tokens.get(i+1).equals(",")||tokens.get(i+1).equals(":"));
            if(word&&!pause)return 0;
            if(n>0&&!clauseStart(tokens,i))return 0;
            if(n==0&&!clauseStart(tokens,i)&&!pause)return 0;
            return pause?2:1;
        }
        // "bullet" is rare enough in speech that it may start an item anywhere.
        if(style<2&&!clauseStart(tokens,i))return 0;
        if(style==0)return w.equals(ORDINALS[n])||w.equals(ORDINALS[n]+"ly")?1:0;
        // "number one" or, as the speech model often writes it, "number 1".
        if(style==1)return w.equals("number")&&(word(tokens,i+1,NUMBERED[n])||word(tokens,i+1,String.valueOf(n+1)))?2:0;
        if(!w.equals("bullet")&&!((w.equals("next")||w.equals("new"))&&word(tokens,i+1,"bullet")))return 0;
        int length=w.equals("bullet")?1:2;
        return word(tokens,i+length,"point")?length+1:length;
    }

    /**
     * Writes a spoken list with one item per line. "My list, first eggs, second milk, and third
     * bread." becomes "My list:" and the lines "1. Eggs", "2. Milk", and "3. Bread". Saying
     * "number one", "number two" works the same way, and "bullet" starts lines with "- ".
     * Numbers work too, as digits or as words followed by a pause: "1. We pack the car. 2
     * we drive home." A list needs at least two markers in order, each at the start of
     * a clause, so "at first we waited a second" stays as it is. The list ends at the end of
     * its last sentence, and items lose their final period.
     */
    private static void lists(List<String> tokens){
        for(int style=0;style<4;style++){
            List<int[]> found=new ArrayList<>();
            for(int i=0;i<tokens.size()&&found.size()<ORDINALS.length;i++){
                int n=style==2?0:found.size();
                // A repeated first marker before the second one restarts the list, as in
                // "First, some background. First, we pack. Second, we drive.".
                if(style!=2&&found.size()==1){
                    int again=marker(tokens,i,0,style);
                    if(again>0&&i>found.get(0)[0]){found.set(0,new int[]{i,again});i+=again-1;continue;}
                }
                int length=marker(tokens,i,n,style);
                // Every item needs words, so "one, two, three" counting is not a list.
                if(length>0&&!found.isEmpty()&&found.get(found.size()-1)[0]+found.get(found.size()-1)[1]>=i)length=0;
                if(length>0){found.add(new int[]{i,length});i+=length-1;}
            }
            if(found.size()<2)continue;
            // A spoken "new line" or "new paragraph" between markers means the speaker chose the layout.
            boolean chosen=false;
            for(int i=found.get(0)[0];i<found.get(found.size()-1)[0];i++)chosen|=newline(tokens.get(i));
            if(chosen)continue;
            // The list ends at the first sentence end after the last marker.
            int end=found.get(found.size()-1)[0];
            while(end<tokens.size()&&!sentenceEnd(tokens,end))end++;
            for(int k=found.size()-1;k>=0;k--){
                int at=found.get(k)[0],length=found.get(k)[1];
                int stop=k+1<found.size()?found.get(k+1)[0]:end;
                // Drop "and"/"then" before the next marker and the commas and period around the item.
                int last=stop-1;
                while(last>at+length-1&&(tokens.get(last).equals(",")||tokens.get(last).equals(";")||tokens.get(last).equals(".")||lower(tokens.get(last)).equals("and")||lower(tokens.get(last)).equals("then")))last--;
                for(int j=stop-1;j>last;j--)tokens.remove(j);
                for(int j=0;j<length;j++)tokens.remove(at);
                while(at<tokens.size()&&(tokens.get(at).equals(",")||tokens.get(at).equals(".")||tokens.get(at).equals(":")))tokens.remove(at);
                if(k==found.size()-1){
                    // Drop the final period of the last item; keep ? and !.
                    int after=at;
                    while(after<tokens.size()&&!sentenceEnd(tokens,after))after++;
                    if(after<tokens.size()&&tokens.get(after).equals("."))tokens.remove(after);
                    if(after<tokens.size()&&!newline(tokens.get(after)))tokens.add(after,NEWLINE);
                }
                tokens.add(at,style==2?"-":(k+1)+".");
                tokens.add(at,NEWLINE);
            }
            // Introduce the list with a colon when the text before it has no closing mark.
            int first=found.get(0)[0];
            while(first>0&&(tokens.get(first-1).equals(",")))tokens.remove(--first);
            if(first>0&&!punctuation(tokens.get(first-1)))tokens.add(first,":");
            if(first==0)tokens.remove(0);
            return;
        }
    }

    private static final Set<String> DOMAINS=words("com org net io ai co edu gov dev app us uk ca me tv info biz xyz gg ly fm");
    private static final Set<String> BEFORE_EMAIL=words("email emailed e-mail address is to at contact reach");
    private static final Set<String> NOT_NAMES=words("a an the this that it it's its i me we us you he him she her they them");

    /**
     * Writes spoken web addresses as text: "google dot com" becomes "google.com" and
     * "www dot example dot co dot uk" becomes "www.example.co.uk". After a word such as "email"
     * or "is", "john at gmail dot com" becomes "john@gmail.com". "a dot com company" stays.
     */
    private static void webAddresses(List<String> tokens){
        for(int i=0;i<tokens.size();i++)if(word(tokens,i,"dotcom")){tokens.set(i,"com");tokens.add(i,"dot");}
        for(int i=0;i<tokens.size();i++){
            if(punctuation(tokens.get(i))||NOT_NAMES.contains(lower(tokens.get(i)))||word(tokens,i,"dot"))continue;
            int k=i+1;
            while(word(tokens,k,"dot")&&k+1<tokens.size()&&!punctuation(tokens.get(k+1)))k+=2;
            if(k==i+1||!DOMAINS.contains(lower(tokens.get(k-1))))continue;
            StringBuilder domain=new StringBuilder(lower(tokens.get(i)));
            for(int j=i+2;j<k;j+=2)domain.append('.').append(lower(tokens.get(j)));
            int start=i;
            if(i>=2&&word(tokens,i-1,"at")&&!punctuation(tokens.get(i-2))&&!NOT_NAMES.contains(lower(tokens.get(i-2)))
                &&(i==2||BEFORE_EMAIL.contains(lower(tokens.get(i-3)))||punctuation(tokens.get(i-3)))){
                start=i-2;domain.insert(0,lower(tokens.get(i-2))+"@");
            }
            for(int j=start;j<k;j++)tokens.remove(start);
            tokens.add(start,domain.toString());
            i=start;
        }
    }

    static boolean punctuation(String token){
        return token.equals(",")||token.equals(".")||token.equals("?")||token.equals("!")||token.equals(":")||token.equals(";")||newline(token);
    }
    private static boolean newline(String token){return token.equals(NEWLINE)||token.equals(PARAGRAPH);}
    private static String lower(String token){return token.toLowerCase(Locale.ROOT);}
    private static boolean word(List<String> tokens,int i,String value){return i>=0&&i<tokens.size()&&lower(tokens.get(i)).equals(value);}
    private static boolean boundary(List<String> tokens,int i){return i<0||i>=tokens.size()||punctuation(tokens.get(i));}
    private static boolean sentenceEnd(List<String> tokens,int i){
        if(i>=tokens.size())return true;
        String token=tokens.get(i);
        return token.equals(".")||token.equals("?")||token.equals("!")||newline(token);
    }

    /** Splits on spaces and separates punctuation at the end of a word. */
    private static List<String> tokenize(String text){
        List<String> tokens=new ArrayList<>();
        for(String piece:text.split("\\s+")){
            if(piece.isEmpty())continue;
            int end=piece.length();
            while(end>0&&",.?!:;".indexOf(piece.charAt(end-1))>=0)end--;
            if(end>0)tokens.add(piece.substring(0,end));
            for(int i=end;i<piece.length();i++)tokens.add(String.valueOf(piece.charAt(i)));
        }
        return tokens;
    }

    private static void spokenPunctuation(List<String> tokens){
        for(int i=0;i<tokens.size();i++){
            String token=lower(tokens.get(i));
            String previous=i>0?lower(tokens.get(i-1)):"";
            String mark=null;int length=1;
            if(token.equals("new")&&word(tokens,i+1,"paragraph")){mark=PARAGRAPH;length=2;}
            else if((token.equals("new")||token.equals("next"))&&word(tokens,i+1,"line")){mark=NEWLINE;length=2;}
            else if(token.equals("newline"))mark=NEWLINE;
            else if(token.equals("question")&&word(tokens,i+1,"mark")){mark="?";length=2;}
            else if(token.equals("exclamation")&&(word(tokens,i+1,"mark")||word(tokens,i+1,"point"))){mark="!";length=2;}
            else if(token.equals("full")&&word(tokens,i+1,"stop")){mark=".";length=2;}
            else if(token.equals("period")&&!BEFORE_NOUN.contains(previous)&&!word(tokens,i+1,"of"))mark=".";
            else if(token.equals("comma")&&!BEFORE_NOUN.contains(previous))mark=",";
            else if(token.equals("colon")&&!BEFORE_NOUN.contains(previous))mark=":";
            else if(token.equals("semicolon"))mark=";";
            if(mark==null)continue;
            for(int n=0;n<length;n++)tokens.remove(i);
            tokens.add(i,mark);
        }
    }

    private static void removeFillers(List<String> tokens,boolean light){
        for(int i=0;i<tokens.size();){
            String token=lower(tokens.get(i));
            boolean sound=SOUND.matcher(token).matches()&&!(token.equals("uh")&&word(tokens,i+1,"oh"));
            boolean fragment=FRAGMENT.matcher(token).matches()&&i+1<tokens.size()&&lower(tokens.get(i+1)).startsWith(token.substring(0,token.length()-1));
            if(fragment){tokens.remove(i);continue;}
            if(sound){drop(tokens,i,1);continue;}
            if(!light){
                int length=token.equals("you")&&word(tokens,i+1,"know")?2:token.equals("like")?1:0;
                // A phrase filler needs pauses around it: "So, you know, it works." or "It was fun, you know."
                if(length>0&&boundary(tokens,i-1)&&(word(tokens,i+length,",")||i>0&&tokens.get(i-1).equals(",")&&sentenceEnd(tokens,i+length))){
                    drop(tokens,i,length);
                    continue;
                }
            }
            i++;
        }
    }

    /**
     * Removes a filler and the pause around it. "So, um, I" keeps the comma after a one-word
     * opener, "It was, um, huge" loses both commas, and "Um, I" loses the comma at the start.
     */
    private static void drop(List<String> tokens,int i,int length){
        for(int n=0;n<length;n++)tokens.remove(i);
        boolean after=i<tokens.size()&&tokens.get(i).equals(",");
        boolean before=i>0&&tokens.get(i-1).equals(",");
        boolean start=i==0||punctuation(tokens.get(i-1))&&!before;
        if(after&&(before||start)){
            tokens.remove(i);
            if(before&&clauseWords(tokens,i-2)>=2)tokens.remove(i-1);
        }
    }
    private static int clauseWords(List<String> tokens,int last){
        int count=0;
        for(int i=last;i>=0&&!punctuation(tokens.get(i));i--)count++;
        return count;
    }

    private static void collapseRepeats(List<String> tokens,boolean light){
        for(int i=0;i<tokens.size();){
            int found=0;
            for(int n=light?1:3;n>=1&&found==0;n--){
                if(i+2*n>tokens.size())continue;
                boolean same=true;
                for(int k=0;k<n&&same;k++){
                    String a=tokens.get(i+k),b=tokens.get(i+n+k);
                    same=!punctuation(a)&&!punctuation(b)&&lower(a).equals(lower(b));
                    // Repeated digits are often a phone number or a code, so keep them.
                    if(n>1&&(NUMBERS.contains(lower(a))||a.chars().anyMatch(Character::isDigit)))same=false;
                }
                if(same&&(n>1||!REPEATABLE.contains(lower(tokens.get(i)))&&!tokens.get(i).chars().allMatch(Character::isDigit)))found=n;
            }
            if(found==0){i++;continue;}
            for(int k=0;k<found;k++)tokens.remove(i+found);
        }
    }

    private static void replace(List<String> tokens,List<String[]> rules){
        if(rules==null||rules.isEmpty())return;
        for(int i=0;i<tokens.size();){
            boolean replaced=false;
            for(String[] rule:rules){
                String[] spoken=rule[0].split(" ");
                if(i+spoken.length>tokens.size())continue;
                boolean match=true;
                for(int k=0;k<spoken.length&&match;k++)match=!punctuation(tokens.get(i+k))&&lower(tokens.get(i+k)).equals(spoken[k]);
                if(!match)continue;
                boolean capital=Character.isUpperCase(tokens.get(i).charAt(0));
                for(int k=0;k<spoken.length;k++)tokens.remove(i);
                if(!rule[1].isEmpty()){tokens.add(i,matchCapital(rule[1],capital));i++;}
                replaced=true;break;
            }
            if(!replaced)i++;
        }
    }

    /**
     * Replaces a word that sounds like a vocabulary word, such as "Kachmarek" or "Kazmarek" for
     * "Kaczmarek". Each word is reduced to a sound key, and a word matches when its key is within
     * one edit of the vocabulary word's key, or two edits for a key of 7 or more letters. Known
     * English words, and words shorter than 4 letters, are never changed.
     */
    private static void soundAlike(List<String> tokens,List<String> vocabulary,Predicate<String> knownWord){
        if(vocabulary==null||vocabulary.isEmpty()||knownWord==null)return;
        String[] keys=new String[vocabulary.size()];
        for(int v=0;v<keys.length;v++)keys[v]=soundKey(lower(vocabulary.get(v)));
        for(int i=0;i<tokens.size();i++){
            String token=tokens.get(i);
            if(token.length()<4||!token.chars().allMatch(Character::isLetter))continue;
            String plain=lower(token);
            if(knownWord.test(plain))continue;
            String key=soundKey(plain);
            int best=-1,bestDistance=Integer.MAX_VALUE;
            for(int v=0;v<keys.length;v++){
                if(lower(vocabulary.get(v)).equals(plain)){best=-1;break;}
                if(keys[v].length()<4||key.isEmpty()||key.charAt(0)!=keys[v].charAt(0))continue;
                int distance=editDistance(key,keys[v]);
                if(distance<=(keys[v].length()>=7?2:1)&&distance<bestDistance){best=v;bestDistance=distance;}
            }
            if(best>=0)tokens.set(i,matchCapital(vocabulary.get(best),Character.isUpperCase(token.charAt(0))));
        }
    }

    /**
     * A rough key for how an English speaker would say a word. Letters that make the same sound
     * get the same symbol: "x" becomes "ks", "ck" and "c" become "k", "z" becomes "s", "ch",
     * "sh", and a final "ge" become "J", and a "j" at the start becomes "y". Every vowel becomes
     * "a", silent letters are dropped, and repeated symbols are written once.
     */
    static String soundKey(String word){
        String s=word.replaceAll("[^a-z]","");
        if(s.isEmpty())return s;
        s=s.replaceAll("^wr","r").replaceAll("^kn","n").replaceAll("ge$","J");
        if(s.length()>3)s=s.replaceAll("([^aeiou])e$","$1");
        s=s.replace("tch","J").replace("dge","J").replace("ch","J").replace("sh","J").replace("zh","J").replace("dj","J");
        s=s.replace("ph","f").replace("ck","k").replace("x","ks").replace("q","k");
        s=s.replaceAll("c(?=[eiy])","s").replace('c','k').replace('z','s');
        s=s.replaceAll("^j","y").replace('j','J');
        s=s.replace("h","");
        s=s.replaceAll("(?<=[bdfgklmnprstvJ])w","");
        s=s.replaceAll("(?<=[aeiou])r(?=[^aeiouy]|$)","");
        if(s.isEmpty())return s;
        StringBuilder key=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if("aeiou".indexOf(c)>=0||c=='y'&&i>0)c='a';
            if(key.length()==0||key.charAt(key.length()-1)!=c)key.append(c);
        }
        return key.toString();
    }

    private static int editDistance(String a,String b){
        int[] row=new int[b.length()+1];
        for(int j=0;j<=b.length();j++)row[j]=j;
        for(int i=1;i<=a.length();i++){
            int diagonal=row[0];row[0]=i;
            for(int j=1;j<=b.length();j++){
                int above=row[j];
                row[j]=Math.min(Math.min(row[j]+1,row[j-1]+1),diagonal+(a.charAt(i-1)==b.charAt(j-1)?0:1));
                diagonal=above;
            }
        }
        return row[b.length()];
    }

    /** Capitalizes a lowercase replacement, such as "kaczmarek", when the word it replaces was capitalized. */
    private static String matchCapital(String written,boolean capital){
        if(!capital||written.isEmpty()||written.chars().anyMatch(Character::isUpperCase))return written;
        return Character.toUpperCase(written.charAt(0))+written.substring(1);
    }

    private static int strength(String mark){
        switch(mark){case ",":return 1;case ";":return 2;case ":":return 3;case ".":return 4;case "?":case "!":return 5;default:return 6;}
    }

    /** Joins the tokens with standard spacing and capitalizes the start of each sentence. */
    private static String join(List<String> tokens,boolean startsSentence,boolean capitalize,Predicate<String> lowercaseWord){
        StringBuilder out=new StringBuilder();
        String last=null;
        boolean capital=startsSentence;
        for(String token:tokens){
            if(punctuation(token)){
                if(last==null)continue;
                if(newline(token)){
                    while(out.length()>0&&out.charAt(out.length()-1)==' ')out.setLength(out.length()-1);
                    if(last.equals(",")||last.equals(";"))out.setLength(out.length()-1);
                    if(newline(last)){
                        if(last.length()>=token.length())continue;
                        out.setLength(out.length()-last.length());
                    }
                    out.append(token);last=token;capital=true;continue;
                }
                if(newline(last))continue;
                if(punctuation(last)){
                    if(strength(token)<strength(last))continue;
                    out.setLength(out.length()-1);
                }
                out.append(token);last=token;
                if(token.equals(".")||token.equals("?")||token.equals("!"))capital=true;
                continue;
            }
            String word=pronounI(token);
            // A list marker such as "1." or "-" passes the capital on to the item's first word.
            boolean listMarker=(last==null||newline(last))&&(word.equals("-")||word.matches("\\d+\\."));
            if(capital&&!capitalize){
                // The model's sentence capital, as in "The", becomes lowercase. Names stay.
                if(last!=null||startsSentence){
                    String lower=lower(word).replace('’','\'');
                    boolean pronoun=lower.equals("i")||lower.startsWith("i'");
                    if(!pronoun&&(lowercaseWord==null||lowercaseWord.test(lower)))word=decapitalize(word);
                }
                capital=false;
                if(last==null&&!startsSentence)word=decapitalize(word);
            }
            // Web and email addresses keep their lowercase letters.
            else if(capital){if(!word.contains(".")&&!word.contains("@"))word=capitalize(word);capital=false;}
            // Models that write capitals start every recording with one. In the middle of a
            // sentence, an ordinary capitalized first word goes back to lowercase.
            else if(last==null&&!startsSentence)word=decapitalize(word);
            if(last!=null&&!newline(last))out.append(' ');
            out.append(word);last=word;
            if(listMarker)capital=true;
        }
        return out.toString();
    }

    private static String pronounI(String token){
        String lower=lower(token).replace('’','\'');
        if(lower.equals("i")||lower.startsWith("i'")&&(lower.equals("i'm")||lower.equals("i've")||lower.equals("i'll")||lower.equals("i'd")))
            return "I"+token.substring(1);
        return token;
    }

    /** Lowercases a word such as "The" or "So", but not "I", "iPhone", "NASA", or "McDonald". */
    private static String decapitalize(String word){
        if(word.isEmpty()||!Character.isUpperCase(word.charAt(0))||word.substring(1).chars().anyMatch(Character::isUpperCase))return word;
        String lower=lower(word).replace('’','\'');
        if(lower.equals("i")||lower.startsWith("i'"))return word;
        return Character.toLowerCase(word.charAt(0))+word.substring(1);
    }

    /** Uppercases the first letter unless the word already has capitals, such as "iPhone". */
    private static String capitalize(String word){
        if(word.chars().anyMatch(Character::isUpperCase))return word;
        for(int i=0;i<word.length();i++){
            char c=word.charAt(i);
            if(Character.isLetter(c))return word.substring(0,i)+Character.toUpperCase(c)+word.substring(i+1);
            if(Character.isDigit(c))return word;
        }
        return word;
    }
}
