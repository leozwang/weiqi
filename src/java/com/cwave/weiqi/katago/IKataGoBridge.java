package com.cwave.weiqi.katago;

public interface IKataGoBridge {
    int init(String configPath, String modelPath);
    String sendGtpCommand(String command);
    int[] getBoardState();
    void shutdown();
}
