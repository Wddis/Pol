package com.example.vpnapp;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
    private TextView parseResult;
    private View byedpiBox;
    private EditText byedpiInput;
    private Prefs prefs;
    private boolean saved;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        prefs = new Prefs(requireContext());
        View root = inflater.inflate(R.layout.sheet_bridges, container, false);
        LinearLayout list = root.findViewById(R.id.list);
        customBox = root.findViewById(R.id.customBox);
        customInput = root.findViewById(R.id.customInput);
        parseResult = root.findViewById(R.id.parseResult);
        byedpiBox = root.findViewById(R.id.byedpiBox);
        byedpiInput = root.findViewById(R.id.byedpiInput);

        selected = savedInstanceState != null
                ? BridgeMode.from(savedInstanceState.getString(STATE_MODE))
                : prefs.getMode();
        if (savedInstanceState == null) {
            customInput.setText(prefs.getCustomBridges());
            byedpiInput.setText(prefs.getByedpiArgs());
        }
        showParseResult();
        customInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showParseResult();
            }
        });

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

        root.findViewById(R.id.save).setOnClickListener(v -> save());

        // Отступ под системную панель навигации
        final int basePadding = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });
        // Поля «свои мосты» и «свои аргументы ByeDPI» раскрываются плавно, лист выезжает с содержимым
        Motion.animateLayout((ViewGroup) customBox.getParent());
        Motion.reveal((ViewGroup) root);
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

    /** Подсказка под полем: сколько мостов распознано и каких. */
    private void showParseResult() {
        String text = customInput.getText().toString();
        if (text.trim().isEmpty()) {
            parseResult.setText("");
            return;
        }
        BridgeParser.Result r = BridgeParser.parse(text);
        String msg = r.valid.isEmpty()
                ? getString(R.string.bridges_parsed_none)
                : getString(R.string.bridges_parsed, r.valid.size(), TextUtils.join(", ", r.transports));
        if (r.invalid > 0) msg += getString(R.string.bridges_invalid, r.invalid);
        parseResult.setText(msg);
    }

    /**
     * Другой способ применяется сразу и лист закрывается (галочка успевает переехать) — без лишнего «Сохранить».
     * «Свои мосты» ждут ввода; поля «Авто» и «Обход DPI» правятся, когда способ уже выбран, — тогда «Сохранить».
     */
    private void select(BridgeMode mode) {
        boolean same = mode == selected;
        selected = mode;
        updateSelection();
        if (mode == BridgeMode.CUSTOM) {
            customInput.requestFocus();
            return;
        }
        View root = getView();
        if (!same && root != null) root.postDelayed(() -> {
            if (isAdded()) save();
        }, 220);
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
        // Поле своих аргументов ByeDPI — в режимах «Обход DPI» и «Авто»
        boolean showByedpi = selected == BridgeMode.BYPASS || selected == BridgeMode.AUTO;
        byedpiBox.setVisibility(showByedpi ? View.VISIBLE : View.GONE);
    }

    private void save() {
        if (saved) return;
        saved = true;
        String text = customInput.getText().toString().trim();
        String byedpi = byedpiInput.getText().toString().trim();
        boolean changed = prefs.getMode() != selected || !text.equals(prefs.getCustomBridges())
                || !byedpi.equals(prefs.getByedpiArgs());
        prefs.setMode(selected);
        prefs.setCustomBridges(text);
        prefs.setByedpiArgs(byedpi);
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
