# JNI-методы регистрируются в com.example.vpnapp.Tun2Socks
APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := armeabi-v7a arm64-v8a
# Папка этого файла (jni) — до любых include. Флаги только здесь: cFlags из Gradle ndk-build получает
# как APP_CFLAGS+= в командной строке make, а такая переменная целиком заменяет эту строку — в 0.8.9
# пропали PKGNAME/CLSNAME, JNI_OnLoad искал чужой класс, и приложение падало при запуске туннеля
ODA_JNI := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
# oda-hev.h: tun2socks без задержки 200 мс на каждое соединение (MSG_MORE)
APP_CFLAGS := -O3 -DPKGNAME=com/example/vpnapp -DCLSNAME=Tun2Socks -include $(ODA_JNI)/oda-hev.h
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
