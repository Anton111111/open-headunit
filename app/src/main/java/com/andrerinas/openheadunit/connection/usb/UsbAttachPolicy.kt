package com.andrerinas.openheadunit.connection.usb

/**
 * Decides whether an attached Android phone should be switched into accessory mode.
 *
 * The gate this replaces asked only whether the vendor id was Google's, so a Pixel connected and
 * every other make fell through to the manual USB button. An empty allow list means "never
 * configured", not "nothing is permitted" - the same fallback single-USB auto-connect makes.
 */
object UsbAttachPolicy {

    /**
     * Checks if a device has a Google vendor ID (0x18D1) or belongs to a known Android Auto dongle
     * (e.g. smartBox, Carlinkit with Xiaomi VID 0x2717, Allwinner VID 0x1F3A, or dongle product names).
     */
    fun isGoogleOrDongleVendor(
        vendorId: Int,
        productName: String? = null,
        manufacturerName: String? = null,
    ): Boolean {
        if (vendorId == 0x18D1) return true // Google / AAWireless
        if (vendorId == 0x2717) return true // Xiaomi / Carlinkit / smartBox
        if (vendorId == 0x1F3A) return true // Allwinner AA dongles
        val product = productName ?: ""
        val mfg = manufacturerName ?: ""
        if (product.contains("smartbox", ignoreCase = true) ||
            product.contains("carlinkit", ignoreCase = true) ||
            product.contains("autokit", ignoreCase = true)) {
            return true
        }
        if (mfg.contains("smartbox", ignoreCase = true) ||
            mfg.contains("carlinkit", ignoreCase = true) ||
            mfg.contains("autokit", ignoreCase = true)) {
            return true
        }
        return false
    }

    /**
     * @param isGoogleVendor        vendor id is 0x18D1, or a known Android Auto dongle
     * @param autoStartOnUsb        the user asked us to start on any USB attach
     * @param allowListConfigured   the user has marked at least one device as allowed
     * @param deviceAllowed         this device is on that list
     */
    fun shouldAttemptAoaSwitch(
        isGoogleVendor: Boolean,
        autoStartOnUsb: Boolean,
        allowListConfigured: Boolean,
        deviceAllowed: Boolean,
    ): Boolean = when {
        isGoogleVendor -> true
        autoStartOnUsb -> true
        !allowListConfigured -> true
        else -> deviceAllowed
    }
}
