package io.github.jocoks.magicvol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.jocoks.magicvol.feature.Constants;
import io.github.jocoks.magicvol.feature.Logs;
import io.github.jocoks.magicvol.feature.Scale;
import io.github.jocoks.magicvol.feature.VolumeSettings;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * MagicVol —— 把系统媒体音量改成 10 档（0 档静音，1..9 档可自定义）。
 *
 * <p>做法（全部在 system_server 的 AudioService 上，只碰媒体流）：
 * <ol>
 *   <li>钩 {@code AudioService.createStreamStates()}：在 VolumeStreamState 创建之前，
 *       把 {@code MAX_STREAM_VOLUME[3]} 从原生 160 改成 90 —— AudioService 内部刻度随即
 *       变成 900（= 9 档 × 每档 100），对外 API 就是 0..9 共 10 档，音量键每按一次正好一档；</li>
 *   <li>钩 {@code AudioSystem.setStreamVolumeIndexAS(...)}：AudioService 把档位换算成
 *       原生索引（0..90）后，这里再按用户设置翻译成真正的 HAL 索引（0..160）——
 *       第 9 档给满 160（系统最大响度），中间档按用户百分比，0 档给 0（静音）。</li>
 * </ol>
 *
 * <p>类加载说明：AudioService 在 services.jar，不在 boot classpath，
 * {@code ClassLoader.getSystemClassLoader()} 在 system_server 里<strong>看不到</strong>它，
 * 必须用模块自己的 ClassLoader（其 parent 才是 system_server 的 PathClassLoader）。
 * 因此这里逐个尝试一组候选 ClassLoader，并在失败后延迟重试。
 *
 * <p>分层：本类只装钩子；换算规则在 feature 包（StepTable / Scale / VolumeSettings）。
 */
public class HookEntry extends XposedModule {

    private static final String TAG = Constants.TAG;
    private static final String CLS_AUDIO_SERVICE = "com.android.server.audio.AudioService";

    /** 重试次数上限（500ms 一次 → 最长约 30 秒，足够覆盖开机阶段） */
    private static final int MAX_RETRY = 60;
    private static final long RETRY_DELAY_MS = 500L;

    /** 两个钩子各自的 guard，互不牵连（一个失败不影响另一个重试） */
    private static volatile boolean sStreamHooked;
    private static volatile boolean sNativeHooked;
    private static volatile boolean sRetryScheduled;
    private static volatile int sRetries;

