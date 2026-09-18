package com.krishnakb.classifiedprobe

import android.content.Context
import android.content.pm.PackageManager

/**
 * Reports whether the pieces needed for a third-party app to open its own ANT+
 * channel are present on this device.
 *
 * Background: a Karoo can pair the Classified hub through its own Sensors app,
 * but that is Karoo's internal radio stack. Whether a *sideloaded* app can open
 * an independent ANT channel is a separate question, and at least one Karoo 3
 * extension author reports that Karoo blocks `setRfFrequency()` for third-party
 * apps. This check is the cheap first half of answering that: if the ANT Radio
 * Service is not even installed, no third-party ANT+ access is possible and the
 * BLE path is the only option.
 *
 * A positive result here is necessary but not sufficient - actually acquiring a
 * channel requires the ANT Android SDK. See README "Stage 2".
 */
object AntEnvironment {

    /** ANT Radio Service: brokers all ANT access for third-party apps. */
    private const val ANT_RADIO_SERVICE = "com.dsi.ant.service.socket"

    /** ANT+ Plugins Service: high-level device profile plugins. */
    private const val ANT_PLUGINS_SERVICE = "com.dsi.ant.plugins.antplus"

    /** System feature advertised by devices with usable ANT hardware. */
    private const val ANT_FEATURE = "com.dsi.ant.antradio_library"

    /** ANT HAL service, the bridge to the radio hardware. */
    private const val ANT_HAL_SERVICE = "com.dsi.ant.server"

    private const val ANT_PERMISSION = "com.dsi.ant.permission.ANT"

    fun report(context: Context): String {
        val lines = mutableListOf<String>()
        lines += "ANT+ ACCESS CHECK"
        lines += "-".repeat(40)
        lines += featureLine(context)
        lines += packageLine(context, ANT_RADIO_SERVICE, "ANT Radio Service")
        lines += packageLine(context, ANT_PLUGINS_SERVICE, "ANT+ Plugins Service")
        lines += packageLine(context, ANT_HAL_SERVICE, "ANT HAL Service")
        lines += permissionLine(context)
        lines += ""
        lines += verdict(context)
        return lines.joinToString("\n")
    }

    private fun featureLine(context: Context): String {
        val present = context.packageManager.hasSystemFeature(ANT_FEATURE)
        // Karoo 3 does not declare this feature yet still ships a working ANT stack,
        // so its absence is informational only, not disqualifying.
        return "${mark(present)} system feature $ANT_FEATURE (informational)"
    }

    private fun packageLine(context: Context, pkg: String, label: String): String {
        val version = installedVersion(context, pkg)
        return if (version == null) "${mark(false)} $label ($pkg) NOT INSTALLED"
        else "${mark(true)} $label v$version"
    }

    private fun permissionLine(context: Context): String {
        val granted = context.checkSelfPermission(ANT_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return "${mark(granted)} $ANT_PERMISSION held by this app"
    }

    private fun installedVersion(context: Context, pkg: String): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun verdict(context: Context): String {
        val radioService = installedVersion(context, ANT_RADIO_SERVICE) != null
        return if (radioService) {
            "VERDICT: ANT Radio Service is present, so third-party ANT+ access is\n" +
                "worth attempting. Next step is Stage 2 (acquire a channel) - this\n" +
                "check cannot tell you whether Karoo blocks channel configuration."
        } else {
            "VERDICT: ANT Radio Service is absent. A sideloaded app cannot open an\n" +
                "ANT+ channel on this device, regardless of Karoo's own ANT+ support.\n" +
                "Use the BLE path instead."
        }
    }

    private fun mark(ok: Boolean) = if (ok) "[YES]" else "[NO ]"
}
