package dev.softkeys.ios;

import java.util.Locale;

/**
 * Decisions for the dictation keyboard that do not need Android classes, so
 * tests/DictationModeTest.java can check them on a plain JDK.
 *
 * The dictation keyboard replaces the keys with a panel that has a Dictate button, a level
 * meter, Space, Delete, Return, and a keyboard button. The user turns it on with the
 * microphone key and off with the keyboard button, and the choice is saved. Fields that
 * must not record, such as passwords and numbers, always show the keys.
 */
public final class DictationMode {
    private DictationMode(){}

    /** The recognizer used for the next recording. */
    public enum Engine {
        /** Parakeet TDT, which decodes the recording after Done. */
        ACCURATE,
        /** The streaming FastConformer model, which decodes while the user speaks. */
        FAST,
        /** Android's speech recognition service. */
        PHONE,
        /** Nothing can recognize speech. */
        NONE;
        public boolean onDevice(){return this==ACCURATE||this==FAST;}
    }

    /**
     * True when a field may record speech. Password, PIN, number, phone, and date fields
     * never record, and neither do fields that ask for no personalized learning, such as
     * incognito browser tabs. allowPredictions is false for those field classes already.
     */
    public static boolean fieldAllowsDictation(boolean privateField,boolean numeric,boolean allowPredictions,boolean noPersonalizedLearning){
        return !privateField&&!numeric&&allowPredictions&&!noPersonalizedLearning;
    }

    /**
     * True when the panel replaces the keys. The saved choice is kept when a field that
     * cannot record shows the keys, so the next ordinary field shows the panel again.
     */
    public static boolean showPanel(boolean savedChoice,boolean fieldAllowsDictation){return savedChoice&&fieldAllowsDictation;}

    /**
     * Chooses the engine. The preferred engine is used when it can run, and the other one
     * otherwise. Among the on-device models, the chosen model is used when it is
     * downloaded, and the other downloaded model otherwise.
     *
     * @param preferDevice the user chose on-device dictation
     * @param model "fast" or "accurate"
     * @param supported the phone has the native speech library
     */
    public static Engine engine(boolean preferDevice,String model,boolean supported,boolean accurateInstalled,boolean fastInstalled,boolean phoneService){
        Engine device=Engine.NONE;
        if(supported){
            if("fast".equals(model))device=fastInstalled?Engine.FAST:accurateInstalled?Engine.ACCURATE:Engine.NONE;
            else device=accurateInstalled?Engine.ACCURATE:fastInstalled?Engine.FAST:Engine.NONE;
        }
        if(preferDevice)return device!=Engine.NONE?device:phoneService?Engine.PHONE:Engine.NONE;
        return phoneService?Engine.PHONE:device;
    }

    /** Recording time as m:ss, such as 0:05 or 12:40. */
    public static String elapsed(long millis){
        long seconds=Math.max(0,millis)/1000;
        return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);
    }

    /** Meter height from 0 to 1 for the RMS of samples from -1 to 1. -55 dBFS and below is 0, and -12 dBFS and above is 1. */
    public static float levelFromRms(double rms){
        if(!(rms>0))return 0;
        double db=20*Math.log10(rms);
        return clamp((float)((db+55)/43));
    }

    /** Meter height for the decibel value from Android's onRmsChanged, which is about -2 in silence and 10 for loud speech. */
    public static float levelFromRmsDb(float db){return clamp((db+1.5f)/10.5f);}

    private static float clamp(float value){return value<0?0:value>1?1:value;}

    /**
     * Turns the microphone level into how strongly the recording waves move, from 0 for the
     * room's background noise to 1 for normal speech. It follows the background level: down
     * at once when the room gets quieter, and up over about 10 seconds, so steady noise such
     * as a fan reads as silence while a long stretch of speech does not.
     */
    public static final class Voice {
        private float floor=-1;

        public void reset(){floor=-1;}

        /** Call once per frame with the smoothed level. Returns the voice activity, from 0 to 1. */
        public float update(float level){
            // Start no higher than a quiet room, in case speech begins with the recording.
            if(floor<0)floor=Math.min(level,.2f);
            floor+=(level-floor)*(level<floor?.3f:.0015f);
            floor=Math.min(floor,.45f);
            float activity=clamp((level-floor-.04f)/.36f);
            return (float)Math.pow(activity,.8);
        }
    }

    /**
     * Smooths the microphone level and keeps a history of bars for the meter. A bar is
     * added every 50 ms of drawing time, so the meter scrolls at an even speed whether the
     * levels arrive from the on-device recorder or from the phone speech service.
     */
    public static final class Meter {
        public static final long STEP_MS=50;
        private final float[] history;
        private int head;
        private float target,current;
        private long last=-1;

        public Meter(int bars){history=new float[Math.max(1,bars)];}

        public void reset(){java.util.Arrays.fill(history,0);head=0;target=current=0;last=-1;}

        /** The latest measured level, from 0 to 1. */
        public void target(float level){target=clamp(level);}

        /** Adds the bars that are due at this time. Rises quickly and falls slowly. */
        public void advance(long now){
            if(last<0){last=now;return;}
            int steps=(int)Math.min(history.length,(now-last)/STEP_MS);
            for(int i=0;i<steps;i++){
                current+=(target-current)*(target>current?.7f:.35f);
                history[head]=current;head=(head+1)%history.length;
            }
            last+=steps*STEP_MS;
            if(now-last>STEP_MS)last=now;
        }

        /** The share of the next step that has passed, from 0 to 1, for smooth scrolling. */
        public float fraction(long now){return last<0?0:Math.min(1,(now-last)/(float)STEP_MS);}

        /** Bar height, where 0 is the newest bar. */
        public float bar(int age){
            if(age<0||age>=history.length)return 0;
            return history[((head-1-age)%history.length+history.length)%history.length];
        }

        public int size(){return history.length;}

        /** The smoothed level, from 0 to 1. */
        public float level(){return current;}
    }
}
