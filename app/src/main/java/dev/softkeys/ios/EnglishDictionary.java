package dev.softkeys.ios;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Offline English word list used for suggestions, spelling correction and glide decoding.
 * The class uses no Android types, so bench/TypingBench.java can run it on a desktop JVM.
 */
public final class EnglishDictionary {
    static final class Entry {
        /** The word as it is inserted, such as "don't", "Monday" or "the". */
        final String word;
        /** The lowercase letters of the word, such as "dont". Typed text is compared with this. */
        final String plain;
        /** The letter indexes a glide passes through. A doubled letter appears once. */
        final byte[] keys;
        final long count;
        final double logCount;
        /** One bit per letter in the word. It rejects most words before the costly comparison. */
        final int mask;
        Entry(String word, long count) {
            this.word = word; this.count = count; logCount = Math.log(count);
            plain = letters(word);
            int bits = 0, n = 0; byte[] sequence = new byte[plain.length()];
            for (int i = 0; i < plain.length(); i++) {
                int c = plain.charAt(i) - 'a'; bits |= 1 << c;
                if (n == 0 || sequence[n - 1] != c) sequence[n++] = (byte) c;
            }
            mask = bits; keys = Arrays.copyOf(sequence, n);
        }
    }

    /** Positions of the letter keys on the visible keyboard, in pixels. */
    public static final class Layout {
        final float[] x = new float[26], y = new float[26];
        final boolean[] present = new boolean[26];
        final float keyWidth, rowHeight;
        public Layout(Map<Character, float[]> centers, float keyWidth, float rowHeight) {
            this.keyWidth = Math.max(1, keyWidth); this.rowHeight = Math.max(1, rowHeight);
            for (Map.Entry<Character, float[]> e : centers.entrySet()) {
                int c = e.getKey() - 'a'; if (c < 0 || c > 25) continue;
                x[c] = e.getValue()[0]; y[c] = e.getValue()[1]; present[c] = true;
            }
        }
        /** Squared distance from a point to a key center. One unit is one key horizontally and one row vertically. */
        double tapDistance(float px, float py, int key) {
            double dx = (px - x[key]) / keyWidth, dy = (py - y[key]) / rowHeight;
            return dx * dx + dy * dy;
        }
    }

    /** The suggestion strip contents and the replacement that a word boundary applies. */
    public static final class Analysis {
        /** The first item is the typed text. The other items are the best candidates. */
        public final String[] suggestions;
        /** The automatic replacement, or null when the typed text should stay. */
        public final String correction;
        Analysis(String[] suggestions, String correction) { this.suggestions = suggestions; this.correction = correction; }
    }

    private final Entry[] entries;
    private final Map<String, Entry> exact = new HashMap<>();
    private final Set<String> plainWords = new HashSet<>();
    private final Entry[][] byFirstKey = new Entry[26][];
    private volatile Entry[] userEntries = new Entry[0];
    private volatile Map<String, Entry> userExact = Collections.emptyMap();

