import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Offline accuracy checks for the SoftKeys dictionary engine. It needs no Android classes.
 * See docs/development.md for how to run it.
 */
public final class TypingBench {
    /** The parts of a dictionary engine that the checks call. */
    public interface Engine {
        /** Returns the text that a word boundary would leave in the editor. */
        String correct(String typed, float[] taps, Map<Character, float[]> centers, float keyWidth, float rowHeight);
        List<String> glide(float[] path, Map<Character, float[]> centers, float keyWidth, float rowHeight);
        boolean known(String word);
    }

    static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

    /** Letter centers for a keyboard that is 10 keys wide, in the same shape SoftKeys draws. */
    static Map<Character, float[]> centers(float left, float keyWidth, float top, float rowHeight) {
        Map<Character, float[]> centers = new HashMap<>();
        float[] offset = {0, .5f, 1.5f};
        for (int r = 0; r < 3; r++)
            for (int i = 0; i < ROWS[r].length(); i++)
                centers.put(ROWS[r].charAt(i), new float[]{left + (offset[r] + i + .5f) * keyWidth, top + (r + .5f) * rowHeight});
        return centers;
    }

    static String plain(String word) {
        StringBuilder b = new StringBuilder();
        for (char c : word.toLowerCase(Locale.US).toCharArray()) if (c >= 'a' && c <= 'z') b.append(c);
        return b.toString();
    }

    // ---- Real swipes -------------------------------------------------------------------------

