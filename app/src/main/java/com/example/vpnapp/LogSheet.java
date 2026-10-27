package com.example.vpnapp;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Нижний лист «Журнал»: что происходило при подключении, с копированием и очисткой. Пока лист открыт,
 * новые строки появляются сами (раз в секунду); если журнал прокручен до конца — он следует за ними.
 */
public class LogSheet extends BottomSheetDialogFragment {

    public static final String TAG = "log";

    private static final long REFRESH_MS = 1_000;
    // Насколько от низа ещё считается «прокручен до конца»
    private static final int BOTTOM_SLOP_DP = 48;

    // Хвосты журналов IPtProxy и Snowflake на момент очистки: их файлы ведут сами мосты, поэтому после
    // «ОЧИСТИТЬ» показываем только то, что появилось в них позже
    private static String ptAtClear = "";
    private static String sfAtClear = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresher = this::refreshLoop;
    private TextView textView;
    private NestedScrollView scroll;
    private String shown = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_log, container, false);
        textView = root.findViewById(R.id.text);
        scroll = root.findViewById(R.id.scroll);
        show(buildText(), false);
        root.findViewById(R.id.copy).setOnClickListener(v -> copy(shown));
        root.findViewById(R.id.clear).setOnClickListener(v -> clear());

        final int basePadding = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });
        Motion.reveal((ViewGroup) root);
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
        main.postDelayed(refresher, REFRESH_MS);
    }

    @Override
    public void onStop() {
        main.removeCallbacks(refresher);
        super.onStop();
    }

    private void refreshLoop() {
        if (!isAdded()) return;
        show(buildText(), true);
        main.postDelayed(refresher, REFRESH_MS);
    }

    /** Новый текст; если был прокручен до конца — остаться внизу (видны свежие строки). */
    private void show(String text, boolean follow) {
        if (text.equals(shown)) return;
        boolean atBottom = follow && isAtBottom();
        shown = text;
        textView.setText(text);
        if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean isAtBottom() {
        View content = scroll.getChildAt(0);
        if (content == null) return true;
        int slop = (int) (BOTTOM_SLOP_DP * getResources().getDisplayMetrics().density);
        return content.getBottom() - (scroll.getScrollY() + scroll.getHeight()) <= slop;
    }

    /**
     * Очистить журнал (память и файл diag.log) — подключение не трогается, новые строки пишутся
     * дальше как обычно. Первая строка — что сейчас с подключением, чтобы новый журнал был понятен.
     */
    private void clear() {
        DiagLog.clear();
        ptAtClear = PtController.logTail();
        sfAtClear = SnowflakeClient.logTail(requireContext());
        StatusBus.Status s = StatusBus.current();
        DiagLog.add("Журнал очищен · ODA " + appVersion() + " · " + getString(stateText(s.state))
                + (s.engine.isEmpty() ? "" : " через " + s.engine)
                + " · способ «" + getString(new Prefs(requireContext()).getMode().title) + "»");
        textView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            show(buildText(), false);
            scroll.scrollTo(0, 0);
            textView.animate().alpha(1f).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
        }).start();
        Toast.makeText(requireContext(), R.string.log_cleared, Toast.LENGTH_SHORT).show();
    }

    private static int stateText(ConnectionState state) {
        switch (state) {
            case ON:
                return R.string.status_on;
            case CONNECTING:
                return R.string.status_connecting;
            default:
                return R.string.status_off;
        }
    }

    private String appVersion() {
        try {
            Context c = requireContext();
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String buildText() {
        String log = DiagLog.dump();
        if (log.isEmpty()) return getString(R.string.log_empty);
        String pt = newSince(PtController.logTail(), ptAtClear);
        String sf = newSince(SnowflakeClient.logTail(requireContext()), sfAtClear);
        return log + (pt.isEmpty() ? "" : "\n--- IPtProxy ---\n" + pt)
                + (sf.isEmpty() ? "" : "\n--- Snowflake ---\n" + sf)
                + "\n--- " + Build.MANUFACTURER + " " + Build.MODEL + ", API " + Build.VERSION.SDK_INT;
    }

    /** Часть хвоста после последней строки снимка (снимок пуст — весь хвост). */
    private static String newSince(String tail, String snapshot) {
        String s = snapshot.trim();
        if (s.isEmpty()) return tail;
        String last = s.substring(s.lastIndexOf('\n') + 1);
        int i = tail.lastIndexOf(last);
        return i >= 0 ? tail.substring(i + last.length()).trim() : tail;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("ODA log", text));
        Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT).show();
    }
}
