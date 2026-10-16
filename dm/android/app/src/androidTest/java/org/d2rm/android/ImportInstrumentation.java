package org.d2rm.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ImportInstrumentation extends Instrumentation {
    private boolean bridgeSuite;
    private boolean graphicsSuite;
    private int passed;
    private static final byte[] DATA = new byte[]{1,2,3,4};
    private static final String CONTRACT = "{\"schemaVersion\":1,\"kind\":\"synthetic-fixture\",\"contractId\":\"device-test-v1\",\"productVersion\":\"test\",\"nsoSha256\":{},\"archives\":[{\"name\":\"test.data\",\"size\":4}]}";
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        bridgeSuite = arguments != null && "arm64-bridge".equals(arguments.getString("suite"));
        graphicsSuite = arguments != null && "vulkan-probe".equals(arguments.getString("suite"));
        start();
    }
    @Override public void onStart() {
        if (bridgeSuite) { Arm64BridgeInstrumentation.run(this); return; }
        if (graphicsSuite) { GraphicsProbeInstrumentation.run(this); return; }
        Bundle result = new Bundle();
        try {
            ResourceContract contract = new ResourceContract(bytes(CONTRACT), false);
            File root = new File(getTargetContext().getNoBackupFilesDir(), "instrumentation-" + UUID.randomUUID());
            ResourceStore store = new ResourceStore(root, contract, ImportApplication.durableFiles());
            byte[] manifest = bytes("{\"schemaVersion\":1,\"kind\":\"synthetic-fixture\",\"contractId\":\"device-test-v1\",\"productVersion\":\"test\",\"exefs\":[],\"archives\":[{\"name\":\"test.data\",\"size\":4,\"sha256\":\"" + ResourceStore.digest(DATA) + "\"}]}");
            var source = source(DATA, manifest);
            var first = store.importResources(source, new ResourceStore.Operation(), (a,b,c) -> { });
            check(Arrays.equals(DATA, Files.readAllBytes(new File(root, "sets/" + first.generation() + "/test.data").toPath())), "Android durable import stores exact bytes");
            var recovered = new ResourceStore(root, contract, ImportApplication.durableFiles()).recover();
            check(first.generation().equals(recovered.generation()) && recovered.bytes() == 4, "Cold recovery validates pointer and lengths");
            reject(() -> store.importResources(source(new byte[]{4,3,2,1}, manifest), new ResourceStore.Operation(), (a,b,c) -> { }), "SHA-256", "Hash mismatch rejected");
            check(first.generation().equals(store.recover().generation()), "Failed replacement retains old set");
            reject(() -> store.importResources(source(new byte[]{1,2,3,4,5}, manifest), new ResourceStore.Operation(), (a,b,c) -> { }), "大于", "Excess bytes rejected");
            ResourceStore.Operation cancelled = new ResourceStore.Operation();
            byte[] oldPointer = Files.readAllBytes(new File(root, "active.json").toPath());
            CountDownLatch allowClose = new CountDownLatch(1);
            Thread closer = new Thread(() -> {
                try { allowClose.await(); cancelled.closeCancelledStream(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            try {
                reject(() -> store.importResources(source, cancelled, (a,b,c) -> {
                    check(cancelled.requestCancel(), "Cancellation request accepted before asynchronous close");
                    try { check(Arrays.equals(oldPointer, Files.readAllBytes(new File(root, "active.json").toPath())), "Accepted cancellation immediately retains exact old pointer"); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                    closer.start();
                }), "取消", "Accepted cancellation prevents publication while close is delayed");
                check(Arrays.equals(oldPointer, Files.readAllBytes(new File(root, "active.json").toPath())), "Cancelled import retains exact old pointer before retry");
            } finally { allowClose.countDown(); closer.join(TimeUnit.SECONDS.toMillis(5)); if (closer.isAlive()) throw new AssertionError("Cancellation closer did not finish"); }
            var next = store.importResources(source, new ResourceStore.Operation(), (a,b,c) -> { cancelled.requestCancel(); cancelled.closeCancelledStream(); });
            check(next.generation().equals(store.recover().generation()) && !next.generation().equals(first.generation()), "Retry safely publishes unique generation; stale cancel harmless");
            reject(() -> new ResourceContract(bytes(CONTRACT), true), "正式应用拒绝", "Production gate rejects fixture contract");
            reject(() -> StrictJson.parse(bytes("{\"a\":1,\"a\":2}")), "JSON", "Duplicate JSON keys rejected");
            ResourceStore.DurableFiles actual = ImportApplication.durableFiles();
            ResourceStore.DurableFiles crash = new ResourceStore.DurableFiles() {
                public void rename(File from, File to) throws IOException { actual.rename(from, to); }
                public void syncDirectory(File directory) throws IOException { if (directory.getName().equals("sets")) throw new IOException("checkpoint"); actual.syncDirectory(directory); }
            };
            ResourceStore broken = new ResourceStore(root, contract, crash);
            reject(() -> broken.importResources(source, new ResourceStore.Operation(), (a,b,c) -> { }), "checkpoint", "Crash after completed-set rename is recoverable");
            var cold = new ResourceStore(root, contract, actual).recover();
            check(cold.interrupted() && next.generation().equals(cold.generation()), "Cold recovery marks interrupted work and retains active generation");
            result.putString("stream", "\nPASS " + passed + " Android importer checks\n"); result.putInt("passed", passed);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) { result.putString("stream", "\nFAIL after " + passed + " checks\n" + android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, result); }
    }
    interface Action { void run() throws Exception; }
    private void reject(Action action, String expected, String label) throws Exception {
        try { action.run(); throw new AssertionError(label + " accepted invalid input"); }
        catch (IOException error) { check(error.getMessage().contains(expected), label); }
    }
    private void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); passed++; Bundle status = new Bundle(); status.putString("stream", "PASS " + label + "\n"); sendStatus(0, status); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static ResourceStore.Source source(byte[] data, byte[] manifest) {
        return new ResourceStore.Source() {
            public List<String> names() { return List.of("game-manifest.json", "test.data"); }
            public InputStream open(String name) { return new ByteArrayInputStream(name.equals("game-manifest.json") ? manifest : data); }
        };
    }
}
