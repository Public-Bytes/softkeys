package dev.softkeys.ios;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        /** Pairs of {spoken phrase, written text}, from {@link #parseReplacements}. */
        public List<String[]> replacements=new ArrayList<>();
    }

    private DictationCleanup(){}

    private static final String NEWLINE="\n",PARAGRAPH="\n\n";
    // Hesitation sounds that are never words in dictated English.
    private static final Pattern SOUND=Pattern.compile("u+m+|u+h+|uhm+|e+r+m+|er|h+m+|m{2,}");
    // A word cut off by the speaker, such as "th-" before "the".
    private static final Pattern FRAGMENT=Pattern.compile("[a-z]{1,3}-");
    private static final String NUMBER_WORDS="zero oh one two three four five six seven eight nine ten eleven twelve hundred thousand";
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

    private static String run(String raw,Options options,boolean light,boolean verbatim){
        if(raw==null)return "";
        String text=raw.trim();
        if(text.isEmpty())return "";
        // Some models write only capital letters. Lowercase them so capitalization can be rebuilt.
        if(text.chars().anyMatch(Character::isLetter)&&text.chars().noneMatch(Character::isLowerCase))text=text.toLowerCase(Locale.ROOT);
        List<String> tokens=tokenize(text);
        if(!verbatim){
            if(options.spokenPunctuation)spokenPunctuation(tokens);
            removeFillers(tokens,light);
            collapseRepeats(tokens,light);
            replace(tokens,options.replacements);
        }
        return join(tokens,options.startsSentence);
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
                for(int k=0;k<spoken.length;k++)tokens.remove(i);
                if(!rule[1].isEmpty()){tokens.add(i,rule[1]);i++;}
                replaced=true;break;
            }
            if(!replaced)i++;
        }
    }

    private static int strength(String mark){
        switch(mark){case ",":return 1;case ";":return 2;case ":":return 3;case ".":return 4;case "?":case "!":return 5;default:return 6;}
    }

    /** Joins the tokens with standard spacing and capitalizes the start of each sentence. */
    private static String join(List<String> tokens,boolean startsSentence){
        StringBuilder out=new StringBuilder();
        String last=null;
        boolean capital=startsSentence;
        for(String token:tokens){
            if(punctuation(token)){
                if(last==null)continue;
                if(newline(token)){
                    while(out.length()>0&&out.charAt(out.length()-1)==' ')out.setLength(out.length()-1);
                    if(last.equals(",")||last.equals(";")||last.equals(":"))out.setLength(out.length()-1);
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
            if(capital){word=capitalize(word);capital=false;}
            // Models that write capitals start every recording with one. In the middle of a
            // sentence, an ordinary capitalized first word goes back to lowercase.
            else if(last==null&&!startsSentence)word=decapitalize(word);
            if(last!=null&&!newline(last))out.append(' ');
            out.append(word);last=word;
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
