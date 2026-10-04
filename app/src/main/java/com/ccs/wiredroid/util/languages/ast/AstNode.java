package com.ccs.wiredroid.util.languages.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in the lightweight syntax tree {@link JavaAstParser} builds.
 *
 * <p>The tree is deliberately shallow — it captures declarations and scopes, not
 * every expression — because that is exactly what semantic highlighting, the
 * structure view and folding need. Each node owns the symbols declared directly
 * inside it, which is what lets an identifier be resolved by walking up the
 * parent chain.</p>
 */
public final class AstNode {

    public enum Kind {
        COMPILATION_UNIT,
        PACKAGE,
        IMPORT,
        /** class, interface, enum, record or annotation type. */
        TYPE,
        FIELD,
        METHOD,
        /** Static or instance initialiser. */
        INITIALIZER,
        /** A braced block: method body, {@code if} body, loop body, … */
        BLOCK,
        /** Parameter list of a method, {@code catch} or lambda. */
        PARAMETERS,
        LOCAL_VARIABLE,
        LAMBDA
    }

    public final Kind kind;
    /** Declared name, or {@code null} for anonymous nodes such as blocks. */
    public final String name;
    public final AstNode parent;
    public final List<AstNode> children = new ArrayList<>();

    /** Index of the first token of this node in the token list. */
    public int startToken;
    /** Index of the last token of this node, inclusive. */
    public int endToken;
    /** Zero-based start line. */
    public int startLine;
    /** Zero-based end line. */
    public int endLine;

    /** Symbols declared directly in this scope, mapped to their role. */
    private Map<String, SemanticRole> symbols;

    AstNode(Kind kind, String name, AstNode parent) {
        this.kind = kind;
        this.name = name;
        this.parent = parent;
        if (parent != null) parent.children.add(this);
    }

    /** Records a declaration visible from this scope downwards. */
    void declare(String symbol, SemanticRole role) {
        if (symbol == null || symbol.isEmpty()) return;
        if (symbols == null) symbols = new LinkedHashMap<>();
        symbols.put(symbol, role);
    }

    /**
     * Resolves a name against this scope and every enclosing one.
     *
     * @return the declared role, or {@code null} when the name is unknown here
     */
    SemanticRole resolve(String symbol) {
        for (AstNode node = this; node != null; node = node.parent) {
            if (node.symbols != null) {
                SemanticRole role = node.symbols.get(symbol);
                if (role != null) return role;
            }
        }
        return null;
    }

    /** The nearest enclosing node of the given kind, or {@code null}. */
    public AstNode enclosing(Kind wanted) {
        for (AstNode node = this; node != null; node = node.parent) {
            if (node.kind == wanted) return node;
        }
        return null;
    }

    /** True when this scope is a class body — where variables are fields. */
    boolean isTypeBody() {
        return kind == Kind.TYPE;
    }

    @Override
    public String toString() {
        return kind + (name != null ? "(" + name + ")" : "") + "[" + startLine + ".." + endLine + "]";
    }
}
