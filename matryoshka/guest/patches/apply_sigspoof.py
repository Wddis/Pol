#!/usr/bin/env python3
"""
Matryoshka guest patch: restricted signature spoofing for microG on AOSP android-11.

Instead of a line-number-based diff (fragile across r-tags), this script edits
frameworks/base by anchoring on stable code, and fails loudly if an anchor is missing.

Behaviour (deliberately narrower than generic spoofing patches):
  * New permission android.permission.FAKE_PACKAGE_SIGNATURE (dangerous, user-grantable).
  * A package may report a fake signature ONLY if
      - its package name is com.google.android.gms or com.android.vending, and
      - it holds the permission, and
      - it declares <meta-data android:name="fake-signature" android:value="<hex cert>"/>.
  So arbitrary apps can never impersonate other signers.

Usage:  python3 apply_sigspoof.py <aosp-root>
License: Apache-2.0 (Matryoshka project). Independent implementation of the approach used by microG.
"""
import pathlib
import sys

ALLOWED = ("com.google.android.gms", "com.android.vending")

PERMISSION_XML = """
    <!-- Matryoshka: lets microG (and only microG's package names) present Google's signature. -->
    <permission android:name="android.permission.FAKE_PACKAGE_SIGNATURE"
        android:protectionLevel="dangerous"
        android:label="@string/permlab_fakePackageSignature"
        android:description="@string/permdesc_fakePackageSignature" />
"""

STRINGS_XML = """
    <string name="permlab_fakePackageSignature">Spoof package signature</string>
    <string name="permdesc_fakePackageSignature">Allows the app to pretend to be a different app. Only honoured for microG.</string>
"""

HELPER_JAVA = """
    // --- Matryoshka: restricted signature spoofing for microG -------------------------------
    private static final java.util.Set<String> MVM_SPOOF_ALLOWED =
            new android.util.ArraySet<>(java.util.Arrays.asList(%s));

    private PackageInfo mvmMayFakeSignature(AndroidPackage p, PackageInfo pi, java.util.Set<String> granted) {
        try {
            if (p != null && pi != null && granted != null
                    && MVM_SPOOF_ALLOWED.contains(p.getPackageName())
                    && granted.contains("android.permission.FAKE_PACKAGE_SIGNATURE")
                    && p.getMetaData() != null) {
                String sig = p.getMetaData().getString("fake-signature");
                if (sig != null && !sig.isEmpty()) {
                    pi.signatures = new android.content.pm.Signature[] { new android.content.pm.Signature(sig) };
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "mvm: fake signature failed", t);
        }
        return pi;
    }
    // ------------------------------------------------------------------------------------------
""" % ", ".join('"%s"' % a for a in ALLOWED)


def patch(path: pathlib.Path, anchor: str, replacement: str, marker: str):
    text = path.read_text()
    if marker in text:
        print(f"already patched: {path}")
        return
    if anchor not in text:
        sys.exit(f"anchor not found in {path}:\n{anchor}\nThe AOSP revision differs; port the patch manually.")
    path.write_text(text.replace(anchor, replacement, 1))
    print(f"patched: {path}")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = pathlib.Path(sys.argv[1])
    fb = root / "frameworks/base"

    patch(fb / "core/res/AndroidManifest.xml",
          "    <application android:process=\"system\"",
          PERMISSION_XML + "\n    <application android:process=\"system\"",
          "FAKE_PACKAGE_SIGNATURE")

    patch(fb / "core/res/res/values/strings.xml",
          "</resources>",
          STRINGS_XML + "</resources>",
          "permlab_fakePackageSignature")

    pms = fb / "services/core/java/com/android/server/pm/PackageManagerService.java"
    # generatePackageInfo() ends like this on android-11.0.0_r*:
    anchor = ("            packageInfo.packageName = packageInfo.applicationInfo.packageName =\n"
              "                    resolveExternalPackageNameLPr(p);\n\n"
              "            return packageInfo;")
    replacement = ("            packageInfo.packageName = packageInfo.applicationInfo.packageName =\n"
                   "                    resolveExternalPackageNameLPr(p);\n\n"
                   "            return mvmMayFakeSignature(p, packageInfo, permissions);")
    patch(pms, anchor, replacement, "mvmMayFakeSignature(p, packageInfo")

    # Add the helper right before generatePackageInfo().
    patch(pms,
          "    private PackageInfo generatePackageInfo(PackageSetting ps, int flags, int userId) {",
          HELPER_JAVA + "\n    private PackageInfo generatePackageInfo(PackageSetting ps, int flags, int userId) {",
          "MVM_SPOOF_ALLOWED")


if __name__ == "__main__":
    main()
