package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 手机端私密老虎机页面。正式拉杆可以由 MCP 完成，本机也保留试转和兑奖入口。 */
public class PrivateSlotActivity extends Activity {
    private UITheme theme;
    private LinearLayout content, reels;
    private TextView resultTitle, resultDescription, status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] reelFaces = new String[]{"💋", "🤍", "🎧", "🖐️", "🎀", "🔒", "🌙"};
    private final BroadcastReceiver updated = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(false); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        theme = UITheme.current(this);
        build();
        render(false);
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            IntentFilter filter = new IntentFilter(PrivateSlotState.ACTION_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(updated, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(updated, filter);
        } catch (Exception ignored) { }
        render(false);
    }

    @Override protected void onPause() {
        try { unregisterReceiver(updated); } catch (Exception ignored) { }
        super.onPause();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setBackground(theme.background());
        setContentView(root);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", false); back.setTextSize(24); back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text("涩涩老虎机", 22, true); LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(48), 1); titleLp.leftMargin = dp(8); header.addView(title, titleLp);
        Button add = button("＋", false); add.setOnClickListener(v -> showAddPrize()); header.addView(add, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(8), 0, dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void render(boolean animate) {
        if (content == null) return;
        JSONObject state = PrivateSlotState.collect(this);
        content.removeAllViews();

        LinearLayout hero = card(); hero.setBackground(theme.hero()); hero.setGravity(Gravity.CENTER_HORIZONTAL);
        status = text("云端拉杆 · 奖池只存在这台手机", 11, false); status.setTextColor(theme.subtext); status.setGravity(Gravity.CENTER); hero.addView(status, matchWrap(0));
        reels = row(); reels.setGravity(Gravity.CENTER); reels.setPadding(0, dp(16), 0, dp(12));
        for (int i = 0; i < 3; i++) { TextView reel = text("💗", 38, false); reel.setGravity(Gravity.CENTER); reel.setBackground(round(theme.card, dp(18), theme.line, 1)); reels.addView(reel, new LinearLayout.LayoutParams(dp(78), dp(78))); if (i < 2) ((LinearLayout.LayoutParams) reel.getLayoutParams()).rightMargin = dp(8); }
        hero.addView(reels);
        resultTitle = text("等一次拉杆", 21, true); resultTitle.setGravity(Gravity.CENTER); hero.addView(resultTitle, matchWrap(2));
        resultDescription = text("让 TA 从 MCP 拉动，结果会落到这里；也可以先在本机试转。", 12, false); resultDescription.setTextColor(theme.subtext); resultDescription.setGravity(Gravity.CENTER); resultDescription.setLineSpacing(dp(3), 1f); hero.addView(resultDescription, matchWrap(6));
        Button spin = button("试转一次", true); spin.setOnClickListener(v -> localSpin()); LinearLayout.LayoutParams spinLp = matchWrap(14); spinLp.height = dp(48); hero.addView(spin, spinLp);
        content.addView(hero, matchWrap(0));

        JSONObject last = state.optJSONObject("last_result");
        if (last != null) showResult(last, animate);

        sectionTitle("待兑奖", state.optInt("pending_count", 0) + " 个");
        JSONArray pending = state.optJSONArray("pending");
        if (pending == null || pending.length() == 0) content.addView(empty("现在没有欠着的兑奖。"), matchWrap(0));
        else for (int i = 0; i < pending.length(); i++) content.addView(resultCard(pending.optJSONObject(i)), matchWrap(7));

        sectionTitle("我们的奖池", state.optInt("pool_count", 0) + " 项 · 右上角可添加");
        JSONArray pool = state.optJSONArray("pool");
        if (pool != null) for (int i = 0; i < pool.length(); i++) content.addView(prizeCard(pool.optJSONObject(i)), matchWrap(7));
    }

    private void localSpin() {
        try {
            JSONObject response = PrivateSlotState.handleCommand(this, new JSONObject().put("action", "spin_private_slot").put("source", "phone_test").put("max_intensity", 3));
            JSONObject result = response.optJSONObject("result");
            if (!response.optBoolean("ok") || result == null) { toast(response.optString("message", "没有转起来")); return; }
            animateTo(result);
        } catch (Exception e) { toast("试转失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void animateTo(JSONObject result) {
        status.setText("转动中……");
        for (int tick = 0; tick < 12; tick++) {
            final int step = tick;
            handler.postDelayed(() -> {
                for (int i = 0; i < reels.getChildCount(); i++) ((TextView) reels.getChildAt(i)).setText(reelFaces[(step + i * 2) % reelFaces.length]);
                if (step == 11) { showResult(result, true); render(false); }
            }, tick * 65L);
        }
    }

    private void showResult(JSONObject result, boolean pop) {
        if (resultTitle == null || reels == null) return;
        String emoji = result.optString("emoji", "💗");
        for (int i = 0; i < reels.getChildCount(); i++) ((TextView) reels.getChildAt(i)).setText(emoji);
        resultTitle.setText(result.optString("title", "抽中一项"));
        resultDescription.setText(result.optString("description", "").trim());
        status.setText("redeemed".equals(result.optString("status")) ? "已经兑奖" : "等着兑现 · 强度 " + stars(result.optInt("intensity", 1)));
        if (pop) { resultTitle.setScaleX(.8f); resultTitle.setScaleY(.8f); resultTitle.animate().scaleX(1f).scaleY(1f).setDuration(240).start(); }
    }

    private View resultCard(JSONObject item) {
        LinearLayout box = card(); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(item == null ? "💗" : item.optString("emoji", "💗"), 28, false); icon.setGravity(Gravity.CENTER); box.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.addView(text(item == null ? "奖项" : item.optString("title", "奖项"), 15, true)); TextView desc = text(item == null ? "" : item.optString("description", ""), 11, false); desc.setTextColor(theme.subtext); copy.addView(desc, matchWrap(3)); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = dp(8); box.addView(copy, cp);
        Button redeem = button("兑现", false); String id = item == null ? "latest" : item.optString("id", "latest"); redeem.setOnClickListener(v -> { try { PrivateSlotState.handleCommand(this, new JSONObject().put("action", "redeem_private_slot_result").put("record_id", id).put("note", "在手机上确认已兑现")); render(false); } catch (Exception e) { toast("没记上，再试一次"); } }); box.addView(redeem, new LinearLayout.LayoutParams(dp(70), dp(42)));
        return box;
    }

    private View prizeCard(JSONObject prize) {
        LinearLayout box = card(); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(prize == null ? "💗" : prize.optString("emoji", "💗"), 26, false); icon.setGravity(Gravity.CENTER); box.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.addView(text(prize == null ? "奖项" : prize.optString("title", "奖项"), 14, true)); TextView meta = text(prize == null ? "" : stars(prize.optInt("intensity", 1)) + "  ·  权重 " + prize.optInt("weight", 1) + (prize.optBoolean("enabled", true) ? "" : "  ·  已停用"), 10, false); meta.setTextColor(theme.subtext); copy.addView(meta, matchWrap(2)); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = dp(8); box.addView(copy, cp);
        return box;
    }

    private void showAddPrize() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(8), 0, dp(8), 0);
        EditText title = input("奖项名字"); form.addView(title); EditText desc = input("一句描述"); form.addView(desc); EditText emoji = input("图标，例如 💗"); form.addView(emoji); EditText intensity = input("强度 1-3"); form.addView(intensity); EditText weight = input("权重 1-20，越大越常抽中"); form.addView(weight);
        new AlertDialog.Builder(this).setTitle("加进我们的奖池").setView(form).setNegativeButton("取消", null).setPositiveButton("添加", (d, w) -> {
            try {
                JSONObject cmd = new JSONObject().put("action", "add_private_slot_prize").put("title", title.getText().toString()).put("description", desc.getText().toString()).put("emoji", emoji.getText().toString().trim().length() == 0 ? "💗" : emoji.getText().toString().trim()).put("intensity", parse(intensity.getText().toString(), 1)).put("weight", parse(weight.getText().toString(), 1));
                JSONObject response = PrivateSlotState.handleCommand(this, cmd); if (!response.optBoolean("ok")) toast(response.optString("message", "没有添加成功")); render(false);
            } catch (Exception e) { toast("没有添加成功"); }
        }).show();
    }

    private void sectionTitle(String name, String detail) { LinearLayout r = row(); r.setGravity(Gravity.BOTTOM); TextView h = text(name, 17, true); r.addView(h, new LinearLayout.LayoutParams(0, dp(44), 1)); TextView d = text(detail, 10, false); d.setTextColor(theme.subtext); d.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT); r.addView(d, new LinearLayout.LayoutParams(-2, dp(44))); content.addView(r, matchWrap(14)); }
    private View empty(String message) { LinearLayout box = card(); TextView t = text(message, 12, false); t.setTextColor(theme.subtext); t.setGravity(Gravity.CENTER); box.addView(t, matchWrap(0)); return box; }
    private LinearLayout card() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(14), dp(14), dp(14)); box.setBackground(round(theme.card, dp(22), theme.line, 1)); return box; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private TextView text(String value, int sp, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextColor(theme.text); t.setTextSize(sp); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button button(String value, boolean primary) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(primary ? Color.WHITE : theme.text); b.setBackground(round(primary ? theme.primary : theme.card, dp(18), primary ? theme.primary : theme.line, 1)); return b; }
    private EditText input(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setTextColor(theme.text); e.setHintTextColor(theme.subtext); e.setSingleLine(true); return e; }
    private GradientDrawable round(int color, float radius, int stroke, int width) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); if (width > 0) g.setStroke(dp(width), stroke); return g; }
    private LinearLayout.LayoutParams matchWrap(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private int dp(float v) { return (int) UITheme.dp(v); }
    private int parse(String value, int fallback) { try { return Integer.parseInt(value.trim()); } catch (Exception e) { return fallback; } }
    private String stars(int n) { return n <= 1 ? "♡" : (n == 2 ? "♡♡" : "♡♡♡"); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
