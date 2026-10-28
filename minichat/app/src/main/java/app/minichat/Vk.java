package app.minichat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The few VK methods the client needs, and parsing of their responses. */
public final class Vk {
    private static final Random RANDOM = new Random();

    public static final class Conversation {
        public long peerId;
        public String title;
        public String lastText;
        public int unread;

        @Override
        public String toString() {
            return (unread > 0 ? "● " : "") + title + "\n" + lastText;
        }
    }

    public static final class Msg {
        public long id;
        public long fromId;
        public boolean out;
        public String author;
        public String text;
        public boolean failed;

        @Override
        public String toString() {
            return (out ? "Вы" : author) + ": " + text + (failed ? "  ⚠ не отправлено" : "");
        }
    }

    private final Api api;

    public Vk(Api api) {
        this.api = api;
    }

    public List<Conversation> conversations() throws IOException, Api.ApiException, JSONException {
        JSONObject r = api.call("messages.getConversations",
                Api.params("count", "50", "extended", "1")).getJSONObject("response");
        Map<Long, String> names = names(r);
        List<Conversation> list = new ArrayList<>();
        JSONArray items = r.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject conv = item.getJSONObject("conversation");
            Conversation c = new Conversation();
            c.peerId = conv.getJSONObject("peer").getLong("id");
            c.unread = conv.optInt("unread_count");
            JSONObject chat = conv.optJSONObject("chat_settings");
            c.title = chat != null ? chat.optString("title") : nameOf(names, c.peerId);
            c.lastText = text(item.optJSONObject("last_message"));
            list.add(c);
        }
        return list;
    }

    public List<Msg> history(long peerId) throws IOException, Api.ApiException, JSONException {
        JSONObject r = api.call("messages.getHistory",
                Api.params("peer_id", Long.toString(peerId), "count", "50", "extended", "1"))
                .getJSONObject("response");
        Map<Long, String> names = names(r);
        List<Msg> list = new ArrayList<>();
        JSONArray items = r.optJSONArray("items");
        // VK returns newest first; show oldest at the top.
        for (int i = items == null ? -1 : items.length() - 1; i >= 0; i--) {
            list.add(msg(items.getJSONObject(i), names));
        }
        return list;
    }

    public long send(long peerId, String text, String captchaSid, String captchaKey) throws IOException, Api.ApiException, JSONException {
        JSONObject r = api.call("messages.send", Api.params(
                "peer_id", Long.toString(peerId),
                "message", text,
                "random_id", Integer.toString(RANDOM.nextInt(Integer.MAX_VALUE)),
                "captcha_sid", captchaSid,
                "captcha_key", captchaKey));
        return r.optLong("response");
    }

    public void markAsRead(long peerId) throws IOException, Api.ApiException, JSONException {
        api.call("messages.markAsRead", Api.params("peer_id", Long.toString(peerId)));
    }

    public JSONObject longPollServer() throws IOException, Api.ApiException, JSONException {
        return api.call("messages.getLongPollServer", Api.params("lp_version", "3")).getJSONObject("response");
    }

    static Msg msg(JSONObject m, Map<Long, String> names) {
        Msg msg = new Msg();
        msg.id = m.optLong("id");
        msg.fromId = m.optLong("from_id");
        msg.out = m.optInt("out") == 1;
        msg.author = nameOf(names, msg.fromId);
        msg.text = text(m);
        return msg;
    }

    static String text(JSONObject m) {
        if (m == null) {
            return "";
        }
        String text = m.optString("text");
        JSONArray att = m.optJSONArray("attachments");
        if (att != null && att.length() > 0) {
            String type = att.optJSONObject(0).optString("type");
            String label = label(type);
            text = text.isEmpty() ? label : text + " " + label;
        }
        JSONArray fwd = m.optJSONArray("fwd_messages");
        if (fwd != null && fwd.length() > 0) {
            text = text.isEmpty() ? "[пересланные]" : text + " [пересланные]";
        }
        return text;
    }

    private static String label(String type) {
        switch (type) {
            case "photo": return "[фото]";
            case "video": return "[видео]";
            case "audio": return "[аудио]";
            case "audio_message": return "[голосовое]";
            case "doc": return "[файл]";
            case "sticker": return "[стикер]";
            case "wall": return "[запись]";
            case "link": return "[ссылка]";
            default: return "[" + type + "]";
        }
    }

    static Map<Long, String> names(JSONObject r) {
        Map<Long, String> names = new HashMap<>();
        JSONArray profiles = r.optJSONArray("profiles");
        for (int i = 0; profiles != null && i < profiles.length(); i++) {
            JSONObject p = profiles.optJSONObject(i);
            names.put(p.optLong("id"), (p.optString("first_name") + " " + p.optString("last_name")).trim());
        }
        JSONArray groups = r.optJSONArray("groups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            names.put(-g.optLong("id"), g.optString("name"));
        }
        return names;
    }

    private static String nameOf(Map<Long, String> names, long id) {
        String name = names.get(id);
        return name != null ? name : "id" + id;
    }
}
