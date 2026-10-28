package app.minichat;

import android.app.AlertDialog;
import org.robolectric.shadows.ShadowAlertDialog;

/** Convenience access to the most recently shown AlertDialog. */
public final class ShadowAlert {
    public static AlertDialog latest() {
        return ShadowAlertDialog.getLatestAlertDialog();
    }
}
