package org.d2rm.android;

final class GraphicsProbe {
    static { System.loadLibrary("d2rm_graphics_probe"); }
    private GraphicsProbe() {}
    static native long create(android.view.Surface surface);
    static native String draw(long session);
    static native void close(long session);
    static native String stats();
}
