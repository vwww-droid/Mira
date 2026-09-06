package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public JSONObject() {}

    public JSONObject(String json) {
        String text = json.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) throw new JSONException("invalid object");
        int index = 1;
        while (index < text.length() - 1) {
            while (index < text.length() - 1 && (Character.isWhitespace(text.charAt(index)) || text.charAt(index) == ',')) index++;
            if (index >= text.length() - 1) break;
            ParsedString key = parseString(text, index);
            index = key.end;
            while (Character.isWhitespace(text.charAt(index))) index++;
            if (text.charAt(index++) != ':') throw new JSONException("missing colon");
            while (Character.isWhitespace(text.charAt(index))) index++;
            if (text.charAt(index) == '"') {
                ParsedString value = parseString(text, index);
                values.put(key.value, value.value);
                index = value.end;
            } else if (text.startsWith("true", index)) {
                values.put(key.value, Boolean.TRUE);
                index += 4;
            } else if (text.startsWith("false", index)) {
                values.put(key.value, Boolean.FALSE);
                index += 5;
            } else {
                int end = index;
                while (end < text.length() - 1 && text.charAt(end) != ',') end++;
                values.put(key.value, text.substring(index, end).trim());
                index = end;
            }
        }
    }

    public JSONObject put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public String optString(String key) {
        return optString(key, "");
    }

    public String optString(String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("{");
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (result.length() > 1) result.append(',');
            result.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof Boolean || value instanceof Number) result.append(value);
            else result.append(quote(String.valueOf(value)));
        }
        return result.append('}').toString();
    }

    private static ParsedString parseString(String text, int start) {
        if (text.charAt(start) != '"') throw new JSONException("expected string");
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = start + 1; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (escaped) {
                value.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return new ParsedString(value.toString(), index + 1);
            } else {
                value.append(ch);
            }
        }
        throw new JSONException("unterminated string");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class ParsedString {
        final String value;
        final int end;

        ParsedString(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }
}
