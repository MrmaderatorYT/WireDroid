package com.ccs.wiredroid.ai;

/** Applies one unambiguous, whitespace-preserving text replacement. */
public final class ExactTextEdit {

    public enum Status {
        APPLIED,
        EMPTY_FIND,
        NOT_FOUND,
        AMBIGUOUS
    }

    public static final class Result {
        public final Status status;
        public final String text;
        public final int offset;

        private Result(Status status, String text, int offset) {
            this.status = status;
            this.text = text;
            this.offset = offset;
        }

        public boolean applied() {
            return status == Status.APPLIED;
        }
    }

    private ExactTextEdit() {}

    public static Result apply(String source, String find, String replacement) {
        String input = source == null ? "" : source;
        if (find == null || find.isEmpty()) {
            return new Result(Status.EMPTY_FIND, input, -1);
        }
        int at = input.indexOf(find);
        if (at < 0) {
            return new Result(Status.NOT_FOUND, input, -1);
        }
        if (input.indexOf(find, at + 1) >= 0) {
            return new Result(Status.AMBIGUOUS, input, -1);
        }
        String value = replacement == null ? "" : replacement;
        return new Result(Status.APPLIED,
                input.substring(0, at) + value + input.substring(at + find.length()), at);
    }

    /**
     * Removes a surrounding markdown fence without trimming the code it contains.
     * Unfenced values are returned byte-for-byte.
     */
    public static String unwrapFence(String value) {
        if (value == null) return "";
        if (!value.startsWith("```")) return value;

        int firstBreak = value.indexOf('\n');
        if (firstBreak < 0) return value;
        int closing = value.lastIndexOf("```");
        if (closing <= firstBreak || !value.substring(closing + 3).trim().isEmpty()) {
            return value;
        }

        String code = value.substring(firstBreak + 1, closing);
        // Markdown code fences commonly put their closing marker on the line after
        // the code. Remove that framing newline, not whitespace that belongs to code.
        if (code.endsWith("\r\n")) return code.substring(0, code.length() - 2);
        if (code.endsWith("\n")) return code.substring(0, code.length() - 1);
        return code;
    }
}
