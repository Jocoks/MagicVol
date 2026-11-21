package io.github.jocoks.magicvol.feature;

/**
 * 日志出口。
 *
 * 钩子跑在 system_server 里，日志必须走 LSPosed 模块日志（用户就是在管理器里看的），
 * 所以入口类安装一个 {@link Sink}，feature 层都只通过这里打日志。
 */
public final class Logs {

    /** 调试日志开关：默认关闭（界面上最底下那个开关，存在 SharedPreferences 里） */
    private static volatile boolean sDebug;

    public interface Sink {
        void log(int priority, String tag, String msg, Throwable tr);
    }

    private static volatile Sink sSink;

    private Logs() {
    }

    public static void install(Sink sink) {
        sSink = sink;
    }

    public static void setDebug(boolean enabled) {
        sDebug = enabled;
    }

    public static boolean enabled() {
        return sDebug;
    }

    public static void i(String msg) {
        if (sDebug) {
            emit(android.util.Log.INFO, msg, null);
        }
    }

    /** 只在调试开关打开时输出 */
    public static void d(String msg) {
        if (sDebug) {
            emit(android.util.Log.INFO, msg, null);
        }
    }

    public static void w(String msg) {
        emit(android.util.Log.WARN, msg, null);
    }

    public static void e(String msg, Throwable t) {
        emit(android.util.Log.ERROR, msg + " :: " + t, t);
    }

    private static void emit(int priority, String msg, Throwable t) {
        Sink sink = sSink;
        if (sink != null) {
            try {
                sink.log(priority, Constants.TAG, msg, t);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (t != null) {
            android.util.Log.println(priority, Constants.TAG, msg + " :: " + t);
        } else {
            android.util.Log.println(priority, Constants.TAG, msg);
        }
    }
}
