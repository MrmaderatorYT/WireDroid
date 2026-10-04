package com.ccs.wiredroid.project;

import android.content.Context;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.content.Context;

import com.ccs.wiredroid.maven.MavenPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Creates a Playground Maven project with sample files for all features.
 * Uses standard Maven layout so compilation works correctly.
 */
public final class PlaygroundProjectFactory {

    private PlaygroundProjectFactory() {}

    public static File create(Context context) throws IOException {
        String gid = "com.playground";
        String pkgPath = gid.replace('.', File.separatorChar);

        File root = new File(MavenPaths.getWireDroidBase(context), "Playground");
        if (root.exists()) deleteRecursive(root);
        root.mkdirs();

        File mainJavaPkg = new File(root, "src/main/java/" + pkgPath);
        mainJavaPkg.mkdirs();
        new File(root, "src/main/resources").mkdirs();
        new File(root, "target/classes").mkdirs();

        // pom.xml
        write(root, "pom.xml", POM_XML);

        // Java files in src/main/java/com/playground/
        write(mainJavaPkg, "Main.java", MAIN_JAVA);
        write(mainJavaPkg, "Calculator.java", CALCULATOR_JAVA);
        write(mainJavaPkg, "Box.java", BOX_JAVA);

        // Kotlin files in src/main/java/com/playground/
        write(mainJavaPkg, "HelloWorld.kt", HELLO_KOTLIN);
        write(mainJavaPkg, "DataModel.kt", DATA_MODEL_KOTLIN);

        // Web files in project root
        write(root, "hello.html", HELLO_HTML);
        write(root, "style.css", STYLE_CSS);
        write(root, "app.js", APP_JS);

        // HTTP client file
        write(root, "demo.http", DEMO_HTTP);

        // SQL
        write(root, "sample.sql", SAMPLE_SQL);

        // SVG
        write(root, "logo.svg", LOGO_SVG);

        // ProGuard mapping
        write(root, "proguard-mapping.txt", PROGUARD_MAPPING);

        // Markdown
        write(root, "notes.md", isUkrainian(context) ? NOTES_MD_UK : NOTES_MD);

        // README
        write(root, "README.md", isUkrainian(context) ? README_MD_UK : README_MD);

        // Create SQLite database with sample data
        createSampleDatabase(root);

        return root;
    }