    static void swipes(Engine engine, Path file, int skip, int limit, boolean verbose) throws IOException {
        int total = 0, top1 = 0, top3 = 0, unknown = 0, errTotal = 0, errTop1 = 0, none = 0; long nanos = 0;
        int[] byLength = new int[12], byLengthHit = new int[12];
        Map<String, Integer> misses = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; int seen = 0;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length < 6 || !(f[1].equals("0") || f[1].equals("1"))) continue;
                if (seen++ < skip) continue;
                if (total + errTotal + unknown >= limit) break;
                String word = f[0];
                if (!engine.known(word)) { unknown++; continue; }
                float kw = Float.parseFloat(f[3]), kh = Float.parseFloat(f[4]);
                // The recorded keyboard has letter rows centered at these fractions of its height.
                float keyWidth = kw / 10, rowHeight = kh * .2805f, top = kh * .147f - rowHeight / 2;
                Map<Character, float[]> centers = centers(0, keyWidth, top, rowHeight);
                String[] points = f[5].split(" ");
                float[] path = new float[points.length * 2]; int n = 0; float lastX = Float.NaN, lastY = Float.NaN;
                for (String point : points) {
                    String[] xy = point.split(",");
                    float x = Float.parseFloat(xy[0]), y = Float.parseFloat(xy[1]);
                    if (x == lastX && y == lastY) continue;
                    path[n++] = x; path[n++] = y; lastX = x; lastY = y;
                }
                path = Arrays.copyOf(path, n);
                long start = System.nanoTime();
                List<String> result = engine.glide(path, centers, keyWidth, rowHeight);
                nanos += System.nanoTime() - start;
                boolean first = !result.isEmpty() && plain(result.get(0)).equals(word), any = false;
                for (String r : result) any |= plain(r).equals(word);
                if (f[1].equals("1")) { errTotal++; if (first) errTop1++; continue; }
                total++; if (first) top1++; if (any) top3++; if (result.isEmpty()) none++;
                int bucket = Math.min(11, word.length()); byLength[bucket]++; if (first) byLengthHit[bucket]++;
                if (!first) misses.merge(word + "->" + (result.isEmpty() ? "(none)" : result.get(0)), 1, Integer::sum);
            }
        }
        System.out.printf("Real swipes marked correct by the dataset: %d. First choice right %.1f%%, in the three choices %.1f%%, no result %.1f%%.%n",
            total, 100.0 * top1 / total, 100.0 * top3 / total, 100.0 * none / total);
        System.out.printf("Real swipes marked as failed by the dataset: %d. First choice right %.1f%%.%n", errTotal, 100.0 * errTop1 / Math.max(1, errTotal));
        System.out.printf("Skipped %d swipes for words outside the dictionary. Mean decode time %.1f ms.%n", unknown, nanos / 1e6 / Math.max(1, total + errTotal));
        StringBuilder lengths = new StringBuilder("First choice right by word length:");
        for (int i = 2; i < 12; i++) if (byLength[i] > 0) lengths.append(String.format(" %d%s=%.0f%%", i, i == 11 ? "+" : "", 100.0 * byLengthHit[i] / byLength[i]));
        System.out.println(lengths);
        if (verbose) {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(misses.entrySet());
            list.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder b = new StringBuilder("Most common misses:");
            for (int i = 0; i < Math.min(40, list.size()); i++) b.append(' ').append(list.get(i).getKey()).append('×').append(list.get(i).getValue());
            System.out.println(b);
        }
    }

    // ---- Simulated tap typing ----------------------------------------------------------------

    static final float KEY = 33.84f, ROW = 44f, LEFT = 10.8f;

    static char nearest(Map<Character, float[]> centers, float x, float y) {
        char best = 'a'; double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<Character, float[]> e : centers.entrySet()) {
            double d = Math.pow((x - e.getValue()[0]) / KEY, 2) + Math.pow((y - e.getValue()[1]) / ROW, 2);
            if (d < bestDistance) { bestDistance = d; best = e.getKey(); }
        }
        return best;
    }

    /** Types a word with finger position noise and occasional swapped, missed and doubled letters. */
    static Object[] type(String word, Map<Character, float[]> centers, Random random, double sigma, double slip) {
        List<Character> intended = new ArrayList<>();
        for (char c : plain(word).toCharArray()) intended.add(c);
        if (intended.size() >= 2 && random.nextDouble() < slip) {
            int kind = random.nextInt(3), i = random.nextInt(intended.size() - 1);
            if (kind == 0) Collections.swap(intended, i, i + 1);
            else if (kind == 1) intended.remove(i + 1);
            else intended.add(i, intended.get(i));
        }
        StringBuilder typed = new StringBuilder(); float[] taps = new float[intended.size() * 2];
        for (int i = 0; i < intended.size(); i++) {
            float[] c = centers.get(intended.get(i));
            float x = (float) (c[0] + random.nextGaussian() * sigma * KEY), y = (float) (c[1] + random.nextGaussian() * sigma * ROW);
            typed.append(nearest(centers, x, y)); taps[i * 2] = x; taps[i * 2 + 1] = y;
        }
        return new Object[]{typed.toString(), taps};
    }

    static void taps(Engine engine, List<String> words, List<Long> counts, long seed, double sigma, double slip, boolean useTaps, int samples, boolean verbose) {
        Map<Character, float[]> centers = centers(LEFT, KEY, 0, ROW);
        Random random = new Random(seed);
        long total = 0; for (long c : counts) total += c;
        long[] cumulative = new long[counts.size()]; long sum = 0;
        for (int i = 0; i < counts.size(); i++) { sum += counts.get(i); cumulative[i] = sum; }
        int typos = 0, fixed = 0, wrongFix = 0, clean = 0, broken = 0; long nanos = 0;
        Map<String, Integer> breaks = new HashMap<>(), left = new HashMap<>();
        for (int s = 0; s < samples; s++) {
            long pick = (long) (random.nextDouble() * total);
            int index = Arrays.binarySearch(cumulative, pick); if (index < 0) index = -index - 1;
            String word = words.get(Math.min(index, words.size() - 1));
            if (plain(word).length() < 2) continue;
            Object[] attempt = type(word, centers, random, sigma, slip);
            String typed = (String) attempt[0];
            long start = System.nanoTime();
            String out = engine.correct(typed, useTaps ? (float[]) attempt[1] : null, centers, KEY, ROW);
            nanos += System.nanoTime() - start;
            boolean right = plain(out).equals(plain(word));
            if (typed.equals(plain(word))) { clean++; if (!right) { broken++; breaks.merge(typed + "->" + out, 1, Integer::sum); } }
            else { typos++; if (right) fixed++; else { if (!out.equals(typed)) wrongFix++; left.merge(word + ":" + typed + "->" + out, 1, Integer::sum); } }
        }
        int all = typos + clean;
        System.out.printf("Simulated taps (%s, spread %.2f key, slips %.0f%%): %d words, %.1f%% typed wrong. Typos fixed %.1f%%, typos changed to a wrong word %.1f%%, correct words changed %.2f%%. Words right at the end %.2f%%. Mean time %.2f ms.%n",
            useTaps ? "with touch points" : "letters only", sigma, slip * 100, all, 100.0 * typos / all, 100.0 * fixed / Math.max(1, typos), 100.0 * wrongFix / Math.max(1, typos), 100.0 * broken / Math.max(1, clean), 100.0 * (clean - broken + fixed) / all, nanos / 1e6 / all);
        if (verbose) { top("Correct words changed:", breaks); top("Typos left wrong:", left); }
    }

    static void top(String title, Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder b = new StringBuilder(title);
        for (int i = 0; i < Math.min(40, list.size()); i++) b.append(' ').append(list.get(i).getKey()).append('×').append(list.get(i).getValue());
        System.out.println(b);
    }

    /** Types words that the dictionary does not hold, without mistakes, and counts unwanted changes. */
    static void unknownWords(Engine engine, List<String> heldOut, boolean verbose) {
        Map<Character, float[]> centers = centers(LEFT, KEY, 0, ROW);
        int changed = 0, total = 0; List<String> examples = new ArrayList<>();
        for (String word : heldOut) {
            if (engine.known(word)) continue;
            total++;
            float[] taps = new float[word.length() * 2];
            for (int i = 0; i < word.length(); i++) { float[] c = centers.get(word.charAt(i)); taps[i * 2] = c[0]; taps[i * 2 + 1] = c[1]; }
            String out = engine.correct(word, taps, centers, KEY, ROW);
            if (!out.equals(word)) { changed++; if (examples.size() < 60) examples.add(word + "->" + out); }
        }
        System.out.printf("Words outside the dictionary typed without mistakes: %d. Changed by autocorrect %.1f%%.%n", total, 100.0 * changed / Math.max(1, total));
        if (verbose) System.out.println("Examples: " + String.join(" ", examples));
    }

    static void expectations(Engine engine, String[][] cases) {
        Map<Character, float[]> centers = centers(LEFT, KEY, 0, ROW);
        int failed = 0;
        for (String[] c : cases) {
            String out = engine.correct(c[0], null, centers, KEY, ROW);
            if (!out.equals(c[1])) { failed++; System.out.println("  FAIL " + c[0] + " gave " + out + ", expected " + c[1]); }
        }
        System.out.printf("Fixed cases: %d of %d passed.%n", cases.length - failed, cases.length);
        if (failed > 0) failures += failed;
    }
    static int failures;

    static final String[][] CASES = {
        {"teh", "the"}, {"adn", "and"}, {"thr", "the"}, {"dont", "don't"}, {"cant", "can't"}, {"wont", "won't"}, {"im", "I'm"}, {"ive", "I've"},
        {"i", "I"}, {"ill", "I'll"}, {"youre", "you're"}, {"thats", "that's"}, {"doesnt", "doesn't"}, {"didnt", "didn't"}, {"isnt", "isn't"},
        {"hasnt", "hasn't"}, {"wouldnt", "wouldn't"}, {"theyre", "they're"}, {"whats", "what's"}, {"hes", "he's"}, {"shes", "she's"},
        {"recieve", "receive"}, {"definately", "definitely"}, {"seperate", "separate"}, {"tommorow", "tomorrow"}, {"becuase", "because"},
        {"wich", "which"}, {"freind", "friend"}, {"beleive", "believe"}, {"thier", "their"}, {"hwo", "how"}, {"jsut", "just"}, {"waht", "what"},
        {"helo", "hello"}, {"gooing", "going"}, {"probaly", "probably"}, {"somthing", "something"}, {"abotu", "about"}, {"woudl", "would"},
        {"monday", "Monday"}, {"january", "January"}, {"london", "London"}, {"Teh", "The"}, {"Monday", "Monday"},
        // Words that must stay as typed.
        {"well", "well"}, {"were", "were"}, {"its", "its"}, {"hell", "hell"}, {"shell", "shell"}, {"the", "the"}, {"form", "form"}, {"from", "from"},
        {"thus", "thus"}, {"tot", "tot"}, {"wit", "wit"}, {"hid", "hid"}, {"lite", "lite"}, {"gut", "gut"}, {"ok", "ok"}, {"lol", "lol"},
        {"iPhone", "iPhone"}, {"NASA", "NASA"}, {"wifi", "wifi"}, {"hello", "hello"}, {"gonna", "gonna"}, {"yeah", "yeah"}, {"may", "may"}, {"will", "will"},
    };

    public static void run(Engine engine, Path root, String[] args) throws IOException {
        boolean verbose = Arrays.asList(args).contains("-v"), quick = Arrays.asList(args).contains("--quick");
        List<String> words = new ArrayList<>(); List<Long> counts = new ArrayList<>(); List<String> rare = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(root.resolve("app/src/main/assets/english.tsv"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] pair = line.split("\t");
                if (!engine.known(pair[0])) continue;
                words.add(pair[0]); counts.add(Long.parseLong(pair[1]));
            }
        }
        expectations(engine, CASES);
        int samples = quick ? 10000 : 30000;
        taps(engine, words, counts, 11, .30, .03, true, samples, verbose);
        if (!quick) taps(engine, words, counts, 11, .30, .03, false, samples, false);
        taps(engine, words, counts, 12, .22, .02, true, samples, false);
        Path swipes = root.resolve("build/bench-data/swipes.tsv");
        if (Files.exists(swipes)) {
            // The recorded sentences hold real words that the word list lacks, such as names.
            Set<String> outside = new TreeSet<>();
            for (String line : Files.readAllLines(swipes)) {
                String[] f = line.split("\t");
                if (f.length >= 6 && f[1].equals("0") && f[0].matches("[a-z]{3,}") && !engine.known(f[0])) outside.add(f[0]);
            }
            unknownWords(engine, new ArrayList<>(outside), verbose);
            if (Arrays.asList(args).contains("--no-swipes")) return;
            boolean holdout = Arrays.asList(args).contains("--holdout");
            swipes(engine, swipes, holdout ? 40000 : 0, quick ? 8000 : holdout ? 60000 : 30000, verbose);
        } else System.out.println("Real swipe data is not present. See docs/development.md to download it.");
    }
}
