# JNI-методы регистрируются в com.example.vpnapp.Tun2Socks
APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := armeabi-v7a arm64-v8a
APP_CFLAGS := -O3 -DPKGNAME=com/example/vpnapp -DCLSNAME=Tun2Socks
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
