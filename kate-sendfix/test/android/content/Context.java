package android.content;
import java.util.*;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final Map<String, Map<String,String>> FILES = new HashMap<>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        final Map<String,String> m = FILES.computeIfAbsent(name, k -> new HashMap<>());
        return new SharedPreferences() {
            public String getString(String k, String d) { return m.getOrDefault(k, d); }
            public Editor edit() { return new Editor() {
                public Editor putString(String k, String v) { m.put(k, v); return this; }
                public Editor remove(String k) { m.remove(k); return this; }
                public Editor clear() { m.clear(); return this; }
                public boolean commit() { return true; }
                public void apply() {}
            }; }
        };
    }
}
