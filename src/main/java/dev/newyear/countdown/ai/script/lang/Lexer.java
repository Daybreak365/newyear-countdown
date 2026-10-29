package dev.newyear.countdown.ai.script.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * WishScript 토크나이저. 자바스크립트 문법의 부분집합을 읽는다.
 * Minecraft 와 무관한 순수 자바 코드라 단위 테스트로 검증할 수 있다.
 */
public final class Lexer {
    public enum T { NUM, STR, TEMPLATE, IDENT, KEYWORD, PUNCT, EOF }

    /** 템플릿 문자열은 문자열 조각과 식 소스를 번갈아 담는다 (parts[0], expr[0], parts[1], ...). */
    public record Token(T type, String text, double num, int line, int col, boolean nlBefore,
                        List<String> tplParts, List<String> tplExprs, List<int[]> tplPos) {
        public boolean is(String s) {
            return (type == T.PUNCT || type == T.KEYWORD) && text.equals(s);
        }

        @Override
        public String toString() {
            return type == T.EOF ? "end of script" : "'" + text + "'";
        }
    }

    static final Set<String> KEYWORDS = Set.of(
            "let", "const", "var", "function", "return", "if", "else", "while", "do", "for", "of", "in",
            "break", "continue", "true", "false", "null", "undefined", "typeof", "try", "catch", "finally",
            "throw", "switch", "case", "default", "new", "this", "delete", "void", "class", "import", "export",
            "async", "await", "yield", "instanceof", "with");

    /** 길이가 긴 것부터 맞춰 본다. */
    private static final String[] PUNCTS = {
            ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=",
            "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "**", "<<", ">>",
            "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%", "&", "|", "^",
            "!", "~", "?", ":", "=", ".", "@", "#"
    };

    private final String src;
    private int pos = 0, line = 1, col = 1;
    private final int baseLine, baseCol;

    public Lexer(String src) {
        this(src, 0, 0);
    }

    /** 템플릿 안의 식을 읽을 때 원래 위치를 유지하기 위해 기준 줄/칸을 받는다. */
    Lexer(String src, int baseLine, int baseCol) {
        this.src = src;
        this.baseLine = baseLine;
        this.baseCol = baseCol;
    }

    private ScriptError err(String msg) {
        return new ScriptError(ScriptError.Kind.SYNTAX, msg, line + baseLine, col + baseCol);
    }

