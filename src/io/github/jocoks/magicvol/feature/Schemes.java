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
