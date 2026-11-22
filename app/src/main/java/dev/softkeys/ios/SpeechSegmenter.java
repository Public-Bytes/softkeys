package dev.softkeys.ios;

/**
 * Chooses where to split a long recording for a model that decodes whole recordings.
 *
 * Parakeet decodes everything the user said after Done. A long recording would make the
 * user wait, and its memory use grows with its length. While the user keeps talking, this
 * class finds a pause after about 20 seconds so that part can be decoded in the
 * background. Without a pause, it splits at the quietest moment of the last 5 seconds
 * once the part reaches 28 seconds. It works in chunks of 50 ms and uses no Android classes.
 */
public final class SpeechSegmenter {
    /** Samples in one chunk at 16 kHz. */
    public static final int CHUNK=800;
    private final int soft,hard,quietRun,search;
    private float[] levels=new float[64];
    private int count;
    private float floor=-1,peak;

    public SpeechSegmenter(){this(400,560,6,100);}

    /**
     * @param soft chunks before a pause may end a part
     * @param hard chunks at which a part is split even without a pause
     * @param quietRun quiet chunks in a row that count as a pause
     * @param search chunks before the hard limit that are searched for the quietest moment
     */
    public SpeechSegmenter(int soft,int hard,int quietRun,int search){this.soft=soft;this.hard=hard;this.quietRun=quietRun;this.search=search;}

    /**
     * Adds the RMS of the next chunk. Returns how many chunks from the start of the current
     * part should be decoded now, or 0 to keep listening. The returned chunks are removed
     * from the current part.
     */
    public int add(float rms){
        if(count==levels.length)levels=java.util.Arrays.copyOf(levels,count*2);
        levels[count++]=rms;
        // The noise floor follows quiet chunks at once and loud chunks very slowly. The speech
        // peak follows loud chunks at once and fades over a few seconds.
        floor=floor<0||rms<floor?rms:floor+(rms-floor)*.002f;
        peak=Math.max(rms,peak*.995f);
        int cut=0;
        if(count>=soft&&quietTail())cut=count-quietRun/2;
        else if(count>=hard){
            int quietest=count-1;
            for(int i=Math.max(0,count-search);i<count;i++)if(levels[i]<levels[quietest])quietest=i;
            cut=quietest+1;
        }
        if(cut>0){
            System.arraycopy(levels,cut,levels,0,count-cut);
            count-=cut;
        }
        return cut;
    }

    private boolean quietTail(){
        // Quiet means near the noise floor and well below recent speech.
        float limit=Math.min(floor*2.5f+.0015f,peak*.3f);
        for(int i=count-quietRun;i<count;i++)if(levels[i]>limit)return false;
        return true;
    }

    /** RMS of samples from -1 to 1. */
    public static float rms(float[] samples,int from,int to){
        double sum=0;
        for(int i=from;i<to;i++)sum+=samples[i]*samples[i];
        return to>from?(float)Math.sqrt(sum/(to-from)):0;
    }
}