    private char peek(int o) {
        int i = pos + o;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private char next() {
        char c = src.charAt(pos++);
        if (c == '\n') { line++; col = 1; } else col++;
        return c;
    }

    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        boolean nl = false;
        while (true) {
            // 공백과 주석
            while (pos < src.length()) {
                char c = peek(0);
                if (c == '\n') { nl = true; next(); }
                else if (Character.isWhitespace(c) || c == '﻿') next();
                else if (c == '/' && peek(1) == '/') { while (pos < src.length() && peek(0) != '\n') next(); }
                else if (c == '/' && peek(1) == '*') {
                    next(); next();
                    while (pos < src.length() && !(peek(0) == '*' && peek(1) == '/')) { if (peek(0) == '\n') nl = true; next(); }
                    if (pos >= src.length()) throw err("Unterminated block comment");
                    next(); next();
                } else break;
            }
            int tl = line + baseLine, tc = col + baseCol;
            if (pos >= src.length()) {
                out.add(new Token(T.EOF, "", 0, tl, tc, true, null, null, null));
                return out;
            }
            char c = peek(0);
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                out.add(number(tl, tc, nl));
            } else if (c == '"' || c == '\'') {
                out.add(new Token(T.STR, string(c), 0, tl, tc, nl, null, null, null));
            } else if (c == '`') {
                out.add(template(tl, tc, nl));
            } else if (Character.isJavaIdentifierStart(c)) {
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && Character.isJavaIdentifierPart(peek(0))) sb.append(next());
                String w = sb.toString();
                out.add(new Token(KEYWORDS.contains(w) ? T.KEYWORD : T.IDENT, w, 0, tl, tc, nl, null, null, null));
            } else {
                String p = null;
                for (String cand : PUNCTS) {
                    if (src.startsWith(cand, pos)) { p = cand; break; }
                }
                if (p == null) throw err("Unexpected character '" + c + "'");
                // "?." 다음에 숫자가 오면 삼항 연산자 (a?.5:1)
                if (p.equals("?.") && Character.isDigit(peek(2))) p = "?";
                for (int i = 0; i < p.length(); i++) next();
                out.add(new Token(T.PUNCT, p, 0, tl, tc, nl, null, null, null));
            }
            nl = false;
        }
    }

    private Token number(int tl, int tc, boolean nl) {
        int start = pos;
        if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            next(); next();
            while (Character.digit(peek(0), 16) >= 0 || peek(0) == '_') next();
            String hex = src.substring(start + 2, pos).replace("_", "");
            if (hex.isEmpty()) throw err("Bad hex number");
            return new Token(T.NUM, src.substring(start, pos), (double) Long.parseLong(hex, 16), tl, tc, nl, null, null, null);
        }
        while (Character.isDigit(peek(0)) || peek(0) == '_') next();
        if (peek(0) == '.' && Character.isDigit(peek(1))) { next(); while (Character.isDigit(peek(0))) next(); }
        else if (peek(0) == '.' && !Character.isJavaIdentifierStart(peek(1))) next();
        if (peek(0) == 'e' || peek(0) == 'E') {
            next();
            if (peek(0) == '+' || peek(0) == '-') next();
            while (Character.isDigit(peek(0))) next();
        }
        String text = src.substring(start, pos);
        try {
            return new Token(T.NUM, text, Double.parseDouble(text.replace("_", "")), tl, tc, nl, null, null, null);
        } catch (NumberFormatException e) {
            throw err("Bad number '" + text + "'");
        }
    }

    private String string(char quote) {
        next();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || peek(0) == '\n') throw err("Unterminated string");
            char c = next();
            if (c == quote) break;
            if (c == '\\') sb.append(escape());
            else sb.append(c);
        }
        return sb.toString();
    }

    private String escape() {
        if (pos >= src.length()) throw err("Bad escape");
        char e = next();
        return switch (e) {
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            case '0' -> "\0";
            case 'b' -> "\b";
            case 'u' -> {
                if (peek(0) == '{') {
                    next();
                    StringBuilder h = new StringBuilder();
                    while (pos < src.length() && peek(0) != '}') h.append(next());
                    next();
                    yield new String(Character.toChars(Integer.parseInt(h.toString(), 16)));
                }
                String h = src.substring(pos, Math.min(src.length(), pos + 4));
                for (int i = 0; i < 4; i++) next();
                try { yield String.valueOf((char) Integer.parseInt(h, 16)); } catch (NumberFormatException ex) { throw err("Bad unicode escape"); }
            }
            case '\n' -> "";
            default -> String.valueOf(e);
        };
    }

    private Token template(int tl, int tc, boolean nl) {
        next(); // `
        List<String> parts = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        List<int[]> exprPos = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw err("Unterminated template string");
            char c = peek(0);
            if (c == '`') { next(); break; }
            if (c == '\\') { next(); sb.append(escape()); continue; }
            if (c == '$' && peek(1) == '{') {
                next(); next();
                parts.add(sb.toString());
                sb.setLength(0);
                exprPos.add(new int[]{line - 1, col - 1});
                int depth = 1;
                StringBuilder ex = new StringBuilder();
                while (true) {
                    if (pos >= src.length()) throw err("Unterminated ${ in template");
                    char d = peek(0);
                    if (d == '\'' || d == '"') {
                        // 식 안의 문자열은 그대로 복사
                        int s = pos;
                        string(d);
                        ex.append(src, s, pos);
                        continue;
                    }
                    if (d == '{') depth++;
                    if (d == '}' && --depth == 0) { next(); break; }
                    ex.append(next());
                }
                exprs.add(ex.toString());
                continue;
            }
            sb.append(next());
        }
        parts.add(sb.toString());
        return new Token(T.TEMPLATE, "`template`", 0, tl, tc, nl, parts, exprs, exprPos);
    }
}
