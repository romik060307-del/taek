package com.rk.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import org.json.JSONArray
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class AppEntry(val label: String, val packageName: String)
    private enum class Screen { HOME, APPS, CONTROL, SETTINGS, NOTIFICATIONS, LOCK_PREVIEW }

    private val prefs by lazy { getSharedPreferences("rk_launcher", Context.MODE_PRIVATE) }
    private val appLoader = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val allApps = mutableListOf<AppEntry>()
    private val iconCache = object : LruCache<String, Drawable.ConstantState>(48) {}
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var dock: LinearLayout
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var islandView: TextView
    private lateinit var adapter: AppAdapter

    private var screen = Screen.HOME
    private var appSearchQuery = ""
    private var islandExpanded = false
    private var torchEnabled = false
    private var torchCameraId: String? = null
    private var batteryLevel = -1
    private var isLoadingApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 13, 26)
        window.navigationBarColor = Color.rgb(8, 13, 26)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        adapter = AppAdapter()
        buildShell()
        renderHome()
        updateClock()
        readBatteryLevel()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(clockTick)
        mainHandler.post(clockTick)
        readBatteryLevel()
        if (allApps.isEmpty() && !isLoadingApps) loadApps()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockTick)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clockTick)
        appLoader.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            else -> renderHome()
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        setContentView(root)
        applyWallpaper()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(10))
        }
        val clockStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockView = TextView(this).apply {
            text = "--:--"
            textSize = 31f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
        }
        dateView = TextView(this).apply {
            text = "RK Launcher"
            textSize = 12f
            setTextColor(0xFFD0D9EE.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        clockStack.addView(clockView)
        clockStack.addView(dateView)
        header.addView(clockStack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        islandView = TextView(this).apply {
            text = "● RK"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(Color.rgb(3, 5, 11), 40)
            setOnClickListener { toggleIsland() }
            contentDescription = "Dynamic Island simulasi RK Launcher"
        }
        header.addView(islandView)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = rounded(0x443A4B70, 28)
        }
        val dockParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76))
        dockParams.setMargins(0, dp(8), 0, dp(5))
        root.addView(dock, dockParams)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(1))
        }
        nav.addView(navItem("⌂", "Home") { renderHome() }, weightedParams())
        nav.addView(navItem("▦", "Apps") { renderApps() }, weightedParams())
        nav.addView(navItem("☷", "Pusat") { renderControlCenter() }, weightedParams())
        nav.addView(navItem("⚙", "Setelan") { renderSettings() }, weightedParams())
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun weightedParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    private fun navItem(symbol: String, label: String, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener { action() }
        }
        box.addView(TextView(this).apply {
            text = symbol
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFFD5DDF0.toInt())
        })
        return box
    }

    private fun updateClock() {
        if (!::clockView.isInitialized) return
        val now = Date()
        clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(now)
        if (islandExpanded && ::islandView.isInitialized && prefs.getBoolean("island_enabled", true)) {
            islandView.text = if (batteryLevel >= 0) "RK  •  $batteryLevel%" else "RK Launcher"
        }
    }

    private fun readBatteryLevel() {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryLevel = (level * 100) / scale
        }
        if (::islandView.isInitialized) updateClock()
    }

    private fun toggleIsland() {
        if (!prefs.getBoolean("island_enabled", true)) {
            Toast.makeText(this, "Dynamic Island simulasi dimatikan di Setelan", Toast.LENGTH_SHORT).show()
            return
        }
        islandExpanded = !islandExpanded
        islandView.text = if (islandExpanded) {
            if (batteryLevel >= 0) "RK  •  $batteryLevel%" else "RK Launcher"
        } else "● RK"
    }

    private fun applyWallpaper() {
        if (!::root.isInitialized) return
        val colors = when (prefs.getString("wallpaper", "ocean")) {
            "plum" -> intArrayOf(0xFF100F27.toInt(), 0xFF3D2B67.toInt(), 0xFF8D5BB7.toInt())
            "mono" -> intArrayOf(0xFF080B12.toInt(), 0xFF252D3D.toInt(), 0xFF546078.toInt())
            else -> intArrayOf(0xFF070B15.toInt(), 0xFF11264C.toInt(), 0xFF2464A8.toInt())
        }
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = 0f }
    }

    private fun loadApps() {
        if (isLoadingApps) return
        isLoadingApps = true
        appLoader.execute {
            val found = LinkedHashMap<String, AppEntry>()
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val resolved = packageManager.queryIntentActivities(intent, 0)
                for (info in resolved) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == packageName || found.containsKey(pkg)) continue
                    val label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { pkg }
                    found[pkg] = AppEntry(label, pkg)
                }
            } catch (_: Exception) {
                // Keep the launcher usable even if an OEM package manager behaves unexpectedly.
            }
            val result = found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            mainHandler.post {
                isLoadingApps = false
                if (isFinishing || isDestroyed) return@post
                allApps.clear()
                allApps.addAll(result)
                pruneAndSeedFavorites()
                iconCache.evictAll()
                when (screen) {
                    Screen.HOME -> renderHome()
                    Screen.APPS -> renderApps()
                    else -> updateDock()
                }
            }
        }
    }

    private fun favoritePackages(): MutableList<String> {
        return prefs.getString("favorites", "")
            .orEmpty()
            .split('|')
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
    }

    private fun saveFavoritePackages(packages: List<String>) {
        prefs.edit().putString("favorites", packages.distinct().joinToString("|")).apply()
    }

    private fun pruneAndSeedFavorites() {
        val installed = allApps.map { it.packageName }.toSet()
        val saved = favoritePackages().filter { installed.contains(it) }.toMutableList()
        if (saved.isEmpty()) saved.addAll(allApps.take(8).map { it.packageName })
        for (entry in allApps) {
            if (saved.size >= 8) break
            if (!saved.contains(entry.packageName)) saved.add(entry.packageName)
        }
        saveFavoritePackages(saved.take(8))
    }

    private fun favorites(max: Int = 8): List<AppEntry> {
        val map = allApps.associateBy { it.packageName }
        return favoritePackages().mapNotNull { map[it] }.take(max)
    }

    private fun renderHome() {
        screen = Screen.HOME
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(6), dp(1), dp(4))
        }
        page.addView(TextView(this).apply {
            text = "APLIKASI FAVORIT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(3), dp(5), dp(2))
        })
        page.addView(TextView(this).apply {
            text = "Tekan lama ikon untuk mengatur favorit"
            textSize = 11f
            setTextColor(0xFFCAD5EC.toInt())
            setPadding(dp(5), 0, dp(5), dp(8))
        })
        val grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(4)
            verticalSpacing = dp(5)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = AppAdapter().also { it.setItems(favorites(8)) }
            setOnItemClickListener { parent, _, position, _ ->
                val selected = parent.adapter.getItem(position) as? AppEntry
                selected?.let { openApp(it) }
            }
            setOnItemLongClickListener { parent, _, position, _ ->
                (parent.adapter.getItem(position) as? AppEntry)?.let { showAppOptions(it) }
                true
            }
        }
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(218)))

        val hintCard = TextView(this).apply {
            text = "⌕   Cari aplikasi, buka panel cepat, atau atur tampilan dari menu bawah."
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(0x332D4168, 18)
            setOnClickListener { renderApps() }
        }
        val hintParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hintParams.setMargins(dp(2), dp(8), dp(2), 0)
        page.addView(hintCard, hintParams)

        val note = TextView(this).apply {
            text = if (allApps.isEmpty()) "Memuat aplikasi…" else "${allApps.size} aplikasi tersedia • Android Oreo ke atas"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9C5DE.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        page.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentFrame.removeAllViews()
        contentFrame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateDock()
        updateIslandVisibility()
    }

    private fun renderApps() {
        screen = Screen.APPS
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Semua Aplikasi"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(actionChip("↻ Muat ulang") { loadApps() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        page.addView(titleRow)

        val search = EditText(this).apply {
            hint = "Cari aplikasi…"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFB6C3DD.toInt())
            background = rounded(0x443C527B, 18)
            setPadding(dp(14), dp(2), dp(14), dp(2))
            setText(appSearchQuery)
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        searchParams.setMargins(0, dp(11), 0, dp(10))
        page.addView(search, searchParams)

        val grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(4)
            verticalSpacing = dp(5)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = this@MainActivity.adapter
            setOnItemClickListener { parent, _, position, _ ->
                (parent.adapter.getItem(position) as? AppEntry)?.let { openApp(it) }
            }
            setOnItemLongClickListener { parent, _, position, _ ->
                (parent.adapter.getItem(position) as? AppEntry)?.let { showAppOptions(it) }
                true
            }
        }
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val empty = TextView(this).apply {
            text = "Aplikasi tidak ditemukan"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        page.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        grid.emptyView = empty

        contentFrame.removeAllViews()
        contentFrame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        adapter.setItems(filterApps(appSearchQuery))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appSearchQuery = s?.toString().orEmpty()
                adapter.setItems(filterApps(appSearchQuery))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateDock()
        updateIslandVisibility()
    }

    private fun filterApps(query: String): List<AppEntry> {
        if (query.isBlank()) return allApps.toList()
        return allApps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    private fun renderControlCenter() {
        screen = Screen.CONTROL
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(12))
        }
        page.addView(sectionTitle("Pusat Kontrol"))
        page.addView(smallText("Panel RK Launcher. Beberapa kontrol membuka Setelan Android karena sistem membatasi perubahan langsung."))

        val brightLabel = TextView(this).apply {
            text = "Kecerahan layar launcher"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(10), 0, 0)
        }
        page.addView(brightLabel)
        val brightness = SeekBar(this).apply {
            max = 100
            progress = 60
        }
        page.addView(brightness, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val attributes = window.attributes
                    attributes.screenBrightness = (0.15f + (progress / 100f) * 0.85f).coerceIn(0.15f, 1f)
                    window.attributes = attributes
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        page.addView(smallText("Kecerahan ini hanya berlaku pada jendela launcher; tidak mengubah kecerahan sistem secara global."))

        addActionRow(page,
            "Wi-Fi" to { openSettings(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            "Bluetooth" to { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        )
        addActionRow(page,
            "Mode pesawat" to { openSettings(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) },
            "Layar" to { openSettings(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        )
        addActionRow(page,
            "Volume −" to { changeVolume(false) },
            "Volume +" to { changeVolume(true) }
        )
        addActionRow(page,
            (if (torchEnabled) "Matikan senter" else "Nyalakan senter") to { requestOrToggleTorch() },
            "Baterai" to { openSettings(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
        )
        addActionRow(page,
            "Suara" to { openSettings(Intent(Settings.ACTION_SOUND_SETTINGS)) },
            "Pusat notifikasi" to { renderNotifications() }
        )
        addActionRow(page,
            "Jangan Ganggu" to { openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            "Setelan Android" to { openSettings(Intent(Settings.ACTION_SETTINGS)) }
        )

        val warning = TextView(this).apply {
            text = "Wi-Fi, Bluetooth, mode pesawat, dan Jangan Ganggu mengikuti batasan Android. RK Launcher tidak memalsukan status sistem."
            textSize = 11f
            setTextColor(0xFFD1DBEF.toInt())
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(0x332D4168, 16)
        }
        val warningParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        warningParams.setMargins(0, dp(10), 0, 0)
        page.addView(warning, warningParams)
        scroll.addView(page)
        contentFrame.removeAllViews()
        contentFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateDock()
        updateIslandVisibility()
    }

    private fun renderSettings() {
        screen = Screen.SETTINGS
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(14))
        }
        page.addView(sectionTitle("Setelan RK Launcher"))
        page.addView(smallText("Tampilan sederhana, pengaturan lokal, dan fitur opsional."))

        page.addView(settingToggle("Mode Lite", "Mengutamakan tampilan ringan dan mematikan efek dekoratif opsional.", prefs.getBoolean("lite_mode", true)) { checked ->
            prefs.edit().putBoolean("lite_mode", checked).apply()
        })
        page.addView(settingToggle("Dynamic Island simulasi", "Menampilkan pil RK di bagian atas Home Screen.", prefs.getBoolean("island_enabled", true)) { checked ->
            prefs.edit().putBoolean("island_enabled", checked).apply()
            if (!checked) islandExpanded = false
            updateIslandVisibility()
        })
        page.addView(settingToggle("Animasi tambahan", "Versi ini menggunakan animasi minimal untuk menjaga responsivitas.", prefs.getBoolean("animations_enabled", false)) { checked ->
            prefs.edit().putBoolean("animations_enabled", checked).apply()
        })

        page.addView(sectionTitle("Wallpaper bawaan"))
        addActionRow(page,
            "Ocean Blue" to { setWallpaper("ocean") },
            "Plum Glass" to { setWallpaper("plum") }
        )
        addActionRow(page,
            "Mono Dark" to { setWallpaper("mono") },
            "Pratinjau Lock Screen" to { renderLockPreview() }
        )
        addActionRow(page,
            "Pilih launcher default" to { openSettings(Intent(Settings.ACTION_HOME_SETTINGS)) },
            "Info aplikasi" to { openAppInfo() }
        )
        addActionRow(page,
            "Pusat notifikasi" to { renderNotifications() },
            "Setelan notifikasi" to { openSettings(Intent(Settings.ACTION_NOTIFICATION_SETTINGS)) }
        )
        addActionRow(page,
            "Muat ulang aplikasi" to { loadApps() },
            "Info aplikasi" to { openAppInfo() }
        )

        val privacy = TextView(this).apply {
            text = "Privasi: daftar aplikasi diproses di perangkat. Launcher ini tidak memerlukan akun atau koneksi internet untuk fitur intinya. Senter hanya meminta izin kamera saat tombol senter ditekan."
            textSize = 11f
            setTextColor(0xFFD1DBEF.toInt())
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(0x332D4168, 16)
        }
        val privacyParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        privacyParams.setMargins(0, dp(10), 0, 0)
        page.addView(privacy, privacyParams)
        page.addView(actionChip("Tentang RK Launcher · v1.0.0") {
            AlertDialog.Builder(this)
                .setTitle("RK Launcher")
                .setMessage("Launcher Android bergaya iOS, dibuat dengan Kotlin native. Minimum Android 8.0 Oreo. Ini bukan iOS asli dan tidak dapat menggantikan seluruh fitur sistem Apple.")
                .setPositiveButton("Mengerti", null)
                .show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        scroll.addView(page)
        contentFrame.removeAllViews()
        contentFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateDock()
        updateIslandVisibility()
    }

    private fun renderNotifications() {
        screen = Screen.NOTIFICATIONS
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(12))
        }
        page.addView(sectionTitle("Pusat Notifikasi"))
        page.addView(smallText("Notifikasi ditampilkan hanya setelah kamu mengaktifkan akses khusus Android. Isi notifikasi disimpan lokal di perangkat."))

        val enabled = try {
            val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            listeners.split(':').mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it.packageName == packageName }
        } catch (_: Exception) { false }

        addActionRow(page,
            (if (enabled) "Akses notifikasi aktif" else "Aktifkan akses notifikasi") to {
                openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            "Hapus daftar lokal" to {
                prefs.edit().putString(RKNotificationListener.KEY, "[]").apply()
                renderNotifications()
            }
        )

        val items = try { JSONArray(prefs.getString(RKNotificationListener.KEY, "[]")) } catch (_: Exception) { JSONArray() }
        if (items.length() == 0) {
            val empty = TextView(this).apply {
                text = if (enabled) "Belum ada notifikasi aktif yang tersedia." else "Akses belum aktif. Kamu tetap bisa menggunakan launcher tanpa memberikan izin ini."
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(28), dp(18), dp(28))
                background = rounded(0x332D4168, 18)
            }
            page.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            for (i in items.length() - 1 downTo 0) {
                val item = items.optJSONObject(i) ?: continue
                val pkg = item.optString("package")
                val title = item.optString("title", "Notifikasi")
                val message = item.optString("text")
                val time = item.optLong("when", 0L)
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                    background = rounded(0x443C527B, 16)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val launchIntent = try { packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
                        if (launchIntent != null) {
                            try { startActivity(launchIntent) } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Aplikasi asal notifikasi tidak tersedia", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                tile.addView(TextView(this).apply {
                    text = title
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                })
                if (message.isNotBlank()) tile.addView(TextView(this).apply {
                    text = message
                    textSize = 12f
                    setTextColor(0xFFE0E7F5.toInt())
                    setPadding(0, dp(3), 0, dp(3))
                })
                tile.addView(TextView(this).apply {
                    val whenText = if (time > 0L) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)) else ""
                    text = if (whenText.isNotBlank()) "$pkg  •  $whenText" else pkg
                    textSize = 9f
                    setTextColor(0xFFB9C5DE.toInt())
                })
                val tileParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tileParams.setMargins(0, dp(4), 0, dp(4))
                page.addView(tile, tileParams)
            }
        }
        scroll.addView(page)
        contentFrame.removeAllViews()
        contentFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateDock()
        updateIslandVisibility()
    }

    private fun renderLockPreview() {
        screen = Screen.LOCK_PREVIEW
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(0x443C527B, 28)
        }
        page.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(Date())
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        page.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 58f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(12))
        })
        page.addView(TextView(this).apply {
            text = "Pratinjau tampilan saja\nKeamanan perangkat tetap menggunakan lock screen Android."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFDCE5F6.toInt())
        })
        val back = actionChip("Kembali") { renderSettings() }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        params.setMargins(0, dp(25), 0, 0)
        page.addView(back, params)
        contentFrame.removeAllViews()
        contentFrame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateDock()
    }

    private fun updateDock() {
        if (!::dock.isInitialized) return
        dock.removeAllViews()
        val entries = favorites(4)
        if (entries.isEmpty()) {
            dock.addView(TextView(this).apply {
                text = "RK Launcher"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        for (entry in entries) {
            val tile = AppTile(this, 30, 9f, 64)
            tile.bind(entry)
            tile.setOnClickListener { openApp(entry) }
            tile.setOnLongClickListener { showAppOptions(entry); true }
            dock.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun updateIslandVisibility() {
        if (::islandView.isInitialized) {
            islandView.visibility = if (prefs.getBoolean("island_enabled", true)) View.VISIBLE else View.GONE
        }
    }

    private fun showAppOptions(entry: AppEntry) {
        val favs = favoritePackages()
        val isFavorite = favs.contains(entry.packageName)
        val items = arrayOf("Buka aplikasi", if (isFavorite) "Hapus dari favorit" else "Tambahkan ke favorit", "Info aplikasi Android")
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openApp(entry)
                    1 -> {
                        val updated = favoritePackages()
                        if (updated.contains(entry.packageName)) updated.remove(entry.packageName)
                        else if (updated.size < 8) updated.add(entry.packageName)
                        else {
                            Toast.makeText(this, "Maksimal 8 aplikasi favorit", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        saveFavoritePackages(updated)
                        if (updated.isEmpty()) pruneAndSeedFavorites()
                        if (screen == Screen.HOME) renderHome() else updateDock()
                        Toast.makeText(this, "Favorit diperbarui", Toast.LENGTH_SHORT).show()
                    }
                    2 -> openAppInfo(entry.packageName)
                }
            }
            .show()
    }

    private fun openApp(entry: AppEntry) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(entry.packageName)
            if (intent == null) {
                Toast.makeText(this, "Aplikasi tidak menyediakan pintasan untuk dibuka", Toast.LENGTH_SHORT).show()
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android menolak membuka aplikasi tersebut", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Terjadi kendala saat membuka aplikasi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(pkg: String = packageName) {
        openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Halaman Setelan tidak tersedia di perangkat ini", Toast.LENGTH_SHORT).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "Akses ini dibatasi sistem Android", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setWallpaper(name: String) {
        prefs.edit().putString("wallpaper", name).apply()
        applyWallpaper()
        Toast.makeText(this, "Wallpaper RK diperbarui", Toast.LENGTH_SHORT).show()
    }

    private fun changeVolume(up: Boolean) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Kontrol volume tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOrToggleTorch() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Toast.makeText(this, "Perangkat ini tidak memiliki flash kamera", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        toggleTorch()
    }

    private fun toggleTorch() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var cameraId = torchCameraId
            if (cameraId == null) {
                cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                torchCameraId = cameraId
            }
            if (cameraId == null) {
                Toast.makeText(this, "Flash kamera tidak ditemukan", Toast.LENGTH_SHORT).show()
                return
            }
            cameraManager.setTorchMode(cameraId, !torchEnabled)
            torchEnabled = !torchEnabled
            Toast.makeText(this, if (torchEnabled) "Senter aktif" else "Senter mati", Toast.LENGTH_SHORT).show()
            if (screen == Screen.CONTROL) renderControlCenter()
        } catch (_: CameraAccessException) {
            Toast.makeText(this, "Kamera sedang digunakan aplikasi lain", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Izin kamera diperlukan untuk menggunakan senter", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Senter tidak dapat diaktifkan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleTorch()
            else Toast.makeText(this, "Izin kamera tidak diberikan; senter tetap mati", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(1), dp(5), dp(1), dp(8))
    }

    private fun smallText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 11f
        setTextColor(0xFFD1DBEF.toInt())
        setPadding(dp(2), 0, dp(2), dp(8))
    }

    private fun actionChip(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(0x553F5C8B, 16)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun addActionRow(parent: LinearLayout, first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val firstView = actionChip(first.first, first.second)
        val secondView = actionChip(second.first, second.second)
        val p1 = LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(0, dp(4), dp(4), dp(4)) }
        val p2 = LinearLayout.LayoutParams(0, dp(60), 1f).apply { setMargins(dp(4), dp(4), 0, dp(4)) }
        row.addView(firstView, p1)
        row.addView(secondView, p2)
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun settingToggle(title: String, description: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(9), dp(7))
            background = rounded(0x332D4168, 16)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        labels.addView(TextView(this).apply {
            text = description
            textSize = 10f
            setTextColor(0xFFD1DBEF.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
            contentDescription = title
        }
        row.addView(toggle)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, dp(4), 0, dp(4))
        row.layoutParams = params
        return row
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class AppTile(
        context: Context,
        private val iconSizeDp: Int = 43,
        private val labelSizeSp: Float = 10.5f,
        private val tileHeightDp: Int = 91
    ) : LinearLayout(context) {
        private val iconView = ImageView(context)
        private val labelView = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(4))
            background = rounded(0x222D4168, 15)
            iconView.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(iconView, LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)))
            labelView.apply {
                gravity = Gravity.CENTER
                textSize = labelSizeSp
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
                includeFontPadding = false
            }
            addView(labelView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            contentDescription = "Ikon aplikasi"
        }

        fun bind(entry: AppEntry) {
            labelView.text = entry.label
            contentDescription = entry.label
            try {
                val state = iconCache.get(entry.packageName)
                val drawable = if (state != null) state.newDrawable(resources) else packageManager.getApplicationIcon(entry.packageName).also {
                    it.constantState?.let { cs -> iconCache.put(entry.packageName, cs) }
                }
                iconView.setImageDrawable(drawable)
            } catch (_: Exception) {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(tileHeightDp))
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        private var items: List<AppEntry> = emptyList()

        fun setItems(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tile = (convertView as? AppTile) ?: AppTile(this@MainActivity)
            tile.bind(items[position])
            return tile
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 4008
    }
}
