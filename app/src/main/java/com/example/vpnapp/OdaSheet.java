package com.example.vpnapp;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Меню (☰ на главной): ключ, как купить, инструкция, частые вопросы, поддержка и бот в Telegram,
 * конфиденциальность, о приложении. Страницы открываются в {@link PageActivity}.
 */
public class OdaSheet extends BottomSheetDialogFragment {

    static final String MENU = "menu";
    static final String KEY = "key";
    static final String BUY = "buy";
    static final String HOWTO = "howto";
    static final String FAQ = "faq";
    static final String PRIVACY = "privacy";
    static final String ABOUT = "about";

    private static final String TAG = "oda_menu";

    private LinearLayout rows;

    /** Меню — листом снизу, остальное — страницей. */
    static void open(FragmentActivity activity, String page) {
        if (!MENU.equals(page)) {
            activity.startActivity(PageActivity.intent(activity, page));
            return;
        }
        FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.findFragmentByTag(TAG) == null) new OdaSheet().show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_menu, container, false);
        rows = root.findViewById(R.id.rows);
        ((TextView) root.findViewById(R.id.sheetTitle)).setText(R.string.menu_title);
        buildMenu();
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

    private void buildMenu() {
        KeyManager keys = KeyManager.get(requireContext());
        String keySub = keys.key() == null ? null : keys.key() + " · " + keys.leftText();
        row(R.drawable.ic_key, true, getString(R.string.menu_key), keySub, v -> page(KEY));
        row(R.drawable.ic_cart, false, getString(R.string.menu_buy), getString(R.string.menu_buy_sub), v -> page(BUY));
        row(R.drawable.ic_doc, false, getString(R.string.menu_howto), getString(R.string.menu_howto_sub), v -> page(HOWTO));
        row(R.drawable.ic_help, false, getString(R.string.menu_faq), null, v -> page(FAQ));
        row(R.drawable.ic_chat, false, getString(R.string.menu_support), getString(R.string.menu_support_sub), v -> bot("support"));
        row(R.drawable.ic_send, false, getString(R.string.menu_bot), getString(R.string.menu_bot_sub), v -> bot(""));
        row(R.drawable.ic_lock, false, getString(R.string.menu_privacy), null, v -> page(PRIVACY));
        row(R.drawable.ic_info, false, getString(R.string.menu_about), "ODA " + appVersion(requireContext()), v -> page(ABOUT));
    }

    private void row(@DrawableRes int icon, boolean accent, String text, @Nullable String sub, View.OnClickListener click) {
        View v = getLayoutInflater().inflate(R.layout.item_row, rows, false);
        ImageView iv = v.findViewById(R.id.icon);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), accent ? R.color.accent : R.color.text)));
        v.findViewById(R.id.iconBg).setBackgroundResource(accent ? R.drawable.bg_circle_accent : R.drawable.bg_circle);
        ((TextView) v.findViewById(R.id.title)).setText(text);
        TextView s = v.findViewById(R.id.subtitle);
        if (sub != null && !sub.isEmpty()) {
            s.setText(sub);
            s.setVisibility(View.VISIBLE);
        }
        v.setOnClickListener(click);
        rows.addView(v);
    }

    private void page(String page) {
        Activity a = getActivity();
        dismiss();
        if (a != null) a.startActivity(PageActivity.intent(a, page));
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
