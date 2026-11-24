package io.github.jocoks.magicvol.feature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯逻辑：把「方案」（名字 + 9 档百分比）与 JSON 互转。
 *
 * <p>不碰界面、不碰 SharedPreferences——存取由 ui 层负责，这样方案本身的规则可以单测。
 */
public final class Schemes {

    /** 内置推荐方案的名字（第一次打开时自动带上） */
    public static final String PRESET_NAME = "推荐";

    /** 内置推荐方案的 9 档百分比（低档细、高档拉开，实测听感比较顺） */
    public static final int[] PRESET_PCT = {7, 12, 17, 22, 27, 40, 60, 80, 100};

    /** 一个方案：名字 + 第 1..9 档的百分比（已规范化） */
    public static final class Entry {

        public final String name;
        public final int[] pct;

        public Entry(String name, int[] pct) {
            this.name = name;
            this.pct = StepTable.sanitize(pct);
        }
    }

    /** JSON → 方案列表；坏数据跳过，不抛异常 */
    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name", "").trim();
                JSONArray p = o.optJSONArray("pct");
                if (name.isEmpty() || p == null) {
                    continue;
                }
                int[] raw = new int[Constants.CUSTOM_STEPS];
                for (int k = 0; k < raw.length; k++) {
                    raw[k] = p.optInt(k, StepTable.defaultPercent(k + 1));
                }
                out.add(new Entry(name, raw));
            }
        } catch (JSONException ignored) {
            // 配置坏了就当没有方案，界面照常可用
        }
        return out;
    }

    /** 方案列表 → JSON */
    public static String serialize(List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (Entry e : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", e.name);
                JSONArray p = new JSONArray();
                for (int v : e.pct) {
                    p.put(v);
                }
                o.put("pct", p);
            } catch (JSONException ignored) {
                continue;
            }
            arr.put(o);
        }
        return arr.toString();
    }

    /** 找一个同名方案的下标，没有返回 -1 */
    public static int indexOf(List<Entry> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 内置推荐方案（Entry 构造时会按「低档不高于高档」再规范化一次） */
    public static Entry recommended() {
        return new Entry(PRESET_NAME, PRESET_PCT);
    }

    /** 第一次运行时要对方案列表做什么 */
    public static final class PresetPlan {

        /** 是否把「推荐」插到列表最前面 */
        public final boolean add;
        /** 是否直接选中并应用「推荐」（只发生在全新安装） */
        public final boolean apply;

        PresetPlan(boolean add, boolean apply) {
            this.add = add;
            this.apply = apply;
        }
    }

    /**
     * 计算内置推荐方案的初始化动作。
     *
     * @param list           已读取的方案列表
     * @param seeded         以前是否已经执行过（用户删掉后不再自动加回来）
     * @param hasStoredTable 用户是否已经存过 9 档数值（说明不是全新安装）
     */
    public static PresetPlan planPreset(List<Entry> list, boolean seeded, boolean hasStoredTable) {
        if (seeded) {
            return new PresetPlan(false, false);
        }
        boolean add = indexOf(list, PRESET_NAME) < 0;
        boolean apply = add && list.isEmpty() && !hasStoredTable;
        return new PresetPlan(add, apply);
    }

    /** 生成本次默认方案名：方案 1、方案 2 …… */
    public static String suggestName(List<Entry> list) {
        for (int n = 1; ; n++) {
            String candidate = "方案 " + n;
            if (indexOf(list, candidate) < 0) {
                return candidate;
            }
        }
    }

    private Schemes() {
    }
}
