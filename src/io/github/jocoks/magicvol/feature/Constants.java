package io.github.jocoks.magicvol.feature;

/** 全局常量：日志标签、被钩的进程、档位数。 */
public final class Constants {

    public static final String TAG = "MagicVol";

    /** AudioService 所在进程的包名（system_server） */
    public static final String PKG_ANDROID = "android";

    /** 设置用的 SharedPreferences 名（模块本体写、钩子侧经 LSPosed 远程读取） */
    public static final String PREFS = "magicvol";

    /** 第 k 档（k = 1..9）百分比键名前缀 */
    public static final String KEY_PREFIX = "pct_";

    /** 调试日志开关（默认 false；只有排查问题时才打开） */
    public static final String KEY_DEBUG = "debug_log";

    /** 方案列表（JSON 字符串）与「当前方案名」的键 */
    public static final String KEY_SCHEMES = "schemes";
    public static final String KEY_ACTIVE_SCHEME = "scheme_active";

    /** 是否已经写过内置「推荐」方案（用户自己删掉后不再自动加回来） */
    public static final String KEY_PRESET_SEEDED = "preset_seeded";

    /** 是否已经还原过旧版自动选中的「推荐」（只做一次） */
    public static final String KEY_PRESET_FIXED = "preset_fixed";

    /** 可自定义的档位数（0 档固定静音，不存） */
    public static final int CUSTOM_STEPS = 9;

    /** 只处理媒体音量 */
    public static final int STREAM_MUSIC = 3;

    /** 总档位数（0..9）。0 档静音，1..9 档可自定义 */
    public static final int STEPS = 10;

    /**
     * 系统默认媒体音量。AudioService 里 DEFAULT_STREAM_VOLUME[3] = MAX_STREAM_VOLUME[3] / 3
     * （160/3 ≈ 53，约占最大音量的 53%），换算到 10 档就是第 5 档。
     */
    public static final int DEFAULT_STEP = 5;

    /**
     * 每档在 AudioService 内部 index 刻度上的跨度。
     * 实测：媒体流每个音量键步长 = 内部 100，而 index = (内部 + 5) / 10，所以 index 每档 = 10。
     */
    public static final int INDEX_PER_STEP = 10;

    public static String key(int step) {
        return KEY_PREFIX + step;
    }

    private Constants() {
    }
}
