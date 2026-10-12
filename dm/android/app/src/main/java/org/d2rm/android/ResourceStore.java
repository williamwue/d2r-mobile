package org.d2rm.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ResourceStore {
    interface Source {
        List<String> names() throws IOException;
        InputStream open(String name) throws IOException;
    }
    interface DurableFiles {
        void syncDirectory(File directory) throws IOException;
        void rename(File from, File to) throws IOException;
    }
    interface Progress { void update(long copied, long total, String filename); }
    record Recovery(String generation, String manifestDigest, long bytes, boolean interrupted, String problem) {
        boolean hasResources() { return generation != null; }
    }
    static final class Operation {
        private enum State { RUNNING, CANCELLED, COMMITTING }
        final String id = UUID.randomUUID().toString();
        private volatile State state = State.RUNNING;
        private InputStream stream;
        synchronized boolean requestCancel() {
            if (state == State.COMMITTING) return false;
            state = State.CANCELLED;
            return true;
        }
        void closeCancelledStream() {
            InputStream input;
            synchronized (this) { input = state == State.CANCELLED ? stream : null; }
            if (input != null) try { input.close(); } catch (IOException ignored) { }
        }
        synchronized void attach(InputStream input) throws IOException { check(); stream = input; }
        synchronized void detach() { stream = null; }
        synchronized void beginCommit() throws IOException { check(); state = State.COMMITTING; }
        void check() throws IOException { if (state == State.CANCELLED) throw new IOException("导入已取消；原有资源保持可用"); }
        boolean isCancelled() { return state == State.CANCELLED; }
    }
    private final File root;
    private final File staging;
    private final File sets;
    private final File pointer;
    private final ResourceContract contract;
    private final DurableFiles durable;

    ResourceStore(File root, ResourceContract contract, DurableFiles durable) throws IOException {
        this.root = root; this.contract = contract; this.durable = durable;
        staging = new File(root, "staging"); sets = new File(root, "sets"); pointer = new File(root, "active.json");
        directory(root); directory(staging); directory(sets);
        durable.syncDirectory(root);
        durable.syncDirectory(root.getParentFile());
    }
    synchronized Recovery importResources(Source source, Operation operation, Progress progress) throws IOException {
        operation.check();
        List<String> sourceNames = source.names();
        Set<String> seen = new HashSet<>();
        for (String name : sourceNames) {
            if (!seen.add(name)) throw new IOException("来源文件名称重复，无法确定唯一资源");
            for (String other : seen) if (!other.equals(name) && other.equalsIgnoreCase(name)) throw new IOException("来源名称大小写存在歧义");
        }
        if (!seen.contains("game-manifest.json")) throw new IOException("请选择直接包含 game-manifest.json 的文件夹");
        byte[] raw;
        try (InputStream input = source.open("game-manifest.json")) {
            operation.attach(input);
            raw = readBounded(input, 65536, operation);
        } finally { operation.detach(); }
        ResourceContract.Manifest manifest = contract.manifest(raw);
        long total = 0;
        for (var entry : manifest.archives()) {
            if (!seen.contains(entry.name())) throw new IOException("缺少资源文件 " + entry.name());
            total = Math.addExact(total, entry.size());
        }
        if (root.getUsableSpace() < total) throw new IOException("应用存储空间不足，需要 " + total + " 字节；已导入资源和未完成工作仍占用空间");
        File marker = new File(root, "operation-" + operation.id + ".marker");
        writeSynced(marker, operation.id.getBytes(StandardCharsets.UTF_8)); durable.syncDirectory(root);
        File stage = new File(staging, operation.id); directory(stage);
        durable.syncDirectory(staging);
        long copied = 0;
        byte[] buffer = new byte[1024 * 1024];
        for (var entry : manifest.archives()) {
            operation.check();
            long fileBytes = 0;
            MessageDigest hash = sha256();
            try (InputStream input = source.open(entry.name()); FileOutputStream output = new FileOutputStream(new File(stage, entry.name()))) {
                operation.attach(input);
                int count;
                while ((count = input.read(buffer)) != -1) {
                    operation.check();
                    if (count == 0) continue;
                    if (count > entry.size() - fileBytes) throw new IOException("资源大于清单大小 " + entry.name());
                    output.write(buffer, 0, count); hash.update(buffer, 0, count);
                    fileBytes += count; copied += count;
                    progress.update(copied, total, entry.name());
                }
                if (fileBytes != entry.size()) throw new IOException("资源字节大小不足 " + entry.name());
                if (!HexFormat.of().formatHex(hash.digest()).equals(entry.sha256())) throw new IOException("SHA-256 校验失败 " + entry.name());
                output.getFD().sync();
            } finally { operation.detach(); }
        }
        operation.check();
        String digest = digest(raw);
        writeSynced(new File(stage, "game-manifest.json"), raw);
        byte[] receipt = StrictJson.encode(Map.of("generation", operation.id, "manifestDigest", digest, "bytes", total)).getBytes(StandardCharsets.UTF_8);
        writeSynced(new File(stage, "receipt.json"), receipt);
        durable.syncDirectory(stage);
        operation.check();
        File complete = new File(sets, operation.id);
        durable.rename(stage, complete);
        durable.syncDirectory(staging);
        durable.syncDirectory(sets);
        File next = new File(root, "pointer-" + operation.id + ".tmp");
        writeSynced(next, receipt);
        durable.syncDirectory(root);
        byte[] previous = pointer.isFile() ? readFile(pointer, 65536) : null;
        operation.beginCommit();
        durable.rename(next, pointer);
        try { durable.syncDirectory(root); }
        catch (IOException failure) {
            if (previous != null) {
                File rollback = new File(root, "rollback-" + operation.id + ".tmp");
                writeSynced(rollback, previous); durable.rename(rollback, pointer); durable.syncDirectory(root);
            } else if (!pointer.delete()) failure.addSuppressed(new IOException("无法撤销新指针"));
            else durable.syncDirectory(root);
            throw failure;
        }
        if (marker.delete()) try { durable.syncDirectory(root); } catch (IOException ignored) { }
        return new Recovery(operation.id, digest, total, false, null);
    }
    synchronized Recovery recover() {
        boolean interrupted = staging.list() != null && staging.list().length > 0;
        File[] pending = root.listFiles((dir, name) -> name.startsWith("pointer-") || name.startsWith("rollback-"));
        interrupted |= pending != null && pending.length > 0;
        File[] markers = root.listFiles((dir, name) -> name.startsWith("operation-") && name.endsWith(".marker"));
        boolean hasMarkers = markers != null && markers.length > 0;
        if (!pointer.exists()) return new Recovery(null, null, 0, interrupted || hasMarkers, null);
        try {
            byte[] rawPointer = readFile(pointer, 65536);
            Map<String,Object> record = ResourceContract.object(StrictJson.parse(rawPointer));
            ResourceContract.fields(record, "generation", "manifestDigest", "bytes");
            String generation = ResourceContract.string(record.get("generation"));
            if (!generation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IOException("资源指针无效");
            String identity = ResourceContract.hash(record.get("manifestDigest"));
            long expected = ResourceContract.size(record.get("bytes"));
            File current = new File(sets, generation);
            if (!java.util.Arrays.equals(rawPointer, readFile(new File(current, "receipt.json"), 65536))) throw new IOException("资源收据与指针不一致");
            byte[] rawManifest = readFile(new File(current, "game-manifest.json"), 65536);
            if (!digest(rawManifest).equals(identity)) throw new IOException("存储清单摘要不匹配");
            ResourceContract.Manifest manifest = contract.manifest(rawManifest);
            long bytes = 0;
            for (var entry : manifest.archives()) {
                File file = new File(current, entry.name());
                if (!file.isFile() || file.length() != entry.size()) throw new IOException("已保存资源缺失或大小改变 " + entry.name());
                bytes = Math.addExact(bytes, file.length());
            }
            if (bytes != expected) throw new IOException("收据总大小不匹配");
            if (markers != null) for (File marker : markers) if (!marker.getName().equals("operation-" + generation + ".marker")) interrupted = true;
            return new Recovery(generation, identity, bytes, interrupted, null);
        } catch (IOException | ArithmeticException failure) { return new Recovery(null, null, 0, interrupted || hasMarkers, failure.getMessage()); }
    }
    private static void directory(File file) throws IOException { if (!file.isDirectory() && !file.mkdirs()) throw new IOException("无法创建资源目录"); }
    private static void writeSynced(File file, byte[] data) throws IOException { try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); output.getFD().sync(); } }
    static byte[] readBounded(InputStream input, int limit, Operation operation) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) { operation.check(); if (count > limit - result.size()) throw new IOException("JSON 超过大小限制"); result.write(buffer, 0, count); }
        return result.toByteArray();
    }
    private static byte[] readFile(File file, int limit) throws IOException { try (InputStream input = new FileInputStream(file)) { return readBounded(input, limit, new Operation()); } }
    static String digest(byte[] data) { return HexFormat.of().formatHex(sha256().digest(data)); }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
}
