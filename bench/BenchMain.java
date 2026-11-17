import dev.softkeys.ios.EnglishDictionary;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/** Runs the accuracy checks against the SoftKeys dictionary engine. */
public final class BenchMain {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 && !args[0].startsWith("-") ? args[0] : ".");
        EnglishDictionary dictionary;
        try (InputStream words = Files.newInputStream(root.resolve("app/src/main/assets/english.tsv"));
             InputStream additions = Files.newInputStream(root.resolve("app/src/main/assets/lexicon.tsv"))) {
            dictionary = new EnglishDictionary(words, additions);
        }
        TypingBench.run(new TypingBench.Engine() {
            public String correct(String typed, float[] taps, Map<Character, float[]> centers, float keyWidth, float rowHeight) {
                String fixed = dictionary.analyze(typed, taps, new EnglishDictionary.Layout(centers, keyWidth, rowHeight)).correction;
                return fixed == null ? typed : fixed;
            }
            public List<String> glide(float[] path, Map<Character, float[]> centers, float keyWidth, float rowHeight) {
                return dictionary.glide(path, new EnglishDictionary.Layout(centers, keyWidth, rowHeight));
            }
            public boolean known(String word) { return dictionary.containsLetters(word); }
        }, root, args);
        if (TypingBench.failures > 0) System.exit(1);
    }
}
