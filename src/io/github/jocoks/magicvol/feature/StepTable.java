package io.github.jocoks.magicvol.feature;

/**
 * 功能层：10 档音量表（纯逻辑，无 Android 依赖）。
 *
 * <p>约定：
 * <ul>
 *   <li>档位 0 = 静音；档位 1..9 各有一个「相对系统最大音量的百分比」；</li>
 *   <li>百分比越大越响，最高 100% 就是系统最大音量，不会超过；</li>
 *   <li>为避免下一档比上一档更响，百分比按 1..9 递增校正。</li>
 * </ul>
 */
public final class StepTable {

    /** 第 1..9 档的默认百分比：把 0..100% 均分 9 份，最后一档 = 系统最大 */
    private static final int[] DEFAULTS = {11, 22, 33, 44, 56, 67, 78, 89, 100};

    private StepTable() {
    }

    public static int[] defaults() {
        return DEFAULTS.clone();
    }

    public static int defaultPercent(int step) {
        int i = step - 1;
        if (i < 0) {
            i = 0;
        }
        if (i > DEFAULTS.length - 1) {
            i = DEFAULTS.length - 1;
        }
        return DEFAULTS[i];
    }

    /**
     * 把用户填的表规范成可用的 9 个百分比：夹在 1..100，并保证不递减
     * （后一档不会比前一档小，否则档位就没有「调大」的意义了）。
     */
    public static int[] sanitize(int[] raw) {
        int[] out = new int[DEFAULTS.length];
        int prev = 1;
        for (int i = 0; i < out.length; i++) {
            int v = (raw != null && i < raw.length && raw[i] > 0) ? raw[i] : DEFAULTS[i];
            if (v < prev) {
                v = prev;
            }
            if (v > 100) {
                v = 100;
            }
            out[i] = v;
            prev = v;
        }
        return out;
    }

    /** 档位（0..9）对应的百分比：0 档固定 0（静音） */
    public static int percentOfStep(int[] table, int step) {
        if (step <= 0) {
            return 0;
        }
        int i = step - 1;
        if (i > table.length - 1) {
            i = table.length - 1;
        }
        return table[i];
    }

    /**
     * 把 AudioService 的 index（0..indexMax）换算成档位（0..9）。
     *
     * <p>10 档时 index 每档 10（内部刻度每档 100），indexMax = 90，
     * 所以这里按比例 round(index * 9 / indexMax)，两种刻度都成立。
     */
    public static int stepOfIndex(int index, int indexMax) {
        if (index <= 0 || indexMax <= 0) {
            return 0;
        }
        int step = Math.round(index * (float) (Constants.STEPS - 1) / indexMax);
        if (step < 0) {
            return 0;
        }
        if (step > Constants.STEPS - 1) {
            return Constants.STEPS - 1;
        }
        return step;
    }

    /**
     * 档位换算成传给音频 HAL 的原生索引（0..nativeMax）。
     *
     * <p>0 档 = 0（静音）；9 档 = nativeMax（系统最大音量）；中间档按用户百分比线性取值。
     * 系统原生最大索引实测 160（= MAX_STREAM_VOLUME[3]），10 档后 AudioService 自己的
     * index 上限只有 90，所以必须由这里补回 90..160 的那部分响度。
     */
    public static int nativeIndexOfStep(int[] table, int step, int nativeMax) {
        if (step <= 0 || nativeMax <= 0) {
            return 0;
        }
        int pct = percentOfStep(table, step);
        int v = Math.round(nativeMax * (pct / 100.0f));
        if (v < 1) {
            // 1..9 档不是静音档，音量至少给 1
            v = 1;
        }
        if (v > nativeMax) {
            v = nativeMax;
        }
        return v;
    }
}
