package com.yoyo.yycartoon

import android.app.Activity
import android.app.Dialog
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Window
import android.widget.GridLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var episodeCountText: TextView
    private lateinit var dailyProgressBar: ProgressBar
    private lateinit var dailyProgressText: TextView
    private lateinit var statusText: TextView
    private lateinit var episodeSelector: android.view.View
    private lateinit var normalButton: Button
    private lateinit var miniButton: Button
    private lateinit var randomButton: Button
    private lateinit var startButton: Button
    private lateinit var chooseFolderButton: Button
    private lateinit var parentCenterButton: Button
    private lateinit var decreaseButton: Button
    private lateinit var increaseButton: Button

    private var episodeCount = 4
    private var watchMode = WatchMode.NORMAL
    private var videos: List<VideoItem> = emptyList()
    private var loadingVideos = false
    private lateinit var watchHistory: WatchHistory
    private var speechCoach: SpeechCoach? = null
    private var speechReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        watchHistory = WatchHistory(preferences)
        bindViews()
        restoreState(savedInstanceState)
        setupListeners()
        updateUi()
        speechCoach = SpeechCoach(this) { ready ->
            speechReady = ready
            if (ready) {
                speakWelcome()
            } else {
                Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        maybeLoadSavedFolder()
    }

    private fun bindViews() {
        episodeSelector = findViewById(R.id.episodeSelector)
        episodeCountText = findViewById(R.id.episodeCountText)
        dailyProgressBar = findViewById(R.id.dailyProgressBar)
        dailyProgressText = findViewById(R.id.dailyProgressText)
        statusText = findViewById(R.id.statusText)
        decreaseButton = findViewById(R.id.decreaseButton)
        increaseButton = findViewById(R.id.increaseButton)
        normalButton = findViewById(R.id.normalButton)
        miniButton = findViewById(R.id.miniButton)
        randomButton = findViewById(R.id.randomButton)
        startButton = findViewById(R.id.startButton)
        chooseFolderButton = findViewById(R.id.chooseFolderButton)
        parentCenterButton = findViewById(R.id.parentCenterButton)
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        episodeCount = savedInstanceState?.getInt(STATE_EPISODE_COUNT)
            ?: preferences.getInt(STATE_EPISODE_COUNT, DEFAULT_EPISODES).coerceIn(1, MAX_EPISODES)
        watchMode = savedInstanceState?.getSerializable(STATE_WATCH_MODE) as? WatchMode
            ?: preferences.getString(STATE_WATCH_MODE, WatchMode.NORMAL.name)
                ?.let { runCatching { WatchMode.valueOf(it) }.getOrNull() }
            ?: WatchMode.NORMAL
    }

    private fun setupListeners() {
        decreaseButton.setOnClickListener { changeEpisodeCount(-1) }
        increaseButton.setOnClickListener { changeEpisodeCount(1) }
        normalButton.setOnClickListener { selectMode(WatchMode.NORMAL) }
        miniButton.setOnClickListener { selectMode(WatchMode.MINI) }
        randomButton.setOnClickListener { selectMode(WatchMode.RANDOM) }
        startButton.setOnClickListener { startWatching() }
        chooseFolderButton.setOnClickListener { chooseFolder() }
        parentCenterButton.setOnClickListener { openParentCenter() }
        episodeSelector.post { episodeSelector.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val inEpisodeSelector = currentFocus?.id == R.id.episodeSelector
        if (event.action == KeyEvent.ACTION_DOWN && inEpisodeSelector) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    changeEpisodeCount(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    changeEpisodeCount(-1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun maybeLoadSavedFolder() {
        val savedUri = preferences.getString(STATE_SOURCE_URI, null) ?: return
        if (savedUri == DEVICE_LIBRARY_URI) {
            openDeviceLibrary()
            return
        }
        val uri = runCatching { Uri.parse(savedUri) }.getOrNull() ?: return
        if (runCatching { contentResolver.persistedUriPermissions.any { it.uri == uri } }.getOrDefault(false)) {
            loadFolder(uri)
        } else {
            preferences.edit().remove(STATE_SOURCE_URI).apply()
        }
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, false)
        }
        // Some Android TV images resolve this intent to a stub that cannot select directories.
        if (intent.resolveActivity(packageManager)?.packageName == "com.android.tv.frameworkpackagestubs") {
            openDeviceLibrary()
            return
        }
        runCatching {
            startActivityForResult(intent, REQUEST_FOLDER)
        }.onFailure {
            openDeviceLibrary()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER) return
        if (resultCode != Activity.RESULT_OK) {
            if (preferences.getString(STATE_SOURCE_URI, null) == null) {
                openDeviceLibrary()
            }
            return
        }
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        preferences.edit().putString(STATE_SOURCE_URI, uri.toString()).apply()
        loadFolder(uri)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_VIDEO_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadDeviceLibrary()
        } else {
            videos = emptyList()
            updateUi()
            Toast.makeText(this, R.string.video_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadFolder(uri: Uri) {
        loadingVideos = true
        statusText.setText(R.string.loading_videos)
        Thread {
            val loaded = runCatching { VideoLibrary.load(this, uri) }.getOrDefault(emptyList())
            runOnUiThread {
                loadingVideos = false
                videos = loaded
                updateUi()
            }
        }.start()
    }

    private fun openDeviceLibrary() {
        if (hasVideoPermission()) {
            loadDeviceLibrary()
        } else {
            requestPermissions(videoPermissions(), REQUEST_VIDEO_PERMISSION)
        }
    }

    private fun loadDeviceLibrary() {
        preferences.edit().putString(STATE_SOURCE_URI, DEVICE_LIBRARY_URI).apply()
        loadingVideos = true
        statusText.setText(R.string.loading_videos)
        Thread {
            val loaded = runCatching { VideoLibrary.loadFromMediaStore(this) }
                .onFailure { Log.e("YYCartoon", "Failed to load videos from MediaStore", it) }
                .getOrDefault(emptyList())
            Log.d("YYCartoon", "MediaStore returned ${loaded.size} videos")
            runOnUiThread {
                loadingVideos = false
                videos = loaded
                updateUi()
            }
        }.start()
    }

    private fun hasVideoPermission(): Boolean {
        return videoPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun videoPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun changeEpisodeCount(delta: Int) {
        syncEpisodeCountWithQuota()
        episodeCount = (episodeCount + delta).coerceIn(1, selectableEpisodeMaximum())
        updateUi()
    }

    private fun selectMode(mode: WatchMode) {
        watchMode = mode
        updateUi()
    }

    private fun startWatching() {
        if (loadingVideos) return
        val dailyRemaining = watchHistory.getDailyRemaining()
        if (dailyRemaining == 0) {
            statusText.setText(R.string.daily_quota_used)
            Toast.makeText(this, R.string.daily_quota_used, Toast.LENGTH_LONG).show()
            return
        }
        if (videos.isEmpty()) {
            statusText.setText(R.string.no_videos)
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_LONG).show()
            return
        }
        val plannedCount = minOf(episodeCount, dailyRemaining)
        val playlist = VideoLibrary.buildPlaylist(videos, watchMode, plannedCount) {
            watchHistory.isWatched(it)
        }
        if (playlist.isEmpty()) {
            statusText.setText(R.string.all_videos_watched)
            return
        }
        if (playlist.size < plannedCount) {
            val message = getString(R.string.not_enough_videos, playlist.size)
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        preferences.edit()
            .putInt(STATE_EPISODE_COUNT, plannedCount)
            .putString(STATE_WATCH_MODE, watchMode.name)
            .apply()
        speechCoach?.stop()
        val intent = Intent(this, PlayerActivity::class.java)
            .putParcelableArrayListExtra(EXTRA_PLAYLIST, ArrayList(playlist))
            .putExtra(EXTRA_PROMISED_COUNT, playlist.size)
        startActivity(intent)
    }

    private fun updateUi() {
        syncEpisodeCountWithQuota()
        episodeCountText.text = getString(R.string.episode_count_value, episodeCount)
        listOf(
            normalButton to WatchMode.NORMAL,
            miniButton to WatchMode.MINI,
            randomButton to WatchMode.RANDOM
        ).forEach { (button, mode) ->
            button.isSelected = mode == watchMode
            button.setTextColor(
                if (mode == watchMode) getColor(R.color.text_primary)
                else getColor(R.color.text_secondary)
            )
        }

        val dailyCount = watchHistory.getDailyCount()
        val dailyLimit = watchHistory.getDailyLimit()
        dailyProgressBar.max = dailyLimit
        dailyProgressBar.progress = dailyCount
        dailyProgressText.text = getString(
            R.string.daily_status,
            dailyCount,
            dailyLimit,
            watchHistory.getDailyRemaining()
        )
        startButton.isEnabled = watchHistory.getDailyRemaining() > 0
        startButton.setText(
            if (watchHistory.getDailyRemaining() > 0) {
                R.string.start_watching
            } else {
                R.string.daily_quota_used_short
            }
        )

        if (!loadingVideos) {
            val savedUri = preferences.getString(STATE_SOURCE_URI, null)
            statusText.text = when {
                savedUri == null -> getString(R.string.source_not_selected)
                videos.isEmpty() -> getString(R.string.no_videos)
                savedUri == DEVICE_LIBRARY_URI -> getString(R.string.source_media_count, videos.size)
                else -> {
                    val available = VideoLibrary.filterByMode(videos, watchMode).size
                    getString(R.string.source_selected, "已找到 $available 个视频")
                }
            }
        }
    }

    private fun selectableEpisodeMaximum(): Int {
        val remaining = watchHistory.getDailyRemaining()
        return if (remaining == 0) 1 else minOf(MAX_EPISODES, watchHistory.getDailyLimit(), remaining)
    }

    private fun syncEpisodeCountWithQuota() {
        episodeCount = episodeCount.coerceIn(1, selectableEpisodeMaximum())
    }

    private fun openParentCenter() {
        if (preferences.contains(PARENT_PIN_HASH)) {
            showPinDialog(
                getString(R.string.pin_verify),
                requireNewPin = false
            ) { showParentOptions() }
        } else {
            showPinDialog(
                getString(R.string.pin_first_setup),
                requireNewPin = true
            ) { showParentOptions() }
        }
    }

    private fun showPinDialog(
        message: String,
        requireNewPin: Boolean,
        onSuccess: () -> Unit
    ) {
        val dialog = createStyledDialog(R.layout.dialog_parent_pin)
        val contentView = dialog.findViewById<android.view.View>(android.R.id.content)
        val pinDisplay = contentView.findViewById<TextView>(R.id.pinDisplay)
        val pinHint = contentView.findViewById<TextView>(R.id.pinHint)
        val digits = StringBuilder()
        var errorMessage: String? = null

        contentView.findViewById<TextView>(R.id.pinMessage).text = message

        fun renderPin() {
            pinDisplay.text = if (digits.isEmpty()) {
                "○ ○ ○ ○"
            } else {
                (0 until digits.length).joinToString(" ") { "●" }
            }
            pinHint.text = errorMessage ?: getString(R.string.pin_hint)
        }

        fun appendDigit(digit: Int) {
            errorMessage = null
            if (digits.length < 6) {
                digits.append(digit)
            }
            renderPin()
        }

        listOf(
            R.id.pin1 to 1,
            R.id.pin2 to 2,
            R.id.pin3 to 3,
            R.id.pin4 to 4,
            R.id.pin5 to 5,
            R.id.pin6 to 6,
            R.id.pin7 to 7,
            R.id.pin8 to 8,
            R.id.pin9 to 9,
            R.id.pin0 to 0
        ).forEach { (buttonId, digit) ->
            contentView.findViewById<Button>(buttonId).setOnClickListener {
                appendDigit(digit)
            }
        }

        contentView.findViewById<Button>(R.id.pinClear).setOnClickListener {
            digits.clear()
            errorMessage = null
            renderPin()
        }
        contentView.findViewById<Button>(R.id.pinDelete).setOnClickListener {
            if (digits.isNotEmpty()) {
                digits.deleteCharAt(digits.length - 1)
            }
            errorMessage = null
            renderPin()
        }
        renderPin()
        contentView.findViewById<Button>(R.id.pinCancelButton).setOnClickListener {
            dialog.dismiss()
        }
        contentView.findViewById<Button>(R.id.pinConfirmButton).setOnClickListener {
            val pin = digits.toString()
            if (!Regex("^\\d{4,6}$").matches(pin)) {
                errorMessage = getString(R.string.pin_invalid)
                renderPin()
                return@setOnClickListener
            }
            if (requireNewPin) {
                preferences.edit().putString(PARENT_PIN_HASH, hashPin(pin)).apply()
                Toast.makeText(this, R.string.pin_reset_done, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onSuccess()
                return@setOnClickListener
            }
            if (hashPin(pin) != preferences.getString(PARENT_PIN_HASH, null)) {
                digits.clear()
                errorMessage = getString(R.string.pin_wrong)
                renderPin()
                return@setOnClickListener
            }
            dialog.dismiss()
            onSuccess()
        }
        contentView.findViewById<Button>(R.id.pin0).requestFocus()
        dialog.show()
    }

    private fun showParentOptions() {
        val dialog = createStyledDialog(R.layout.dialog_parent_center)
        val contentView = dialog.findViewById<android.view.View>(android.R.id.content)
        val limitButton = contentView.findViewById<Button>(R.id.parentLimitButton)
        val summaryText = contentView.findViewById<TextView>(R.id.parentSummaryText)

        fun refreshParentPanel() {
            limitButton.text = getString(R.string.parent_option_limit, watchHistory.getDailyLimit())
            limitButton.setTextColor(getColor(R.color.text_primary))
            summaryText.text = getString(
                R.string.parent_center_summary,
                watchHistory.getDailyCount(),
                watchHistory.getDailyLimit(),
                watchHistory.getWatchedCount()
            )
        }

        refreshParentPanel()
        limitButton.setOnClickListener {
            showDailyLimitDialog {
                refreshParentPanel()
                updateUi()
            }
        }
        contentView.findViewById<Button>(R.id.parentResetPinButton).setOnClickListener {
            dialog.dismiss()
            showPinDialog(
                getString(R.string.pin_first_setup),
                requireNewPin = true
            ) {
                Toast.makeText(this, R.string.pin_reset_done, Toast.LENGTH_SHORT).show()
                showParentOptions()
            }
        }
        contentView.findViewById<Button>(R.id.parentResetTodayButton).setOnClickListener {
            watchHistory.resetToday()
            refreshParentPanel()
            updateUi()
            Toast.makeText(this, R.string.pin_limit_reset_done, Toast.LENGTH_LONG).show()
        }
        contentView.findViewById<Button>(R.id.parentResetHistoryButton).setOnClickListener {
            watchHistory.clearWatchedRecordsAndProgress()
            refreshParentPanel()
            updateUi()
            Toast.makeText(this, R.string.history_reset_done, Toast.LENGTH_LONG).show()
        }
        contentView.findViewById<Button>(R.id.parentCloseButton).setOnClickListener {
            dialog.dismiss()
        }
        limitButton.requestFocus()
        dialog.show()
    }

    private fun showDailyLimitDialog(onChanged: () -> Unit = {}) {
        val dialog = createStyledDialog(R.layout.dialog_parent_limit)
        val contentView = dialog.findViewById<android.view.View>(android.R.id.content)
        val grid = contentView.findViewById<GridLayout>(R.id.limitGrid)
        val currentLimit = watchHistory.getDailyLimit()
        val density = resources.displayMetrics.density
        val spacing = (8 * density).toInt()

        (1..WatchHistory.MAX_DAILY_LIMIT).forEach { limit ->
            val button = Button(this)
            button.text = getString(R.string.daily_limit_choice, limit)
            button.textSize = 23f
            button.setTextColor(getColor(R.color.text_primary))
            button.isAllCaps = false
            button.stateListAnimator = null
            button.background = getDrawable(R.drawable.parent_limit_button)
            button.isSelected = limit == currentLimit
            button.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = (72 * density).toInt()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, spacing, spacing)
            }
            button.setOnClickListener {
                watchHistory.setDailyLimit(limit)
                onChanged()
                dialog.dismiss()
            }
            grid.addView(button)
        }
        dialog.show()
    }

    private fun createStyledDialog(layoutId: Int): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layoutInflater.inflate(layoutId, null))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
        }
        return dialog
    }




    private fun hashPin(pin: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun speakWelcome() {
        if (!speechReady) return
        speechCoach?.speak(getString(R.string.speak_welcome, episodeCount))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_EPISODE_COUNT, episodeCount)
        outState.putSerializable(STATE_WATCH_MODE, watchMode)
    }

    override fun onStart() {
        super.onStart()
        syncEpisodeCountWithQuota()
        updateUi()
        if (speechReady) speakWelcome()
    }

    override fun onDestroy() {
        speechCoach?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCES_NAME = "yoyo_cartoon"
        private const val STATE_SOURCE_URI = "source_uri"
        private const val STATE_EPISODE_COUNT = "episode_count"
        private const val STATE_WATCH_MODE = "watch_mode"
        private const val PARENT_PIN_HASH = "parent_pin_hash"
        private const val REQUEST_FOLDER = 1001
        private const val REQUEST_VIDEO_PERMISSION = 1002
        private const val DEVICE_LIBRARY_URI = "device-media"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_PROMISED_COUNT = "promised_count"
        private const val DEFAULT_EPISODES = 4
        private const val MAX_EPISODES = 12
    }
}