    private static boolean isUkrainian(Context context) {
        android.os.LocaleList locales = context.getResources().getConfiguration().getLocales();
        return !locales.isEmpty() && "uk".equalsIgnoreCase(locales.get(0).getLanguage());
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private static void write(File dir, String name, String content) throws IOException {
        dir.mkdirs();
        File f = new File(dir, name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void createSampleDatabase(File root) {
        File dbFile = new File(root, "sample.db");
        if (dbFile.exists()) dbFile.delete();

        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            // Close the auto-created db, reopen at our path
            db.close();

            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
            db.execSQL("CREATE TABLE users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "email TEXT UNIQUE," +
                    "age INTEGER DEFAULT 0)");
            db.execSQL("CREATE TABLE posts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_id INTEGER," +
                    "title TEXT NOT NULL," +
                    "body TEXT," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (user_id) REFERENCES users(id))");

            ContentValues u1 = new ContentValues();
            u1.put("name", "Alice");
            u1.put("email", "alice@example.com");
            u1.put("age", 25);
            db.insert("users", null, u1);

            ContentValues u2 = new ContentValues();
            u2.put("name", "Bob");
            u2.put("email", "bob@example.com");
            u2.put("age", 30);
            db.insert("users", null, u2);

            ContentValues u3 = new ContentValues();
            u3.put("name", "Charlie");
            u3.put("email", "charlie@example.com");
            u3.put("age", 35);
            db.insert("users", null, u3);

            ContentValues p1 = new ContentValues();
            p1.put("user_id", 1);
            p1.put("title", "First Post");
            p1.put("body", "Hello World!");
            db.insert("posts", null, p1);

            ContentValues p2 = new ContentValues();
            p2.put("user_id", 1);
            p2.put("title", "Second Post");
            p2.put("body", "More content");
            db.insert("posts", null, p2);

            ContentValues p3 = new ContentValues();
            p3.put("user_id", 2);
            p3.put("title", "Bobs Post");
            p3.put("body", "Bob writes here");
            db.insert("posts", null, p3);

        } catch (Exception ignored) {
        } finally {
            if (db != null && db.isOpen()) db.close();
        }
    }

    // ════════════════════════════════════════════════════════════
    //  pom.xml
    // ════════════════════════════════════════════════════════════

    private static final String POM_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
            + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
            + "http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "    <modelVersion>4.0.0</modelVersion>\n"
            + "    <groupId>com.playground</groupId>\n"
            + "    <artifactId>playground</artifactId>\n"
            + "    <version>1.0-SNAPSHOT</version>\n"
            + "    <packaging>jar</packaging>\n"
            + "    <name>WireDroid Playground</name>\n"
            + "    <properties>\n"
            + "        <maven.compiler.source>1.8</maven.compiler.source>\n"
            + "        <maven.compiler.target>1.8</maven.compiler.target>\n"
            + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
            + "        <mainClass>com.playground.Main</mainClass>\n"
            + "    </properties>\n"
            + "</project>\n";

    // ════════════════════════════════════════════════════════════
    //  Java source files
    // ════════════════════════════════════════════════════════════

    private static final String MAIN_JAVA =
            "package com.playground;\n"
            + "\n"
            + "import java.util.Arrays;\n"
            + "import java.util.List;\n"
            + "import java.util.stream.Collectors;\n"
            + "\n"
            + "public class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        System.out.println(\"=== WireDroid Playground ===\");\n"
            + "        System.out.println();\n"
            + "\n"
            + "        Calculator calc = new Calculator();\n"
            + "        System.out.println(\"2 + 3 = \" + calc.add(2, 3));\n"
            + "        System.out.println(\"10 / 3 = \" + calc.divide(10, 3));\n"
            + "        System.out.println(\"factorial(5) = \" + calc.factorial(5));\n"
            + "        System.out.println(\"isPrime(17) = \" + calc.isPrime(17));\n"
            + "        System.out.println();\n"
            + "\n"
            + "        List<String> names = Arrays.asList(\n"
            + "            \"Alice\", \"Bob\", \"Charlie\", \"David\", \"Eve\");\n"
            + "        String result = names.stream()\n"
            + "            .filter(n -> n.length() > 3)\n"
            + "            .map(String::toUpperCase)\n"
            + "            .collect(Collectors.joining(\", \"));\n"
            + "        System.out.println(\"Long names: \" + result);\n"
            + "        System.out.println();\n"
            + "\n"
            + "        Runnable greet = () -> System.out.println(\"Hello from lambda!\");\n"
            + "        greet.run();\n"
            + "        System.out.println();\n"
            + "\n"
            + "        try {\n"
            + "            calc.divide(10, 0);\n"
            + "        } catch (ArithmeticException e) {\n"
            + "            System.out.println(\"Caught: \" + e.getMessage());\n"
            + "        }\n"
            + "        System.out.println();\n"
            + "\n"
            + "        Box<String> stringBox = new Box<>(\"Hello Generics!\");\n"
            + "        Box<Integer> intBox = new Box<>(42);\n"
            + "        System.out.println(stringBox.get());\n"
            + "        System.out.println(\"Box contains: \" + intBox.get());\n"
            + "        System.out.println();\n"
            + "\n"
            + "        System.out.println(\"Done!\");\n"
            + "    }\n"
            + "}\n";

    private static final String CALCULATOR_JAVA =
            "package com.playground;\n"
            + "\n"
            + "public class Calculator {\n"
            + "\n"
            + "    public int add(int a, int b) {\n"
            + "        return a + b;\n"
            + "    }\n"
            + "\n"
            + "    public int subtract(int a, int b) {\n"
            + "        return a - b;\n"
            + "    }\n"
            + "\n"
            + "    public int multiply(int a, int b) {\n"
            + "        return a * b;\n"
            + "    }\n"
            + "\n"
            + "    public double divide(int a, int b) {\n"
            + "        if (b == 0) throw new ArithmeticException(\"Division by zero\");\n"
            + "        return (double) a / b;\n"
            + "    }\n"
            + "\n"
            + "    public long factorial(int n) {\n"
            + "        if (n <= 1) return 1;\n"
            + "        return n * factorial(n - 1);\n"
            + "    }\n"
            + "\n"
            + "    public boolean isPrime(int n) {\n"
            + "        if (n < 2) return false;\n"
            + "        for (int i = 2; i * i <= n; i++) {\n"
            + "            if (n % i == 0) return false;\n"
            + "        }\n"
            + "        return true;\n"
            + "    }\n"
            + "}\n";

    private static final String BOX_JAVA =
            "package com.playground;\n"
            + "\n"
            + "public class Box<T> {\n"
            + "    private T value;\n"
            + "\n"
            + "    public Box(T value) {\n"
            + "        this.value = value;\n"
            + "    }\n"
            + "\n"
            + "    public T get() {\n"
            + "        return value;\n"
            + "    }\n"
            + "\n"
            + "    public void set(T value) {\n"
            + "        this.value = value;\n"
            + "    }\n"
            + "\n"
            + "    @Override\n"
            + "    public String toString() {\n"
            + "        return \"Box{\" + value + \"}\";\n"
            + "    }\n"
            + "}\n";

    // ════════════════════════════════════════════════════════════
    //  Kotlin source files
    // ════════════════════════════════════════════════════════════

    private static final String HELLO_KOTLIN =
            "package com.playground\n"
            + "\n"
            + "fun main() {\n"
            + "    println(\"Hello from Kotlin!\")\n"
            + "    println(\"2 + 3 = \" + (2 + 3))\n"
            + "    println(\"PI = \" + Math.PI)\n"
            + "\n"
            + "    val numbers = listOf(1, 2, 3, 4, 5)\n"
            + "    val evens = numbers.filter { it % 2 == 0 }\n"
            + "    println(\"Evens: \" + evens)\n"
            + "\n"
            + "    val squares = numbers.map { it * it }\n"
            + "    println(\"Squares: \" + squares)\n"
            + "\n"
            + "    val sum = numbers.reduce { acc, i -> acc + i }\n"
            + "    println(\"Sum: \" + sum)\n"
            + "\n"
            + "    val user = User(\"Alice\", 25)\n"
            + "    println(user)\n"
            + "    println(\"Name: \" + user.name + \", Age: \" + user.age)\n"
            + "}\n";

    private static final String DATA_MODEL_KOTLIN =
            "package com.playground\n"
            + "\n"
            + "data class User(\n"
            + "    val name: String,\n"
            + "    val age: Int\n"
            + ")\n"
            + "\n"
            + "data class Product(\n"
            + "    val id: Long,\n"
            + "    val name: String,\n"
            + "    val price: Double,\n"
            + "    val inStock: Boolean = true\n"
            + ")\n"
            + "\n"
            + "sealed class Result {\n"
            + "    data class Success(val data: String) : Result()\n"
            + "    data class Error(val message: String) : Result()\n"
            + "    object Loading : Result()\n"
            + "}\n";

    // ════════════════════════════════════════════════════════════
    //  Web files
    // ════════════════════════════════════════════════════════════

    private static final String HELLO_HTML =
            "<!DOCTYPE html>\n"
            + "<html lang=\"en\">\n"
            + "<head>\n"
            + "    <meta charset=\"UTF-8\">\n"
            + "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "    <title>Playground</title>\n"
            + "    <link rel=\"stylesheet\" href=\"style.css\">\n"
            + "</head>\n"
            + "<body>\n"
            + "    <div class=\"container\">\n"
            + "        <h1>WireDroid Playground</h1>\n"
            + "        <p>This page is rendered in the built-in WebView.</p>\n"
            + "\n"
            + "        <div class=\"card\">\n"
            + "            <h2>Interactive Counter</h2>\n"
            + "            <div class=\"counter\">\n"
            + "                <button onclick=\"decrement()\">-</button>\n"
            + "                <span id=\"count\">0</span>\n"
            + "                <button onclick=\"increment()\">+</button>\n"
            + "            </div>\n"
            + "        </div>\n"
            + "\n"
            + "        <div class=\"card\">\n"
            + "            <h2>Color Picker</h2>\n"
            + "            <input type=\"color\" id=\"colorPicker\" value=\"#569cd6\" onchange=\"changeColor(this.value)\">\n"
            + "            <p id=\"colorValue\">#569cd6</p>\n"
            + "        </div>\n"
            + "\n"
            + "        <div class=\"card\">\n"
            + "            <h2>Console Output</h2>\n"
            + "            <button onclick=\"logMessage()\">Log to Console</button>\n"
            + "            <pre id=\"console\"></pre>\n"
            + "        </div>\n"
            + "    </div>\n"
            + "    <script src=\"app.js\"></script>\n"
            + "</body>\n"
            + "</html>\n";

    private static final String STYLE_CSS =
            "* { margin: 0; padding: 0; box-sizing: border-box; }\n"
            + "body {\n"
            + "    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
            + "    background: #1e1e1e;\n"
            + "    color: #d4d4d4;\n"
            + "    padding: 20px;\n"
            + "}\n"
            + ".container { max-width: 600px; margin: 0 auto; }\n"
            + "h1 { color: #569cd6; margin-bottom: 16px; }\n"
            + "h2 { color: #9cdcfe; font-size: 16px; margin-bottom: 8px; }\n"
            + "p { color: #808080; margin-bottom: 16px; }\n"
            + ".card {\n"
            + "    background: #252526;\n"
            + "    border-radius: 8px;\n"
            + "    padding: 16px;\n"
            + "    margin-bottom: 12px;\n"
            + "    border: 1px solid #333;\n"
            + "}\n"
            + ".counter { display: flex; align-items: center; gap: 16px; }\n"
            + ".counter button {\n"
            + "    width: 40px; height: 40px;\n"
            + "    font-size: 20px; border: none; border-radius: 8px;\n"
            + "    background: #569cd6; color: white; cursor: pointer;\n"
            + "}\n"
            + ".counter button:active { background: #4a8bc2; }\n"
            + "#count { font-size: 32px; font-weight: bold; color: #dcdcaa; min-width: 40px; text-align: center; }\n"
            + "input[type='color'] { width: 50px; height: 40px; border: none; border-radius: 8px; cursor: pointer; }\n"
            + "#colorValue { font-family: monospace; color: #ce9178; }\n"
            + "button:not(.counter button) {\n"
            + "    padding: 8px 16px; border: none; border-radius: 6px;\n"
            + "    background: #4ec9b0; color: #1e1e1e; cursor: pointer; font-weight: bold;\n"
            + "}\n"
            + "pre {\n"
            + "    background: #1e1e1e; padding: 8px; border-radius: 4px;\n"
            + "    font-size: 12px; color: #6a9955; margin-top: 8px;\n"
            + "    max-height: 100px; overflow: auto;\n"
            + "}\n";

    private static final String APP_JS =
            "let count = 0;\n"
            + "\n"
            + "function increment() {\n"
            + "    count++;\n"
            + "    document.getElementById('count').textContent = count;\n"
            + "    console.log('Count: ' + count);\n"
            + "}\n"
            + "\n"
            + "function decrement() {\n"
            + "    count--;\n"
            + "    document.getElementById('count').textContent = count;\n"
            + "    console.log('Count: ' + count);\n"
            + "}\n"
            + "\n"
            + "function changeColor(color) {\n"
            + "    document.getElementById('colorValue').textContent = color;\n"
            + "    document.querySelector('h1').style.color = color;\n"
            + "    console.log('Color changed to: ' + color);\n"
            + "}\n"
            + "\n"
            + "let logIndex = 0;\n"
            + "function logMessage() {\n"
            + "    logIndex++;\n"
            + "    const msg = 'Log #' + logIndex + ' at ' + new Date().toLocaleTimeString();\n"
            + "    const el = document.getElementById('console');\n"
            + "    el.textContent += msg + '\\n';\n"
            + "    console.log(msg);\n"
            + "    if (typeof AndroidConsole !== 'undefined') {\n"
            + "        AndroidConsole.log(msg);\n"
            + "    }\n"
            + "}\n"
            + "\n"
            + "console.log('Playground JS loaded!');\n";

    // ════════════════════════════════════════════════════════════
    //  HTTP / SQL / SVG / etc.
    // ════════════════════════════════════════════════════════════

    private static final String DEMO_HTTP =
            "### Get JSONPlaceholder users\n"
            + "GET https://jsonplaceholder.typicode.com/users\n"
            + "Accept: application/json\n\n"
            + "### Get a single post\n"
            + "GET https://jsonplaceholder.typicode.com/posts/1\n"
            + "Accept: application/json\n\n"
            + "### Create a new post\n"
            + "POST https://jsonplaceholder.typicode.com/posts\n"
            + "Content-Type: application/json\n\n"
            + "{\n"
            + "    \"title\": \"Hello from WireDroid\",\n"
            + "    \"body\": \"Test post from built-in HTTP client.\",\n"
            + "    \"userId\": 1\n"
            + "}\n\n"
            + "### Get todos\n"
            + "GET https://jsonplaceholder.typicode.com/todos?_limit=5\n"
            + "Accept: application/json\n";

    private static final String SAMPLE_SQL =
            "CREATE TABLE IF NOT EXISTS users (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    name TEXT NOT NULL,\n"
            + "    email TEXT UNIQUE,\n"
            + "    age INTEGER DEFAULT 0\n"
            + ");\n\n"
            + "CREATE TABLE IF NOT EXISTS posts (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    user_id INTEGER,\n"
            + "    title TEXT NOT NULL,\n"
            + "    body TEXT,\n"
            + "    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,\n"
            + "    FOREIGN KEY (user_id) REFERENCES users(id)\n"
            + ");\n\n"
            + "INSERT INTO users (name, email, age) VALUES\n"
            + "    ('Alice', 'alice@example.com', 25),\n"
            + "    ('Bob', 'bob@example.com', 30),\n"
            + "    ('Charlie', 'charlie@example.com', 35);\n\n"
            + "INSERT INTO posts (user_id, title, body) VALUES\n"
            + "    (1, 'First Post', 'Hello World!'),\n"
            + "    (1, 'Second Post', 'More content'),\n"
            + "    (2, 'Bobs Post', 'Bob writes here');\n\n"
            + "SELECT * FROM users;\n"
            + "SELECT u.name, COUNT(p.id) AS post_count\n"
            + "FROM users u LEFT JOIN posts p ON u.id = p.user_id\n"
            + "GROUP BY u.id;\n"
            + "PRAGMA table_info(users);\n";

    private static final String LOGO_SVG =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<svg width=\"200\" height=\"200\" xmlns=\"http://www.w3.org/2000/svg\">\n"
            + "  <defs>\n"
            + "    <linearGradient id=\"grad\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"100%\">\n"
            + "      <stop offset=\"0%\" style=\"stop-color:#569cd6;stop-opacity:1\" />\n"
            + "      <stop offset=\"100%\" style=\"stop-color:#4ec9b0;stop-opacity:1\" />\n"
            + "    </linearGradient>\n"
            + "  </defs>\n"
            + "  <rect width=\"200\" height=\"200\" rx=\"24\" fill=\"#1e1e1e\"/>\n"
            + "  <text x=\"100\" y=\"80\" text-anchor=\"middle\" font-family=\"monospace\"\n"
            + "        font-size=\"48\" font-weight=\"bold\" fill=\"url(#grad)\">JD</text>\n"
            + "  <text x=\"100\" y=\"120\" text-anchor=\"middle\" font-family=\"sans-serif\"\n"
            + "        font-size=\"16\" fill=\"#808080\">Playground</text>\n"
            + "  <circle cx=\"100\" cy=\"155\" r=\"8\" fill=\"#dcdcaa\" opacity=\"0.8\"/>\n"
            + "  <circle cx=\"80\" cy=\"165\" r=\"5\" fill=\"#569cd6\" opacity=\"0.6\"/>\n"
            + "  <circle cx=\"120\" cy=\"165\" r=\"5\" fill=\"#4ec9b0\" opacity=\"0.6\"/>\n"
            + "</svg>\n";

    private static final String PROGUARD_MAPPING =
            "# ProGuard/R8 mapping file\n"
            + "# compiler: R8\n\n"
            + "com.playground.Calculator -> a.b.c:\n"
            + "    int add(int,int) -> a\n"
            + "    int subtract(int,int) -> b\n"
            + "    int multiply(int,int) -> c\n"
            + "    double divide(int,int) -> d\n"
            + "    long factorial(int) -> e\n"
            + "    boolean isPrime(int) -> f\n\n"
            + "com.playground.Main -> x.y.z:\n"
            + "    void main(java.lang.String[]) -> a\n";

    private static final String NOTES_MD =
            "# Markdown Preview Test\n\n"
            + "## Features\n\n"
            + "- **Bold text** and *italic text*\n"
            + "- `inline code`\n"
            + "- Lists\n"
            + "  - Item 1\n"
            + "  - Item 2\n\n"
            + "## Code Block\n\n"
            + "```java\n"
            + "System.out.println(\"Hello Markdown!\");\n"
            + "```\n\n"
            + "> Blockquote: This is a quote.\n\n"
            + "---\n\n"
            + "*Created with WireDroid*";

    private static final String README_MD =
            "# Playground\n\n"
            + "Welcome to WireDroid Playground!\n\n"
            + "## How to use\n\n"
            + "- **Run Java**: open `src/main/java/com/playground/Main.java` -> press Run\n"
            + "- **Preview button 👁**: click to switch between editor and preview\n"
            + "  - Works for `.html` (WebView), `.md` (Markdown), `.svg` (SVG Viewer)\n"
            + "  - Files open in editor by default, click 👁 to preview\n"
            + "- **HTTP Client**: open `demo.http` -> send requests\n"
            + "- **Database Inspector**: open any `.db` file -> run SQL\n"
            + "- **Call Graph**: click Graph tab in bottom panel\n"
            + "- **ProGuard mapping**: menu -> Load ProGuard Mapping\n"
            + "- **Syntax highlighting**: SQL, JavaScript, CSS, HTML, Kotlin, Markdown\n\n"
            + "## File types\n\n"
            + "| File | Editor | Preview | Run |\n"
            + "|------|--------|---------|-----|\n"
            + "| `.java` | Yes (Java) | - | ▶ Run |\n"
            + "| `.kt` | Yes (Kotlin) | - | ▶ Run |\n"
            + "| `.html` | Yes (HTML) | 👁 WebView | - |\n"
            + "| `.css` | Yes (CSS) | - | - |\n"
            + "| `.js` | Yes (JavaScript) | - | - |\n"
            + "| `.sql` | Yes (SQL) | - | ▶ Run |\n"
            + "| `.http` | - | - | ▶ HTTP Client |\n"
            + "| `.svg` | Yes (SVG) | 👁 SVG Viewer | - |\n"
            + "| `.md` | Yes (Markdown) | 👁 Markdown | - |\n"
            + "| `.db` | - | - | ▶ Database Inspector |\n";

    private static final String NOTES_MD_UK =
            "# Перевірка Markdown-перегляду\n\n"
            + "## Можливості\n\n"
            + "- **Жирний текст** і *курсив*\n"
            + "- `вбудований код`\n"
            + "- Списки\n"
            + "  - Елемент 1\n"
            + "  - Елемент 2\n\n"
            + "## Блок коду\n\n"
            + "```java\n"
            + "System.out.println(\"Привіт, Markdown!\");\n"
            + "```\n\n"
            + "> Цитата: це приклад цитати.\n\n"
            + "---\n\n"
            + "*Створено у WireDroid*";

    private static final String README_MD_UK =
            "# Пісочниця\n\n"
            + "Ласкаво просимо до пісочниці WireDroid!\n\n"
            + "## Як користуватися\n\n"
            + "- **Запуск Java**: відкрийте `src/main/java/com/playground/Main.java` і натисніть «Запустити»\n"
            + "- **Кнопка перегляду 👁**: перемикає між редактором і попереднім переглядом\n"
            + "  - Працює для `.html` (WebView), `.md` (Markdown) і `.svg` (SVG Viewer)\n"
            + "  - Файли спочатку відкриваються в редакторі; натисніть 👁 для перегляду\n"
            + "- **HTTP-клієнт**: відкрийте `demo.http` і надішліть запит\n"
            + "- **Інспектор бази даних**: відкрийте файл `.db` і виконайте SQL\n"
            + "- **Граф викликів**: відкрийте вкладку «Граф» у нижній панелі\n"
            + "- **ProGuard mapping**: меню → «Завантажити ProGuard Mapping»\n"
            + "- **Підсвічування синтаксису**: SQL, JavaScript, CSS, HTML, Kotlin і Markdown\n\n"
            + "## Типи файлів\n\n"
            + "| Файл | Редактор | Перегляд | Запуск |\n"
            + "|------|----------|----------|--------|\n"
            + "| `.java` | Так (Java) | — | ▶ Запустити |\n"
            + "| `.kt` | Так (Kotlin) | — | ▶ Запустити |\n"
            + "| `.html` | Так (HTML) | 👁 WebView | — |\n"
            + "| `.css` | Так (CSS) | — | — |\n"
            + "| `.js` | Так (JavaScript) | — | — |\n"
            + "| `.sql` | Так (SQL) | — | ▶ Запустити |\n"
            + "| `.http` | — | — | ▶ HTTP-клієнт |\n"
            + "| `.svg` | Так (SVG) | 👁 SVG Viewer | — |\n"
            + "| `.md` | Так (Markdown) | 👁 Markdown | — |\n"
            + "| `.db` | — | — | ▶ Інспектор бази даних |\n";
}
