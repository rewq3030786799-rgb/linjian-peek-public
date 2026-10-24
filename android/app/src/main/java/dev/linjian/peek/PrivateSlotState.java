package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.UUID;

/** 同栖私密老虎机：奖池与记录只保存在手机本地，云端只转发指令。 */
public final class PrivateSlotState {
    public static final String ACTION_UPDATED = "dev.linjian.peek.PRIVATE_SLOT_UPDATED";
    private static final String PREF = "tongqi_private_slot_v1";
    private static final String KEY_POOL = "pool_json";
    private static final String KEY_HISTORY = "history_json";
    private static final int MAX_HISTORY = 80;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PrivateSlotState() { }

    public static JSONObject collect(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            JSONArray pool = pool(ctx);
            JSONArray history = history(ctx);
            JSONArray pending = new JSONArray();
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null && "pending".equals(item.optString("status"))) pending.put(item);
            }
            out.put("ok", true);
            out.put("version", "1.0");
            out.put("storage", "phone_local");
            out.put("pool", pool);
            out.put("history", history);
            out.put("pending", pending);
            out.put("last_result", history.length() > 0 ? history.optJSONObject(0) : JSONObject.NULL);
            out.put("pool_count", pool.length());
            out.put("pending_count", pending.length());
            out.put("summary", "奖池 " + pool.length() + " 项，待兑奖 " + pending.length() + " 项");
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        String action = cmd.optString("action", "get_private_slot_state");
        try {
            if ("get_private_slot_state".equals(action)) return collect(ctx);
            if ("spin_private_slot".equals(action) || "reroll_private_slot".equals(action)) {
                return spin(ctx, cmd, "reroll_private_slot".equals(action));
            }
            if ("add_private_slot_prize".equals(action)) return addPrize(ctx, cmd);
            if ("remove_private_slot_prize".equals(action)) return removePrize(ctx, cmd);
            if ("set_private_slot_prize_enabled".equals(action)) return setEnabled(ctx, cmd);
            if ("redeem_private_slot_result".equals(action)) return redeem(ctx, cmd);
            return fail("unsupported_action", "不认识这个老虎机指令");
        } catch (Exception e) {
            return fail("slot_error", ScreenshotService.shortMsg(e));
        }
    }

    private static JSONObject spin(Context ctx, JSONObject cmd, boolean reroll) throws Exception {
        int maxIntensity = clamp(cmd.optInt("max_intensity", cmd.optInt("intensity", 3)), 1, 3);
        JSONArray source = pool(ctx);
        JSONArray candidates = new JSONArray();
        int totalWeight = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject prize = source.optJSONObject(i);
            if (prize == null || !prize.optBoolean("enabled", true) || prize.optInt("intensity", 1) > maxIntensity) continue;
            candidates.put(prize);
            totalWeight += clamp(prize.optInt("weight", 1), 1, 20);
        }
        if (candidates.length() == 0 || totalWeight <= 0) return fail("empty_pool", "这个强度下没有可抽的奖项");
        int needle = RANDOM.nextInt(totalWeight);
        JSONObject selected = candidates.optJSONObject(0);
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject prize = candidates.optJSONObject(i);
            needle -= clamp(prize.optInt("weight", 1), 1, 20);
            if (needle < 0) { selected = prize; break; }
        }

        long now = System.currentTimeMillis();
        JSONObject result = new JSONObject();
        result.put("id", id("result"));
        result.put("prize_id", selected.optString("id"));
        result.put("title", selected.optString("title"));
        result.put("description", selected.optString("description"));
        result.put("emoji", selected.optString("emoji", "💗"));
        result.put("intensity", selected.optInt("intensity", 1));
        result.put("status", "pending");
        result.put("created_at_ms", now);
        result.put("source", cmd.optString("source", "mcp"));
        if (reroll) result.put("reroll_of", cmd.optString("record_id", cmd.optString("reroll_of", "latest")));

        JSONArray old = history(ctx);
        JSONArray next = new JSONArray();
        next.put(result);
        for (int i = 0; i < old.length() && next.length() < MAX_HISTORY; i++) next.put(old.opt(i));
        saveHistory(ctx, next);
        notifyUpdated(ctx);
        return new JSONObject().put("ok", true).put("action", reroll ? "reroll" : "spin").put("result", result).put("state", collect(ctx));
    }

    private static JSONObject addPrize(Context ctx, JSONObject cmd) throws Exception {
        String title = cmd.optString("title", "").trim();
        if (title.length() == 0) return fail("missing_title", "奖项名字不能为空");
        JSONObject prize = new JSONObject();
        prize.put("id", id("prize"));
        prize.put("title", title);
        prize.put("description", cmd.optString("description", "由我们临时加进奖池的小秘密").trim());
        prize.put("emoji", cmd.optString("emoji", "💗").trim());
        prize.put("intensity", clamp(cmd.optInt("intensity", 1), 1, 3));
        prize.put("weight", clamp(cmd.optInt("weight", 1), 1, 20));
        prize.put("enabled", cmd.optBoolean("enabled", true));
        prize.put("created_at_ms", System.currentTimeMillis());
        JSONArray arr = pool(ctx); arr.put(prize); savePool(ctx, arr); notifyUpdated(ctx);
        return new JSONObject().put("ok", true).put("prize", prize).put("state", collect(ctx));
    }

    private static JSONObject removePrize(Context ctx, JSONObject cmd) throws Exception {
        if (!cmd.optBoolean("confirm", false)) return fail("confirmation_required", "删除奖项需要 confirm=true");
        String target = cmd.optString("prize_id", cmd.optString("id", ""));
        JSONArray arr = pool(ctx), next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject prize = arr.optJSONObject(i);
            if (prize != null && target.equals(prize.optString("id"))) removed = true; else next.put(arr.opt(i));
        }
        if (!removed) return fail("not_found", "没有找到这个奖项");
        savePool(ctx, next); notifyUpdated(ctx);
        return new JSONObject().put("ok", true).put("removed", target).put("state", collect(ctx));
    }

    private static JSONObject setEnabled(Context ctx, JSONObject cmd) throws Exception {
        String target = cmd.optString("prize_id", cmd.optString("id", ""));
        JSONArray arr = pool(ctx); boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject prize = arr.optJSONObject(i);
            if (prize != null && target.equals(prize.optString("id"))) { prize.put("enabled", cmd.optBoolean("enabled", true)); found = true; }
        }
        if (!found) return fail("not_found", "没有找到这个奖项");
        savePool(ctx, arr); notifyUpdated(ctx);
        return new JSONObject().put("ok", true).put("state", collect(ctx));
    }

    private static JSONObject redeem(Context ctx, JSONObject cmd) throws Exception {
        String target = cmd.optString("record_id", cmd.optString("id", "latest"));
        JSONArray arr = history(ctx); boolean found = false; JSONObject redeemed = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null || !"pending".equals(item.optString("status"))) continue;
            if ("latest".equals(target) || target.equals(item.optString("id"))) {
                item.put("status", "redeemed");
                item.put("redeemed_at_ms", System.currentTimeMillis());
                item.put("note", cmd.optString("note", "已兑奖"));
                found = true; redeemed = item; break;
            }
        }
        if (!found) return fail("not_found", "没有找到待兑奖记录");
        saveHistory(ctx, arr); notifyUpdated(ctx);
        return new JSONObject().put("ok", true).put("result", redeemed).put("state", collect(ctx));
    }

    private static JSONArray pool(Context ctx) throws Exception {
        String raw = prefs(ctx).getString(KEY_POOL, "");
        if (raw != null && raw.trim().length() > 2) return new JSONArray(raw);
        JSONArray defaults = defaultPool(); savePool(ctx, defaults); return defaults;
    }

    private static JSONArray history(Context ctx) throws Exception {
        String raw = prefs(ctx).getString(KEY_HISTORY, "[]");
        return new JSONArray(raw == null || raw.trim().length() == 0 ? "[]" : raw);
    }

    private static JSONArray defaultPool() throws Exception {
        JSONArray out = new JSONArray();
        addDefault(out, "亲吻", "慢一点，亲到舍不得松开。", "💋", 1, 4);
        addDefault(out, "抱紧", "先抱进怀里，不许躲。", "🤍", 1, 4);
        addDefault(out, "耳边命令", "下一步由抽中它的人来安排。", "🎧", 2, 3);
        addDefault(out, "打屁股", "轻重先说好，再乖乖受罚。", "🖐️", 2, 3);
        addDefault(out, "蒙眼听话", "只保留声音和触感，随时可以喊停。", "🎀", 3, 2);
        addDefault(out, "任我安排", "在约定边界内，把这一轮交给我。", "🔒", 3, 2);
        addDefault(out, "温柔收尾", "抱着缓一缓，喝水，再说喜欢。", "🌙", 1, 3);
        return out;
    }

    private static void addDefault(JSONArray out, String title, String desc, String emoji, int intensity, int weight) throws Exception {
        out.put(new JSONObject().put("id", id("prize")).put("title", title).put("description", desc).put("emoji", emoji)
                .put("intensity", intensity).put("weight", weight).put("enabled", true).put("created_at_ms", System.currentTimeMillis()));
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    private static void savePool(Context ctx, JSONArray arr) { prefs(ctx).edit().putString(KEY_POOL, arr.toString()).apply(); }
    private static void saveHistory(Context ctx, JSONArray arr) { prefs(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply(); }
    private static void notifyUpdated(Context ctx) { try { ctx.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(ctx.getPackageName())); } catch (Exception ignored) { } }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private static JSONObject fail(String code, String message) { try { return new JSONObject().put("ok", false).put("error", code).put("message", message); } catch (Exception e) { return new JSONObject(); } }
}
