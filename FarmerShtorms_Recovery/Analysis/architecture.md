# Архитектура
- **Android ABI: arm64-v8a** (ELF64, AArch64, little endian). Достоверность 100 %: это прямо указано в заголовке ELF.
- Страницы 16 КБ (LOAD align 0x4000).
- В файле нет кода 32-битных ARMv7, x86 или x86_64. Если у оригинальной игры были другие ABI, это были отдельные `.so`.
- Графика: Vulkan (`vkCreate*`, 29 строк) и OpenGL ES/EGL (`glDraw*`, `eglCreate*`).
- Платформенный слой: GameActivity / ANativeWindow (в Unity 6 по умолчанию используется GameActivity).
