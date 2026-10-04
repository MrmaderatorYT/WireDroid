package com.ccs.wiredroid.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of 30+ ready-to-run Java sample projects for beginners and advanced learners.
 */
public final class SampleRegistry {

    public static final class SampleItem {
        public final String id;
        public final String title;
        public final String category;
        public final String description;
        public final String defaultProjectName;
        public final String mainClassName;
        public final String sourceCode;

        public SampleItem(String id, String title, String category, String description,
                          String defaultProjectName, String mainClassName, String sourceCode) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.description = description;
            this.defaultProjectName = defaultProjectName;
            this.mainClassName = mainClassName;
            this.sourceCode = sourceCode;
        }

        @Override
        public String toString() {
            return "[" + category + "] " + title;
        }
    }

    private static final Map<String, SampleItem> SAMPLES_MAP = new LinkedHashMap<>();
    private static final List<SampleItem> SAMPLES_LIST = new ArrayList<>();

    static {
        // ── Basics ────────────────────────────────────────────────────────────
        add(new SampleItem(
                "hello_world",
                "Hello World",
                "Basics",
                "Your first Java program: printing text to console.",
                "Sample_HelloWorld",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello, World!\");\n"
                + "        System.out.println(\"Welcome to WireDroid IDE! 🚀\");\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "text_input",
                "Text Input (Scanner)",
                "Basics",
                "Reading user input from console using java.util.Scanner.",
                "Sample_TextInput",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.Scanner;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        Scanner scanner = new Scanner(System.in);\n"
                + "        System.out.print(\"Enter your name: \");\n"
                + "        String name = scanner.hasNextLine() ? scanner.nextLine() : \"Developer\";\n"
                + "        System.out.println(\"Hello, \" + name + \"! Enjoy coding Java on Android.\");\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "functions",
                "Functions & Methods",
                "Basics",
                "Methods, parameters, return values, overloading, and recursion.",
                "Sample_Functions",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        int sum = add(15, 25);\n"
                + "        System.out.println(\"15 + 25 = \" + sum);\n"
                + "        System.out.println(\"Max of 4.5 and 7.2 = \" + max(4.5, 7.2));\n"
                + "        System.out.println(\"Factorial of 5 = \" + factorial(5));\n"
                + "    }\n\n"
                + "    public static int add(int a, int b) {\n"
                + "        return a + b;\n"
                + "    }\n\n"
                + "    public static double max(double a, double b) {\n"
                + "        return a > b ? a : b;\n"
                + "    }\n\n"
                + "    public static long factorial(int n) {\n"
                + "        if (n <= 1) return 1;\n"
                + "        return n * factorial(n - 1);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "for_loop",
                "For Loop",
                "Basics",
                "Counted loops, step intervals, and nested loops for multiplication table.",
                "Sample_ForLoop",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"--- Counting 1 to 5 ---\");\n"
                + "        for (int i = 1; i <= 5; i++) {\n"
                + "            System.out.println(\"Step \" + i);\n"
                + "        }\n\n"
                + "        System.out.println(\"\\n--- Multiplication Table (1..5) ---\");\n"
                + "        for (int i = 1; i <= 5; i++) {\n"
                + "            for (int j = 1; j <= 5; j++) {\n"
                + "                System.out.printf(\"%4d\", i * j);\n"
                + "            }\n"
                + "            System.out.println();\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "while_loop",
                "While Loop",
                "Basics",
                "Conditional while & do-while loops with countdown and simulation.",
                "Sample_WhileLoop",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"--- While loop countdown ---\");\n"
                + "        int count = 5;\n"
                + "        while (count > 0) {\n"
                + "            System.out.println(\"T-minus: \" + count);\n"
                + "            count--;\n"
                + "        }\n"
                + "        System.out.println(\"Liftoff! 🚀\");\n\n"
                + "        System.out.println(\"\\n--- Do-While loop ---\");\n"
                + "        int num = 1;\n"
                + "        do {\n"
                + "            System.out.println(\"Value: \" + num + \" (squared: \" + (num * num) + \")\");\n"
                + "            num *= 2;\n"
                + "        } while (num <= 16);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "for_each",
                "For-Each Loop",
                "Basics",
                "Enhanced for-loop over arrays and Iterable collections.",
                "Sample_ForEach",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.Arrays;\n"
                + "import java.util.List;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        String[] fruits = { \"Apple\", \"Banana\", \"Cherry\", \"Orange\" };\n"
                + "        System.out.println(\"--- Array elements ---\");\n"
                + "        for (String fruit : fruits) {\n"
                + "            System.out.println(\"🍎 \" + fruit);\n"
                + "        }\n\n"
                + "        List<Integer> primes = Arrays.asList(2, 3, 5, 7, 11, 13);\n"
                + "        int sum = 0;\n"
                + "        for (int p : primes) {\n"
                + "            sum += p;\n"
                + "        }\n"
                + "        System.out.println(\"Sum of primes: \" + sum);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "big_numbers",
                "Big Numbers (BigInteger & BigDecimal)",
                "Basics",
                "Arbitrary precision arithmetic for massive numbers and exact decimals.",
                "Sample_BigNumbers",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.math.BigDecimal;\n"
                + "import java.math.BigInteger;\n"
                + "import java.math.RoundingMode;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        // Factorial of 40 (far exceeds standard long)\n"
                + "        BigInteger fact = BigInteger.ONE;\n"
                + "        for (int i = 1; i <= 40; i++) {\n"
                + "            fact = fact.multiply(BigInteger.valueOf(i));\n"
                + "        }\n"
                + "        System.out.println(\"40! = \" + fact);\n\n"
                + "        // Exact financial decimal calculations\n"
                + "        BigDecimal price = new BigDecimal(\"19.99\");\n"
                + "        BigDecimal taxRate = new BigDecimal(\"0.07\");\n"
                + "        BigDecimal tax = price.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);\n"
                + "        BigDecimal total = price.add(tax);\n"
                + "        System.out.println(\"Price: $\" + price + \" + Tax: $\" + tax + \" = Total: $\" + total);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "matrix_2d",
                "Matrix & 2D Arrays",
                "Basics",
                "Working with multidimensional arrays, matrix multiplication and diagonals.",
                "Sample_Matrix2D",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        int[][] a = {\n"
                + "            { 1, 2, 3 },\n"
                + "            { 4, 5, 6 },\n"
                + "            { 7, 8, 9 }\n"
                + "        };\n\n"
                + "        System.out.println(\"--- 3x3 Matrix ---\");\n"
                + "        int mainDiag = 0;\n"
                + "        for (int i = 0; i < a.length; i++) {\n"
                + "            for (int j = 0; j < a[i].length; j++) {\n"
                + "                System.out.printf(\"%3d\", a[i][j]);\n"
                + "                if (i == j) mainDiag += a[i][j];\n"
                + "            }\n"
                + "            System.out.println();\n"
                + "        }\n"
                + "        System.out.println(\"Main diagonal sum: \" + mainDiag);\n"
                + "    }\n"
                + "}\n"
        ));

        // ── Collections & Data Structures ─────────────────────────────────────
        add(new SampleItem(
                "array_list",
                "ArrayList",
                "Collections",
                "Dynamic list operations: adding, removing, sorting, and filtering.",
                "Sample_ArrayList",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.Collections;\n"
                + "import java.util.List;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        List<String> heroes = new ArrayList<>();\n"
                + "        heroes.add(\"Iron Man\");\n"
                + "        heroes.add(\"Thor\");\n"
                + "        heroes.add(\"Spider-Man\");\n"
                + "        heroes.add(\"Captain America\");\n\n"
                + "        System.out.println(\"Original list: \" + heroes);\n"
                + "        Collections.sort(heroes);\n"
                + "        System.out.println(\"Alphabetical: \" + heroes);\n"
                + "        heroes.remove(\"Thor\");\n"
                + "        System.out.println(\"After removing Thor: \" + heroes);\n"
                + "        System.out.println(\"Contains Spider-Man? \" + heroes.contains(\"Spider-Man\"));\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "hash_map",
                "HashMap",
                "Collections",
                "Key-value dictionary, getOrDefault, entrySet, and word frequency counter.",
                "Sample_HashMap",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.HashMap;\n"
                + "import java.util.Map;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        String text = \"java droid java code android java ide droid\";\n"
                + "        Map<String, Integer> freq = new HashMap<>();\n\n"
                + "        for (String word : text.split(\" \")) {\n"
                + "            freq.put(word, freq.getOrDefault(word, 0) + 1);\n"
                + "        }\n\n"
                + "        System.out.println(\"--- Word Frequency Counter ---\");\n"
                + "        for (Map.Entry<String, Integer> entry : freq.entrySet()) {\n"
                + "            System.out.println(\"• \" + entry.getKey() + \": \" + entry.getValue() + \" times\");\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "stack_queue",
                "Stack & Queue (ArrayDeque)",
                "Collections",
                "LIFO (Stack) and FIFO (Queue) data structure implementations.",
                "Sample_StackQueue",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.ArrayDeque;\n"
                + "import java.util.Deque;\n"
                + "import java.util.Queue;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        // LIFO Stack (Last In, First Out)\n"
                + "        Deque<String> stack = new ArrayDeque<>();\n"
                + "        stack.push(\"First\");\n"
                + "        stack.push(\"Second\");\n"
                + "        stack.push(\"Third\");\n"
                + "        System.out.println(\"Stack pop: \" + stack.pop()); // Third\n\n"
                + "        // FIFO Queue (First In, First Out)\n"
                + "        Queue<String> queue = new ArrayDeque<>();\n"
                + "        queue.offer(\"Customer 1\");\n"
                + "        queue.offer(\"Customer 2\");\n"
                + "        queue.offer(\"Customer 3\");\n"
                + "        System.out.println(\"Queue poll: \" + queue.poll()); // Customer 1\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "sorting",
                "Sorting & Searching",
                "Collections",
                "Sorting primitives, custom Comparators, and binary searching.",
                "Sample_Sorting",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.Collections;\n"
                + "import java.util.Comparator;\n"
                + "import java.util.List;\n\n"
                + "public class Main {\n"
                + "    static class Player {\n"
                + "        String name;\n"
                + "        int score;\n"
                + "        Player(String name, int score) { this.name = name; this.score = score; }\n"
                + "        public String toString() { return name + \" (\" + score + \" pts)\"; }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        List<Player> players = new ArrayList<>();\n"
                + "        players.add(new Player(\"Alice\", 450));\n"
                + "        players.add(new Player(\"Bob\", 820));\n"
                + "        players.add(new Player(\"Charlie\", 610));\n\n"
                + "        // Sort by score descending\n"
                + "        players.sort(Comparator.comparingInt((Player p) -> p.score).reversed());\n"
                + "        System.out.println(\"Leaderboard: \" + players);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "binary_tree",
                "Binary Search Tree",
                "Collections",
                "Custom Node-based Binary Search Tree with in-order sorted traversal.",
                "Sample_BinaryTree",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    static class Node {\n"
                + "        int val;\n"
                + "        Node left, right;\n"
                + "        Node(int val) { this.val = val; }\n"
                + "    }\n\n"
                + "    static Node insert(Node root, int val) {\n"
                + "        if (root == null) return new Node(val);\n"
                + "        if (val < root.val) root.left = insert(root.left, val);\n"
                + "        else if (val > root.val) root.right = insert(root.right, val);\n"
                + "        return root;\n"
                + "    }\n\n"
                + "    static void inOrder(Node root) {\n"
                + "        if (root != null) {\n"
                + "            inOrder(root.left);\n"
                + "            System.out.print(root.val + \" \");\n"
                + "            inOrder(root.right);\n"
                + "        }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        Node root = null;\n"
                + "        int[] values = { 50, 30, 20, 40, 70, 60, 80 };\n"
                + "        for (int v : values) root = insert(root, v);\n"
                + "        System.out.print(\"In-order sorted BST: \");\n"
                + "        inOrder(root);\n"
                + "        System.out.println();\n"
                + "    }\n"
                + "}\n"
        ));

        // ── OOP & Design ──────────────────────────────────────────────────────
        add(new SampleItem(
                "classes_objects",
                "Classes & Objects",
                "OOP",
                "Encapsulation, constructors, getters, setters, and toString.",
                "Sample_Classes",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    static class BankAccount {\n"
                + "        private final String owner;\n"
                + "        private double balance;\n\n"
                + "        public BankAccount(String owner, double initial) {\n"
                + "            this.owner = owner;\n"
                + "            this.balance = initial;\n"
                + "        }\n\n"
                + "        public void deposit(double amount) {\n"
                + "            balance += amount;\n"
                + "            System.out.println(owner + \" deposited $\" + amount);\n"
                + "        }\n\n"
                + "        public boolean withdraw(double amount) {\n"
                + "            if (balance >= amount) {\n"
                + "                balance -= amount;\n"
                + "                System.out.println(owner + \" withdrew $\" + amount);\n"
                + "                return true;\n"
                + "            }\n"
                + "            System.out.println(\"Insufficient funds for \" + owner);\n"
                + "            return false;\n"
                + "        }\n\n"
                + "        public double getBalance() { return balance; }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        BankAccount acc = new BankAccount(\"Alex\", 100.0);\n"
                + "        acc.deposit(50.0);\n"
                + "        acc.withdraw(30.0);\n"
                + "        System.out.println(\"Final Balance: $\" + acc.getBalance());\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "inheritance",
                "Inheritance & Polymorphism",
                "OOP",
                "Abstract classes, method overriding, super calls, and dynamic dispatch.",
                "Sample_Inheritance",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    abstract static class Animal {\n"
                + "        String name;\n"
                + "        Animal(String name) { this.name = name; }\n"
                + "        abstract void makeSound();\n"
                + "    }\n\n"
                + "    static class Dog extends Animal {\n"
                + "        Dog(String name) { super(name); }\n"
                + "        @Override void makeSound() { System.out.println(name + \" says: Woof! 🐶\"); }\n"
                + "    }\n\n"
                + "    static class Cat extends Animal {\n"
                + "        Cat(String name) { super(name); }\n"
                + "        @Override void makeSound() { System.out.println(name + \" says: Meow! 🐱\"); }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        Animal[] animals = { new Dog(\"Buddy\"), new Cat(\"Luna\") };\n"
                + "        for (Animal a : animals) {\n"
                + "            a.makeSound();\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "generics",
                "Generics",
                "OOP",
                "Type-safe generic classes and methods (e.g. Box<T>, Pair<K,V>).",
                "Sample_Generics",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    static class Pair<K, V> {\n"
                + "        private final K key;\n"
                + "        private final V value;\n\n"
                + "        public Pair(K key, V value) {\n"
                + "            this.key = key;\n"
                + "            this.value = value;\n"
                + "        }\n\n"
                + "        public K getKey() { return key; }\n"
                + "        public V getValue() { return value; }\n"
                + "        public String toString() { return \"(\" + key + \" -> \" + value + \")\"; }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        Pair<String, Integer> p1 = new Pair<>(\"Age\", 25);\n"
                + "        Pair<Integer, String> p2 = new Pair<>(200, \"OK\");\n"
                + "        System.out.println(\"Pair 1: \" + p1);\n"
                + "        System.out.println(\"Pair 2: \" + p2);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "enums",
                "Enums with Methods",
                "OOP",
                "Java enums with custom fields, constructors, and abstract behavior.",
                "Sample_Enums",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    enum Operation {\n"
                + "        PLUS(\"+\") { double apply(double x, double y) { return x + y; } },\n"
                + "        MINUS(\"-\") { double apply(double x, double y) { return x - y; } },\n"
                + "        MULTIPLY(\"*\") { double apply(double x, double y) { return x * y; } },\n"
                + "        DIVIDE(\"/\") { double apply(double x, double y) { return x / y; } };\n\n"
                + "        final String symbol;\n"
                + "        Operation(String symbol) { this.symbol = symbol; }\n"
                + "        abstract double apply(double x, double y);\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        double a = 12.0, b = 4.0;\n"
                + "        for (Operation op : Operation.values()) {\n"
                + "            System.out.printf(\"%.1f %s %.1f = %.1f%n\", a, op.symbol, b, op.apply(a, b));\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "design_patterns",
                "Design Patterns (Builder & Factory)",
                "OOP",
                "Fluent Builder pattern and Factory method implementations.",
                "Sample_DesignPatterns",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    static class User {\n"
                + "        private final String name;\n"
                + "        private final String email;\n"
                + "        private final int age;\n\n"
                + "        private User(Builder b) {\n"
                + "            this.name = b.name;\n"
                + "            this.email = b.email;\n"
                + "            this.age = b.age;\n"
                + "        }\n\n"
                + "        public static class Builder {\n"
                + "            private String name;\n"
                + "            private String email;\n"
                + "            private int age;\n\n"
                + "            public Builder name(String n) { this.name = n; return this; }\n"
                + "            public Builder email(String e) { this.email = e; return this; }\n"
                + "            public Builder age(int a) { this.age = a; return this; }\n"
                + "            public User build() { return new User(this); }\n"
                + "        }\n\n"
                + "        public String toString() {\n"
                + "            return \"User[name=\" + name + \", email=\" + email + \", age=\" + age + \"]\";\n"
                + "        }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        User user = new User.Builder()\n"
                + "                .name(\"Alexander\")\n"
                + "                .email(\"alex@example.com\")\n"
                + "                .age(28)\n"
                + "                .build();\n"
                + "        System.out.println(\"Constructed via Builder: \" + user);\n"
                + "    }\n"
                + "}\n"
        ));

        // ── Core Java & Modern Features ───────────────────────────────────────
        add(new SampleItem(
                "exceptions",
                "Exception Handling",
                "Core Java",
                "try-catch-finally, try-with-resources, custom checked exceptions.",
                "Sample_Exceptions",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    static class ValidationException extends Exception {\n"
                + "        public ValidationException(String msg) { super(msg); }\n"
                + "    }\n\n"
                + "    static void checkAge(int age) throws ValidationException {\n"
                + "        if (age < 18) throw new ValidationException(\"Age must be at least 18 (got: \" + age + \")\");\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        try {\n"
                + "            checkAge(15);\n"
                + "        } catch (ValidationException e) {\n"
                + "            System.err.println(\"Caught exception: \" + e.getMessage());\n"
                + "        } finally {\n"
                + "            System.out.println(\"Finally block executed successfully.\");\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "reflection",
                "Reflection API",
                "Core Java",
                "Inspecting class fields, constructors, methods, and dynamic invocation.",
                "Sample_Reflection",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.lang.reflect.Method;\n\n"
                + "public class Main {\n"
                + "    public void secretGreeting(String target) {\n"
                + "        System.out.println(\"Invoked via Reflection: Hello, \" + target + \"!\");\n"
                + "    }\n\n"
                + "    public static void main(String[] args) throws Exception {\n"
                + "        Class<?> clazz = Main.class;\n"
                + "        System.out.println(\"Class Name: \" + clazz.getName());\n"
                + "        System.out.println(\"Methods count: \" + clazz.getDeclaredMethods().length);\n\n"
                + "        Object instance = clazz.getDeclaredConstructor().newInstance();\n"
                + "        Method method = clazz.getDeclaredMethod(\"secretGreeting\", String.class);\n"
                + "        method.invoke(instance, \"WireDroid\");\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "streams",
                "Streams API",
                "Core Java",
                "Functional pipelines: filter, map, reduce, distinct, and collectors.",
                "Sample_Streams",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.Arrays;\n"
                + "import java.util.List;\n"
                + "import java.util.stream.Collectors;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        List<String> names = Arrays.asList(\"alice\", \"bob\", \"charlie\", \"anna\", \"david\");\n\n"
                + "        List<String> result = names.stream()\n"
                + "                .filter(n -> n.startsWith(\"a\"))\n"
                + "                .map(String::toUpperCase)\n"
                + "                .sorted()\n"
                + "                .collect(Collectors.toList());\n\n"
                + "        System.out.println(\"Names starting with 'a' (uppercase): \" + result);\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "lambdas",
                "Lambda Expressions",
                "Core Java",
                "Functional interfaces (Predicate, Consumer, Function) and method references.",
                "Sample_Lambdas",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.function.Function;\n"
                + "import java.util.function.Predicate;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        Predicate<Integer> isEven = n -> n % 2 == 0;\n"
                + "        System.out.println(\"Is 10 even? \" + isEven.test(10));\n"
                + "        System.out.println(\"Is 7 even? \" + isEven.test(7));\n\n"
                + "        Function<String, Integer> lengthFunc = String::length;\n"
                + "        System.out.println(\"Length of 'Android': \" + lengthFunc.apply(\"Android\"));\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "records",
                "Records (Java 16+)",
                "Core Java",
                "Compact, immutable data record classes and pattern matching.",
                "Sample_Records",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public record Point(int x, int y) {\n"
                + "        public double distanceToOrigin() {\n"
                + "            return Math.sqrt(x * x + y * y);\n"
                + "        }\n"
                + "    }\n\n"
                + "    public static void main(String[] args) {\n"
                + "        Point p1 = new Point(3, 4);\n"
                + "        System.out.println(\"Point: \" + p1);\n"
                + "        System.out.println(\"Distance to origin: \" + p1.distanceToOrigin());\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "date_time",
                "Date & Time (java.time)",
                "Core Java",
                "Modern Java date/time APIs: LocalDate, Duration, and DateTimeFormatter.",
                "Sample_DateTime",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.time.LocalDate;\n"
                + "import java.time.LocalDateTime;\n"
                + "import java.time.format.DateTimeFormatter;\n"
                + "import java.time.temporal.ChronoUnit;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        LocalDate today = LocalDate.now();\n"
                + "        LocalDate future = today.plusDays(30);\n"
                + "        System.out.println(\"Today: \" + today);\n"
                + "        System.out.println(\"In 30 days: \" + future);\n"
                + "        System.out.println(\"Days until: \" + ChronoUnit.DAYS.between(today, future));\n\n"
                + "        LocalDateTime now = LocalDateTime.now();\n"
                + "        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(\"yyyy-MM-dd HH:mm:ss\");\n"
                + "        System.out.println(\"Formatted: \" + now.format(fmt));\n"
                + "    }\n"
                + "}\n"
        ));

        // ── I/O, Network & Utilities ──────────────────────────────────────────
        add(new SampleItem(
                "files_io",
                "Files & I/O",
                "I/O & Utilities",
                "Reading and writing text files using standard Java file streams.",
                "Sample_FilesIO",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.io.File;\n"
                + "import java.io.FileWriter;\n"
                + "import java.util.Scanner;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        try {\n"
                + "            File tempFile = File.createTempFile(\"sample_\", \".txt\");\n"
                + "            tempFile.deleteOnExit();\n\n"
                + "            // Write to file\n"
                + "            try (FileWriter writer = new FileWriter(tempFile)) {\n"
                + "                writer.write(\"WireDroid offline Android IDE\\nLine 2: Fast ECJ Compiler\");\n"
                + "            }\n\n"
                + "            // Read from file\n"
                + "            System.out.println(\"--- Reading from temp file ---\");\n"
                + "            try (Scanner reader = new Scanner(tempFile)) {\n"
                + "                while (reader.hasNextLine()) {\n"
                + "                    System.out.println(reader.nextLine());\n"
                + "                }\n"
                + "            }\n"
                + "        } catch (Exception e) {\n"
                + "            e.printStackTrace();\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "threads",
                "Threads & Concurrency",
                "I/O & Utilities",
                "Multi-threading, Runnable, ExecutorService, and AtomicInteger.",
                "Sample_Threads",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.concurrent.ExecutorService;\n"
                + "import java.util.concurrent.Executors;\n"
                + "import java.util.concurrent.TimeUnit;\n"
                + "import java.util.concurrent.atomic.AtomicInteger;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) throws InterruptedException {\n"
                + "        AtomicInteger counter = new AtomicInteger(0);\n"
                + "        ExecutorService executor = Executors.newFixedThreadPool(2);\n\n"
                + "        for (int i = 0; i < 10; i++) {\n"
                + "            final int taskId = i;\n"
                + "            executor.submit(() -> {\n"
                + "                int val = counter.incrementAndGet();\n"
                + "                System.out.println(\"Task \" + taskId + \" on \" + Thread.currentThread().getName() + \" -> Counter: \" + val);\n"
                + "            });\n"
                + "        }\n\n"
                + "        executor.shutdown();\n"
                + "        executor.awaitTermination(3, TimeUnit.SECONDS);\n"
                + "        System.out.println(\"Final count: \" + counter.get());\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "http_client",
                "HTTP Client",
                "I/O & Utilities",
                "Making HTTP GET requests using HttpURLConnection.",
                "Sample_HttpClient",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.io.BufferedReader;\n"
                + "import java.io.InputStreamReader;\n"
                + "import java.net.HttpURLConnection;\n"
                + "import java.net.URL;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"--- HTTP Request Demo ---\");\n"
                + "        try {\n"
                + "            URL url = new URL(\"https://httpbin.org/get\");\n"
                + "            HttpURLConnection conn = (HttpURLConnection) url.openConnection();\n"
                + "            conn.setRequestMethod(\"GET\");\n"
                + "            conn.setConnectTimeout(4000);\n"
                + "            conn.setReadTimeout(4000);\n"
                + "            System.out.println(\"Response Code: \" + conn.getResponseCode());\n\n"
                + "            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));\n"
                + "            String line;\n"
                + "            int linesPrinted = 0;\n"
                + "            while ((line = in.readLine()) != null && linesPrinted < 10) {\n"
                + "                System.out.println(line);\n"
                + "                linesPrinted++;\n"
                + "            }\n"
                + "            in.close();\n"
                + "        } catch (Exception e) {\n"
                + "            System.out.println(\"Network note: Connect to internet to fetch live HTTP data (\" + e.getMessage() + \")\");\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "regex",
                "Regular Expressions (Regex)",
                "I/O & Utilities",
                "Pattern & Matcher for validating formats, matching, and replacements.",
                "Sample_Regex",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.util.regex.Matcher;\n"
                + "import java.util.regex.Pattern;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        String text = \"Contact us at support@example.com or sales@company.org!\";\n"
                + "        Pattern emailPattern = Pattern.compile(\"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,6}\");\n"
                + "        Matcher matcher = emailPattern.matcher(text);\n\n"
                + "        System.out.println(\"Found emails in text:\");\n"
                + "        while (matcher.find()) {\n"
                + "            System.out.println(\"📧 \" + matcher.group());\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "crypto_math",
                "Math & Cryptography",
                "I/O & Utilities",
                "SHA-256 / MD5 hashing with MessageDigest and SecureRandom token generation.",
                "Sample_CryptoMath",
                "Main",
                "package com.example.sample;\n\n"
                + "import java.nio.charset.StandardCharsets;\n"
                + "import java.security.MessageDigest;\n"
                + "import java.security.SecureRandom;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) throws Exception {\n"
                + "        String input = \"WireDroid2026\";\n"
                + "        MessageDigest md = MessageDigest.getInstance(\"SHA-256\");\n"
                + "        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));\n\n"
                + "        StringBuilder hex = new StringBuilder();\n"
                + "        for (byte b : hash) hex.append(String.format(\"%02x\", b));\n"
                + "        System.out.println(\"SHA-256 of '\" + input + \"': \" + hex);\n\n"
                + "        SecureRandom rand = new SecureRandom();\n"
                + "        System.out.println(\"Random Secure Token: \" + Integer.toHexString(rand.nextInt()));\n"
                + "    }\n"
                + "}\n"
        ));

        add(new SampleItem(
                "json_processing",
                "JSON Processing",
                "I/O & Utilities",
                "Building and reading JSON strings natively in Java.",
                "Sample_JsonProcessing",
                "Main",
                "package com.example.sample;\n\n"
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        String json = \"{\\\"app\\\":\\\"WireDroid\\\",\\\"version\\\":2.0,\\\"offline\\\":true}\";\n"
                + "        System.out.println(\"JSON Payload: \" + json);\n"
                + "        System.out.println(\"Contains 'app': \" + json.contains(\"app\"));\n"
                + "    }\n"
                + "}\n"
        ));
    }

    private static void add(SampleItem item) {
        SAMPLES_MAP.put(item.id, item);
        SAMPLES_LIST.add(item);
    }

    public static List<SampleItem> getAll() {
        return Collections.unmodifiableList(SAMPLES_LIST);
    }

    public static SampleItem get(String id) {
        return SAMPLES_MAP.get(id);
    }

    public static SampleItem getOrDefault(String id) {
        SampleItem item = SAMPLES_MAP.get(id);
        if (item != null) return item;
        return SAMPLES_LIST.get(0);
    }

    private SampleRegistry() {}
}
