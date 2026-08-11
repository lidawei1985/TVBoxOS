package com.github.tvbox.osc.search;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * 规格3：本地明星联想。
 * 输入拼音首字母(如 L)或全拼/汉字，返回匹配的明星列表（姓名 + 可选海报）。
 * 数据来自 assets/celebrities.json，首次加载后缓存。
 */
public class CelebritySuggestion {
    private static final String TAG = "CelebritySuggestion";
    private static ArrayList<Celebrity> ALL = null;

    public static void ensureLoaded(Context ctx) {
        if (ALL != null) return;
        ALL = new ArrayList<>();
        try {
            InputStream is = ctx.getAssets().open("celebrities.json");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "utf-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Celebrity c = new Celebrity();
                    c.name = o.optString("name");
                    c.py = o.optString("py").toLowerCase();
                    c.pyi = o.optString("pyi").toLowerCase();
                    c.poster = o.optString("poster");
                    ALL.add(c);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "load celebrities.json failed: " + t);
        }
    }

    public static ArrayList<Celebrity> match(Context ctx, String query) {
        ensureLoaded(ctx);
        ArrayList<Celebrity> out = new ArrayList<>();
        if (ALL == null) return out;
        String raw = (query == null ? "" : query).trim();
        String q = raw.toLowerCase();
        if (q.isEmpty()) return out;
        boolean singleLetter = q.length() == 1 && Character.isLetter(q.charAt(0));
        final int limit = 12;
        for (Celebrity c : ALL) {
            boolean hit;
            if (singleLetter) {
                hit = c.pyi.startsWith(q) || c.py.startsWith(q);
            } else {
                hit = c.py.contains(q) || c.pyi.startsWith(q)
                        || (c.name != null && c.name.contains(raw));
            }
            if (hit) {
                out.add(c);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }
}
