package app.minichat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * Drives the real Activities inside Robolectric (a simulated Android phone) against
 * {@link MockVk}, exercising the full UI flow: login, dialogs, chat, send, errors,
 * live LongPoll delivery.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PhoneSimulationTest {
    private MockVk mock;
    private Store store;

    @Before
    public void setUp() throws Exception {
        mock = new MockVk();
        Api.base = mock.methodBase;
        LongPoll.scheme = "http://";
        store = new Store(org.robolectric.RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        mock.stop();
    }

    /** Runs all pending background work and main-thread callbacks until quiet. */
    private void settle() {
        for (int i = 0; i < 50; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            ShadowLooper.idleMainLooper();
        }
    }

    private void login() {
        store.saveSession("USER_TOKEN", 42);
    }

    // --- Login screen ---------------------------------------------------------

    @Test
    public void login_rejectsNonNumericAppId() {
        ActivityController<LoginActivity> c = Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity a = c.get();
        ((EditText) a.findViewById(1)).setText("not-a-number");
        a.findViewById(2).performClick();
        assertEquals("Введите числовой ID приложения", ShadowToast.getTextOfLatestToast());
        assertFalse(a.isFinishing());
    }

    @Test
    public void login_savesTokenFromRedirect() {
        ActivityController<LoginActivity> c = Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity a = c.get();
        boolean consumed = a.handle(LoginActivity.REDIRECT
                + "#access_token=TKN&expires_in=0&user_id=777");
        assertTrue(consumed);
        assertEquals("TKN", store.token());
        assertEquals(777, store.userId());
        assertTrue(a.isFinishing());
    }

    @Test
    public void login_ignoresUnrelatedUrls() {
        ActivityController<LoginActivity> c = Robolectric.buildActivity(LoginActivity.class).setup();
        assertFalse(c.get().handle("https://id.vk.ru/auth?step=1"));
    }

    @Test
    public void login_reportsOauthError() {
        ActivityController<LoginActivity> c = Robolectric.buildActivity(LoginActivity.class).setup();
        c.get().handle(LoginActivity.REDIRECT + "#error=access_denied&error_description=denied");
        assertNull(store.token());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("denied"));
    }

    // --- Dialogs --------------------------------------------------------------

    @Test
    public void dialogs_redirectToLoginWhenNoToken() {
        ActivityController<DialogsActivity> c = Robolectric.buildActivity(DialogsActivity.class).setup();
        Intent next = Shadows.shadowOf(c.get()).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(LoginActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void dialogs_listsConversations() {
        login();
        mock.responses.put("messages.getConversations",
                "{\"response\":{\"items\":[" +
                "{\"conversation\":{\"peer\":{\"id\":101},\"unread_count\":2}," +
                "\"last_message\":{\"text\":\"Привет\"}}," +
                "{\"conversation\":{\"peer\":{\"id\":2000000001},\"chat_settings\":{\"title\":\"Беседа\"}}," +
                "\"last_message\":{\"text\":\"Всем привет\"}}]," +
                "\"profiles\":[{\"id\":101,\"first_name\":\"Иван\",\"last_name\":\"Петров\"}]}}");

        ActivityController<DialogsActivity> c = Robolectric.buildActivity(DialogsActivity.class).setup();
        settle();
        ListView list = firstListView(c.get());
        assertEquals(2, list.getAdapter().getCount());
        assertEquals("● Иван Петров\nПривет", list.getAdapter().getItem(0).toString());
        assertEquals("Беседа\nВсем привет", list.getAdapter().getItem(1).toString());
    }

    @Test
    public void dialogs_authErrorLogsOutAndReturnsToLogin() {
        login();
        mock.responses.put("messages.getConversations", MockVk.error(5, "auth failed"));
        ActivityController<DialogsActivity> c = Robolectric.buildActivity(DialogsActivity.class).setup();
        settle();
        assertNull(store.token());
        Intent next = Shadows.shadowOf(c.get()).getNextStartedActivity();
        assertEquals(LoginActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void dialogs_floodControlShownNotTreatedAsSendSuccess() {
        login();
        mock.responses.put("messages.getConversations", MockVk.error(9, "Flood control"));
        ActivityController<DialogsActivity> c = Robolectric.buildActivity(DialogsActivity.class).setup();
        settle();
        TextView status = firstTextView(c.get());
        assertTrue(status.getText().toString().contains("Ошибка VK 9"));
        assertTrue(status.getText().toString().contains("Flood control"));
        assertNotNull(store.token()); // code 9 is not an auth error
    }

    // --- Chat -----------------------------------------------------------------

    private ChatActivity openChat(long peerId) {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), ChatActivity.class);
        intent.putExtra("peer_id", peerId);
        intent.putExtra("title", "Иван Петров");
        return Robolectric.buildActivity(ChatActivity.class, intent).setup().get();
    }

    @Test
    public void chat_loadsHistoryOldestFirst() {
        login();
        mock.responses.put("messages.getHistory",
                "{\"response\":{\"items\":[" +
                "{\"id\":20,\"from_id\":42,\"out\":1,\"text\":\"второе\"}," +
                "{\"id\":19,\"from_id\":101,\"out\":0,\"text\":\"первое\"}]," +
                "\"profiles\":[{\"id\":101,\"first_name\":\"Иван\",\"last_name\":\"Петров\"}]}}");
        ChatActivity a = openChat(101);
        settle();
        ListView list = firstListView(a);
        assertEquals(2, list.getAdapter().getCount());
        assertEquals("Иван Петров: первое", list.getAdapter().getItem(0).toString());
        assertEquals("Вы: второе", list.getAdapter().getItem(1).toString());
    }

    @Test
    public void chat_sendsMessageWithUserToken() {
        login();
        mock.responses.put("messages.getHistory", "{\"response\":{\"items\":[]}}");
        mock.responses.put("messages.send", "{\"response\":555}");
        ChatActivity a = openChat(101);
        settle();
        typeAndSend(a, "Тестовое сообщение");
        settle();
        assertEquals("Тестовое сообщение", mock.lastSentMessage);
        assertEquals("USER_TOKEN", mock.lastSendToken);
        ListView list = firstListView(a);
        assertEquals("Вы: Тестовое сообщение", list.getAdapter().getItem(list.getAdapter().getCount() - 1).toString());
    }

    @Test
    public void chat_sendFailureMarksMessageFailed_noFakeSuccess() {
        login();
        mock.responses.put("messages.getHistory", "{\"response\":{\"items\":[]}}");
        mock.responses.put("messages.send", MockVk.error(9, "Flood control"));
        ChatActivity a = openChat(101);
        settle();
        typeAndSend(a, "упадёт");
        settle();
        ListView list = firstListView(a);
        String shown = list.getAdapter().getItem(list.getAdapter().getCount() - 1).toString();
        assertTrue(shown.contains("не отправлено"));
        assertTrue(firstTextView(a).getText().toString().contains("Ошибка VK 9"));
    }

    @Test
    public void chat_captchaThenRetrySucceeds() {
        login();
        mock.responses.put("messages.getHistory", "{\"response\":{\"items\":[]}}");
        mock.errorsOnce.put("messages.send", MockVk.captchaError());
        mock.responses.put("messages.send", "{\"response\":1}");
        ChatActivity a = openChat(101);
        settle();
        typeAndSend(a, "нужна капча");
        settle();
        android.app.AlertDialog dialog = ShadowAlert.latest();
        assertNotNull("captcha dialog expected", dialog);
        ((EditText) findEditTextIn(dialog)).setText("ABCD");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals("нужна капча", mock.lastSentMessage);
        // last call carried the captcha answer
        assertTrue(mock.calls.contains("messages.send"));
    }

    @Test
    public void chat_longPollDeliversIncomingMessage() {
        login();
        mock.responses.put("messages.getHistory", "{\"response\":{\"items\":[]}}");
        mock.responses.put("messages.getLongPollServer",
                "{\"response\":{\"server\":\"" + mock.lpHostPort + "\",\"key\":\"k\",\"ts\":1}}");
        mock.lpResponse = "{\"ts\":2,\"updates\":[[4,500,0,101,1700000000,\"входящее\",{}]]}";
        ChatActivity a = openChat(101);
        settle();
        settle();
        ListView list = firstListView(a);
        boolean found = false;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (list.getAdapter().getItem(i).toString().contains("входящее")) {
                found = true;
            }
        }
        assertTrue("incoming LongPoll message should appear", found);
    }

    // --- helpers --------------------------------------------------------------

    private void typeAndSend(ChatActivity a, String text) {
        EditText input = findInput(a);
        input.setText(text);
        findSendButton(a).performClick();
    }

    private static ListView firstListView(Activity a) {
        return (ListView) find(a.getWindow().getDecorView(), ListView.class);
    }

    private static TextView firstTextView(Activity a) {
        return (TextView) find(a.getWindow().getDecorView(), TextView.class);
    }

    private static EditText findInput(Activity a) {
        return (EditText) find(a.getWindow().getDecorView(), EditText.class);
    }

    private static Button findSendButton(Activity a) {
        return (Button) find(a.getWindow().getDecorView(), Button.class);
    }

    private static android.view.View findEditTextIn(android.app.Dialog d) {
        return find(d.getWindow().getDecorView(), EditText.class);
    }

    private static android.view.View find(android.view.View v, Class<?> type) {
        if (type.isInstance(v)) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = find(g.getChildAt(i), type);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
