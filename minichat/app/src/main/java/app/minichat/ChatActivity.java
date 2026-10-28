package app.minichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** One conversation: history, live updates via LongPoll, sending. */
public class ChatActivity extends Activity implements LongPoll.Listener {
    private Store store;
    private Vk vk;
    private long peerId;

    private TextView status;
    private ListView list;
    private EditText input;
    private Button sendButton;
    private ArrayAdapter<Vk.Msg> adapter;
    private final List<Vk.Msg> data = new ArrayList<>();
    private LongPoll longPoll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        vk = new Vk(store.api());
        peerId = getIntent().getLongExtra("peer_id", 0);
        setTitle(getIntent().getStringExtra("title"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        int pad = dp(8);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status, mw());

        list = new ListView(this);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setHint("Сообщение");
        bar.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        sendButton = new Button(this);
        sendButton.setText("➤");
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(null, null);
            }
        });
        bar.addView(sendButton, wc());
        root.addView(bar, mw());
        setContentView(root);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, data);
        list.setAdapter(adapter);

        loadHistory();
    }

    private void loadHistory() {
        status.setText("Загрузка…");
        Bg.run(new Bg.Work<List<Vk.Msg>>() {
            @Override
            public List<Vk.Msg> run() throws Exception {
                return vk.history(peerId);
            }
        }, new Bg.Done<List<Vk.Msg>>() {
            @Override
            public void ok(List<Vk.Msg> result) {
                data.clear();
                data.addAll(result);
                adapter.notifyDataSetChanged();
                scrollDown();
                status.setText("");
                startLongPoll();
                markRead();
            }

            @Override
            public void fail(Exception e) {
                status.setText(Api.describe(e));
            }
        });
    }

    private void send(final String captchaSid, final String captchaKey) {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        sendButton.setEnabled(false);
        final Vk.Msg pending = new Vk.Msg();
        pending.out = true;
        pending.text = text;
        data.add(pending);
        adapter.notifyDataSetChanged();
        scrollDown();

        Bg.run(new Bg.Work<Long>() {
            @Override
            public Long run() throws Exception {
                return vk.send(peerId, text, captchaSid, captchaKey);
            }
        }, new Bg.Done<Long>() {
            @Override
            public void ok(Long id) {
                pending.id = id;
                sendButton.setEnabled(true);
                status.setText("");
            }

            @Override
            public void fail(Exception e) {
                sendButton.setEnabled(true);
                if (e instanceof Api.ApiException && ((Api.ApiException) e).code == 14) {
                    data.remove(pending);
                    adapter.notifyDataSetChanged();
                    input.setText(text);
                    showCaptcha((Api.ApiException) e);
                    return;
                }
                pending.failed = true;
                adapter.notifyDataSetChanged();
                status.setText(Api.describe(e));
            }
        });
    }

    private void showCaptcha(final Api.ApiException e) {
        status.setText("Требуется капча");
        final EditText answer = new EditText(this);
        answer.setHint("Код с картинки");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final ImageView image = new ImageView(this);
        box.addView(image, wc());
        box.addView(answer, mw());

        Bg.run(new Bg.Work<Bitmap>() {
            @Override
            public Bitmap run() throws Exception {
                byte[] bytes = Api.getBytes(e.captchaImg);
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        }, new Bg.Done<Bitmap>() {
            @Override
            public void ok(Bitmap bmp) {
                image.setImageBitmap(bmp);
            }

            @Override
            public void fail(Exception ex) {
                status.setText("Не удалось загрузить капчу");
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Введите капчу")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> send(e.captchaSid, answer.getText().toString().trim()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void markRead() {
        Bg.run(new Bg.Work<Void>() {
            @Override
            public Void run() throws Exception {
                vk.markAsRead(peerId);
                return null;
            }
        }, new Bg.Done<Void>() {
            @Override
            public void ok(Void v) {
            }

            @Override
            public void fail(Exception e) {
            }
        });
    }

    private void startLongPoll() {
        if (longPoll == null) {
            longPoll = new LongPoll(vk, this);
            longPoll.start();
        }
    }

    @Override
    public void onMessage(long fromPeer, long messageId, boolean out, String text) {
        if (fromPeer != peerId) {
            return;
        }
        for (Vk.Msg m : data) {
            if (m.id == messageId && messageId != 0) {
                return; // already shown (e.g. our own just-sent message)
            }
        }
        Vk.Msg m = new Vk.Msg();
        m.id = messageId;
        m.out = out;
        m.author = getIntent().getStringExtra("title");
        m.text = text;
        data.add(m);
        adapter.notifyDataSetChanged();
        scrollDown();
        if (!out) {
            markRead();
        }
    }

    private void scrollDown() {
        list.post(new Runnable() {
            @Override
            public void run() {
                list.setSelection(adapter.getCount() - 1);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (longPoll != null) {
            longPoll.stop();
        }
        super.onDestroy();
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wc() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
