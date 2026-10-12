package org.d2rm.android;

public final class NativeProbe {
    static {
        System.loadLibrary("d2rm_probe");
    }

    private NativeProbe() {}

    public static native String read();
}
