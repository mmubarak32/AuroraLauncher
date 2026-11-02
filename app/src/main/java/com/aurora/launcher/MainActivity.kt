package com.aurora.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class AppItem(val label: String, val pkg: String, val icon: Drawable)

class MainActivity : Activity() {
    private var all = listOf<AppItem>()
    private val shown = mutableListOf<AppItem>()
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var favGrid: RecyclerView
    private lateinit var dock: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var batteryBar: ProgressBar
    private var drawerOpen = false
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var detector: GestureDetector

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this).apply { setBackgroundColor(0x66101030) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); i
        }
        buildHome(); buildDrawer()
        setContentView(root)
        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!drawerOpen && vy < -1200) { openDrawer(); return true }
                return false
            }
            override fun onLongPress(e: MotionEvent) {
                if (!drawerOpen) startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { detector.onTouchEvent(ev); return super.dispatchTouchEvent(ev) }
    override fun onResume() { super.onResume(); loadApps(); registerReceiver(batt, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    override fun onPause() { super.onPause(); try { unregisterReceiver(batt) } catch (_: Exception) {} }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); if (drawerOpen) closeDrawer() }
    @Deprecated("") override fun onBackPressed() { if (drawerOpen) closeDrawer() }

    private val batt = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryText.text = "Battery  $p%"; batteryBar.progress = p
        }
    }

    // ---------- UI building ----------
    private fun glass(r: Int, color: Int = 0x33FFFFFF) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); setStroke(dp(1), 0x44FFFFFF)
    }

    private fun txt(t: String, size: Float, c: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildHome() {
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(12)) }
        // Clock widget
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(30); setPadding(dp(24), dp(18), dp(24), dp(18)) }
        clock.addView(TextClock(this).apply { format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 64f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) })
        clock.addView(TextClock(this).apply { format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"; textSize = 16f; setTextColor(0xCCFFFFFF.toInt()) })
        home.addView(clock, LinearLayout.LayoutParams(-1, -2))
        // Battery + storage widgets
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(24); setPadding(dp(18), dp(14), dp(18), dp(14)) }
        batteryText = txt("Battery", 15f, bold = true); batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bc.addView(batteryText); bc.addView(batteryBar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(10) })
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes / 1_000_000_000
        val sc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = glass(24); setPadding(dp(18), dp(14), dp(18), dp(14)) }
        sc.addView(txt("Storage", 15f, bold = true)); sc.addView(txt("$free GB free", 20f).apply { setPadding(0, dp(6), 0, 0) })
        row.addView(bc, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(sc, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        home.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        // Favourite big icons
        favGrid = RecyclerView(this).apply { layoutManager = GridLayoutManager(context, 4); overScrollMode = View.OVER_SCROLL_NEVER; isNestedScrollingEnabled = false }
        home.addView(favGrid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        // Dock
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = glass(32, 0x44FFFFFF); setPadding(dp(10), dp(12), dp(10), dp(12)) }
        home.addView(dock, LinearLayout.LayoutParams(-1, -2))
        home.addView(txt("Swipe up for all apps", 12f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildDrawer() {
        drawer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(0xE6101028.toInt()); setPadding(dp(14), dp(18), dp(14), 0) }
        val search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); background = glass(22); setPadding(dp(18), dp(12), dp(18), dp(12)); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { filter(e.toString()) }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        drawerAdapter = AppAdapter(shown)
        val rv = RecyclerView(this).apply { layoutManager = GridLayoutManager(context, 4); adapter = drawerAdapter; clipToPadding = false; setPadding(0, dp(12), 0, dp(40)) }
        drawer.addView(search, LinearLayout.LayoutParams(-1, -2))
        drawer.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------- Data ----------
    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        all = packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.loadIcon(packageManager)) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        filter("")
        val dockPk = listOf(
            pkgFor(Intent(Intent.ACTION_DIAL)), pkgFor(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:"))),
            pkgFor(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://"))), pkgFor(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        ).filterNotNull().distinct()
        val dockApps = dockPk.mapNotNull { p -> all.find { it.pkg == p } }.take(4)
        dock.removeAllViews()
        dockApps.forEach { dock.addView(iconView(it, 66, false), LinearLayout.LayoutParams(0, -2, 1f)) }
        favGrid.adapter = AppAdapter(all.filter { a -> dockApps.none { it.pkg == a.pkg } }.take(8))
    }

    private fun pkgFor(i: Intent) = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        ?.takeIf { it != "android" }

    private fun filter(q: String) {
        shown.clear(); shown.addAll(all.filter { it.label.contains(q, true) }); drawerAdapter.notifyDataSetChanged()
    }

    // ---------- Big 3D-tilt icon ----------
    private fun iconView(app: AppItem, size: Int, label: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(8)); cameraDistance = 9000 * d }
        val img = ImageView(this).apply {
            setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4)); background = glass(size / 3, 0x22FFFFFF)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(size) / 3f) } }
            clipToOutline = true; elevation = dp(6).toFloat()
        }
        box.addView(img, LinearLayout.LayoutParams(dp(size), dp(size)))
        if (label) box.addView(txt(app.label, 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val x = (e.x / v.width - .5f) * 2; val y = (e.y / v.height - .5f) * 2
                    v.rotationY = x * 20; v.rotationX = -y * 20; v.scaleX = .9f; v.scaleY = .9f
                }
                MotionEvent.ACTION_UP -> { reset(v); if (e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()) launch(app, v) }
                MotionEvent.ACTION_CANCEL -> reset(v)
            }
            true
        }
        return box
    }

    private fun reset(v: View) { v.animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.5f)).setDuration(350).start() }

    private fun launch(app: AppItem, v: View) {
        val i = packageManager.getLaunchIntentForPackage(app.pkg) ?: return
        startActivity(i, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle())
        if (drawerOpen) closeDrawer()
    }

    // ---------- 3D drawer transition ----------
    private fun openDrawer() {
        drawerOpen = true
        drawer.apply { visibility = View.VISIBLE; cameraDistance = 12000 * d; pivotY = height.toFloat(); rotationX = 25f; translationY = height * .4f; alpha = 0f
            animate().rotationX(0f).translationY(0f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(.8f)).start() }
        home.animate().scaleX(.9f).scaleY(.9f).alpha(.3f).setDuration(380).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.animate().rotationX(25f).translationY(drawer.height * .4f).alpha(0f).setDuration(280).withEndAction { drawer.visibility = View.GONE }.start()
        home.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).start()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(drawer.windowToken, 0)
    }

    inner class AppAdapter(private val list: List<AppItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(FrameLayout(p.context)) {}
        override fun getItemCount() = list.size
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val f = h.itemView as FrameLayout; f.removeAllViews(); f.addView(iconView(list[pos], 72, true))
            f.layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
    }
}
