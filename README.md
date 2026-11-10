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

Готово (модуль `:engine`):
- модель пространств и менеджер (`space/`): создание, переименование, архив, клон, удаление, реконсиляция с диском;
- раскладка хранилища и проверка целостности (`storage/`);
- парсер бинарного `AndroidManifest.xml` с unit-тестами (`pm/ManifestParser.kt`);
- установщик APK / split / XAPK / APKS / APKM с атомарной заменой и откатом (`pm/PackageInstaller.kt`);
- виртуальный слой разрешений (`perm/`), журнал событий (`log/`), утилиты (`util/`).

Не готово:
- рантайм запуска гостевых приложений (`runtime/`): начат, не закончен;
- модуль `:app` и UI (Compose, палитра из ТЗ), бэкапы, диагностика, release-сборка.

Известная проблема: `:engine` сейчас **не собирается**. В `runtime/GuestLoadedApk.kt:43` используется скрытое поле
`ApplicationInfo.credentialProtectedDataDir`, его нет в публичном SDK.

### Сборка

Нужны JDK 17+, Android SDK (platform 36, build-tools 36.0.0), NDK 27.3.13750724 и CMake 3.22.1.

```bash
cd atlant-space
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :engine:assembleDebug
./gradlew :engine:testDebugUnitTest -Datlant.testApk=/path/to/any.apk
```

Версии: Gradle 8.14.3 (wrapper в репозитории), AGP 8.11.1, Kotlin 2.2.0, compileSdk 36, minSdk 26.
