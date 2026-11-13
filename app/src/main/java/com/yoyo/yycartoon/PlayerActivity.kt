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
    private lateinit var restCountdown: TextView

    private var playlist = mutableListOf<VideoItem>()
    private var promisedCount = 0
    private var currentIndex = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var announcementRemoval = Runnable {}
    private val parentHoldHandler = Handler(Looper.getMainLooper())
    private var resting = false
    private var restSecondsRemaining = 0
    private val restTick = object : Runnable {
        override fun run() {
            if (!resting) return
            restSecondsRemaining--
            if (restSecondsRemaining == 0) {
                endRest()
            } else {
                updateRestCountdown()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }
    private var parentHoldStart = 0L
    private var parentHoldRunnable = Runnable {}
    private var finished = false
    private var speechCoach: SpeechCoach? = null
    private lateinit var watchHistory: WatchHistory
    private var currentVideo: VideoItem? = null
    private var resumePosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        bindViews()
        playlist = intent?.getParcelableArrayListExtra(MainActivity.EXTRA_PLAYLIST) ?: mutableListOf()
        promisedCount = intent?.getIntExtra(MainActivity.EXTRA_PROMISED_COUNT, playlist.size) ?: playlist.size
        if (playlist.isEmpty()) {
            finish()
            return
        }
        watchHistory = WatchHistory(getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE))
        speechCoach = SpeechCoach(this) { }
        videoView.setOnPreparedListener {
            if (resumePosition > 0) {
                videoView.seekTo(resumePosition)
            }
            videoView.start()
        }
        videoView.setOnCompletionListener { playNext(watchedCurrent = true) }
        videoView.setOnErrorListener { _, what, extra ->
            if (!finished && !resting) {
                Toast.makeText(this, "播放失败：$what/$extra", Toast.LENGTH_LONG).show()
                playNext(watchedCurrent = false)
            }
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
        restCountdown = findViewById(R.id.restCountdown)
    }

    private fun playCurrent() {
        if (currentIndex >= playlist.size || currentIndex >= promisedCount) {
            finishSession()
            return
        }
        val video = playlist[currentIndex]
        if (currentVideo?.uri != video.uri) {
            saveCurrentPosition()
        }
        currentVideo = video
        resumePosition = watchHistory.getSavedPosition(video)
        watchHistory.registerEpisodeStarted(video)
        val remaining = promisedCount - currentIndex
        playerTitle.text = video.displayName
        playerProgress.text = String.format(Locale.CHINA, "%d / %d", currentIndex + 1, promisedCount)
        val announcementText = getString(R.string.now_playing, currentIndex + 1, remaining)
        announcement.text = announcementText
        announcement.visibility = View.VISIBLE
        speechCoach?.speak(announcementText)
        mainHandler.removeCallbacks(announcementRemoval)
        announcementRemoval = Runnable { announcement.visibility = View.GONE }
        mainHandler.postDelayed(announcementRemoval, ANNOUNCEMENT_DURATION)
        videoView.setVideoURI(video.uri)
        videoView.requestFocus()
    }

    private fun playNext(watchedCurrent: Boolean) {
        if (finished || resting) return
        finishCurrentEpisode(watchedCurrent)
        currentIndex++
        if (watchedCurrent) {
            startRest()
        } else if (currentIndex >= promisedCount || currentIndex >= playlist.size) {
            finishSession()
        } else {
            playCurrent()
        }
    }

    private fun startRest() {
        resting = true
        restSecondsRemaining = REST_DURATION_SECONDS
        mainHandler.removeCallbacks(announcementRemoval)
        announcement.visibility = View.GONE
        videoView.stopPlayback()
        restPanel.visibility = View.VISIBLE
        updateRestCountdown()
        speechCoach?.speak(getString(R.string.rest_look_outside))
        mainHandler.postDelayed(restTick, 1000L)
    }

    private fun updateRestCountdown() {
        val message = if (currentIndex >= promisedCount || currentIndex >= playlist.size) {
            R.string.rest_final_countdown
        } else {
            R.string.rest_countdown
        }
        restCountdown.text = getString(message, restSecondsRemaining)
    }

    private fun endRest() {
        mainHandler.removeCallbacks(restTick)
        resting = false
        restPanel.visibility = View.GONE
        if (currentIndex >= promisedCount || currentIndex >= playlist.size) {
            finishSession()
        } else {
            playCurrent()
        }
    }

    private fun finishSession() {
        if (finished) return
        finished = true
        resting = false
        mainHandler.removeCallbacks(restTick)
        mainHandler.removeCallbacks(announcementRemoval)
        restPanel.visibility = View.GONE
        announcement.visibility = View.GONE
        saveCurrentPosition()
        videoView.stopPlayback()
        doneMessage.text = getString(R.string.session_done_message, promisedCount)
        donePanel.visibility = View.VISIBLE
        speechCoach?.speak(getString(R.string.session_done_message, promisedCount))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val seekAmount = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SEEK_STEP_MS
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -SEEK_STEP_MS
                else -> null
            }
            if (seekAmount != null) {
                if (!finished && !resting) seekBy(seekAmount)
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
        if (finished) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                parentHoldStart = System.currentTimeMillis()
                parentHoldRunnable = Runnable {
                    finish()
                }
                parentHoldHandler.postDelayed(parentHoldRunnable, PARENT_HOLD_DURATION)
                return true
            }
            return true
        }
        if (resting) {
            if (keyCode == KeyEvent.KEYCODE_BACK) finishSession()
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                togglePlayback()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayback()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finishSession()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (finished && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            parentHoldHandler.removeCallbacks(parentHoldRunnable)
            val held = System.currentTimeMillis() - parentHoldStart
            if (held < PARENT_HOLD_DURATION) {
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
        mainHandler.removeCallbacks(restTick)
        saveCurrentPosition()
        if (!finished && videoView.isPlaying) {
            videoView.pause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (resting) mainHandler.postDelayed(restTick, 1000L)
    }

    override fun onDestroy() {
        saveCurrentPosition()
        parentHoldHandler.removeCallbacks(parentHoldRunnable)
        mainHandler.removeCallbacks(restTick)
        mainHandler.removeCallbacks(announcementRemoval)
        speechCoach?.shutdown()
        super.onDestroy()
    }

    private fun finishCurrentEpisode(watched: Boolean) {
        val video = currentVideo ?: return
        if (watched) {
            watchHistory.markWatched(video)
            watchHistory.clearPosition(video)
        } else {
            saveCurrentPosition()
        }
        currentVideo = null
        resumePosition = 0
    }

    private fun saveCurrentPosition() {
        val video = currentVideo ?: return
        if (videoView.duration <= 0) return
        watchHistory.savePosition(video, videoView.currentPosition, videoView.duration)
    }

    companion object {
        private const val ANNOUNCEMENT_DURATION = 3800L
        private const val SEEK_ANNOUNCEMENT_DURATION = 2000L
        private const val SEEK_STEP_MS = 10_000
        private const val PARENT_HOLD_DURATION = 3000L
        private const val REST_DURATION_SECONDS = 20
        private const val PREFERENCES_NAME = "yoyo_cartoon"
    }
}
