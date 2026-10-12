package org.d2rm.android;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

public final class MainActivity extends Activity implements ImportApplication.Observer {
    private ImportApplication app;
    private TextView detail, resource, progressText;
    private ProgressBar progress;
    private Button choose, cancel, retry;
    private boolean pickerOpen;
    private int ink, muted, accent, card;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (ImportApplication)getApplication();
        pickerOpen = saved != null && saved.getBoolean("pickerOpen");
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ink = Color.parseColor(dark ? "#E6EFED" : "#142A26"); muted = Color.parseColor(dark ? "#B3C5C0" : "#4B645E");
        accent = Color.parseColor(dark ? "#77D8C4" : "#006B5E"); card = Color.parseColor(dark ? "#1A2B28" : "#FFFFFF");
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.parseColor(dark ? "#0D1917" : "#EDF4F1"));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24), dp(24), dp(24), dp(28)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        TextView badge = text(getPackageName().endsWith(".fixture") ? "TEST · 合成资源验证" : "ANDROID · 首阶段", 12, accent); page.addView(badge);
        TextView title = text("D2R Mobile", 32, ink); title.setTypeface(null, Typeface.BOLD); title.setAccessibilityHeading(true); page.addView(title);
        TextView subtitle = text("资源准备与设备检查", 17, muted); subtitle.setPadding(0, dp(8), 0, dp(24)); page.addView(subtitle);
        LinearLayout runtime = section(page, "运行状态"); runtime.addView(text("游戏运行时尚不可用", 21, ink));
        runtime.addView(text("本阶段仅检查设备能力并导入资源，不支持启动游戏。", 15, muted));
        runtime.addView(text("目标版本  " + app.productVersion(), 14, muted));
        LinearLayout resources = section(page, "本地资源"); resource = text("尚未导入", 20, ink); resources.addView(resource);
        detail = text("", 15, muted); detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); resources.addView(detail);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); resources.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
        progressText = text("", 14, muted); resources.addView(progressText);
        choose = button("选择资源文件夹", resources); choose.setOnClickListener(v -> openPicker());
        cancel = button("取消导入", resources); cancel.setOnClickListener(v -> app.cancel());
        retry = button("从头重试", resources); retry.setOnClickListener(v -> app.retry());
        resources.addView(text("选择直接包含 game-manifest.json 与资源文件的文件夹。清单仅声明版本及校验摘要，不证明来源真实性。", 14, muted));
        resources.addView(text("资源保存在应用私有空间。替换导入会保留旧资源和未完成数据，本阶段不自动清理。导入需额外可用空间；离开应用会请求取消；开始提交后无法取消。", 14, muted));
        LinearLayout device = section(page, "设备报告");
        String probe;
        try { probe = deviceReport(NativeProbe.read()); } catch (Throwable failure) { probe = "原生探测不可用：" + failure.getClass().getSimpleName(); }
        TextView report = text("Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT + "\n" + Build.MANUFACTURER + " " + Build.MODEL + "\n" + probe, 14, muted);
        report.setTextIsSelectable(true); device.addView(report);
        setContentView(scroll);
    }
    private LinearLayout section(LinearLayout page, String heading) {
        LinearLayout block = new LinearLayout(this); block.setOrientation(LinearLayout.VERTICAL); block.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(card); shape.setCornerRadius(dp(20)); block.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(18); page.addView(block, params);
        TextView label = text(heading, 13, accent); label.setTypeface(null, Typeface.BOLD); label.setAccessibilityHeading(true); label.setPadding(0, 0, 0, dp(10)); block.addView(label); return block;
    }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(4), 1); text.setPadding(0, dp(4), 0, dp(7)); return text; }
    private Button button(String label, LinearLayout parent) { Button result = new Button(this); result.setText(label); result.setAllCaps(false); result.setMinHeight(dp(52)); parent.addView(result, new LinearLayout.LayoutParams(-1, -2)); return result; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void openPicker() {
        pickerOpen = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, 100); }
        catch (RuntimeException failure) { pickerOpen = false; detail.setText("系统文件夹选择器不可用：" + failure.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 100) return;
        pickerOpen = false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new SecurityException("No read permission granted");
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            app.start(uri);
        } catch (SecurityException failure) { detail.setText("无法取得文件夹权限，请重新选择。"); }
    }
    @Override protected void onStart() { super.onStart(); app.observe(this); }
    @Override protected void onStop() { app.detach(this); if (!isChangingConfigurations() && !pickerOpen) app.cancel(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("pickerOpen", pickerOpen); super.onSaveInstanceState(out); }
    @Override public void changed(ImportApplication.State state) {
        ResourceStore.Recovery current = state.recovery();
        resource.setText(current != null && current.hasResources() ? "资源已保存 · " + size(current.bytes()) : "尚无可用资源");
        String receipt = current != null && current.hasResources() ? "\n启动检查已核对文件长度及导入收据，未重新计算全部资源摘要。" : "";
        detail.setText(state.detail() + receipt);
        progress.setVisibility(state.busy() ? View.VISIBLE : View.GONE); progress.setIndeterminate(state.total() == 0);
        if (state.total() > 0) progress.setProgress((int)(state.copied() * 1000 / state.total()));
        progressText.setText(state.total() > 0 ? size(state.copied()) + " / " + size(state.total()) : "");
        choose.setEnabled(!state.busy()); cancel.setVisibility(state.busy() ? View.VISIBLE : View.GONE); cancel.setEnabled(state.phase() == ImportApplication.Phase.IMPORTING);
        retry.setVisibility(!state.busy() && app.canRetry() ? View.VISIBLE : View.GONE);
    }
    private static String size(long bytes) { return String.format(Locale.CHINA, "%.1f MiB", bytes / 1048576.0); }
    private static String deviceReport(String raw) throws org.json.JSONException {
        org.json.JSONObject report = new org.json.JSONObject(raw);
        StringBuilder text = new StringBuilder("ABI  ").append(report.optString("abi", "未知"))
                .append("\n页面大小  ").append(report.optLong("pageSize", 0)).append(" 字节")
                .append("\nJNI 探测  ").append(report.optInt("jniSentinel") == 4242 ? "通过" : "异常");
        org.json.JSONObject vulkan = report.optJSONObject("vulkan");
        if (vulkan == null) return text.append("\nVulkan  报告不可用").toString();
        String status = vulkan.optString("status");
        text.append("\nVulkan  ").append(switch (status) { case "available" -> "已检测到设备"; case "no_devices" -> "没有可用设备"; case "unavailable" -> "不可用"; default -> "探测失败"; });
        org.json.JSONArray devices = vulkan.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            org.json.JSONObject device = devices.getJSONObject(i); long api = device.optLong("apiVersion");
            text.append("\n").append(device.optString("name", "未知设备")).append(" · ").append(api >> 22).append('.').append((api >> 12) & 1023).append('.').append(api & 4095)
                    .append("\nBC  ").append(device.optBoolean("textureCompressionBC") ? "支持" : "不支持")
                    .append(" · ASTC  ").append(device.optBoolean("textureCompressionASTC_LDR") ? "支持" : "不支持");
        }
        if (vulkan.has("reason")) text.append("\n").append(vulkan.optString("reason"));
        if (vulkan.has("vkResult")) text.append("\nVulkan 错误码  ").append(vulkan.optInt("vkResult"));
        return text.toString();
    }
}
