package com.cwave.weiqi.katago;

public class KataGoBridge {
    static {
        try {
            System.loadLibrary("katago_opencl");
        } catch (UnsatisfiedLinkError e) {
        }
        System.loadLibrary("katago_jni");
    }

    /**
     * Initializes the KataGo engine with the provided configuration and model paths.
     * @param configPath Path to the .cfg file.
     * @param modelPath Path to the .bin.gz or .txt.gz model file.
     * @return 0 on success, non-zero on failure.
     */
    public native int init(String configPath, String modelPath);

    /**
     * Sends a GTP command to the KataGo engine and returns the response.
     * @param command The GTP command string.
     * @return The response string from the engine.
     */
    public native String sendGtpCommand(String command);

    /**
     * Shuts down the KataGo engine.
     */
    public native void shutdown();
}