    // Costs are in natural log units, so they can be compared with the log of a word count.
    /** Assumed spread of a tap around the key center, as a fraction of a key. */
    private static final double TAP_SIGMA = .32;
    private static final double SUBSTITUTE_MAX = 6.5, SUBSTITUTE_VOWEL = 4.3, SUBSTITUTE_MIN = .3, FIRST_LETTER = .5;
    private static final double TRANSPOSE = 2.5, EXTRA_DOUBLED = 2.2, EXTRA_NEIGHBOR = 3.2, EXTRA_OTHER = 4.5;
    private static final double MISSED_DOUBLED = 2.2, MISSED_OTHER = 3.8, APOSTROPHE = .4;
    private static final double BOUND = 9.5;
    /** An unknown typed word is replaced when log(count) minus the edit cost of the best word reaches this. */
    private static final double UNKNOWN_THRESHOLD = 4.7;
    /** Short unknown text is often an acronym or a name, so it needs more evidence before it is replaced. */
    private static final double UNKNOWN_EXTRA_2 = 3, UNKNOWN_EXTRA_3 = 6, UNKNOWN_EXTRA_4 = 2;
    /** The largest edit cost allowed for typed text of up to 4 letters and up to 6 letters. Each allows about one edit. */
    private static final double BOUND_SHORT = 4.6, BOUND_MEDIUM = 7.6;
    /** A known typed word is replaced only by a word that is this much more likely, after the edit cost. */
    private static final double KNOWN_THRESHOLD = 6, KNOWN_APOSTROPHE_THRESHOLD = 3.5;
    private static final double KNOWN_DOUBLED_THRESHOLD = 5.5;
    private static final long KNOWN_MAX_COUNT = 1500, USER_COUNT = 5000;
    private static final double COMPLETION_BASE = 1.0, COMPLETION_PER_LETTER = .35;

    // Glide weights. The values were fitted on recorded human swipes. See bench/TypingBench.java.
    private static final double GLIDE_START = 5.28, GLIDE_END = 1.848, GLIDE_KEY = 2.2, GLIDE_PATH = .549, GLIDE_FREQUENCY = .66;
    /** How far the first and last points may be from the first and last keys of a word, in key widths. */
    private static final double GLIDE_REACH = 2.5;
    /** Weight of the difference between the gesture length and the length of the straight path through the keys. */
    private static final double GLIDE_LENGTH = 3;
    /** Weight of a sharp turn that is far from the keys of a word, and the smallest turn that counts, in degrees. */
    private static final double GLIDE_CORNER = 2, GLIDE_CORNER_ANGLE = 50;

    private static final Set<String> TWO_LETTER_WORDS = new HashSet<>(Arrays.asList((
        "ad ah ai am an as at aw ax be bi bo by da do eh el em er ex fa go ha he hi hm ho id if in is it la lo ma me mm my no of oh ok on oo op or ow ox pa pi re so ta to uh um up us we ya ye yo "
        + "tv uk dr mr ms pm vs ps pc cd dj ny dc hr ft lb oz cm km kg mg ml co jr sr st mt vp pr hq iq tb gb mb ui os pe ed cc bf gf eu un").split(" ")));

