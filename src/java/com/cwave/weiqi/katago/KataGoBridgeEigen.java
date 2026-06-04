package com.cwave.weiqi.katago;

public class KataGoBridgeEigen implements IKataGoBridge {
    static {
        System.loadLibrary("katago_eigen_jni");
    }

    public native int init(String configPath, String modelPath);
    public native String sendGtpCommand(String command);
    public native int[] getBoardState();
    public native void shutdown();
}
