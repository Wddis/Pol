package app.minichat;

import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User Long Poll (version 3): one getLongPollServer call, then long-held GETs to the
 * poll server, so new messages arrive without spending API calls.
 */
public final class LongPoll implements Runnable {
    /** Replaced only by tests. */
    static String scheme = "https://";

    public interface Listener {
        /** Called on the main thread. */
        void onMessage(long peerId, long messageId, boolean out, String text);
    }

    private final Vk vk;
    private final Listener listener;
    private volatile boolean stopped;
    private Thread thread;

    public LongPoll(Vk vk, Listener listener) {
        this.vk = vk;
        this.listener = listener;
    }

    public void start() {
        thread = new Thread(this, "longpoll");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        String server = null;
        String key = null;
        long ts = 0;
        int failures = 0;
        while (!stopped) {
            try {
                if (server == null) {
                    JSONObject s = vk.longPollServer();
                    server = s.getString("server");
                    key = s.getString("key");
                    ts = s.getLong("ts");
                }
                String url = scheme + server + "?act=a_check&key=" + URLEncoder.encode(key, "UTF-8")
                        + "&ts=" + ts + "&wait=25&mode=2&version=3";
                JSONObject r = new JSONObject(Api.get(url, 35000));
                failures = 0;
                if (r.has("failed")) {
                    int failed = r.getInt("failed");
                    if (failed == 1) {
                        ts = r.getLong("ts");
                    } else {
                        server = null; // key expired or user info lost: ask for a new server
                    }
                    continue;
                }
                ts = r.getLong("ts");
                dispatch(r.optJSONArray("updates"));
            } catch (Api.ApiException e) {
                // Token problems are reported by the screens' own calls; do not spin on them.
                return;
            } catch (Exception e) {
                if (stopped) {
                    return;
                }
                failures++;
                try {
                    Thread.sleep(Math.min(60000, 2000L * failures));
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void dispatch(JSONArray updates) {
        for (int i = 0; updates != null && i < updates.length(); i++) {
            JSONArray u = updates.optJSONArray(i);
            if (u == null || u.optInt(0) != 4) {
                continue;
            }
            final long messageId = u.optLong(1);
            final boolean out = (u.optInt(2) & 2) != 0;
            final long peerId = u.optLong(3);
            final String text = unescape(u.optString(5));
            Bg.main(new Runnable() {
                @Override
                public void run() {
                    if (!stopped) {
                        listener.onMessage(peerId, messageId, out, text);
                    }
                }
            });
        }
    }

    static String unescape(String s) {
        return s.replace("<br>", "\n").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }
}
