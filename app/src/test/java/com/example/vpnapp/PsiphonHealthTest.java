package com.example.vpnapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static com.example.vpnapp.PsiphonHealth.Change.*;

final class PsiphonHealthTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            if (!NetProbe.psiphonHealth(Integer.parseInt(args[0]))) {
                throw new AssertionError("No successful HTTPS response through Psiphon");
            }
            return;
        }
        PsiphonHealth health = new PsiphonHealth();
        expect(NONE, health.probe(health.epoch(), true));
        expect(NONE, health.transport(true));
        int first = health.epoch();
        expect(NONE, health.probe(first, false));
        expect(READY, health.probe(first, true));
        expect(NONE, health.probe(first, false));
        expect(NONE, health.probe(first, true));
        expect(NONE, health.probe(first, false));
        expect(LOST, health.probe(first, false));
        expect(RESTORED, health.probe(first, true));
        expect(LOST, health.transport(false));
        expect(NONE, health.transport(true));
        expect(NONE, health.probe(first, true));
        expect(RESTORED, health.probe(health.epoch(), true));
        int last = health.epoch();
        health.reset();
        expect(NONE, health.transport(true));
        expect(NONE, health.probe(last, true));
        expect(READY, health.probe(health.epoch(), true));

        rejectsUnusableProxy();
        System.out.println("Psiphon health and SOCKS routing: OK");
    }

    private static void rejectsUnusableProxy() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(5_000);
            FutureTask<List<String>> requests = new FutureTask<>(
                    () -> List.of(rejectConnect(proxy), rejectConnect(proxy)));
            Thread peer = new Thread(requests, "test-socks");
            peer.setDaemon(true);
            peer.start();
            if (NetProbe.psiphonHealth(proxy.getLocalPort())) {
                throw new AssertionError("SOCKS without HTTPS must not count as ready");
            }
            if (!requests.get(5, TimeUnit.SECONDS).equals(List.of("www.google.com", "www.cloudflare.com"))) {
                throw new AssertionError("Both probe domains must be sent to SOCKS for remote DNS");
            }
        }
    }

    private static String rejectConnect(ServerSocket proxy) throws IOException {
        try (Socket client = proxy.accept()) {
            client.setSoTimeout(3_000);
            var in = client.getInputStream();
            var out = client.getOutputStream();
            if (!java.util.Arrays.equals(in.readNBytes(3), new byte[]{5, 1, 0})) {
                throw new AssertionError("Expected SOCKS5 greeting");
            }
            out.write(new byte[]{5, 0});
            if (!java.util.Arrays.equals(in.readNBytes(4), new byte[]{5, 1, 0, 3})) {
                throw new AssertionError("Expected a SOCKS5 domain request");
            }
            int length = in.read();
            if (length <= 0) throw new AssertionError("Expected a hostname");
            String host = new String(in.readNBytes(length), StandardCharsets.US_ASCII);
            if (!java.util.Arrays.equals(in.readNBytes(2), new byte[]{1, (byte) 187})) {
                throw new AssertionError("Expected HTTPS port 443");
            }
            out.write(new byte[]{5, 4, 0, 1, 0, 0, 0, 0, 0, 0});
            return host;
        }
    }

    private static void expect(PsiphonHealth.Change expected, PsiphonHealth.Change actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
