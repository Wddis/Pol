# Архитектура

- **CPU:** ARMv8-A, AArch64 (ELF machine 183).
- **Android ABI:** `arm64-v8a`.
- **Разрядность:** 64 бит, little-endian.
- **Выравнивание страниц:** 0x4000 (16 КБ) — сборка совместима с 16 KB page-size устройствами (требование Google Play с 2025 г.).
- **Связывание:** `BIND_NOW`, полное RELRO (`GNU_RELRO` покрывает весь первый RW-сегмент).
- **Стек:** неисполняемый (`GNU_STACK RW`).
- **Графика:** Vulkan и OpenGL ES (импорт `egl*`, строки `vkCmd*`, `glBindAttribLocation`, `kGL_*`), Swappy (Android Frame Pacing — экспорт `UnitySwappy_*`).
- **Медиа:** NDK MediaCodec / MediaExtractor / AImageReader (видео-плеер Unity).
- **Сенсоры:** ASensorManager (гироскоп — подтверждается сборкой `Gameplay.Features.GyroscopeSupport`).
- **Android activity:** поддержка `UnityPlayerForGameActivity` (GameActivity) и классического `UnityPlayer`.

Других ABI (armeabi-v7a, x86_64) в файле нет — это одна ABI-сборка из APK/AAB.
