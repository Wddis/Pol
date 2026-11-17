Вспомогательные скрипты, которыми проводился анализ сдвига (запуск: `python3 -I script.py Original/Test.so ...`).
Только читают файл. `rela.py` — проверка адресов из R_AARCH64_RELATIVE, `adrp2.py` — ссылки ADRP+ADD из кода,
`flt.py` — float-ссылки ADRP+LDR. Карта выравнивания — `Extracted/alignment_shift_map.txt`.
