package com.example.vpnapp;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
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

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Нижний лист «Приложения через VPN» (раздельное туннелирование): по умолчанию через VPN идут все;
 * снятая отметка — приложение работает через обычную сеть, мимо ODA ({@link Prefs#getExcludedApps},
 * VpnService.Builder.addDisallowedApplication). Сохраняется сразу; при подключённом VPN экран
 * перезапускает только VPN-интерфейс ({@link VpnTunnelService#ACTION_APPS}), способ не переподключается.
 */
public class AppsSheet extends BottomSheetDialogFragment {

    public static final String TAG = "apps";
    public static final String REQUEST_KEY = "apps_request";
    public static final String RESULT_CHANGED = "changed";

    private static final class App {
        final String pkg;
        final String label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> rows = new ArrayList<>();
    private Prefs prefs;
    private Set<String> excluded;
    private boolean changed;
    private LinearLayout list;
    private TextView loading;
    private String filter = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        prefs = new Prefs(requireContext());
        excluded = prefs.getExcludedApps();
        View root = inflater.inflate(R.layout.sheet_apps, container, false);
        list = root.findViewById(R.id.list);
        loading = root.findViewById(R.id.loading);
        EditText search = root.findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        root.findViewById(R.id.all).setOnClickListener(v -> {
            if (excluded.isEmpty()) return;
            excluded.clear();
            save();
            for (View row : rows) bind(row, (App) row.getTag());
        });

        final int basePadding = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });
        Motion.reveal((ViewGroup) root);
        loadApps(inflater);
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

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        // Результат — до super: потом фрагмент снимается с экрана
        if (changed && isAdded()) {
            Bundle result = new Bundle();
            result.putBoolean(RESULT_CHANGED, true);
            getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
        }
        super.onDismiss(dialog);
    }

    /** Приложения с иконкой на рабочем столе (их видно в <queries> манифеста), кроме самого ODA. */
    private void loadApps(LayoutInflater inflater) {
        PackageManager pm = requireContext().getPackageManager();
        String own = requireContext().getPackageName();
        new Thread(() -> {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<App> apps = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(own) || !seen.add(pkg)) continue;
                apps.add(new App(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)));
            }
            Collator collator = Collator.getInstance(Locale.forLanguageTag("ru"));
            apps.sort((a, b) -> collator.compare(a.label, b.label));
            main.post(() -> {
                if (!isAdded()) return;
                loading.setVisibility(View.GONE);
                for (App app : apps) {
                    View row = inflater.inflate(R.layout.item_app, list, false);
                    row.setTag(app);
                    ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(app.icon);
                    ((TextView) row.findViewById(R.id.name)).setText(app.label);
                    bind(row, app);
                    row.setOnClickListener(v -> toggle(row, app));
                    rows.add(row);
                    list.addView(row);
                }
                applyFilter();
                list.setAlpha(0f);
                list.animate().alpha(1f).setDuration(220).setInterpolator(Motion.EASE_OUT).start();
            });
        }, "oda-apps").start();
    }

    private void toggle(View row, App app) {
        if (!excluded.remove(app.pkg)) excluded.add(app.pkg);
        save();
        bind(row, app);
    }

    private void bind(View row, App app) {
        boolean viaVpn = !excluded.contains(app.pkg);
        ImageView check = row.findViewById(R.id.check);
        check.animate().alpha(viaVpn ? 1f : 0f).scaleX(viaVpn ? 1f : 0.6f).scaleY(viaVpn ? 1f : 0.6f)
                .setDuration(180).setInterpolator(Motion.EASE_OUT).start();
        row.findViewById(R.id.icon).animate().alpha(viaVpn ? 1f : 0.4f).setDuration(180).start();
        ((TextView) row.findViewById(R.id.route)).setText(viaVpn ? R.string.apps_via_vpn : R.string.apps_direct);
    }

    private void applyFilter() {
        for (View row : rows) {
            App app = (App) row.getTag();
            boolean show = filter.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(filter)
                    || app.pkg.contains(filter);
            row.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void save() {
        prefs.setExcludedApps(excluded);
        changed = true;
    }
}
