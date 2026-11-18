# SysScan: откат всех изменений. Запуск: ~/rish -c 'sh /sdcard/Download/3_undo.sh'
for p in com.miui.msa.global com.miui.analytics com.mi.android.globalminusscreen \
         com.miui.videoplayer com.xiaomi.discover com.miui.hybrid com.miui.hybrid.accessory \
         com.miui.yellowpage com.netflix.partner.activation com.google.android.projection.gearhead; do
  pm enable --user 0 $p
done
for p in com.google.android.apps.maps com.google.android.youtube com.miui.weather2 \
         com.avito.android ru.ozon.app.android ru.beru.android ru.dns.shop.android \
         com.reddit.frontpage com.zhiliaoapp.musically com.linkedin.android tv.twitch.android.app \
         com.utorrent.client com.yandex.browser com.kiwibrowser.browser org.mozilla.firefox \
         com.lazycatsoftware.lmd com.guanyin.litv ru.ag.a24htv ru.rostel to.boosty.android \
         com.suno.android cn.wps.moffice_eng com.logistic.sdek com.idamob.tinkoff.android \
         com.dualspace.multispace.android com.google.android.apps.youtube.creator; do
  cmd appops set $p RUN_ANY_IN_BACKGROUND allow 2>/dev/null
  am set-standby-bucket $p active 2>/dev/null
done
settings put global window_animation_scale 1.0
settings put global transition_animation_scale 1.0
settings put global animator_duration_scale 1.0
echo "Откат выполнен."
