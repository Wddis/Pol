package com.example.vpnapp;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/** Нижний лист «Мосты»: режим подключения и свои строки мостов. */
public class BridgesSheet extends BottomSheetDialogFragment {

    public static final String TAG = "bridges";
    public static final String REQUEST_KEY = "bridges_request";
    public static final String RESULT_CHANGED = "changed";

    private static final String STATE_MODE = "mode";

    private final List<View> rows = new ArrayList<>();
    private BridgeMode selected;
    private View customBox;
    private EditText customInput;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Prefs prefs = new Prefs(requireContext());
        View root = inflater.inflate(R.layout.sheet_bridges, container, false);
        LinearLayout list = root.findViewById(R.id.list);
        customBox = root.findViewById(R.id.customBox);
        customInput = root.findViewById(R.id.customInput);

        selected = savedInstanceState != null
                ? BridgeMode.from(savedInstanceState.getString(STATE_MODE))
                : prefs.getMode();
        if (savedInstanceState == null) customInput.setText(prefs.getCustomBridges());

        for (BridgeMode mode : BridgeMode.values()) {
            View row = inflater.inflate(R.layout.item_mode, list, false);
            ((TextView) row.findViewById(R.id.title)).setText(mode.title);
            ((TextView) row.findViewById(R.id.hint)).setText(mode.hint);
            row.setTag(mode);
            row.setOnClickListener(v -> select(mode));
            rows.add(row);
            list.addView(row);
        }
        updateSelection();

        root.findViewById(R.id.save).setOnClickListener(v -> save(prefs));

        // Отступ под системную панель навигации
        final int basePadding = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_MODE, selected.name());
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
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void select(BridgeMode mode) {
        selected = mode;
        updateSelection();
        if (mode == BridgeMode.CUSTOM) customInput.requestFocus();
    }

    private void updateSelection() {
        for (View row : rows) {
            boolean on = row.getTag() == selected;
            row.findViewById(R.id.check).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            row.setSelected(on);
        }
        // Поле своих мостов видно в режимах «Свои» и «Авто» (Авто их тоже пробует)
        boolean showCustom = selected == BridgeMode.CUSTOM || selected == BridgeMode.AUTO;
        customBox.setVisibility(showCustom ? View.VISIBLE : View.GONE);
    }

    private void save(Prefs prefs) {
        String text = customInput.getText().toString().trim();
        boolean changed = prefs.getMode() != selected || !text.equals(prefs.getCustomBridges());
        prefs.setMode(selected);
        prefs.setCustomBridges(text);
        if (changed) {
            // Новые настройки: старый «рабочий» способ больше не приоритетен
            prefs.setLastGood(null);
        }
        Bundle result = new Bundle();
        result.putBoolean(RESULT_CHANGED, changed);
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
        dismiss();
    }
}
