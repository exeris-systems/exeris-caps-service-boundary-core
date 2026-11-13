package eu.exeris.caps.serviceboundarycore.internal;

import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.caps.serviceboundarycore.api.RouteMatch;
import eu.exeris.caps.serviceboundarycore.testsupport.Fixtures;
import eu.exeris.caps.serviceboundarycore.testsupport.ReferencePathPattern;
import eu.exeris.caps.serviceboundarycore.testsupport.ScriptedExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.RouteRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route a request is served by, and the 404 or 405 it is answered with when none is, equal an independent model
 * of the documented rule: among the routes of the request's method, one without parameters that equals the path
 * wins; otherwise the first registered route with parameters that matches the path. When none serves the method, the
 * methods of every registered route that matches the path, in registration order, are the {@code Allow} value.
 */
@DisplayName("Route selection follows the documented rule, in every registration order")
class RouteSelectionModelTest {

    private record Spec(int id, HttpMethod method, String pattern, boolean secured, ReferencePathPattern reference) {
        boolean hasParameters() {
            return !reference.names().isEmpty();
        }

        String shape() {
            return Pattern.compile("\\{[a-zA-Z0-9_]+\\}").matcher(reference.normalisedPattern()).replaceAll("{}");
        }
    }

    private static final String[] PATTERNS = {
            "/", "/a", "/a/b", "/a/{x}", "/{x}", "/{x}/b", "/a/{x}/c", "/{x}/{y}", "/a/b/{x}", "/a/{x}-{y}",
            "/a/{x}.{y}", "/{x}{y}", "/a/{x}/", "/a/{p}", "/{p}/b", "/a/b/c", "/a/{x}{y}", "/{x}/{y}/{z}"};

    private static final HttpMethod[] METHODS = {HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT};

    private static final String[] REQUEST_PATHS = {
            "/", "/a", "/a/", "/b", "/a/b", "/a/b/", "/a/b?x=1", "/a/1", "/a/1/c", "/a/b/c", "/a/b/d", "/1/b", "/x/y", "/x/y/z",
            "/a/x-y", "/a/x.y", "/a/x-y-z", "/a/xy", "/xy", " /a/b ", "a/b", "/a/%2F", "/a//", "/a/1/c/", "/a/1?/c", "//",
            "/a/b/c?z", "/c/b", "/-/b", "/a/-", "/a/-/c"};

    private final List<String> served = Collections.synchronizedList(new ArrayList<>());

    private Spec spec(int id, HttpMethod method, String pattern, boolean secured) {
        return new Spec(id, method, pattern, secured, new ReferencePathPattern(pattern));
    }

    private ApiSurfaceRoute route(Spec spec) {
        return Fixtures.route(spec.method(), spec.pattern(),
                List.of(spec.secured() ? RouteRequirement.authenticated() : RouteRequirement.permitAll()),
                ex -> {
                    served.add("route " + spec.id());
                    ex.respond(HttpStatus.OK);
                });
    }

    // ---- the model ---------------------------------------------------------------------------------------------

    private static Spec select(List<Spec> registered, HttpMethod method, String requestPath) {
        String cut = ReferencePathPattern.withoutQuery(requestPath);
        String path = ReferencePathPattern.normalise(cut);
        for (Spec spec : registered) {
            if (spec.method() == method && !spec.hasParameters() && spec.reference().normalisedPattern().equals(path)) {
                return spec;
            }
        }
        for (Spec spec : registered) {
            if (spec.method() == method && spec.hasParameters() && spec.reference().matches(cut)) {
                return spec;
            }
        }
        return null;
    }

    private static String allow(List<Spec> registered, String requestPath) {
        Set<String> methods = new LinkedHashSet<>();
        for (Spec spec : registered) {
            if (spec.reference().matches(ReferencePathPattern.withoutQuery(requestPath))) {
                methods.add(spec.method().name());
            }
        }
        return methods.isEmpty() ? null : String.join(", ", methods);
    }

    private static String allowHeader(ScriptedExchange exchange) {
        return exchange.responses().get(0).headers().stream()
                .filter(h -> h.name().equalsIgnoreCase("Allow")).map(HttpHeader::value).findFirst().orElse(null);
    }

    // ---- the comparison ----------------------------------------------------------------------------------------

