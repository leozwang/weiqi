package com.cwave.weiqi.katago;

import android.util.Log;

/**
 * KataGo bridge implementation targeting the Google Tensor G4 TPU on Pixel 9 devices.
 * Uses LiteRT / DarwiNN hardware acceleration with fallback mechanisms.
 */
public class KataGoBridgeTPU implements IKataGoBridge {
    private static final String TAG = "KataGoBridgeTPU";
    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("LiteRtDispatch_google_tensor");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "libLiteRtDispatch_google_tensor.so not found on system path (will use built-in dispatcher).");
        }

        try {
            System.loadLibrary("katago_tpu_jni");
            sLibraryLoaded = true;
            Log.i(TAG, "Successfully loaded libkatago_tpu_jni.so");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libkatago_tpu_jni.so", e);
            sLibraryLoaded = false;
        }
    }

    public static boolean isSupported() {
        return sLibraryLoaded;
    }

    /**
     * Initializes the KataGo engine targeting the Pixel 9 Tensor G4 TPU.
     * @param configPath Path to the .cfg file.
     * @param modelPath Path to the .bin.gz, .tflite, or compiled TPU model.
     * @return 0 on success, negative error code on failure.
     */
    public native int init(String configPath, String modelPath);

    /**
     * Sends a GTP command to the KataGo engine.
     * @param command The GTP command string.
     * @return The response string from the engine.
     */
    public native String sendGtpCommand(String command);

    /**
     * Returns the current board state as a 1D array of integers (19x19).
     * 0: EMPTY, 1: BLACK, 2: WHITE
     */
    public native int[] getBoardState();

    /**
     * Shuts down the KataGo TPU engine.
     */
    public native void shutdown();
}
