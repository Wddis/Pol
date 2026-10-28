"""Модели устройств: телевизор Samsung без Smart TV, приёмник Триколора, Android-стик."""
from dataclasses import dataclass, field


@dataclass
class Device:
    name: str
    os: str
    # Среда, в которой могут работать приложения: None, "stingray-qml", "android"
    app_runtime: str | None
    app_store: bool
    sideload_apk: bool
    accepts_unofficial_firmware: bool
    # Есть ли сборка Android под процессор этого устройства
    android_port_exists: bool
    hdmi_inputs: int = 0
    hdmi_output: bool = False
    screen_mirroring: bool = False
    has_wifi: bool = False
    installed_apps: list[str] = field(default_factory=list)
    bricked: bool = False
    facts: tuple[str, ...] = ()


def samsung_tv_non_smart() -> Device:
    return Device(
        name="Samsung TV (без Smart TV)",
        os="встроенная прошивка Samsung (без платформы приложений)",
        app_runtime=None,
        app_store=False,
        sideload_apk=False,
        accepts_unofficial_firmware=False,
        android_port_exists=False,
        hdmi_inputs=2,
        facts=("samsung_nonsmart_no_apps", "samygo_limited", "no_android_port_samsung"),
    )


def tricolor_receiver(early_firmware: bool = False) -> Device:
    """early_firmware=True — ранние прошивки GS, где homebrew ещё не закрыли."""
    return Device(
        name="Приёмник Триколор GS (StingrayTV)",
        os="StingrayTV (Linux)",
        app_runtime="stingray-qml",
        app_store=False,
        sideload_apk=False,
        accepts_unofficial_firmware=False,
        android_port_exists=False,
        hdmi_output=True,
        has_wifi=True,
        installed_apps=["Кино Онлайн", "Кино Спутник", "Мультиэкран"],
        facts=("gs_os_stingray", "gs_homebrew_closed", "gs_changelog_no_youtube",
               "gs_updates_official_only", "gs_no_custom_fw", "gs_signature_unknown"),
    ) if not early_firmware else Device(
        name="Приёмник Триколор GS (ранняя прошивка)",
        os="StingrayTV (Linux), homebrew разрешён",
        app_runtime="stingray-qml",
        app_store=False,
        sideload_apk=False,
        accepts_unofficial_firmware=False,
        android_port_exists=False,
        hdmi_output=True,
        has_wifi=True,
        installed_apps=["Кино Онлайн"],
        facts=("gs_os_stingray", "gs_homebrew_closed", "gs_updates_official_only"),
    )


def android_stick() -> Device:
    return Device(
        name="Триколор СмартСтик (Android 11)",
        os="Android 11",
        app_runtime="android",
        # Google Play на стике в источниках не подтверждён — считаем, что YouTube ставится из APK
        app_store=False,
        sideload_apk=True,
        accepts_unofficial_firmware=False,
        android_port_exists=True,
        hdmi_output=True,
        has_wifi=True,
        facts=("smartstick",),
    )


def phone_or_laptop() -> Device:
    return Device(
        name="Телефон/ноутбук через HDMI",
        os="Android/Windows",
        app_runtime="android",
        app_store=True,
        sideload_apk=True,
        accepts_unofficial_firmware=False,
        android_port_exists=True,
        hdmi_output=True,
        has_wifi=True,
    )
