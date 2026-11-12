package eu.exeris.caps.serviceboundarycore.testsupport;

import java.util.List;

/**
 * Compares instances by reference without calling {@code equals}, {@code hashCode} or {@code toString} on them,
 * so that an assertion about a registry does not itself touch the methods whose calls a test counts.
 */
public final class Identity {

    private Identity() { }

    /** Fails unless {@code actual} holds exactly the given instances, in that order. */
    public static void assertSameInstances(List<?> actual, Object... expected) {
        boolean same = actual.size() == expected.length;
        for (int i = 0; same && i < expected.length; i++) {
            same = actual.get(i) == expected[i];
        }
        if (!same) {
            throw new AssertionError("expected the instances " + describe(List.of(expected)) + " but found "
                    + describe(actual));
        }
    }

    private static String describe(List<?> instances) {
        StringBuilder text = new StringBuilder("[");
        for (Object instance : instances) {
            text.append(instance.getClass().getSimpleName()).append('@')
                    .append(Integer.toHexString(System.identityHashCode(instance))).append(' ');
        }
        return text.append(']').toString();
    }
}
