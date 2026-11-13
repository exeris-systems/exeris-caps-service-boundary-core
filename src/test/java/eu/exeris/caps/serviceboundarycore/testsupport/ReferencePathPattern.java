package eu.exeris.caps.serviceboundarycore.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference for what a route pattern matches, built only from the documented definition.
 *
 * <p>The definition: a parameter is written {@code {name}} with a name of letters, digits and underscores, and
 * stands for one or more characters other than {@code /}; every other character of the pattern is literal text.
 * A pattern and a path are both normalised before they are compared: surrounding white space (every character up
 * to a space) is removed, a leading slash is added when missing, and one trailing slash is dropped from a result
 * longer than one character. A request path is cut at its first {@code ?} before that. A path matches when the
 * whole normalised path is matched by the regular expression made from the normalised pattern by quoting each
 * literal text with {@link Pattern#quote} and writing {@code ([^/]+)} for each parameter, anchored at both ends.
 * The value of each parameter is what its group captured; when a name occurs twice the value of the later group is
 * kept, at the position of the earlier name.
 */
public final class ReferencePathPattern {

    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static final Pattern LEADING_SPACE = Pattern.compile("\\A[\\u0000-\\u0020]+");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[\\u0000-\\u0020]+\\z");

    private final String normalisedPattern;
    private final List<String> names;
    private final Pattern regex;

    public ReferencePathPattern(String pattern) {
        this.normalisedPattern = normalise(pattern);
        List<String> found = new ArrayList<>();
        StringBuilder expression = new StringBuilder("^");
        Matcher tokens = TOKEN.matcher(normalisedPattern);
        int last = 0;
        while (tokens.find()) {
            expression.append(Pattern.quote(normalisedPattern.substring(last, tokens.start())));
            expression.append("([^/]+)");
            found.add(tokens.group(1));
            last = tokens.end();
        }
        expression.append(Pattern.quote(normalisedPattern.substring(last))).append('$');
        this.names = List.copyOf(found);
        this.regex = Pattern.compile(expression.toString());
    }

    /** The normalisation of the documented rule, for a path or a pattern. */
    public static String normalise(String text) {
        String stripped = LEADING_SPACE.matcher(text).replaceFirst("");
        stripped = TRAILING_SPACE.matcher(stripped).replaceFirst("");
        if (!stripped.startsWith("/")) {
            stripped = "/" + stripped;
        }
        if (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    /** A request path without its query string: everything before the first {@code ?}. */
    public static String withoutQuery(String requestPath) {
        int question = requestPath.indexOf('?');
        return question < 0 ? requestPath : requestPath.substring(0, question);
    }

    public String normalisedPattern() {
        return normalisedPattern;
    }

    /** The parameter names in pattern order, a repeated name repeated. */
    public List<String> names() {
        return names;
    }

    public boolean matches(String path) {
        return regex.matcher(normalise(path)).matches();
    }

    /**
     * The parameters of a path in order of first occurrence of each name, each with the value of its last
     * occurrence, or {@code null} when the path does not match.
     */
    public LinkedHashMap<String, String> parameters(String path) {
        Matcher matcher = regex.matcher(normalise(path));
        if (!matcher.matches()) {
            return null;
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            values.put(names.get(i), matcher.group(i + 1));
        }
        return values;
    }

    /** Renders a string with every character outside printable ASCII as a {@code \\uXXXX} escape. */
    public static String show(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ' ' || c > '~') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
