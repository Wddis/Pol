# Matryoshka VM guest — Android 11 (AOSP android-11.0.0_r48) virtual device.
PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/mvm_arm64.mk

COMMON_LUNCH_CHOICES := \
    mvm_arm64-userdebug
