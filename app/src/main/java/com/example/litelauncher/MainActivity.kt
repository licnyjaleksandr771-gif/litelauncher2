package com.example.litelauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class AppInfo(val label: String, val component: ComponentName)

class MainActivity : Activity() {

    private companion object {
        const val FAV_COUNT = 6      // сколько избранных приложений показывать
        const val ALL_SPAN = 6       // колонок в сетке «Все приложения»
        const val PREFS = "launch_counts"
    }

    private lateinit var main: View
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var rvFav: RecyclerView
    private lateinit var rvAll: RecyclerView
    private lateinit var btnAll: TextView
    private lateinit var prefs: SharedPreferences

    // Один фоновый поток для загрузки списка и иконок; живёт только пока жива Activity
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val iconCache = LruCache<String, Drawable>(48)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    private var allApps: List<AppInfo> = emptyList()
    private var favApps: List<AppInfo> = emptyList()
    private var lastFavPos = 0

    // ---- Фокус: явные правила, чтобы фокус никогда не терялся ----
    private val favAdapter = AppAdapter { pos, key ->
        lastFavPos = pos
        when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> true                              // выше ничего нет — остаёмся
            KeyEvent.KEYCODE_DPAD_DOWN -> { btnAll.requestFocus(); true } // вниз — на кнопку
            else -> false                                                 // влево/вправо — стандартно
        }
    }
    private val allAdapter = AppAdapter { pos, key ->
        key == KeyEvent.KEYCODE_DPAD_UP && pos < ALL_SPAN                 // верхний ряд: вверх не уходим
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        main = findViewById(R.id.main)
        tvTime = findViewById(R.id.tvTime)
        tvDate = findViewById(R.id.tvDate)
        rvFav = findViewById(R.id.rvFav)
        rvAll = findViewById(R.id.rvAll)
        btnAll = findViewById(R.id.btnAll)

        rvFav.layoutManager = GridLayoutManager(this, FAV_COUNT)
        rvFav.adapter = favAdapter
        rvFav.itemAnimator = null
        rvFav.setHasFixedSize(true)

        rvAll.layoutManager = GridLayoutManager(this, ALL_SPAN)
        rvAll.adapter = allAdapter
        rvAll.itemAnimator = null
        rvAll.setHasFixedSize(true)

        btnAll.setOnClickListener { showAll() }
        btnAll.setOnFocusChangeListener { v, has -> scaleOnFocus(v, has) }
        btnAll.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { focusFavorite(); true }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> true                       // дальше идти некуда
                else -> false
            }
        }
    }

    // Работаем только пока лаунчер виден: подписка на время — в onStart/onStop
    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(clockReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(clockReceiver, f)
        }
        updateClock()
        loadApps()
    }

    override fun onStop() {
        unregisterReceiver(clockReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // Повторное нажатие Home — закрыть «Все приложения»
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (rvAll.visibility == View.VISIBLE) hideAll()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Лаунчер не закрывается по Back; закрываем только оверлей
        if (rvAll.visibility == View.VISIBLE) hideAll()
    }

    // ---------------- Часы ----------------

    private fun updateClock() {
        val tz = TimeZone.getDefault()
        timeFmt.timeZone = tz
        dateFmt.timeZone = tz
        val now = Date()
        tvTime.text = timeFmt.format(now)
        tvDate.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
    }

    // ---------------- Список приложений ----------------

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val seen = HashSet<String>()
            val list = ArrayList<AppInfo>()
            // Сначала TV-приложения, затем обычные (на ТВ-приставках часто только LAUNCHER)
            for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
                val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
                for (ri in pm.queryIntentActivities(q, 0)) {
                    val ai = ri.activityInfo ?: continue
                    if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
                    list.add(AppInfo(ri.loadLabel(pm).toString(), ComponentName(ai.packageName, ai.name)))
                }
            }
            list.sortBy { it.label.lowercase() }

            // Избранное = самые часто запускаемые; недостающее добиваем по алфавиту
            val counts = prefs.all
            fun cnt(a: AppInfo) = (counts[a.component.packageName] as? Int) ?: 0
            val fav = list.filter { cnt(it) > 0 }
                .sortedByDescending { cnt(it) }
                .take(FAV_COUNT)
                .toMutableList()
            for (a in list) {
                if (fav.size >= FAV_COUNT) break
                if (a !in fav) fav.add(a)
            }

            ui.post {
                if (isDestroyed) return@post
                if (list != allApps) {
                    allApps = list
                    allAdapter.items = list
                    allAdapter.notifyDataSetChanged()
                }
                if (fav != favApps) {
                    favApps = fav
                    favAdapter.items = fav
                    favAdapter.notifyDataSetChanged()
                }
                // Если фокус потерян (первый запуск / обновление списка) — вернуть на избранное
                rvFav.post {
                    if (currentFocus == null && rvAll.visibility != View.VISIBLE) focusFavorite()
                }
            }
        }
    }

    private fun launch(app: AppInfo) {
        val pkg = app.component.packageName
        prefs.edit().putInt(pkg, prefs.getInt(pkg, 0) + 1).apply()
        val i = Intent(Intent.ACTION_MAIN)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
        } catch (_: Exception) {
            // приложение удалено/недоступно — игнорируем
        }
    }

    // ---------------- Фокус и оверлей ----------------

    private fun focusFavorite() {
        val lm = rvFav.layoutManager as GridLayoutManager
        val pos = lastFavPos.coerceIn(0, (favApps.size - 1).coerceAtLeast(0))
        (lm.findViewByPosition(pos) ?: rvFav.getChildAt(0))?.requestFocus()
    }

    private fun showAll() {
        main.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        rvAll.visibility = View.VISIBLE
        rvAll.scrollToPosition(0)
        rvAll.post { rvAll.getChildAt(0)?.requestFocus() }
    }

    private fun hideAll() {
        rvAll.visibility = View.GONE
        main.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        btnAll.requestFocus()
    }

    private fun scaleOnFocus(v: View, hasFocus: Boolean) {
        val s = if (hasFocus) 1.05f else 1f
        v.scaleX = s
        v.scaleY = s
    }

    // ---------------- Адаптер (общий для обеих сеток) ----------------

    private inner class AppAdapter(
        private val onKey: (position: Int, keyCode: Int) -> Boolean
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        var items: List<AppInfo> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val label: TextView = v.findViewById(R.id.label)
            var key: String? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_app, parent, false)
            val vh = VH(v)
            v.setOnClickListener {
                items.getOrNull(vh.bindingAdapterPosition)?.let { launch(it) }
            }
            v.setOnKeyListener { _, code, ev ->
                ev.action == KeyEvent.ACTION_DOWN && onKey(vh.bindingAdapterPosition, code)
            }
            v.setOnFocusChangeListener { view, has -> scaleOnFocus(view, has) }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val app = items[position]
            val key = app.component.flattenToShortString()
            h.label.text = app.label
            h.key = key

            val cached = iconCache.get(key)
            if (cached != null) {
                h.icon.setImageDrawable(cached.constantState?.newDrawable() ?: cached)
                return
            }
            h.icon.setImageDrawable(null)
            // Иконка грузится асинхронно напрямую из PackageManager
            io.execute {
                val d = try {
                    packageManager.getActivityIcon(app.component)
                } catch (_: Exception) {
                    return@execute
                }
                iconCache.put(key, d)
                ui.post {
                    if (h.key == key) h.icon.setImageDrawable(d.constantState?.newDrawable() ?: d)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
