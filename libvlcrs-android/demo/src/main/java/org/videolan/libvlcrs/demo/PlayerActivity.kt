package org.videolan.libvlcrs.demo

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.videolan.libvlcrs.Eye
import org.videolan.libvlcrs.PlayerState
import org.videolan.libvlcrs.ProjectionMode
import org.videolan.libvlcrs.RsMediaPlayer
import org.videolan.libvlcrs.RsMediaPlayerListener
import org.videolan.libvlcrs.ErrorCode
import org.videolan.libvlcrs.VrVideoView

/**
 * The player screen: [VrVideoView] plus the controls for the whole format matrix.
 *
 * Everything here is a thin mapping from a widget onto an [RsMediaPlayer]
 * property, which is the integration shape a host application is expected to
 * use.
 */
class PlayerActivity : Activity() {

    private var player: RsMediaPlayer? = null
    private lateinit var video: VrVideoView
    private lateinit var status: TextView
    private lateinit var timeLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPause: Button
    private lateinit var eyeButton: Button
    private lateinit var gyroButton: Button
    private lateinit var modeSpinner: Spinner

    private var seeking = false
    private var durationMs = 0L

    private val positionTicker = object : Runnable {
        override fun run() {
            updatePosition()
            video.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemUi()

        video = findViewById(R.id.video)
        status = findViewById(R.id.status)
        timeLabel = findViewById(R.id.time)
        seekBar = findViewById(R.id.seek)
        playPause = findViewById(R.id.play_pause)
        eyeButton = findViewById(R.id.eye)
        gyroButton = findViewById(R.id.gyro)
        modeSpinner = findViewById(R.id.mode)
        val recenter = findViewById<Button>(R.id.recenter)
        val controls = findViewById<View>(R.id.controls)

        val engine = RsMediaPlayer(this)
        player = engine
        video.player = engine

        engine.listener = object : RsMediaPlayerListener {
            override fun onPrepared(durationMs: Long, hasSphericalMetadata: Boolean) {
                this@PlayerActivity.durationMs = durationMs
                runOnUiThread {
                    seekBar.max = (durationMs / 1000L).toInt().coerceAtLeast(0)
                    status.text = "prepared ${format(durationMs)}" +
                        if (hasSphericalMetadata) " · spherical metadata found"
                        else " · no spherical metadata (Auto → planar)"
                }
            }

            override fun onVideoSize(width: Int, height: Int) {
                runOnUiThread { status.text = "video ${width}×${height}" }
            }

            override fun onProjectionChanged(mode: ProjectionMode, eye: Eye) {
                runOnUiThread {
                    if (modeSpinner.selectedItemPosition != mode.id) {
                        modeSpinner.setSelection(mode.id, false)
                    }
                    eyeButton.text = getString(R.string.eye_label, eye.label)
                }
            }

            override fun onBoundaryReached(yaw: Boolean, pitch: Boolean) {
                runOnUiThread {
                    status.text = "boundary reached: " + listOfNotNull(
                        if (yaw) "yaw" else null,
                        if (pitch) "pitch" else null
                    ).joinToString("/") + " (converging, no black)"
                }
            }

            override fun onEndReached() {
                runOnUiThread {
                    status.text = "end reached"
                    playPause.text = getString(R.string.play)
                }
            }

            override fun onError(code: ErrorCode, detail: Int) {
                runOnUiThread { status.text = "error: ${code.label} ($detail)" }
            }

            override fun onFirstFrame() {
                runOnUiThread { status.text = "first frame rendered" }
            }
        }

        // ---- format matrix ------------------------------------------------
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ProjectionMode.ALL.map { it.label }
        )
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                // switching mid-playback must keep the current position
                engine.projectionMode = ProjectionMode.fromId(position)
                status.text = "mode → ${engine.projectionMode.label} @ ${format(engine.time)}"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        eyeButton.setOnClickListener {
            engine.eye = engine.eye.other()
            eyeButton.text = getString(R.string.eye_label, engine.eye.label)
            status.text = "eye → ${engine.eye.label} @ ${format(engine.time)}"
        }

        gyroButton.setOnClickListener {
            engine.gyroEnabled = !engine.gyroEnabled
            engine.displayRotation = displayRotationDegrees()
            gyroButton.text = getString(
                if (engine.gyroEnabled) R.string.gyro_on else R.string.gyro_off
            )
            status.text = if (engine.gyroEnabled) {
                "gyroscope on — turn the device to look around"
            } else {
                "gyroscope off — drag to look around"
            }
        }

        recenter.setOnClickListener {
            engine.recenter()
            status.text = "view re-centred"
        }

        playPause.setOnClickListener {
            when (engine.state) {
                PlayerState.PLAYING -> {
                    engine.pause()
                    playPause.text = getString(R.string.play)
                }
                else -> {
                    engine.resume()
                    if (engine.state != PlayerState.PLAYING) engine.play()
                    playPause.text = getString(R.string.pause)
                }
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeLabel.text = format(progress * 1000L)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                seeking = false
                val target = (bar?.progress ?: 0) * 1000L
                engine.seekTo(target)
                status.text = "seek → ${format(target)}"
            }
        })

        controls.setOnClickListener { /* swallow taps so they do not reach the video */ }
        video.onTap = { toggleControls() }

        // ---- media --------------------------------------------------------
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri = intent.getStringExtra(EXTRA_URI)
        when {
            !org.videolan.libvlcrs.LibVLCRs.isSupported(this) -> {
                status.text = "unsupported device: arm64-v8a + API 26 required"
            }
            path != null -> {
                status.text = "opening $path"
                engine.projectionMode = ProjectionMode.AUTO
                if (engine.setMedia(path)) engine.play() else status.text = "cannot open $path"
            }
            uri != null -> {
                status.text = "opening $uri"
                engine.projectionMode = ProjectionMode.AUTO
                val parsed = Uri.parse(uri)
                if (engine.setMedia(parsed)) engine.play() else status.text = "cannot open $uri"
            }
            else -> status.text = "no media given"
        }
        playPause.text = getString(R.string.pause)
        updateGyroLabel()
    }

    private fun updateGyroLabel() {
        val engine = player ?: return
        gyroButton.text =
            getString(if (engine.gyroEnabled) R.string.gyro_on else R.string.gyro_off)
    }

    private fun toggleControls() {
        val controls = findViewById<View>(R.id.controls)
        controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun displayRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun updatePosition() {
        val engine = player ?: return
        val now = engine.time
        if (!seeking) {
            seekBar.progress = (now / 1000L).toInt().coerceIn(0, seekBar.max)
            timeLabel.text = "${format(now)} / ${format(durationMs.coerceAtLeast(engine.length))}"
        }
    }

    private fun format(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        video.post(positionTicker)
        player?.let { if (it.state == PlayerState.PAUSED) it.resume() }
    }

    override fun onPause() {
        video.removeCallbacks(positionTicker)
        player?.let { if (it.isPlaying) it.pause() }
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "org.videolan.libvlcrs.demo.PATH"
        const val EXTRA_URI = "org.videolan.libvlcrs.demo.URI"
    }
}
