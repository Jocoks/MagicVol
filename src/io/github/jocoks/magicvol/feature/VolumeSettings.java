package io.github.jocoks.magicvol.feature;

import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;

/**
 * 功能层：钩子侧读取 9 个自定义档位的百分比。
 *
 * <p>钩子跑在 system_server（uid 1000），读不到模块本体的 SharedPreferences，
 * 所以统一经 {@link SettingsContract#TABLE_URI} 由 {@code SettingsProvider} 提供，
 * 1 秒 TTL 缓存（改完滑杆最多 1 秒后生效，音量键连按也不会每次都跨进程）。
 */
public final class VolumeSettings {

    private static final long TTL_MS = 1000L;

    private static volatile Context sContext;
    private static volatile int[] sCached;
    private static volatile long sStamp;
    private static volatile String sLogged;
    private static volatile boolean sOnceWarned;

    private VolumeSettings() {
    }

    /** 钩子在 createStreamStates 里从 AudioService 的 mContext 拿到 system Context */
    public static void attach(Context ctx) {
        if (ctx != null) {
            sContext = ctx;
        }
    }

    public static int[] table() {
        Context ctx = sContext;
        if (ctx == null) {
            warnOnce("no system context yet, using defaults");
            return StepTable.defaults();
        }
        long now = SystemClock.uptimeMillis();
        int[] cached = sCached;
        if (cached != null && now - sStamp < TTL_MS) {
            return cached;
        }
        int[] fresh = query(ctx);
        sCached = fresh;
        sStamp = now;
        return fresh;
    }

    private static int[] query(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver()
                    .query(SettingsContract.TABLE_URI, null, null, null, null);
            if (c == null) {
                warnOnce("settings provider returned null, using defaults");
                return StepTable.defaults();
            }
            int[] raw = new int[Constants.CUSTOM_STEPS];
            boolean[] seen = new boolean[Constants.CUSTOM_STEPS];
            int iStep = c.getColumnIndex(SettingsContract.COL_STEP);
            int iPct = c.getColumnIndex(SettingsContract.COL_PCT);
            while (c.moveToNext()) {
                int step = c.getInt(iStep);
                int pct = c.getInt(iPct);
                if (step >= 1 && step <= raw.length) {
                    raw[step - 1] = pct;
                    seen[step - 1] = true;
                }
            }
            for (int i = 0; i < raw.length; i++) {
                if (!seen[i]) {
                    raw[i] = StepTable.defaultPercent(i + 1);
                }
            }
            int[] table = StepTable.sanitize(raw);
            refreshDebugFlag(ctx);
            logOnce(table);
            return table;
        } catch (Throwable t) {
            warnOnce("read MagicVol settings failed: " + t);
            return StepTable.defaults();
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // 关闭失败不关心
                }
            }
        }
    }

    /** 调试开关也由模块本体控制：开关打开后，下一次刷新（≤1 秒）开始出日志 */
    private static void refreshDebugFlag(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(SettingsContract.DEBUG_URI, null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return;
            }
            int col = c.getColumnIndex(SettingsContract.COL_ON);
            Logs.setDebug(col >= 0 && c.getInt(col) != 0);
        } catch (Throwable ignored) {
            // 读不到就保持当前开关状态
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // 关闭失败不关心
                }
            }
        }
    }

    /** 只在整张表变化时打一行，便于用户在模块日志里确认读到的就是滑杆上的值 */
    private static void logOnce(int[] table) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < table.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(table[i]);
        }
        String text = sb.toString();
        if (!text.equals(sLogged)) {
            sLogged = text;
            Logs.i("settings loaded: " + text);
        }
    }

    private static void warnOnce(String msg) {
        if (!sOnceWarned) {
            sOnceWarned = true;
            Logs.w(msg);
        }
    }
}
