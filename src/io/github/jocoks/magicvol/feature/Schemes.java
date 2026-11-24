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

    /** 内置推荐方案的名字（第一次打开时加入方案列表，不会自动套用） */
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

    /**
     * 第一次运行时是否把「推荐」加进方案列表。
     *
     * <p>只加按钮、不套用数值：推荐只是可选方案之一，不能替用户决定默认档位。
     * seeded 表示以前已经加过一次，用户自己删掉后就不再自动加回来。
     */
    public static boolean shouldAddPreset(List<Entry> list, boolean seeded) {
        return !seeded && indexOf(list, PRESET_NAME) < 0;
    }

    /**
     * 是否是旧版「全新安装自动选中推荐」留下的状态。
     *
     * <p>1.1.0 / 1.1.1 会替用户套用推荐，1.1.2 起不再这么做；升级时用这个判断把
     * 那一次自动写入的推荐还原成默认档位。用户自己改过数值就不算，避免误伤。
     */
    public static boolean looksAutoSelectedPreset(String activeName, int[] table) {
        return PRESET_NAME.equals(activeName)
                && java.util.Arrays.equals(StepTable.sanitize(table), PRESET_PCT);
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
