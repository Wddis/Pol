import java.util.*;
import com.perm.kate.api.KException;
public final class e3 {
    public static final Map<String,Integer> ERR = new HashMap<>(); // token -> error code to throw
    public static final List<String> CALLS = new ArrayList<>();
    public String a;
    public e3(String s) { a = s; }
    public final org.json.JSONObject o(u70 r, boolean z) throws Exception {
        if (a != null) r.Q("access_token", a);
        String tok = r.d.get("access_token"); CALLS.add(tok);
        Integer c = ERR.get(tok);
        if (c != null) { KException k = new KException(c == 9 ? "Flood control" : "err" + c, c, "https://api.vk.ru/method/messages.send"); if (c == 14) { k.captcha_sid = "SID"; k.captcha_img = "IMG"; } throw k; }
        return new org.json.JSONObject("ok:" + tok);
    }
}
