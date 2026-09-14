package com.ekinao.desktopmascot

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var repository: SettingsRepository
    private lateinit var overlayStatus: TextView
    private lateinit var startButton: Button
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var lowBatteryCheck: CheckBox
    private lateinit var disableBreathingCheck: CheckBox
    private lateinit var batteryThreshold: EditText
    private lateinit var accessibilityStatus: TextView
    private lateinit var defaultMascotRadio: RadioButton
    private lateinit var customMascotRadio: RadioButton
    private lateinit var mascotWarning: TextView

    private val customImages = mutableMapOf(
        MASCOT_SIT to mutableListOf<String>(),
        MASCOT_WALK_LEFT to mutableListOf<String>(),
        MASCOT_WALK_RIGHT to mutableListOf<String>(),
        MASCOT_REST to mutableListOf<String>()
    )
    private var pendingImageCategory: String? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val category = pendingImageCategory ?: return@registerForActivityResult
        pendingImageCategory = null
        if (uris.isEmpty()) return@registerForActivityResult

        lifecycleScope.launch {
            val destinationDir = File(filesDir, "${SettingsRepository.CUSTOM_MASCOT_DIR}/$category")
            destinationDir.mkdirs()

            customImages.getValue(category).clear()
            uris.forEachIndexed { index, uri ->
                try {
                    val extension = "png"
                    val file = File(destinationDir, "frame_${index + 1}.$extension")
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (file.length() > 0L) customImages.getValue(category).add(file.name)
                } catch (_: Exception) {
                    // Skip a file that could not be copied.
                }
            }
            refreshMascotImageUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this)
        buildUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        startMascotIfPermitted()
        MascotService.setAppHidden(false)
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateStartButton()
        if (::accessibilityStatus.isInitialized) updateAccessibilityStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(Color.rgb(247, 247, 247))
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = getString(R.string.app_title)
            textSize = 28f
            setTextColor(Color.rgb(32, 33, 36))
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 15f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, 0, 0, dp(18))
        })

        val permissionCard = section(getString(R.string.section_overlay))
        overlayStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        }
        permissionCard.addView(overlayStatus)
        permissionCard.addView(Button(this).apply {
            text = getString(R.string.grant_overlay)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        content.addView(permissionCard)

        val timing = section(getString(R.string.section_timing))
        addRange(timing, getString(R.string.sit_before_walking), "sitBeforeWalkMin", "sitBeforeWalkMax")
        addRange(timing, getString(R.string.walk_duration), "walkDurationMin", "walkDurationMax")
        addRange(timing, getString(R.string.sit_after_walking), "sitAfterWalkMin", "sitAfterWalkMax")
        addRange(timing, getString(R.string.rest_duration), "restMin", "restMax")
        content.addView(timing)

        val movement = section(getString(R.string.section_movement))
        addNumber(movement, getString(R.string.walking_speed), "walkSpeed")
        addNumber(movement, getString(R.string.screen_margin), "screenMargin")
        disableBreathingCheck = CheckBox(this).apply {
            text = getString(R.string.disable_breathing_animation)
            textSize = 15f
        }
        movement.addView(disableBreathingCheck)
        content.addView(movement)

        val battery = section(getString(R.string.section_battery))
        lowBatteryCheck = CheckBox(this).apply {
            text = getString(R.string.hide_low_battery)
            textSize = 15f
        }
        battery.addView(lowBatteryCheck)
        batteryThreshold = addNumber(battery, getString(R.string.battery_threshold), "batteryThreshold")
        content.addView(battery)

        val images = section(getString(R.string.section_manage_images))
        images.addView(TextView(this).apply {
            text = getString(R.string.manage_images_description)
            textSize = 14f
            setTextColor(Color.rgb(80, 80, 80))
            setPadding(0, 0, 0, dp(10))
        })

        defaultMascotRadio = RadioButton(this).apply {
            text = getString(R.string.load_default_mascot)
            textSize = 15f
            setOnClickListener {
                customMascotRadio.isChecked = false
                lifecycleScope.launch {
                    repository.resetMascotImages()
                    customImages.values.forEach { it.clear() }
                    refreshMascotImageUi()
                    Toast.makeText(this@MainActivity, getString(R.string.default_mascot_loaded), Toast.LENGTH_SHORT).show()
                }
            }
        }
        images.addView(defaultMascotRadio)

        customMascotRadio = RadioButton(this).apply {
            text = getString(R.string.use_own_mascot)
            textSize = 15f
            setOnClickListener {
                defaultMascotRadio.isChecked = false
                refreshMascotImageUi()
            }
        }
        images.addView(customMascotRadio)

        mascotWarning = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(180, 80, 40))
            setPadding(dp(32), 0, 0, dp(8))
        }
        images.addView(mascotWarning)

        addImagePickerRow(images, MASCOT_SIT, getString(R.string.mascot_sit))
        addImagePickerRow(images, MASCOT_WALK_LEFT, getString(R.string.mascot_walk_left))
        addImagePickerRow(images, MASCOT_WALK_RIGHT, getString(R.string.mascot_walk_right))
        addImagePickerRow(images, MASCOT_REST, getString(R.string.mascot_rest))
        content.addView(images)

        val homeBehavior = section(getString(R.string.section_visibility))
        accessibilityStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, 0, 0, dp(8))
        }
        homeBehavior.addView(TextView(this).apply {
            text = getString(R.string.visibility_description)
            textSize = 15f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(0, 0, 0, dp(8))
        })
        homeBehavior.addView(accessibilityStatus)
        homeBehavior.addView(Button(this).apply {
            text = getString(R.string.open_accessibility)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        content.addView(homeBehavior)

        content.addView(Button(this).apply {
            text = getString(R.string.save_settings)
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.rounded_button)
            setOnClickListener { saveSettings() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        startButton = Button(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.rounded_button)
            setOnClickListener { toggleMascot() }
        }
        content.addView(startButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        content.addView(TextView(this).apply {
            text = getString(R.string.state_cycle_note)
            textSize = 13f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(16), 0, dp(16))
        })

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            val s = repository.settings.first()
            put("sitBeforeWalkMin", s.sitBeforeWalkMin)
            put("sitBeforeWalkMax", s.sitBeforeWalkMax)
            put("walkDurationMin", s.walkDurationMin)
            put("walkDurationMax", s.walkDurationMax)
            put("sitAfterWalkMin", s.sitAfterWalkMin)
            put("sitAfterWalkMax", s.sitAfterWalkMax)
            put("restMin", s.restMin)
            put("restMax", s.restMax)
            put("walkSpeed", s.walkSpeed)
            put("screenMargin", s.screenMargin)
            disableBreathingCheck.isChecked = s.disableBreathingAnimation
            put("batteryThreshold", s.batteryThreshold)
            lowBatteryCheck.isChecked = s.hideWhenLowBattery

            customImages[MASCOT_SIT]!!.addAll(s.customSitImages)
            customImages[MASCOT_WALK_LEFT]!!.addAll(s.customWalkLeftImages)
            customImages[MASCOT_WALK_RIGHT]!!.addAll(s.customWalkRightImages)
            customImages[MASCOT_REST]!!.addAll(s.customRestImages)

            if (s.useCustomMascot) customMascotRadio.isChecked = true else defaultMascotRadio.isChecked = true
            refreshMascotImageUi()
        }
    }

    private fun addImagePickerRow(parent: LinearLayout, category: String, label: String) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(dp(32), dp(6), 0, dp(2))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            tag = "status_$category"
            textSize = 13f
            setTextColor(Color.rgb(95, 99, 104))
        }
        row.addView(status, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(Button(this).apply {
            text = getString(R.string.choose_pngs)
            setOnClickListener {
                pendingImageCategory = category
                imagePicker.launch(arrayOf("image/png"))
            }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.clear)
            setOnClickListener {
                customImages.getValue(category).clear()
                refreshMascotImageUi()
            }
        })
        parent.addView(row)
    }

    private fun refreshMascotImageUi() {
        val complete = missingCategories().isEmpty()
        if (::customMascotRadio.isInitialized && !customMascotRadio.isChecked) {
            mascotWarning.text = getString(R.string.default_mascot_active)
        } else if (::mascotWarning.isInitialized) {
            mascotWarning.text = if (complete) {
                getString(R.string.custom_mascot_ready)
            } else {
                getString(R.string.custom_mascot_incomplete, missingCategories().joinToString(", "))
            }
        }

        listOf(MASCOT_SIT, MASCOT_WALK_LEFT, MASCOT_WALK_RIGHT, MASCOT_REST).forEach { category ->
            findViewByTag<TextView>("status_$category")?.text =
                if (customImages.getValue(category).isEmpty()) {
                    getString(R.string.no_images_selected)
                } else {
                    getString(R.string.images_selected, customImages.getValue(category).size)
                }
        }
    }

    private fun missingCategories(): List<String> = buildList {
        if (customImages.getValue(MASCOT_SIT).isEmpty()) add(getString(R.string.mascot_sit))
        if (customImages.getValue(MASCOT_WALK_LEFT).isEmpty()) add(getString(R.string.mascot_walk_left))
        if (customImages.getValue(MASCOT_WALK_RIGHT).isEmpty()) add(getString(R.string.mascot_walk_right))
        if (customImages.getValue(MASCOT_REST).isEmpty()) add(getString(R.string.mascot_rest))
    }

    private fun <T : android.view.View> findViewByTag(tagValue: String): T? {
        fun search(view: android.view.View): T? {
            if (view.tag == tagValue) @Suppress("UNCHECKED_CAST") return view as T
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return search(window.decorView)
    }

    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.rounded_card)
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.rgb(32, 33, 36))
            setPadding(0, 0, 0, dp(8))
        })
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        return box
    }

    private fun addRange(parent: LinearLayout, label: String, minKey: String, maxKey: String) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(0, dp(6), 0, dp(4))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply { text = getString(R.string.minimum); textSize = 13f })
        val min = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.seconds)
            setSingleLine(true)
        }
        fields[minKey] = min
        row.addView(min, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        row.addView(TextView(this).apply { text = getString(R.string.maximum); textSize = 13f })
        val max = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.seconds)
            setSingleLine(true)
        }
        fields[maxKey] = max
        row.addView(max, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        parent.addView(row)
    }

    private fun addNumber(parent: LinearLayout, label: String, key: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(0, dp(6), 0, 0)
        })
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
        fields[key] = edit
        parent.addView(edit, LinearLayout.LayoutParams(-1, dp(52)))
        return edit
    }

    private fun updateAccessibilityStatus() {
        val expected = ComponentName(this, MascotAccessibilityService::class.java)
        val enabled = try {
            val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            raw.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
        } catch (_: Exception) { false }

        accessibilityStatus.text = if (enabled) {
            getString(R.string.accessibility_enabled)
        } else {
            getString(R.string.accessibility_disabled)
        }
    }

    private fun saveSettings() {
        val s = MascotSettings(
            sitBeforeWalkMin = int("sitBeforeWalkMin", 60),
            sitBeforeWalkMax = int("sitBeforeWalkMax", 90),
            walkDurationMin = int("walkDurationMin", 4),
            walkDurationMax = int("walkDurationMax", 10),
            sitAfterWalkMin = int("sitAfterWalkMin", 10),
            sitAfterWalkMax = int("sitAfterWalkMax", 20),
            restMin = int("restMin", 30),
            restMax = int("restMax", 90),
            walkSpeed = double("walkSpeed", 1.0),
            screenMargin = int("screenMargin", 50),
            disableBreathingAnimation = disableBreathingCheck.isChecked,
            hideWhenLowBattery = lowBatteryCheck.isChecked,
            batteryThreshold = int("batteryThreshold", 20).coerceIn(1, 100),
            useCustomMascot = customMascotRadio.isChecked,
            customSitImages = customImages.getValue(MASCOT_SIT).toList(),
            customWalkLeftImages = customImages.getValue(MASCOT_WALK_LEFT).toList(),
            customWalkRightImages = customImages.getValue(MASCOT_WALK_RIGHT).toList(),
            customRestImages = customImages.getValue(MASCOT_REST).toList()
        )

        lifecycleScope.launch {
            repository.save(s)
            Toast.makeText(this@MainActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMascotIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (MascotServiceHolder.running) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, MascotService::class.java))
            MascotServiceHolder.running = true
        } catch (_: Exception) {
            MascotServiceHolder.running = false
        }
    }

    private fun toggleMascot() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val intent = Intent(this, MascotService::class.java)
        if (MascotServiceHolder.running) {
            stopService(intent)
            MascotServiceHolder.running = false
        } else {
            ContextCompat.startForegroundService(this, intent)
            MascotServiceHolder.running = true
        }
        updateStartButton()
    }

    private fun updateOverlayStatus() {
        overlayStatus.text = if (Settings.canDrawOverlays(this)) {
            getString(R.string.overlay_granted)
        } else {
            getString(R.string.overlay_not_granted)
        }
    }

    private fun updateStartButton() {
        startButton.text = if (MascotServiceHolder.running) getString(R.string.stop_mascot) else getString(R.string.start_mascot)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun put(key: String, value: Any) { fields[key]?.setText(value.toString()) }
    private fun int(key: String, fallback: Int): Int = fields[key]?.text?.toString()?.toIntOrNull() ?: fallback
    private fun double(key: String, fallback: Double): Double = fields[key]?.text?.toString()?.toDoubleOrNull() ?: fallback
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val MASCOT_SIT = "sit"
        const val MASCOT_WALK_LEFT = "walk_left"
        const val MASCOT_WALK_RIGHT = "walk_right"
        const val MASCOT_REST = "rest"
    }
}

object MascotServiceHolder {
    var running: Boolean = false
}
