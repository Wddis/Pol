package android.content;
public interface SharedPreferences {
    String getString(String k, String d);
    Editor edit();
    interface Editor { Editor putString(String k, String v); Editor remove(String k); Editor clear(); boolean commit(); void apply(); }
}
