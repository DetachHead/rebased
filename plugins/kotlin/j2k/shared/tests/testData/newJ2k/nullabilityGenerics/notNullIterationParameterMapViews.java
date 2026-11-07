import java.util.*;

class Totals {
    private final Map<String, Integer> totals = new HashMap<>();

    void put(String key, Integer amount) {
        totals.put(key, amount);
    }

    int sum() {
        int sum = 0;
        for (Integer value : totals.values()) {
            sum += value;
        }
        return sum;
    }

    int length() {
        int length = 0;
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            String name = entry.getKey();
            length += name.length();
        }
        return length;
    }
}
