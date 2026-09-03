package com.ary.push.internal.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.ary.push.BuildConfig
import java.util.Locale
import java.util.TimeZone

/**
 * Collects the small, documented set of device facts the backend needs to target a notification.
 *
 * Deliberately narrow. Nothing here identifies a person, and nothing is read that would require
 * a runtime permission: no advertising id, no hardware serial, no accounts, no phone number, no
 * location. Every field below is listed in docs/SECURITY.md so that a privacy review can be
 * done against the documentation rather than against the source.
 */
internal class DeviceInfoProvider(context: Context) {

    private val appContext = context.applicationContext

    /** Host application version name, e.g. `5.2.0`. */
    val appVersion: String? by lazy { packageInfo()?.versionName }

    /** Host application version code, as a string so that long codes survive JSON. */
    val appBuild: String? by lazy {
        packageInfo()?.let { PackageInfoCompat.getLongVersionCode(it).toString() }
    }

    /** ARY Push SDK version, taken from the build so it can never drift from the artifact. */
    val sdkVersion: String get() = BuildConfig.SDK_VERSION

    /** Android release version, e.g. `14`. */
    val osVersion: String get() = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()

    /** Marketing device model, e.g. `Pixel 8`. */
    val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Current BCP-47 locale, e.g. `en-PK`. Read on each access so a locale change is picked up. */
    val locale: String get() = Locale.getDefault().toLanguageTag()

    /** IANA timezone, e.g. `Asia/Karachi`. */
    val timezone: String get() = TimeZone.getDefault().id

    /** The host application's package name, which is also its public Play identity. */
    val packageName: String get() = appContext.packageName

    private fun packageInfo() = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
