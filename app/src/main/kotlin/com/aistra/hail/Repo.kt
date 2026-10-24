package com.aistra.hail

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HShizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class Entry(
    val key: String,
    val pkg: String,
    val userId: Int,
    val sortKey: String,
    val order: Int,
    val name: String,
    val frozen: Boolean,
    val pinned: Boolean,
    val whitelisted: Boolean
)

object Repo {
    private const val MATCH_UNINSTALLED = 0x2000
    private val cloneUserIds = 999 downTo 995
    private val prefs by lazy { app.getSharedPreferences("hailx", Context.MODE_PRIVATE) }
    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    /** 汉字转拼音再按字母排序 系统不支持时退回到中文排序规则 */
    private val transliterator: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
            }.getOrNull()
        } else null
    }

    val myUserId: Int get() = android.os.Process.myUserHandle().hashCode()

    private fun sortKeyOf(label: String): String =
        (transliterator?.let { t -> runCatching { t.transliterate(label) }.getOrNull() }
            ?: label.lowercase()).replace(" ", "")

    private fun compareKeys(a: String, b: String): Int =
        if (transliterator != null) a.compareTo(b) else collator.compare(a, b)

    private fun pins(): Set<String> = prefs.getStringSet("pins", emptySet()) ?: emptySet()
    private fun whites(): Set<String> = prefs.getStringSet("whites", emptySet()) ?: emptySet()

    /** 长按：置顶 / 取消置顶 返回 true 表示现在是置顶 */
    fun togglePin(key: String): Boolean {
        val set = pins().toMutableSet()
        val added = set.add(key)
        if (!added) set.remove(key)
        prefs.edit().putStringSet("pins", set).apply()
        return added
    }

    /** 右滑：加入 / 移出白名单 返回 true 表示现在在白名单里 */
    fun toggleWhite(key: String): Boolean {
        val set = whites().toMutableSet()
        val added = set.add(key)
        if (!added) set.remove(key)
        prefs.edit().putStringSet("whites", set).apply()
        return added
    }

    fun withPins(list: List<Entry>): List<Entry> {
        val pins = pins()
        val whites = whites()
        return list.map {
            it.copy(pinned = it.key in pins, whitelisted = it.key in whites)
        }.sortedWith(
            compareByDescending<Entry> { it.pinned }
                .thenComparator { a, b -> compareKeys(a.sortKey, b.sortKey) }
                .thenBy { it.order }
        )
    }

    private fun thirdParty(list: List<ApplicationInfo>): List<ApplicationInfo> = list.filter {
        it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                it.flags and ApplicationInfo.FLAG_INSTALLED != 0 &&
                !it.packageName.startsWith("com.aistra.hail")
    }

    @Suppress("DEPRECATION")
    private fun mainApps(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(MATCH_UNINSTALLED.toLong()))
        } else {
            pm.getInstalledApplications(MATCH_UNINSTALLED)
        }

    /** 自动读取：所有已安装的第三方应用 + 分身用户里的应用 */
    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val me = myUserId
        val main = thirdParty(mainApps(pm))
        val mainByPkg = main.associateBy { it.packageName }
        val out = ArrayList<Entry>()
        for (info in main) {
            val label = info.loadLabel(pm).toString()
            out += Entry(
                key = info.packageName, pkg = info.packageName, userId = me,
                sortKey = sortKeyOf(label), order = 0, name = label,
                frozen = !info.enabled, pinned = false, whitelisted = false
            )
        }
        if (HShizuku.isAlive && HShizuku.isGranted) {
            for (uid in cloneUserIds) {
                for (info in thirdParty(HShizuku.installedApps(uid))) {
                    val src = mainByPkg[info.packageName] ?: info
                    val label = src.loadLabel(pm).toString()
                    val n = 1000 - uid
                    out += Entry(
                        key = "${info.packageName}#$uid", pkg = info.packageName, userId = uid,
                        sortKey = sortKeyOf(label), order = n, name = "$n $label",
                        frozen = !info.enabled, pinned = false, whitelisted = false
                    )
                }
            }
        }
        withPins(out)
    }

    /** 一键冻结：跳过白名单的应用 返回成功数量 */
    suspend fun freezeAll(list: List<Entry>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (e in list) {
            if (!e.frozen && !e.whitelisted && HShizuku.setDisabled(e.pkg, e.userId, true)) n++
        }
        n
    }

    /** 解冻（如果需要）并直接启动指定用户下的应用 */
    suspend fun launch(e: Entry): Boolean = withContext(Dispatchers.IO) {
        if (e.frozen) HShizuku.setDisabled(e.pkg, e.userId, false)
        val u = e.userId
        val result = HShizuku.execute(
            "am start --user $u -n \$(cmd package resolve-activity --brief --user $u " +
                    "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${e.pkg} | tail -n 1)"
        )
        result.first == 0 && result.second?.contains("Error", ignoreCase = true) != true
    }
}
