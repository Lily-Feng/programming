import java.util.List;
import java.util.Map;

/**
 * Modern Java: language and API changes that are final (not preview) as of Java 21.
 *
 * <p>Feature availability, with the release that made each one final:
 * <ul>
 *   <li>{@code var} for local variables — Java 10</li>
 *   <li>immutable collection factories ({@code List.of}, {@code Map.of}) — Java 9</li>
 *   <li>switch expressions with {@code yield} — Java 14</li>
 *   <li>text blocks — Java 15</li>
 *   <li>pattern matching for {@code instanceof} — Java 16</li>
 *   <li>records — Java 16</li>
 *   <li>sealed types — Java 17</li>
 *   <li>pattern matching for {@code switch}, record patterns — Java 21</li>
 * </ul>
 *
 * <p>The module system (Java 9) is not shown here: it needs a {@code module-info.java}
 * and a multi-file build, so it belongs under {@code ../07-testing-and-build}.
 *
 * <p>Requires Java 21. Run with: {@code java ModernJava.java}
 */
public final class ModernJava {

    public static void main(String[] args) {
        localVariableTypeInference();
        switchExpressions();
        textBlocks();
        recordsAndPatterns();
        exhaustiveSwitchOverSealedTypes();
        immutableFactories();
    }

    // --- var (Java 10) --------------------------------------------------------

    private static void localVariableTypeInference() {
        var totals = new java.util.LinkedHashMap<String, Integer>(); // no repeated type argument
        totals.put("read", 3);
        totals.put("write", 1);

        var sum = 0;
        for (var entry : totals.entrySet()) {
            sum += entry.getValue();
        }

        check("var infers the initializer's static type", sum == 4);
        check("inference does not erase the declared generics", totals.get("read") == 3);
    }

    // --- switch expressions (Java 14) ----------------------------------------

    enum Signal { RED, AMBER, GREEN }

    private static int secondsToWait(Signal signal) {
        return switch (signal) {          // an expression: exhaustive, no fall-through
            case RED -> 30;
            case AMBER -> 5;
            case GREEN -> {
                int base = 0;
                yield base;               // a block arm produces its value with yield
            }
        };
    }

    private static void switchExpressions() {
        check("arrow arms cannot fall through", secondsToWait(Signal.AMBER) == 5);
        check("block arms yield a value", secondsToWait(Signal.GREEN) == 0);
    }

    // --- text blocks (Java 15) ------------------------------------------------

    private static void textBlocks() {
        String json = """
                {
                  "name": "modern-java",
                  "release": 21
                }""";

        check("incidental leading whitespace is stripped", json.startsWith("{\n  \"name\""));
        check("no trailing newline unless the closing delimiter is on its own line",
                !json.endsWith("\n"));
    }

    // --- records and pattern matching (Java 16 / 21) --------------------------

    record Point(int x, int y) {
        Point {                                  // compact constructor: validate, then assign
            if (x < 0 || y < 0) throw new IllegalArgumentException("quadrant I only");
        }

        Point translated(int dx, int dy) { return new Point(x + dx, y + dy); }
    }

    record Line(Point from, Point to) { }

    private static String classify(Object value) {
        if (value instanceof Line(Point(var x1, var y1), Point(var x2, var y2))) { // record pattern
            return x1 == x2 || y1 == y2 ? "axis-aligned" : "diagonal";
        }
        if (value instanceof String text && !text.isBlank()) { // pattern variable plus a guard
            return "text:" + text.length();
        }
        return "other";
    }

    private static void recordsAndPatterns() {
        Point origin = new Point(0, 0);

        check("records generate value-based equals", origin.equals(new Point(0, 0)));
        check("records generate a matching hashCode", origin.hashCode() == new Point(0, 0).hashCode());
        check("records generate a readable toString", origin.toString().equals("Point[x=0, y=0]"));
        check("accessors are named after the components", origin.translated(2, 0).x() == 2);

        try {
            new Point(-1, 0);
            check("the compact constructor validates before assignment", false);
        } catch (IllegalArgumentException expected) {
            check("the compact constructor validates before assignment", true);
        }

        check("record patterns destructure nested components",
                classify(new Line(new Point(0, 0), new Point(3, 0))).equals("axis-aligned"));
        check("a guarded type pattern binds and tests in one step",
                classify("hello").equals("text:5"));
    }

    // --- sealed types (Java 17) plus switch patterns (Java 21) -----------------

    sealed interface Shape permits Circle, Rectangle, Square { }

    record Circle(double radius) implements Shape { }
    record Rectangle(double width, double height) implements Shape { }
    record Square(double side) implements Shape { }

    private static double area(Shape shape) {
        return switch (shape) {                       // no default: the compiler checks exhaustiveness
            case Circle c -> Math.PI * c.radius() * c.radius();
            case Rectangle(double w, double h) -> w * h;
            case Square(double side) -> side * side;
        };
    }

    private static void exhaustiveSwitchOverSealedTypes() {
        check("sealed permits make the switch exhaustive without a default",
                area(new Rectangle(2, 3)) == 6.0);
        check("record patterns work in switch arms too", area(new Square(4)) == 16.0);
        check("the permitted set is known at compile time",
                Shape.class.getPermittedSubclasses().length == 3);
    }

    // --- immutable collection factories (Java 9) -------------------------------

    private static void immutableFactories() {
        List<String> stages = List.of("learn", "practice", "build");
        Map<String, Integer> weights = Map.of("learn", 1, "practice", 2);

        check("factory collections reject mutation", isUnmodifiable(stages));
        check("factory collections reject null elements", rejectsNull());
        check("Map.of has no defined iteration order guarantee, only contents",
                weights.get("practice") == 2);
    }

    private static boolean isUnmodifiable(List<String> list) {
        try {
            list.add("mutate");
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    private static boolean rejectsNull() {
        try {
            List.of("a", null);
            return false;
        } catch (NullPointerException expected) {
            return true;
        }
    }

    // --- tiny assertion helper (assertions are off by default) -----------------

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
