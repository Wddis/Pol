package com.example.vpnapp;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

/** Нижний лист со списком стран. Результат отдаётся через Fragment Result API. */
public class CountryPickerSheet extends BottomSheetDialogFragment {

    public static final String TAG = "country_picker";
    public static final String REQUEST_KEY = "country_request";
    public static final String RESULT_CODE = "code";

    private static final String ARG_SELECTED = "selected";

    public static CountryPickerSheet newInstance(String selectedCode) {
        CountryPickerSheet sheet = new CountryPickerSheet();
        Bundle args = new Bundle();
        args.putString(ARG_SELECTED, selectedCode);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_country, container, false);
        LinearLayout list = root.findViewById(R.id.list);
        String selected = requireArguments().getString(ARG_SELECTED);

        for (Country country : Country.all(requireContext())) {
            View row = inflater.inflate(R.layout.item_country, list, false);
            ((TextView) row.findViewById(R.id.code)).setText(country.shortLabel());
            ((TextView) row.findViewById(R.id.name)).setText(country.name);
            boolean isSelected = country.code.equals(selected);
            row.findViewById(R.id.check).setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            row.setSelected(isSelected);
            row.setOnClickListener(v -> pick(country.code));
            list.addView(row);
        }

        // Отступ под системную панель навигации
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
    }

    private void pick(String code) {
        Bundle result = new Bundle();
        result.putString(RESULT_CODE, code);
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
        dismiss();
    }
}
