package app.minichat;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs work off the main thread and delivers the result back on it. */
public final class Bg {
    static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Work<T> {
        T run() throws Exception;
    }

    public interface Done<T> {
        void ok(T result);

        void fail(Exception e);
    }

    public static <T> void run(final Work<T> work, final Done<T> done) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T result = work.run();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            done.ok(result);
                        }
                    });
                } catch (final Exception e) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            done.fail(e);
                        }
                    });
                }
            }
        });
    }

    static void main(Runnable r) {
        MAIN.post(r);
    }
}
