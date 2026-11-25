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
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Нижний лист со списком стран. Результат отдаётся через Fragment Result API. */
public class CountryPickerSheet extends BottomSheetDialogFragment {

    public static final String TAG = "country_picker";
    public static final String REQUEST_KEY = "country_request";
    public static final String RESULT_CODE = "code";

    private static final String ARG_SELECTED = "selected";
    private RegionLatency latency;

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
        Map<String, TextView> readings = new LinkedHashMap<>();

        for (Country country : Country.all(requireContext())) {
            View row = inflater.inflate(R.layout.item_country, list, false);
            ((TextView) row.findViewById(R.id.code)).setText(country.shortLabel());
            ((TextView) row.findViewById(R.id.name)).setText(Country.selectionName(requireContext(), country.code));
            boolean isSelected = country.code.equals(selected);
            row.findViewById(R.id.check).setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            row.setSelected(isSelected);
            row.setOnClickListener(v -> pick(country.code));
            readings.put(country.code, row.findViewById(R.id.region_latency));
            list.addView(row);
        }

        View measure = root.findViewById(R.id.measure_region_latency);
        measure.setOnClickListener(v -> measureLatency(measure, readings, selected));

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

    private void measureLatency(View button, Map<String, TextView> readings, String selected) {
        latency = new RegionLatency(requireContext());
        ArrayList<String> codes = new ArrayList<>(readings.keySet());
        if (codes.remove(selected)) codes.add(0, selected);
        for (Map.Entry<String, TextView> item : readings.entrySet()) {
            long cached = latency.cached(item.getKey());
            TextView reading = item.getValue();
            reading.setVisibility(View.VISIBLE);
            if (cached >= 0) showLatency(reading, cached, true);
            else reading.setText(R.string.region_latency_wait);
        }
        button.setEnabled(false);
        button.setContentDescription(getString(R.string.region_latency_running));
        latency.start(codes, new RegionLatency.Listener() {
            @Override
            public void result(String code, long milliseconds) {
                showLatency(readings.get(code), milliseconds, false);
            }

            @Override
            public void finished() {
                button.setEnabled(true);
                button.setContentDescription(getString(R.string.region_latency_measure));
                latency = null;
            }
        });
    }

    private void showLatency(TextView reading, long milliseconds, boolean cached) {
        int color = milliseconds < 0 || milliseconds >= 600 ? R.color.region_latency_bad
                : milliseconds >= 250 ? R.color.region_latency_medium : R.color.region_latency_good;
        reading.setTextColor(ContextCompat.getColor(requireContext(), color));
        if (milliseconds < 0) {
            reading.setText(R.string.region_latency_unavailable);
            reading.setContentDescription(getString(R.string.region_latency_failed_cd));
        } else {
            reading.setText(getString(cached ? R.string.region_latency_cached : R.string.region_latency_value,
                    milliseconds));
            reading.setContentDescription(null);
        }
    }

    @Override
    public void onDestroyView() {
        if (latency != null) {
            latency.cancel();
            latency = null;
        }
        super.onDestroyView();
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
