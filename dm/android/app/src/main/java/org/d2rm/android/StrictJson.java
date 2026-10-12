package org.d2rm.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StrictJson {
    private final String input;
    private int position;
    private StrictJson(String input) { this.input = input; }
    static Object parse(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            StrictJson parser = new StrictJson(text);
            Object value = parser.value(0);
            parser.space();
            if (parser.position != text.length()) throw parser.error();
            return value;
        } catch (CharacterCodingException e) { throw new IOException("JSON 必须为有效 UTF-8", e); }
    }
    private IOException error() { return new IOException("无效或重复的 JSON 字段，位置 " + position); }
    private void space() { while (position < input.length() && " \n\r\t".indexOf(input.charAt(position)) >= 0) position++; }
    private boolean take(char c) { space(); if (position < input.length() && input.charAt(position) == c) { position++; return true; } return false; }
    private Object value(int depth) throws IOException {
        if (depth > 32) throw error();
        space();
        if (position == input.length()) throw error();
        char c = input.charAt(position);
        if (c == '"') return string();
        if (take('{')) {
            Map<String,Object> object = new LinkedHashMap<>();
            if (take('}')) return object;
            do {
                space();
                if (position == input.length() || input.charAt(position) != '"') throw error();
                String key = string();
                if (!take(':') || object.containsKey(key)) throw error();
                object.put(key, value(depth + 1));
            } while (take(','));
            if (!take('}')) throw error();
            return object;
        }
        if (take('[')) {
            List<Object> array = new ArrayList<>();
            if (take(']')) return array;
            do { array.add(value(depth + 1)); } while (take(','));
            if (!take(']')) throw error();
            return array;
        }
        for (String literal : new String[]{"true", "false", "null"}) {
            if (input.startsWith(literal, position)) { position += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
        }
        int start = position;
        if (c == '-') position++;
        int digits = position;
        while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9') position++;
        if (digits == position || (position - digits > 1 && input.charAt(digits) == '0')) throw error();
        try { return Long.parseLong(input.substring(start, position)); } catch (NumberFormatException e) { throw error(); }
    }
    private String string() throws IOException {
        if (!take('"')) throw error();
        StringBuilder result = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') {
                for (int i = 0; i < result.length(); i++) {
                    char u = result.charAt(i);
                    if (Character.isHighSurrogate(u)) {
                        if (++i == result.length() || !Character.isLowSurrogate(result.charAt(i))) throw error();
                    } else if (Character.isLowSurrogate(u)) throw error();
                }
                return result.toString();
            }
            if (c < 32) throw error();
            if (c == '\\') {
                if (position == input.length()) throw error();
                c = input.charAt(position++);
                switch (c) {
                    case '"', '\\', '/' -> result.append(c);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (position + 4 > input.length()) throw error();
                        for (int i = position; i < position + 4; i++) if ("0123456789abcdefABCDEF".indexOf(input.charAt(i)) < 0) throw error();
                        try { result.append((char) Integer.parseInt(input.substring(position, position + 4), 16)); }
                        catch (NumberFormatException e) { throw error(); }
                        position += 4;
                    }
                    default -> throw error();
                }
            } else result.append(c);
        }
        throw error();
    }
    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 32) b.append(String.format("\\u%04x", (int)c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
        if (value instanceof Map<?,?> map) {
            List<String> fields = new ArrayList<>();
            map.entrySet().stream().sorted((a,b) -> a.getKey().toString().compareTo(b.getKey().toString()))
                    .forEach(e -> fields.add(encode(e.getKey().toString()) + ":" + encode(e.getValue())));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof List<?> list) { List<String> items = new ArrayList<>(); for (Object item : list) items.add(encode(item)); return "[" + String.join(",", items) + "]"; }
        return value.toString();
    }
}
