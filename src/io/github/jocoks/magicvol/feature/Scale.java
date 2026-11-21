package io.github.jocoks.magicvol.feature;

/**
 * 功能层：系统原生刻度 ↔ 10 档刻度。
 *
 * <p>实测（Honor MagicOS 11 / Android 17）：
 * <ul>
 *   <li>媒体流原生最大索引 {@code nativeMax = 160}（AudioService.MAX_STREAM_VOLUME[3]）；</li>
 *   <li>AudioService 内部刻度 = 原生索引 × 10（mIndexMax = 1600）；</li>
 *   <li>对外 API 再除 10（getStreamVolume 两次 /10），所以原生是 0..16 共 17 档；</li>
 *   <li>每个音量键 = 内部 +100 = 索引 +10 = 对外 +1。</li>
 * </ul>
 *
 * <p>本模块把媒体流内部最大值改成 900（索引 90，每档 10），于是对外变成 0..9 共 10 档；
 * 再把每档的索引按用户百分比映射到 0..90 区间。
 *
 * <p>注意：AudioPolicyManager 侧的最大索引也是 90（dumpsys media.audio_policy 的
 * {@code Index Max} = 90，百分比 = index*100/90），超过 90 会被夹到 90 即满刻度 −0dB。
 * 所以映射目标必须是 0..90 而不是原生 0..160，否则第 5 档以上全部饱和、听不出差别。
 */
public final class Scale {

    /** 系统原生最大索引（createStreamStates 里改刻度之前捕获，实测 160） */
    private static volatile int sNativeMax = 160;

    /** 10 档时 AudioService 的 index 上限：9 档 × 每档 10 = 90 */
    private static volatile int sIndexMax = (Constants.STEPS - 1) * Constants.INDEX_PER_STEP;

    /** 刻度是否已经改成功（没改成功就不做原生索引映射，保持系统原样） */
    private static volatile boolean sPatched;

    private Scale() {
    }

    /** 改刻度成功后调用（在 createStreamStates 入口，改 MAX_STREAM_VOLUME 之前捕获的原生上限） */
    public static void markPatched(int nativeMax) {
        sNativeMax = nativeMax;
        sPatched = true;
    }

    public static boolean patched() {
        return sPatched;
    }

    public static int nativeMax() {
        return sNativeMax;
    }

    public static int indexMax() {
        return sIndexMax;
    }

    /** 10 档时写进 MAX_STREAM_VOLUME[3] 的值（原生索引上限 90） */
    public static int patchedNativeMaxIndex() {
        return sIndexMax;
    }

    /**
     * 把 AudioService 算出来的索引（0..90）按用户百分比换算成真正下发的索引。
     *
     * <p>目标区间是 AudioPolicyManager 的 0..90：该区间的 90 = 100% = 系统最大音量，
     * 因此第 9 档（100%）等于系统最大，任何一档都不会超过它。
     * 未改刻度时原样返回，不干扰系统默认行为。
     */
    public static int remap(int index) {
        if (!sPatched) {
            return index;
        }
        int step = StepTable.stepOfIndex(index, sIndexMax);
        int out = StepTable.nativeIndexOfStep(VolumeSettings.table(), step, sIndexMax);
        return out;
    }
}
