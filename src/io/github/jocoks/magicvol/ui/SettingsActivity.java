package io.github.jocoks.magicvol.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.jocoks.magicvol.feature.Constants;
import io.github.jocoks.magicvol.feature.Schemes;
import io.github.jocoks.magicvol.feature.StepTable;

/**
 * 交互层：MagicVol 设置页。
 *
 * <p>白色背景 + 代码搭界面（模块不依赖任何 UI 库）。9 根滑杆分别对应第 1..9 档，
 * 值是「占系统最大音量的百分比」；0 档固定静音，所以不提供滑杆。
 *
 * <p>另外提供两种更省事的填法：按「起始值 + 递增值」自动铺满 9 档，以及把当前 9 个值
 * 存成可重命名的「方案」，随时切回来。
 */
public class SettingsActivity extends Activity {

    /** 自定义档数（第 1..9 档） */
    private static final int CUSTOM = Constants.CUSTOM_STEPS;

    private final SeekBar[] mBars = new SeekBar[CUSTOM];
    private final TextView[] mValues = new TextView[CUSTOM];
    private SharedPreferences mPrefs;
    private TextView mStatus;
    private CheckBox mDebugCheck;
    /** 程序自己改滑杆时不要再触发保存 */
    private boolean mSyncing;

    private EditText mStartInput;
    private EditText mStepInput;

    private TextView mSchemeLabel;
    private LinearLayout mSchemeChips;
    private List<Schemes.Entry> mSchemes;
    /** 当前方案名；null 表示还没存过方案 */
    private String mActiveScheme;
    /** 应用方案后又手动拖过滑杆 */
    private boolean mSchemeDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("MagicVol · 10 档媒体音量");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("0 档 = 静音（没有声音）。1–9 档分别设置「占系统最大音量的百分比」，"
                + "数值越大越响；第 9 档 100% 就是系统最大音量，本模块不会超过它。\n"
                + "低档位不会高于高档位，保存时会自动校正。只改媒体音量，铃声 / 通话 / 闹钟不受影响。\n"
                + "本模块只在 MagicOS 11（Android 17）上实测，其他系统版本请自行体验。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        for (int step = 1; step <= CUSTOM; step++) {
            table.addView(buildRow(step));
        }
        root.addView(table);

        root.addView(buildQuickSet());
        root.addView(buildSchemeSection());

        mStatus = new TextView(this);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStatus.setTextColor(Color.DKGRAY);
        mStatus.setPadding(0, dp(16), 0, dp(6));
        root.addView(mStatus);

        Button apply = new Button(this);
        apply.setText("应用这个音量设置");
        apply.setOnClickListener(v -> applyCurrentSetting());
        root.addView(apply);

        Button reset = new Button(this);
        reset.setText("恢复默认档位（11…100%）");
        reset.setOnClickListener(v -> resetTable());
        root.addView(reset);

        mDebugCheck = new CheckBox(this);
        mDebugCheck.setText("调试日志（排查问题用，默认关闭）");
        mDebugCheck.setChecked(mPrefs.getBoolean(Constants.KEY_DEBUG, false));
        mDebugCheck.setPadding(0, dp(18), 0, 0);
        mDebugCheck.setOnCheckedChangeListener((v, checked) -> {
            mPrefs.edit().putBoolean(Constants.KEY_DEBUG, checked).apply();
            toast(checked ? "已打开调试日志：模块日志里会出现详细记录" : "已关闭调试日志");
        });
        root.addView(mDebugCheck);

        TextView debugHint = new TextView(this);
        debugHint.setText("开关改动最多 1 秒内对模块生效；开机阶段的日志需要重启后才会出现。");
        debugHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        debugHint.setTextColor(Color.GRAY);
        debugHint.setPadding(0, dp(4), 0, dp(24));
        root.addView(debugHint);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(root);
        setContentView(scroll);

