package com.example.vpnapp;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Нижние листы меню (0.10): «Меню», «Информация о ключе» и справка — как купить, инструкция, частые вопросы,
 * конфиденциальность, о приложении. Поддержка и бот — ссылки в Telegram-бот.
 */
public class OdaSheet extends BottomSheetDialogFragment {

    static final String MENU = "menu";
    static final String KEY = "key";
    static final String BUY = "buy";
    static final String HOWTO = "howto";
    static final String FAQ = "faq";
    static final String PRIVACY = "privacy";
    static final String ABOUT = "about";

    private static final String TAG = "oda_sheet_";
    private static final String ARG_PAGE = "page";

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private LinearLayout buttons;
    private TextView body;
    private TextView title;

    static void show(FragmentManager fm, String page) {
        if (fm.findFragmentByTag(TAG + page) != null) return;
        OdaSheet s = new OdaSheet();
        Bundle b = new Bundle();
        b.putString(ARG_PAGE, page);
        s.setArguments(b);
        s.show(fm, TAG + page);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_menu, container, false);
        rows = root.findViewById(R.id.rows);
        buttons = root.findViewById(R.id.buttons);
        body = root.findViewById(R.id.body);
        title = root.findViewById(R.id.sheetTitle);
        build();
        View content = root.findViewById(R.id.content);
        final int basePadding = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });
        Motion.reveal((ViewGroup) content);
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) dialog).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private String page() {
        Bundle a = getArguments();
        return a != null ? a.getString(ARG_PAGE, MENU) : MENU;
    }

    private void build() {
        rows.removeAllViews();
        buttons.removeAllViews();
        body.setVisibility(View.GONE);
        switch (page()) {
            case KEY:
                buildKey();
                break;
            case BUY:
                buildInfo(R.string.menu_buy, getString(R.string.info_buy), "buy");
                break;
            case HOWTO:
                buildInfo(R.string.menu_howto, getString(R.string.info_howto), null);
                break;
            case FAQ:
                buildInfo(R.string.menu_faq, getString(R.string.info_faq), "support");
                break;
            case PRIVACY:
                buildInfo(R.string.menu_privacy, getString(R.string.info_privacy), null);
                break;
            case ABOUT:
                buildInfo(R.string.menu_about, getString(R.string.info_about, appVersion(requireContext()))
                        .replace("\n", "<br>"), "");
                break;
            default:
                buildMenu();
        }
    }

    // ---------- меню ----------

    private void buildMenu() {
        title.setText(R.string.menu_title);
        KeyManager keys = KeyManager.get(requireContext());
        String keySub = keys.key() == null ? null : keys.key() + " · " + keys.leftText();
        row(getString(R.string.menu_key), keySub, v -> open(KEY));
        row(getString(R.string.menu_buy), getString(R.string.menu_buy_sub), v -> open(BUY));
        row(getString(R.string.menu_howto), getString(R.string.menu_howto_sub), v -> open(HOWTO));
        row(getString(R.string.menu_faq), null, v -> open(FAQ));
        row(getString(R.string.menu_support), getString(R.string.menu_support_sub), v -> bot("support"));
        row(getString(R.string.menu_bot), getString(R.string.menu_bot_sub), v -> bot(""));
        row(getString(R.string.menu_privacy), null, v -> open(PRIVACY));
        row(getString(R.string.menu_about), "ODA " + appVersion(requireContext()), v -> open(ABOUT));
    }

    // ---------- ключ ----------

    private void buildKey() {
        title.setText(R.string.keyinfo_title);
        KeyManager keys = KeyManager.get(requireContext());
        String key = keys.key();
        if (key == null) return;
        boolean forever = keys.forever();
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        info(R.string.keyinfo_key, key);
        if (!keys.tariffTitle().isEmpty()) info(R.string.keyinfo_tariff, keys.tariffTitle());
        info(R.string.keyinfo_left, keys.secondsLeft() > 0 ? keys.leftText() : getString(R.string.key_title_expired));
        if (!forever && keys.expiresAt() > 0) info(R.string.keyinfo_until, fmt.format(new Date(keys.expiresAt() * 1000)));
        if (keys.devicesMax() > 0) {
            info(R.string.keyinfo_devices, getString(R.string.keyinfo_devices_value, keys.devicesUsed(), keys.devicesMax()));
        }
        info(R.string.keyinfo_device, KeyManager.deviceName());
        if (keys.lastOk() > 0) info(R.string.keyinfo_checked, fmt.format(new Date(keys.lastOk() * 1000)));

        button(R.string.keyinfo_renew, true, v -> bot("renew"));
        button(R.string.keyinfo_copy, false, v -> copy(key));
        button(R.string.keyinfo_refresh, false, v -> refresh((TextView) v));
        button(R.string.keyinfo_change, false, v -> confirmChange());
    }

    private void refresh(TextView button) {
        button.setEnabled(false);
        button.setText(R.string.key_checking);
        Context app = requireContext().getApplicationContext();
        int port = StatusBus.current().socksPort;
        new Thread(() -> {
            KeyManager.Result r = KeyManager.get(app).check(null, "", port);
            main.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(app, r.ok ? getString(R.string.key_checked) : r.message, Toast.LENGTH_LONG).show();
                FragmentActivity a = getActivity();
                if (a instanceof MainActivity) ((MainActivity) a).onKeyChanged();
                if (isAdded()) build();
            });
        }, "oda-key-refresh").start();
    }

    private void confirmChange() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.keyinfo_change_title)
                .setMessage(R.string.keyinfo_change_text)
                .setPositiveButton(R.string.keyinfo_change_ok, (d, w) -> changeKey())
                .setNegativeButton(R.string.keyinfo_cancel, null)
                .show();
    }

    private void changeKey() {
        Context ctx = requireContext();
        DiagLog.add("Ключ: смена ключа пользователем");
        KeyManager.get(ctx).forget();
        try {
            ctx.startService(new Intent(ctx, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_STOP));
        } catch (RuntimeException ignored) {
            // сервис не запущен
        }
        FragmentActivity a = getActivity();
        dismissAllowingStateLoss();
        if (a instanceof MainActivity) ((MainActivity) a).onKeyChanged();
    }

    private void copy(String key) {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ODA", key));
        Toast.makeText(requireContext(), R.string.keyinfo_copied, Toast.LENGTH_SHORT).show();
    }

    // ---------- справка ----------

    /** botStart != null — кнопка «Открыть бота» (раздел бота; "" — главное меню). */
    private void buildInfo(int titleRes, String html, @Nullable String botStart) {
        title.setText(titleRes);
        body.setVisibility(View.VISIBLE);
        body.setText(Html.fromHtml(html.trim(), Html.FROM_HTML_MODE_COMPACT));
        if (botStart != null) button(R.string.info_open_bot, true, v -> bot(botStart));
    }

    // ---------- элементы ----------

    private void row(String text, @Nullable String sub, View.OnClickListener click) {
        View v = getLayoutInflater().inflate(R.layout.item_menu, rows, false);
        ((TextView) v.findViewById(R.id.title)).setText(text);
        TextView s = v.findViewById(R.id.subtitle);
        if (sub != null && !sub.isEmpty()) {
            s.setText(sub);
            s.setVisibility(View.VISIBLE);
        }
        v.setOnClickListener(click);
        rows.addView(v);
    }

    /** Строка «значение» с подписью снизу (без нажатия). */
    private void info(int label, String value) {
        View v = getLayoutInflater().inflate(R.layout.item_menu, rows, false);
        ((TextView) v.findViewById(R.id.title)).setText(value);
        TextView s = v.findViewById(R.id.subtitle);
        s.setText(label);
        s.setVisibility(View.VISIBLE);
        v.setClickable(false);
        v.setFocusable(false);
        v.setBackground(null);
        rows.addView(v);
    }

    private void button(int text, boolean primary, View.OnClickListener click) {
        TextView b = (TextView) getLayoutInflater().inflate(
                primary ? R.layout.item_button_primary : R.layout.item_button_outline, buttons, false);
        b.setText(text);
        b.setOnClickListener(click);
        buttons.addView(b);
    }

    private void open(String page) {
        FragmentManager fm = getParentFragmentManager();
        dismiss();
        show(fm, page);
    }

    private void bot(String start) {
        openBot(requireContext(), start);
    }

    /** Telegram-бот ODA (приложение Telegram или браузер). */
    static void openBot(Context ctx, String start) {
        try {
            ctx.startActivity(KeyManager.botIntent(start));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "t.me/" + KeyManager.BOT, Toast.LENGTH_LONG).show();
        }
    }

    static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