    /** libxposed 在 onSystemServerStarting 里给出的 system_server ClassLoader（services.jar 可见） */
    private static volatile ClassLoader sServerLoader;


    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // feature 层都通过 Logs 打日志；这里把出口接到 LSPosed 模块日志
        Logs.install(new Logs.Sink() {
            @Override
            public void log(int priority, String tag, String msg, Throwable tr) {
                if (tr != null) {
                    HookEntry.this.log(priority, tag, msg, tr);
                } else {
                    HookEntry.this.log(priority, tag, msg);
                }
            }
        });
        info("module loaded, systemServer=" + param.isSystemServer()
                + " process=" + param.getProcessName());
        // 注意：system_server 里 "android" 包早在模块被注入前就已加载，
        // LSPosed 不会再回调 onPackageLoaded("android")，所以必须在模块加载时直接装钩子。
        if (param.isSystemServer()) {
            ensureStreamHook();
            ensureNativeHook();
            scheduleRetry();
        }
    }

    /**
     * system_server 的正确入口：libxposed 在这里给出 system_server 的 ClassLoader，
     * 只有它（及其 parent 链）能看见 services.jar 里的 com.android.server.audio.AudioService。
     * 之前用 ClassLoader.getSystemClassLoader() 拿不到这个类，钩子装不上就是因为它只看见 boot classpath。
     */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            ClassLoader cl = param.getClassLoader();
            sServerLoader = cl;
            info("system server starting, loader=" + describeLoader(cl));
            ensureNativeHook();
            ensureStreamHook();
            scheduleRetry();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onSystemServerStarting failed", t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            if (Constants.PKG_ANDROID.equals(param.getPackageName())) {
                ensureNativeHook();
                ensureStreamHook();
                        scheduleRetry();
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onPackageLoaded(" + param.getPackageName() + ") failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 安装 / 重试
    // ------------------------------------------------------------------

    /**
     * 安装刻度钩子。类暂时不可见时返回 false，由定时重试继续尝试
     * （开机阶段 system_server 的类加载环境才逐步就绪）。
     */
    private boolean ensureStreamHook() {
        if (sStreamHooked) {
            return true;
        }
        Class<?> cls = null;
        ClassLoader preferred = sServerLoader;
        if (preferred != null) {
            try {
                cls = Class.forName(CLS_AUDIO_SERVICE, false, preferred);
                info("resolved " + CLS_AUDIO_SERVICE + " via system server loader ("
                        + describeLoader(preferred) + ")");
            } catch (Throwable ignored) {
                // 备用：下面继续按候选链找
            }
        }
        if (cls == null) {
            cls = findServerClass(CLS_AUDIO_SERVICE);
        }
        if (cls == null) {
            return false;
        }
        try {
            Method m = cls.getDeclaredMethod("createStreamStates");
            hook(m)
                    .setId("magicvol-stream-steps")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            try {
                                patchScale(chain.getThisObject());
                            } catch (Throwable t) {
                                log(Log.ERROR, TAG, "patch scale failed", t);
                            }
                            Object result = chain.proceed();
                            try {
                                verifyScale(chain.getThisObject());
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "verify scale failed", t);
                            }
                            return result;
                        }
                    });
            sStreamHooked = true;
            info("hooked AudioService.createStreamStates");
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook createStreamStates failed", t);
            return false;
        }
    }

    /** 原生索引钩子：AudioSystem 在 framework.jar（boot classpath），始终可见 */
    private boolean ensureNativeHook() {
        if (sNativeHooked) {
            return true;
        }
        try {
            Class<?> cls = Class.forName("android.media.AudioSystem", false,
                    HookEntry.class.getClassLoader());
            Method m = cls.getDeclaredMethod("setStreamVolumeIndexAS",
                    int.class, int.class, boolean.class, int.class);
            hook(m)
                    .setId("magicvol-native-index")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            try {
                                int stream = (Integer) chain.getArg(0);
                                if (stream == Constants.STREAM_MUSIC) {
                                    int index = (Integer) chain.getArg(1);
                                    int mapped = Scale.remap(index);
                                    if (mapped != index) {
                                        Object[] args = chain.getArgs().toArray();
                                        args[1] = mapped;
                                        return chain.proceed(args);
                                    }
                                }
                            } catch (Throwable t) {
                                log(Log.ERROR, TAG, "remap failed", t);
                            }
                            return chain.proceed();
                        }
                    });
            sNativeHooked = true;
            info("hooked AudioSystem.setStreamVolumeIndexAS");
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook native index failed", t);
            return false;
        }
    }

    private void scheduleRetry() {
        if (sRetryScheduled || (sStreamHooked && sNativeHooked)) {
            return;
        }
        sRetryScheduled = true;
        try {
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sRetries++;
                    if (!sNativeHooked) {
                        ensureNativeHook();
                    }
                    if (!sStreamHooked) {
                        ensureStreamHook();
                    }
                    if ((sStreamHooked && sNativeHooked) || sRetries >= MAX_RETRY) {
                        sRetryScheduled = false;
                        if (!sStreamHooked) {
                            log(Log.ERROR, TAG, "give up stream hook after " + sRetries
                                    + " retries (AudioService 仍不可见)");
                        }
                        return;
                    }
                    handler.postDelayed(this, RETRY_DELAY_MS);
                }
            }, RETRY_DELAY_MS);
        } catch (Throwable t) {
            sRetryScheduled = false;
            log(Log.WARN, TAG, "schedule retry failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 类加载：找一个能看见 services.jar 的 ClassLoader
    // ------------------------------------------------------------------

    private Class<?> findServerClass(String name) {
        List<ClassLoader> candidates = new ArrayList<>();
        // 首选 libxposed 给的 system_server ClassLoader
        collectChain(candidates, sServerLoader);
        // 模块自己的 ClassLoader 通常以 system_server 的 PathClassLoader 为 parent
        collectChain(candidates, HookEntry.class.getClassLoader());
        collectChain(candidates, ClassLoader.getSystemClassLoader());
        try {
            collectChain(candidates, Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignored) {
            // 某些线程没有 context classloader，忽略
        }
        // ActivityThread 的系统 Context 用的就是 system_server 的 ClassLoader
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false,
                    HookEntry.class.getClassLoader());
            Method current = at.getDeclaredMethod("currentActivityThread");
            Object thread = current.invoke(null);
            if (thread != null) {
                for (String getter : new String[]{"getSystemContext", "getApplication"}) {
                    try {
                        Method get = at.getDeclaredMethod(getter);
                        Object ctx = get.invoke(thread);
                        if (ctx instanceof Context) {
                            collectChain(candidates, ((Context) ctx).getClassLoader());
                        }
                    } catch (Throwable ignored) {
                        // 该方法不存在或此刻还拿不到，继续下一个
                    }
                }
            }
        } catch (Throwable ignored) {
            // ActivityThread 不可用就用前面的候选
        }
        for (int i = 0; i < candidates.size(); i++) {
            ClassLoader loader = candidates.get(i);
            try {
                Class<?> cls = Class.forName(name, false, loader);
                info("resolved " + name + " via loader#" + i + " ("
                        + describeLoader(loader) + ")");
                return cls;
            } catch (Throwable ignored) {
                // 这个 ClassLoader 看不见，试下一个
            }
        }
        if (sRetries < 2) {
            log(Log.WARN, TAG, "cannot resolve " + name + " yet, candidates="
                    + describeCandidates(candidates));
        }
        return null;
    }

    private static void collectChain(List<ClassLoader> out, ClassLoader start) {
        for (ClassLoader l = start; l != null; l = l.getParent()) {
            boolean found = false;
            for (ClassLoader existing : out) {
                if (existing == l) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.add(l);
            }
        }
    }

    private static String describeLoader(ClassLoader loader) {
        return loader.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static String describeCandidates(List<ClassLoader> candidates) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i).append('=').append(describeLoader(candidates.get(i)));
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------
    // 改刻度：MAX_STREAM_VOLUME[3] 160 → 90
    // ------------------------------------------------------------------

    /**
     * MAX_STREAM_VOLUME 是 AudioService 的静态数组，VolumeStreamState 构造时用
     * {@code mIndexMax = MAX_STREAM_VOLUME[3] * 10} 定刻度，所以必须在 createStreamStates
     * 进入时改，改完系统自己就按 10 档算（面板、持久化、广播、按键步长全都跟着变）。
     */
    private void patchScale(Object audioService) throws Throwable {
        attachSettingsContext(audioService);
        if (Scale.patched()) {
            return;
        }
        // 实际实例可能是子类（本机是 com.android.server.audio.HwAudioService），
        // 静态数组声明在 AudioService 上，必须沿继承链找。
        Class<?> cls = audioService.getClass();
        Field maxField = findField(cls, "MAX_STREAM_VOLUME");
        if (maxField == null) {
            log(Log.ERROR, TAG, "MAX_STREAM_VOLUME 字段没找到（类=" + cls.getName() + "）");
            return;
        }
        maxField.setAccessible(true);
        int[] max = (int[]) maxField.get(null);
        if (max == null || max.length <= Constants.STREAM_MUSIC) {
            log(Log.ERROR, TAG, "MAX_STREAM_VOLUME 异常: " + (max == null ? "null" : max.length));
            return;
        }
        int nativeMax = max[Constants.STREAM_MUSIC];
        if (nativeMax <= 0) {
            nativeMax = 160;
        }
        // 先记下原生上限（160），后面的映射要靠它把第 9 档还原成系统最大响度
        Scale.markPatched(nativeMax);
        max[Constants.STREAM_MUSIC] = Scale.patchedNativeMaxIndex();
        info("scale patched: nativeMax=" + nativeMax
                + " mediaNativeIndexMax=" + max[Constants.STREAM_MUSIC]
                + " internalMax=" + (max[Constants.STREAM_MUSIC] * 10));
    }

    /**
     * 设置读取通道：钩子跑在 system_server，读不到模块本体的 SharedPreferences，
     * 所以从 AudioService 的 mContext（system Context）拿 ContentResolver 去查 SettingsProvider。
     *
     * <p>注意：libxposed 的 getRemotePreferences() 是 LSPosed 自己维护的另一份偏好文件，
     * 与模块本体用 getSharedPreferences() 写的不是同一份，钩子从那里只能读到默认值，
     * 所以这里不再用它。
     */
    private void attachSettingsContext(Object audioService) {
        try {
            Context ctx = null;
            Field field = findField(audioService.getClass(), "mContext");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(audioService);
                if (value instanceof Context) {
                    ctx = (Context) value;
                }
            }
            if (ctx == null) {
                ctx = systemContext();
            }
            if (ctx != null) {
                VolumeSettings.attach(ctx);
                int[] table = VolumeSettings.table();
                info("settings context ready: " + ctx.getPackageName()
                        + " table=" + table[0] + "/" + table[table.length - 1]);
            } else {
                log(Log.WARN, TAG, "settings context unavailable, custom levels will be ignored");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "attach settings context failed: " + t);
        }
    }

    /** 兜底：ActivityThread 的 system Context（AudioService 字段取不到时用） */
    private static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object ctx = activityThread.getMethod("getSystemContext").invoke(thread);
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
            Object app = activityThread.getMethod("getApplication").invoke(thread);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 取不到就算了，调用方会退回默认档位表
        }
        return null;
    }

    /** INFO 级日志受「调试日志」开关控制（默认关闭）；ERROR/WARN 永远输出 */
    private void info(String msg) {
        if (Logs.enabled()) {
            log(Log.INFO, TAG, msg);
        }
    }

    private void info(String msg, Throwable t) {
        if (Logs.enabled()) {
            log(Log.INFO, TAG, msg, t);
        }
    }

    /** 沿继承链找字段（Honor 的 HwAudioService 子类里没有 AudioService 的静态数组） */
    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /** createStreamStates 跑完后回读媒体流的 mIndexMin/mIndexMax，确认刻度真的变成 900 */
    private void verifyScale(Object audioService) {
        try {
            Field statesField = findField(audioService.getClass(), "mStreamStates");
            if (statesField == null) {
                log(Log.WARN, TAG, "verify: mStreamStates not found");
                return;
            }
            statesField.setAccessible(true);
            Object states = statesField.get(audioService);
            if (!(states instanceof android.util.SparseArray)) {
                log(Log.WARN, TAG, "verify: unexpected mStreamStates " + states);
                return;
            }
            Object vss = ((android.util.SparseArray<?>) states).get(Constants.STREAM_MUSIC);
            if (vss == null) {
                log(Log.WARN, TAG, "verify: no VolumeStreamState for media");
                return;
            }
            Field minField = findField(vss.getClass(), "mIndexMin");
            Field maxField = findField(vss.getClass(), "mIndexMax");
            Integer min = null;
            Integer max = null;
            if (minField != null) {
                minField.setAccessible(true);
                min = (Integer) minField.get(vss);
            }
            if (maxField != null) {
                maxField.setAccessible(true);
                max = (Integer) maxField.get(vss);
            }
            int expectedMax = Scale.patchedNativeMaxIndex() * Constants.INDEX_PER_STEP;
            if (max == null || max.intValue() != expectedMax) {
                log(Log.WARN, TAG, "verify: media vss min=" + min + " max=" + max
                        + " (预期 " + expectedMax + ")");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "verify scale failed", t);
        }
    }
}
