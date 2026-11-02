# Hardware abstraction for the paravirtual "mvm" board.

PRODUCT_SHIPPING_API_LEVEL := 30
PRODUCT_USE_DYNAMIC_PARTITIONS := false
PRODUCT_CHARACTERISTICS := default
DEVICE_PACKAGE_OVERLAYS += device/matryoshka/mvm/overlay

# Boot integration with the host app.
PRODUCT_COPY_FILES += \
    device/matryoshka/mvm/fstab.mvm:$(TARGET_COPY_OUT_RAMDISK)/fstab.mvm \
    device/matryoshka/mvm/fstab.mvm:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.mvm \
    device/matryoshka/mvm/init.mvm.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.mvm.rc \
    device/matryoshka/mvm/ueventd.mvm.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc \
    device/matryoshka/mvm/mvm-guest-setup.sh:$(TARGET_COPY_OUT_VENDOR)/bin/mvm-guest-setup.sh \
    frameworks/native/data/etc/android.hardware.ethernet.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.ethernet.xml \
    frameworks/native/data/etc/android.hardware.touchscreen.multitouch.jazzhand.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.jazzhand.xml \
    frameworks/native/data/etc/android.software.app_widgets.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.app_widgets.xml \
    frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml

# Graphics: virtio-gpu (crosvm/QEMU) through minigbm + drm_hwcomposer, Mesa virgl when the host
# renders, SwiftShader otherwise.
PRODUCT_PACKAGES += \
    android.hardware.graphics.allocator@4.0-service.minigbm \
    android.hardware.graphics.mapper@4.0-impl.minigbm \
    android.hardware.graphics.composer@2.4-service \
    hwcomposer.drm \
    libGLES_mesa \
    libEGL_swiftshader libGLESv1_CM_swiftshader libGLESv2_swiftshader \
    android.hardware.renderscript@1.0-impl

# Core HALs with software / default implementations.
PRODUCT_PACKAGES += \
    android.hardware.audio@6.0-impl \
    android.hardware.audio.effect@6.0-impl \
    android.hardware.audio.service \
    audio.primary.default \
    audio.r_submix.default \
    android.hardware.keymaster@4.1-service \
    android.hardware.gatekeeper@1.0-service.software \
    android.hardware.health@2.1-service \
    android.hardware.health@2.1-impl \
    android.hardware.power-service.example \
    android.hardware.light@2.0-service \
    android.hardware.memtrack@1.0-impl \
    android.hardware.memtrack@1.0-service \
    android.hardware.drm@1.3-service.clearkey \
    android.hardware.sensors@2.1-service.multihal \
    android.hardware.configstore@1.1-service

# Networking: Ethernet (static config written by mvm-guest-setup.sh), no Wi-Fi/telephony stacks.
PRODUCT_PACKAGES += \
    ethernet-service

# Strip consumer apps that are dead weight in a virtual phone.
PRODUCT_PACKAGES += MvmRemovePackages

# microG signature spoofing (frameworks/base patch from guest/patches) is limited to
# com.google.android.gms and com.android.vending.
PRODUCT_PRODUCT_PROPERTIES += ro.mvm.sigspoof=gms-only

PRODUCT_ENFORCE_VINTF_MANIFEST_OVERRIDE := true
