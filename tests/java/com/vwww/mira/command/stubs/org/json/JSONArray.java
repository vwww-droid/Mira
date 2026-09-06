package org.json;

import java.util.ArrayList;
import java.util.List;

public class JSONArray {
    private final List<Object> values = new ArrayList<>();

    public JSONArray put(Object value) {
        values.add(value);
        return this;
    }

    public int length() {
        return values.size();
    }

    public String optString(int index, String fallback) {
        if (index < 0 || index >= values.size()) return fallback;
        Object value = values.get(index);
        return value instanceof String ? (String) value : fallback;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("[");
        for (Object value : values) {
            if (result.length() > 1) result.append(',');
            result.append(JSONObject.valueToString(value));
        }
        return result.append(']').toString();
    }
}
