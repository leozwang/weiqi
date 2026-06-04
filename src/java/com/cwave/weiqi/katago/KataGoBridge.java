package com.cwave.weiqi.katago;

public class KataGoBridge implements IKataGoBridge {
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
     * @return 0 on success, negative integer indicating failure:
     *         -1: Config file not found.
     *         -2: Model file not found.
     *         -3: Core hash/table initialization failed (legacy).
     *         -4: Asset copy extraction failed.
     *         -5: Java-level or general platform exception during copying or init.
     *         -10 to -18: C++ engine failure steps matching C++ code setup sequence.
     */
    public native int init(String configPath, String modelPath);

    /**
     * Sends a GTP command to the KataGo engine and returns the response.
     * @param command The GTP command string.
     * @return The response string from the engine.
     */
    public native String sendGtpCommand(String command);

    /**
     * Returns the current board state as an array of integers.
     * 0: EMPTY, 1: BLACK, 2: WHITE
     * @return 1D array of size boardSize * boardSize
     */
    public native int[] getBoardState();

    /**
     * Shuts down the KataGo engine.
     */
    public native void shutdown();
}
