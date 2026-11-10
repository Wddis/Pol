# Pol

В репозитории два независимых проекта.

## 1. Telegram-бот (`bot.py`)

Telegram-бот-ассистент на русском языке. Отвечает через NVIDIA API и хранит историю диалога для каждого пользователя.

- Зависимости: `requirements.txt` (`python-telegram-bot`, `requests`).
- Запуск: `python bot.py`, на Heroku-подобных платформах через `Procfile` (`worker: python bot.py`).

> ⚠️ Сейчас токены `TELEGRAM_TOKEN` и `NVIDIA_API_KEY` записаны прямо в `bot.py` и попали в историю git.
> Их нужно отозвать и перевыпустить, а в коде читать из переменных окружения.

## 2. ATLANT SPACE (`atlant-space/`)

Android-проект на Gradle: платформа изолированных «пространств» для приложений. Подробное описание и ТЗ: [`atlant-space/HANDOFF.md`](atlant-space/HANDOFF.md).

### Состояние

Модуль `:engine` (логика виртуализации):
- модель пространств и менеджер (`space/`): создание, переименование, архив, клон, удаление, реконсиляция с диском;
- раскладка хранилища и проверка целостности (`storage/`);
- парсер бинарного `AndroidManifest.xml` с unit-тестами (`pm/ManifestParser.kt`);
- установщик APK / split / XAPK / APKS / APKM с атомарной заменой и откатом (`pm/PackageInstaller.kt`);
- каталог установленных приложений (`pm/AppRepository.kt`);
- виртуальный слой разрешений (`perm/`), журнал событий (`log/`), утилиты (`util/`);
- рантайм запуска гостя (`runtime/`): bootstrap гостевого процесса (`VClient`), hook-слой
  (instrumentation, `mH`-callback, AMS/PM-хуки), пул stub-компонентов, перенаправление хранилища;
- менеджер процессов и чтение памяти (`proc/`), фасад движка и IPC (`AtlantEngine`, `ipc/`);
- Virtual Root (`root/`), совместимость с GMS (`gms/`), бэкап/восстановление пространства
  (`backup/`), самодиагностика (`diag/`).

Модуль `:app` (приложение, Jetpack Compose, тёмная тема по палитре ТЗ):
- экраны: главный, пространства, менеджер приложений и карточка приложения, установка APK (с прогрессом),
  монитор процессов/памяти, файловый менеджер, менеджер разрешений, Root-менеджер, бэкапы,
  диагностика, журнал, настройки пространства;
- оригинальный векторный логотип ATLANT SPACE и адаптивная иконка;
- пул stub-компонентов (6 процессов `:vprocN`) объявлен в манифесте, R8/shrinkResources для release.

Проверено в этой сборке: `:engine` компилируется, unit-тесты парсера проходят на реальном APK;
`:app` собирается в debug и release, устанавливается и запускается на эмуляторе (API 33), движок
инициализируется, UI рендерится и навигируется. **Release APK ≈ 1.2 МБ** (лимит ТЗ — ≤ 100 МБ).

Требует проверки на реальном устройстве: сквозной запуск гостевого приложения (hook-слой полагается
на приватные поля `ActivityThread`, которые различаются между версиями/OEM); нативный IO-redirector
(`cpp/`) для приложений с хардкод-путями `/data/data/<pkg>` пока не реализован.

### Сборка

Нужны JDK 17+, Android SDK (platform 36, build-tools 36.0.0), NDK 27.3.13750724 и CMake 3.22.1.

```bash
cd atlant-space
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :engine:testDebugUnitTest -Datlant.testApk=/path/to/any.apk   # тесты парсера
./gradlew :app:assembleDebug                                            # debug APK приложения
./gradlew :app:assembleRelease                                          # release APK (R8), ≤100 МБ
```

При сетевых 429 от Maven Central помогает зеркало Google в `~/.gradle/init.d/` (см. HANDOFF).

Версии: Gradle 8.14.3 (wrapper в репозитории), AGP 8.11.1, Kotlin 2.2.0, compileSdk 36, minSdk 26.
