package org.d2rm.android;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImportApplication extends Application {
    enum Phase { IDLE, IMPORTING, CANCELLING, COMMITTING, READY, FAILED, CANCELLED }
    record State(Phase phase, long copied, long total, String detail, ResourceStore.Recovery recovery) {
        boolean busy() { return phase == Phase.IMPORTING || phase == Phase.CANCELLING || phase == Phase.COMMITTING; }
    }
    interface Observer { void changed(State state); }
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ResourceStore store;
    private ResourceContract contract;
    private ResourceStore.Operation current;
    private Observer observer;
    private Uri lastSource;
    private State state;
    @Override public void onCreate() {
        super.onCreate();
        try (InputStream input = getAssets().open("contract.json")) {
            contract = new ResourceContract(ResourceStore.readBounded(input, 65536, new ResourceStore.Operation()), !getPackageName().endsWith(".fixture"));
            store = new ResourceStore(new File(getNoBackupFilesDir(), "resources"), contract, durableFiles());
            ResourceStore.Recovery recovery = store.recover();
            String detail = recovery.problem() != null ? recovery.problem() : recovery.interrupted() ? "发现未完成导入，已标记中断。重试将从头校验。" : "请选择资源文件夹";
            state = new State(recovery.hasResources() ? Phase.READY : Phase.IDLE, 0, 0, detail, recovery);
        } catch (IOException e) { state = new State(Phase.FAILED, 0, 0, "初始化失败：" + e.getMessage(), null); }
    }
    static ResourceStore.DurableFiles durableFiles() {
        return new ResourceStore.DurableFiles() {
            @Override public void syncDirectory(File directory) throws IOException {
                FileDescriptor descriptor = null;
                try { descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0); Os.fsync(descriptor); }
                catch (ErrnoException e) { throw new IOException("目录同步失败 " + directory.getName(), e); }
                finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException e) { throw new IOException("目录关闭失败", e); } }
            }
            @Override public void rename(File from, File to) throws IOException {
                try { Os.rename(from.getAbsolutePath(), to.getAbsolutePath()); }
                catch (ErrnoException e) { throw new IOException("原子发布失败", e); }
            }
        };
    }
    String productVersion() { return contract == null ? "未知" : contract.productVersion; }
    State state() { return state; }
    void observe(Observer target) { observer = target; target.changed(state); }
    void detach(Observer target) { if (observer == target) observer = null; }
    boolean canRetry() { return lastSource != null && store != null; }
    void retry() { if (lastSource != null) start(lastSource); }
    void start(Uri source) {
        if (state.busy() || store == null) return;
        lastSource = source;
        ResourceStore.Operation operation = new ResourceStore.Operation(); current = operation;
        ResourceStore.Recovery old = state.recovery();
        publish(new State(Phase.IMPORTING, 0, 0, "正在检查清单和资源目录", old));
        writer.execute(() -> {
            try {
                ResourceStore.Recovery recovery = store.importResources(new SafSource(getContentResolver(), source), operation, (copied, total, name) -> {
                    main.post(() -> { if (current == operation && state.phase() == Phase.IMPORTING) publish(new State(Phase.IMPORTING, copied, total, "正在校验 " + name, old)); });
                });
                main.post(() -> { if (current == operation) { current = null; publish(new State(Phase.READY, recovery.bytes(), recovery.bytes(), "导入完成，全部资源在导入时通过 SHA-256 校验", recovery)); } });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> { if (current == operation) {
                    current = null;
                    String message = operation.isCancelled() ? "导入已取消；原有资源保持可用" : "导入失败：" + failure.getMessage() + "。请检查空间、权限及来源后重试。";
                    publish(new State(operation.isCancelled() ? Phase.CANCELLED : Phase.FAILED, state.copied(), state.total(), message, old));
                } });
            }
        });
    }
    boolean cancel() {
        if (current == null || !state.busy()) return false;
        ResourceStore.Operation operation = current;
        if (!operation.requestCancel()) {
            publish(new State(Phase.COMMITTING, state.copied(), state.total(), "资源已进入提交阶段，无法取消，正在完成发布", state.recovery()));
            return false;
        }
        publish(new State(Phase.CANCELLING, state.copied(), state.total(), "正在取消并关闭读取流…", state.recovery()));
        new Thread(operation::closeCancelledStream, "resource-cancel-" + operation.id).start();
        return true;
    }
    private void publish(State next) { state = next; if (observer != null) observer.changed(next); }
}
