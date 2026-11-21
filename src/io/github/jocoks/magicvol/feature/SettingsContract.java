package io.github.jocoks.magicvol.feature;

import android.net.Uri;

/**
 * 跨进程设置通道的约定。
 *
 * <p>模块本体是普通沙盒 App，system_server（uid 1000）读不到它的 SharedPreferences，
 * 所以设置必须走 ContentProvider：App 侧写 SharedPreferences，Provider 读出来给钩子。
 * 这是唯一一处对外的组件，只读，不写任何东西。
 */
public final class SettingsContract {

    public static final String AUTHORITY = "io.github.jocoks.magicvol.settings";

    public static final String PATH_TABLE = "table";

    public static final String PATH_DEBUG = "debug";

    public static final Uri TABLE_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_TABLE);

    /** 调试日志开关（单行，列名 {@link #COL_ON}） */
    public static final Uri DEBUG_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_DEBUG);

    /** 档位（1..9） */
    public static final String COL_STEP = "step";

    /** 该档位占系统最大音量的百分比（1..100） */
    public static final String COL_PCT = "pct";

    /** 调试开关值（0/1） */
    public static final String COL_ON = "on";

    private SettingsContract() {
    }
}
