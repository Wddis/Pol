import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;
import android.widget.Toast;
import com.perm.kate.KApplication;
import com.perm.kate.api.KException;

public class SendFixTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static void reset() {
        Context.FILES.clear();
        e3.ERR.clear();
        e3.CALLS.clear();
    }

    static u70 req() {
        u70 r = new u70("messages.send");
        r.Q("peer_id", "1");
        r.Q("random_id", "42");
        return r;
    }

    static KException sendExpectingError(e3 main, String uid) {
        try {
            KateSendFix.send(main, req(), uid);
            return null;
        } catch (KException e) {
            return e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] a) throws Exception {
        KApplication.d = new KApplication();
        e3 main = new e3("KATE");

        reset();
        check("no send token: main token used", KateSendFix.send(main, req(), "100").v.equals("ok:KATE"));

        reset();
        e3.ERR.put("KATE", 9);
        KException e = sendExpectingError(main, "100");
        check("no send token, server error 9: code kept", e != null && e.error_code == 9);
        check("no send token, server error 9: server text kept + Kate quota hint", e.getMessage().startsWith("Flood control") && e.getMessage().contains("2685278"));

        reset();
        KApplication.d.getSharedPreferences("kate_send_tokens", 0).edit().putString("token_100", "OWN").commit();
        check("send token: request goes with own token", KateSendFix.send(main, req(), "100").v.equals("ok:OWN"));
        check("send token: exactly one request", e3.CALLS.size() == 1);

        reset();
        KApplication.d.getSharedPreferences("kate_send_tokens", 0).edit().putString("token_100", "OWN").commit();
        check("send token of another account is not used", KateSendFix.send(main, req(), "200").v.equals("ok:KATE"));

        reset();
        KApplication.d.getSharedPreferences("kate_send_tokens", 0).edit().putString("token_100", "OWN").commit();
        e3.ERR.put("OWN", 9);
        e = sendExpectingError(main, "100");
        check("real flood control on own token: error 9 propagated, no fallback", e != null && e.error_code == 9 && e3.CALLS.size() == 1);
        check("real flood control on own token: hint names own app", e.getMessage().contains("вашего приложения"));

        for (int code : new int[]{4, 5, 17, 25}) {
            reset();
            KApplication.d.getSharedPreferences("kate_send_tokens", 0).edit().putString("token_100", "OWN").commit();
            e3.ERR.put("OWN", code);
            String v = KateSendFix.send(main, req(), "100").v;
            check("own token error " + code + ": falls back to main token", v.equals("ok:KATE") && e3.CALLS.size() == 2);
            check("own token error " + code + ": send token removed", KateSendFix.token("100") == null);
        }

        reset();
        KApplication.d.getSharedPreferences("kate_send_tokens", 0).edit().putString("token_100", "OWN").commit();
        e3.ERR.put("OWN", 14);
        e = sendExpectingError(main, "100");
        check("captcha on own token: original exception with captcha_sid", e != null && e.error_code == 14 && "SID".equals(e.captcha_sid) && "IMG".equals(e.captcha_img));
        check("captcha: send token kept", "OWN".equals(KateSendFix.token("100")));

        reset();
        KApplication.d.getSharedPreferences("kate_send_tokens", 0).edit().putString("token_100", "OWN").commit();
        e3.ERR.put("OWN", 15);
        e = sendExpectingError(main, "100");
        check("own app without messages access: code 15 + hint", e != null && e.error_code == 15 && e.getMessage().contains("messages"));

        reset();
        e3.ERR.put("KATE", 900);
        e = sendExpectingError(main, "100");
        check("other errors untouched (900)", e != null && e.error_code == 900 && e.getMessage().equals("err900"));

        // Login flow
        reset();
        Activity act = new Activity();
        act.intent = new Intent();
        WebView wv = new WebView();
        check("normal login is not intercepted", !KateSendFix.startAuth(act, wv) && !KateSendFix.onUrl(act, "https://oauth.vk.ru/blank.html#access_token=X&user_id=1"));

        act.intent.extras.put("send_token", "1");
        check("send-token mode without API ID: handled and finished", KateSendFix.startAuth(act, wv) && act.finished && wv.loaded == null);

        reset();
        act = new Activity();
        act.intent = new Intent();
        act.intent.extras.put("send_token", "1");
        act.getSharedPreferences("default", 0).edit().putString("send_api_id", " 51234567 ").commit();
        check("send-token mode: authorize URL with user's client_id", KateSendFix.startAuth(act, wv)
                && wv.loaded.startsWith("https://oauth.vk.ru/authorize?client_id=51234567&")
                && wv.loaded.contains("scope=messages,offline") && wv.loaded.contains("response_type=token"));
        check("intermediate pages ignored", KateSendFix.onUrl(act, "https://id.vk.ru/auth?x=1") && !act.finished);
        check("redirect consumed", KateSendFix.onUrl(act, "https://oauth.vk.ru/blank.html#access_token=vk1.a.TOKEN&expires_in=0&user_id=100"));
        check("token saved per user id", "vk1.a.TOKEN".equals(KateSendFix.token("100")) && act.finished && act.result == -1);
        check("main account prefs untouched", Context.FILES.get("default").get("send_api_id") != null && !Context.FILES.get("default").containsKey("token_100"));

        act = new Activity();
        act.intent = new Intent();
        act.intent.extras.put("send_token", "1");
        KateSendFix.onUrl(act, "https://oauth.vk.ru/blank.html#error=access_denied&error_description=User%20denied");
        check("auth error reported, nothing saved", Toast.last.contains("User denied") && act.finished && act.result == 0);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
