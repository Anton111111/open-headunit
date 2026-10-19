package com.adayo.mcucommproxy;

/**
 * Minimal Java binding to the head unit's vendor-provided {@code mcucommproxy_jni} library.
 * The Adayo/Haval projection plugin uses it to subscribe to MCU gear-status updates so Android
 * Auto yields video focus to the reverse camera and resumes when reverse gear is disengaged.
 * The package, class name and native method signatures must match the vendor JNI contract.
 * The library is loaded by the device plugin, not by this binding.
 *
 * @author Anton Potekhin (anton.potekhin@gmail.com)
 */
public final class McuCommManager {
    private static final McuCommManager INSTANCE = new McuCommManager();

    private McuCommManager() {}

    public static McuCommManager getInstance() {
        return INSTANCE;
    }

    public static native boolean connectMcuCommServer();

    public static native boolean mcuCommSendcmd(byte[] data, char length);

    public static native boolean registMcuCommCallback(IMcuCommCallback callback, byte functionId);

    public static native boolean unregistMcuCommCallback(IMcuCommCallback callback, byte functionId);
}
