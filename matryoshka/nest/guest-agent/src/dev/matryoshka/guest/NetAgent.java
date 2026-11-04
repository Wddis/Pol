// SPDX-License-Identifier: Apache-2.0
package dev.matryoshka.guest;

import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;

/**
 * The guest's network (runs in the guest as an init service with the system uid).
 *
 * Under Nest the guest's sockets are the phone's own: connections always go out through whatever
 * network the phone uses. What the guest lacks is a network its ConnectivityService knows about
 * (there is no interface for it to configure), so apps would report "no connection" and the DNS
 * resolver would have no servers. This agent registers one always-up Ethernet-type network with
 * the phone's DNS servers (property nest.net.dns). The addresses are nominal: routing is the
 * phone's. When system_server restarts the agent exits and init starts it again.
 */
public final class NetAgent {
    private static final String TAG = "NestNet";
    private static final String IFACE = "eth0";

    // NetworkCapabilities / ConnectivityManager constants (Android 11)
    private static final int TRANSPORT_ETHERNET = 3;
    private static final int NET_CAPABILITY_NOT_METERED = 11;
    private static final int NET_CAPABILITY_INTERNET = 12;
    private static final int NET_CAPABILITY_NOT_ROAMING = 18;
    private static final int NET_CAPABILITY_NOT_CONGESTED = 20;
    private static final int NET_CAPABILITY_NOT_SUSPENDED = 21;
    private static final int TYPE_ETHERNET = 9;
    private static final int SCORE = 70;  // like EthernetNetworkFactory

    private static final class Agent extends NetworkAgent {
        Agent(Context c, Looper l, NetworkCapabilities nc, LinkProperties lp, NetworkAgentConfig cfg) {
            super(c, l, TAG, nc, lp, SCORE, cfg, null);
        }

        @Override
        public void onNetworkUnwanted() {
            // Never expected for the only network; start over so the guest stays online.
            Log.w(TAG, "network unwanted, restarting");
            System.exit(2);
        }
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            Log.e(TAG, "failed", t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Looper.prepareMainLooper();
        IBinder cs;
        while ((cs = ServiceManager.checkService("connectivity")) == null) Thread.sleep(500);
        cs.linkToDeath(new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                Log.w(TAG, "system server died");
                System.exit(3);
            }
        }, 0);
        Context context = systemContext();

        NetworkCapabilities nc = new NetworkCapabilities();  // NOT_RESTRICTED, TRUSTED, NOT_VPN
        nc.addTransportType(TRANSPORT_ETHERNET);
        nc.addCapability(NET_CAPABILITY_INTERNET);
        nc.addCapability(NET_CAPABILITY_NOT_METERED);
        nc.addCapability(NET_CAPABILITY_NOT_ROAMING);
        nc.addCapability(NET_CAPABILITY_NOT_CONGESTED);
        nc.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        nc.setLinkUpstreamBandwidthKbps(100_000);
        nc.setLinkDownstreamBandwidthKbps(100_000);

        String dns = currentDns();
        LinkProperties lp = linkProperties(dns);

        NetworkAgentConfig cfg = new NetworkAgentConfig.Builder().setLegacyType(TYPE_ETHERNET).setLegacyTypeName("ETHERNET").build();
        Agent agent = new Agent(context, Looper.myLooper(), nc, lp, cfg);
        final android.net.Network network = agent.register();
        agent.markConnected();
        Log.i(TAG, "network up, DNS " + dns);
        // The phone changes networks (Wi-Fi, mobile): the app rewrites the DNS file.
        final Agent a = agent;
        final String[] current = {dns};
        Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(3000);
                        String now = currentDns();
                        if (!now.equals(current[0])) {
                            current[0] = now;
                            a.sendLinkProperties(linkProperties(now));
                            Log.i(TAG, "DNS now " + now);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "dns watch", t);
                    }
                }
            }
        }, "nest-dns");
        watch.setDaemon(true);
        watch.start();
        // Self-test for the logs: a socket on this network and a name lookup through it (retried:
        // ConnectivityService connects the network shortly after registration).
        Thread test = new Thread(new Runnable() {
            @Override
            public void run() {
                String result = "not run";
                for (int attempt = 0; attempt < 8; attempt++) {
                    try {
                        Thread.sleep(attempt == 0 ? 5000 : 15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    String sock;
                    boolean ok = false;
                    try {
                        java.io.FileDescriptor fd = android.system.Os.socket(2 /* AF_INET */, 2 /* SOCK_DGRAM */, 17 /* UDP */);
                        try {
                            network.bindSocket(fd);
                            android.system.Os.connect(fd, InetAddress.getByName("8.8.8.8"), 53);
                            sock = "ok";
                        } finally {
                            android.system.Os.close(fd);
                        }
                    } catch (Throwable t) {
                        sock = String.valueOf(t);
                    }
                    String dns;
                    try {
                        dns = network.getAllByName("connectivitycheck.gstatic.com")[0].getHostAddress();
                        ok = "ok".equals(sock);
                    } catch (Throwable t) {
                        dns = String.valueOf(t);
                    }
                    result = "socket " + sock + ", dns " + dns + " (attempt " + (attempt + 1) + ")";
                    if (ok) break;
                }
                Log.i(TAG, "self-test: " + result);
            }
        }, "nest-selftest");
        test.setDaemon(true);
        test.start();
        Looper.loop();
    }

    /** The phone's DNS servers: the file the app keeps current (nest.net.dns_file), else the boot value. */
    private static String currentDns() {
        String file = SystemProperties.get("nest.net.dns_file", "");
        if (!file.isEmpty()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) return line.trim();
            } catch (java.io.IOException ignored) {
                // not written yet
            }
        }
        return SystemProperties.get("nest.net.dns", "");
    }

    private static LinkProperties linkProperties(String dns) throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(IFACE);
        lp.addLinkAddress(new LinkAddress(InetAddress.getByName("10.0.2.15"), 24));
        lp.addRoute(new RouteInfo(new IpPrefix(InetAddress.getByName("0.0.0.0"), 0), InetAddress.getByName("10.0.2.2"), IFACE));
        lp.setMtu(1500);
        int count = 0;
        for (String d : dns.split(",")) {
            d = d.trim();
            if (d.isEmpty() || !d.matches("[0-9a-fA-F:.]+")) continue;  // numeric addresses only
            lp.addDnsServer(InetAddress.getByName(d));
            count++;
        }
        if (count == 0) {
            lp.addDnsServer(InetAddress.getByName("1.1.1.1"));
            lp.addDnsServer(InetAddress.getByName("8.8.8.8"));
        }
        return lp;
    }

    /** The framework's system context, as tools started with app_process obtain it. */
    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        Method get = at.getDeclaredMethod("getSystemContext");
        get.setAccessible(true);
        return (Context) get.invoke(thread);
    }
}