    /** Registers the specs in order, as far as the registry allows, and returns those that were registered. */
    private List<Spec> register(DefaultApiSurfaceRegistry registry, List<Spec> specs) {
        List<Spec> registered = new ArrayList<>();
        for (Spec spec : specs) {
            boolean duplicate = registered.stream()
                    .anyMatch(r -> r.method() == spec.method() && r.shape().equals(spec.shape()));
            if (duplicate) {
                try {
                    registry.registerRoute(route(spec));
                    throw new AssertionError("a second route of one method and shape was registered: " + spec);
                } catch (IllegalStateException expected) {
                    continue;
                }
            }
            registry.registerRoute(route(spec));
            registered.add(spec);
        }
        return registered;
    }

    private long compareRequests(DefaultApiSurfaceRegistry registry, List<Spec> registered, List<Spec> order) {
        long checked = 0;
        for (HttpMethod method : METHODS) {
            for (String path : REQUEST_PATHS) {
                Spec expected = select(registered, method, path);
                String context = "registered " + order.stream().map(s -> s.id() + ":" + s.method() + " " + s.pattern()
                        + (s.secured() ? " secured" : "")).toList() + " request " + method + " " + path;

                Optional<RouteMatch> match = registry.matchRoute(method, path);
                if (expected == null) {
                    assertThat(match).as(context).isEmpty();
                } else {
                    assertThat(match).as(context).isPresent();
                    assertThat(match.get().route().pathPattern()).as(context).isEqualTo(expected.reference().normalisedPattern());
                    assertThat(match.get().route().method()).as(context).isEqualTo(method);
                    assertThat(match.get().pathParams()).as(context).isEqualTo(
                            expected.reference().parameters(ReferencePathPattern.withoutQuery(path)) == null ? Map.of()
                                    : expected.reference().parameters(ReferencePathPattern.withoutQuery(path)));
                }

                served.clear();
                ScriptedExchange exchange = ScriptedExchange.of(method, path, List.of());
                registry.handle(exchange);
                if (expected != null) {
                    HttpStatus status = expected.secured() ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
                    assertThat(exchange.statuses()).as(context).containsExactly(status);
                    assertThat(served).as(context).isEqualTo(expected.secured() ? List.of() : List.of("route " + expected.id()));
                } else {
                    String allow = allow(registered, path);
                    assertThat(served).as(context).isEmpty();
                    if (allow == null) {
                        assertThat(exchange.statuses()).as(context).containsExactly(HttpStatus.NOT_FOUND);
                    } else {
                        assertThat(exchange.statuses()).as(context).containsExactly(HttpStatus.METHOD_NOT_ALLOWED);
                        assertThat(allowHeader(exchange)).as(context).isEqualTo(allow);
                    }
                }
                checked++;
            }
        }
        return checked;
    }

