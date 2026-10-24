package com.gaiys.proto.prototypehub.storage.parser;

import java.util.function.Consumer;

/**
 * 只提取 CSS 字面 url() 与引号形式 @import；线性跳过注释、普通字符串和转义，不解释脚本或变量。
 *
 * <p>输入大小由交付包扫描器限制；不下载导入内容，每份清单 CSS 独立扫描，循环导入不会递归。
 */
public final class StaticCssReferences {
    private StaticCssReferences() {}

    public static void scan(String css, Consumer<String> reference) {
        int position = 0;
        while (position < css.length()) {
            int next = whitespace(css, position);
            if (next != position) {
                position = next;
                continue;
            }
            char value = css.charAt(position);
            if (value == '\'' || value == '"') {
                position = quoted(css, position).end();
            } else if (value == '@' && keyword(css, position + 1, "import")) {
                position = whitespace(css, position + 7);
                if (position < css.length()
                        && (css.charAt(position) == '\'' || css.charAt(position) == '"')) {
                    Token token = quoted(css, position);
                    if (token.valid()) reference.accept(token.text());
                    position = token.end();
                }
            } else if (keyword(css, position, "url")) {
                int open = whitespace(css, position + 3);
                if (open < css.length() && css.charAt(open) == '(') {
                    Token token = url(css, open + 1);
                    if (token.valid()) reference.accept(token.text());
                    position = token.end();
                } else position += 3;
            } else position++;
        }
    }

    private static Token url(String css, int position) {
        position = whitespace(css, position);
        if (position < css.length()
                && (css.charAt(position) == '\'' || css.charAt(position) == '"')) {
            Token token = quoted(css, position);
            int close = whitespace(css, token.end());
            boolean valid = token.valid() && close < css.length() && css.charAt(close) == ')';
            return new Token(
                    token.text(), valid ? close + 1 : Math.max(close, position + 1), valid);
        }
        StringBuilder text = new StringBuilder();
        while (position < css.length()) {
            char value = css.charAt(position++);
            if (value == ')') return new Token(text.toString(), position, true);
            if (value == '\\') position = escape(css, position, text);
            else if (Character.isWhitespace(value)) {
                int close = whitespace(css, position);
                boolean valid = close < css.length() && css.charAt(close) == ')';
                return new Token(text.toString(), valid ? close + 1 : close, valid);
            } else if (value == '\'' || value == '"' || value == '(' || value < 32)
                return new Token("", position, false);
            else text.append(value);
        }
        return new Token("", position, false);
    }

    private static Token quoted(String css, int position) {
        char quote = css.charAt(position++);
        StringBuilder text = new StringBuilder();
        while (position < css.length()) {
            char value = css.charAt(position++);
            if (value == quote) return new Token(text.toString(), position, true);
            if (value == '\\') position = escape(css, position, text);
            else if (value == '\n' || value == '\r' || value == '\f')
                return new Token("", position, false);
            else text.append(value);
        }
        return new Token("", position, false);
    }

    private static int escape(String css, int position, StringBuilder text) {
        if (position == css.length()) return position;
        char value = css.charAt(position);
        if (value == '\n' || value == '\r' || value == '\f') {
            position++;
            if (value == '\r' && position < css.length() && css.charAt(position) == '\n')
                position++;
            return position;
        }
        int start = position;
        int code = 0;
        while (position < css.length() && position - start < 6) {
            int digit = Character.digit(css.charAt(position), 16);
            if (digit < 0) break;
            code = code * 16 + digit;
            position++;
        }
        if (position == start) text.append(css.charAt(position++));
        else {
            text.appendCodePoint(
                    code == 0
                                    || !Character.isValidCodePoint(code)
                                    || (code >= 0xd800 && code <= 0xdfff)
                            ? 0xfffd
                            : code);
            if (position < css.length() && Character.isWhitespace(css.charAt(position))) {
                char space = css.charAt(position++);
                if (space == '\r' && position < css.length() && css.charAt(position) == '\n')
                    position++;
            }
        }
        return position;
    }

    private static int whitespace(String css, int position) {
        while (position < css.length()) {
            if (Character.isWhitespace(css.charAt(position))) position++;
            else if (css.startsWith("/*", position)) {
                int close = css.indexOf("*/", position + 2);
                position = close < 0 ? css.length() : close + 2;
            } else break;
        }
        return position;
    }

    private static boolean keyword(String css, int position, String word) {
        int end = position + word.length();
        return css.regionMatches(true, position, word, 0, word.length())
                && (position == 0 || !identifier(css.charAt(position - 1)))
                && (end == css.length() || !identifier(css.charAt(end)));
    }

    private static boolean identifier(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '\\';
    }

    private record Token(String text, int end, boolean valid) {}
}
