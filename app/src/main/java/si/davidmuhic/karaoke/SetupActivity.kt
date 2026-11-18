package si.davidmuhic.karaoke

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Setup screen: grants permissions and edits settings. Built in code to keep the project small. */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private val statusRows = mutableListOf<Pair<TextView, (Context) -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 20))
            addView(column)
        })

        column.addView(text("Karaoke for AGAMA", 26f, bold = true))
        column.addView(text("Full-screen synced lyrics for whatever is playing.", 15f, dim = true))

        column.addView(section("Permissions"))
        column.addView(permissionRow("Notification access", "Needed to see what's playing", Permissions::notificationAccess) {
            open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        column.addView(permissionRow("Display over other apps", "Needed to draw lyrics over AGAMA", Permissions::overlay) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        column.addView(permissionRow("Location", "Needed to hide lyrics while driving", Permissions::location) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        })
        if (Build.VERSION.SDK_INT >= 33) {
            column.addView(permissionRow("Notifications", "Optional: shows a control notification", Permissions::postNotifications) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            })
        }
        column.addView(permissionRow("No battery restrictions", "Stops the system from killing the app", Permissions::batteryUnrestricted) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        column.addView(
            text(
                "Tip: many head units also have their own \"app whitelist\" or \"keep alive\" list — add Karaoke there too.",
                13f,
                dim = true,
            ),
        )

        column.addView(section("Settings"))
        column.addView(toggle("Hide lyrics while driving", prefs.speedGate) { prefs.speedGate = it })
        column.addView(toggle("Show lyrics automatically when stopped", prefs.autoShow) { prefs.autoShow = it })
        column.addView(toggle("Start when the head unit boots", prefs.autoStart) { prefs.autoStart = it })
        column.addView(fontSizeSlider())

        column.addView(section("Use"))
        column.addView(
            text(
                "Add the \"Karaoke\" app as a shortcut in AGAMA (long-press an empty slot). Tapping it shows or hides the lyrics; tap the lyrics to close them.",
                14f,
            ),
        )
        column.addView(button("Show lyrics now") {
            if (Permissions.essentials(this)) KaraokeService.send(this, KaraokeService.ACTION_SHOW)
        })
        column.addView(button("Stop background service") {
            startService(KaraokeService.intent(this, KaraokeService.ACTION_QUIT))
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        if (Permissions.essentials(this) && !KaraokeService.running) {
            KaraokeService.send(this, KaraokeService.ACTION_START)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatuses()
        // Restart so the service picks up location for the speed gate.
        if (requestCode == 1 && KaraokeService.running) {
            startService(KaraokeService.intent(this, KaraokeService.ACTION_QUIT))
            window.decorView.postDelayed({ KaraokeService.send(this, KaraokeService.ACTION_START) }, 500)
        }
    }

    private fun refreshStatuses() {
        for ((label, check) in statusRows) {
            val ok = check(this)
            label.text = if (ok) "✓" else "✗"
            label.setTextColor(if (ok) Color.rgb(90, 210, 120) else Color.rgb(255, 107, 74))
        }
    }

    /** Opens a settings screen, falling back to the app's info page on head units that lack it. */
    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun permissionRow(title: String, subtitle: String, check: (Context) -> Boolean, onGrant: () -> Unit): View {
        val status = text("", 22f, bold = true).apply {
            minWidth = dp(32)
            gravity = Gravity.CENTER
        }
        statusRows += status to check
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, bold = true))
            addView(text(subtitle, 13f, dim = true))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(status)
            addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "Grant"
                setOnClickListener { onGrant() }
            })
        }
    }

    private fun toggle(title: String, initial: Boolean, onChange: (Boolean) -> Unit): View =
        Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = initial
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                if (KaraokeService.running) KaraokeService.send(context, KaraokeService.ACTION_START)
            }
        }

    private fun fontSizeSlider(): View {
        val label = text("", 16f)
        fun update(scale: Float) {
            label.text = "Lyrics size: ${(scale * 100).toInt()}%"
        }
        update(prefs.fontScale)
        val slider = SeekBar(this).apply {
            max = 90 // 70% … 160%
            progress = ((prefs.fontScale - 0.7f) * 100).toInt().coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    val scale = 0.7f + value / 100f
                    prefs.fontScale = scale
                    update(scale)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {
                    if (KaraokeService.running) KaraokeService.send(this@SetupActivity, KaraokeService.ACTION_START)
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(label)
            addView(slider)
        }
    }

    private fun section(title: String): View = text(title.uppercase(), 13f, bold = true).apply {
        setTextColor(Color.rgb(255, 107, 74))
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun button(title: String, onClick: () -> Unit): View = Button(this).apply {
        text = title
        setOnClickListener { onClick() }
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, dim: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(if (dim) Color.rgb(160, 160, 165) else Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
