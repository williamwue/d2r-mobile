package org.d2rm.android;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class ResourceStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private ResourceContract contract;
    private ResourceStore store;
    private static final byte[] DATA = new byte[]{1, 2, 3, 4};
    private static final ResourceStore.DurableFiles FILES = new ResourceStore.DurableFiles() {
        public void syncDirectory(File directory) { }
        public void rename(File from, File to) throws IOException { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
    };
    @Before public void setup() throws Exception {
        root = temporary.newFolder();
        contract = new ResourceContract(bytes("{\"schemaVersion\":1,\"kind\":\"synthetic-fixture\",\"contractId\":\"test-v1\",\"productVersion\":\"test\",\"nsoSha256\":{},\"archives\":[{\"name\":\"test.data\",\"size\":4}]}"), false);
        store = new ResourceStore(root, contract, FILES);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] manifest(String hash) {
        return bytes("{\"schemaVersion\":1,\"kind\":\"synthetic-fixture\",\"contractId\":\"test-v1\",\"productVersion\":\"test\",\"exefs\":[],\"archives\":[{\"name\":\"test.data\",\"size\":4,\"sha256\":\"" + hash + "\"}]}");
    }
    private static ResourceStore.Source source(byte[] data, byte[] manifest) {
        return new ResourceStore.Source() {
            public List<String> names() { return List.of("game-manifest.json", "test.data"); }
            public InputStream open(String name) { return new ByteArrayInputStream(name.equals("game-manifest.json") ? manifest : data); }
        };
    }
    private ResourceStore.Recovery valid() throws IOException { return store.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { }); }
    private static void fails(String message, Throwing action) throws Exception {
        try { action.run(); fail("Expected rejection " + message); } catch (IOException e) { assertTrue(e.getMessage(), e.getMessage().contains(message)); }
    }
    interface Throwing { void run() throws Exception; }
    @Test public void validImportPersistsExactBytesAndRecoversReceipt() throws Exception {
        ResourceStore.Recovery result = valid();
        assertArrayEquals(DATA, Files.readAllBytes(new File(root, "sets/" + result.generation() + "/test.data").toPath()));
        ResourceStore.Recovery reopened = new ResourceStore(root, contract, FILES).recover();
        assertEquals(result.generation(), reopened.generation()); assertEquals(4, reopened.bytes()); assertFalse(reopened.interrupted());
        assertEquals(ResourceStore.digest(manifest(ResourceStore.digest(DATA))), reopened.manifestDigest());
    }
    @Test public void replacementUsesUniqueGenerationAndPreservesOldSet() throws Exception {
        var first = valid(); var second = valid(); assertNotEquals(first.generation(), second.generation());
        assertArrayEquals(DATA, Files.readAllBytes(new File(root, "sets/" + first.generation() + "/test.data").toPath())); assertEquals(second.generation(), store.recover().generation());
    }
    @Test public void hashMismatchLeavesPreviousActiveSet() throws Exception {
        var old = valid(); fails("SHA-256", () -> store.importResources(source(new byte[]{4,3,2,1}, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { }));
        assertEquals(old.generation(), store.recover().generation()); assertTrue(store.recover().interrupted());
    }
    @Test public void excessBytesAreRejectedBeforeExcessWrite() throws Exception {
        fails("大于", () -> store.importResources(source(new byte[]{1,2,3,4,5}, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { }));
        File stage = new File(root, "staging").listFiles()[0]; assertEquals(0, new File(stage, "test.data").length()); assertFalse(store.recover().hasResources());
    }
    @Test public void shortStreamRejected() throws Exception { fails("大小不足", () -> store.importResources(source(new byte[]{1,2,3}, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { })); assertFalse(store.recover().hasResources()); }
    @Test public void missingFileRejected() throws Exception {
        ResourceStore.Source missing = new ResourceStore.Source() { public List<String> names() { return List.of("game-manifest.json"); } public InputStream open(String name) { return new ByteArrayInputStream(manifest(ResourceStore.digest(DATA))); } };
        fails("缺少资源", () -> store.importResources(missing, new ResourceStore.Operation(), (a,b,c) -> { })); assertFalse(store.recover().hasResources());
    }
    @Test public void cancellationPreservesOldSetAndRetrySafelyRestarts() throws Exception {
        var old = valid(); var cancelled = new ResourceStore.Operation();
        fails("取消", () -> store.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), cancelled, (a,b,c) -> assertTrue(cancelled.requestCancel())));
        assertEquals(old.generation(), store.recover().generation());
        var next = store.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> {
            assertTrue(cancelled.requestCancel()); cancelled.closeCancelledStream();
        });
        assertEquals(next.generation(), store.recover().generation());
    }
    @Test public void acceptedCancellationWithDelayedCloseCannotPublish() throws Exception {
        valid();
        byte[] oldPointer = Files.readAllBytes(new File(root, "active.json").toPath());
        var operation = new ResourceStore.Operation();
        CountDownLatch allowClose = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            try { allowClose.await(); operation.closeCancelledStream(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        ResourceStore.DurableFiles cancelBeforeCommit = new ResourceStore.DurableFiles() {
            public void rename(File from, File to) throws IOException { FILES.rename(from, to); }
            public void syncDirectory(File directory) throws IOException {
                if (directory.equals(root) && new File(root, "pointer-" + operation.id + ".tmp").isFile()) {
                    assertTrue(operation.requestCancel());
                    closer.start();
                    assertArrayEquals(oldPointer, Files.readAllBytes(new File(root, "active.json").toPath()));
                }
                FILES.syncDirectory(directory);
            }
        };
        ResourceStore cancelledStore = new ResourceStore(root, contract, cancelBeforeCommit);
        try {
            fails("取消", () -> cancelledStore.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), operation, (a,b,c) -> { }));
            assertArrayEquals(oldPointer, Files.readAllBytes(new File(root, "active.json").toPath()));
        } finally {
            allowClose.countDown(); closer.join(TimeUnit.SECONDS.toMillis(5)); assertFalse(closer.isAlive());
        }
    }
    @Test public void cancellationAtCommitBoundaryReportsTooLateWithoutBlocking() throws Exception {
        valid();
        byte[] oldPointer = Files.readAllBytes(new File(root, "active.json").toPath());
        var operation = new ResourceStore.Operation();
        CountDownLatch committing = new CountDownLatch(1), allowPublication = new CountDownLatch(1);
        ResourceStore.DurableFiles delayed = new ResourceStore.DurableFiles() {
            public void syncDirectory(File directory) throws IOException { FILES.syncDirectory(directory); }
            public void rename(File from, File to) throws IOException {
                if (to.getName().equals("active.json")) {
                    committing.countDown();
                    try { if (!allowPublication.await(5, TimeUnit.SECONDS)) throw new IOException("Publication timed out"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                }
                FILES.rename(from, to);
            }
        };
        ResourceStore delayedStore = new ResourceStore(root, contract, delayed);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var imported = executor.submit(() -> delayedStore.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), operation, (a,b,c) -> { }));
        try {
            assertTrue(committing.await(5, TimeUnit.SECONDS));
            assertFalse(operation.requestCancel()); assertFalse(operation.isCancelled());
            assertArrayEquals(oldPointer, Files.readAllBytes(new File(root, "active.json").toPath()));
            allowPublication.countDown();
            assertEquals(operation.id, imported.get(5, TimeUnit.SECONDS).generation());
            assertEquals(operation.id, store.recover().generation());
        } finally { allowPublication.countDown(); executor.shutdownNow(); }
    }
    @Test public void injectedReadFailurePreservesOldSet() throws Exception {
        var old = valid(); var good = source(DATA, manifest(ResourceStore.digest(DATA)));
        ResourceStore.Source broken = new ResourceStore.Source() {
            public List<String> names() throws IOException { return good.names(); }
            public InputStream open(String name) throws IOException { if (name.equals("test.data")) throw new IOException("模拟读取失败"); return good.open(name); }
        };
        fails("模拟读取失败", () -> store.importResources(broken, new ResourceStore.Operation(), (a,b,c) -> { })); assertEquals(old.generation(), store.recover().generation());
    }
    @Test public void crashAfterCompleteRenameIsInterruptedAndKeepsOldPointer() throws Exception {
        var old = valid();
        ResourceStore.DurableFiles interrupted = new ResourceStore.DurableFiles() {
            public void rename(File from, File to) throws IOException { FILES.rename(from, to); }
            public void syncDirectory(File directory) throws IOException { if (directory.getName().equals("sets")) throw new IOException("模拟发布前崩溃"); }
        };
        ResourceStore broken = new ResourceStore(root, contract, interrupted);
        fails("模拟发布前崩溃", () -> broken.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { }));
        var recovered = new ResourceStore(root, contract, FILES).recover(); assertEquals(old.generation(), recovered.generation()); assertTrue(recovered.interrupted());
    }
    @Test public void coldRecoveryRejectsMissingOrChangedLength() throws Exception { var current = valid(); Files.write(new File(root, "sets/" + current.generation() + "/test.data").toPath(), new byte[]{1}); assertFalse(store.recover().hasResources()); assertTrue(store.recover().problem().contains("大小改变")); }
    @Test public void receiptIsNotClaimedAsCurrentFullRehash() throws Exception { var current = valid(); Files.write(new File(root, "sets/" + current.generation() + "/test.data").toPath(), new byte[]{4,3,2,1}); assertEquals(current.generation(), store.recover().generation()); }
    @Test public void productionApplicationRejectsFixtureContract() throws Exception { fails("正式应用拒绝", () -> new ResourceContract(bytes("{\"schemaVersion\":1,\"kind\":\"synthetic-fixture\",\"contractId\":\"test-v1\",\"productVersion\":\"test\",\"nsoSha256\":{},\"archives\":[{\"name\":\"test.data\",\"size\":4}]}"), true)); }
    @Test public void duplicateKeysAndUnicodeSignsRejected() throws Exception {
        fails("JSON", () -> StrictJson.parse(bytes("{\"a\":1,\"a\":2}")));
        fails("JSON", () -> StrictJson.parse(bytes("\"\\u+123\""))); fails("JSON", () -> StrictJson.parse(bytes("\"\\u-123\"")));
    }
    @Test public void strictManifestRejectsWrongTypesUnknownFieldsAndProfile() throws Exception {
        String text = new String(manifest(ResourceStore.digest(DATA)), StandardCharsets.UTF_8);
        fails("正整数", () -> contract.manifest(bytes(text.replace("\"size\":4", "\"size\":\"4\""))));
        fails("字段", () -> contract.manifest(bytes(text.replace("\"exefs\":[]", "\"exefs\":[],\"extra\":1"))));
        fails("不匹配", () -> contract.manifest(bytes(text.replace("test-v1", "unknown-v1"))));
    }
    @Test public void rawManifestBytesDetermineIdentity() throws Exception {
        byte[] compact = manifest(ResourceStore.digest(DATA));
        byte[] spaced = bytes(new String(compact, StandardCharsets.UTF_8) + "\n");
        var imported = store.importResources(source(DATA, spaced), new ResourceStore.Operation(), (a,b,c) -> { });
        assertEquals(ResourceStore.digest(spaced), imported.manifestDigest()); assertNotEquals(ResourceStore.digest(compact), imported.manifestDigest());
    }
    @Test public void ambiguousProviderNamesRejected() throws Exception {
        var good = source(DATA, manifest(ResourceStore.digest(DATA)));
        ResourceStore.Source ambiguous = new ResourceStore.Source() { public List<String> names() { return List.of("game-manifest.json", "test.data", "TEST.data"); } public InputStream open(String name) throws IOException { return good.open(name); } };
        fails("歧义", () -> store.importResources(ambiguous, new ResourceStore.Operation(), (a,b,c) -> { })); assertFalse(store.recover().hasResources());
    }
    @Test public void manifestBoundRejectsOversizedInput() throws Exception { fails("大小限制", () -> store.importResources(source(DATA, new byte[65537]), new ResourceStore.Operation(), (a,b,c) -> { })); assertFalse(store.recover().hasResources()); }
    @Test public void failedPointerDirectorySyncRestoresPreviousPointer() throws Exception {
        var old = valid();
        ResourceStore.DurableFiles failure = new ResourceStore.DurableFiles() {
            boolean published, failed;
            public void rename(File from, File to) throws IOException { FILES.rename(from, to); if (to.getName().equals("active.json")) published = true; }
            public void syncDirectory(File directory) throws IOException { if (published && !failed && directory.equals(root)) { failed = true; throw new IOException("模拟指针同步失败"); } }
        };
        ResourceStore broken = new ResourceStore(root, contract, failure);
        fails("模拟指针同步失败", () -> broken.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { }));
        var recovered = new ResourceStore(root, contract, FILES).recover(); assertEquals(old.generation(), recovered.generation()); assertTrue(recovered.interrupted());
    }
    @Test public void failedFirstPointerSyncLeavesNoActiveGeneration() throws Exception {
        ResourceStore.DurableFiles failure = new ResourceStore.DurableFiles() {
            boolean published, failed;
            public void rename(File from, File to) throws IOException { FILES.rename(from, to); if (to.getName().equals("active.json")) published = true; }
            public void syncDirectory(File directory) throws IOException { if (published && !failed && directory.equals(root)) { failed = true; throw new IOException("模拟指针同步失败"); } }
        };
        ResourceStore broken = new ResourceStore(root, contract, failure);
        fails("模拟指针同步失败", () -> broken.importResources(source(DATA, manifest(ResourceStore.digest(DATA))), new ResourceStore.Operation(), (a,b,c) -> { }));
        assertFalse(new ResourceStore(root, contract, FILES).recover().hasResources()); assertFalse(new File(root, "active.json").exists());
    }
    @Test public void duplicateProviderNamesRejected() throws Exception {
        var good = source(DATA, manifest(ResourceStore.digest(DATA)));
        ResourceStore.Source duplicate = new ResourceStore.Source() { public List<String> names() { return List.of("game-manifest.json", "test.data", "test.data"); } public InputStream open(String name) throws IOException { return good.open(name); } };
        fails("重复", () -> store.importResources(duplicate, new ResourceStore.Operation(), (a,b,c) -> { })); assertFalse(store.recover().hasResources());
    }
    @Test public void declaredSizeBeyondAvailableSpaceRejectedBeforeCopy() throws Exception {
        String hugeContract = "{\"schemaVersion\":1,\"kind\":\"synthetic-fixture\",\"contractId\":\"test-v1\",\"productVersion\":\"test\",\"nsoSha256\":{},\"archives\":[{\"name\":\"test.data\",\"size\":9223372036854775807}]}";
        ResourceStore huge = new ResourceStore(root, new ResourceContract(bytes(hugeContract), false), FILES);
        byte[] declaration = bytes(new String(manifest(ResourceStore.digest(DATA)), StandardCharsets.UTF_8).replace("\"size\":4", "\"size\":9223372036854775807"));
        fails("空间不足", () -> huge.importResources(source(DATA, declaration), new ResourceStore.Operation(), (a,b,c) -> { })); assertFalse(huge.recover().hasResources());
    }
    @Test public void productionManifestPinsExeFsHashesAndRejectsFixtureProfile() throws Exception {
        String pinned = "0".repeat(64);
        ResourceContract game = new ResourceContract(bytes("{\"schemaVersion\":1,\"kind\":\"game-resources\",\"contractId\":\"game-v1\",\"productVersion\":\"test\",\"nsoSha256\":{\"main\":\"" + pinned + "\"},\"archives\":[{\"name\":\"test.data\",\"size\":4}]}"), true);
        fails("不匹配", () -> game.manifest(manifest(ResourceStore.digest(DATA))));
        String gameManifest = new String(manifest(ResourceStore.digest(DATA)), StandardCharsets.UTF_8).replace("synthetic-fixture", "game-resources").replace("test-v1", "game-v1").replace("\"exefs\":[]", "\"exefs\":[{\"name\":\"main\",\"size\":1,\"sha256\":\"" + pinned + "\"}]");
        assertEquals("test.data", game.manifest(bytes(gameManifest)).archives().get(0).name());
        fails("ExeFS", () -> game.manifest(bytes(gameManifest.replace(pinned, "1".repeat(64)))));
    }
}