        mSchemes = Schemes.parse(mPrefs.getString(Constants.KEY_SCHEMES, ""));
        mActiveScheme = mPrefs.getString(Constants.KEY_ACTIVE_SCHEME, null);
        refreshScheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSyncing = true;
        int[] table = StepTable.sanitize(readRaw());
        for (int i = 0; i < CUSTOM; i++) {
            mBars[i].setProgress(table[i]);
        }
        mSyncing = false;
        refreshValues(table);
        refreshStatus();
    }

    private LinearLayout buildRow(final int step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = new TextView(this);
        name.setText("第 " + step + " 档");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTextColor(Color.BLACK);
        row.addView(name, new LinearLayout.LayoutParams(dp(58),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setMin(1);
        bar.setProgress(mPrefs.getInt(Constants.key(step), StepTable.defaultPercent(step)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!mSyncing) {
                    saveTable();
                    markSchemeDirty();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(Color.BLACK);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        mBars[step - 1] = bar;
        mValues[step - 1] = value;
        return row;
    }

    // ── 递增快速填档 ───────────────────────────────────────────────

    private LinearLayout buildQuickSet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(22), 0, 0);

        TextView title = new TextView(this);
        title.setText("按递增快速填档位");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(Color.BLACK);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("例：起始 2、递增 3 → 第 1 档 2%、第 2 档 5%、第 3 档 8%……超过 100% 的档位按 100% 算。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, dp(4), 0, dp(8));
        box.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        mStartInput = numberInput("2");
        mStepInput = numberInput("3");

        row.addView(smallLabel("起始"));
        row.addView(mStartInput);
        row.addView(smallLabel("递增"));
        row.addView(mStepInput);

        Button apply = new Button(this);
        apply.setText("应用");
        apply.setOnClickListener(v -> applyArithmetic());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        row.addView(apply, lp);
        box.addView(row);
        return box;
    }

    /** 第 k 档 = 起始 + (k-1) × 递增，夹在 1..100 */
    private void applyArithmetic() {
        int start = parseInt(mStartInput, StepTable.defaultPercent(1));
        int inc = parseInt(mStepInput, 0);
        int[] table = new int[CUSTOM];
        for (int i = 0; i < CUSTOM; i++) {
            long v = (long) start + (long) inc * i;
            table[i] = (int) Math.max(1, Math.min(100, v));
        }
        table = StepTable.sanitize(table);
        writeTable(table);
        markSchemeDirty();
        toast("已按递增填好：" + table[0] + "% / " + table[1] + "% / " + table[2] + "% … "
                + table[CUSTOM - 1] + "%");
    }

    // ── 方案（保存 / 应用 / 重命名 / 删除）────────────────────────────

    private LinearLayout buildSchemeSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(22), 0, 0);

        TextView title = new TextView(this);
        title.setText("方案");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(Color.BLACK);
        box.addView(title);

        mSchemeLabel = new TextView(this);
        mSchemeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mSchemeLabel.setTextColor(Color.DKGRAY);
        mSchemeLabel.setPadding(0, dp(4), 0, dp(8));
        box.addView(mSchemeLabel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button save = new Button(this);
        save.setText("保存为方案…");
        save.setOnClickListener(v -> saveAsScheme());
        row.addView(save);

        Button rename = new Button(this);
        rename.setText("重命名…");
        rename.setOnClickListener(v -> renameScheme());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.leftMargin = dp(4);
        row.addView(rename, lp1);

        Button remove = new Button(this);
        remove.setText("删除");
        remove.setOnClickListener(v -> deleteScheme());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.leftMargin = dp(4);
        row.addView(remove, lp2);
        box.addView(row);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        mSchemeChips = new LinearLayout(this);
        mSchemeChips.setOrientation(LinearLayout.HORIZONTAL);
        mSchemeChips.setPadding(0, dp(8), 0, 0);
        hsv.addView(mSchemeChips);
        box.addView(hsv);
        return box;
    }

    private void saveAsScheme() {
        final String initial = mActiveScheme != null ? mActiveScheme : Schemes.suggestName(mSchemes);
        final EditText input = dialogInput(initial);
        new AlertDialog.Builder(this)
                .setTitle("保存为方案（可改名）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名字不能为空");
                        return;
                    }
                    int[] table = StepTable.sanitize(readRaw());
                    writeTable(table);
                    int at = Schemes.indexOf(mSchemes, name);
                    boolean replaced = at >= 0;
                    if (replaced) {
                        mSchemes.set(at, new Schemes.Entry(name, table));
                    } else {
                        mSchemes.add(new Schemes.Entry(name, table));
                    }
                    mActiveScheme = name;
                    mSchemeDirty = false;
                    persistSchemes();
                    refreshScheme();
                    toast(replaced ? "已覆盖方案：" + name : "已保存方案：" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renameScheme() {
        if (mActiveScheme == null) {
            toast("先在下面点一个方案，或先保存一个");
            return;
        }
        final EditText input = dialogInput(mActiveScheme);
        new AlertDialog.Builder(this)
                .setTitle("重命名方案")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名字不能为空");
                        return;
                    }
                    int at = Schemes.indexOf(mSchemes, mActiveScheme);
                    if (at < 0) {
                        return;
                    }
                    int dup = Schemes.indexOf(mSchemes, name);
                    if (dup >= 0 && dup != at) {
                        mSchemes.remove(dup);
                        at = Schemes.indexOf(mSchemes, mActiveScheme);
                    }
                    mSchemes.set(at, new Schemes.Entry(name, mSchemes.get(at).pct));
                    mActiveScheme = name;
                    persistSchemes();
                    refreshScheme();
                    toast("已重命名为：" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteScheme() {
        if (mActiveScheme == null) {
            toast("当前没有选中的方案");
            return;
        }
        final String name = mActiveScheme;
        new AlertDialog.Builder(this)
                .setTitle("删除方案")
                .setMessage("删除「" + name + "」？当前 9 档数值不会变。")
                .setPositiveButton("删除", (d, w) -> {
                    int at = Schemes.indexOf(mSchemes, name);
                    if (at >= 0) {
                        mSchemes.remove(at);
                    }
                    mActiveScheme = null;
                    mSchemeDirty = false;
                    persistSchemes();
                    refreshScheme();
                    toast("已删除：" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyScheme(Schemes.Entry entry) {
        writeTable(entry.pct);
        mActiveScheme = entry.name;
        mSchemeDirty = false;
        persistSchemes();
        refreshScheme();
        toast("已切换到方案：" + entry.name);
    }

    private void markSchemeDirty() {
        if (mActiveScheme != null && !mSchemeDirty) {
            mSchemeDirty = true;
            refreshScheme();
        }
    }

    private void persistSchemes() {
        SharedPreferences.Editor ed = mPrefs.edit();
        ed.putString(Constants.KEY_SCHEMES, Schemes.serialize(mSchemes));
        if (mActiveScheme == null) {
            ed.remove(Constants.KEY_ACTIVE_SCHEME);
        } else {
            ed.putString(Constants.KEY_ACTIVE_SCHEME, mActiveScheme);
        }
        ed.apply();
    }

    private void refreshScheme() {
        if (mSchemeLabel == null || mSchemeChips == null) {
            return;
        }
        String name = mActiveScheme == null ? "未命名（自定义）" : mActiveScheme;
        mSchemeLabel.setText("当前方案：" + name + (mSchemeDirty ? "（已修改，未保存）" : ""));

        mSchemeChips.removeAllViews();
        for (final Schemes.Entry entry : mSchemes) {
            boolean active = entry.name.equals(mActiveScheme) && !mSchemeDirty;
            Button chip = new Button(this);
            chip.setText((active ? "✓ " : "") + entry.name);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setAllCaps(false);
            chip.setOnClickListener(v -> applyScheme(entry));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            mSchemeChips.addView(chip, lp);
        }
    }

    // ── 档位读写 ──────────────────────────────────────────────────

    private int[] readRaw() {
        int[] raw = new int[CUSTOM];
        for (int i = 0; i < CUSTOM; i++) {
            raw[i] = mBars[i].getProgress();
        }
        return raw;
    }

    /** 把 9 个滑杆的值规范后保存，顺便把被校正过的滑杆同步回去 */
    private void saveTable() {
        writeTable(StepTable.sanitize(readRaw()));
    }

    private void writeTable(int[] table) {
        SharedPreferences.Editor ed = mPrefs.edit();
        for (int i = 0; i < CUSTOM; i++) {
            ed.putInt(Constants.key(i + 1), table[i]);
        }
        ed.apply();
        mSyncing = true;
        for (int i = 0; i < CUSTOM; i++) {
            if (mBars[i].getProgress() != table[i]) {
                mBars[i].setProgress(table[i]);
            }
        }
        mSyncing = false;
        refreshValues(table);
        refreshStatus();
    }

    private void refreshValues(int[] table) {
        for (int i = 0; i < CUSTOM; i++) {
            mValues[i].setText(table[i] + "%");
        }
    }

    private void refreshStatus() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            mStatus.setText("");
            return;
        }
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        String range = max == Constants.STEPS - 1
                ? "当前 0–" + max + " 共 " + (max + 1) + " 档"
                : "当前 0–" + max + "（模块未生效，重启手机后生效）";
        mStatus.setText(range + "，媒体音量在 " + cur + " 档");
    }

    /**
     * 应用这个音量设置：立刻保存当前 9 档百分比，并强制把当前档位重新下发一次。
     *
     * <p>为什么要"抖"一下：AudioService 在档位没变时会跳过下发，钩子也就没机会按新百分比
     * 重算 HAL 索引；先把音量切到相邻档再切回来，才能保证新设置立刻生效。
     */
    private void applyCurrentSetting() {
        saveTable();
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            toast("系统音频服务不可用");
            return;
        }
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int alt = current > 0 ? current - 1 : Math.min(1, max);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, alt, 0);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, current, 0);
                    toast("已应用当前设置（第 " + current + " 档）");
                } catch (Throwable t) {
                    toast("应用失败：" + t);
                }
                refreshStatus();
            }, 150L);
        } catch (Throwable t) {
            toast("应用失败：" + t);
        }
    }

    private void resetTable() {
        writeTable(StepTable.defaults());
        mSchemeDirty = mActiveScheme != null;
        refreshScheme();
        toast("已恢复默认档位");
    }

    // ── 小工具 ────────────────────────────────────────────────────

    private EditText numberInput(String text) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setGravity(Gravity.CENTER);
        int w = dp(58);
        input.setLayoutParams(new LinearLayout.LayoutParams(w,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private EditText dialogInput(String text) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }

    private TextView smallLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(Color.BLACK);
        label.setPadding(dp(6), 0, dp(4), 0);
        return label;
    }

    private int parseInt(EditText input, int fallback) {
        try {
            String s = input.getText().toString().trim();
            return s.isEmpty() ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
