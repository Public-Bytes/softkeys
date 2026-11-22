import dev.softkeys.ios.DictationCleanup;
import java.util.List;

/**
 * Offline checks for DictationCleanup. It needs only a JDK.
 *
 *   mkdir -p build/cleanup-test
 *   javac -d build/cleanup-test tests/DictationCleanupTest.java app/src/main/java/dev/softkeys/ios/DictationCleanup.java
 *   java -cp build/cleanup-test DictationCleanupTest
 */
public final class DictationCleanupTest {
    private static int failures,passes;

    public static void main(String[] args){
        // Hesitation sounds and the pauses around them.
        check("um i think we should go","I think we should go");
        check("so um we uh need to leave","So we need to leave");
        check("Um, I think so.","I think so.");
        check("So, um, I think so","So, I think so");
        check("it was, um, huge","It was huge");
        check("I think, um.","I think.");
        check("hmm let me see","Let me see");
        check("uh oh the build broke","Uh oh the build broke");
        check("errr is not removed but er is","Errr is not removed but is");

        // "you know" and "like" only between pauses.
        check("So, you know, it works.","So, it works.");
        check("It was fun, you know.","It was fun.");
        check("do you know the time","Do you know the time");
        check("I like cats","I like cats");
        check("it was, like, huge","It was huge");
        check("I would like to go","I would like to go");

        // Stutters and repeated words.
        check("i i think the the plan works","I think the plan works");
        check("we we we need to go","We need to go");
        check("I think I think it's fine","I think it's fine");
        check("that that is the question","That that is the question");
        check("it is very very good","It is very very good");
        check("call five five five one two one two","Call five five five one two one two");
        check("th- the plan","The plan");
        check("no no no","No no no");

        // Spoken punctuation.
        check("hello comma how are you question mark","Hello, how are you?");
        check("it works period new line thanks","It works.\nThanks");
        check("first item new paragraph second item","First item\n\nSecond item");
        check("wow exclamation point","Wow!");
        check("the period of time was short","The period of time was short");
        check("after a short period we left period","After a short period we left.");
        check("note colon bring snacks","Note: bring snacks");
        check("that is all full stop","That is all.");
        check("comma at the start","At the start");
        check("one comma two comma three","One, two, three");
        check("done period period","Done.");
        check("ok comma period","Ok.");
        checkWith("hello comma world",true,false,"","Hello comma world");

        // Capitalization and spacing.
        check("i'm sure i'll be there","I'm sure I'll be there");
        check("this is it. and this is more","This is it. And this is more");
        check("WHAT IS THE TIME","What is the time");
        check("  spaced   out  words ","Spaced out words");
        check("hello , world .","Hello, world.");
        checkWith("and then we left",false,true,"","and then we left");
        checkWith("um and then we left",false,true,"","and then we left");

        // Punctuated, capitalized text from the High accuracy model.
        check("Um, so I think, uh, we should go.","So I think we should go.");
        check("I I think the plan works. Thanks.","I think the plan works. Thanks.");
        check("Hello comma how are you question mark","Hello, how are you?");
        checkWith("The meeting moved to Friday.",false,true,"","the meeting moved to Friday.");
        checkWith("So we left early.",false,true,"","so we left early.");
        checkWith("I think so.",false,true,"","I think so.");
        checkWith("I'm late.",false,true,"","I'm late.");
        checkWith("iPhone sales rose.",false,true,"","iPhone sales rose.");
        checkWith("NASA launched it.",false,true,"","NASA launched it.");

        // Verbatim keeps every word and fixes only spacing and capitals.
        verbatim("um i think i i should go comma ok",true,"Um I think I I should go comma ok");
        verbatim("Um, I think, uh, we should go.",true,"Um, I think, uh, we should go.");
        verbatim("hello , world . and more",true,"Hello, world. And more");
        verbatim("The plan works.",false,"the plan works.");
        verbatim("soft keys is basically fine",true,"Soft keys is basically fine");

        // Word replacements.
        String rules="# my words\nsoft keys => SoftKeys\nI phone -> iPhone\ngonna = going to\nbasically =>\nbad line\n= nothing\n";
        List<String[]> parsed=DictationCleanup.parseReplacements(rules);
        expect("parse count",String.valueOf(parsed.size()),"4");
        checkWith("i use soft keys on my i phone",true,true,rules,"I use SoftKeys on my iPhone");
        checkWith("i phone works",true,true,rules,"iPhone works");
        checkWith("we're gonna win basically",true,true,rules,"We're going to win");
        checkWith("SOFT KEYS",true,true,rules,"SoftKeys");

        // Preview is lighter: no phrase fillers, single-line.
        DictationCleanup.Options options=new DictationCleanup.Options();
        expect("preview","um hello comma, you know, new line world",
            DictationCleanup.preview("um hello comma, you know, new line world",options),"Hello, you know World");
        expect("empty","",DictationCleanup.clean("",options),"");
        expect("only fillers","um uh",DictationCleanup.clean("um uh",options),"");
        expect("null","null",DictationCleanup.clean(null,options),"");

        System.out.println(passes+" passed, "+failures+" failed");
        if(failures>0)System.exit(1);
    }

    private static void check(String input,String expected){checkWith(input,true,true,"",expected);}
    private static void verbatim(String input,boolean startsSentence,String expected){
        DictationCleanup.Options options=new DictationCleanup.Options();
        options.startsSentence=startsSentence;
        options.replacements=DictationCleanup.parseReplacements("soft keys => SoftKeys\nbasically =>");
        expect("verbatim: "+input,input,DictationCleanup.verbatim(input,options),expected);
    }
    private static void checkWith(String input,boolean startsSentence,boolean spokenPunctuation,String rules,String expected){
        DictationCleanup.Options options=new DictationCleanup.Options();
        options.startsSentence=startsSentence;
        options.spokenPunctuation=spokenPunctuation;
        options.replacements=DictationCleanup.parseReplacements(rules);
        expect(input,input,DictationCleanup.clean(input,options),expected);
    }
    private static void expect(String name,String actual,String expected){expect(name,name,actual,expected);}
    private static void expect(String name,String input,String actual,String expected){
        if(actual.equals(expected)){passes++;return;}
        failures++;
        System.out.println("FAIL "+name.replace("\n","\\n")+"\n  input:    "+input.replace("\n","\\n")+"\n  expected: "+expected.replace("\n","\\n")+"\n  actual:   "+actual.replace("\n","\\n"));
    }
}
