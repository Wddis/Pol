package app.minichat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Dialog list. Entry point. */
public class DialogsActivity extends Activity {
    private static final int REQ_LOGIN = 1;

    private Store store;
    private ListView list;
    private TextView status;
    private ArrayAdapter<Vk.Conversation> adapter;
    private final List<Vk.Conversation> data = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        int pad = dp(12);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, data);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                Vk.Conversation c = data.get(position);
                Intent intent = new Intent(DialogsActivity.this, ChatActivity.class);
                intent.putExtra("peer_id", c.peerId);
                intent.putExtra("title", c.title);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store.token() == null) {
            startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
        } else {
            load();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN && resultCode != RESULT_OK) {
            finish();
        }
    }

    private void load() {
        status.setText("Загрузка диалогов…");
        final Vk vk = new Vk(store.api());
        Bg.run(new Bg.Work<List<Vk.Conversation>>() {
            @Override
            public List<Vk.Conversation> run() throws Exception {
                return vk.conversations();
            }
        }, new Bg.Done<List<Vk.Conversation>>() {
            @Override
            public void ok(List<Vk.Conversation> result) {
                data.clear();
                data.addAll(result);
                adapter.notifyDataSetChanged();
                status.setText("Диалоги · вызовов API за месяц: " + store.callsThisMonth());
            }

            @Override
            public void fail(Exception e) {
                status.setText(Api.describe(e));
                if (e instanceof Api.ApiException && isAuthError(((Api.ApiException) e).code)) {
                    store.logout();
                    startActivityForResult(new Intent(DialogsActivity.this, LoginActivity.class), REQ_LOGIN);
                }
            }
        });
    }

    static boolean isAuthError(int code) {
        return code == 5;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Обновить");
        menu.add(0, 2, 1, "Выйти");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            load();
            return true;
        }
        if (item.getItemId() == 2) {
            store.logout();
            Toast.makeText(this, "Вы вышли", Toast.LENGTH_SHORT).show();
            startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
