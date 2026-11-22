import dev.softkeys.ios.DictationCleanup;
import java.util.List;

/**
 * Offline checks for DictationCleanup. It needs only a JDK.
 *
 *   mkdir -p build/cleanup-test
 *   javac -d build/cleanup-test tests/DictationCleanupTest.java app/src/main/java/dev/softkeys/ios/DictationCleanup.java app/src/main/java/dev/softkeys/ios/DictationNumbers.java app/src/main/java/dev/softkeys/ios/EnglishDictionary.java
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
        check("call five five five one two one two","Call 555-1212");
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

        // Sound matching. One rule teaches the spelling, and other spellings that sound
        // the same are fixed. Known English words are never changed.
        String name="kachmarek => kaczmarek\nMcAllister\n";
        expect("vocabulary",String.join(",",DictationCleanup.parseVocabulary(name)),"kaczmarek,McAllister");
        sounds("Anna Kachmarek. Anna Katchmarek. Anna Kazmarek. Anna Kosmarek.",name,
            "Anna Kaczmarek. Anna Kaczmarek. Anna Kaczmarek. Anna Kaczmarek.");
        sounds("i met mack alister and mcalister",name,"I met mack alister and McAllister");
        sounds("i saw a camera and a market and the yacht sank",name,"I saw a camera and a market and the yacht sank");
        checkWith("Anna Kachmarek",true,true,name,"Anna Kaczmarek");

        // Seams between separately decoded parts of a long recording.
        String s=" "+DictationCleanup.SEAM+" ";
        seam("we picked a plan with"+s+"What features we need.","We picked a plan with what features we need.");
        seam("check back in"+s+"Four weeks, six weeks.","Check back in 4 weeks, 6 weeks.");
        seam("We tested it with Mercury"+s+"But I want you to try.","We tested it with Mercury but I want you to try.");
        seam("It works."+s+"What now?","It works. What now?");
        seam("we talked to"+s+"Claude and Grok","We talked to Claude and Grok");
        seam("we met on"+s+"Monday","We met on Monday");
        seam("and then"+s+"I left","And then I left");
        seam("and then"+s+"NASA called","And then NASA called");
        expect("seam without a dictionary",DictationCleanup.clean("going to"+s+"What",new DictationCleanup.Options()),"Going to What");
        expect("verbatim seam",DictationCleanup.verbatim("going to"+s+"What um",seamOptions()),"Going to what um");

        // Spoken numbers.
        check("The shelf is six foot two, maybe a hundred and twenty pounds of solid oak.","The shelf is 6'2\", maybe 120 pounds of solid oak.");
        check("One time there was this guy","One time there was this guy");
        check("there are two ways to get there","There are two ways to get there");
        check("the two of us left at one","The two of us left at one");
        check("in four weeks, six weeks, ten weeks","In 4 weeks, 6 weeks, 10 weeks");
        check("one day it rained for one hour","One day it rained for one hour");
        check("it costs twenty dollars and fifty cents","It costs $20.50");
        check("it costs five dollars","It costs $5");
        check("fifty percent off","50% off");
        check("about twenty five people came","About 25 people came");
        check("twenty-five people came","25 people came");
        check("it was nineteen ninety nine","It was 1999");
        check("in twenty twenty six we moved","In 2026 we moved");
        check("in two thousand twenty six","In 2026");
        check("two thousand five hundred dollars","$2,500");
        check("forty thousand people","40,000 people");
        check("two million people and a million dollars","2 million people and a million dollars");
        check("three point five million users","3.5 million users");
        check("it is three point five","It is 3.5");
        check("meet me at three thirty","Meet me at 3:30");
        check("meet at seven oh five pm","Meet at 7:05 pm");
        check("I have three fifteen year olds","I have three 15 year olds");
        check("call me at five five five one two three four five six seven","Call me at 555-123-4567");
        check("on the twenty first of May","On the 21st of May");
        check("the first and second time","The first and second time");
        check("one, two, three","One, two, three");
        check("I ran five miles","I ran 5 miles");

        // Web addresses.
        check("go to google dot com","Go to google.com");
        check("Visit Amazon dot com.","Visit amazon.com.");
        check("www dot example dot co dot uk","www.example.co.uk");
        check("it was a dot com company","It was a dot com company");
        check("my email is john at gmail dot com","My email is john@gmail.com");
        check("we met at google dot com","We met at google.com");
        check("check softkeys dotcom","Check softkeys.com");

        // Taking back what the speaker corrected.
        check("And she said, there are two ways to get there by train. Actually, wait, there are three ways to get there by train.",
            "And she said, there are three ways to get there by train.");
        check("let's meet at five, no wait, six.","Let's meet at six.");
        check("I'll be there Tuesday, actually wait, Wednesday.","I'll be there Wednesday.");
        check("send it to Mark, no wait, send it to Sarah.","Send it to Sarah.");
        check("Buy milk. Scratch that. Buy bread.","Buy bread.");
        check("buy milk scratch that buy bread","Buy bread");
        check("I mean, it's fine","I mean, it's fine");
        check("it was good, I mean it was fine","It was fine");
        check("I love it, I mean it's great","I love it, I mean it's great");
        check("we need the red one, I mean the blue one","We need the blue one");
        check("no wait for me","No wait for me");
        check("I like tea more than wheat. I like coffee more than juice.","I like coffee more than juice.");
        check("the shelf is two feet wide weight, three feet wide, five hundred dollars, actually four hundred fifty dollars.",
            "The shelf is 3 feet wide, $450.");
        check("I like tea more than, wait, I like coffee","I like coffee");
        check("I can't wait to see you","I can't wait to see you");
        check("the cost, the weight. And how long it lasts.","The cost, the weight. And how long it lasts.");
        check("we need to wait, then we go","We need to wait, then we go");
        check("I actually like it","I actually like it");
        check("it was fine, actually it was great","It was great");
        check("the bread has wheat in it","The bread has wheat in it");
        check("let's meet at five, no wait, six o'clock.","Let's meet at 6 o'clock.");
        check("book it for four people, make that six people.","Book it for six people.");
        check("I'll make that call tomorrow.","I'll make that call tomorrow.");
        check("the meeting is on Friday, never mind, Sunday.","The meeting is on Sunday.");
        check("it's fine, never mind.","It's fine, never mind.");
        check("send it to the platform team, no sorry, to the support team.","Send it to the support team.");
        check("we ship in February, sorry, make that May.","We ship in May.");
        check("sorry I'm late","Sorry I'm late");
        check("I'm sorry, I didn't see it","I'm sorry, I didn't see it");
        check("the correction was made yesterday","The correction was made yesterday");
        check("it was my mistake to go","It was my mistake to go");
        check("I'd rather go home","I'd rather go home");
        check("the call is at eleven am, ignore that, the call is at three pm.","The call is at 3 pm.");
        check("send the deck to Kwame, on second thought, send it to Ravi.","Send it to Ravi.");
        check("the conference is in Prague, my mistake, Vienna.","The conference is in Vienna.");
        check("we need forty chairs. Correction, nine chairs.","We need nine chairs.");
        check("I'll send the update on Tuesday, on second thought, Wednesday in the meantime.","I'll send the update on Wednesday in the meantime.");
        check("book room two for the session, sorry, the lab and I'll update the tracker.","Book room two for the lab and I'll update the tracker.");
        check("the server is in Prague, on second thought, Oslo because the client asked.","The server is in Oslo because the client asked.");

        // Lists.
        check("My grocery list, first eggs, second milk, and third bread.","My grocery list:\n1. Eggs\n2. Milk\n3. Bread");
        check("First, call the bank. Second, email the landlord. Then we're done.","1. Call the bank\n2. Email the landlord\nThen we're done.");
        check("These are the house rules 1. we take our shoes off. 2 we clean up. 3 we close the door.",
            "These are the house rules:\n1. We take our shoes off\n2. We clean up\n3. We close the door");
        check("the plan. One, we pack. Two, we drive. Three, we rest.","The plan.\n1. We pack\n2. We drive\n3. We rest");
        check("One day we left. Two weeks later we came back.","One day we left. 2 weeks later we came back.");
        check("one, two, three, go","One, two, three, go");
        check("Here is the plan. First, some background. First, we pack. Second, we drive. Third, we rest.",
            "Here is the plan. First, some background.\n1. We pack\n2. We drive\n3. We rest");
        check("Let's make another list. Number one, we pack. Number two, we drive.","Let's make another list.\n1. We pack\n2. We drive");
        check("Here is my list. Number 1 we pack the car. Number 2 we drive home.","Here is my list.\n1. We pack the car\n2. We drive home");
        check("Number 1. We pack. Number 2: we drive.","1. We pack\n2. We drive");
        check("I have 2 dogs. 3 cats live next door.","I have 2 dogs. 3 cats live next door.");
        check("todo bullet point fix the build bullet point ship it","Todo:\n- Fix the build\n- Ship it");
        check("number one we go home, number two we sleep","1. We go home\n2. We sleep");
        check("at first we waited a second","At first we waited a second");
        check("first we ate and then we left","First we ate and then we left");
        check("the second time was the first time I won","The second time was the first time I won");
        check("make it a bullet point","Make it a bullet point");
        DictationCleanup.Options noLists=new DictationCleanup.Options();noLists.formatLists=false;
        expect("lists off",DictationCleanup.clean("first eggs, second milk",noLists),"First eggs, second milk");

        // Vocabulary, final period, and lowercase.
        DictationCleanup.Options vocab=new DictationCleanup.Options();
        vocab.replacements=DictationCleanup.vocabularyRules(java.util.List.of("SwiftUI","TestFlight","OpenCode","Kaczmarek"));
        expect("vocabulary rules",DictationCleanup.clean("we use swift ui and testflight with open code",vocab),"We use SwiftUI and TestFlight with OpenCode");
        expect("vocabulary rule count",String.valueOf(vocab.replacements.size()),"7");
        DictationCleanup.Options plain=new DictationCleanup.Options();plain.endWithPeriod=false;
        expect("no final period",DictationCleanup.clean("It works. See you soon.",plain),"It works. See you soon");
        expect("keeps question mark",DictationCleanup.clean("Are you coming?",plain),"Are you coming?");
        DictationCleanup.Options low=new DictationCleanup.Options();low.lowercase=true;
        expect("lowercase",DictationCleanup.clean("I met Anna in Paris.",low),"i met anna in paris.");

        // Input for the smart cleanup model.
        expect("prepare",DictationCleanup.prepareForEditor("So, um, I I like tea more than wheat. I like coffee, you know, a lot.",new DictationCleanup.Options()),
            "so i like coffee a lot");
        expect("spoken punctuation found",String.valueOf(DictationCleanup.hasSpokenPunctuation("hello comma world")),"true");
        expect("no spoken punctuation",String.valueOf(DictationCleanup.hasSpokenPunctuation("the commander left")),"false");

        // Automatic capitalization off.
        expect("no caps",lowerCase("The meeting moved. So we left early. I think Monday works and NASA agreed."),
            "the meeting moved. so we left early. I think Monday works and NASA agreed.");
        expect("no caps fast model",lowerCase("HELLO THERE. I AM HERE"),"hello there. I am here");
        expect("no caps verbatim",DictationCleanup.verbatim("The plan works. It is fine.",noCaps()),"the plan works. it is fine.");

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
    private static dev.softkeys.ios.EnglishDictionary dictionary;
    private static DictationCleanup.Options seamOptions(){
        if(dictionary==null)try(java.io.InputStream words=new java.io.FileInputStream("app/src/main/assets/english.tsv");
            java.io.InputStream additions=new java.io.FileInputStream("app/src/main/assets/lexicon.tsv")){
            dictionary=new dev.softkeys.ios.EnglishDictionary(words,additions);
        }catch(java.io.IOException e){throw new RuntimeException(e);}
        DictationCleanup.Options options=new DictationCleanup.Options();
        options.lowercaseWord=dictionary::commonLowercase;
        return options;
    }
    private static DictationCleanup.Options noCaps(){DictationCleanup.Options o=seamOptions();o.capitalize=false;return o;}
    private static String lowerCase(String input){return DictationCleanup.clean(input,noCaps());}
    private static void seam(String input,String expected){expect(input,input,DictationCleanup.clean(input,seamOptions()),expected);}
    private static java.util.Set<String> english;
    private static void sounds(String input,String rules,String expected){
        if(english==null){
            english=new java.util.HashSet<>();
            try{for(String line:java.nio.file.Files.readAllLines(java.nio.file.Paths.get("app/src/main/assets/english.tsv")))english.add(line.split("\t")[0]);}
            catch(java.io.IOException e){throw new RuntimeException(e);}
        }
        DictationCleanup.Options options=new DictationCleanup.Options();
        options.replacements=DictationCleanup.parseReplacements(rules);
        options.vocabulary=DictationCleanup.parseVocabulary(rules);
        options.knownWord=english::contains;
        expect(input,input,DictationCleanup.clean(input,options),expected);
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
