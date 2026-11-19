# libprobe.so — нативная демо-либа для Патч-лабы (без NDK)

Собирается системным clang+lld (без Android NDK):

    clang -target aarch64-linux-android24 -fPIC -shared -nostdlib -fuse-ld=lld \
          -Wl,-soname,libprobe.so -o libprobe.so jni/probe.c

Кладётся в APK как lib/arm64-v8a/libprobe.so (manifest: extractNativeLibs="true").

g_val: оффсет в либе vaddr 0x35b0 (file offset 0x5b0), значение 0x11223344.
Экспорты (JNI): probe(), valueAddr(), patch(addr,v) класса PatchLab.
Патч g_val выполняется в СВОЁМ процессе (данные в RW-сегменте) — root не нужен.
