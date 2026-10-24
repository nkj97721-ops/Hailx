package com.aistra.hail.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.aistra.hail.BuildConfig
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object HShizuku {
    private const val TAG = "HShizuku"
    private const val MATCH_UNINSTALLED = 0x2000

    val isAlive: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val isGranted: Boolean
        get() = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private val isRoot: Boolean get() = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)

    private fun asInterface(className: String, original: IBinder): Any {
        val stub = Class.forName("$className\$Stub")
        val wrapped = ShizukuBinderWrapper(original)
        val result: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.invoke(stub, null, "asInterface", wrapped)
        } else {
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
        }
        return result!!
    }

    private fun service(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    /** 指定用户下已安装的应用（含分身用户） */
    @Suppress("UNCHECKED_CAST")
    fun installedApps(userId: Int): List<ApplicationInfo> = runCatching {
        val pm = service("android.content.pm.IPackageManager", "package")
        // Android 13+ 的 flags 是 long 之前是 int
        val slice = runCatching {
            HiddenApiBypass.invoke(
                pm::class.java, pm, "getInstalledApplications", MATCH_UNINSTALLED.toLong(), userId
            )
        }.getOrElse {
            HiddenApiBypass.invoke(
                pm::class.java, pm, "getInstalledApplications", MATCH_UNINSTALLED, userId
            )
        }!!
        val base = Class.forName("android.content.pm.BaseParceledListSlice")
        HiddenApiBypass.invoke(base, slice, "getList") as List<ApplicationInfo>
    }.getOrElse {
        Log.e(TAG, "installedApps($userId)", it)
        emptyList()
    }

    fun forceStop(packageName: String, userId: Int): Boolean = runCatching {
        val am = service("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
        HiddenApiBypass.invoke(am::class.java, am, "forceStopPackage", packageName, userId)
        true
    }.getOrElse {
        Log.e(TAG, "forceStop($packageName, $userId)", it)
        false
    }

    fun setDisabled(packageName: String, userId: Int, disabled: Boolean): Boolean {
        if (disabled) forceStop(packageName, userId)
        return runCatching {
            val pm = service("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            pm::class.java.getMethod(
                "setApplicationEnabledSetting",
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                String::class.java
            ).invoke(pm, packageName, newState, 0, userId, BuildConfig.APPLICATION_ID)
            true
        }.getOrElse {
            Log.e(TAG, "setDisabled($packageName, $userId, $disabled)", it)
            false
        }
    }

    fun execute(command: String): Pair<Int, String?> = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder())
            .newProcess(arrayOf(if (isRoot) "su" else "sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                    it.write(command.toByteArray())
                }
                waitFor() to inputStream.text().ifBlank { errorStream.text() }.also { destroy() }
            }
    }.getOrElse { 1 to it.stackTraceToString() }

    private fun ParcelFileDescriptor.text(): String =
        ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
}
