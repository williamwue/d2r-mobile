package org.d2rm.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

final class GraphicsProbeInstrumentation {
    private final Instrumentation instrumentation;
    private final JSONArray checks = new JSONArray();
    private GraphicsProbeInstrumentation(Instrumentation value) { instrumentation = value; }
    static void run(Instrumentation value) { new GraphicsProbeInstrumentation(value).run(); }
    private void check(boolean condition,String label) {
        if (!condition) throw new AssertionError(label);
        checks.put(label);
    }
    private JSONObject awaitFrame(GraphicsProbeActivity activity,long previous) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            JSONObject value = new JSONObject(activity.probeReport());
            if (value.optString("status").equals("failed")) throw new AssertionError(value.toString());
            if (value.optString("status").equals("present_submitted") && value.optLong("session") > previous && value.optLong("queuedPresents") >= 3) return value;
            Thread.sleep(50);
        }
        throw new AssertionError("No new rendered session: " + activity.probeReport());
    }
    private void awaitReleased() throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 10000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            JSONObject stats = new JSONObject(GraphicsProbe.stats());
            if (stats.getLong("created") == stats.getLong("destroyed")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Graphics session leaked: " + GraphicsProbe.stats());
    }
    private void pixels(GraphicsProbeActivity activity,String filename) throws Exception {
        AtomicInteger width = new AtomicInteger(), height = new AtomicInteger();
        instrumentation.runOnMainSync(() -> { width.set(activity.probeSurface().getWidth()); height.set(activity.probeSurface().getHeight()); });
        check(width.get() > 3 && height.get() > 3,"Surface has drawable extent: " + filename);
        Bitmap bitmap = Bitmap.createBitmap(width.get(),height.get(),Bitmap.Config.ARGB_8888);
        try {
            CountDownLatch copied = new CountDownLatch(1);
            AtomicInteger status = new AtomicInteger(-1);
            instrumentation.runOnMainSync(() -> PixelCopy.request(activity.probeSurface(),bitmap,result -> { status.set(result); copied.countDown(); },new Handler(Looper.getMainLooper())));
            check(copied.await(5,TimeUnit.SECONDS) && status.get() == PixelCopy.SUCCESS,"PixelCopy sees a submitted Surface frame: " + filename);
            File output = new File(instrumentation.getTargetContext().getNoBackupFilesDir(),filename);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream),"PNG evidence written: " + filename);
            }
            for (int i = 0; i < 3; ++i) {
                int pixel = bitmap.getPixel(width.get() * (2*i+1)/6,height.get()/2);
                int[] channels = {Color.red(pixel),Color.green(pixel),Color.blue(pixel)};
                check(channels[i] > 150 && channels[i] > channels[(i+1)%3]*2 && channels[i] > channels[(i+2)%3]*2,
                    "GPU column " + i + " has the expected dominant channel: " + filename + " rgb=" + java.util.Arrays.toString(channels));
            }
        } finally { bitmap.recycle(); }
    }
    private void run() {
        Bundle result = new Bundle();
        GraphicsProbeActivity activity = null;
        try {
            awaitReleased();
            try { GraphicsProbe.create(null); throw new AssertionError("Null Surface accepted"); }
            catch (IllegalStateException expected) { check(true,"Null Surface rejected before native setup"); }
            awaitReleased();
            Intent intent = new Intent(instrumentation.getTargetContext(),GraphicsProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (GraphicsProbeActivity)instrumentation.startActivitySync(intent);
            JSONObject first = awaitFrame(activity,0);
            check(first.getString("gameRuntime").equals("unavailable"),"Probe does not claim a game runtime");
            pixels(activity,"vulkan-probe-initial.png");
            long last = first.getLong("session");
            for (int i = 0; i < 3; ++i) {
                GraphicsProbeActivity current = activity;
                instrumentation.runOnMainSync(() -> check(current.moveTaskToBack(true),"Task moved to background"));
                awaitReleased();
                check(true,"Background releases all native sessions, cycle " + i);
                instrumentation.getTargetContext().startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                JSONObject resumed = awaitFrame(activity,last);
                last = resumed.getLong("session");
                check(true,"Foreground creates a new rendered session, cycle " + i);
                pixels(activity,"vulkan-probe-resume-" + i + ".png");
            }
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(GraphicsProbeActivity.class.getName(),null,false);
            GraphicsProbeActivity old = activity;
            instrumentation.runOnMainSync(old::recreate);
            Activity replacement = instrumentation.waitForMonitorWithTimeout(monitor,10000);
            instrumentation.removeMonitor(monitor);
            check(replacement instanceof GraphicsProbeActivity,"Activity recreation produces a new activity");
            activity = (GraphicsProbeActivity)replacement;
            JSONObject recreated = awaitFrame(activity,last);
            pixels(activity,"vulkan-probe-recreated.png");
            monitor = instrumentation.addMonitor(GraphicsProbeActivity.class.getName(),null,false);
            GraphicsProbeActivity portrait = activity;
            instrumentation.runOnMainSync(() -> portrait.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            replacement = instrumentation.waitForMonitorWithTimeout(monitor,10000);
            instrumentation.removeMonitor(monitor);
            check(replacement instanceof GraphicsProbeActivity,"Orientation change recreates the activity and Surface");
            activity = (GraphicsProbeActivity)replacement;
            JSONObject rotated = awaitFrame(activity,recreated.getLong("session"));
            check(rotated.getInt("width") != recreated.getInt("width") || rotated.getInt("height") != recreated.getInt("height"),"Rotated swapchain uses the new extent");
            pixels(activity,"vulkan-probe-landscape.png");
            result.putString("deviceReport",rotated.toString());
            GraphicsProbeActivity current = activity;
            instrumentation.runOnMainSync(current::finish);
            awaitReleased(); activity = null;
            check(true,"Finishing activity releases all native sessions");
            JSONObject report = new JSONObject();
            report.put("status","passed"); report.put("checks",checks); report.put("device",rotated);
            report.put("sessions",new JSONObject(GraphicsProbe.stats()));
            result.putString("stream","\nVULKAN_PROBE_REPORT=" + report + "\n");
            instrumentation.finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            String lastReport = activity == null ? "none" : activity.probeReport();
            if (activity != null) { GraphicsProbeActivity current = activity; instrumentation.runOnMainSync(current::finish); }
            result.putString("stream","\nFAIL Vulkan probe after " + checks.length() + " checks\n" + lastReport + "\n" + android.util.Log.getStackTraceString(failure));
            instrumentation.finish(Activity.RESULT_CANCELED,result);
        }
    }
}
