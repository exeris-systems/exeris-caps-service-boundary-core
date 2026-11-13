package eu.exeris.caps.serviceboundarycore.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A route's path pattern compiled for matching a request path in place.
 *
 * <p>The pattern is split once, at construction, into {@code n + 1} literal texts around {@code n} parameters:
 * {@code literal[0] {p0} literal[1] {p1} ... {p(n-1)} literal[n]}. A parameter is written {@code {name}} with a
 * name of letters, digits and underscores; every other character, a stray brace included, is literal text.
 * Matching never uses {@code java.util.regex}; this class uses it only at construction, to find the parameters.
 *
 * <p>A parameter stands for one or more characters, none of them {@code /}, with no decoding, and is greedy: of
 * the ways to divide the path between the parameters and the literal texts, the match is the first one found by
 * giving each parameter in turn the longest value for which the rest matches. That is the meaning of the
 * expression {@code ^} + the quoted literal text + {@code ([^/]+)} + ... + {@code $}, whose unit is the code
 * point. Every place where a literal text meets a parameter value (the start of the pattern, a literal text
 * before and after a value, two adjacent values) is therefore a code point boundary of the path: it lies
 * between two code points, never between the two halves of a surrogate pair. A literal text is compared to the
 * path char by char between two such boundaries, so a lone surrogate in a literal text matches only a lone
 * surrogate in the path, and a value neither starts nor ends inside a pair. The end of the path is a boundary.
 *
 * <p>The time to match a request is polynomial in the length of the path segment that the parameters share. A
 * route with one parameter in each segment is linear in the path. A route whose parameters can share text,
 * that is parameters that are adjacent or are separated by a literal text that may also occur inside a value,
 * tries the divisions of that text between them: the time grows with a degree up to the number of such
 * parameters. A route author keeps it small by writing one parameter per segment.
 *
 * <p>The path is normalised before it is compared: surrounding white space (every character up to a space) is
 * trimmed, a leading slash is added when missing and one trailing slash is dropped from a result longer than one
 * character. The normalisation is {@link #region}, shared with the matching of a route without parameters; the
 * normalised string itself is never built.
 *
 * <p>Matching a path that does not match allocates nothing. A path that matches allocates the parameter values
 * and the one immutable map that holds them; see {@link #match}.
 *
 * @since 0.1.0
 */
public final class PathPattern {

    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    /** The most distinct parameter names for which the value map is built by {@code Map.of} without entries. */
    private static final int INLINE_LIMIT = 4;

    private final String[] literals;
    private final List<String> names;
    /** The distinct names, in order of last occurrence. */
    private final String[] distinctNames;
    /** For each parameter, its slot in {@code distinctNames} when it is the last of its name, else -1. */
    private final int[] slotOf;

    /**
     * Compiles a pattern that is already normalised: it starts with {@code /}.
     *
     * @param pattern the normalised path pattern; non-null
     */
    public PathPattern(String pattern) {
        List<String> texts = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
        Matcher matcher = PARAMETER.matcher(pattern);
        int lastEnd = 0;
        while (matcher.find()) {
            texts.add(pattern.substring(lastEnd, matcher.start()));
            parameterNames.add(matcher.group(1));
            lastEnd = matcher.end();
        }
        texts.add(pattern.substring(lastEnd));
        this.literals = texts.toArray(new String[0]);
        this.names = Collections.unmodifiableList(parameterNames);

        int count = parameterNames.size();
        this.slotOf = new int[count];
        List<String> distinct = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = parameterNames.get(i);
            boolean last = !parameterNames.subList(i + 1, count).contains(name);
            slotOf[i] = last ? distinct.size() : -1;
            if (last) {
                distinct.add(name);
            }
        }
        this.distinctNames = distinct.toArray(new String[0]);
    }

    /** The parameter names in pattern order, a repeated name repeated. */
    public List<String> names() {
        return names;
    }

    /**
     * The region of {@code path[0, end)} that is the normalised path, packed into one {@code long} so that
     * nothing is allocated: the low 32 bits are the index of its first character and the high 32 bits the
     * index after its last one, once the trailing slash has been dropped. The normalised path is that region,
     * preceded by a slash when the region does not start with one; the region starts with a slash exactly when
     * it is non-empty and its first character is one.
     */
    public static long region(String path, int end) {
        int from = 0;
        while (from < end && path.charAt(from) <= ' ') {
            from++;
        }
        int to = end;
        while (to > from && path.charAt(to - 1) <= ' ') {
            to--;
        }
        boolean leadingSlash = to > from && path.charAt(from) == '/';
        int length = (to - from) + (leadingSlash ? 0 : 1);
        if (length > 1 && path.charAt(to - 1) == '/') {
            to--;
        }
        return ((long) to << 32) | from;
    }

    public static int regionStart(long region) {
        return (int) region;
    }

    public static int regionLimit(long region) {
        return (int) (region >>> 32);
    }

    /** Whether the region starts with a slash of its own, so that the normalisation adds none. */
    public static boolean startsWithSlash(String path, long region) {
        int from = regionStart(region);
        return regionLimit(region) > from && path.charAt(from) == '/';
    }

    /**
     * Whether {@code path[0, end)} matches this pattern. Allocates nothing.
     *
     * @param path the request path, possibly followed by a query string that {@code end} excludes
     * @param end  the index after the last character that belongs to the path
     */
    public boolean matches(String path, int end) {
        long region = region(path, end);
        return matchesRegion(path, region);
    }

    /**
     * The path parameters of {@code path[0, end)}, or {@code null} when it does not match this pattern. A
     * pattern without parameters yields the shared empty map. When two parameters have the same name the later
     * value is the one kept.
     *
     * <p>A miss allocates nothing. A hit allocates one {@code String} per distinct parameter name (a repeated
     * name is read once, for its last occurrence) and one immutable map: a {@code Map.of} with up to four
     * entries, which is the map object and, from two entries on, its table array. Each value is a substring
     * because the request path is a {@code String} and the exposed map holds {@code String}s; the map is the
     * immutable holder that {@code RouteMatch} and the exchange accept without a further copy. With more than
     * four distinct names the map is built in a {@code HashMap} and copied, which costs that map's nodes and table and the copy.
     */
    public Map<String, String> match(String path, int end) {
        long region = region(path, end);
        if (!matchesRegion(path, region)) {
            return null;
        }
        if (names.isEmpty()) {
            return Map.of();
        }
        int limit = regionLimit(region);
        int pos = startPosition(path, region);
        String v0 = null;
        String v1 = null;
        String v2 = null;
        String v3 = null;
        String[] more = distinctNames.length > INLINE_LIMIT ? new String[distinctNames.length] : null;
        for (int k = 0; k < slotOf.length; k++) {
            int stop = parameterEnd(path, limit, k, pos);
            int slot = slotOf[k];
            if (slot >= 0) {
                String value = path.substring(pos, stop);
                if (more != null) {
                    more[slot] = value;
                } else {
                    switch (slot) {
                        case 0 -> v0 = value;
                        case 1 -> v1 = value;
                        case 2 -> v2 = value;
                        default -> v3 = value;
                    }
                }
            }
            pos = stop + literals[k + 1].length();
        }
        if (more != null) {
            Map<String, String> collected = new HashMap<>(distinctNames.length * 2);
            for (int i = 0; i < more.length; i++) {
                collected.put(distinctNames[i], more[i]);
            }
            return Map.copyOf(collected);
        }
        return switch (distinctNames.length) {
            case 1 -> Map.of(distinctNames[0], v0);
            case 2 -> Map.of(distinctNames[0], v0, distinctNames[1], v1);
            case 3 -> Map.of(distinctNames[0], v0, distinctNames[1], v1, distinctNames[2], v2);
            default -> Map.of(distinctNames[0], v0, distinctNames[1], v1, distinctNames[2], v2,
                    distinctNames[3], v3);
        };
    }

    /** The index in the path where the first parameter, or the end, starts, after the first literal text. */
    private int startPosition(String path, long region) {
        int start = regionStart(region);
        return start + literals[0].length() - (startsWithSlash(path, region) ? 0 : 1);
    }

    private boolean matchesRegion(String path, long region) {
        int from = regionStart(region);
        int limit = regionLimit(region);
        // The first literal begins with the slash the normalisation adds when the region has none of its own.
        int skipped = startsWithSlash(path, region) ? 0 : 1;
        String first = literals[0];
        int length = first.length() - skipped;
        if (from + length > limit || !path.regionMatches(from, first, skipped, length)
                || splitsPair(path, limit, from + length)) {
            return false;
        }
        return matchParameters(path, limit, 0, from + length);
    }

    /**
     * Whether parameters {@code k..n-1}, each followed by its literal text, match from {@code pos} to
     * {@code limit}. Each parameter takes the longest value for which the rest matches.
     */
    private boolean matchParameters(String path, int limit, int k, int pos) {
        if (k == slotOf.length) {
            return pos == limit;
        }
        String next = literals[k + 1];
        int length = next.length();
        for (int stop = valueLimit(path, limit, pos); stop > pos; stop--) {
            if (literalAt(path, limit, stop, next)
                    && matchParameters(path, limit, k + 1, stop + length)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The end of parameter {@code k}'s value in a path that is known to match: the first, longest, end for which
     * the rest matches, which is the end {@link #matchParameters} found.
     */
    private int parameterEnd(String path, int limit, int k, int pos) {
        String next = literals[k + 1];
        int length = next.length();
        for (int stop = valueLimit(path, limit, pos); stop > pos; stop--) {
            if (literalAt(path, limit, stop, next)
                    && matchParameters(path, limit, k + 1, stop + length)) {
                return stop;
            }
        }
        throw new IllegalStateException("A path that matched has no value for parameter " + k);
    }

    /** The index of the first {@code /} at or after {@code pos}, or {@code limit}: no value reaches past it. */
    private static int valueLimit(String path, int limit, int pos) {
        int slash = path.indexOf('/', pos);
        return slash < 0 || slash > limit ? limit : slash;
    }

    /**
     * Whether {@code literal} occurs in {@code path} at {@code at} within {@code limit}, between two code point
     * boundaries: the value before it ends at {@code at} and the value after it starts where it ends.
     */
    private static boolean literalAt(String path, int limit, int at, String literal) {
        int end = at + literal.length();
        return end <= limit
                && !splitsPair(path, limit, at)
                && path.regionMatches(at, literal, 0, literal.length())
                && !splitsPair(path, limit, end);
    }

    /**
     * Whether {@code index}, inside the path that ends at {@code limit}, is between the two halves of a
     * surrogate pair. The start and the end of the path are boundaries; the characters before the path are
     * white space.
     */
    private static boolean splitsPair(String path, int limit, int index) {
        return index > 0 && index < limit
                && Character.isHighSurrogate(path.charAt(index - 1))
                && Character.isLowSurrogate(path.charAt(index));
    }
}
