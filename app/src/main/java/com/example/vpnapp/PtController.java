package com.example.vpnapp;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import IPtProxy.Controller;
import IPtProxy.IPtProxy;
import IPtProxy.OnTransportEvents;

/**
 * Pluggable transports (IPtProxy): локальные SOCKS-прокси obfs4, snowflake, webtunnel, meek, dnstt.
 * Tor подключается к ним через ClientTransportPlugin. Snowflake — свой клиент с маскировкой DTLS
 * (SnowflakeClient), если он есть в APK.
 */
final class PtController {

    private static final String TAG = "PtController";
    private static final String STATE_DIR = "pt_state";
    private static final int LOG_TAIL_BYTES = 6 * 1024;
    private static final int SNOWFLAKE_PEERS = 3;

    // IPtProxy не потокобезопасен: все вызовы — под одной блокировкой.
    // Транспорт общий для всех (Moat и попытки), поэтому считаем пользователей
    // и останавливаем его только когда он не нужен никому.
    private static final Object LOCK = new Object();
    private static final Map<String, Integer> USERS = new HashMap<>();
    private static final Set<String> STARTED = new HashSet<>();
    private static Controller controller;
    private static File stateDir;

    private final Context context;
    private final List<String> running = new ArrayList<>();

    PtController(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Запускает транспорты набора мостов.
     *
     * @return строки torrc: UseBridges, ClientTransportPlugin, Bridge
     * @throws Exception если транспорт не запустился
     */
    synchronized List<String> start(BridgeSet set) throws Exception {
        stop();
        List<String> torrc = new ArrayList<>();
        if (set.isDirect()) {
            torrc.add("UseBridges 0");
            return torrc;
        }
        String snowflake = set.firstLine(IPtProxy.Snowflake);
        // Свой клиент Snowflake с маскировкой DTLS, если он есть в APK; иначе — IPtProxy
        String snowflakeExec = snowflake != null
                ? SnowflakeClient.torrcLine(context, SNOWFLAKE_PEERS) : null;
        if (snowflake != null && snowflakeExec == null) {
            synchronized (LOCK) {
                configureSnowflake(controller(context), snowflake);
            }
        }

        Map<String, Long> ports = new LinkedHashMap<>();
        for (String transport : set.transports()) {
            if (snowflakeExec != null && IPtProxy.Snowflake.equals(transport)) continue;
            long port = acquire(transport);
            if (port <= 0 || port > 65535) throw new IllegalStateException("No port for " + transport);
            ports.put(transport, port);
        }
        torrc.add("UseBridges 1");
        if (snowflakeExec != null) torrc.add(snowflakeExec);
        for (Map.Entry<String, Long> e : ports.entrySet()) {
            torrc.add("ClientTransportPlugin " + e.getKey() + " socks5 127.0.0.1:" + e.getValue());
        }
        for (String line : set.lines) torrc.add("Bridge " + line);
        return torrc;
    }

    /** Запуск одного транспорта (meek/dnstt для Moat); возвращает локальный порт SOCKS. */
    synchronized long startSingle(String transport) throws Exception {
        return acquire(transport);
    }

    /** Освобождает транспорты этого владельца. */
    synchronized void stop() {
        for (String t : running) release(t);
        running.clear();
    }

    /** Запускает транспорт, если он ещё не запущен, и возвращает его порт. */
    private long acquire(String transport) throws Exception {
        synchronized (LOCK) {
            Controller c = controller(context);
            // Каждый транспорт запускается один раз и живёт до конца процесса:
            // остановка внутри IPtProxy приводила к аварийному завершению
            if (!STARTED.contains(transport)) {
                c.start(transport, null);
                STARTED.add(transport);
            }
            Integer users = USERS.get(transport);
            USERS.put(transport, users == null ? 1 : users + 1);
            running.add(transport);
            return c.port(transport);
        }
    }

    private static void release(String transport) {
        synchronized (LOCK) {
            Integer users = USERS.get(transport);
            if (users == null) return;
            if (users > 1) {
                USERS.put(transport, users - 1);
                return;
            }
            // Транспорт не останавливаем: без подключений Tor он просто ждёт
            USERS.remove(transport);
        }
    }

    /** Хвост журнала IPtProxy (ошибки obfs4/snowflake/meek) для экрана «Журнал». */
    static String logTail() {
        File dir = stateDir;
        if (dir == null) return "";
        File log = new File(dir, IPtProxy.LogFileName);
        if (!log.exists()) return "";
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long start = Math.max(0, f.length() - LOG_TAIL_BYTES);
            byte[] buf = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Вызывать под LOCK. */
    private static Controller controller(Context context) {
        if (controller == null) {
            File dir = new File(context.getFilesDir(), STATE_DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            stateDir = dir;
            // Журнал включён, адреса в нём скрыты (unsafeLogging = false)
            controller = new Controller(dir.getAbsolutePath(), true, false, "INFO", EVENTS);
        }
        return controller;
    }

    /** Snowflake берёт адрес брокера, домены фронтинга и STUN-серверы из строки моста. */
    private static void configureSnowflake(Controller c, String line) {
        String fronts = BridgeSet.arg(line, "fronts");
        if (fronts == null) fronts = BridgeSet.arg(line, "front");
        c.setSnowflakeBrokerUrl(orEmpty(BridgeSet.arg(line, "url")));
        c.setSnowflakeFrontDomains(orEmpty(fronts));
        c.setSnowflakeIceServers(orEmpty(BridgeSet.arg(line, "ice")));
        // AMP-кэш не задаём по умолчанию: он есть только в своих строках моста
        c.setSnowflakeAmpCacheUrl("");
        c.setSnowflakeSqsUrl("");
        c.setSnowflakeSqsCreds("");
        // Несколько добровольцев сразу: если один отключится, трафик перейдёт на другого
        // (действует с первого запуска snowflake, поэтому задаётся до него)
        c.setSnowflakeMaxPeers(SNOWFLAKE_PEERS);
    }

    private static String orEmpty(@Nullable String s) {
        return s != null ? s : "";
    }

    private static final OnTransportEvents EVENTS = new OnTransportEvents() {
        @Override
        public void connected(String name) {
            DiagLog.add("PT " + name + ": готов");
        }

        @Override
        public void error(String name, Exception error) {
            DiagLog.add("PT " + name + " ошибка: " + error);
        }

        @Override
        public void stopped(String name, Exception error) {
            if (error != null) DiagLog.add("PT " + name + " остановлен: " + error);
        }
    };
}
