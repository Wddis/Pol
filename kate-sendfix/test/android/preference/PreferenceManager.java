package android.preference;
public class PreferenceManager { public static android.content.SharedPreferences getDefaultSharedPreferences(android.content.Context c) { return c.getSharedPreferences("default", 0); } }
