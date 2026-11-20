package com.grocery.dbtests.lint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A MySQL migration script as statements of tokens, the way the server reads it: comments dropped ({@code -- },
 * {@code #}, {@code /* *}{@code /}; the body of a versioned comment {@code /*!80000 ... *}{@code /} is code, as MySQL
 * executes it), string literals and quoted identifiers kept whole (a {@code ;} or {@code --} inside them is text),
 * statements split at top-level {@code ;}. Keywords are compared case-insensitively. Every token knows its line.
 * {@code DELIMITER} (stored programs) is not supported and is reported, so a lint never silently misreads a file.
 */
public final class SqlScript {

    /** Token kinds. */
    public enum Kind { WORD, QUOTED_IDENTIFIER, STRING, NUMBER, SYMBOL }

    /** One token: its kind, its text (identifier without its backticks), its line (1-based). */
    public record Token(Kind kind, String text, int line) {

        /** Whether this is the keyword (or bare word) {@code word}, case-insensitively. */
        public boolean is(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }

        public boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        /** An identifier: a bare word or a backtick-quoted name. */
        public boolean isIdentifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED_IDENTIFIER;
        }

        /** Identifiers compare case-insensitively (column names always; table names on the platform's servers too). */
        public String name() {
            return text.toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return kind == Kind.QUOTED_IDENTIFIER ? "`" + text + "`" : kind == Kind.STRING ? "'" + text + "'" : text;
        }
    }

    /** One statement: its tokens and the line it starts on. */
    public record Statement(List<Token> tokens, int line) {

        public Token get(int index) {
            return index < tokens.size() ? tokens.get(index) : null;
        }

        public int size() {
            return tokens.size();
        }

        /** Whether the statement starts with these keywords (case-insensitively). */
        public boolean startsWith(String... words) {
            for (int i = 0; i < words.length; i++) {
                Token token = get(i);
                if (token == null || !token.is(words[i])) {
                    return false;
                }
            }
            return true;
        }

        /** The statement's text, tokens joined by single spaces (for messages). */
        public String text() {
            return String.join(" ", tokens.stream().map(Token::toString).toList());
        }

        @Override
        public String toString() {
            return text();
        }
    }

    private final List<Statement> statements;

    private SqlScript(List<Statement> statements) {
        this.statements = List.copyOf(statements);
    }

    public List<Statement> statements() {
        return statements;
    }

    /** Parses a whole script. */
    public static SqlScript parse(String sql) {
        return new SqlScript(new Lexer(sql).statements());
    }

    private static final class Lexer {

        private final String sql;
        private final int[] lineStarts;
        private int position;
        private int versionedCommentDepth;

        Lexer(String sql) {
            this.sql = sql;
            List<Integer> starts = new ArrayList<>(List.of(0));
            for (int i = 0; i < sql.length(); i++) {
                if (sql.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        private int line(int offset) {
            int index = Arrays.binarySearch(lineStarts, offset);
            return (index >= 0 ? index : -index - 2) + 1;
        }

        List<Statement> statements() {
            List<Statement> statements = new ArrayList<>();
            List<Token> current = new ArrayList<>();
            Token token;
            while ((token = next()) != null) {
                if (token.isSymbol(";")) {
                    if (!current.isEmpty()) {
                        statements.add(new Statement(List.copyOf(current), current.getFirst().line()));
                        current.clear();
                    }
                    continue;
                }
                if (current.isEmpty() && token.is("DELIMITER")) {
                    throw new IllegalArgumentException("Line " + token.line() + ": DELIMITER (stored programs) is not"
                            + " supported by the migration lint");
                }
                current.add(token);
            }
            if (!current.isEmpty()) {
                statements.add(new Statement(List.copyOf(current), current.getFirst().line()));
            }
            return statements;
        }

        private Token next() {
            while (position < sql.length()) {
                char c = sql.charAt(position);
                if (Character.isWhitespace(c)) {
                    position++;
                } else if (c == '#' || (c == '-' && startsWith("--") && (position + 2 >= sql.length()
                        || Character.isWhitespace(sql.charAt(position + 2)) || Character.isISOControl(sql.charAt(position + 2))))) {
                    int end = sql.indexOf('\n', position);
                    position = end < 0 ? sql.length() : end + 1;
                } else if (startsWith("/*!")) {
                    position += 3;
                    while (position < sql.length() && Character.isDigit(sql.charAt(position))) {
                        position++;                                     // the server version: the body is code
                    }
                    versionedCommentDepth++;
                } else if (startsWith("*/") && versionedCommentDepth > 0) {
                    position += 2;
                    versionedCommentDepth--;
                } else if (startsWith("/*")) {
                    int end = sql.indexOf("*/", position + 2);
                    if (end < 0) {
                        throw new IllegalArgumentException("Line " + line(position) + ": unterminated comment");
                    }
                    position = end + 2;
                } else {
                    return token(c);
                }
            }
            return null;
        }

        private boolean startsWith(String text) {
            return sql.startsWith(text, position);
        }

        private Token token(char c) {
            int start = position;
            int line = line(start);
            if (c == '\'' || c == '"') {
                return new Token(Kind.STRING, quoted(c), line);
            }
            if (c == '`') {
                return new Token(Kind.QUOTED_IDENTIFIER, quoted('`'), line);
            }
            if (Character.isDigit(c) || (c == '.' && position + 1 < sql.length()
                    && Character.isDigit(sql.charAt(position + 1)))) {
                while (position < sql.length() && (Character.isLetterOrDigit(sql.charAt(position))
                        || sql.charAt(position) == '.')) {
                    position++;
                }
                return new Token(Kind.NUMBER, sql.substring(start, position), line);
            }
            if (Character.isLetter(c) || c == '_' || c == '$' || c == '@') {
                while (position < sql.length() && (Character.isLetterOrDigit(sql.charAt(position))
                        || sql.charAt(position) == '_' || sql.charAt(position) == '$' || sql.charAt(position) == '@')) {
                    position++;
                }
                return new Token(Kind.WORD, sql.substring(start, position), line);
            }
            position++;
            return new Token(Kind.SYMBOL, String.valueOf(c), line);
        }

        /** A quoted literal or identifier; a doubled quote and (outside backticks) a backslash escape the next char. */
        private String quoted(char quote) {
            int startLine = line(position);
            StringBuilder text = new StringBuilder();
            position++;
            while (position < sql.length()) {
                char c = sql.charAt(position);
                if (c == '\\' && quote != '`' && position + 1 < sql.length()) {
                    text.append(sql.charAt(position + 1));
                    position += 2;
                } else if (c == quote) {
                    if (position + 1 < sql.length() && sql.charAt(position + 1) == quote) {
                        text.append(quote);
                        position += 2;
                    } else {
                        position++;
                        return text.toString();
                    }
                } else {
                    text.append(c);
                    position++;
                }
            }
            throw new IllegalArgumentException("Line " + startLine + ": unterminated " + quote + " quote");
        }
    }
}
