# Size optimisation: a module that *overrides* (and therefore prevents installing) consumer apps
# and services that a virtual phone does not need. Core frameworks, Settings, the launcher,
# the package installer, DocumentsUI, WebView and the keyboard are kept so normal apps work.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := MvmRemovePackages
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_OVERRIDES_PACKAGES := \
    BasicDreams \
    BookmarkProvider \
    Browser2 \
    Calendar \
    CalendarProvider \
    Camera2 \
    CaptivePortalLogin \
    CarrierConfig \
    CellBroadcastApp \
    CellBroadcastServiceModule \
    DeskClock \
    Email \
    EmergencyInfo \
    Gallery2 \
    LiveWallpapersPicker \
    ManagedProvisioning \
    MmsService \
    Music \
    MusicFX \
    NfcNci \
    OneTimeInitializer \
    PhotoTable \
    PrintRecommendationService \
    PrintSpooler \
    QuickSearchBox \
    SimAppDialog \
    Stk \
    Tag \
    TeleService \
    Telecom \
    TelephonyProvider \
    Traceur \
    WallpaperCropper \
    messaging \
    Dialer \
    Contacts
LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_SRC_FILES := /dev/null
include $(BUILD_PREBUILT)
