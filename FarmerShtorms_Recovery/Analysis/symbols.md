# Символы

`.symtab` отсутствует (stripped). `.dynsym` — 304 записи: **7 экспортов, 296 импортов**.

## Экспорты (полный список)
| Символ | Адрес | Размер |
|---|---|---|
| JNI_OnLoad | 0x5ac3bf0 | 212 |
| JNI_OnUnload | 0x5ac3f88 | 156 |
| UnitySendMessage | 0x5a1f684 | 92 |
| UnitySwappy_version | 0x5a997b4 | 12 |
| UnitySwappy_injectTracer | 0x5a997c0 | 68 |
| UnityInitialize | 0x5acb728 | 504 |
| UnityShutdown | 0x5acb920 | 48 |

Адреса — виртуальные. В текущем (повреждённом) файле код по ним лежит на файловом смещении `VA − 0x4000 + 0x841`.

## Импорты (296)
Полный список: `Extracted/imports.txt`. Группы:
- Bionic libc / pthread / семафоры / сигналы / setjmp;
- математика (`sin`, `atan2f`, `powf`, …);
- `dlopen/dlsym/dladdr/dl_iterate_phdr`;
- `ANativeWindow_*`, `ALooper_*`, `AChoreographer_*`, `ASensor*`, `AHardwareBuffer_*`, `AImageReader_*`;
- `AMediaCodec_*`, `AMediaExtractor_*`, `AMediaFormat_*`, `AMEDIAFORMAT_KEY_*`;
- `egl*` (19 функций);
- zlib `inflate*`;
- сеть: `getaddrinfo`, `gethostbyname`, `getnameinfo`.

## Чего нет
- Имён функций игры/движка — их можно получить только из `libunity.sym.so` (у Unity для своих билдов) или
  из `global-metadata.dat` (для C#-методов через Il2CppDumper).
- Отладочной информации DWARF.
