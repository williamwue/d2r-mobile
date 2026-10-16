package org.d2rm.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class GraphicsProbeActivity extends Activity implements SurfaceHolder.Callback {
    private final HandlerThread thread = new HandlerThread("vulkan-probe");
    private final Handler ui = new Handler(android.os.Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private Handler worker;
    private SurfaceView canvas;
    private TextView status;
    private boolean resumed, released;
    private long session;
    private volatile String report = "{\"status\":\"idle\"}";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        thread.start(); worker = new Handler(thread.getLooper());
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(13,25,23));
        page.setOnApplyWindowInsetsListener((view,insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        TextView title = new TextView(this); title.setText(R.string.vulkan_probe_title);
        title.setTextSize(21); title.setTextColor(Color.WHITE); title.setPadding(24,24,24,12);
        title.setAccessibilityHeading(true); page.addView(title);
        TextView scope = new TextView(this); scope.setText(R.string.vulkan_probe_scope);
        scope.setTextColor(Color.LTGRAY); scope.setPadding(24,0,24,16); page.addView(scope);
        canvas = new SurfaceView(this); canvas.setContentDescription(getString(R.string.vulkan_probe_surface));
        canvas.getHolder().addCallback(this); page.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14);
        status.setPadding(24,16,24,16); status.setText(R.string.vulkan_probe_waiting); page.addView(status);
        Button back = new Button(this); back.setText(R.string.vulkan_probe_back); back.setAllCaps(false);
        back.setOnClickListener(v -> finish()); page.addView(back);
        setContentView(page);
    }
    private void updateSurface() {
        if (released) return;
        int token = generation.incrementAndGet();
        Surface target = resumed && canvas.getHolder().getSurface().isValid() ? canvas.getHolder().getSurface() : null;
        worker.post(() -> {
            if (token != generation.get()) return;
            closeSession();
            if (target == null || !target.isValid()) { publish("{\"status\":\"paused\"}",token); return; }
            try { session = GraphicsProbe.create(target); tick(token); }
            catch (Throwable error) { failed(error,token); }
        });
    }
    private void tick(int token) {
        if (token != generation.get() || session == 0) return;
        try {
            String next = GraphicsProbe.draw(session);
            JSONObject result = new JSONObject(next);
            publish(next,token);
            if (result.getString("status").equals("recreate")) {
                ui.post(() -> { if (token == generation.get()) updateSurface(); });
            } else worker.postDelayed(() -> tick(token),100);
        } catch (Throwable error) { failed(error,token); }
    }
    private void publish(String next,int token) {
        if (token != generation.get()) return;
        report = next;
        ui.post(() -> {
            if (token != generation.get() || released) return;
            try {
                JSONObject value = new JSONObject(next);
                status.setText(switch (value.getString("status")) {
                    case "present_submitted" -> getString(R.string.vulkan_probe_frames,value.getLong("queuedPresents"),value.getString("device"),value.getInt("width"),value.getInt("height"));
                    case "paused" -> getString(R.string.vulkan_probe_paused);
                    case "failed" -> getString(R.string.vulkan_probe_failed,value.optString("error"));
                    default -> getString(R.string.vulkan_probe_waiting);
                });
            } catch (Exception error) { status.setText(R.string.vulkan_probe_report_error); }
        });
    }
    private void failed(Throwable error,int token) {
        closeSession();
        JSONObject result = new JSONObject();
        try { result.put("status","failed"); result.put("error",error.toString()); }
        catch (org.json.JSONException ignored) { }
        publish(result.toString(),token);
    }
    private void closeSession() { if (session != 0) { long old = session; session = 0; GraphicsProbe.close(old); } }
    private void stopAndWait() {
        if (released) return;
        int token = generation.incrementAndGet();
        CountDownLatch done = new CountDownLatch(1);
        if (!worker.post(() -> { try { closeSession(); publish("{\"status\":\"paused\"}",token); } finally { done.countDown(); } })) return;
        try { if (!done.await(4,TimeUnit.SECONDS)) status.setText(R.string.vulkan_probe_release_timeout); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; updateSurface(); }
    @Override protected void onPause() { resumed = false; updateSurface(); super.onPause(); }
    @Override protected void onDestroy() { stopAndWait(); released = true; thread.quitSafely(); super.onDestroy(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { updateSurface(); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { updateSurface(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { stopAndWait(); }
    String probeReport() { return report; }
    SurfaceView probeSurface() { return canvas; }
}
