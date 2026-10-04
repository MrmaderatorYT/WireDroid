package com.ccs.wiredroid.util.languages.ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a scope tree over the token stream and assigns every identifier the
 * semantic role its position implies.
 *
 * <p>This is the difference from regex highlighting. {@code Foo} is a type when
 * it precedes an identifier, follows {@code new}, or sits in a generic argument
 * list; {@code foo} is a method when it precedes {@code (}, a field when it is
 * declared in a class body, and a local when it is declared in a block — and a
 * later use of {@code foo} takes the role of whichever declaration is in scope.
 * None of that is expressible as a token-level pattern.</p>
 *
 * <p>The parser is deliberately error-tolerant: any construct it does not
 * recognise falls back to expression classification and the walk continues, so
 * half-typed code still highlights sensibly.</p>
 */
public final class JavaAstParser {

    private static final Set<String> MODIFIERS = new HashSet<>(Arrays.asList(
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "native", "transient", "volatile", "strictfp", "default", "sealed"
    ));

    /** Keywords that introduce a list of type references. */
    private static final Set<String> TYPE_LIST_KEYWORDS = new HashSet<>(Arrays.asList(
            "extends", "implements", "throws", "permits"
    ));

    private static final Set<String> PRIMITIVES = new HashSet<>(Arrays.asList(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
    ));

    private final List<JavaToken> tokens;
    private int index;
    private AstNode scope;
    private final AstNode root;
    /** Set when a declaration has been recognised and its body brace is next. */
    private AstNode pendingScope;
    /** Parallel to the scope stack: whether that scope is an enum body. */
    private final Deque<Boolean> enumBodies = new ArrayDeque<>();

    /** Names of the types declared in this file, in source order. */
    public final List<String> declaredTypes = new ArrayList<>();
    /** Names of the methods declared in this file, in source order. */
    public final List<String> declaredMethods = new ArrayList<>();

    public JavaAstParser(List<JavaToken> tokens) {
        this.tokens = tokens;
        this.root = new AstNode(AstNode.Kind.COMPILATION_UNIT, null, null);
        this.scope = root;
        this.enumBodies.push(Boolean.FALSE);
    }

    /**
     * Runs the pass. Roles are written back into the token objects.
     *
     * @return the root of the scope tree
     */
    public AstNode parse() {
        index = 0;
        root.startToken = 0;
        root.startLine = 0;

        boolean atDeclarationStart = true;

        while (index < tokens.size()) {
            JavaToken t = tokens.get(index);
            if (t.kind == JavaToken.Kind.EOF) break;

            if (isComment(t)) { index++; continue; }

            // ── Annotations, anywhere they may appear.
            if (t.kind == JavaToken.Kind.AT) {
                if (readAnnotationType()) continue;
                index++;
                continue;
            }

            // ── Modifiers never change the declaration-start state.
            if (t.kind == JavaToken.Kind.KEYWORD && MODIFIERS.contains(t.text)) {
                index++;
                continue;
            }

            // ── Scope braces.
            if (t.is("{")) {
                openScope(t);
                index++;
                atDeclarationStart = true;
                continue;
            }
            if (t.is("}")) {
                closeScope(t);
                index++;
                atDeclarationStart = true;
                continue;
            }
            if (t.is(";")) {
                index++;
                atDeclarationStart = true;
                continue;
            }

            // ── package / import.
            if (t.isKeyword("package")) { readPackage(); atDeclarationStart = true; continue; }
            if (t.isKeyword("import")) { readImport(); atDeclarationStart = true; continue; }

            // ── Type declarations. `record` is contextual, so require a name after it.
            if (isTypeDeclarationKeyword(t)) {
                readTypeDeclaration();
                atDeclarationStart = false;
                continue;
            }

            // ── extends / implements / throws / permits: everything after is a type.
            if (t.kind == JavaToken.Kind.KEYWORD && TYPE_LIST_KEYWORDS.contains(t.text)) {
                index++;
                readTypeList();
                atDeclarationStart = false;
                continue;
            }

            if (t.isKeyword("new")) {
                index++;
                readTypeReference(true);
                atDeclarationStart = false;
                continue;
            }

            if (t.isKeyword("instanceof")) {
                index++;
                readTypeReference(true);
                atDeclarationStart = false;
                continue;
            }

            if (t.isKeyword("catch")) {
                index++;
                readParameters(scope, true);
                atDeclarationStart = false;
                continue;
            }

            // ── Generic method type parameters: `<T extends X> T foo()`.
            if (t.is("<") && atDeclarationStart) {
                int after = readTypeArguments(index, true);
                if (after > index) { index = after; continue; }
            }

            // ── A parenthesised lambda parameter list, or `for (int i = …)`.
            if (t.is("(")) {
                int close = matchingParen(index);
                if (close > 0 && isArrowAfter(close)) {
                    readLambdaParameters(index, close);
                    index = close + 1;
                    atDeclarationStart = false;
                    continue;
                }
                index++;
                // Inside `for (`/`if (` a declaration may follow; a call argument
                // will simply fail the declaration patterns below.
                atDeclarationStart = true;
                continue;
            }

            // ── A bare lambda parameter: `x -> …`.
            if (t.isIdentifier() && peekIs(index + 1, "->")) {
                t.role = SemanticRole.PARAMETER;
                scope.declare(t.text, SemanticRole.PARAMETER);
                index += 2;
                atDeclarationStart = false;
                continue;
            }

            if (atDeclarationStart) {
                // A label: `outer:` at the start of a statement.
                if (t.isIdentifier() && peekIs(index + 1, ":") && !peekIs(index + 2, ":")) {
                    t.role = SemanticRole.LABEL;
                    index += 2;
                    continue;
                }
                if (readEnumConstant()) { atDeclarationStart = false; continue; }
                if (readMethodDeclaration()) { atDeclarationStart = false; continue; }
                if (readVariableDeclaration()) { atDeclarationStart = false; continue; }
            }

            classifyExpressionToken();
            atDeclarationStart = false;
        }

        // Close any scope left open by unbalanced braces.
        JavaToken last = tokens.get(tokens.size() - 1);
        while (scope != root) {
            scope.endToken = tokens.size() - 1;
            scope.endLine = last.line;
            scope = scope.parent;
            if (enumBodies.size() > 1) enumBodies.pop();
        }
        root.endToken = tokens.size() - 1;
        root.endLine = last.line;
        return root;
    }

    // ─── Declarations ───────────────────────────────────────────────────────

    private void readPackage() {
        index++; // `package`
        while (index < tokens.size()) {
            JavaToken t = tokens.get(index);
            if (t.is(";") || t.kind == JavaToken.Kind.EOF) return;
            if (t.isIdentifier()) t.role = SemanticRole.PLAIN;
            index++;
        }
    }

    /** {@code import a.b.C;} — the final segment is the type, the rest is the package. */
    private void readImport() {
        int importToken = index;
        index++; // `import`
        if (index < tokens.size() && tokens.get(index).isKeyword("static")) index++;

        int lastIdentifier = -1;
        while (index < tokens.size()) {
            JavaToken t = tokens.get(index);
            if (t.is(";") || t.kind == JavaToken.Kind.EOF) break;
            if (t.isIdentifier()) { t.role = SemanticRole.PLAIN; lastIdentifier = index; }
            index++;
        }
        if (lastIdentifier >= 0) {
            tokens.get(lastIdentifier).role = SemanticRole.TYPE;
        }
        AstNode node = new AstNode(AstNode.Kind.IMPORT, null, root);
        node.startToken = importToken;
        node.endToken = index;
        node.startLine = tokens.get(importToken).line;
        node.endLine = tokens.get(Math.min(index, tokens.size() - 1)).line;
    }

    private boolean isTypeDeclarationKeyword(JavaToken t) {
        if (t.isKeyword("class") || t.isKeyword("interface") || t.isKeyword("enum")) return true;
        // `record` and `sealed`/`non-sealed` are contextual: only a keyword when a
        // name follows, otherwise they are ordinary identifiers.
        if (t.isIdentifier() && t.text.equals("record")) {
            JavaToken next = peek(index + 1);
            return next != null && next.isIdentifier();
        }
        return false;
    }

    private void readTypeDeclaration() {
        JavaToken keyword = tokens.get(index);
        keyword.role = SemanticRole.KEYWORD;
        boolean isEnum = keyword.text.equals("enum");
        index++;

        String name = null;
        JavaToken nameToken = peek(index);
        if (nameToken != null && nameToken.isIdentifier()) {
            nameToken.role = SemanticRole.TYPE;
            name = nameToken.text;
            declaredTypes.add(name);
            // Visible from the enclosing scope so sibling code resolves it.
            scope.declare(name, SemanticRole.TYPE);
            index = indexOf(nameToken) + 1;
        }

        AstNode node = new AstNode(AstNode.Kind.TYPE, name, scope);
        node.startToken = indexOf(keyword);
        node.startLine = keyword.line;
        if (name != null) node.declare(name, SemanticRole.TYPE);

        // Type parameters: `<T, U extends V>`.
        JavaToken next = peek(index);
        if (next != null && next.is("<")) {
            int after = readTypeArguments(indexOf(next), true);
            if (after > index) index = after;
        }

        // A record header declares its components as fields.
        next = peek(index);
        if (next != null && next.is("(")) {
            readParameters(node, false);
            for (AstNode child : node.children) {
                if (child.kind == AstNode.Kind.PARAMETERS) break;
            }
        }

        pendingScope = node;
        pendingIsEnum = isEnum;
    }

    private boolean pendingIsEnum;

    /**
     * Recognises an enum constant — an identifier at the start of a declaration
     * inside an enum body, followed by {@code , ; ( } or a class body.
     */
    private boolean readEnumConstant() {
        if (!Boolean.TRUE.equals(enumBodies.peek())) return false;
        JavaToken t = peek(index);
        if (t == null || !t.isIdentifier()) return false;
        JavaToken next = peek(index + 1);
        if (next == null) return false;
        if (!(next.is(",") || next.is(";") || next.is("(") || next.is("{"))) return false;

        t.role = SemanticRole.FIELD;
        AstNode typeBody = scope.enclosing(AstNode.Kind.TYPE);
        if (typeBody != null) typeBody.declare(t.text, SemanticRole.FIELD);
        index = indexOf(t) + 1;
        return true;
    }

    /**
     * Recognises {@code [Type] name(params)} — a method or constructor.
     *
     * @return true when a declaration was consumed
     */
    private boolean readMethodDeclaration() {
        int save = index;
        int cursor = index;

        // A constructor has no return type: `Name(` where Name is the enclosing type.
        JavaToken first = peek(cursor);
        if (first == null || first.kind == JavaToken.Kind.EOF) return false;

        AstNode enclosingType = scope.enclosing(AstNode.Kind.TYPE);
        boolean constructor = first.isIdentifier()
                && enclosingType != null && first.text.equals(enclosingType.name)
                && peekIs(indexOf(first) + 1, "(");

        int nameIndex;
        if (constructor) {
            nameIndex = indexOf(first);
        } else {
            int afterType = tryReadType(cursor);
            if (afterType < 0) { index = save; return false; }
            JavaToken nameToken = peek(afterType);
            if (nameToken == null || !nameToken.isIdentifier()) { index = save; return false; }
            nameIndex = indexOf(nameToken);
            if (!peekIs(nameIndex + 1, "(")) { index = save; return false; }
            markType(cursor, afterType);
        }

        JavaToken nameToken = tokens.get(nameIndex);
        nameToken.role = SemanticRole.METHOD;
        declaredMethods.add(nameToken.text);

        AstNode node = new AstNode(AstNode.Kind.METHOD, nameToken.text, scope);
        node.startToken = save;
        node.startLine = tokens.get(save).line;

        index = nameIndex + 1;
        readParameters(node, false);

        // `throws A, B` and a possible `default value` on annotation members.
        JavaToken next = peek(index);
        while (next != null && !next.is("{") && !next.is(";") && next.kind != JavaToken.Kind.EOF) {
            if (next.kind == JavaToken.Kind.KEYWORD && TYPE_LIST_KEYWORDS.contains(next.text)) {
                index = indexOf(next) + 1;
                readTypeList();
            } else {
                classifyExpressionTokenAt(indexOf(next));
                index = indexOf(next) + 1;
            }
            next = peek(index);
        }

        if (next != null && next.is("{")) {
            pendingScope = node;
            pendingIsEnum = false;
        } else {
            // Abstract or interface method — no body, so close the node now.
            node.endToken = index;
            node.endLine = next != null ? next.line : node.startLine;
        }
        return true;
    }

    /**
     * Recognises {@code Type name}, {@code Type name = …}, {@code var name = …}
     * and multi-declarator forms. Fields and locals are told apart by whether the
     * current scope is a class body.
     *
     * @return true when a declaration was consumed
     */
    private boolean readVariableDeclaration() {
        int save = index;
        int afterType = tryReadType(index);
        if (afterType < 0) return false;

        JavaToken nameToken = peek(afterType);
        if (nameToken == null || !nameToken.isIdentifier()) { index = save; return false; }

        JavaToken after = peek(indexOf(nameToken) + 1);
        if (after == null) { index = save; return false; }
        boolean declarator = after.is("=") || after.is(";") || after.is(",")
                || after.is(":")               // enhanced for: `for (Type x : xs)`
                || after.is(")");              // single-parameter shapes
        if (!declarator) { index = save; return false; }

        markType(save, afterType);

        SemanticRole role = scope.isTypeBody() ? SemanticRole.FIELD : SemanticRole.LOCAL;
        AstNode.Kind nodeKind = scope.isTypeBody() ? AstNode.Kind.FIELD : AstNode.Kind.LOCAL_VARIABLE;

        AstNode node = new AstNode(nodeKind, nameToken.text, scope);
        node.startToken = save;
        node.startLine = tokens.get(save).line;

        nameToken.role = role;
        scope.declare(nameToken.text, role);
        index = indexOf(nameToken) + 1;

        // Further declarators after a comma: `int a = 1, b = 2;`
        while (true) {
            int commaAt = skipToDeclaratorSeparator();
            if (commaAt < 0) break;
            index = commaAt + 1;
            JavaToken extra = peek(index);
            if (extra == null || !extra.isIdentifier()) break;
            extra.role = role;
            scope.declare(extra.text, role);
            index = indexOf(extra) + 1;
        }

        node.endToken = index;
        node.endLine = tokens.get(Math.min(index, tokens.size() - 1)).line;
        return true;
    }

    /**
     * Advances through an initialiser expression looking for a {@code ,} that
     * separates declarators, classifying what it passes.
     *
     * @return the index of the comma, or -1 when the declaration ends first
     */
    private int skipToDeclaratorSeparator() {
        int depth = 0;
        while (index < tokens.size()) {
            JavaToken t = tokens.get(index);
            if (t.kind == JavaToken.Kind.EOF) return -1;
            if (isComment(t)) { index++; continue; }
            if (t.is("(") || t.is("[") || t.is("{")) depth++;
            else if (t.is(")") || t.is("]") || t.is("}")) {
                if (depth == 0) return -1;
                depth--;
            } else if (t.is(";") && depth == 0) return -1;
            else if (t.is(",") && depth == 0) return index;
            classifyExpressionTokenAt(index);
            index++;
        }
        return -1;
    }

    /**
     * Reads a parenthesised parameter list, declaring each parameter into
     * {@code owner}. Assumes the current token is {@code (}.
     *
     * @param bareAllowed accept {@code catch (E | F e)} style union types
     */
    private void readParameters(AstNode owner, boolean bareAllowed) {
        JavaToken open = peek(index);
        if (open == null || !open.is("(")) return;
        int close = matchingParen(indexOf(open));
        if (close < 0) close = tokens.size() - 1;

        AstNode params = new AstNode(AstNode.Kind.PARAMETERS, null, owner);
        params.startToken = indexOf(open);
        params.endToken = close;
        params.startLine = open.line;
        params.endLine = tokens.get(close).line;

        int cursor = indexOf(open) + 1;
        while (cursor < close) {
            JavaToken t = tokens.get(cursor);
            if (isComment(t) || t.is(",")) { cursor++; continue; }
            if (t.kind == JavaToken.Kind.AT) {
                int save = index;
                index = cursor;
                readAnnotationType();
                cursor = index;
                index = save;
                continue;
            }
            if (t.kind == JavaToken.Kind.KEYWORD && MODIFIERS.contains(t.text)) { cursor++; continue; }
            if (t.isKeyword("final")) { cursor++; continue; }

            int afterType = tryReadType(cursor);
            if (afterType > cursor) {
                JavaToken nameToken = peek(afterType);
                if (nameToken != null && nameToken.isIdentifier() && indexOf(nameToken) < close) {
                    markType(cursor, afterType);
                    nameToken.role = SemanticRole.PARAMETER;
                    owner.declare(nameToken.text, SemanticRole.PARAMETER);
                    cursor = indexOf(nameToken) + 1;
                    continue;
                }
                if (bareAllowed) {
                    // `catch (IOException | SQLException e)` — the union types are
                    // type references even before the name is reached.
                    markType(cursor, afterType);
                    cursor = afterType;
                    continue;
                }
            }
            classifyExpressionTokenAt(cursor);
            cursor++;
        }
        index = close + 1;
    }

    /** Declares the parameters of {@code (a, b) -> …} between the parentheses. */
    private void readLambdaParameters(int open, int close) {
        int cursor = open + 1;
        while (cursor < close) {
            JavaToken t = tokens.get(cursor);
            if (isComment(t) || t.is(",")) { cursor++; continue; }

            int afterType = tryReadType(cursor);
            JavaToken nameToken = afterType > cursor ? peek(afterType) : null;
            if (nameToken != null && nameToken.isIdentifier() && indexOf(nameToken) < close) {
                markType(cursor, afterType);
                nameToken.role = SemanticRole.PARAMETER;
                scope.declare(nameToken.text, SemanticRole.PARAMETER);
                cursor = indexOf(nameToken) + 1;
                continue;
            }
            if (t.isIdentifier()) {
                t.role = SemanticRole.PARAMETER;
                scope.declare(t.text, SemanticRole.PARAMETER);
                cursor++;
                continue;
            }
            classifyExpressionTokenAt(cursor);
            cursor++;
        }
    }

    /** Reads a comma-separated list of type references. */
    private void readTypeList() {
        while (true) {
            int after = tryReadType(index);
            if (after < 0) return;
            markType(index, after);
            index = after;
            JavaToken next = peek(index);
            if (next != null && (next.is(",") || next.is("&"))) {
                index = indexOf(next) + 1;
                continue;
            }
            return;
        }
    }

    /** Reads one type reference at the current position and marks it. */
    private void readTypeReference(boolean advance) {
        int after = tryReadType(index);
        if (after < 0) return;
        markType(index, after);
        if (advance) index = after;
    }

    /**
     * {@code @Foo}, {@code @foo.Bar}, {@code @Foo(args)} — colours the {@code @}
     * and the name, leaving the argument list to normal classification.
     *
     * @return true when an annotation was consumed
     */
    private boolean readAnnotationType() {
        JavaToken at = peek(index);
        if (at == null || at.kind != JavaToken.Kind.AT) return false;
        JavaToken next = peek(index + 1);
        // `@interface Foo` is a type declaration, not an annotation use.
        if (next != null && next.isKeyword("interface")) {
            at.role = SemanticRole.KEYWORD;
            index = indexOf(next);
            readTypeDeclaration();
            return true;
        }
        if (next == null || !next.isIdentifier()) return false;

        at.role = SemanticRole.ANNOTATION;
        int cursor = indexOf(next);
        while (cursor < tokens.size()) {
            JavaToken t = tokens.get(cursor);
            if (t.isIdentifier()) {
                t.role = SemanticRole.ANNOTATION;
                cursor++;
                JavaToken dot = peek(cursor);
                if (dot != null && dot.is(".")) {
                    dot.role = SemanticRole.ANNOTATION;
                    cursor = indexOf(dot) + 1;
                    continue;
                }
            }
            break;
        }
        index = cursor;
        return true;
    }

    // ─── Type recognition ───────────────────────────────────────────────────

    /**
     * Tries to read a type reference — a primitive, or a possibly qualified name
     * with optional generic arguments and array brackets.
     *
     * @param from token index to start at
     * @return the index just past the type, or -1 when there is no type here
     */
    private int tryReadType(int from) {
        int cursor = skipComments(from);
        if (cursor >= tokens.size()) return -1;
        JavaToken t = tokens.get(cursor);

        if (t.kind == JavaToken.Kind.KEYWORD && PRIMITIVES.contains(t.text)) {
            cursor++;
        } else if (t.isIdentifier() && t.text.equals("var")) {
            return cursor + 1;                      // `var` needs no further parsing
        } else if (t.isIdentifier()) {
            cursor++;
            // Qualified name: a.b.C
            while (true) {
                int dot = skipComments(cursor);
                if (dot >= tokens.size() || !tokens.get(dot).is(".")) break;
                int nameAt = skipComments(dot + 1);
                if (nameAt >= tokens.size() || !tokens.get(nameAt).isIdentifier()) break;
                cursor = nameAt + 1;
            }
        } else {
            return -1;
        }

        // Generic arguments.
        int lt = skipComments(cursor);
        if (lt < tokens.size() && tokens.get(lt).is("<")) {
            int after = readTypeArguments(lt, false);
            if (after < 0) return -1;               // `a < b` is a comparison
            cursor = after;
        }

        // Array brackets, including `int[][]`.
        while (true) {
            int open = skipComments(cursor);
            if (open + 1 >= tokens.size()) break;
            if (!tokens.get(open).is("[")) break;
            int close = skipComments(open + 1);
            if (close >= tokens.size() || !tokens.get(close).is("]")) break;
            cursor = close + 1;
        }
        // Varargs.
        int ellipsis = skipComments(cursor);
        if (ellipsis < tokens.size() && tokens.get(ellipsis).is("...")) cursor = ellipsis + 1;

        return cursor;
    }

    /**
     * Reads a balanced {@code <…>} generic argument list, marking the names
     * inside as types.
     *
     * @param declaration true for a type-parameter declaration, where the names
     *                    are also declared in the current scope
     * @return the index just past {@code >}, or -1 when the {@code <} is an operator
     */
    private int readTypeArguments(int ltIndex, boolean declaration) {
        int depth = 0;
        int cursor = ltIndex;
        while (cursor < tokens.size()) {
            JavaToken t = tokens.get(cursor);
            if (t.kind == JavaToken.Kind.EOF) return -1;
            if (isComment(t)) { cursor++; continue; }

            if (t.is("<")) { depth++; t.role = SemanticRole.OPERATOR; cursor++; continue; }
            if (t.is(">")) {
                depth--;
                t.role = SemanticRole.OPERATOR;
                cursor++;
                if (depth == 0) return cursor;
                continue;
            }
            if (t.is(">>")) {
                // A nested closing pair lexed as a shift.
                depth -= 2;
                t.role = SemanticRole.OPERATOR;
                cursor++;
                if (depth <= 0) return cursor;
                continue;
            }
            if (t.is(">>>")) {
                depth -= 3;
                t.role = SemanticRole.OPERATOR;
                cursor++;
                if (depth <= 0) return cursor;
                continue;
            }
            if (t.is(",") || t.is("?") || t.is(".") || t.is("[") || t.is("]") || t.is("&")) {
                cursor++;
                continue;
            }
            if (t.kind == JavaToken.Kind.AT) {
                int save = index;
                index = cursor;
                readAnnotationType();
                cursor = index;
                index = save;
                continue;
            }
            if (t.isKeyword("extends") || t.isKeyword("super")) { cursor++; continue; }
            if (t.kind == JavaToken.Kind.KEYWORD && PRIMITIVES.contains(t.text)) { cursor++; continue; }
            if (t.isIdentifier()) {
                t.role = SemanticRole.TYPE;
                if (declaration && depth == 1) scope.declare(t.text, SemanticRole.TYPE);
                cursor++;
                continue;
            }
            // Anything else means this was not a generic list after all.
            return -1;
        }
        return -1;
    }

    /**
     * Marks the tokens of an already-validated type reference spanning
     * {@code [from, to)}. Qualifier segments stay plain; only segments that look
     * like type names are coloured as types.
     */
    private void markType(int from, int to) {
        int lastIdentifier = -1;
        for (int i = from; i < to && i < tokens.size(); i++) {
            JavaToken t = tokens.get(i);
            if (t.isIdentifier()) {
                if (t.text.equals("var")) {
                    t.role = SemanticRole.KEYWORD;
                    continue;
                }
                lastIdentifier = i;
                // Uppercase-initial segments are type names; lowercase ones are
                // package qualifiers, unless already resolved as a type.
                boolean typeLike = Character.isUpperCase(t.text.charAt(0))
                        || scope.resolve(t.text) == SemanticRole.TYPE;
                t.role = typeLike ? SemanticRole.TYPE : SemanticRole.PLAIN;
            }
        }
        // The final segment of a qualified name is always the type itself.
        if (lastIdentifier >= 0 && tokens.get(lastIdentifier).role != SemanticRole.KEYWORD) {
            tokens.get(lastIdentifier).role = SemanticRole.TYPE;
        }
    }

    // ─── Expression classification ──────────────────────────────────────────

    private void classifyExpressionToken() {
        classifyExpressionTokenAt(index);
        index++;
    }

    /**
     * Assigns a role to a token inside an expression: method calls, field
     * accesses, and names resolved against the scope chain.
     */
    private void classifyExpressionTokenAt(int at) {
        if (at < 0 || at >= tokens.size()) return;
        JavaToken t = tokens.get(at);
        if (!t.isIdentifier()) return;
        if (t.role != SemanticRole.PLAIN) return;    // already classified

        JavaToken prev = peekBack(at - 1);
        JavaToken next = peek(at + 1);

        boolean afterDot = prev != null && (prev.is(".") || prev.is("::"));
        boolean called = next != null && next.is("(");

        if (called) {
            t.role = SemanticRole.METHOD;
            return;
        }
        if (next != null && next.is("::")) {
            // `Foo::bar` — the left side is a type or a value depending on case.
            t.role = classifyName(t, afterDot);
            return;
        }
        t.role = classifyName(t, afterDot);
    }

    /** Resolves a plain name using the scope chain, then falls back to casing. */
    private SemanticRole classifyName(JavaToken t, boolean afterDot) {
        SemanticRole declared = scope.resolve(t.text);
        if (declared != null) return declared;

        if (Character.isUpperCase(t.text.charAt(0))) {
            // ALL_CAPS after a dot reads as a constant, not a type.
            if (afterDot && isScreamingCase(t.text)) return SemanticRole.FIELD;
            return SemanticRole.TYPE;
        }
        return afterDot ? SemanticRole.FIELD : SemanticRole.PLAIN;
    }

    private static boolean isScreamingCase(String s) {
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            }
        }
        return hasLetter;
    }

    // ─── Scope bookkeeping ──────────────────────────────────────────────────

    private void openScope(JavaToken brace) {
        AstNode node;
        boolean isEnum = false;
        if (pendingScope != null) {
            node = pendingScope;
            isEnum = pendingIsEnum;
            pendingScope = null;
            pendingIsEnum = false;
        } else {
            node = new AstNode(AstNode.Kind.BLOCK, null, scope);
            node.startToken = indexOf(brace);
            node.startLine = brace.line;
        }
        scope = node;
        enumBodies.push(isEnum);
    }

    private void closeScope(JavaToken brace) {
        if (scope == root) return;
        scope.endToken = indexOf(brace);
        scope.endLine = brace.line;
        scope = scope.parent;
        if (enumBodies.size() > 1) enumBodies.pop();
    }

    // ─── Token navigation ───────────────────────────────────────────────────

    private static boolean isComment(JavaToken t) {
        return t.kind == JavaToken.Kind.LINE_COMMENT
                || t.kind == JavaToken.Kind.BLOCK_COMMENT
                || t.kind == JavaToken.Kind.JAVADOC;
    }

    private int skipComments(int from) {
        int i = Math.max(0, from);
        while (i < tokens.size() && isComment(tokens.get(i))) i++;
        return i;
    }

    /** The token at or after {@code at}, skipping comments. */
    private JavaToken peek(int at) {
        int i = skipComments(at);
        if (i >= tokens.size()) return null;
        JavaToken t = tokens.get(i);
        return t.kind == JavaToken.Kind.EOF ? null : t;
    }

    /** The token at or before {@code at}, skipping comments. */
    private JavaToken peekBack(int at) {
        int i = at;
        while (i >= 0 && i < tokens.size() && isComment(tokens.get(i))) i--;
        if (i < 0 || i >= tokens.size()) return null;
        return tokens.get(i);
    }

    private boolean peekIs(int at, String symbol) {
        JavaToken t = peek(at);
        return t != null && t.is(symbol);
    }

    /** Index of a token, found by its start offset. */
    private int indexOf(JavaToken token) {
        // Tokens are ordered by offset, so a binary search is exact and cheap.
        int lo = 0, hi = tokens.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = Integer.compare(tokens.get(mid).start, token.start);
            if (cmp == 0) return mid;
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return Math.min(lo, tokens.size() - 1);
    }

    /** Index of the {@code )} matching the {@code (} at {@code openIndex}, or -1. */
    private int matchingParen(int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            JavaToken t = tokens.get(i);
            if (t.kind == JavaToken.Kind.EOF) return -1;
            if (t.is("(")) depth++;
            else if (t.is(")")) {
                depth--;
                if (depth == 0) return i;
            } else if (t.is("{") || t.is("}")) {
                // A brace inside would mean an anonymous class or a lambda body;
                // keep scanning, but bail out if the nesting looks broken.
                if (depth <= 0) return -1;
            }
        }
        return -1;
    }

    private boolean isArrowAfter(int closeParen) {
        return peekIs(closeParen + 1, "->");
    }
}
