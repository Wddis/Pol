# SysScan: разовая чистка Redmi Note 8 (MIUI 12.5). Запуск: ~/rish -c 'sh /sdcard/Download/1_cleanup.sh'
# Всё обратимо: 3_undo.sh
echo "== 1. Отключаю рекламу/аналитику/неиспользуемое MIUI =="
for p in com.miui.msa.global com.miui.analytics com.mi.android.globalminusscreen \
         com.miui.videoplayer com.xiaomi.discover com.miui.hybrid com.miui.hybrid.accessory \
         com.miui.yellowpage com.netflix.partner.activation com.google.android.projection.gearhead; do
  echo "$p: $(pm disable-user --user 0 $p 2>&1)"
done

echo "== 2. Убираю из белого списка энергосбережения (им запрещено висеть в фоне) =="
for p in com.miui.msa.global com.miui.analytics com.xiaomi.discover com.xiaomi.mipicks \
         com.miui.weather2 ua.itaysonlab.vkx com.marcussacana.DirectPackageInstaller; do
  echo "$p: $(cmd deviceidle whitelist -$p 2>&1)"
done

echo "== 3. Запрет фона (приложения работают, когда открыты) =="
for p in com.google.android.apps.maps com.google.android.youtube com.miui.weather2 \
         com.avito.android ru.ozon.app.android ru.beru.android ru.dns.shop.android \
         com.reddit.frontpage com.zhiliaoapp.musically com.linkedin.android tv.twitch.android.app \
         com.utorrent.client com.yandex.browser com.kiwibrowser.browser org.mozilla.firefox \
         com.lazycatsoftware.lmd com.guanyin.litv ru.ag.a24htv ru.rostel to.boosty.android \
         com.suno.android cn.wps.moffice_eng com.logistic.sdek com.idamob.tinkoff.android \
         com.dualspace.multispace.android com.google.android.apps.youtube.creator; do
  cmd appops set $p RUN_ANY_IN_BACKGROUND ignore 2>/dev/null
  am set-standby-bucket $p restricted 2>/dev/null
  echo "restricted: $p"
done

echo "== 4. Анимации интерфейса x0.5 =="
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5

echo "== 5. Закрываю фон =="
am kill-all
echo "ГОТОВО. Перезагрузи телефон, затем снова запусти Shizuku."
