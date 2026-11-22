package dev.softkeys.ios;

/** A user-controlled dictation session. Callbacks arrive on the main thread. */
interface DictationEngine {
    interface Listener {
        void status(DictationEngine session,String text,boolean finishing);
        /** The microphone level from 0 to 1, several times a second while recording. */
        void level(DictationEngine session,float level);
        /** The transcript as the recognizer wrote it. The keyboard applies cleanup. */
        void result(DictationEngine session,String text);
        void error(DictationEngine session,String message,String retainedText);
    }
    void start();
    /** Finishes the session. The listener receives the transcript once. */
    void stop();
    /** Discards the session without a callback. */
    void close();
    /** True when the transcript comes from an on-device model, so the keyboard's cleanup applies. */
    default boolean onDevice(){return false;}
}
