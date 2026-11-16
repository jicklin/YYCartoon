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
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Window
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var dailyProgressBar: ProgressBar
    private lateinit var dailyProgressText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var chooseEpisodeButton: Button
    private lateinit var chooseFolderButton: Button
    private lateinit var parentCenterButton: Button

    private var videos: List<VideoItem> = emptyList()
    private var shows: List<String> = emptyList()
    private var selectedShow = VideoItem.DEFAULT_SHOW
    private var selectedSeason = VideoItem.DEFAULT_SEASON
    private var loadingVideos = false
    private lateinit var watchHistory: WatchHistory
    private var speechCoach: SpeechCoach? = null
    private var speechReady = false
    private val dailyRefreshHandler = Handler(Looper.getMainLooper())
    private val dailyRefresh = Runnable {
        updateUi()
        scheduleNextDayRefresh()
    }

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
        dailyProgressBar = findViewById(R.id.dailyProgressBar)
        dailyProgressText = findViewById(R.id.dailyProgressText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        chooseEpisodeButton = findViewById(R.id.chooseEpisodeButton)
        chooseFolderButton = findViewById(R.id.chooseFolderButton)
        parentCenterButton = findViewById(R.id.parentCenterButton)
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        selectedShow = savedInstanceState?.getString(STATE_SELECTED_SHOW)
            ?: preferences.getString(STATE_SELECTED_SHOW, VideoItem.DEFAULT_SHOW).orEmpty()
        selectedSeason = savedInstanceState?.getString(STATE_SELECTED_SEASON)
            ?: preferences.getString(STATE_SELECTED_SEASON, VideoItem.DEFAULT_SEASON).orEmpty()
    }

    private fun setupListeners() {
        startButton.setOnClickListener { startWatching() }
        chooseEpisodeButton.setOnClickListener { showVideoPicker() }
        chooseFolderButton.setOnClickListener { chooseFolder() }
        parentCenterButton.setOnClickListener { openParentCenter() }
        startButton.post { startButton.requestFocus() }
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
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
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
        if (requestCode == REQUEST_PLAY_VIDEO) {
            if (resultCode == Activity.RESULT_OK && videos.isNotEmpty()) {
                showVideoPicker()
            }
            return
        }
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
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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

    private fun loadFolder(uri: Uri, forceRebuild: Boolean = false) {
        if (loadingVideos) return
        loadingVideos = true
        statusText.setText(R.string.loading_videos)
        Thread {
            val loaded = runCatching { VideoLibrary.load(this, uri, forceRebuild) }
                .onFailure { Log.e("YYCartoon", "Failed to load indexed videos", it) }
                .getOrDefault(
                    VideoLibrary.IndexResult(
                        videos = emptyList(),
                        shows = emptyList(),
                        fromCache = false,
                        savedToUsb = false
                    )
                )
            runOnUiThread {
                loadingVideos = false
                videos = loaded.videos
                shows = loaded.shows
                selectedShow = selectedShow.takeIf { shows.contains(it) }
                    ?: shows.firstOrNull()
                    ?: VideoItem.DEFAULT_SHOW
                val availableSeasons = VideoLibrary.seasonsFor(videos, selectedShow)
                selectedSeason = selectedSeason.takeIf { availableSeasons.contains(it) }
                    ?: availableSeasons.firstOrNull()
                    ?: VideoItem.DEFAULT_SEASON
                preferences.edit().putString(STATE_SELECTED_SEASON, selectedSeason).apply()
                if (loaded.videos.isNotEmpty() && !loaded.savedToUsb) {
                    Toast.makeText(this, R.string.index_saved_locally, Toast.LENGTH_LONG).show()
                }
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
                shows = loaded.map { it.showName }.distinct().sorted()
                selectedShow = selectedShow.takeIf { shows.contains(it) }
                    ?: shows.firstOrNull()
                    ?: VideoItem.DEFAULT_SHOW
                selectedSeason = VideoLibrary.seasonsFor(videos, selectedShow).firstOrNull()
                    ?: VideoItem.DEFAULT_SEASON
                selectedSeason = VideoItem.DEFAULT_SEASON
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

    private fun startWatching() {
        if (loadingVideos) return
        if (watchHistory.getDailyRemaining() == 0) {
            statusText.setText(R.string.daily_quota_used)
            Toast.makeText(this, R.string.daily_quota_used, Toast.LENGTH_LONG).show()
            return
        }
        if (videos.isEmpty()) {
            statusText.setText(R.string.no_videos)
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_LONG).show()
            return
        }
        val lastVideo = watchHistory.getLastPlayedVideo()
        val startVideo = videos.firstOrNull { it.uri == lastVideo?.uri && !watchHistory.isWatched(it) }
            ?: videos.firstOrNull { it.showName == lastVideo?.showName && !watchHistory.isWatched(it) }
            ?: videos.firstOrNull { it.showName == lastVideo?.showName }
            ?: videos.firstOrNull()
        if (startVideo == null) {
            statusText.setText(R.string.no_videos)
            return
        }
        launchPlaylist(VideoLibrary.playlistFrom(videos, startVideo))
    }

    private fun launchPlaylist(playlist: List<VideoItem>) {
        if (playlist.isEmpty()) return
        preferences.edit()
            .putString(STATE_SELECTED_SHOW, selectedShow)
            .putString(STATE_SELECTED_SEASON, selectedSeason)
            .apply()
        speechCoach?.stop()
        val intent = Intent(this, PlayerActivity::class.java)
            .putParcelableArrayListExtra(EXTRA_PLAYLIST, ArrayList(playlist))
        startActivityForResult(intent, REQUEST_PLAY_VIDEO)
    }

    private fun updateUi() {
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
        if (watchHistory.getDailyRemaining() == 0) {
            startButton.setText(R.string.daily_quota_used_short)
        } else {
            val lastVideo = watchHistory.getLastPlayedVideo()
            val hasResume = videos.any { it.uri == lastVideo?.uri && watchHistory.getSavedPosition(it) > 0 }
            startButton.setText(
                if (hasResume) R.string.resume_last_episode else R.string.start_watching
            )
        }
        chooseEpisodeButton.text = getString(R.string.choose_episode)
        chooseEpisodeButton.isEnabled = videos.isNotEmpty()

        if (!loadingVideos) {
            val savedUri = preferences.getString(STATE_SOURCE_URI, null)
            statusText.text = when {
                savedUri == null -> getString(R.string.source_not_selected)
                videos.isEmpty() -> getString(R.string.no_videos)
                savedUri == DEVICE_LIBRARY_URI -> getString(R.string.source_media_count, videos.size)
                else -> {
                    val showLabel = if (shows.size == 1) shows.first() else "${shows.size}部动画"
                    getString(R.string.source_selected, "$showLabel · ${videos.size} 个视频")
                }
            }
        }
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
                watchHistory.getTotalPlayCount(),
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
        contentView.findViewById<Button>(R.id.parentRebuildIndexButton).setOnClickListener {
            dialog.dismiss()
            rebuildVideoIndex()
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

    private fun showVideoPicker() {
        if (loadingVideos || videos.isEmpty()) {
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = createStyledDialog(R.layout.dialog_video_picker)
        val contentView = dialog.findViewById<android.view.View>(android.R.id.content)
        val showRow = contentView.findViewById<android.widget.LinearLayout>(R.id.showRow)
        val seasonRow = contentView.findViewById<android.widget.LinearLayout>(R.id.seasonRow)
        val videoGrid = contentView.findViewById<GridLayout>(R.id.videoGrid)
        val density = resources.displayMetrics.density
        val cardSpacing = (12 * density).toInt()
        val backdrops = listOf(
            R.drawable.library_backdrop_1,
            R.drawable.library_backdrop_2,
            R.drawable.library_backdrop_3,
            R.drawable.library_backdrop_4
        )

        fun renderVideos() {
            videoGrid.removeAllViews()
            VideoLibrary.episodesFor(videos, selectedShow, selectedSeason).forEach { video ->
                val card = layoutInflater.inflate(R.layout.item_episode_card, videoGrid, false)
                val nameText = card.findViewById<TextView>(R.id.episodeNameText)
                val stateText = card.findViewById<TextView>(R.id.episodeStateText)
                val playCountText = card.findViewById<TextView>(R.id.episodePlayCountText)
                val progress = watchHistory.getSavedPosition(video)
                nameText.text = video.displayName
                stateText.text = when {
                    progress > 0 -> "▶"
                    watchHistory.isWatched(video) -> "✓"
                    else -> ""
                }
                val playCount = watchHistory.getPlayCount(video)
                playCountText.text = getString(R.string.play_count_badge, playCount)
                playCountText.visibility = if (playCount > 0) android.view.View.VISIBLE else android.view.View.GONE
                card.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = (88 * density).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, 0, cardSpacing, cardSpacing)
                }
                card.setOnClickListener {
                    if (watchHistory.getDailyRemaining() == 0) {
                        Toast.makeText(this, R.string.daily_quota_used, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    selectedShow = video.showName
                    selectedSeason = video.season
                    launchPlaylist(VideoLibrary.playlistFrom(videos, video))
                }
                videoGrid.addView(card)
            }
        }

        fun renderSeasons() {
            seasonRow.removeAllViews()
            val availableSeasons = VideoLibrary.seasonsFor(videos, selectedShow)
                .ifEmpty { listOf(VideoItem.DEFAULT_SEASON) }
            availableSeasons.forEach { season ->
                val button = Button(this)
                button.text = season
                button.textSize = 17f
                button.isSingleLine = true
                button.ellipsize = TextUtils.TruncateAt.END
                button.isAllCaps = false
                button.stateListAnimator = null
                button.isSelected = season == selectedSeason
                button.background = getDrawable(R.drawable.season_chip)
                button.setTextColor(getColor(R.color.text_primary))
                button.minimumWidth = (128 * density).toInt()
                button.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    (48 * density).toInt()
                ).apply {
                    marginEnd = cardSpacing
                }
                button.setOnClickListener {
                    selectedSeason = season
                    preferences.edit().putString(STATE_SELECTED_SEASON, selectedSeason).apply()
                    renderSeasons()
                    renderVideos()
                    seasonRow.getChildAt(availableSeasons.indexOf(season))?.requestFocus()
                    updateUi()
                }
                seasonRow.addView(button)
            }
        }

        fun renderShows() {
            showRow.removeAllViews()
            val availableShows = shows.ifEmpty { listOf(VideoItem.DEFAULT_SHOW) }
            availableShows.forEach { showName ->
                val index = availableShows.indexOf(showName)
                val episodes = VideoLibrary.episodesFor(videos, showName)
                val card = layoutInflater.inflate(R.layout.item_show_card, showRow, false)
                val backdrop = card.findViewById<android.widget.ImageView>(R.id.showBackdrop)
                val showNameText = card.findViewById<TextView>(R.id.showNameText)
                val showCountText = card.findViewById<TextView>(R.id.showCountText)
                backdrop.setImageResource(backdrops[index % backdrops.size])
                showNameText.text = showName
                showCountText.text = getString(R.string.show_episode_count, episodes.size)
                card.isSelected = showName == selectedShow
                card.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = cardSpacing
                }
                card.setOnClickListener {
                    selectedShow = showName
                    selectedSeason = VideoLibrary.seasonsFor(videos, selectedShow).firstOrNull()
                        ?: VideoItem.DEFAULT_SEASON
                    renderShows()
                    renderSeasons()
                    renderVideos()
                    showRow.getChildAt(index)?.requestFocus()
                    updateUi()
                }
                showRow.addView(card)
            }
        }

        renderShows()
        renderSeasons()
        renderVideos()
        contentView.findViewById<Button>(R.id.videoPickerCloseButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
        val selectedShowIndex = shows.indexOf(selectedShow).coerceAtLeast(0)
        showRow.getChildAt(selectedShowIndex)?.requestFocus()
    }

    private fun rebuildVideoIndex() {
        val savedUri = preferences.getString(STATE_SOURCE_URI, null)
        val uri = savedUri
            ?.takeIf { it != DEVICE_LIBRARY_URI }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) {
            Toast.makeText(this, R.string.index_rebuild_need_folder, Toast.LENGTH_LONG).show()
            return
        }
        loadFolder(uri, forceRebuild = true)
    }

    private fun createStyledDialog(layoutId: Int): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(layoutInflater.inflate(layoutId, null))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
        }
        dialog.setOnShowListener {
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val desiredWidth = when (layoutId) {
                R.layout.dialog_video_picker -> 1000
                R.layout.dialog_parent_pin -> 500
                R.layout.dialog_parent_limit -> 620
                else -> 660
            }
            val width = minOf((desiredWidth * density).toInt(), screenWidth - (48 * density).toInt())
            val height = if (layoutId == R.layout.dialog_video_picker) {
                minOf((620 * density).toInt(), screenHeight - (32 * density).toInt())
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            dialog.window?.setLayout(width, height)
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
        speechCoach?.speak(getString(R.string.speak_welcome))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_SHOW, selectedShow)
        outState.putString(STATE_SELECTED_SEASON, selectedSeason)
    }

    override fun onStart() {
        super.onStart()
        updateUi()
        scheduleNextDayRefresh()
        if (speechReady) speakWelcome()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun scheduleNextDayRefresh() {
        dailyRefreshHandler.removeCallbacks(dailyRefresh)
        val nextDay = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        dailyRefreshHandler.postDelayed(
            dailyRefresh,
            (nextDay.timeInMillis - System.currentTimeMillis()).coerceAtLeast(1000L)
        )
    }

    override fun onStop() {
        dailyRefreshHandler.removeCallbacks(dailyRefresh)
        super.onStop()
    }

    override fun onDestroy() {
        speechCoach?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val PREFERENCES_NAME = "yoyo_cartoon"
        private const val STATE_SOURCE_URI = "source_uri"
        private const val STATE_SELECTED_SHOW = "selected_show"
        private const val STATE_SELECTED_SEASON = "selected_season"
        private const val PARENT_PIN_HASH = "parent_pin_hash"
        private const val REQUEST_FOLDER = 1001
        private const val REQUEST_VIDEO_PERMISSION = 1002
        private const val REQUEST_PLAY_VIDEO = 1003
        private const val DEVICE_LIBRARY_URI = "device-media"
        const val EXTRA_PLAYLIST = "playlist"
    }
}
