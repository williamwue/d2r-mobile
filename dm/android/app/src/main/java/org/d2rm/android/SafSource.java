package org.d2rm.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SafSource implements ResourceStore.Source {
    private final ContentResolver resolver;
    private final Uri tree;
    private final Map<String,Uri> files = new HashMap<>();
    SafSource(ContentResolver resolver, Uri tree) { this.resolver = resolver; this.tree = tree; }
    @Override public List<String> names() throws IOException {
        files.clear();
        List<String> names = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("文档提供程序没有返回目录内容");
            while (cursor.moveToNext()) {
                if (names.size() >= 4096) throw new IOException("文件夹条目过多，请选择资源所在的直接目录");
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (id == null || name == null) throw new IOException("文档提供程序返回了无效条目");
                names.add(name);
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) files.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } catch (SecurityException e) { throw new IOException("文件夹读取权限已失效，请重新选择", e); }
        return names;
    }
    @Override public InputStream open(String name) throws IOException {
        Uri uri = files.get(name);
        if (uri == null) throw new IOException("缺少普通文件 " + name);
        try {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("无法打开资源 " + name);
            return input;
        } catch (SecurityException e) { throw new IOException("文件读取权限已失效，请重新选择", e); }
    }
}
