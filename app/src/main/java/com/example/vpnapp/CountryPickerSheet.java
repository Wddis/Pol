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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
            if (!Country.isAny(country.code)) {
                readings.put(country.code, row.findViewById(R.id.region_latency));
            }
            list.addView(row);
        }

        View measure = root.findViewById(R.id.measure_region_latency);
        TextView progress = root.findViewById(R.id.region_latency_progress);
        measure.setOnClickListener(v -> measureLatency(measure, progress, readings, selected));

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

    private void measureLatency(View button, TextView progress, Map<String, TextView> readings,
                                String selected) {
        latency = new RegionLatency(requireContext());
        ArrayList<String> codes = new ArrayList<>(readings.keySet());
        if (codes.remove(selected)) codes.add(0, selected);
        for (Map.Entry<String, TextView> item : readings.entrySet()) {
            long cached = latency.cached(item.getKey());
            TextView reading = item.getValue();
            reading.setVisibility(View.VISIBLE);
            if (cached >= 0) showLatency(reading, cached, true);
            else showState(reading, R.string.region_latency_wait);
        }
        button.setEnabled(false);
        button.setContentDescription(getString(R.string.region_latency_running));
        progress.setVisibility(View.VISIBLE);
        progress.setText(getString(R.string.region_latency_progress, 0, codes.size()));
        Set<String> completed = new HashSet<>();
        latency.start(codes, new RegionLatency.Listener() {
            @Override
            public void update(String code, RegionLatency.State state, long milliseconds) {
                TextView reading = readings.get(code);
                switch (state) {
                    case RUNNING:
                        if (latency.cached(code) < 0) showState(reading, R.string.region_latency_active);
                        break;
                    case VERIFIED:
                        showLatency(reading, milliseconds, false);
                        break;
                    case TIMEOUT:
                        showState(reading, R.string.region_latency_unavailable);
                        break;
                    case OUTDATED:
                        showState(reading, R.string.region_latency_outdated);
                        break;
                }
                if (state != RegionLatency.State.RUNNING) {
                    completed.add(code);
                    progress.setText(getString(R.string.region_latency_progress, completed.size(), codes.size()));
                }
            }

            @Override
            public void finished() {
                button.setEnabled(true);
                button.setContentDescription(getString(R.string.region_latency_measure));
                latency = null;
            }
        });
    }

    private void showState(TextView reading, int label) {
        reading.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        reading.setText(label);
    }

    private void showLatency(TextView reading, long milliseconds, boolean cached) {
        int color = milliseconds >= 600 ? R.color.region_latency_bad
                : milliseconds >= 250 ? R.color.region_latency_medium : R.color.region_latency_good;
        reading.setTextColor(ContextCompat.getColor(requireContext(), color));
        reading.setText(getString(cached ? R.string.region_latency_cached : R.string.region_latency_value,
                milliseconds));
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
