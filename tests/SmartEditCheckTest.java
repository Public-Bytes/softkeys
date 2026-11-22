import dev.softkeys.ios.SmartEditCheck;

/**
 * Offline checks for SmartEditCheck. It needs only a JDK.
 *
 *   mkdir -p build/edit-check-test
 *   javac -d build/edit-check-test tests/SmartEditCheckTest.java app/src/main/java/dev/softkeys/ios/SmartEditCheck.java
 *   java -cp build/edit-check-test SmartEditCheckTest
 */
public final class SmartEditCheckTest {
    private static int failures,passes;

    public static void main(String[] args){
        // Good edits.
        accept("we need the red one i mean the blue one","We need the blue one.");
        accept("let's meet at five no wait six o'clock","Let's meet at six o'clock.");
        accept("so um i think we should uh you know ship it on friday scratch that ship it monday","I think we should ship it monday.");
        accept("i i think the plan works","I think the plan works.");
        accept("and she said there are two ways to get there actually wait there are three ways to get there",
            "And she said there are three ways to get there.");
        accept("book it for four people make that six people","Book it for six people.");
        accept("i can't wait to see you tomorrow","I can't wait to see you tomorrow.");
        accept("it was like six feet wide","It was six feet wide.");
        accept("","");
        // Bad edits.
        reject("i lost weight i lost ten pounds this year","I lost ten pounds this year.");
        reject("i actually like it a lot","I like it a lot.");
        reject("no i don't want it","I don't want it.");
        reject("go to google dot com","Go to Google.com.");
        reject("there are two things actually wait there are three things","There are three things. Actually wait there are three things.");
        reject("the cost the weight and how long it lasts","The cost, the weight.");
        reject("we ship in july the total is fifteen hundred","The total is fifteen hundred.");
        reject("hello there","");
        reject("hello there",null);
        reject("one two three four five six seven eight nine ten no wait eleven","Eleven.");
        System.out.println(passes+" passed, "+failures+" failed");
        if(failures>0)System.exit(1);
    }

    private static void accept(String in,String out){check(in,out,true);}
    private static void reject(String in,String out){check(in,out,false);}
    private static void check(String in,String out,boolean expected){
        boolean actual=SmartEditCheck.accept(in,out);
        if(actual==expected){passes++;return;}
        failures++;
        System.out.println("FAIL "+(expected?"should accept":"should reject")+"\n  input:  "+in+"\n  output: "+out);
    }
}
