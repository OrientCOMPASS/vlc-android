package org.videolan.libvlcrs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Drop-in video view for libvlcrs: a [SurfaceView] the engine renders into, with
 * the VR gestures and an optional HUD overlay.
 *
 * Gestures:
 *  * one finger drag → look around ([RsMediaPlayer.drag]);
 *  * pinch → field of view ([RsMediaPlayer.zoom]);
 *  * double tap → switch the rendered eye ([RsMediaPlayer.eye]);
 *  * single tap → toggle the HUD (see [hudVisible]).
 *
 * Usage:
 * ```kotlin
 * val view = VrVideoView(context)
 * setContentView(view)
 * view.player = player      // attaches/detaches the surface automatically
 * ```
 *
 * The view owns no playback state: everything is forwarded to the
 * [RsMediaPlayer], so a framework can also drive the surface itself.
 */
class VrVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    /** The surface the engine renders into. */
    val surfaceView: SurfaceView = SurfaceView(context)

    /** HUD overlay (yaw / pitch / FOV / mode / eye / position …). */
    val hudView: TextView = TextView(context)

    /** Player driving this view; attaching detaches the previous one. */
    var player: RsMediaPlayer? = null
        set(value) {
            if (field === value) return
            field?.clearSurface()
            field = value
            val holder = surfaceView.holder
            val surface = holder.surface
            if (value != null && surface.isValid) {
                value.setSurface(surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
            }
            startHudLoop()
        }

    /** Show the HUD overlay. */
    var hudVisible: Boolean = true
        set(value) {
            field = value
            hudView.visibility = if (value) VISIBLE else GONE
            if (value) refreshHud()
        }

    /** Enable the built-in gestures. */
    var gesturesEnabled: Boolean = true

    /** HUD refresh period in milliseconds. */
    var hudIntervalMs: Long = 250L

    /** Called on a single tap (the HUD is toggled first). */
    var onTap: (() -> Unit)? = null

    /** Called when the user double taps (after the eye has been switched). */
    var onDoubleTap: (() -> Unit)? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!gesturesEnabled) return false
                player?.zoom(detector.scaleFactor)
                return true
            }
        })

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!gesturesEnabled) return false
                // GestureDetector reports the *inverse* delta; the engine wants
                // (current - previous) so that the picture follows the finger.
                player?.drag(-distanceX, -distanceY)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!gesturesEnabled) return false
                val p = player ?: return false
                p.eye = p.eye.other()
                onDoubleTap?.invoke()
                refreshHud()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                hudVisible = !hudVisible
                onTap?.invoke()
                return true
            }
        }
    )

    private val hudRunnable = object : Runnable {
        override fun run() {
            refreshHud()
            val interval = hudIntervalMs
            if (interval > 0 && player != null) {
                postDelayed(this, interval)
            }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        surfaceView.holder.addCallback(this)

        hudView.setTextColor(Color.WHITE)
        hudView.setShadowLayer(2f, 1f, 1f, Color.BLACK)
        hudView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        hudView.setTypeface(Typeface.MONOSPACE)
        hudView.setBackgroundColor(Color.argb(0x60, 0, 0, 0))
        val pad = (8 * resources.displayMetrics.density).toInt()
        hudView.setPadding(pad, pad, pad, pad)
        val hudParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        hudParams.gravity = Gravity.TOP or Gravity.START
        hudParams.topMargin = pad * 2
        hudParams.marginStart = pad * 2
        addView(hudView, hudParams)

        setOnTouchListener { _, event ->
            if (!gesturesEnabled) return@setOnTouchListener false
            var handled = scaleDetector.onTouchEvent(event)
            handled = gestureDetector.onTouchEvent(event) || handled
            handled
        }
    }

    /** Push a fresh HUD sample into the overlay. */
    fun refreshHud() {
        val p = player ?: return
        if (!hudVisible) return
        val text = runCatching { p.hudText }.getOrDefault("")
        if (text.isNotEmpty()) {
            hudView.text = text
        } else {
            hudView.text = p.hud.compact()
        }
    }

    private fun startHudLoop() {
        removeCallbacks(hudRunnable)
        if (hudIntervalMs > 0 && player != null) {
            post(hudRunnable)
        }
    }

    // ---------------------------------------------------- SurfaceHolder.Callback

    override fun surfaceCreated(holder: SurfaceHolder) {
        // wait for surfaceChanged: the size is not final yet
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        player?.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.clearSurface()
        surfaceWidth = 0
        surfaceHeight = 0
    }

    /** Size of the surface the engine renders into. */
    fun surfaceSize(): Pair<Int, Int> = surfaceWidth to surfaceHeight

    override fun onDetachedFromWindow() {
        removeCallbacks(hudRunnable)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startHudLoop()
    }
}
