package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Matching a route without path parameters on the path in place")
class LiteralPathMatchingTest {

    // ---- the normalisation, restated from the README's description ------------------------------------------------

    /** The path as the old pipeline cut it: everything before the first question mark. */
    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /** Trim every character up to a space at both ends, add a leading slash, drop one trailing slash if longer than one. */
    private static String normalised(String path) {
        int from = 0;
        int to = path.length();
        while (from < to && path.charAt(from) <= ' ') {
            from++;
        }
        while (to > from && path.charAt(to - 1) <= ' ') {
            to--;
        }
        StringBuilder result = new StringBuilder();
        if (from == to || path.charAt(from) != '/') {
            result.append('/');
        }
        result.append(path, from, to);
        if (result.length() > 1 && result.charAt(result.length() - 1) == '/') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    /** What the old code decided for a literal route: its matchesPath on the query-stripped path. */
    private static boolean oldDecision(ApiSurfaceRoute route, String path) {
        String stripped = stripQuery(path);
        boolean viaRoute = route.matchesPath(stripped);
        boolean viaEquality = route.pathPattern().equals(normalised(stripped));
        assertThat(viaEquality).as("equality of the restated normalisation for %s vs %s", route.pathPattern(), quote(path))
                .isEqualTo(viaRoute);
        return viaRoute;
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c < ' ' || c > '~') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static final String[] PATTERNS = {
            "/health", "/", "/Health", "/health/x", "/a/b", "/a%2F", "/a/..", "/.", "/..", "/ü", "/a b", "/a#b",
            "/health%2F", "//", "/a//b", "/x?y", "/a.b", "/ż/ó", "/HEALTH", "/h", "/health/", "health", " /padded ", ""};

    private static final String[] TOKENS = {
            "/", "/", "/", "?", "?", " ", "\t", "#", "%2F", "..", ".", "//", "health", "Health", "HEALTH", "a", "b",
            "ü", "ż", "", "\n", "\r", "\u0000", " ", " ", "x=1", "a b", "x", "h"};

    private static final String[] WHITESPACE = {"", "", "", " ", "  ", "\t", "\n", "\u0000", "\u001f", " "};

    private static String randomPath(Random random, String[] patterns) {
        if (random.nextBoolean()) {
            StringBuilder soup = new StringBuilder();
            int count = random.nextInt(7);
            for (int i = 0; i < count; i++) {
                soup.append(TOKENS[random.nextInt(TOKENS.length)]);
            }
            return soup.toString();
        }
        String base = patterns[random.nextInt(patterns.length)];
        if (random.nextInt(3) == 0 && !base.isEmpty()) {
            base = base.substring(1);
        }
        StringBuilder path = new StringBuilder();
        path.append(WHITESPACE[random.nextInt(WHITESPACE.length)]).append(base);
        int slashes = random.nextInt(4) == 0 ? random.nextInt(3) : 0;
        path.append("/".repeat(slashes));
        path.append(WHITESPACE[random.nextInt(WHITESPACE.length)]);
        if (random.nextInt(3) == 0) {
            path.append('?').append(TOKENS[random.nextInt(TOKENS.length)]).append(TOKENS[random.nextInt(TOKENS.length)]);
        }
        if (random.nextInt(8) == 0) {
            path.append(WHITESPACE[random.nextInt(WHITESPACE.length)]);
        }
        return path.toString();
    }

    @Test
    @DisplayName("over 500000 seeded pattern and path pairs the registry decides as the old normalisation did")
    void differentialAgainstOldNormalisation() {
        List<ApiSurfaceRoute> routes = new ArrayList<>();
        List<DefaultApiSurfaceRegistry> registries = new ArrayList<>();
        for (String pattern : PATTERNS) {
            ApiSurfaceRoute route = Fixtures.open(pattern);
            routes.add(route);
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            registry.registerRoute(route);
            registries.add(registry);
        }
        Random random = new Random(20260512L);
        long hits = 0;
        long misses = 0;
        int pairs = 600_000;
        for (int i = 0; i < pairs; i++) {
            int index = random.nextInt(PATTERNS.length);
            String path = randomPath(random, PATTERNS);
            boolean expected = oldDecision(routes.get(index), path);
            Optional<RouteMatch> actual = registries.get(index).matchRoute(HttpMethod.GET, path);
            assertThat(actual.isPresent()).as("pattern %s path %s", quote(routes.get(index).pathPattern()), quote(path))
                    .isEqualTo(expected);
            if (expected) {
                hits++;
                assertThat(actual.get().route()).isSameAs(routes.get(index));
            } else {
                misses++;
            }
        }
        assertThat(hits).as("the sample exercises matches").isGreaterThan(20_000);
        assertThat(misses).as("the sample exercises misses").isGreaterThan(20_000);
    }

    @Test
    @DisplayName("over route pairs, one protected and one anonymous, a path selects the route the old normalisation selected")
    void protectedRouteNeverTradedForAnonymousOne() {
        List<String> pool = List.of("/admin", "/Admin", "/admin/x", "/", "/a b", "/admin%2F", "/admin/..", "/ü", "/.", "/public");
        Random random = new Random(7L);
        long protectedHits = 0;
        long anonymousHits = 0;
        for (String protectedPattern : pool) {
            for (String anonymousPattern : pool) {
                if (ApiSurfaceRoute.builder().pathPattern(protectedPattern).handler(Fixtures.ok()).build().pathPattern()
                        .equals(ApiSurfaceRoute.builder().pathPattern(anonymousPattern).handler(Fixtures.ok()).build().pathPattern())) {
                    continue;
                }
                ApiSurfaceRoute secured = Fixtures.route(HttpMethod.GET, protectedPattern,
                        List.of(RouteRequirement.authenticated()), Fixtures.ok());
                ApiSurfaceRoute anonymous = Fixtures.route(HttpMethod.GET, anonymousPattern,
                        List.of(RouteRequirement.permitAll()), Fixtures.ok());
                DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
                registry.registerRoute(secured);
                registry.registerRoute(anonymous);
                DefaultApiSurfaceRegistry reversed = new DefaultApiSurfaceRegistry();
                reversed.registerRoute(anonymous);
                reversed.registerRoute(secured);
                for (int i = 0; i < 4_000; i++) {
                    String path = randomPath(random, pool.toArray(new String[0]));
                    ApiSurfaceRoute expected = oldDecision(secured, path) ? secured
                            : oldDecision(anonymous, path) ? anonymous : null;
                    for (DefaultApiSurfaceRegistry candidate : List.of(registry, reversed)) {
                        Optional<RouteMatch> actual = candidate.matchRoute(HttpMethod.GET, path);
                        assertThat(actual.map(RouteMatch::route).orElse(null))
                                .as("protected %s anonymous %s path %s", protectedPattern, anonymousPattern, quote(path))
                                .isSameAs(expected);
                    }
                    if (expected == secured) {
                        protectedHits++;
                        ScriptedExchange exchange = ScriptedExchange.get(path);
                        registry.handle(exchange);
                        assertThat(exchange.statuses()).as("path %s", quote(path)).containsExactly(HttpStatus.UNAUTHORIZED);
                    } else if (expected == anonymous) {
                        anonymousHits++;
                    }
                }
            }
        }
        assertThat(protectedHits).isGreaterThan(1_000);
        assertThat(anonymousHits).isGreaterThan(1_000);
    }

    // ---- named cases --------------------------------------------------------------------------------------------

    private record Case(String path, boolean health, boolean root) { }

    private static final List<Case> NAMED = List.of(
            new Case("/health", true, false),
            new Case("/health/", true, false),
            new Case("/health//", false, false),
            new Case("/health?x=1", true, false),
            new Case("/health/?x=1", true, false),
            new Case("/health?", true, false),
            new Case("?x", false, true),
            new Case(" /health ", true, false),
            new Case("health", true, false),
            new Case("", false, true),
            new Case("/", false, true),
            new Case("//", false, true),
            new Case("///", false, false),
            new Case("/Health", false, false),
            new Case("/health%2F", false, false),
            new Case("/health/..", false, false),
            new Case("/health#frag", false, false),
            new Case("/health\t", true, false),
            new Case("\n/health/\r", true, false),
            new Case("/health ", false, false),
            new Case(" ", false, true),
            new Case("/ ", false, true),
            new Case("/health /", false, false),
            new Case("/health/?/health", true, false),
            new Case("x?/health", false, false));

    @Test
    @DisplayName("the named paths select the same route as the old normalisation and the 404 and 405 answers are unchanged")
    void namedCases() {
        ApiSurfaceRoute health = Fixtures.open("/health");
        ApiSurfaceRoute root = Fixtures.open("/");
        ApiSurfaceRoute healthPost = Fixtures.route(HttpMethod.POST, "/health", List.of(RouteRequirement.permitAll()), Fixtures.ok());
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        registry.registerRoute(health);
        registry.registerRoute(root);
        DefaultApiSurfaceRegistry postOnly = new DefaultApiSurfaceRegistry();
        postOnly.registerRoute(healthPost);
        for (Case c : NAMED) {
            Optional<RouteMatch> match = registry.matchRoute(HttpMethod.GET, c.path());
            ApiSurfaceRoute expected = c.health() ? health : c.root() ? root : null;
            assertThat(match.map(RouteMatch::route).orElse(null)).as(quote(c.path())).isSameAs(expected);
            assertThat(oldDecision(health, c.path())).as("old, health, " + quote(c.path())).isEqualTo(c.health());
            assertThat(oldDecision(root, c.path())).as("old, root, " + quote(c.path())).isEqualTo(c.root());

            ScriptedExchange exchange = new ScriptedExchange(
                    eu.exeris.kernel.spi.http.HttpRequest.noBody(HttpMethod.GET, c.path(),
                            eu.exeris.kernel.spi.http.HttpVersion.HTTP_1_1, List.of()));
            postOnly.handle(exchange);
            if (c.health()) {
                assertThat(exchange.statuses()).as(quote(c.path())).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
                HttpResponse response = exchange.responses().get(0);
                assertThat(response.headers()).extracting(HttpHeader::name, HttpHeader::value)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("Allow", "POST"));
            } else if (c.root()) {
                assertThat(exchange.statuses()).as(quote(c.path())).containsExactly(HttpStatus.NOT_FOUND);
            } else {
                assertThat(exchange.statuses()).as(quote(c.path())).containsExactly(HttpStatus.NOT_FOUND);
            }
        }
    }

