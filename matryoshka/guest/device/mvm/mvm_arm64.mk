# Matryoshka VM guest product: compact AOSP 11 phone for running inside another phone.
#
# Built on the AOSP "full base" product set, then trimmed (see removepkgs/) — the goal is a
# small, fast system that still runs normal Play-Store-era apps.

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/aosp_base.mk)
$(call inherit-product, device/matryoshka/mvm/device.mk)

PRODUCT_NAME := mvm_arm64
PRODUCT_DEVICE := mvm
PRODUCT_BRAND := Matryoshka
PRODUCT_MODEL := Matryoshka Virtual Phone
PRODUCT_MANUFACTURER := Matryoshka

# userdebug: `su` for the shell user (used by the host app during Magisk patching only).
PRODUCT_PROPERTY_OVERRIDES += \
    ro.adb.secure=1 \
    persist.sys.usb.config=adb \
    persist.adb.tcp.port=5555 \
    ro.config.low_ram=false \
    dalvik.vm.heapgrowthlimit=256m \
    dalvik.vm.heapsize=512m \
    ro.opengles.version=196609 \
    ro.setupwizard.mode=DISABLED \
    ro.com.android.dataroaming=false \
    ro.mvm.guest=1
