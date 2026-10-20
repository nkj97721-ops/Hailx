package com.aistra.hail.utils

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HPackages {
    val myUserId get() = android.os.Process.myUserHandle().hashCode()

    // 分身应用用 "包名#用户ID" 作为唯一标识 其余代码按包名处理即可无需改动
    private const val CLONE_SEP = '#'
    val cloneUserIds = 999 downTo 995

    fun userIdOf(key: String): Int = key.substringAfter(CLONE_SEP, "").toIntOrNull() ?: myUserId
    fun pkgOf(key: String): String = key.substringBefore(CLONE_SEP)
    fun cloneKey(packageName: String, userId: Int): String =
        if (userId == myUserId) packageName else "$packageName$CLONE_SEP$userId"

    fun getCloneKeys(): List<String> {
        if (!HailData.workingMode.startsWith(HailData.SHIZUKU)) return emptyList()
        return cloneUserIds.flatMap { userId ->
            HShizuku.getInstalledApplicationsAsUser(userId)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { cloneKey(it.packageName, userId) }
        }
    }

    fun packageUri(packageName: String) = "package:${pkgOf(packageName)}"

    @RequiresApi(Build.VERSION_CODES.N)
    fun packageUid(packageName: String): Int {
        val pkg = pkgOf(packageName)
        val uid = if (HTarget.T) app.packageManager.getPackageUid(
            pkg, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        ) else app.packageManager.getPackageUid(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        return userIdOf(packageName) * 100000 + uid % 100000
    }

    fun getInstalledApplications(flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192): List<ApplicationInfo> =
        if (HTarget.T) app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getInstalledApplications(flags)

    fun getUnhiddenPackageInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192
    ) = runCatching {
        val pkg = pkgOf(packageName)
        if (HTarget.T) app.packageManager.getPackageInfo(
            pkg, PackageManager.PackageInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getPackageInfo(pkg, flags)
    }.getOrNull()

    fun getApplicationInfoOrNull(
        packageName: String, flags: Int = if (HTarget.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 8192
    ): ApplicationInfo? {
        val userId = userIdOf(packageName)
        if (userId != myUserId) return HShizuku.getApplicationInfoAsUser(pkgOf(packageName), flags, userId)
        return runCatching {
            if (HTarget.T) app.packageManager.getApplicationInfo(
                packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
            )
            else app.packageManager.getApplicationInfo(packageName, flags)
        }.getOrNull()
    }

    fun isAppDisabled(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false

    fun isAppHidden(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 1 == 1
    } ?: false

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppSuspended(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        when {
//            This method will cause NameNotFoundException with uninstalled packages
//            HTarget.Q -> app.packageManager.isPackageSuspended(packageName)
            HTarget.N -> it.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
            else -> false
        }
    } ?: false

    fun isAppUninstalled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 8 == 8
    } ?: false

    fun canUninstallNormally(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.sourceDir?.startsWith("/data") ?: false

    fun forceStopApp(packageName: String): Boolean = runCatching {
        app.getSystemService<ActivityManager>()!!.let {
            if (HTarget.P) HiddenApiBypass.invoke(it::class.java, it, "forceStopPackage", packageName)
            else it::class.java.getMethod("forceStopPackage", String::class.java).invoke(it, packageName)
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean {
        getApplicationInfoOrNull(packageName) ?: return false
        if (disabled) forceStopApp(packageName)
        runCatching {
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }.onFailure {
            HLog.e(it)
        }
        return isAppDisabled(packageName) == disabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        app.getSystemService<AppOpsManager>()!!.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }
}
