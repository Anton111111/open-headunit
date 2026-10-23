package com.adayo.mcucommproxy;

/**
 * Callback contract used by the vendor MCU JNI library to deliver raw status packets.
 * The Adayo/Haval projection plugin decodes gear-status packets to detect reverse-camera
 * activation. The package, interface name and callback signature, including the {@code char}
 * length parameter, must match the vendor JNI contract.
 *
 * @author @author Anton Potekhin (anton.potekhin@gmail.com)
 */
public interface IMcuCommCallback {
    void mcuCommCallback(byte[] data, char length);
}
