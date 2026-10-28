package android.net;
import java.net.URLDecoder;
public class Uri {
    String q;
    public static Uri parse(String s) { Uri u = new Uri(); int i = s.indexOf('?'); u.q = i < 0 ? "" : s.substring(i + 1); return u; }
    public String getQueryParameter(String k) {
        for (String kv : q.split("&")) { int e = kv.indexOf('='); if (e > 0 && kv.substring(0, e).equals(k)) try { return URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Exception x) { throw new RuntimeException(x); } }
        return null;
    }
}