    public EnglishDictionary(InputStream words, InputStream additions) throws IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> spelling = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(words, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] pair = line.split("\t"); if (pair.length < 2) continue;
                // The subtitle list holds stray single letters. Only "a" and "i" are words.
                if (pair[0].length() == 1 && !pair[0].equals("a") && !pair[0].equals("i")) continue;
                // It also holds hundreds of two letter scanning errors, such as "lf" for "If".
                if (pair[0].length() == 2 && !TWO_LETTER_WORDS.contains(pair[0])) continue;
                counts.put(pair[0], Long.parseLong(pair[1])); spelling.put(pair[0], pair[0]);
            }
        }
        if (additions != null) try (BufferedReader in = new BufferedReader(new InputStreamReader(additions, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] pair = line.split("\t"); String word = pair[0], lower = word.toLowerCase(Locale.US), previous = spelling.get(lower);
                if (pair.length == 1) {
                    // Capitalization only. Keep the count from the word list.
                    if (previous == null) continue;
                    long count = counts.remove(previous); counts.put(word, count); spelling.put(lower, word);
                } else {
                    long count = Long.parseLong(pair[1]);
                    if (previous != null) { counts.remove(previous); spelling.remove(lower); }
                    if (count > 0) { counts.put(word, count); spelling.put(lower, word); }
                }
            }
        }
        List<Entry> list = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> e : counts.entrySet()) { Entry entry = new Entry(e.getKey(), e.getValue()); if (!entry.plain.isEmpty() && entry.plain.length() <= 26) list.add(entry); }
        list.sort((a, b) -> Long.compare(b.count, a.count));
        entries = list.toArray(new Entry[0]);
        List<List<Entry>> buckets = new ArrayList<>();
        for (int i = 0; i < 26; i++) buckets.add(new ArrayList<>());
        for (Entry e : entries) {
            exact.put(e.word.toLowerCase(Locale.US), e); plainWords.add(e.plain);
            if (e.keys.length >= 2) buckets.get(e.keys[0]).add(e);
        }
        for (int i = 0; i < 26; i++) byFirstKey[i] = buckets.get(i).toArray(new Entry[0]);
    }

    /** Replaces the words the user has taught the keyboard. They are never corrected and can be suggested. */
    public void setUserWords(Collection<String> words) {
        Map<String, Entry> map = new HashMap<>();
        for (String word : words) {
            String lower = word.toLowerCase(Locale.US);
            if (word.isEmpty() || exact.containsKey(lower)) continue;
            Entry entry = new Entry(word, USER_COUNT);
            if (!entry.plain.isEmpty() && entry.plain.length() <= 26) map.put(lower, entry);
        }
        userExact = map; userEntries = map.values().toArray(new Entry[0]);
    }
    /** True when the word, written with its apostrophes, is in the word list or was taught by the user. */
    public boolean contains(String word) { String lower = word.toLowerCase(Locale.US); return exact.containsKey(lower) || userExact.containsKey(lower); }
    /** True when some word in the list has exactly these letters, ignoring apostrophes. */
    public boolean containsLetters(String word) { return plainWords.contains(letters(word)); }

    static String letters(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) { char c = Character.toLowerCase(text.charAt(i)); if (c >= 'a' && c <= 'z') b.append(c); }
        return b.toString();
    }
    /** Applies the capitals of the typed text to a dictionary word. Words with their own capitals keep them. */
    static String matchCase(String text, String model) {
        if (text.isEmpty()) return text;
        if (model.length() > 1 && model.equals(model.toUpperCase(Locale.US)) && !model.equals(model.toLowerCase(Locale.US))) return text.toUpperCase(Locale.US);
        if (!text.equals(text.toLowerCase(Locale.US))) return text;
        if (!model.isEmpty() && Character.isUpperCase(model.charAt(0))) return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text;
    }

    private static final class Ranked {
        final Entry entry; final double score;
        Ranked(Entry entry, double score) { this.entry = entry; this.score = score; }
    }
    /** Keeps the highest scores seen, in order. */
    private static void keep(Ranked[] top, Entry entry, double score) {
        int n = top.length;
        if (top[n - 1] != null && top[n - 1].score >= score) return;
        int i = n - 1;
        while (i > 0 && (top[i - 1] == null || top[i - 1].score < score)) { top[i] = top[i - 1]; i--; }
        top[i] = new Ranked(entry, score);
    }

    /**
     * Finds suggestions for the letters typed so far and decides whether a word boundary should replace them.
     * taps holds the x and y of the touch for each typed letter, or null when the positions are unknown.
     */
    public Analysis analyze(String typed, float[] taps, Layout layout) {
        String lower = typed.toLowerCase(Locale.US), plain = letters(typed);
        int n = plain.length();
        if (n == 0 || n > 26) return new Analysis(new String[]{typed}, null);
        if (taps != null && (layout == null || taps.length != n * 2)) taps = null;
        Entry same = exact.get(lower); boolean taught = false;
        if (same == null) { same = userExact.get(lower); taught = same != null; }
        // Capitals after the first letter mark an acronym or a name. Leave that text alone.
        boolean literal = typed.length() > 1 && !typed.substring(1).equals(typed.substring(1).toLowerCase(Locale.US));

        double[][] substitute = substitutionCosts(plain, taps, layout);
        double[] extra = extraCosts(plain, layout);
        int typedMask = 0; for (int i = 0; i < n; i++) typedMask |= 1 << (plain.charAt(i) - 'a');
        double[] a = new double[30], b = new double[30], c = new double[30];
        Ranked[] corrections = new Ranked[3], strip = new Ranked[4];
        Entry[] user = userEntries;
        for (int index = 0; index < entries.length + user.length; index++) {
            Entry e = index < entries.length ? entries[index] : user[index - entries.length];
            if (e == same) continue;
            int m = e.plain.length(); double gain = Double.NEGATIVE_INFINITY, shown = Double.NEGATIVE_INFINITY;
            if (m > n && e.plain.startsWith(plain)) shown = e.logCount - COMPLETION_BASE - COMPLETION_PER_LETTER * (m - n);
            if (m == n && e.plain.equals(plain)) gain = e.logCount - APOSTROPHE;
            else if (Math.abs(m - n) <= 2 && n >= 2 && Integer.bitCount(typedMask ^ e.mask) <= 4) {
                double bound = Math.min(n <= 4 ? BOUND_SHORT : n <= 6 ? BOUND_MEDIUM : BOUND, e.logCount);
                double cost = editCost(plain, e.plain, substitute, extra, bound, a, b, c);
                if (cost < Double.MAX_VALUE) gain = e.logCount - cost;
            }
            if (gain > Double.NEGATIVE_INFINITY) keep(corrections, e, gain);
            if (Math.max(gain, shown) > Double.NEGATIVE_INFINITY) keep(strip, e, Math.max(gain, shown));
        }

        String correction = null;
        if (!literal && !taught && corrections[0] != null) {
            Entry best = corrections[0].entry; double gain = corrections[0].score;
            if (same == null) {
                if (gain >= UNKNOWN_THRESHOLD + (n <= 2 ? UNKNOWN_EXTRA_2 : n == 3 ? UNKNOWN_EXTRA_3 : n == 4 ? UNKNOWN_EXTRA_4 : 0)) correction = best.word;
            } else if (best.plain.equals(plain)) {
                if (gain - same.logCount >= KNOWN_APOSTROPHE_THRESHOLD - APOSTROPHE) correction = best.word;
            } else if (same.count < KNOWN_MAX_COUNT) {
                // The word list holds some rare misspellings. Replace one only when a far more common word is very close.
                if (best.plain.length() == n && gain - same.logCount >= KNOWN_THRESHOLD) correction = best.word;
                else for (Ranked r : corrections) {
                    // The same letters with one doubled or undoubled, such as "helo" for "hello".
                    if (r != null && Arrays.equals(r.entry.keys, same.keys) && r.entry.logCount - same.logCount >= KNOWN_DOUBLED_THRESHOLD) { correction = r.entry.word; break; }
                }
            }
        }
        // A known word can still need its capitals, such as "i" or "monday".
        if (correction == null && !literal && same != null && !same.word.equals(lower) && typed.equals(lower)) correction = same.word;
        if (correction != null) { correction = matchCase(correction, typed); if (correction.equals(typed)) correction = null; }

        LinkedHashSet<String> shown = new LinkedHashSet<>();
        shown.add(typed);
        if (correction != null) shown.add(correction);
        for (Ranked r : strip) { if (r == null || shown.size() >= 3) break; shown.add(matchCase(r.entry.word, typed)); }
        return new Analysis(shown.toArray(new String[0]), correction);
    }

    /** Cost of reading typed letter i as each other letter. Zero for the typed letter itself. */
    private static double[][] substitutionCosts(String plain, float[] taps, Layout layout) {
        int n = plain.length(); double[][] cost = new double[n][26];
        for (int i = 0; i < n; i++) {
            int typed = plain.charAt(i) - 'a';
            for (int k = 0; k < 26; k++) {
                if (k == typed) continue;
                // Swapping one vowel for another is a common spelling mistake, wherever the keys are.
                double most = isVowel(typed) && isVowel(k) ? SUBSTITUTE_VOWEL : SUBSTITUTE_MAX, value = most;
                if (layout != null && layout.present[typed] && layout.present[k]) {
                    if (taps != null) {
                        // Compare how far the touch was from each key. A touch near the shared edge costs little.
                        double difference = layout.tapDistance(taps[i * 2], taps[i * 2 + 1], k) - layout.tapDistance(taps[i * 2], taps[i * 2 + 1], typed);
                        value = difference / (2 * TAP_SIGMA * TAP_SIGMA);
                    } else {
                        // Without the touch, use the average cost of slipping onto a neighboring key.
                        double distance = layout.tapDistance(layout.x[typed], layout.y[typed], k);
                        value = distance < 1.05 ? 2.9 : distance < 1.6 ? 3.6 : most;
                    }
                }
                cost[i][k] = Math.max(SUBSTITUTE_MIN, Math.min(most, value)) + (i == 0 ? FIRST_LETTER : 0);
            }
        }
        return cost;
    }
    private static boolean isVowel(int key) { return key == 0 || key == 4 || key == 8 || key == 14 || key == 20; }
    /** Cost of treating typed letter i as an accidental extra press. */
    private static double[] extraCosts(String plain, Layout layout) {
        int n = plain.length(); double[] cost = new double[n];
        for (int i = 0; i < n; i++) {
            int key = plain.charAt(i) - 'a';
            if (i > 0 && plain.charAt(i - 1) == plain.charAt(i)) { cost[i] = EXTRA_DOUBLED; continue; }
            boolean neighbor = false;
            if (layout != null && layout.present[key]) for (int j = i - 1; j <= i + 1; j += 2) {
                if (j < 0 || j >= n) continue;
                int other = plain.charAt(j) - 'a';
                if (layout.present[other] && layout.tapDistance(layout.x[key], layout.y[key], other) < 1.6) neighbor = true;
            }
            cost[i] = neighbor ? EXTRA_NEIGHBOR : EXTRA_OTHER;
        }
        return cost;
    }
    /** Weighted edit distance from the typed letters to a word. Returns Double.MAX_VALUE above the bound. */
    private static double editCost(String typed, String word, double[][] substitute, double[] extra, double bound, double[] previous2, double[] previous, double[] row) {
        int n = typed.length(), m = word.length();
        previous[0] = 0;
        for (int j = 1; j <= m; j++) previous[j] = previous[j - 1] + missedCost(word, j - 1);
        for (int i = 1; i <= n; i++) {
            char t = typed.charAt(i - 1);
            row[0] = previous[0] + extra[i - 1]; double least = row[0];
            for (int j = 1; j <= m; j++) {
                char w = word.charAt(j - 1);
                double best = previous[j - 1] + (t == w ? 0 : substitute[i - 1][w - 'a']);
                best = Math.min(best, previous[j] + extra[i - 1]);
                best = Math.min(best, row[j - 1] + missedCost(word, j - 1));
                if (i > 1 && j > 1 && t != w && t == word.charAt(j - 2) && typed.charAt(i - 2) == w) best = Math.min(best, previous2[j - 2] + TRANSPOSE);
                row[j] = best; if (best < least) least = best;
            }
            if (least > bound) return Double.MAX_VALUE;
            double[] reuse = previous2; previous2 = previous; previous = row; row = reuse;
        }
        return previous[m] > bound ? Double.MAX_VALUE : previous[m];
    }
    private static double missedCost(String word, int j) {
        if (j > 0 && word.charAt(j) == word.charAt(j - 1)) return MISSED_DOUBLED;
        return MISSED_OTHER + (j == 0 ? FIRST_LETTER : 0);
    }

    /**
     * Decodes a glide. path holds x and y pairs in pixels. The result lists up to three words, best first.
     */
    public List<String> glide(float[] path, Layout layout) {
        if (path.length < 4) return Collections.emptyList();
        // Work in units of one key width, with the same scale on both axes.
        float unit = layout.keyWidth; int count = path.length / 2;
        double[] px = new double[count], py = new double[count]; double length = 0;
        for (int i = 0; i < count; i++) {
            px[i] = path[i * 2] / unit; py[i] = path[i * 2 + 1] / unit;
            if (i > 0) length += Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
        }
        int n = Math.max(12, Math.min(96, (int) Math.ceil(length / .2) + 1));
        double[] gx = new double[n], gy = new double[n];
        resample(px, py, length, gx, gy);
        double step = length / (n - 1);
        double[] kx = new double[26], ky = new double[26];
        double[][] keyDistance = new double[26][];
        for (int k = 0; k < 26; k++) {
            if (!layout.present[k]) continue;
            kx[k] = layout.x[k] / unit; ky[k] = layout.y[k] / unit;
            double[] d = new double[n];
            for (int i = 0; i < n; i++) { double dx = gx[i] - kx[k], dy = gy[i] - ky[k]; d[i] = dx * dx + dy * dy; }
            keyDistance[k] = d;
        }
        double[] corner = corners(gx, gy, step);
        double[][] segmentDistance = new double[26 * 26][];
        double reach = GLIDE_REACH * GLIDE_REACH;
        Ranked[] top = new Ranked[6];
        double[] f = new double[32];
        Entry[] user = userEntries;
        for (int firstKey = 0; firstKey <= 26; firstKey++) {
            Entry[] bucket;
            if (firstKey == 26) bucket = user;
            else { if (keyDistance[firstKey] == null || keyDistance[firstKey][0] > reach) continue; bucket = byFirstKey[firstKey]; }
            for (Entry e : bucket) {
                byte[] keys = e.keys; int m = keys.length;
                if (m < 2 || m > 30) continue;
                if (keyDistance[keys[0]] == null || keyDistance[keys[0]][0] > reach) continue;
                double[] lastDistance = keyDistance[keys[m - 1]];
                if (lastDistance == null || lastDistance[n - 1] > reach) continue;
                double ideal = 0; boolean valid = true;
                for (int j = 1; j < m; j++) {
                    if (keyDistance[keys[j]] == null) { valid = false; break; }
                    ideal += Math.hypot(kx[keys[j]] - kx[keys[j - 1]], ky[keys[j]] - ky[keys[j - 1]]);
                }
                if (!valid || ideal > length * 1.9 + 1.5 || length > ideal * 2.4 + 2.5) continue;
                // A score can only fall as the path cost grows, so stop when this word cannot reach the kept list.
                double prior = GLIDE_FREQUENCY * e.logCount - GLIDE_LENGTH * Math.abs(Math.log((length + 1) / (ideal + 1))), limit = top[top.length - 1] == null ? Double.MAX_VALUE : prior - top[top.length - 1].score;
                double cost = glideCost(keys, n, step, keyDistance, segmentDistance, corner, gx, gy, kx, ky, f, limit);
                if (cost < limit) keep(top, e, prior - cost);
            }
        }
        List<String> results = new ArrayList<>();
        for (Ranked r : top) { if (r == null || results.size() == 3) break; if (!results.contains(r.entry.word)) results.add(r.entry.word); }
        return results;
    }
    /**
     * Matches the gesture to a word's keys in order. Each point belongs to the stretch between two neighboring keys.
     * The cost adds the distance of every point from its stretch and the distance of the path from each key.
     */
    private static double glideCost(byte[] keys, int n, double step, double[][] keyDistance, double[][] segmentDistance, double[] corner, double[] gx, double[] gy, double[] kx, double[] ky, double[] f, double limit) {
        int stretches = keys.length - 1;
        double[][] segments = new double[stretches][];
        for (int j = 0; j < stretches; j++) {
            int id = keys[j] * 26 + keys[j + 1]; double[] d = segmentDistance[id];
            if (d == null) {
                d = new double[n];
                double ax = kx[keys[j]], ay = ky[keys[j]], bx = kx[keys[j + 1]] - ax, by = ky[keys[j + 1]] - ay, squared = bx * bx + by * by;
                for (int i = 0; i < n; i++) {
                    double t = Math.max(0, Math.min(1, ((gx[i] - ax) * bx + (gy[i] - ay) * by) / squared));
                    double dx = gx[i] - ax - t * bx, dy = gy[i] - ay - t * by; d[i] = dx * dx + dy * dy;
                }
                segmentDistance[id] = d;
            }
            segments[j] = d;
        }
        double path = GLIDE_PATH * step;
        Arrays.fill(f, 0, stretches, Double.MAX_VALUE);
        f[0] = GLIDE_START * keyDistance[keys[0]][0] + path * segments[0][0];
        for (int i = 1; i < n; i++) {
            double carried = Double.MAX_VALUE, least = Double.MAX_VALUE;
            for (int j = 0; j < stretches; j++) {
                double value = f[j];
                if (j > 0 && carried < Double.MAX_VALUE) value = Math.min(value, carried + GLIDE_KEY * keyDistance[keys[j]][i]);
                carried = value;
                if (value < Double.MAX_VALUE) {
                    value += path * segments[j][i];
                    // A sharp turn marks a letter. Charge the word when neither end of this stretch is near the turn.
                    if (corner[i] > 0) value += corner[i] * Math.min(keyDistance[keys[j]][i], keyDistance[keys[j + 1]][i]);
                    if (value < least) least = value;
                }
                f[j] = value;
            }
            if (least > limit) return Double.MAX_VALUE;
        }
        double last = f[stretches - 1];
        return last == Double.MAX_VALUE ? last : last + GLIDE_END * keyDistance[keys[keys.length - 1]][n - 1];
    }
    /** Finds sharp turns in the resampled gesture. The result holds a weight for each point, and zero where the path runs on. */
    private static double[] corners(double[] gx, double[] gy, double step) {
        int n = gx.length, window = Math.max(1, (int) Math.round(.45 / Math.max(step, 1e-6)));
        double[] turn = new double[n], result = new double[n];
        for (int i = window; i < n - window; i++) {
            double ax = gx[i] - gx[i - window], ay = gy[i] - gy[i - window], bx = gx[i + window] - gx[i], by = gy[i + window] - gy[i];
            double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
            if (la < 1e-6 || lb < 1e-6) continue;
            double angle = Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / (la * lb))));
            turn[i] = Math.max(0, Math.min(1, (angle - Math.toRadians(GLIDE_CORNER_ANGLE)) / Math.toRadians(80)));
        }
        for (int i = window; i < n - window; i++) {
            if (turn[i] <= 0) continue;
            boolean peak = true;
            for (int j = Math.max(0, i - window); j <= Math.min(n - 1, i + window) && peak; j++) if (turn[j] > turn[i] || turn[j] == turn[i] && j < i) peak = false;
            if (peak) result[i] = GLIDE_CORNER * turn[i];
        }
        return result;
    }
    private static void resample(double[] px, double[] py, double total, double[] outX, double[] outY) {
        int n = outX.length, segment = 1; double walked = 0;
        for (int i = 0; i < n; i++) {
            double target = total * i / (n - 1);
            while (segment < px.length - 1 && walked + Math.hypot(px[segment] - px[segment - 1], py[segment] - py[segment - 1]) < target) {
                walked += Math.hypot(px[segment] - px[segment - 1], py[segment] - py[segment - 1]); segment++;
            }
            double span = Math.hypot(px[segment] - px[segment - 1], py[segment] - py[segment - 1]);
            double t = span == 0 ? 0 : Math.max(0, Math.min(1, (target - walked) / span));
            outX[i] = px[segment - 1] + t * (px[segment] - px[segment - 1]); outY[i] = py[segment - 1] + t * (py[segment] - py[segment - 1]);
        }
    }
}