    @Test
    @DisplayName("the 404, 405 and matched statuses over seeded paths equal the old pipeline's, with the same Allow header")
    void answersEqualTheOldPipeline() {
        ApiSurfaceRoute getHealth = Fixtures.route(HttpMethod.GET, "/health", List.of(RouteRequirement.permitAll()),
                ex -> ex.respond(HttpStatus.OK));
        ApiSurfaceRoute postHealth = Fixtures.route(HttpMethod.POST, "/health", List.of(RouteRequirement.permitAll()),
                ex -> ex.respond(HttpStatus.CREATED));
        ApiSurfaceRoute item = Fixtures.route(HttpMethod.GET, "/items/{id}", List.of(RouteRequirement.permitAll()),
                ex -> ex.respond(HttpStatus.ACCEPTED));
        ApiSurfaceRoute putOther = Fixtures.route(HttpMethod.PUT, "/other/", List.of(RouteRequirement.permitAll()),
                ex -> ex.respond(HttpStatus.NO_CONTENT));
        ApiSurfaceRoute getRoot = Fixtures.route(HttpMethod.GET, "/", List.of(RouteRequirement.permitAll()),
                ex -> ex.respond(HttpStatus.PARTIAL_CONTENT));
        List<ApiSurfaceRoute> routes = List.of(getHealth, postHealth, item, putOther, getRoot);
        DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
        routes.forEach(registry::registerRoute);
        String[] patterns = {"/health", "/items/1", "/items/", "/other", "/", "/items/a/b"};
        HttpMethod[] methods = {HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE};
        Random random = new Random(99L);
        int allows = 0;
        for (int i = 0; i < 60_000; i++) {
            String path = randomPath(random, patterns);
            HttpMethod method = methods[random.nextInt(methods.length)];
            HttpStatus expected;
            String allow = null;
            String stripped = stripQuery(path);
            ApiSurfaceRoute literal = null;
            ApiSurfaceRoute parameterised = null;
            for (ApiSurfaceRoute route : routes) {
                if (route.method() == method && route.matchesPath(stripped)) {
                    if (route.paramNames().isEmpty()) {
                        literal = route;
                        break;
                    } else if (parameterised == null) {
                        parameterised = route;
                    }
                }
            }
            ApiSurfaceRoute selected = literal != null ? literal : parameterised;
            if (selected != null) {
                expected = selected == getHealth ? HttpStatus.OK : selected == postHealth ? HttpStatus.CREATED
                        : selected == item ? HttpStatus.ACCEPTED : selected == putOther ? HttpStatus.NO_CONTENT
                        : HttpStatus.PARTIAL_CONTENT;
            } else {
                Set<String> allowed = new LinkedHashSet<>();
                for (ApiSurfaceRoute route : routes) {
                    if (route.matchesPath(stripped)) {
                        allowed.add(route.method().name());
                    }
                }
                expected = allowed.isEmpty() ? HttpStatus.NOT_FOUND : HttpStatus.METHOD_NOT_ALLOWED;
                allow = allowed.isEmpty() ? null : String.join(", ", allowed);
            }
            ScriptedExchange exchange = new ScriptedExchange(eu.exeris.kernel.spi.http.HttpRequest.noBody(method, path,
                    eu.exeris.kernel.spi.http.HttpVersion.HTTP_1_1, List.of()));
            registry.handle(exchange);
            assertThat(exchange.statuses()).as("%s %s", method, quote(path)).containsExactly(expected);
            if (allow != null) {
                allows++;
                assertThat(exchange.responses().get(0).headers()).extracting(HttpHeader::name, HttpHeader::value)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("Allow", allow));
            }
        }
        assertThat(allows).isGreaterThan(1_000);
    }
}
