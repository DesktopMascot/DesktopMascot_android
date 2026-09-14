package com.ekinao.desktopmascot

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Final mascot overlay.
 *
 * State cycle:
 *   SIT -> WALK -> SIT_AFTER_WALK -> REST -> SIT -> ...
 *
 * Sprite names are resolved at runtime, so the project still builds even if
 * the PNGs have not been copied in yet. Put the user's PNGs in
 * app/src/main/res/drawable-nodpi/ (or drawable/) with these names:
 *   sit_01 ... sit_05
 *   walk_left_01 ... walk_left_05
 *   walk_right_01 ... walk_right_05
 *   rest_01 ... rest_05
 */
class MascotOverlay(private val context: Context) {
    private enum class State { SIT, WALK, SIT_AFTER_WALK, REST }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random.Default

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(4, 4, 4, 4)
        contentDescription = context.getString(R.string.mascot_content_description)
    }

    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var manualDrag = false
    private var walkDirection = 1 // 1 = right, -1 = left
    private var lastRenderedWalkDirection = 0
    private var breathingPhase = 0f

    private var state = State.SIT
    private var stateEndsAt = 0L
    private var nextFrameAt = 0L
    private var frameIndex = 0
    private var walkTargetX = 0f
    private var walkTargetY = 0f
    private var lastTick = 0L
    private var settings = MascotSettings()
    private var temporarilyHidden = false
    private var temporarilyHiddenAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            update(System.currentTimeMillis())
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun show() {
        if (attached) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val p = WindowManager.LayoutParams(
            MASCOT_SIZE_PX,
            MASCOT_SIZE_PX,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 250
        }

        imageView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    manualDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) {
                        manualDrag = true
                    }
                    p.x = startX + dx
                    p.y = startY + dy
                    try {
                        windowManager.updateViewLayout(imageView, p)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    true
                }
                else -> true
            }
        }

        params = p
        windowManager.addView(imageView, p)
        attached = true

        loadSettings()
        enterState(State.SIT, System.currentTimeMillis())
        handler.post(tick)
    }

    fun hide() {
        if (!attached) return
        handler.removeCallbacks(tick)
        try {
            windowManager.removeView(imageView)
        } catch (_: Exception) {
        }
        attached = false
        params = null
        temporarilyHidden = false
        temporarilyHiddenAt = 0L
    }

    fun temporarilyHide() {
        if (!attached || temporarilyHidden) return
        handler.removeCallbacks(tick)
        try {
            windowManager.removeView(imageView)
        } catch (_: Exception) {
        }
        attached = false
        temporarilyHidden = true
        temporarilyHiddenAt = System.currentTimeMillis()
    }

    fun restoreAfterTemporaryHide() {
        if (!temporarilyHidden || attached) return
        val p = params ?: return
        try {
            val hiddenDuration = if (temporarilyHiddenAt > 0L) {
                System.currentTimeMillis() - temporarilyHiddenAt
            } else {
                0L
            }
            // Freeze the state-machine clock while hidden so a long hidden
            // period does not cause an immediate state transition on restore.
            stateEndsAt += hiddenDuration
            nextFrameAt += hiddenDuration

            windowManager.addView(imageView, p)
            attached = true
            temporarilyHidden = false
            temporarilyHiddenAt = 0L
            handler.post(tick)
        } catch (_: Exception) {
            attached = false
        }
    }

    fun isShown(): Boolean = attached

    fun destroy() = hide()

    private fun loadSettings() {
        settings = MascotService.currentSettings ?: MascotSettings()
    }

    fun updateSettings(newSettings: MascotSettings) {
        settings = newSettings
        if (attached) {
            // Refresh the currently displayed frame immediately after settings change.
            if (state == State.WALK) showNextFrame() else showStaticStateSprite()
        }
    }

    private fun customMascotUsable(): Boolean {
        if (!settings.useCustomMascot) return false
        val categories = listOf(
            MainActivity.MASCOT_SIT to settings.customSitImages,
            MainActivity.MASCOT_WALK_LEFT to settings.customWalkLeftImages,
            MainActivity.MASCOT_WALK_RIGHT to settings.customWalkRightImages,
            MainActivity.MASCOT_REST to settings.customRestImages
        )
        return categories.all { (category, files) ->
            files.isNotEmpty() && files.all { fileName ->
                val file = customFile(fileName, category)
                file.exists() && file.length() > 0L &&
                    BitmapFactory.decodeFile(file.absolutePath) != null
            }
        }
    }

    private fun customFile(fileName: String, category: String): java.io.File =
        java.io.File(context.filesDir, "${SettingsRepository.CUSTOM_MASCOT_DIR}/$category/$fileName")

    private fun customFiles(category: String): List<java.io.File> {
        val names = when (category) {
            MainActivity.MASCOT_SIT -> settings.customSitImages
            MainActivity.MASCOT_WALK_LEFT -> settings.customWalkLeftImages
            MainActivity.MASCOT_WALK_RIGHT -> settings.customWalkRightImages
            MainActivity.MASCOT_REST -> settings.customRestImages
            else -> emptyList()
        }
        return names.map { customFile(it, category) }
            .filter { it.exists() && it.length() > 0L }
    }

    private fun showCustomFrame(category: String, index: Int = 0): Boolean {
        val files = customFiles(category)
        if (files.isEmpty()) return false
        val file = files[index.mod(files.size)]
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        imageView.setImageBitmap(bitmap)
        return true
    }

    private fun update(now: Long) {
        if (!attached) return

        if (now >= stateEndsAt) {
            when (state) {
                State.SIT -> enterState(State.WALK, now)
                State.WALK -> enterState(State.SIT_AFTER_WALK, now)
                State.SIT_AFTER_WALK -> enterState(State.REST, now)
                State.REST -> enterState(State.SIT, now)
            }
        }

        if (state == State.WALK && !manualDrag) {
            moveTowardTarget()
        }

        updateBreathing()

        // Only the WALK state advances animation frames.
        // SIT and REST use a single static sprite; breathing is handled
        // separately by updateBreathing().
        if (state == State.WALK && now >= nextFrameAt) {
            showNextFrame()
            nextFrameAt = now + FRAME_TIME_MS
        }
    }

    private fun enterState(newState: State, now: Long) {
        state = newState
        frameIndex = 0
        breathingPhase = 0f
        manualDrag = false
        lastTick = now

        val durationSeconds = when (newState) {
            State.SIT -> randomRange(settings.sitBeforeWalkMin, settings.sitBeforeWalkMax)
            State.WALK -> randomRange(settings.walkDurationMin, settings.walkDurationMax)
            State.SIT_AFTER_WALK -> randomRange(settings.sitAfterWalkMin, settings.sitAfterWalkMax)
            State.REST -> randomRange(settings.restMin, settings.restMax)
        }
        stateEndsAt = now + durationSeconds * 1000L
        nextFrameAt = now

        if (newState == State.WALK) {
            chooseNewTarget()
            showNextFrame()
        } else {
            // SIT/REST are static poses. Do not cycle through sit_01...
            // or rest_01...; breathing is the only animation in these states.
            showStaticStateSprite()
        }
    }

    private fun showNextFrame() {
        if (customMascotUsable()) {
            val category = when (state) {
                State.SIT, State.SIT_AFTER_WALK -> MainActivity.MASCOT_SIT
                State.WALK -> if (walkDirection < 0) MainActivity.MASCOT_WALK_LEFT else MainActivity.MASCOT_WALK_RIGHT
                State.REST -> MainActivity.MASCOT_REST
            }
            if (showCustomFrame(category, frameIndex)) {
                frameIndex = (frameIndex + 1) % customFiles(category).size
                return
            }
        }

        val prefix = when (state) {
            State.SIT, State.SIT_AFTER_WALK -> "sit"
            State.REST -> "rest"
            State.WALK -> if (walkDirection < 0) "walk_left" else "walk_right"
        }
        val frames = findAnimationFrames(prefix)
        if (frames.isEmpty()) {
            findDrawable("mascot_fallback")?.let { imageView.setImageResource(it) }
            return
        }

        if (state == State.WALK && lastRenderedWalkDirection != walkDirection) {
            frameIndex = 0
            lastRenderedWalkDirection = walkDirection
        }

        imageView.setImageResource(frames[frameIndex % frames.size])
        frameIndex = (frameIndex + 1) % frames.size
    }

    private fun showStaticStateSprite() {
        val category = when (state) {
            State.SIT, State.SIT_AFTER_WALK -> MainActivity.MASCOT_SIT
            State.REST -> MainActivity.MASCOT_REST
            State.WALK -> return
        }

        if (customMascotUsable() && showCustomFrame(category, 0)) return

        val prefix = when (state) {
            State.SIT, State.SIT_AFTER_WALK -> "sit"
            State.REST -> "rest"
            State.WALK -> return
        }
        val frames = findAnimationFrames(prefix)
        if (frames.isNotEmpty()) {
            imageView.setImageResource(frames[random.nextInt(frames.size)])
        } else {
            findDrawable("mascot_fallback")?.let { imageView.setImageResource(it) }
        }
    }

    private fun findAnimationFrames(prefix: String): List<Int> {
        val result = ArrayList<Pair<Int, Int>>()
        for (number in 1..20) {
            val id = findDrawable("${prefix}_${number.toString().padStart(2, '0')}")
                ?: findDrawable("${prefix}_$number")
            if (id != null) {
                result.add(number to id)
            }
        }
        return result.sortedBy { it.first }.map { it.second }
    }

    private fun updateBreathing() {
        if (settings.disableBreathingAnimation) {
            imageView.scaleY = 1f
            imageView.pivotX = imageView.width / 2f
            imageView.pivotY = imageView.height.toFloat()
            return
        }

        if (state != State.SIT && state != State.SIT_AFTER_WALK && state != State.REST) {
            imageView.scaleY = 1f
            imageView.pivotX = imageView.width / 2f
            imageView.pivotY = imageView.height.toFloat()
            return
        }

        // Gentle vertical breathing, about +/- 2%. Keep the bottom/feet anchored.
        breathingPhase += 0.16f
        val scale = 1f + kotlin.math.sin(breathingPhase.toDouble()).toFloat() * 0.02f
        imageView.pivotX = imageView.width / 2f
        imageView.pivotY = imageView.height.toFloat()
        imageView.scaleY = scale
    }

    private fun moveTowardTarget() {
        val p = params ?: return
        val currentX = p.x.toFloat()
        val currentY = p.y.toFloat()
        val dx = walkTargetX - currentX
        val dy = walkTargetY - currentY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (abs(dx) >= 1f) {
            val newDirection = if (dx < 0f) -1 else 1
            if (newDirection != walkDirection) {
                walkDirection = newDirection
                frameIndex = 0
            }
        }

        if (distance < 4f) {
            chooseNewTarget()
            return
        }

        val step = max(0.5f, settings.walkSpeed.toFloat() * WALK_STEP_MULTIPLIER)
        val ratio = min(1f, step / distance)
        p.x = (currentX + dx * ratio).toInt()
        p.y = (currentY + dy * ratio).toInt()

        try {
            windowManager.updateViewLayout(imageView, p)
        } catch (_: Exception) {
        }
    }

    private fun chooseNewTarget() {
        val bounds = screenBounds()
        val margin = max(0, settings.screenMargin)
        val maxX = max(margin, bounds.first - MASCOT_SIZE_PX - margin)
        val maxY = max(margin, bounds.second - MASCOT_SIZE_PX - margin)

        walkTargetX = random.nextInt(margin, maxX + 1).toFloat()
        walkTargetY = random.nextInt(margin, maxY + 1).toFloat()

        val dx = walkTargetX - currentX()
        if (abs(dx) >= 1f) {
            val newDirection = if (dx < 0f) -1 else 1
            if (newDirection != walkDirection) {
                walkDirection = newDirection
                frameIndex = 0
            }
        }
    }

    private fun currentX(): Int = params?.x ?: 0

    private fun screenBounds(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            Pair(metrics.bounds.width(), metrics.bounds.height())
        } else {
            val dm = context.resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun findDrawable(name: String): Int? {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        return if (id != 0) id else null
    }

    private fun randomRange(minValue: Int, maxValue: Int): Int {
        val lo = min(minValue, maxValue).coerceAtLeast(0)
        val hi = max(minValue, maxValue).coerceAtLeast(lo)
        return if (lo == hi) lo else random.nextInt(lo, hi + 1)
    }

    companion object {
        private const val MASCOT_SIZE_PX = 180
        private const val TICK_MS = 50L
        private const val FRAME_TIME_MS = 120L
        private const val WALK_STEP_MULTIPLIER = 2.0f
        private const val DRAG_THRESHOLD = 5f
    }
}
