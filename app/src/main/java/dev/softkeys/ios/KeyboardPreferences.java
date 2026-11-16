package dev.softkeys.ios;

import android.content.Context;
import android.content.SharedPreferences;

final class KeyboardPreferences {
    static final int DEFAULT_VIBRATION_DURATION=12;
    private KeyboardPreferences() {}
    static int vibrationDuration(SharedPreferences prefs) {
        return Math.max(1,Math.min(100,prefs.getInt("vibrationDuration",DEFAULT_VIBRATION_DURATION)));
    }
    static int vibrationDuration(SharedPreferences prefs,boolean longPress) {
        int duration=vibrationDuration(prefs);
        return longPress?Math.min(100,Math.round(duration*5f/3f)):duration;
    }
    static int holdDelay(SharedPreferences prefs) {
        return Math.max(150,Math.min(1000,prefs.getInt("holdDuration",300)));
    }
    static SharedPreferences open(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("keyboard",0);
        // Apply the newly requested reference design once to existing installations.
        // Later appearance choices remain the user's setting.
        if(prefs.getInt("referenceDesignRevision",0)<1)
            prefs.edit().putInt("theme",3).putInt("referenceDesignRevision",1).apply();
        return prefs;
    }
}
