package app.minichat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fake VK API + Long Poll server used by the phone-simulation tests.
 * Implemented with a plain ServerSocket so it needs only java.net on the Android
 * unit-test classpath.
 */
public final class MockVk {
    private final ServerSocket socket;
    private final Thread acceptor;
    private volatile boolean running = true;
    public final String methodBase;
    public final String lpHostPort;

    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, String> responses = new ConcurrentHashMap<>();
    /** method -> error JSON to return once, then cleared. */
    public final Map<String, String> errorsOnce = new ConcurrentHashMap<>();
    public volatile String lpResponse = "{\"ts\":1,\"updates\":[]}";
    public volatile String lastSentMessage;
    public volatile String lastSendToken;

    public MockVk() throws IOException {
        socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
        int port = socket.getLocalPort();
        methodBase = "http://127.0.0.1:" + port + "/method/";
        lpHostPort = "127.0.0.1:" + port + "/lp";
        acceptor = new Thread(this::acceptLoop, "mockvk");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = socket.accept();
                Thread t = new Thread(() -> serve(client));
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try {
            InputStream in = client.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String request = r.readLine();
            if (request == null) {
                client.close();
                return;
            }
            String path = request.split(" ")[1];
            int contentLength = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            char[] bodyBuf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = r.read(bodyBuf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            String body = new String(bodyBuf, 0, read);
            String response = route(path, body);
            byte[] bytes = response.getBytes("UTF-8");
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(bytes);
            out.flush();
            client.close();
        } catch (IOException ignored) {
        }
    }

    private String route(String path, String body) {
        if (path.startsWith("/lp")) {
            return lpResponse;
        }
        String method = path.substring("/method/".length());
        int q = method.indexOf('?');
        if (q >= 0) {
            method = method.substring(0, q);
        }
        calls.add(method);
        Map<String, String> p = parse(body);
        if (method.equals("messages.send")) {
            lastSentMessage = p.get("message");
            lastSendToken = p.get("access_token");
        }
        String err = errorsOnce.remove(method);
        if (err != null) {
            return err;
        }
        String resp = responses.get(method);
        return resp != null ? resp : "{\"response\":1}";
    }

    static Map<String, String> parse(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                try {
                    m.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                            java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return m;
    }

    public static String error(int code, String msg) {
        return "{\"error\":{\"error_code\":" + code + ",\"error_msg\":\"" + msg + "\"}}";
    }

    public static String captchaError() {
        return "{\"error\":{\"error_code\":14,\"error_msg\":\"Captcha needed\","
                + "\"captcha_sid\":\"sid123\",\"captcha_img\":\"http://127.0.0.1/img\"}}";
    }
}
