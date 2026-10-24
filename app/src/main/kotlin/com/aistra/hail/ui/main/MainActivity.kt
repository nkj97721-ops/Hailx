package com.aistra.hail.ui.main

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aistra.hail.Entry
import com.aistra.hail.R
import com.aistra.hail.Repo
import com.aistra.hail.utils.HShizuku
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var refresh: SwipeRefreshLayout
    private val adapter = EntryAdapter({ onTap(it) }, { onLongTap(it) })
    private var entries: List<Entry> = emptyList()
    private var keyword = ""
    private var busy = false
    private var searchItem: MenuItem? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) runOnUiThread { reload() }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            if (!HShizuku.isGranted) askPermission()
            reload()
        }
    }

    /** 右滑：加入 / 移出白名单 */
    private val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.RIGHT
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val e = adapter.currentList.getOrNull(pos)
            adapter.notifyItemChanged(pos)
            if (e != null) onSwipe(e)
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val toolbar = MaterialToolbar(this).apply { title = "雹" }
        val tint = ColorStateList.valueOf(
            MaterialColors.getColor(toolbar, com.google.android.material.R.attr.colorOnSurface)
        )

        val search = toolbar.menu.add(0, 2, 0, "搜索").setIcon(android.R.drawable.ic_menu_search)
        search.setShowAsAction(
            MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
        )
        MenuItemCompat.setIconTintList(search, tint)
        val searchView = SearchView(toolbar.context).apply {
            queryHint = "搜索应用"
            maxWidth = Int.MAX_VALUE
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?): Boolean = false
                override fun onQueryTextChange(q: String?): Boolean {
                    keyword = q.orEmpty()
                    render()
                    return true
                }
            })
        }
        search.actionView = searchView
        search.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                keyword = ""
                render()
                return true
            }
        })
        searchItem = search

        val freeze = toolbar.menu.add(0, 1, 1, "一键冻结").setIcon(R.drawable.ic_round_frozen)
        freeze.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        MenuItemCompat.setIconTintList(freeze, tint)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == 1) freezeAll()
            true
        }

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
        }
        swipeHelper.attachToRecyclerView(list)
        refresh = SwipeRefreshLayout(this).apply {
            addView(
                list,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            setOnRefreshListener { reload() }
        }

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val item = searchItem
                if (item != null && item.isActionViewExpanded) {
                    item.collapseActionView()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun askPermission() {
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) toast("请在 Shizuku 应用里给雹授权")
            else Shizuku.requestPermission(1)
        }
    }

    private fun ensureShizuku(): Boolean {
        if (!HShizuku.isAlive) {
            toast("请先启动 Shizuku")
            return false
        }
        if (!HShizuku.isGranted) {
            askPermission()
            return false
        }
        return true
    }

    /** 搜索：名称 包名 拼音 */
    private fun shown(): List<Entry> {
        val q = keyword.trim()
        if (q.isEmpty()) return entries
        val compact = q.replace(" ", "")
        return entries.filter {
            it.name.contains(q, ignoreCase = true) ||
                    it.pkg.contains(q, ignoreCase = true) ||
                    it.sortKey.contains(compact, ignoreCase = true)
        }
    }

    private fun render() {
        adapter.submitList(shown())
    }

    private fun reload() {
        if (busy) return
        busy = true
        refresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                entries = Repo.load()
                render()
            } finally {
                refresh.isRefreshing = false
                busy = false
            }
        }
    }

    private fun freezeAll() {
        if (busy || !ensureShizuku()) return
        busy = true
        refresh.isRefreshing = true
        lifecycleScope.launch {
            val n = try {
                Repo.freezeAll(entries)
            } finally {
                busy = false
            }
            toast("已冻结 $n 个")
            reload()
        }
    }

    private fun onTap(e: Entry) {
        if (busy || !ensureShizuku()) return
        busy = true
        lifecycleScope.launch {
            val ok = try {
                Repo.launch(e)
            } finally {
                busy = false
            }
            if (!ok) toast("启动失败")
        }
    }

    private fun onSwipe(e: Entry) {
        val on = Repo.toggleWhite(e.key)
        entries = Repo.withPins(entries)
        render()
        toast(if (on) "已加入白名单" else "已移出白名单")
    }

    /** 长按：置顶 / 取消置顶 */
    private fun onLongTap(e: Entry) {
        val on = Repo.togglePin(e.key)
        entries = Repo.withPins(entries)
        render()
        toast(if (on) "已置顶" else "已取消置顶")
    }
}

private class EntryAdapter(
    private val onTap: (Entry) -> Unit,
    private val onLong: (Entry) -> Unit
) : ListAdapter<Entry, EntryAdapter.VH>(EntryAdapter.Diff) {

    class VH(val label: TextView) : RecyclerView.ViewHolder(label)

    object Diff : DiffUtil.ItemCallback<Entry>() {
        override fun areItemsTheSame(a: Entry, b: Entry) = a.key == b.key
        override fun areContentsTheSame(a: Entry, b: Entry) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val label = TextView(ctx).apply {
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (52 * d).toInt()
            val p = (20 * d).toInt()
            setPadding(p, 0, p, 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        return VH(label)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        val mark = (if (e.pinned) "★ " else "") + (if (e.whitelisted) "🔒 " else "")
        holder.label.text = mark + e.name
        holder.label.alpha = if (e.frozen) 0.4f else 1f
        holder.label.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onTap(getItem(p))
        }
        holder.label.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(getItem(p))
            true
        }
    }
}
