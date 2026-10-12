package org.d2rm.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class ResourceContract {
    record Entry(String name, long size, String sha256) {}
    record Manifest(byte[] raw, List<Entry> archives) {}
    final String kind;
    final String contractId;
    final String productVersion;
    final Map<String,Long> archives;
    final Map<String,String> nsoHashes;

    ResourceContract(byte[] raw, boolean gameApplication) throws IOException {
        Map<String,Object> root = object(StrictJson.parse(raw));
        fields(root, "schemaVersion", "kind", "contractId", "productVersion", "nsoSha256", "archives");
        version(root);
        kind = string(root.get("kind"));
        if (gameApplication && !kind.equals("game-resources")) throw new IOException("正式应用拒绝测试资源合约");
        if (!kind.equals("game-resources") && !kind.equals("synthetic-fixture")) throw new IOException("未知资源类型");
        contractId = string(root.get("contractId"));
        productVersion = string(root.get("productVersion"));
        nsoHashes = new TreeMap<>();
        for (var entry : object(root.get("nsoSha256")).entrySet()) { name(entry.getKey()); nsoHashes.put(entry.getKey(), hash(entry.getValue())); }
        archives = new TreeMap<>();
        for (Object value : array(root.get("archives"))) {
            Map<String,Object> item = object(value); fields(item, "name", "size");
            String name = name(item.get("name"));
            if (archives.put(name, size(item.get("size"))) != null) throw new IOException("合约资源名称重复");
        }
        if (archives.isEmpty()) throw new IOException("合约没有资源");
    }
    Manifest manifest(byte[] raw) throws IOException {
        if (raw.length > 65536) throw new IOException("清单超过 64 KiB");
        Map<String,Object> root = object(StrictJson.parse(raw));
        fields(root, "schemaVersion", "kind", "contractId", "productVersion", "exefs", "archives");
        version(root);
        if (!kind.equals(string(root.get("kind"))) || !contractId.equals(string(root.get("contractId"))) || !productVersion.equals(string(root.get("productVersion")))) throw new IOException("清单版本或资源类型与应用不匹配");
        List<Entry> exefs = entries(root.get("exefs"));
        Map<String,String> declarations = new TreeMap<>();
        for (Entry e : exefs) declarations.put(e.name, e.sha256);
        if (!declarations.equals(nsoHashes)) throw new IOException("ExeFS 声明与固定摘要不匹配");
        List<Entry> files = entries(root.get("archives"));
        Map<String,Long> declared = new TreeMap<>();
        for (Entry e : files) declared.put(e.name, e.size);
        if (!declared.equals(archives)) throw new IOException("资源名称或字节大小与合约不匹配");
        return new Manifest(raw.clone(), files);
    }
    private static List<Entry> entries(Object value) throws IOException {
        Map<String,Entry> entries = new TreeMap<>();
        for (Object itemValue : array(value)) {
            Map<String,Object> item = object(itemValue); fields(item, "name", "size", "sha256");
            Entry e = new Entry(name(item.get("name")), size(item.get("size")), hash(item.get("sha256")));
            if (entries.put(e.name, e) != null) throw new IOException("清单资源名称重复");
        }
        return new ArrayList<>(entries.values());
    }
    static Map<String,Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?,?> map)) throw new IOException("需要 JSON 对象");
        Map<String,Object> result = new TreeMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("JSON 对象键必须为字符串");
            result.put(key, entry.getValue());
        }
        return result;
    }
    static List<?> array(Object value) throws IOException { if (!(value instanceof List<?> list)) throw new IOException("需要 JSON 数组"); return list; }
    static void fields(Map<String,Object> value, String... expected) throws IOException { if (!value.keySet().equals(Set.of(expected))) throw new IOException("JSON 字段不完整或包含未知字段"); }
    static String string(Object value) throws IOException { if (!(value instanceof String text) || text.isEmpty()) throw new IOException("需要非空字符串"); return text; }
    static String name(Object value) throws IOException {
        String result = string(value);
        if (!result.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || result.contains("..")) throw new IOException("无效资源名称");
        return result;
    }
    static String hash(Object value) throws IOException { String result = string(value); if (!result.matches("[0-9a-f]{64}")) throw new IOException("无效 SHA-256 摘要"); return result; }
    static long size(Object value) throws IOException { if (!(value instanceof Long n) || n <= 0) throw new IOException("大小必须是正整数"); return n; }
    private static void version(Map<String,Object> root) throws IOException { if (!Long.valueOf(1).equals(root.get("schemaVersion"))) throw new IOException("未知清单版本"); }
}
