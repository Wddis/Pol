package android.widget;
public class Toast { public static final int LENGTH_LONG = 1; public static String last; String t;
  public static Toast makeText(android.content.Context c, CharSequence s, int d) { Toast x = new Toast(); x.t = s.toString(); return x; } public void show() { last = t; } }
