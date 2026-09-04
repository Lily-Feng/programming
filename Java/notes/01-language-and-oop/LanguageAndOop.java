import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Language and object-oriented programming.
 *
 * <p>Each method isolates one semantic distinction:
 * <ul>
 *   <li>values, references, parameter passing, conversions, operators</li>
 *   <li>initialization order across static blocks, instance blocks, constructors</li>
 *   <li>overriding (dynamic) versus overloading, field and static-method hiding</li>
 *   <li>composition, polymorphism, covariant returns, default methods</li>
 *   <li>equality, hashing, immutability, defensive copying</li>
 * </ul>
 *
 * <p>Requires Java 11 or later. Run with: {@code java LanguageAndOop.java}
 */
public final class LanguageAndOop {

    public static void main(String[] args) {
        valuesAndReferences();
        conversionsAndOperators();
        initializationOrder();
        dispatchAndHiding();
        polymorphismAndCovariantReturns();
        equalityAndImmutability();
    }

    // --- values, references, parameter passing ------------------------------

    private static void reassign(int primitive, int[] reference) {
        primitive++;      // copy of the caller's value
        reference[0]++;   // the caller's array, reached through a copied reference
    }

    private static void valuesAndReferences() {
        int counter = 1;
        int[] boxed = {1};
        reassign(counter, boxed);

        check("arguments are always passed by value", counter == 1);
        check("a copied reference still reaches the same object", boxed[0] == 2);
    }

    // --- conversions and operators ------------------------------------------

    private static void conversionsAndOperators() {
        check("int arithmetic wraps silently", Integer.MAX_VALUE + 1 == Integer.MIN_VALUE);
        check("narrowing casts truncate high bits", (byte) 130 == -126);
        check("binary floating point is not decimal", 0.1 + 0.2 != 0.3);
        check("integer division truncates toward zero", -7 / 2 == -3);
        check("remainder keeps the dividend's sign", -7 % 2 == -1);

        Integer cachedLow = 127, cachedLowAgain = 127;
        Integer boxedHigh = 128, boxedHighAgain = 128;
        check("small Integers come from a cache", cachedLow == cachedLowAgain);
        check("larger Integers are distinct objects", boxedHigh != boxedHighAgain);
        check("equals compares the value, not the identity", boxedHigh.equals(boxedHighAgain));
    }

    // --- initialization order ------------------------------------------------

    static final List<String> TRACE = new ArrayList<>();

    static class Base {
        static { TRACE.add("Base static"); }
        { TRACE.add("Base instance"); }

        Base() {
            TRACE.add("Base constructor");
            describe(); // dispatches to the subclass before its fields are assigned
        }

        void describe() { TRACE.add("Base.describe"); }
    }

    static class Derived extends Base {
        static { TRACE.add("Derived static"); }
        { TRACE.add("Derived instance"); }

        // Not a constant variable: a String field initialized with a literal would be
        // inlined by javac and would read "derived" even here.
        private final List<String> traits = List.of("loud");

        Derived() {
            super();
            TRACE.add("Derived constructor");
        }

        @Override
        void describe() { TRACE.add("Derived.describe sees traits=" + traits); }
    }

    private static void initializationOrder() {
        TRACE.clear();
        new Derived();

        check("static initializers run once, outermost first",
                TRACE.indexOf("Base static") < TRACE.indexOf("Derived static"));
        check("superclass construction completes before subclass initializers",
                TRACE.indexOf("Base constructor") < TRACE.indexOf("Derived instance"));
        check("instance initializers run before the constructor body",
                TRACE.indexOf("Derived instance") < TRACE.indexOf("Derived constructor"));
        check("a call out of a superclass constructor sees unassigned subclass fields",
                TRACE.contains("Derived.describe sees traits=null"));
    }

    // --- overriding, overloading, hiding -------------------------------------

    static class Animal {
        String kind = "animal";
        static String staticKind() { return "animal"; }
        String speak() { return "..."; }
    }