    @Test
    @DisplayName("random sets of routes in random registration order: the selected route, the status and the Allow value equal the model")
    void randomSetsMatchTheModel() {
        SplittableRandom random = new SplittableRandom(2024);
        long checked = 0;
        int sets = 1_500;
        for (int round = 0; round < sets; round++) {
            int count = 2 + random.nextInt(9);
            List<Spec> specs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                specs.add(spec(i, METHODS[random.nextInt(METHODS.length)], PATTERNS[random.nextInt(PATTERNS.length)],
                        random.nextInt(100) < 35));
            }
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            List<Spec> registered = register(registry, specs);
            assertThat(registry.routes()).extracting(ApiSurfaceRoute::pathPattern)
                    .containsExactlyElementsOf(registered.stream().map(s -> s.reference().normalisedPattern()).toList());
            checked += compareRequests(registry, registered, specs);
        }
        System.out.println("route selection: " + sets + " random route sets, " + checked + " requests compared with the model");
        assertThat(checked).isEqualTo((long) sets * METHODS.length * REQUEST_PATHS.length);
    }

    private static void permutations(List<Spec> rest, List<Spec> chosen, List<List<Spec>> out) {
        if (rest.isEmpty()) {
            out.add(List.copyOf(chosen));
            return;
        }
        for (int i = 0; i < rest.size(); i++) {
            List<Spec> next = new ArrayList<>(rest);
            Spec taken = next.remove(i);
            chosen.add(taken);
            permutations(next, chosen, out);
            chosen.remove(chosen.size() - 1);
        }
    }

    @Test
    @DisplayName("every registration order of overlapping literal, parameterised and secured routes selects the model's route")
    void everyOrderOfOverlappingRoutes() {
        List<Spec> routes = List.of(
                spec(0, HttpMethod.GET, "/a/b", false),
                spec(1, HttpMethod.GET, "/a/{x}", false),
                spec(2, HttpMethod.GET, "/{x}/b", true),
                spec(3, HttpMethod.POST, "/a/{x}", false),
                spec(4, HttpMethod.GET, "/{x}/{y}", false),
                spec(5, HttpMethod.PUT, "/a/b", true));
        List<List<Spec>> orders = new ArrayList<>();
        permutations(routes, new ArrayList<>(), orders);
        assertThat(orders).hasSize(720);
        long checked = 0;
        for (List<Spec> order : orders) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            List<Spec> registered = register(registry, order);
            assertThat(registered).hasSameSizeAs(order);
            checked += compareRequests(registry, registered, order);
        }
        System.out.println("route selection: 720 registration orders of six routes, " + checked + " requests compared with the model");
    }

    @Test
    @DisplayName("a secured literal route is never replaced by an anonymous parameterised route that matches the same path, in either registration order")
    void securedLiteralBeatsAnonymousParameterised() {
        for (boolean literalFirst : new boolean[] {true, false}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            Spec literal = spec(0, HttpMethod.GET, "/admin/users", true);
            Spec open = spec(1, HttpMethod.GET, "/admin/{section}", false);
            for (Spec s : literalFirst ? List.of(literal, open) : List.of(open, literal)) {
                registry.registerRoute(route(s));
            }
            for (String path : new String[] {"/admin/users", "/admin/users/", "/admin/users?x=1", " /admin/users "}) {
                served.clear();
                ScriptedExchange exchange = ScriptedExchange.get(path);
                registry.handle(exchange);
                assertThat(exchange.statuses()).as("literal first %s, %s", literalFirst, path).containsExactly(HttpStatus.UNAUTHORIZED);
                assertThat(served).as("the anonymous handler did not run").isEmpty();
            }
            served.clear();
            ScriptedExchange other = ScriptedExchange.get("/admin/other");
            registry.handle(other);
            assertThat(other.statuses()).as("a path only the parameterised route matches").containsExactly(HttpStatus.OK);
            assertThat(served).containsExactly("route 1");
        }
    }

    @Test
    @DisplayName("an anonymous literal route is never replaced by a secured parameterised route that matches the same path, in either registration order")
    void anonymousLiteralBeatsSecuredParameterised() {
        for (boolean literalFirst : new boolean[] {true, false}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            Spec literal = spec(0, HttpMethod.GET, "/docs/public", false);
            Spec secured = spec(1, HttpMethod.GET, "/docs/{name}", true);
            for (Spec s : literalFirst ? List.of(literal, secured) : List.of(secured, literal)) {
                registry.registerRoute(route(s));
            }
            served.clear();
            ScriptedExchange exchange = ScriptedExchange.get("/docs/public");
            registry.handle(exchange);
            assertThat(exchange.statuses()).as("literal first %s", literalFirst).containsExactly(HttpStatus.OK);
            assertThat(served).containsExactly("route 0");
            ScriptedExchange other = ScriptedExchange.get("/docs/private");
            registry.handle(other);
            assertThat(other.statuses()).containsExactly(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("among routes with parameters the first registered that matches serves the request, and a route of another method is not a candidate")
    void firstRegisteredParameterisedRouteWins() {
        for (boolean wideFirst : new boolean[] {true, false}) {
            DefaultApiSurfaceRegistry registry = new DefaultApiSurfaceRegistry();
            Spec wide = spec(0, HttpMethod.GET, "/{a}/{b}", false);
            Spec narrow = spec(1, HttpMethod.GET, "/x/{b}", false);
            Spec otherMethod = spec(2, HttpMethod.POST, "/x/{b}", false);
            for (Spec s : wideFirst ? List.of(otherMethod, wide, narrow) : List.of(otherMethod, narrow, wide)) {
                registry.registerRoute(route(s));
            }
            served.clear();
            registry.handle(ScriptedExchange.get("/x/1"));
            assertThat(served).containsExactly(wideFirst ? "route 0" : "route 1");
        }
    }
}
