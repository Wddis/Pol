package android.util;
public class Log { public static int w(String t, String m) { System.out.println("  [log] " + m); return 0; } public static int e(String t, String m, Throwable x) { System.out.println("  [log] " + m + " " + x); return 0; } }
