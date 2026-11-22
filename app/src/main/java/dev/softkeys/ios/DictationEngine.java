package dev.softkeys.ios;

/** A user-controlled dictation session. Callbacks arrive on the main thread. */
interface DictationEngine {
    interface Listener {
        void status(DictationEngine session,String text,boolean finishing);
        void result(DictationEngine session,String text);
        void error(DictationEngine session,String message,String retainedText);
    }
    void start();
    /** Finishes the session. The listener receives the transcript once. */
    void stop();
    /** Discards the session without a callback. */
    void close();
}
