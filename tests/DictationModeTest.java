import dev.softkeys.ios.DictationMode;
import dev.softkeys.ios.DictationMode.Engine;
import dev.softkeys.ios.SpeechSegmenter;

/**
 * Offline checks for the dictation keyboard decisions in DictationMode and SpeechSegmenter.
 * It needs only a JDK.
 *
 *   mkdir -p build/mode-test
 *   javac -d build/mode-test tests/DictationModeTest.java app/src/main/java/dev/softkeys/ios/DictationMode.java app/src/main/java/dev/softkeys/ios/SpeechSegmenter.java
 *   java -cp build/mode-test DictationModeTest
 */
public final class DictationModeTest {
    private static int failures,passes;

    public static void main(String[] args){
        // Which fields may record. allowPredictions is false for password, number, phone, and date fields.
        expect("plain text field",DictationMode.fieldAllowsDictation(false,false,true,false),true);
        expect("password field",DictationMode.fieldAllowsDictation(true,false,false,false),false);
        expect("password flag alone",DictationMode.fieldAllowsDictation(true,false,true,false),false);
        expect("number field",DictationMode.fieldAllowsDictation(false,true,false,false),false);
        expect("field without predictions",DictationMode.fieldAllowsDictation(false,false,false,false),false);
        expect("incognito field",DictationMode.fieldAllowsDictation(false,false,true,true),false);

        // The panel shows only when the user chose it and the field allows recording.
        expect("chosen, ordinary field",DictationMode.showPanel(true,true),true);
        expect("chosen, password field",DictationMode.showPanel(true,false),false);
        expect("not chosen",DictationMode.showPanel(false,true),false);
        // The saved choice survives a private field: the next ordinary field shows the panel again.
        boolean saved=true;
        expect("password field keeps keys",DictationMode.showPanel(saved,DictationMode.fieldAllowsDictation(true,false,false,false)),false);
        expect("next text field shows panel",DictationMode.showPanel(saved,DictationMode.fieldAllowsDictation(false,false,true,false)),true);

        // Engine choice: preferDevice, model, supported, accurateInstalled, fastInstalled, phoneService.
        expect("accurate chosen and installed",DictationMode.engine(true,"accurate",true,true,true,true),Engine.ACCURATE);
        expect("fast chosen and installed",DictationMode.engine(true,"fast",true,true,true,true),Engine.FAST);
        expect("accurate chosen, only fast installed",DictationMode.engine(true,"accurate",true,false,true,true),Engine.FAST);
        expect("fast chosen, only accurate installed",DictationMode.engine(true,"fast",true,true,false,true),Engine.ACCURATE);
        expect("no model, phone service",DictationMode.engine(true,"accurate",true,false,false,true),Engine.PHONE);
        expect("unsupported phone",DictationMode.engine(true,"accurate",false,true,true,true),Engine.PHONE);
        expect("nothing available",DictationMode.engine(true,"accurate",true,false,false,false),Engine.NONE);
        expect("phone service preferred",DictationMode.engine(false,"accurate",true,true,true,true),Engine.PHONE);
        expect("phone service preferred but missing",DictationMode.engine(false,"accurate",true,true,false,false),Engine.ACCURATE);
        expect("phone service preferred, nothing",DictationMode.engine(false,"fast",true,false,false,false),Engine.NONE);
        expect("unknown model id uses accurate",DictationMode.engine(true,"other",true,true,true,true),Engine.ACCURATE);
        expect("accurate is on-device",Engine.ACCURATE.onDevice(),true);
        expect("phone is not on-device",Engine.PHONE.onDevice(),false);

        // Timer.
        expect("zero",DictationMode.elapsed(0),"0:00");
        expect("rounds down",DictationMode.elapsed(1999),"0:01");
        expect("minute",DictationMode.elapsed(65_000),"1:05");
        expect("long",DictationMode.elapsed(12*60_000+40_000),"12:40");
        expect("negative",DictationMode.elapsed(-5),"0:00");

        // Levels.
        expect("silence",DictationMode.levelFromRms(0),0f);
        expect("quiet room",DictationMode.levelFromRms(0.0005)<0.05f,true);
        expect("speech",DictationMode.levelFromRms(0.05)>0.5f,true);
        expect("loud",DictationMode.levelFromRms(0.9),1f);
        expect("NaN",DictationMode.levelFromRms(Double.NaN),0f);
        expect("service silence",DictationMode.levelFromRmsDb(-2),0f);
        expect("service loud",DictationMode.levelFromRmsDb(12),1f);

        // Meter: one bar per 50 ms, rising fast and falling slowly, newest first.
        DictationMode.Meter meter=new DictationMode.Meter(8);
        meter.advance(1000);meter.target(1);meter.advance(1050);
        expect("one step",meter.bar(0)>0.6f,true);
        expect("older bar empty",meter.bar(1),0f);
        meter.advance(1100);float peak=meter.bar(0);
        meter.target(0);meter.advance(1150);
        expect("falls slowly",meter.bar(0)>peak*0.5f&&meter.bar(0)<peak,true);
        expect("fraction",meter.fraction(1175),0.5f);
        meter.advance(5000);
        expect("long gap fills at most the history",meter.bar(7)>=0f,true);
        expect("out of range",meter.bar(99),0f);
        meter.reset();
        expect("reset",meter.bar(0),0f);

        // Segmenter, with chunks of 50 ms: soft limit 20 s, pause 0.7 s, hard limit 40 s.
        SpeechSegmenter segmenter=new SpeechSegmenter();
        int cut=0,at=0;
        for(int i=0;i<500&&cut==0;i++){
            boolean pause=i>=420&&i<436; // A 0.8-second pause at 21 s.
            cut=segmenter.add(pause?0.0005f:0.08f);at=i;
        }
        expect("cuts in the pause",at>=433&&at<436,true);
        expect("cut point is inside the pause",cut>=420&&cut<=436,true);
        segmenter=new SpeechSegmenter();
        cut=0;
        for(int i=0;i<700&&cut==0;i++){
            boolean pause=i>=420&&i<430; // A half-second pause inside a sentence is not a split point.
            cut=segmenter.add(pause?0.0005f:0.08f+(i%7)*0.001f);
        }
        expect("short pauses are not cut",cut,0);
        segmenter=new SpeechSegmenter();
        cut=0;at=0;
        for(int i=0;i<900&&cut==0;i++){
            float level=i==760?0.01f:0.08f+(i%7)*0.001f; // No pause, one softer moment at 38 s.
            cut=segmenter.add(level);at=i;
        }
        expect("hard limit",at,799);
        expect("cuts at the quietest moment",cut,761);
        segmenter=new SpeechSegmenter();
        cut=0;
        for(int i=0;i<300;i++)cut+=segmenter.add(i%40<30?0.08f:0.0005f);
        expect("short recordings are not cut",cut,0);
        expect("rms",SpeechSegmenter.rms(new float[]{0.5f,-0.5f},0,2),0.5f);

        // Voice activity for the recording waves. A room level of 0.2 reads as silence and
        // speech at 0.65 as full, and a steady fan does not keep the waves up.
        DictationMode.Voice voice=new DictationMode.Voice();
        float quiet=0;for(int i=0;i<60;i++)quiet=voice.update(0.2f);
        expect("silence is flat",quiet<0.05f,true);
        float loud=voice.update(0.65f);
        expect("speech is full",loud>0.95f,true);
        float soft=voice.update(0.4f);
        expect("soft speech is partial",soft>0.3f&&soft<0.8f,true);
        voice.reset();
        float fan=0;for(int i=0;i<4000;i++)fan=voice.update(0.45f);
        expect("steady noise settles",fan<0.1f,true);

        System.out.println(passes+" passed, "+failures+" failed");
        if(failures>0)System.exit(1);
    }

    private static void expect(String name,Object actual,Object expected){
        if(actual.equals(expected)){passes++;return;}
        failures++;
        System.out.println("FAIL "+name+"\n  expected: "+expected+"\n  actual:   "+actual);
    }
}
