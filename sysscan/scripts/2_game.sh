# SysScan: перед каждой игрой. Запуск: ~/rish -c 'sh /sdcard/Download/2_game.sh'
for p in com.google.android.apps.maps com.google.android.youtube com.android.settings \
         com.google.android.tts com.miui.weather2 com.android.vending com.miui.videoplayer \
         com.android.camera com.miui.gallery \
         com.openai.chatgpt com.anthropic.claude com.discord com.yandex.browser; do
  am force-stop $p 2>/dev/null
done
am kill-all
echo "MemAvailable: $(grep MemAvailable /proc/meminfo)"
monkey -p com.axlebolt.standoff2 -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "Standoff 2 запущен."
