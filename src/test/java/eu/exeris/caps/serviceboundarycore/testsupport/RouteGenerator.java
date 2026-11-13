package eu.exeris.caps.serviceboundarycore.testsupport;

import java.util.SplittableRandom;

/**
 * Seeded generators of route patterns and request paths over a small alphabet chosen to make literal texts occur
 * inside parameter values, to put separators next to each other, and to put surrogates on every boundary.
 */
public final class RouteGenerator {

    /** The characters of generated paths; a lone high and a lone low surrogate make a pair when adjacent. */
    public static final String[] ALPHABET = {
            "/", ".", "-", "a", "b", "{", "}", "?", " ", "\t", "\n", "%", "é", "😀", "\uD83D", "\uDE00"};

    private static final String[] LITERALS = {
            "", "", "", "/", "/", "/", "-", ".", "a", "b", "/a", "/a/", "a-", "-a", ".-", "/x/", "x", "ab", "/-/", "--",
            "/files/", "/api/v1/", ".*+?()[]\\^$|", ".*", "[a-z]", "\\E", "\\Q", "\\", "$", "^", "|", "(a)", "{", "}", "{}",
            "{a", "a}", "{a-b}", "{ }", "?", "%", " ", "é", "😀", "\uD83D", "\uDE00", "😀-",
            "-\uD83D", "\uDE00-"};

    private static final String[] NAMES = {"a", "b", "c", "id", "x_1", "A"};

    private static final String[] VALUE_PIECES = {
            "a", "b", "ab", "-", ".", "--", "a-b", "a.b", "x", "1", "%2F", "é", "😀", "\uD83D", "\uDE00",
            "{", "}", "{a}", "?", " ", "\t", "-a-", "\n"};

    private final SplittableRandom random;

    public RouteGenerator(long seed) {
        this.random = new SplittableRandom(seed);
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    private String pick(String[] from) {
        return from[random.nextInt(from.length)];
    }

    /** A pattern with 0 to 4 parameters, possibly adjacent or repeated, separated by literal texts. */
    public String pattern() {
        int roll = random.nextInt(100);
        int parameters = roll < 15 ? 0 : roll < 40 ? 1 : roll < 70 ? 2 : roll < 90 ? 3 : 4;
        StringBuilder out = new StringBuilder();
        if (chance(90)) {
            out.append('/');
        } else if (chance(30)) {
            out.append(pick(new String[] {" ", "\t", " /", "\n/"}));
        }
        for (int i = 0; i <= parameters; i++) {
            int pieces = random.nextInt(3);
            for (int p = 0; p < pieces; p++) {
                out.append(pick(LITERALS));
            }
            if (i < parameters) {
                String name = chance(25) ? "a" : pick(NAMES);
                out.append('{').append(name).append('}');
            }
        }
        if (chance(15)) {
            out.append('/');
        }
        if (chance(5)) {
            out.append(pick(new String[] {" ", "\t", "\n", "  "}));
        }
        return out.toString();
    }

    /** A path made of random alphabet characters. */
    public String randomPath() {
        int length = random.nextInt(15);
        StringBuilder out = new StringBuilder();
        if (chance(70)) {
            out.append('/');
        }
        for (int i = 0; i < length; i++) {
            out.append(pick(ALPHABET));
        }
        return out.toString();
    }

    /** The pattern with each parameter replaced by a value, then possibly disturbed. */
    public String derivedPath(String pattern) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            int close;
            if (pattern.charAt(i) == '{' && (close = pattern.indexOf('}', i)) > i + 1
                    && pattern.substring(i + 1, close).matches("[a-zA-Z0-9_]+")) {
                out.append(value());
                i = close + 1;
            } else {
                out.append(pattern.charAt(i));
                i++;
            }
        }
        return disturb(out.toString());
    }

    private String value() {
        int pieces = 1 + random.nextInt(3);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pieces; i++) {
            out.append(chance(3) ? "/" : pick(VALUE_PIECES));
        }
        return out.toString();
    }

    private String disturb(String path) {
        StringBuilder out = new StringBuilder(path);
        int roll = random.nextInt(100);
        if (roll < 50) {
            return path;
        }
        if (roll < 62) {
            out.append('/');
        } else if (roll < 70) {
            out.insert(0, pick(new String[] {" ", "\t", "\n", "  "}));
        } else if (roll < 78) {
            out.append(pick(new String[] {" ", "\t", "\n", " /"}));
        } else if (roll < 86) {
            out.append(pick(new String[] {"?", "?x=1", "?/a", "?a/b/", "?{a}"}));
        } else if (roll < 91 && out.length() > 0) {
            out.deleteCharAt(random.nextInt(out.length()));
        } else if (roll < 96) {
            out.insert(random.nextInt(out.length() + 1), pick(ALPHABET));
        } else if (out.length() > 0) {
            out.setCharAt(random.nextInt(out.length()), pick(ALPHABET).charAt(0));
        }
        return out.toString();
    }

    /** A path for a pattern: half of them derived from it, half random. */
    public String path(String pattern) {
        return chance(65) ? derivedPath(pattern) : randomPath();
    }
}
