package com.yoyo.yycartoon

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class PlayerActivity : AppCompatActivity() {
    private lateinit var videoView: VideoView
    private lateinit var playerTitle: TextView
    private lateinit var playerProgress: TextView
    private lateinit var announcement: TextView
    private lateinit var donePanel: View
    private lateinit var doneMessage: TextView
    private lateinit var restPanel: View
    private lateinit var restCountdownText: TextView

    private var playlist = mutableListOf<VideoItem>()
    private var currentIndex = 0
    private var currentVideo: VideoItem? = null
    private var resumePosition = 0
    private var finished = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var announcementRemoval = Runnable {}
    private var restBreakRunnable = Runnable {}
    private var restRemainingSeconds = 0
    private var afterRestBreak: (() -> Unit)? = null
    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            mainHandler.postDelayed(this, SAVE_PROGRESS_INTERVAL_MS)
        }
    }
    private val parentHoldHandler = Handler(Looper.getMainLooper())
    private var parentHoldStart = 0L
    private var parentHoldRunnable = Runnable {}
    private var lastBackPressTime = 0L
    private var speechCoach: SpeechCoach? = null
    private lateinit var watchHistory: WatchHistory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        bindViews()
        playlist = intent?.getParcelableArrayListExtra(MainActivity.EXTRA_PLAYLIST) ?: mutableListOf()
        if (playlist.isEmpty()) {
            finish()
            return
        }
        watchHistory = WatchHistory(getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE))
        speechCoach = SpeechCoach(this) { }
        videoView.setOnPreparedListener {
            if (resumePosition > 0) videoView.seekTo(resumePosition)
            videoView.start()
        }
        videoView.setOnCompletionListener { playNextAfterCompletion() }
        videoView.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "播放失败：$what/$extra", Toast.LENGTH_LONG).show()
            playNextAfterError()
            true
        }
        playCurrent()
    }

    private fun bindViews() {
        videoView = findViewById(R.id.videoView)
        playerTitle = findViewById(R.id.playerTitle)
        playerProgress = findViewById(R.id.playerProgress)
        announcement = findViewById(R.id.announcement)
        donePanel = findViewById(R.id.donePanel)
        doneMessage = findViewById(R.id.doneMessage)
        restPanel = findViewById(R.id.restPanel)
        restCountdownText = findViewById(R.id.restCountdown)
    }

    private fun playCurrent() {
        if (finished || currentIndex >= playlist.size) {
            finishSession()
            return
        }
        saveCurrentPosition()
        val video = playlist[currentIndex]
        currentVideo = video
        resumePosition = watchHistory.getSavedPosition(video)
        watchHistory.setLastPlayed(video)
        playerTitle.text = video.displayName
        playerProgress.text = String.format(
            Locale.CHINA,
            "%s · %d/%d",
            video.season,
            currentIndex + 1,
            playlist.size
        )
        val announcementText = getString(
            R.string.now_playing,
            video.showName,
            video.season,
            video.displayName
        )
        announcement.text = announcementText
        announcement.visibility = View.VISIBLE
        speechCoach?.speak(announcementText)
        mainHandler.removeCallbacks(announcementRemoval)
        announcementRemoval = Runnable { announcement.visibility = View.GONE }
        mainHandler.postDelayed(announcementRemoval, ANNOUNCEMENT_DURATION)
        videoView.setVideoURI(video.uri)
        videoView.requestFocus()
        mainHandler.removeCallbacks(saveProgressRunnable)
        mainHandler.postDelayed(saveProgressRunnable, SAVE_PROGRESS_INTERVAL_MS)
    }

    private fun playNextAfterCompletion() {
        if (finished) return
        val video = currentVideo ?: return
        mainHandler.removeCallbacks(saveProgressRunnable)
        watchHistory.markWatched(video)
        watchHistory.clearPosition(video)
        watchHistory.registerEpisodeCompleted(video)
        watchHistory.registerPlayCompleted(video)
        currentVideo = null
        resumePosition = 0
        currentIndex++
        when {
            watchHistory.getDailyRemaining() == 0 -> finishSession()
            currentIndex >= playlist.size -> finishSession()
            else -> startRestBreak { playCurrent() }
        }
    }

    private fun startRestBreak(onBreakFinished: () -> Unit) {
        if (finished) return
        restRemainingSeconds = REST_DURATION_SECONDS
        afterRestBreak = onBreakFinished
        announcement.visibility = View.GONE
        restPanel.visibility = View.VISIBLE
        renderRestCountdown()
        speechCoach?.speak(getString(R.string.rest_look_outside))
        scheduleRestBreakTick()
    }

    private fun scheduleRestBreakTick() {
        mainHandler.removeCallbacks(restBreakRunnable)
        restBreakRunnable = Runnable {
            restRemainingSeconds--
            if (restRemainingSeconds <= 0) {
                finishRestBreak()
            } else {
                renderRestCountdown()
                scheduleRestBreakTick()
            }
        }
        mainHandler.postDelayed(restBreakRunnable, 1000L)
    }

    private fun renderRestCountdown() {
        val textRes = if (restRemainingSeconds == 1) {
            R.string.rest_final_countdown
        } else {
            R.string.rest_countdown
        }
        restCountdownText.text = getString(textRes, restRemainingSeconds)
    }

    private fun finishRestBreak() {
        mainHandler.removeCallbacks(restBreakRunnable)
        restRemainingSeconds = 0
        restPanel.visibility = View.GONE
        val nextAction = afterRestBreak
        afterRestBreak = null
        if (!finished) nextAction?.invoke()
    }

    private fun playNextAfterError() {
        if (finished) return
        saveCurrentPosition()
        currentVideo = null
        resumePosition = 0
        currentIndex++
        if (currentIndex >= playlist.size) {
            finishSession()
        } else {
            playCurrent()
        }
    }

    private fun finishSession() {
        if (finished) return
        finished = true
        mainHandler.removeCallbacks(announcementRemoval)
        mainHandler.removeCallbacks(saveProgressRunnable)
        mainHandler.removeCallbacks(restBreakRunnable)
        afterRestBreak = null
        restRemainingSeconds = 0
        restPanel.visibility = View.GONE
        saveCurrentPosition()
        videoView.stopPlayback()
        announcement.visibility = View.GONE
        doneMessage.text = getString(
            R.string.session_done_message,
            watchHistory.getDailyCount()
        )
        donePanel.visibility = View.VISIBLE
        speechCoach?.speak(getString(R.string.session_done_message, watchHistory.getDailyCount()))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !finished) {
            val seekAmount = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SEEK_STEP_MS
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -SEEK_STEP_MS
                else -> null
            }
            if (seekAmount != null) {
                seekBy(seekAmount)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekBy(amountMs: Int) {
        val duration = videoView.duration
        if (duration <= 0 || currentVideo == null) return
        val target = (videoView.currentPosition.toLong() + amountMs)
            .coerceIn(0L, (duration - 1000).coerceAtLeast(0).toLong())
            .toInt()
        videoView.seekTo(target)
        val direction = if (amountMs > 0) R.string.seek_forward else R.string.seek_backward
        announcement.text = getString(direction, formatTime(target), formatTime(duration))
        announcement.visibility = View.VISIBLE
        mainHandler.removeCallbacks(announcementRemoval)
        announcementRemoval = Runnable { announcement.visibility = View.GONE }
        mainHandler.postDelayed(announcementRemoval, SEEK_ANNOUNCEMENT_DURATION)
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds / 1000
        return String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackToPicker()
            return true
        }
        if (restRemainingSeconds > 0) return true
        if (finished) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                parentHoldStart = System.currentTimeMillis()
                parentHoldRunnable = Runnable { finish() }
                parentHoldHandler.postDelayed(parentHoldRunnable, PARENT_HOLD_DURATION)
                return true
            }
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayback()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleBackToPicker() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < DOUBLE_BACK_INTERVAL_MS) {
            mainHandler.removeCallbacks(announcementRemoval)
            mainHandler.removeCallbacks(saveProgressRunnable)
            mainHandler.removeCallbacks(restBreakRunnable)
            afterRestBreak = null
            restRemainingSeconds = 0
            saveCurrentPosition()
            videoView.stopPlayback()
            setResult(RESULT_OK)
            finish()
        } else {
            lastBackPressTime = now
            if (videoView.isPlaying) videoView.pause()
            saveCurrentPosition()
            Toast.makeText(this, R.string.press_back_again_to_picker, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (finished && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            parentHoldHandler.removeCallbacks(parentHoldRunnable)
            if (System.currentTimeMillis() - parentHoldStart < PARENT_HOLD_DURATION) {
                Toast.makeText(this, R.string.parent_exit, Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun togglePlayback() {
        if (videoView.isPlaying) {
            videoView.pause()
            Toast.makeText(this, R.string.paused, Toast.LENGTH_SHORT).show()
        } else {
            videoView.start()
            Toast.makeText(this, R.string.playing, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        if (restRemainingSeconds > 0) mainHandler.removeCallbacks(restBreakRunnable)
        saveCurrentPosition()
        if (!finished && videoView.isPlaying) videoView.pause()
        super.onPause()
    }

    override fun onRestart() {
        super.onRestart()
        if (!finished && restRemainingSeconds > 0) scheduleRestBreakTick()
    }

    override fun onDestroy() {
        saveCurrentPosition()
        mainHandler.removeCallbacks(announcementRemoval)
        mainHandler.removeCallbacks(saveProgressRunnable)
        mainHandler.removeCallbacks(restBreakRunnable)
        parentHoldHandler.removeCallbacks(parentHoldRunnable)
        speechCoach?.shutdown()
        super.onDestroy()
    }

    private fun saveCurrentPosition() {
        val video = currentVideo ?: return
        if (videoView.duration <= 0) return
        watchHistory.savePosition(video, videoView.currentPosition, videoView.duration)
    }

    companion object {
        private const val ANNOUNCEMENT_DURATION = 3800L
        private const val SEEK_ANNOUNCEMENT_DURATION = 2000L
        private const val SAVE_PROGRESS_INTERVAL_MS = 5000L
        private const val SEEK_STEP_MS = 10_000
        private const val PARENT_HOLD_DURATION = 3000L
        private const val DOUBLE_BACK_INTERVAL_MS = 1800L
        private const val REST_DURATION_SECONDS = 20
        private const val PREFERENCES_NAME = "yoyo_cartoon"
    }
}
