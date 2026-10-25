package com.example.vpnapp;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
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

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/** Нижний лист «Журнал»: что происходило при подключении, с копированием текста. */
public class LogSheet extends BottomSheetDialogFragment {

    public static final String TAG = "log";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_log, container, false);
        String text = buildText();
        ((TextView) root.findViewById(R.id.text)).setText(text);
        root.findViewById(R.id.copy).setOnClickListener(v -> copy(text));

        final int basePadding = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });
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

    private String buildText() {
        String log = DiagLog.dump();
        if (log.isEmpty()) return getString(R.string.log_empty);
        String pt = PtController.logTail();
        String sf = SnowflakeClient.logTail(requireContext());
        return log + (pt.isEmpty() ? "" : "\n--- IPtProxy ---\n" + pt)
                + (sf.isEmpty() ? "" : "\n--- Snowflake ---\n" + sf)
                + "\n--- " + Build.MANUFACTURER + " " + Build.MODEL + ", API " + Build.VERSION.SDK_INT;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("ODA log", text));
        Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT).show();
    }
}