    static class Dog extends Animal {
        String kind = "dog";                       // hides, does not override
        static String staticKind() { return "dog"; } // hides, not dispatched
        @Override String speak() { return "woof"; }
    }

    private static String overloaded(Object value) { return "Object"; }
    private static String overloaded(String value) { return "String"; }

    private static void dispatchAndHiding() {
        Animal asAnimal = new Dog();

        check("instance methods dispatch on the runtime type", asAnimal.speak().equals("woof"));
        check("fields resolve on the static type", asAnimal.kind.equals("animal"));
        check("the runtime field still exists", ((Dog) asAnimal).kind.equals("dog"));

        Object asObject = "text";
        check("overloads resolve at compile time", overloaded(asObject).equals("Object"));
        check("the same value as String picks the other overload", overloaded("text").equals("String"));
    }

    // --- composition, polymorphism, covariant returns -------------------------

    interface Describable {
        String label();
        default String describe() { return "a " + label(); } // default: extend without breaking implementors
    }

    abstract static class Shape implements Describable {
        abstract double area();
        abstract Shape scaled(double factor); // overridden with a narrower return type below
    }

    static final class Circle extends Shape {
        private final double radius;

        Circle(double radius) {
            if (radius <= 0) throw new IllegalArgumentException("radius must be positive");
            this.radius = radius;
        }

        @Override double area() { return Math.PI * radius * radius; }
        @Override Circle scaled(double factor) { return new Circle(radius * factor); } // covariant return
        @Override public String label() { return "circle"; }
    }

    /** Composition: a drawing owns shapes instead of inheriting from one. */
    static final class Drawing {
        private final List<Shape> shapes = new ArrayList<>();

        Drawing add(Shape shape) { shapes.add(shape); return this; }
        double totalArea() { return shapes.stream().mapToDouble(Shape::area).sum(); }
    }

    private static void polymorphismAndCovariantReturns() {
        Circle unit = new Circle(1);
        Circle doubled = unit.scaled(2); // no cast needed thanks to the covariant return

        check("scaling a circle quadruples the area", Math.abs(doubled.area() - 4 * unit.area()) < 1e-9);
        check("default methods build on abstract ones", unit.describe().equals("a circle"));
        check("composition aggregates polymorphic parts",
                new Drawing().add(unit).add(doubled).totalArea() > unit.area());

        try {
            new Circle(0);
            check("invalid state is rejected in the constructor", false);
        } catch (IllegalArgumentException expected) {
            check("invalid state is rejected in the constructor", true);
        }
    }

    // --- equality, hashing, immutability, defensive copying -------------------

    static final class Period {
        private final String name;
        private final List<String> events;

        Period(String name, List<String> events) {
            this.name = Objects.requireNonNull(name, "name");
            this.events = List.copyOf(events); // defensive copy on the way in
        }

        List<String> events() { return events; } // already immutable, safe to expose

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Period)) return false;
            Period period = (Period) other;
            return name.equals(period.name) && events.equals(period.events);
        }

        @Override
        public int hashCode() { return Objects.hash(name, events); }

        @Override
        public String toString() { return name + events; }
    }

    private static void equalityAndImmutability() {
        List<String> mutableSource = new ArrayList<>(List.of("start"));
        Period period = new Period("q1", mutableSource);
        mutableSource.add("leaked?");

        check("the defensive copy ignores later caller mutation", period.events().size() == 1);
        check("equal values are equal objects", period.equals(new Period("q1", List.of("start"))));
        check("equal objects share a hash code",
                period.hashCode() == new Period("q1", List.of("start")).hashCode());

        try {
            period.events().add("nope");
            check("the exposed view is unmodifiable", false);
        } catch (UnsupportedOperationException expected) {
            check("the exposed view is unmodifiable", true);
        }
    }

    // --- tiny assertion helper (assertions are off by default) ----------------

    private static void check(String claim, boolean holds) {
        if (!holds) throw new AssertionError("failed: " + claim);
        System.out.println("ok  " + claim);
    }
}
