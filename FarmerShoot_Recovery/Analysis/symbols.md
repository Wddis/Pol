# Symbols

## Статус
- `.symtab` (полная таблица символов) **отсутствует** — файл stripped + таблица секций разрушена. Имён внутренних функций НЕТ.
- `.dynsym` (динамические символы) **цела** — разобрана вручную (304 записи): 7 экспортируемых + 296 импортируемых.

## Экспортируемые символы (7) — определяют природу файла
| Адрес | Тип | Размер | Имя |
|---|---|---|---|
| 0x5ac3bf0 | FUNC | 212 | `JNI_OnLoad` |
| 0x5ac3f88 | FUNC | 156 | `JNI_OnUnload` |
| 0x5a1f684 | FUNC | 92 | `UnitySendMessage` |
| 0x5a997b4 | FUNC | 12 | `UnitySwappy_version` |
| 0x5a997c0 | FUNC | 68 | `UnitySwappy_injectTracer` |
| 0x5acb728 | FUNC | 504 | `UnityInitialize` |
| 0x5acb920 | FUNC | 48 | `UnityShutdown` |

**Это сигнатура движка `libunity.so`.** `UnityInitialize`/`UnityShutdown`/`UnitySendMessage` — точки входа движка; `UnitySwappy_*` — встроенный Android Frame Pacing. **Ни одного `il2cpp_*`, ни одного `mono_*` экспорта нет** → перед нами движок, а не игровой рантайм.

## Импортируемые символы (296) — категории
- **pthread/синхронизация:** `pthread_create`, `pthread_mutex_*`, `pthread_cond_*`, `pthread_setspecific`, `syscall`, `getpriority`.
- **Графика (EGL/Android):** `eglSwapBuffers`, `eglQuerySurface`, `eglGetConfigAttrib`, `ANativeWindow_getWidth/Height`, `ANativeWindow_setBuffersGeometry`.
- **Математика:** `sinf/cosf/powf/atan2f/fmodf/acosf/sincosf/expf/logf/ldexp` …
- **zlib:** `inflate`, `inflateInit2_`, `inflateEnd`.
- **libc:** `malloc/realloc/free`, `setjmp/longjmp`, `sprintf`, `qsort`, `gettimeofday`, `gmtime`, `exit`, `__errno`, `__stack_chk_fail`, `__cxa_atexit`.
- Прочее: `_ZTH15gDeferredAction` (C++ TLS init), `wmemchr`, `strerror`.

Полные списки: `Analysis/07_symbols.txt`.

## Вывод
Экспорты однозначно идентифицируют файл как **Unity engine `libunity.so`**. Имён игровых функций в таблицах символов нет (они были бы в `libil2cpp.so`).
