# База знаний для эдитов (изучено один раз — дальше пользоваться отсюда)

Источник: zip «файлы для Эдита Claude code» —
https://www.mediafire.com/file/anz7p4ls2zoj50v/файлы+для+Эдита+Claude+code.zip/file
(видео в git не хранятся по правилу ROADMAP; контрольные суммы — `media_sha256.txt`).

Структура zip:
```
инструкции с Ютуба по edits/
  инструкция по edit в ae.mp4        (13:17 урок по AE)      -> tutorial_ae/
  готовая ЦК/Edit_CC/*.ffx           (5 пресетов ЦК)         -> cc_presets/ffx/  (сохранены в git)
эдиты людей в tik tok/*.mp4          (4 готовых эдита)       -> reference_edits/
```

| Папка | Что там |
|---|---|
| `tutorial_ae/INSTRUCTION.md` | **пошаговая инструкция из урока** (все шаги, хоткеи, значения ключей) + таблица перевода AE → EffectCraft |
| `tutorial_ae/TRANSCRIPT.md` | **полный текст урока по главам** с таймкодами (ошибки распознавания исправлены) |
| `tutorial_ae/subtitles_ru.srt` | **субтитры урока** (366 шт., таймкоды по оригинальному видео) |
| `tutorial_ae/STRUCTURE.md` | **структура инструкции**: слои проекта, порядок эффектов, ключи по кадрам, переходы, текст, разбор итогового эдита |
| `tutorial_ae/transcript.txt` | сырой текст урока (ElevenLabs Scribe, речь → текст) |
| `tutorial_ae/asr_fixes.json` | словарь исправлений распознавания |
| `tutorial_ae/words_1.2x.json` | пословные тайминги (аудио ×1.2 без пауз: время в оригинале ≈ t×1.2) |
| `tutorial_ae/frames/` | раскадровка урока (кадр / 10 с) + `final_edit_sheet.jpg` (итоговый эдит, 4 кадра/с) |
| `cc_presets/README.md` | **5 готовых ЦК**: что внутри (декодировано), как применить в EffectCraft, точность |
| `cc_presets/ffx/` | оригинальные `.ffx` пресеты автора урока |
| `cc_presets/looks_decoded.json` | декодированные цепочки Magic Bullet Looks (инструменты + числа) |
| `reference_edits/ANALYSIS.md` | **разбор 4 эдитов людей**: структура, темп, текст, цвет, аутро |

Код:
- `tiktok/tools/words_to_srt.py` — пословные тайминги (ElevenLabs) → .srt (для уроков и пословных титров в эдитах);
- `tiktok/effectcraft/cc/cc_steps.py` — готовые ЦК → команды EffectCraft (одна строка в генераторе эдита);
- `tiktok/effectcraft/cc/luts/*.cube` — те же ЦК как LUT для CapCut/Premiere/DaVinci;
- `tiktok/effectcraft/aeconv/mblooks.py` — декодер Magic Bullet Looks из `.ffx/.aep`.
