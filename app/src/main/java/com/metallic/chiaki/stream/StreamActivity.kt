// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

@RequiresApi(Build.VERSION_CODES.O)
class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener, TextToSpeech.OnInitListener {
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val MIN_SUB_FONT_SIZE = 10
		private const val MAX_SUB_FONT_SIZE = 80
		private const val REQUEST_CODE_IMPORT_CACHE_DB = 9042
		private const val SUBTITLE_AUTO_HIDE_MS = 10000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())
	private val ocrHandlerThread = android.os.HandlerThread("PS-OCR-Thread").apply { start() }
	private val ocrHandler by lazy { Handler(ocrHandlerThread.looper) }
	private val ocrScope = CoroutineScope(Dispatchers.Main + Job())
	private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

	enum class VoiceGenderMode {
		AUTO_AI,
		FEMALE,
		MALE
	}

	enum class TtsQueueMode {
		QUEUE_ALL,
		FLUSH_IMMEDIATE,
		SMART_QUEUE
	}

	enum class CropEditTarget {
		SUBTITLE,
		NOTE,
		POPUP_LEFT,
		POPUP_RIGHT
	}

	enum class PopupSide {
		LEFT,
		RIGHT
	}

	enum class PopupTtsMode {
		SMART_QUEUE,
		DUAL_CHANNEL
	}

	enum class PopupVoiceTone {
		BRIGHT,
		DEEP,
		NORMAL
	}

	enum class TranslationProvider(
		val displayName: String,
		val defaultModel: String,
		val defaultEndpoint: String,
		val keyHint: String,
		val keyLabel: String
	) {
		GOOGLE_FREE("Google ฟรี", "", "", "", ""),
		GEMINI("Gemini AI", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com/v1beta/interactions", "AIzaSy...", "Gemini API Key (Google AI Studio):"),
		GROQ("Groq AI", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1/chat/completions", "gsk_...", "Groq API Key (console.groq.com):"),
		CEREBRAS("Cerebras AI", "llama-3.3-70b", "https://api.cerebras.ai/v1/chat/completions", "csk-...", "Cerebras API Key (cloud.cerebras.ai):"),
		OPENAI("OpenAI", "gpt-4.1-mini", "https://api.openai.com/v1/chat/completions", "sk-...", "OpenAI API Key (platform.openai.com):"),
		OPENROUTER("OpenRouter", "google/gemini-2.5-flash-lite", "https://openrouter.ai/api/v1/chat/completions", "sk-or-...", "OpenRouter API Key (openrouter.ai):"),
		CUSTOM("Custom API", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions", "sk-...", "API Key (Bearer Token):")
	}

	data class PendingPopupSpeech(
		val side: PopupSide,
		val english: String,
		val thai: String,
		val createdAtMs: Long = System.currentTimeMillis()
	)

	data class SubtitleHistoryItem(
		val english: String,
		val thai: String,
		val timestamp: Long = System.currentTimeMillis()
	)

	private var tts: TextToSpeech? = null
	private var popupTts: TextToSpeech? = null
	private var isPopupTtsReady = false
	private var isTtsEnabled = false
	private var voiceGenderMode = VoiceGenderMode.AUTO_AI
	private var ttsQueueMode = TtsQueueMode.SMART_QUEUE
	private val activeUtterances = java.util.Collections.synchronizedSet(mutableSetOf<String>())
	private var isSubtitleEnabled = true
	private var isAudioMuted = false
	private var isTextOnlyMode = false
	private var isTvBlackScreenMode = false
	private var lastTvOverlayTapTimeMs = 0L
	private var isCapturing = false
	private var captureFrameCounter = 0
	private var isTranslating = false
	private var pendingOcrEnglish = ""
	private var queuedLatestEnglish = ""
	private var lastTranslatedEnglish = ""
	private var lastTranslatedThai = ""
	private var lastSpokenThai = ""
	private var lastSpokenTimeMs = 0L
	private lateinit var cropPrefs: SharedPreferences
	private var isFilterShortTextEnabled = true
	private var isSkipSpeakerNameInTts = true
	private var speechSpeedRate = 1.25f

	// Multi-Provider Translation API, Local Cache, Samsung TV Overlay Server & Gemini AI TTS settings
	private lateinit var translationCacheDb: TranslationCacheDb
	private var tvOverlayServer: TvOverlayServer? = null
	private var isTvCastEnabled = true
	private var activeTranslationProvider = TranslationProvider.GOOGLE_FREE
	private var editingProvider = TranslationProvider.GOOGLE_FREE
	private var isLocalCacheEnabled = true
	private var geminiApiKey = ""
	private var isGeminiTtsEnabled = true
	private var geminiMediaPlayer: MediaPlayer? = null
	private val geminiAudioQueue = java.util.ArrayDeque<java.io.File>()
	private var popupMediaPlayer: MediaPlayer? = null
	private val popupGeminiAudioQueue = java.util.ArrayDeque<Pair<java.io.File, PopupSide>>()
	private var popupChimeToneGenerator: ToneGenerator? = null

	data class NoteBlockItem(
		val english: String,
		val rect: Rect,
		var thai: String = ""
	)

	// On-demand Note/Tutorial Crop Box, Top Quick Drawer & Auto Popup Zones (Left / Right)
	private var activeCropEditTarget = CropEditTarget.SUBTITLE
	private var isNoteCropBoxVisible = false
	private var isScanningNote = false
	private var isPopupZonesMode = false
	private var isPopupLeftEnabled = true
	private var isPopupRightEnabled = true
	private var isFilterPopupHudEnabled = true
	private var popupHoldDurationMs = 5000L

	// Popup TTS & Audio Separation settings
	private var isPopupTtsEnabled = true
	private var popupTtsMode = PopupTtsMode.SMART_QUEUE
	private var popupVoiceTone = PopupVoiceTone.BRIGHT
	private var popupVolume = 0.75f
	private var isPopupStereoPanEnabled = false
	private var isPopupChimeEnabled = true
	private var pendingSmartPopupSpeech: PendingPopupSpeech? = null

	// Live Popup OCR & Card state (independent from main subtitle!)
	@Volatile private var isScanningPopupLeft = false
	@Volatile private var isScanningPopupRight = false
	private var isTranslatingPopupLeft = false
	private var isTranslatingPopupRight = false
	private var lastPopupLeftEnglish = ""
	private var lastPopupLeftThai = ""
	private var isPopupLeftVisible = false
	private var lastPopupRightEnglish = ""
	private var lastPopupRightThai = ""
	private var isPopupRightVisible = false

	// Typewriter / progressive popup stabilization state (waits until text finishes typing before translating)
	private var pendingPopupLeftCandidate = ""
	private var pendingPopupLeftStableTicks = 0
	private var pendingPopupLeftFirstSeenMs = 0L
	private var queuedPopupLeftEnglish = ""

	private var pendingPopupRightCandidate = ""
	private var pendingPopupRightStableTicks = 0
	private var pendingPopupRightFirstSeenMs = 0L
	private var queuedPopupRightEnglish = ""

	private var lastNoteThai = ""
	private var lastNoteEnglish = ""

	private val hidePopupLeftRunnable = Runnable {
		if (::binding.isInitialized && !isDestroyed && !isFinishing) {
			hidePopupCard(PopupSide.LEFT)
		}
	}

	private val autoHideSubtitleRunnable = Runnable {
		if (::binding.isInitialized && !isDestroyed && !isFinishing) {
			subtitleHistory.clear()
			renderSubtitles()
		}
	}

	private val hidePopupRightRunnable = Runnable {
		if (::binding.isInitialized && !isDestroyed && !isFinishing) {
			hidePopupCard(PopupSide.RIGHT)
		}
	}

	private val hideNoteCardRunnable = Runnable {
		if (::binding.isInitialized && !isDestroyed && !isFinishing) {
			hideNoteTranslationCard()
		}
	}

	private val hideTopDrawerRunnable = Runnable {
		if (::binding.isInitialized && !isDestroyed && !isFinishing) {
			closeTopQuickDrawer()
		}
	}

	// Subtitle cascade history and styling options
	private val subtitleHistory = mutableListOf<SubtitleHistoryItem>()
	private var maxHistoryLines = 3
	private var currentSubTextColor = Color.parseColor("#00FF88")
	private var currentSubFontSize = 26
	private var currentSubPosition = "BOTTOM"
	private var currentSubVerticalPercent = 15
	private var currentSubBgOpacity = 80
	private var isSubtitleBgEnabled = true
	private var popupLeftVerticalPercent = 8
	private var popupRightVerticalPercent = 8
	private var isSyncingFontUI = false

	private val hideMenuButtonRunnable = Runnable {
		if (::binding.isInitialized && !binding.menuPanelContainer.isVisible) {
			binding.btnMenuToggle.animate().alpha(0.35f).setDuration(300).start()
		}
	}

	private val captureRunnable = object : Runnable {
		override fun run() {
			if (viewModel.session.state.value == StreamStateConnected && !isCapturing) {
				captureAndProcessSubtitle()
			}
			uiVisibilityHandler.postDelayed(this, 350)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		tts = TextToSpeech(this, this)
		popupTts = TextToSpeech(this) { status ->
			if (status == TextToSpeech.SUCCESS) {
				isPopupTtsReady = true
				popupTts?.language = Locale("th", "TH")
			}
		}

		cropPrefs = getSharedPreferences("ps_translator_crop", Context.MODE_PRIVATE)
		translationCacheDb = TranslationCacheDb(applicationContext)
		isFilterShortTextEnabled = cropPrefs.getBoolean("filter_short_ui", true)
		binding.filterShortTextSwitch.isChecked = isFilterShortTextEnabled
		setupCropEditor()

		// 3-Lines Circular Menu Button -> Liquid Glass Bottom Sheet
		binding.btnMenuToggle.setOnClickListener {
			if (!binding.menuPanelContainer.isVisible) {
				openSettingsSheet()
			} else {
				closeSettingsSheet()
			}
		}

		binding.btnCloseMenu.setOnClickListener {
			closeSettingsSheet()
		}

		binding.menuPanelContainer.setOnClickListener {
			closeSettingsSheet()
		}

		setupLiquidGlassMotion()

		binding.btnMenuBack.setOnClickListener {
			showMenuPage("MAIN")
		}
		binding.btnOpenSubText.setOnClickListener {
			showMenuPage("TEXT")
		}
		binding.btnOpenSubVoice.setOnClickListener {
			showMenuPage("VOICE")
		}
		binding.btnOpenSubMode.setOnClickListener {
			showMenuPage("MODE")
		}
		binding.btnOpenSubApi.setOnClickListener {
			showMenuPage("API")
		}

		setupGeminiApiControls()

		// Subtitle overlay switch (persisted)
		isSubtitleEnabled = cropPrefs.getBoolean("sub_overlay_enabled", true)
		binding.subOverlaySwitch.isChecked = isSubtitleEnabled
		binding.subCustomizationContainer.isVisible = isSubtitleEnabled
		binding.subOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
			isSubtitleEnabled = isChecked
			cropPrefs.edit().putBoolean("sub_overlay_enabled", isChecked).apply()
			binding.subCustomizationContainer.isVisible = isChecked
			binding.subtitleOverlayContainer.isVisible = isChecked && !isTextOnlyMode && subtitleHistory.isNotEmpty()
		}

		// Load subtitle customization preferences
		currentSubTextColor = try {
			Color.parseColor(cropPrefs.getString("sub_text_color", "#00FF88") ?: "#00FF88")
		} catch (_: Exception) {
			Color.parseColor("#00FF88")
		}
		currentSubFontSize = cropPrefs.getInt("sub_font_size_sp", 26).coerceIn(MIN_SUB_FONT_SIZE, MAX_SUB_FONT_SIZE)
		currentSubPosition = cropPrefs.getString("sub_position", "BOTTOM") ?: "BOTTOM"
		val defaultVertPct = if (currentSubPosition == "CENTER") 50 else 15
		currentSubVerticalPercent = cropPrefs.getInt("sub_vertical_percent", defaultVertPct).coerceIn(0, 100)
		currentSubBgOpacity = cropPrefs.getInt("sub_bg_opacity", 80).coerceIn(0, 100)
		isSubtitleBgEnabled = cropPrefs.getBoolean("sub_bg_enabled", true)
		popupLeftVerticalPercent = cropPrefs.getInt("popup_left_vertical_percent", 8).coerceIn(0, 100)
		popupRightVerticalPercent = cropPrefs.getInt("popup_right_vertical_percent", 8).coerceIn(0, 100)
		maxHistoryLines = cropPrefs.getInt("sub_history_limit", 3).coerceIn(1, 3)

		setupSubtitleCustomizationUI()
		applySubtitleStyles()

		// TTS voice switch (persisted)
		isTtsEnabled = cropPrefs.getBoolean("tts_voice_enabled", false)
		isSkipSpeakerNameInTts = cropPrefs.getBoolean("skip_speaker_name_tts", true)
		binding.ttsVoiceSwitch.isChecked = isTtsEnabled
		binding.switchApiMasterTts.isChecked = isTtsEnabled
		binding.useGeminiTtsSwitch.isVisible = isTtsEnabled
		binding.skipSpeakerNameInTtsSwitch.isChecked = isSkipSpeakerNameInTts
		binding.voiceGenderContainer.isVisible = isTtsEnabled
		binding.speechSpeedContainer.isVisible = isTtsEnabled
		binding.ttsQueueModeContainer.isVisible = isTtsEnabled
		binding.ttsVoiceSwitch.setOnCheckedChangeListener { _, isChecked ->
			setMasterTtsEnabled(isChecked)
		}
		binding.switchApiMasterTts.setOnCheckedChangeListener { _, isChecked ->
			setMasterTtsEnabled(isChecked)
		}

		binding.skipSpeakerNameInTtsSwitch.setOnCheckedChangeListener { _, isChecked ->
			isSkipSpeakerNameInTts = isChecked
			cropPrefs.edit().putBoolean("skip_speaker_name_tts", isChecked).apply()
		}

		val savedVoiceMode = cropPrefs.getString("voice_gender_mode", VoiceGenderMode.AUTO_AI.name)
		voiceGenderMode = try { VoiceGenderMode.valueOf(savedVoiceMode ?: VoiceGenderMode.AUTO_AI.name) } catch(_: Exception) { VoiceGenderMode.AUTO_AI }
		when (voiceGenderMode) {
			VoiceGenderMode.AUTO_AI -> binding.voiceGenderToggleGroup.check(R.id.btnVoiceAuto)
			VoiceGenderMode.FEMALE -> binding.voiceGenderToggleGroup.check(R.id.btnVoiceFemale)
			VoiceGenderMode.MALE -> binding.voiceGenderToggleGroup.check(R.id.btnVoiceMale)
		}

		binding.voiceGenderToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				voiceGenderMode = when (checkedId) {
					R.id.btnVoiceFemale -> VoiceGenderMode.FEMALE
					R.id.btnVoiceMale -> VoiceGenderMode.MALE
					else -> VoiceGenderMode.AUTO_AI
				}
				cropPrefs.edit().putString("voice_gender_mode", voiceGenderMode.name).apply()
			}
		}

		// Speech speed selection
		speechSpeedRate = cropPrefs.getFloat("speech_speed_rate", 1.25f)
		when {
			speechSpeedRate <= 1.05f -> binding.speechSpeedToggleGroup.check(R.id.btnSpeedNormal)
			speechSpeedRate >= 1.45f -> binding.speechSpeedToggleGroup.check(R.id.btnSpeedVeryFast)
			else -> binding.speechSpeedToggleGroup.check(R.id.btnSpeedFast)
		}

		binding.speechSpeedToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				speechSpeedRate = when (checkedId) {
					R.id.btnSpeedNormal -> 1.0f
					R.id.btnSpeedVeryFast -> 1.5f
					else -> 1.25f
				}
				cropPrefs.edit().putFloat("speech_speed_rate", speechSpeedRate).apply()
			}
		}

		// TTS queue mode selection (Queue All / Flush Immediate / Smart Queue)
		val savedQueueMode = cropPrefs.getString("tts_queue_mode", TtsQueueMode.SMART_QUEUE.name)
		ttsQueueMode = try { TtsQueueMode.valueOf(savedQueueMode ?: TtsQueueMode.SMART_QUEUE.name) } catch (_: Exception) { TtsQueueMode.SMART_QUEUE }
		when (ttsQueueMode) {
			TtsQueueMode.QUEUE_ALL -> binding.ttsQueueModeToggleGroup.check(R.id.btnQueueAdd)
			TtsQueueMode.FLUSH_IMMEDIATE -> binding.ttsQueueModeToggleGroup.check(R.id.btnQueueFlush)
			TtsQueueMode.SMART_QUEUE -> binding.ttsQueueModeToggleGroup.check(R.id.btnQueueSmart)
		}

		binding.ttsQueueModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				ttsQueueMode = when (checkedId) {
					R.id.btnQueueAdd -> TtsQueueMode.QUEUE_ALL
					R.id.btnQueueFlush -> TtsQueueMode.FLUSH_IMMEDIATE
					else -> TtsQueueMode.SMART_QUEUE
				}
				cropPrefs.edit().putString("tts_queue_mode", ttsQueueMode.name).apply()
			}
		}

		// Restore saved game audio mute state
		isAudioMuted = cropPrefs.getBoolean("game_audio_muted", false)
		viewModel.session.isAudioMuted = isAudioMuted
		if (isAudioMuted) {
			binding.btnMuteAudio.text = "เปิดเสียงเกมมือถือ"
			binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_off)
		} else {
			binding.btnMuteAudio.text = "ปิดเสียงเกมจากมือถือ"
			binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_up)
		}

		// Mute / Unmute game audio directly in native stream (keeps TTS translation voice loud and clear!)
		binding.btnMuteAudio.setOnClickListener {
			isAudioMuted = !isAudioMuted
			viewModel.session.isAudioMuted = isAudioMuted
			cropPrefs.edit().putBoolean("game_audio_muted", isAudioMuted).apply()
			if (isAudioMuted) {
				binding.btnMuteAudio.text = "เปิดเสียงเกมมือถือ"
				binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_off)
			} else {
				binding.btnMuteAudio.text = "ปิดเสียงเกมจากมือถือ"
				binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_up)
			}
			updateQuickDrawerUI()
		}

		// Text-Only Mode button
		binding.btnTextOnlyMode.setOnClickListener {
			isTextOnlyMode = !isTextOnlyMode
			updateTextOnlyModeUI()
			binding.menuPanelContainer.isVisible = false
			resetMenuButtonTimer()
		}

		binding.pureFullscreenLayout.setOnClickListener {
			isTextOnlyMode = false
			updateTextOnlyModeUI()
			resetMenuButtonTimer()
		}

		binding.mainStreamLayout.setOnClickListener {
			resetMenuButtonTimer()
		}

		val savedDisplayMode = cropPrefs.getString("stream_display_mode", TransformMode.FIT.name)
		when (savedDisplayMode) {
			TransformMode.STRETCH.name -> binding.displayModeToggle.check(R.id.display_mode_stretch_button)
			TransformMode.ZOOM.name -> binding.displayModeToggle.check(R.id.display_mode_zoom_button)
			else -> binding.displayModeToggle.check(R.id.display_mode_normal_button)
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				val mode = TransformMode.fromButton(checkedId)
				cropPrefs.edit().putString("stream_display_mode", mode.name).apply()
			}
			adjustStreamViewAspect()
		}

		// Display streaming directly via SurfaceView
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		// Start periodic OCR capture loop
		setupPopupAudioControls()
		setupTopQuickDrawer()
		setupNoteTranslationFeature()
		setupPopupZonesControls()
		updateQuickDrawerUI()
		updateGeminiApiStatusUI()
		applyViceCityThemeAndFont()
		uiVisibilityHandler.postDelayed(captureRunnable, 1500)
		resetMenuButtonTimer()
	}

	private fun applyViceCityThemeAndFont() {
		val gtaPromptFont = try {
			androidx.core.content.res.ResourcesCompat.getFont(this, R.font.gta_prompt_light)
		} catch (_: Exception) {
			null
		}
		if (gtaPromptFont != null) {
			applyTypefaceRecursively(binding.root, gtaPromptFont)
		}
		// Apply thicker GTA Art Deco + Prompt SemiBold ONLY to main translated subtitles
		val subBoldFont = try {
			androidx.core.content.res.ResourcesCompat.getFont(this, R.font.gta_prompt_semibold)
		} catch (_: Exception) {
			null
		}
		if (subBoldFont != null) {
			binding.translatedSubText.setTypeface(subBoldFont, android.graphics.Typeface.BOLD)
			binding.translatedSubText1.setTypeface(subBoldFont, android.graphics.Typeface.BOLD)
			binding.translatedSubText2.setTypeface(subBoldFont, android.graphics.Typeface.BOLD)
			binding.pureFullscreenText.setTypeface(subBoldFont, android.graphics.Typeface.BOLD)
			binding.pureFullscreenText1.setTypeface(subBoldFont, android.graphics.Typeface.BOLD)
			binding.pureFullscreenText2.setTypeface(subBoldFont, android.graphics.Typeface.BOLD)
		}
		applyViceCityMenuColorsRecursively(binding.menuSheetContainer)
	}

	private fun applyTypefaceRecursively(view: View, typeface: android.graphics.Typeface) {
		if (view is TextView) {
			val style = view.typeface?.style ?: android.graphics.Typeface.NORMAL
			view.setTypeface(typeface, style)
		} else if (view is ViewGroup) {
			for (i in 0 until view.childCount) {
				applyTypefaceRecursively(view.getChildAt(i), typeface)
			}
		}
	}

	private fun applyViceCityMenuColorsRecursively(view: View) {
		if (view is com.google.android.material.switchmaterial.SwitchMaterial) {
			view.setTextColor(Color.parseColor("#FF80BF"))
		} else if (view is TextView && view !is com.google.android.material.button.MaterialButton && view !is EditText) {
			val cur = view.currentTextColor
			// Convert neutral grey/white labels inside the settings menu into Vice City Sunset Orange & Lavender Pink
			if (cur == Color.parseColor("#AAAAAA") || cur == Color.parseColor("#CCCCCC")) {
				view.setTextColor(Color.parseColor("#FF9A4A"))
			} else if (cur == Color.parseColor("#EEEEEE") || cur == Color.parseColor("#DDDDDD")) {
				view.setTextColor(Color.parseColor("#F7E8FF"))
			} else if (cur == Color.parseColor("#88FFFFFF") || cur == Color.parseColor("#99FFFFFF")) {
				view.setTextColor(Color.parseColor("#D8B6F8"))
			}
		} else if (view is ViewGroup) {
			for (i in 0 until view.childCount) {
				applyViceCityMenuColorsRecursively(view.getChildAt(i))
			}
		}
	}

	private fun resetMenuButtonTimer() {
		binding.btnMenuToggle.alpha = 1.0f
		uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
		uiVisibilityHandler.postDelayed(hideMenuButtonRunnable, 3500)
	}

	private fun setTvBlackScreenMode(enabled: Boolean) {
		isTvBlackScreenMode = enabled
		if (enabled) {
			if (!isTvCastEnabled) {
				isTvCastEnabled = true
				cropPrefs.edit().putBoolean("tv_cast_overlay_enabled", true).apply()
				binding.switchTvCastOverlay.isChecked = true
				tvOverlayServer?.isTvCastEnabled = true
				updateTvCastStatusUI(tvOverlayServer?.connectedTvName)
			}
			binding.menuPanelContainer.isVisible = false
			closeTopQuickDrawer()
			binding.tvBlackLockOverlay.isVisible = true
			val lp = window.attributes
			lp.screenBrightness = 0.01f
			window.attributes = lp
			syncTvOverlayState()
			Toast.makeText(this, "เข้าสู่โหมดทีวี (จอดำสนิท) — แตะหน้าจอ 2 ครั้งติดกันเพื่อออก", Toast.LENGTH_SHORT).show()
		} else {
			binding.tvBlackLockOverlay.isVisible = false
			val lp = window.attributes
			lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
			window.attributes = lp
			resetMenuButtonTimer()
		}
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed() {
		if (::binding.isInitialized && isTvBlackScreenMode) {
			setTvBlackScreenMode(false)
			return
		}
		super.onBackPressed()
	}

	private fun updateTextOnlyModeUI() {
		if (isTextOnlyMode) {
			binding.pureFullscreenLayout.isVisible = true
			binding.aspectRatioLayout.visibility = View.VISIBLE
			binding.subtitleOverlayContainer.isVisible = false
			binding.floatingNoteCropBox.isVisible = false
			binding.btnTextOnlyMode.text = "สลับเป็นโหมดสตรีมภาพ"
			binding.btnTextOnlyFloat.setColorFilter(Color.parseColor("#00E5FF"))
		} else {
			binding.pureFullscreenLayout.isVisible = false
			binding.aspectRatioLayout.visibility = View.VISIBLE
			binding.subtitleOverlayContainer.isVisible = isSubtitleEnabled && subtitleHistory.isNotEmpty()
			binding.floatingNoteCropBox.isVisible = !isPopupZonesMode && isNoteCropBoxVisible
			if (!isPopupZonesMode && isNoteCropBoxVisible) syncFloatingNoteCropBox()
			binding.btnTextOnlyMode.text = "โหมดโชว์เฉพาะตัวหนังสือ (จอดำ)"
			binding.btnTextOnlyFloat.setColorFilter(Color.parseColor("#FFFFFF"))
		}
		syncPopupCardVisibility()
	}

	override fun onInit(status: Int) {
		if (status == TextToSpeech.SUCCESS) {
			tts?.language = Locale("th", "TH")
			tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
				override fun onStart(utteranceId: String?) {}
				override fun onDone(utteranceId: String?) {
					if (utteranceId != null) activeUtterances.remove(utteranceId)
					uiVisibilityHandler.post { tryFlushPendingSmartPopupSpeech() }
				}
				override fun onError(utteranceId: String?) {
					if (utteranceId != null) activeUtterances.remove(utteranceId)
					uiVisibilityHandler.post { tryFlushPendingSmartPopupSpeech() }
				}
				override fun onStop(utteranceId: String?, interrupted: Boolean) {
					if (utteranceId != null) activeUtterances.remove(utteranceId)
					uiVisibilityHandler.post { tryFlushPendingSmartPopupSpeech() }
				}
			})
		}
	}

	private fun startSubtitleTranslation(text: String) {
		isTranslating = true
		pendingOcrEnglish = text
		android.util.Log.d("PSTranslatorOCR", "Detected dialogue: $text")
		ocrScope.launch {
			try {
				val englishForTranslate = if (isSkipSpeakerNameInTts) stripSpeakerPrefix(text) else text
				val rawThai = translateToThai(englishForTranslate, isPopup = false)
				val thai = if (isSkipSpeakerNameInTts) stripSpeakerPrefix(rawThai) else rawThai
				android.util.Log.d("PSTranslatorOCR", "Translated text: $thai")
				val isDuplicateThai = isSimilarThai(thai, lastTranslatedThai) ||
					subtitleHistory.take(2).any { isSimilarThai(thai, it.thai) }
				if (!isDuplicateThai) {
					if (thai != englishForTranslate) {
						lastTranslatedEnglish = text
					}
					lastTranslatedThai = thai
					subtitleHistory.add(0, SubtitleHistoryItem(englishForTranslate, thai))
					while (subtitleHistory.size > maxHistoryLines) {
						subtitleHistory.removeAt(subtitleHistory.size - 1)
					}
					renderSubtitles()
					uiVisibilityHandler.removeCallbacks(autoHideSubtitleRunnable)
					uiVisibilityHandler.postDelayed(autoHideSubtitleRunnable, SUBTITLE_AUTO_HIDE_MS)
					if (isTtsEnabled && thai != englishForTranslate) {
						speakTranslation(text, thai)
					}
				}
			} finally {
				isTranslating = false
				val next = queuedLatestEnglish
				if (next.isNotEmpty()) {
					queuedLatestEnglish = ""
					if (!isSimilarText(next, lastTranslatedEnglish)) {
						startSubtitleTranslation(next)
					}
				}
			}
		}
	}

	private fun captureAndProcessSubtitle() {
		val surfaceView = binding.surfaceView
		if (surfaceView.holder?.surface?.isValid != true) return
		if (surfaceView.width <= 100 || surfaceView.height <= 100) return
		if (isCapturing || isScanningNote) return

		val isPopupSettling = pendingPopupLeftCandidate.isNotEmpty() || pendingPopupRightCandidate.isNotEmpty()
		val shouldScanPopupsThisTick = isPopupZonesMode &&
			(isPopupSettling || (++captureFrameCounter % 2 == 0)) &&
			!binding.cropEditorOverlay.isVisible &&
			!binding.menuPanelContainer.isVisible

		val fullBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
		isCapturing = true

		try {
			PixelCopy.request(surfaceView, fullBitmap, { copyResult ->
				if (copyResult == PixelCopy.SUCCESS) {
					var leftPopupBmp: Bitmap? = null
					var rightPopupBmp: Bitmap? = null
					try {
						val cropLeftNorm = cropPrefs.getFloat("crop_left", 0.18f).coerceIn(0f, 0.85f)
						val cropTopNorm = cropPrefs.getFloat("crop_top", 0.72f).coerceIn(0f, 0.90f)
						val cropWidthNorm = cropPrefs.getFloat("crop_width", 0.64f).coerceIn(0.10f, 1f - cropLeftNorm)
						val cropHeightNorm = cropPrefs.getFloat("crop_height", 0.22f).coerceIn(0.06f, 1f - cropTopNorm)

						val cropX = (1920 * cropLeftNorm).toInt().coerceIn(0, 1919)
						val cropY = (1080 * cropTopNorm).toInt().coerceIn(0, 1079)
						val cropW = (1920 * cropWidthNorm).toInt().coerceIn(40, 1920 - cropX)
						val cropH = (1080 * cropHeightNorm).toInt().coerceIn(30, 1080 - cropY)

						val subBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)

						// Reuse the exact same captured frame for Left/Right Popup Zones (zero extra PixelCopy overhead!)
						if (shouldScanPopupsThisTick) {
							if (isPopupLeftEnabled && !isScanningPopupLeft) {
								isScanningPopupLeft = true
								leftPopupBmp = cropPopupZoneBitmap(fullBitmap, PopupSide.LEFT)
								if (leftPopupBmp == null) isScanningPopupLeft = false
							}
							if (isPopupRightEnabled && !isScanningPopupRight) {
								isScanningPopupRight = true
								rightPopupBmp = cropPopupZoneBitmap(fullBitmap, PopupSide.RIGHT)
								if (rightPopupBmp == null) isScanningPopupRight = false
							}
						}

						fullBitmap.recycle()

						leftPopupBmp?.let { processPopupZoneBitmap(PopupSide.LEFT, it) }
						rightPopupBmp?.let { processPopupZoneBitmap(PopupSide.RIGHT, it) }

						val ocrBitmap = if (cropW < 700) {
							val scaled = Bitmap.createScaledBitmap(subBitmap, cropW * 2, cropH * 2, true)
							subBitmap.recycle()
							scaled
						} else {
							subBitmap
						}

						val image = InputImage.fromBitmap(ocrBitmap, 0)
						textRecognizer.process(image)
							.addOnSuccessListener { visionText ->
								val rawText = visionText.text.trim().replace("\n", " ")
								val fixedText = fixOcrText(rawText)
								val text = fixedText.replace(Regex("\\s+"), " ").trim()
								if (text.isNotEmpty() && isDialogueSubtitle(text)) {
									val isDuplicateOfPending = isTranslating && isSimilarText(text, pendingOcrEnglish)
									val isDuplicateOfQueued = queuedLatestEnglish.isNotEmpty() && isSimilarText(text, queuedLatestEnglish)
									val isDuplicateOfLast = isSimilarText(text, lastTranslatedEnglish)
									val isDuplicateOfRecent = subtitleHistory.take(2).any { isSimilarText(text, it.english) }

									if (!isDuplicateOfPending && !isDuplicateOfQueued && !isDuplicateOfLast && !isDuplicateOfRecent) {
										if (isTranslating) {
											// Queue newest subtitle immediately instead of dropping it
											queuedLatestEnglish = text
										} else {
											startSubtitleTranslation(text)
										}
									}
								}
							}
							.addOnFailureListener { e ->
								android.util.Log.e("PSTranslatorOCR", "OCR recognition error", e)
							}
							.addOnCompleteListener {
								ocrBitmap.recycle()
								isCapturing = false
							}
					} catch (e: Exception) {
						if (!fullBitmap.isRecycled) fullBitmap.recycle()
						leftPopupBmp?.let { if (!it.isRecycled) it.recycle(); isScanningPopupLeft = false }
						rightPopupBmp?.let { if (!it.isRecycled) it.recycle(); isScanningPopupRight = false }
						isCapturing = false
						android.util.Log.e("PSTranslatorOCR", "Bitmap processing error", e)
					}
				} else {
					fullBitmap.recycle()
					isCapturing = false
					android.util.Log.w("PSTranslatorOCR", "PixelCopy failed with status: $copyResult")
				}
			}, ocrHandler)
		} catch (e: Exception) {
			fullBitmap.recycle()
			isCapturing = false
			android.util.Log.e("PSTranslatorOCR", "PixelCopy request error", e)
		}
	}

	private fun setupSubtitleCustomizationUI() {
		// 1. Vertical Position Slider (0% = Bottom .. 100% = Top)
		binding.subPositionSeekBar.max = 100
		binding.subPositionSeekBar.progress = currentSubVerticalPercent
		updateSubPositionLabel()
		binding.subPositionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (fromUser) {
					currentSubVerticalPercent = progress.coerceIn(0, 100)
					currentSubPosition = if (currentSubVerticalPercent >= 40) "CENTER" else "BOTTOM"
					updateSubPositionLabel()
					if (subtitleHistory.isEmpty()) {
						subtitleHistory.add(SubtitleHistoryItem("Subtitle position preview", "ตัวอย่างตำแหน่งซับไตเติ้ล"))
					}
					uiVisibilityHandler.removeCallbacks(autoHideSubtitleRunnable)
					uiVisibilityHandler.postDelayed(autoHideSubtitleRunnable, SUBTITLE_AUTO_HIDE_MS)
					applySubtitleStyles()
				}
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				cropPrefs.edit()
					.putInt("sub_vertical_percent", currentSubVerticalPercent)
					.putString("sub_position", currentSubPosition)
					.apply()
			}
		})

		// 2. Color toggle
		when (currentSubTextColor) {
			Color.parseColor("#FFFFFF") -> binding.colorToggleGroup.check(R.id.btnColorWhite)
			Color.parseColor("#00E5FF") -> binding.colorToggleGroup.check(R.id.btnColorCyan)
			Color.parseColor("#FFD700") -> binding.colorToggleGroup.check(R.id.btnColorYellow)
			Color.parseColor("#FF9100") -> binding.colorToggleGroup.check(R.id.btnColorOrange)
			Color.parseColor("#FF4081") -> binding.colorToggleGroup.check(R.id.btnColorPink)
			else -> binding.colorToggleGroup.check(R.id.btnColorGreen)
		}
		binding.colorToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				currentSubTextColor = when (checkedId) {
					R.id.btnColorWhite -> Color.parseColor("#FFFFFF")
					R.id.btnColorCyan -> Color.parseColor("#00E5FF")
					R.id.btnColorYellow -> Color.parseColor("#FFD700")
					R.id.btnColorOrange -> Color.parseColor("#FF9100")
					R.id.btnColorPink -> Color.parseColor("#FF4081")
					else -> Color.parseColor("#00FF88")
				}
				val hexColor = String.format("#%06X", 0xFFFFFF and currentSubTextColor)
				cropPrefs.edit().putString("sub_text_color", hexColor).apply()
				applySubtitleStyles()
			}
		}

		// 3. Font Size Presets, Stepper & Slider
		syncFontSizeToggleState()
		binding.fontSizeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked && !isSyncingFontUI) {
				currentSubFontSize = when (checkedId) {
					R.id.btnSizeSmall -> 16
					R.id.btnSizeMedium -> 26
					R.id.btnSizeLarge -> 40
					R.id.btnSizeXLarge -> 56
					R.id.btnSizeXXLarge -> 72
					else -> 26
				}
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
				syncFontSizeToggleState()
				applySubtitleStyles()
			}
		}

		binding.btnFontDecrease.setOnClickListener {
			if (currentSubFontSize > MIN_SUB_FONT_SIZE) {
				currentSubFontSize = (currentSubFontSize - 2).coerceAtLeast(MIN_SUB_FONT_SIZE)
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
				syncFontSizeToggleState()
				applySubtitleStyles()
			}
		}

		binding.btnFontIncrease.setOnClickListener {
			if (currentSubFontSize < MAX_SUB_FONT_SIZE) {
				currentSubFontSize = (currentSubFontSize + 2).coerceAtMost(MAX_SUB_FONT_SIZE)
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
				syncFontSizeToggleState()
				applySubtitleStyles()
			}
		}

		binding.fontSizeSeekBar.max = MAX_SUB_FONT_SIZE - MIN_SUB_FONT_SIZE
		binding.fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (fromUser && !isSyncingFontUI) {
					currentSubFontSize = (MIN_SUB_FONT_SIZE + progress).coerceIn(MIN_SUB_FONT_SIZE, MAX_SUB_FONT_SIZE)
					syncFontSizeToggleState()
					applySubtitleStyles()
				}
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
			}
		})

		// 4. Background Opacity
		// 4. Subtitle Background Box On/Off Switch & Opacity
		binding.switchSubtitleBgEnabled.isChecked = isSubtitleBgEnabled
		binding.bgOpacityToggleGroup.isEnabled = isSubtitleBgEnabled
		binding.bgOpacityToggleGroup.alpha = if (isSubtitleBgEnabled) 1.0f else 0.45f
		binding.switchSubtitleBgEnabled.setOnCheckedChangeListener { _, isChecked ->
			isSubtitleBgEnabled = isChecked
			cropPrefs.edit().putBoolean("sub_bg_enabled", isChecked).apply()
			binding.bgOpacityToggleGroup.isEnabled = isChecked
			binding.bgOpacityToggleGroup.alpha = if (isChecked) 1.0f else 0.45f
			applySubtitleStyles()
		}

		when (currentSubBgOpacity) {
			0 -> binding.bgOpacityToggleGroup.check(R.id.btnOpacityTransparent)
			40 -> binding.bgOpacityToggleGroup.check(R.id.btnOpacityGlass)
			100 -> binding.bgOpacityToggleGroup.check(R.id.btnOpacitySolid)
			else -> binding.bgOpacityToggleGroup.check(R.id.btnOpacityDark)
		}
		binding.bgOpacityToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				currentSubBgOpacity = when (checkedId) {
					R.id.btnOpacityTransparent -> 0
					R.id.btnOpacityGlass -> 40
					R.id.btnOpacitySolid -> 100
					else -> 80
				}
				cropPrefs.edit().putInt("sub_bg_opacity", currentSubBgOpacity).apply()
				applySubtitleStyles()
			}
		}

		// 5. History Lines Count
		when (maxHistoryLines) {
			1 -> binding.historyLinesToggleGroup.check(R.id.btnHistory1)
			2 -> binding.historyLinesToggleGroup.check(R.id.btnHistory2)
			else -> binding.historyLinesToggleGroup.check(R.id.btnHistory3)
		}
		binding.historyLinesToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				maxHistoryLines = when (checkedId) {
					R.id.btnHistory1 -> 1
					R.id.btnHistory2 -> 2
					else -> 3
				}
				cropPrefs.edit().putInt("sub_history_limit", maxHistoryLines).apply()
				while (subtitleHistory.size > maxHistoryLines) {
					subtitleHistory.removeAt(subtitleHistory.size - 1)
				}
				renderSubtitles()
			}
		}
	}

	private fun updateSubPositionLabel() {
		val zoneName = when {
			currentSubVerticalPercent < 28 -> "ด้านล่าง"
			currentSubVerticalPercent < 68 -> "กลางจอ"
			else -> "ด้านบน"
		}
		binding.tvCurrentSubPosition.text = "$zoneName ($currentSubVerticalPercent%)"
	}

	private fun applySubtitleVerticalPosition() {
		val lp = binding.subtitleOverlayContainer.layoutParams as FrameLayout.LayoutParams
		val parentH = if (binding.mainStreamLayout.height > 0) binding.mainStreamLayout.height else resources.displayMetrics.heightPixels
		val boxH = if (binding.subtitleOverlayContainer.height > 0) binding.subtitleOverlayContainer.height else dpToPx(68)
		val minMargin = dpToPx(12)
		val maxMargin = (parentH - boxH - dpToPx(16)).coerceAtLeast(minMargin)
		lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
		lp.bottomMargin = minMargin + ((maxMargin - minMargin) * (currentSubVerticalPercent / 100f)).toInt()
		lp.topMargin = 0
		binding.subtitleOverlayContainer.layoutParams = lp
	}

	private fun syncFontSizeToggleState() {
		isSyncingFontUI = true
		try {
			binding.fontSizeToggleGroup.clearChecked()
			when (currentSubFontSize) {
				16 -> binding.fontSizeToggleGroup.check(R.id.btnSizeSmall)
				26 -> binding.fontSizeToggleGroup.check(R.id.btnSizeMedium)
				40 -> binding.fontSizeToggleGroup.check(R.id.btnSizeLarge)
				56 -> binding.fontSizeToggleGroup.check(R.id.btnSizeXLarge)
				72 -> binding.fontSizeToggleGroup.check(R.id.btnSizeXXLarge)
			}
			binding.fontSizeSeekBar.progress = (currentSubFontSize - MIN_SUB_FONT_SIZE).coerceIn(0, MAX_SUB_FONT_SIZE - MIN_SUB_FONT_SIZE)
		} finally {
			isSyncingFontUI = false
		}
	}

	private fun applySubtitleStyles() {
		// 1. Floating borderless translucent background drawable (auto-fits subtitle length, toggleable on/off)
		val effectiveOpacity = if (isSubtitleBgEnabled) currentSubBgOpacity else 0
		val alphaInt = (effectiveOpacity * 255 / 100).coerceIn(0, 255)
		val bgDrawable = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = dpToPx(12).toFloat()
			setColor(if (alphaInt > 0) Color.argb(alphaInt, 16, 18, 26) else Color.TRANSPARENT)
		}
		binding.subtitleOverlayContainer.background = bgDrawable
		val padH = if (alphaInt > 0) dpToPx(16) else dpToPx(6)
		val padV = if (alphaInt > 0) dpToPx(8) else dpToPx(2)
		binding.subtitleOverlayContainer.setPadding(padH, padV, padH, padV)

		// 2. Vertical Position (0% = Bottom .. 100% = Top)
		applySubtitleVerticalPosition()
		updateSubPositionLabel()

		// 3. Text Color & Sizes
		binding.translatedSubText.setTextColor(currentSubTextColor)
		binding.translatedSubText.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSubFontSize.toFloat())

		binding.translatedSubText1.setTextColor(currentSubTextColor)
		binding.translatedSubText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.88f).coerceAtLeast(9f))

		binding.translatedSubText2.setTextColor(currentSubTextColor)
		binding.translatedSubText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.78f).coerceAtLeast(8f))

		// Text-only mode text styling (updates BOTH color and font size live!)
		val fullSize = (currentSubFontSize + 4).toFloat()
		binding.pureFullscreenText.setTextColor(currentSubTextColor)
		binding.pureFullscreenText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fullSize)
		binding.pureFullscreenText1.setTextColor(currentSubTextColor)
		binding.pureFullscreenText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fullSize * 0.85f).coerceAtLeast(11f))
		binding.pureFullscreenText2.setTextColor(currentSubTextColor)
		binding.pureFullscreenText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fullSize * 0.72f).coerceAtLeast(10f))

		// 4. Update Font Size Label in Settings Menu (Preview box hidden so window does not expand)
		binding.tvCurrentFontSize.text = "$currentSubFontSize sp"
		binding.tvCurrentFontSize.setTextColor(currentSubTextColor)

		// Refresh existing history rendering with updated styles
		renderSubtitles()
	}

	private fun showMenuPage(page: String) {
		binding.menuMainPage.isVisible = (page == "MAIN")
		binding.menuSubTextPage.isVisible = (page == "TEXT")
		binding.menuSubVoicePage.isVisible = (page == "VOICE")
		binding.menuSubModePage.isVisible = (page == "MODE")
		binding.menuSubApiPage.isVisible = (page == "API")
		binding.btnMenuBack.isVisible = (page != "MAIN")
		binding.tvMenuTitle.text = when (page) {
			"TEXT" -> "ปรับแต่งตัวหนังสือ"
			"VOICE" -> "การออกเสียงพากย์"
			"MODE" -> "โหมดและการแสดงผล"
			"API" -> "ตั้งค่า API แปล & พากย์"
			else -> "ตั้งค่า PS Translator"
		}
		if (page == "API") {
			refreshCacheStatsUI()
		}
	}

	private fun getProviderKeyPrefName(provider: TranslationProvider): String {
		return when (provider) {
			TranslationProvider.GEMINI -> "gemini_api_key"
			TranslationProvider.GROQ -> "api_key_groq"
			TranslationProvider.CEREBRAS -> "api_key_cerebras"
			TranslationProvider.OPENAI -> "api_key_openai"
			TranslationProvider.OPENROUTER -> "api_key_openrouter"
			TranslationProvider.CUSTOM -> "api_key_custom"
			TranslationProvider.GOOGLE_FREE -> ""
		}
	}

	private fun getProviderModelPrefName(provider: TranslationProvider): String {
		return "api_model_${provider.name.lowercase()}"
	}

	private fun getProviderApiKey(provider: TranslationProvider): String {
		val prefKey = getProviderKeyPrefName(provider)
		if (prefKey.isEmpty()) return ""
		return cropPrefs.getString(prefKey, "")?.trim() ?: ""
	}

	private fun getProviderModel(provider: TranslationProvider): String {
		if (provider == TranslationProvider.GOOGLE_FREE) return ""
		val saved = cropPrefs.getString(getProviderModelPrefName(provider), "")?.trim() ?: ""
		return if (saved.isNotEmpty()) saved else provider.defaultModel
	}

	private fun getCustomEndpointUrl(): String {
		val saved = cropPrefs.getString("api_custom_url", "")?.trim() ?: ""
		return if (saved.isNotEmpty()) saved else TranslationProvider.CUSTOM.defaultEndpoint
	}

	private fun setupGeminiApiControls() {
		geminiApiKey = getProviderApiKey(TranslationProvider.GEMINI)
		isGeminiTtsEnabled = cropPrefs.getBoolean("gemini_tts_enabled", true)

		val savedProviderName = cropPrefs.getString("translation_provider", null)
		activeTranslationProvider = if (savedProviderName != null) {
			try {
				TranslationProvider.valueOf(savedProviderName)
			} catch (_: Exception) {
				TranslationProvider.GOOGLE_FREE
			}
		} else {
			// Seamless migration from older Gemini-only setting
			val oldGeminiEnabled = cropPrefs.getBoolean("gemini_translate_enabled", true)
			if (geminiApiKey.isNotEmpty() && oldGeminiEnabled) TranslationProvider.GEMINI else TranslationProvider.GOOGLE_FREE
		}
		editingProvider = activeTranslationProvider

		binding.useGeminiTtsSwitch.isChecked = isGeminiTtsEnabled

		val providerButtons = mapOf(
			TranslationProvider.GOOGLE_FREE to binding.btnProviderGoogle,
			TranslationProvider.GEMINI to binding.btnProviderGemini,
			TranslationProvider.GROQ to binding.btnProviderGroq,
			TranslationProvider.CEREBRAS to binding.btnProviderCerebras,
			TranslationProvider.OPENAI to binding.btnProviderOpenAi,
			TranslationProvider.OPENROUTER to binding.btnProviderOpenRouter,
			TranslationProvider.CUSTOM to binding.btnProviderCustom
		)

		for ((provider, btn) in providerButtons) {
			btn.setOnClickListener {
				selectProviderInUi(provider)
				if (provider == TranslationProvider.GOOGLE_FREE) {
					activeTranslationProvider = TranslationProvider.GOOGLE_FREE
					cropPrefs.edit().putString("translation_provider", activeTranslationProvider.name).apply()
					updateGeminiApiStatusUI()
					Toast.makeText(this, "สลับเป็น Google Translate (ฟรี / เร็ว)", Toast.LENGTH_SHORT).show()
				} else if (getProviderApiKey(provider).isNotEmpty()) {
					activeTranslationProvider = provider
					cropPrefs.edit().putString("translation_provider", activeTranslationProvider.name).apply()
					updateGeminiApiStatusUI()
					Toast.makeText(this, "สลับค่ายแปลเป็น ${provider.displayName} แล้ว", Toast.LENGTH_SHORT).show()
				}
			}
		}

		selectProviderInUi(activeTranslationProvider)
		updateGeminiApiStatusUI()

		binding.btnSaveGeminiApiKey.setOnClickListener {
			val target = editingProvider
			if (target == TranslationProvider.GOOGLE_FREE) {
				activeTranslationProvider = TranslationProvider.GOOGLE_FREE
				cropPrefs.edit().putString("translation_provider", activeTranslationProvider.name).apply()
				updateGeminiApiStatusUI()
				return@setOnClickListener
			}

			val key = binding.etGeminiApiKey.text?.toString()?.trim() ?: ""
			val model = binding.etProviderModel.text?.toString()?.trim().orEmpty().ifEmpty { target.defaultModel }
			val editor = cropPrefs.edit()
				.putString(getProviderKeyPrefName(target), key)
				.putString(getProviderModelPrefName(target), model)

			if (target == TranslationProvider.CUSTOM) {
				val customUrl = binding.etCustomBaseUrl.text?.toString()?.trim().orEmpty().ifEmpty { target.defaultEndpoint }
				editor.putString("api_custom_url", customUrl)
			}

			if (target == TranslationProvider.GEMINI) {
				geminiApiKey = key
			}

			if (key.isNotEmpty()) {
				activeTranslationProvider = target
				editor.putString("translation_provider", activeTranslationProvider.name)
				editor.apply()
				updateGeminiApiStatusUI()
				Toast.makeText(this, "บันทึก ${target.displayName} ($model) เรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
			} else {
				editor.apply()
				if (activeTranslationProvider == target) {
					activeTranslationProvider = TranslationProvider.GOOGLE_FREE
					cropPrefs.edit().putString("translation_provider", activeTranslationProvider.name).apply()
				}
				updateGeminiApiStatusUI()
				Toast.makeText(this, "ยังไม่ได้กรอก API Key (ใช้ Google ฟรีชั่วคราว)", Toast.LENGTH_SHORT).show()
			}
		}

		binding.btnClearGeminiApiKey.setOnClickListener {
			val target = editingProvider
			if (target == TranslationProvider.GOOGLE_FREE) return@setOnClickListener
			cropPrefs.edit().remove(getProviderKeyPrefName(target)).apply()
			binding.etGeminiApiKey.setText("")
			if (target == TranslationProvider.GEMINI) {
				geminiApiKey = ""
			}
			if (activeTranslationProvider == target) {
				activeTranslationProvider = TranslationProvider.GOOGLE_FREE
				cropPrefs.edit().putString("translation_provider", activeTranslationProvider.name).apply()
			}
			selectProviderInUi(target)
			updateGeminiApiStatusUI()
			Toast.makeText(this, "ล้าง API Key ของ ${target.displayName} แล้ว", Toast.LENGTH_SHORT).show()
		}

		binding.useGeminiTtsSwitch.setOnCheckedChangeListener { _, isChecked ->
			isGeminiTtsEnabled = isChecked
			cropPrefs.edit().putBoolean("gemini_tts_enabled", isChecked).apply()
			updateGeminiApiStatusUI()
			if (!isChecked) {
				stopGeminiAudioPlayback()
				stopPopupGeminiAudioPlayback()
			}
		}

		setupTranslationQualityAndCacheControls()
	}

	private fun setupTranslationQualityAndCacheControls() {
		isLocalCacheEnabled = cropPrefs.getBoolean("local_cache_enabled", true)
		binding.switchLocalCache.isChecked = isLocalCacheEnabled
		refreshCacheStatsUI()

		binding.switchLocalCache.setOnCheckedChangeListener { _, isChecked ->
			isLocalCacheEnabled = isChecked
			cropPrefs.edit().putBoolean("local_cache_enabled", isChecked).apply()
		}

		binding.btnImportTranslationCache.setOnClickListener {
			try {
				val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
					addCategory(android.content.Intent.CATEGORY_OPENABLE)
					type = "*/*"
				}
				startActivityForResult(intent, REQUEST_CODE_IMPORT_CACHE_DB)
			} catch (e: Exception) {
				Toast.makeText(this@StreamActivity, "ไม่สามารถเปิดตัวเลือกไฟล์ได้: ${e.message}", Toast.LENGTH_SHORT).show()
			}
		}

		binding.btnClearTranslationCache.setOnClickListener {
			ocrScope.launch {
				withContext(Dispatchers.IO) {
					translationCacheDb.clearAll()
				}
				refreshCacheStatsUI()
				Toast.makeText(this@StreamActivity, "รีเซ็ตแคชคำแปลในเครื่องเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
			}
		}

		// Samsung TV Overlay Cast Server (Port 8791)
		isTvCastEnabled = cropPrefs.getBoolean("tv_cast_overlay_enabled", true)
		binding.switchTvCastOverlay.isChecked = isTvCastEnabled
		if (tvOverlayServer == null) {
			tvOverlayServer = TvOverlayServer(applicationContext) { tvName ->
				uiVisibilityHandler.post {
					if (!isDestroyed && !isFinishing) {
						updateTvCastStatusUI(tvName)
					}
				}
			}.apply {
				this.isTvCastEnabled = this@StreamActivity.isTvCastEnabled
				start()
			}
		}
		updateTvCastStatusUI(tvOverlayServer?.connectedTvName)
		syncTvOverlayState()

		binding.switchTvCastOverlay.setOnCheckedChangeListener { _, isChecked ->
			isTvCastEnabled = isChecked
			cropPrefs.edit().putBoolean("tv_cast_overlay_enabled", isChecked).apply()
			tvOverlayServer?.isTvCastEnabled = isChecked
			updateTvCastStatusUI(tvOverlayServer?.connectedTvName)
			syncTvOverlayState()
		}

		binding.btnEnterTvMode.setOnClickListener {
			setTvBlackScreenMode(true)
		}

		// 1-Click Cloud Subtitle Sync (OTA without downloading APK)
		binding.btnSyncCloudSubtitles.setOnClickListener {
			binding.btnSyncCloudSubtitles.isEnabled = false
			binding.btnSyncCloudSubtitles.text = "กำลังซิงค์..."
			ocrScope.launch {
				val count = AppUpdater.syncCloudSubtitleDatabase(this@StreamActivity, translationCacheDb)
				binding.btnSyncCloudSubtitles.isEnabled = true
				binding.btnSyncCloudSubtitles.text = "ซิงค์ซับล่าสุด"
				refreshCacheStatsUI()
				val msg = if (count > 0) {
					"ซิงค์คลังซับล่าสุดจากคลาวด์สำเร็จ ($count ประโยค)!"
				} else {
					"ซิงค์คลังซับไม่สำเร็จ กรุณาตรวจสอบอินเทอร์เน็ต"
				}
				Toast.makeText(this@StreamActivity, msg, Toast.LENGTH_LONG).show()
			}
		}

		// 1-Click In-App APK Auto-Updater (downloads & launches installer inside app)
		binding.btnInAppUpdateApk.setOnClickListener {
			binding.btnInAppUpdateApk.isEnabled = false
			binding.btnInAppUpdateApk.text = "เช็กอัปเดต..."
			ocrScope.launch {
				val info = AppUpdater.fetchLatestReleaseInfo()
				val url = info?.apkUrl ?: "https://github.com/teeradream/ps-translator/releases/latest/download/PS-Translator.apk"
				val ok = AppUpdater.downloadAndInstallApk(this@StreamActivity, url) { pct ->
					if (!isDestroyed && !isFinishing) {
						binding.btnInAppUpdateApk.text = "โหลด $pct%..."
					}
				}
				if (!isDestroyed && !isFinishing) {
					binding.btnInAppUpdateApk.isEnabled = true
					binding.btnInAppUpdateApk.text = "อัปเดตแอปในตัว"
					if (!ok) {
						Toast.makeText(this@StreamActivity, "ดาวน์โหลดอัปเดตไม่สำเร็จ กรุณาลองใหม่", Toast.LENGTH_LONG).show()
					}
				}
			}
		}
	}

	private fun updateTvCastStatusUI(connectedTv: String?) {
		if (!::binding.isInitialized) return
		val ip = TvOverlayServer.getLocalWifiIp(this)
		if (!isTvCastEnabled) {
			binding.tvSamsungCastStatus.text = "ปิดการส่งซับขึ้นทีวีอยู่ (IP มือถือ: $ip)"
			binding.tvSamsungCastStatus.setTextColor(Color.parseColor("#C7A6E8"))
		} else if (!connectedTv.isNullOrBlank()) {
			binding.tvSamsungCastStatus.text = "✓ เชื่อมต่อกับทีวีแล้ว: $connectedTv (IP: $ip:${TvOverlayServer.PORT})"
			binding.tvSamsungCastStatus.setTextColor(Color.parseColor("#3EF0D8"))
		} else {
			binding.tvSamsungCastStatus.text = "พร้อมเชื่อมต่อทีวีใน Wi-Fi บ้าน (IP: $ip) — เปิดแอป PS Translator TV บนทีวีได้เลย"
			binding.tvSamsungCastStatus.setTextColor(Color.parseColor("#E0C8F5"))
		}
	}

	private fun syncTvOverlayState() {
		val server = tvOverlayServer ?: return
		val showLeft = isPopupZonesMode && isPopupLeftEnabled && isPopupLeftVisible && lastPopupLeftThai.isNotEmpty()
		val showRight = isPopupZonesMode && isPopupRightEnabled && isPopupRightVisible && lastPopupRightThai.isNotEmpty()
		val showNote = ::binding.isInitialized && binding.noteTranslationCard.isVisible && lastNoteThai.isNotEmpty()
		server.updateOverlayState(
			subtitles = subtitleHistory.map { it.english to it.thai },
			maxHistoryLines = maxHistoryLines,
			fontSizeSp = currentSubFontSize,
			textColorInt = currentSubTextColor,
			bgOpacityPercent = currentSubBgOpacity,
			subBgEnabled = isSubtitleBgEnabled,
			position = currentSubPosition,
			verticalPercent = currentSubVerticalPercent,
			isSubtitleEnabled = isSubtitleEnabled,
			popupLeftText = if (showLeft) lastPopupLeftThai else null,
			popupRightText = if (showRight) lastPopupRightThai else null,
			popupLeftVerticalPercent = popupLeftVerticalPercent,
			popupRightVerticalPercent = popupRightVerticalPercent,
			noteText = if (showNote) lastNoteThai else null
		)
	}

	@Deprecated("Deprecated in Java")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == REQUEST_CODE_IMPORT_CACHE_DB && resultCode == RESULT_OK) {
			val uri = data?.data ?: return
			ocrScope.launch {
				val imported = withContext(Dispatchers.IO) {
					translationCacheDb.importFromUri(this@StreamActivity, uri)
				}
				refreshCacheStatsUI()
				val msg = if (imported > 0) {
					"นำเข้าฐานข้อมูลคำแปลสำเร็จ $imported ประโยค!"
				} else {
					"ไม่พบข้อมูลคำแปลที่รองรับในไฟล์นี้ (.db / .tsv)"
				}
				Toast.makeText(this@StreamActivity, msg, Toast.LENGTH_LONG).show()
			}
		}
	}

	private fun refreshCacheStatsUI() {
		if (!::binding.isInitialized || !::translationCacheDb.isInitialized) return
		ocrScope.launch {
			val count = withContext(Dispatchers.IO) { translationCacheDb.count() }
			if (!isDestroyed && !isFinishing) {
				binding.tvCacheStats.text = "แคชในเครื่อง: $count ประโยค"
			}
		}
	}

	private fun selectProviderInUi(provider: TranslationProvider) {
		editingProvider = provider
		val buttons = mapOf(
			TranslationProvider.GOOGLE_FREE to binding.btnProviderGoogle,
			TranslationProvider.GEMINI to binding.btnProviderGemini,
			TranslationProvider.GROQ to binding.btnProviderGroq,
			TranslationProvider.CEREBRAS to binding.btnProviderCerebras,
			TranslationProvider.OPENAI to binding.btnProviderOpenAi,
			TranslationProvider.OPENROUTER to binding.btnProviderOpenRouter,
			TranslationProvider.CUSTOM to binding.btnProviderCustom
		)
		for ((p, btn) in buttons) {
			val selected = (p == provider)
			val color = if (selected) Color.parseColor("#FF5AA5") else Color.parseColor("#E0C8F5")
			val stroke = if (selected) Color.parseColor("#FF5AA5") else Color.parseColor("#66FF9A4A")
			val bg = if (selected) Color.parseColor("#33FF5AA5") else Color.TRANSPARENT
			btn.setTextColor(color)
			btn.strokeColor = android.content.res.ColorStateList.valueOf(stroke)
			btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
		}

		if (provider == TranslationProvider.GOOGLE_FREE) {
			binding.providerConfigContainer.isVisible = false
		} else {
			binding.providerConfigContainer.isVisible = true
			binding.tvProviderKeyLabel.text = provider.keyLabel
			binding.etGeminiApiKey.hint = provider.keyHint
			binding.etGeminiApiKey.setText(getProviderApiKey(provider))
			binding.etProviderModel.hint = provider.defaultModel
			binding.etProviderModel.setText(getProviderModel(provider))
			binding.layoutCustomEndpoint.isVisible = (provider == TranslationProvider.CUSTOM)
			if (provider == TranslationProvider.CUSTOM) {
				binding.etCustomBaseUrl.setText(getCustomEndpointUrl())
			}
		}
	}

	private fun updateGeminiApiStatusUI() {
		if (!::binding.isInitialized) return
		val keyReady = activeTranslationProvider == TranslationProvider.GOOGLE_FREE ||
			getProviderApiKey(activeTranslationProvider).isNotEmpty()

		val ttsLabel = when {
			!isTtsEnabled && !(isPopupZonesMode && isPopupTtsEnabled) -> "ปิด (แปลอย่างเดียว)"
			!isTtsEnabled && isPopupZonesMode && isPopupTtsEnabled -> "เฉพาะป๊อปอัพ"
			isGeminiTtsEnabled && geminiApiKey.isNotEmpty() -> "Gemini AI TTS"
			else -> "TTS ในเครื่อง"
		}

		if (activeTranslationProvider != TranslationProvider.GOOGLE_FREE && keyReady) {
			val model = getProviderModel(activeTranslationProvider)
			binding.tvGeminiApiStatus.text = "กำลังใช้แปล: ${activeTranslationProvider.displayName} ($model) • พากย์: $ttsLabel"
			binding.tvGeminiApiStatus.setTextColor(Color.parseColor("#3EF0D8"))
		} else {
			binding.tvGeminiApiStatus.text = "กำลังใช้แปล: Google Translate (ฟรี) • พากย์: $ttsLabel"
			binding.tvGeminiApiStatus.setTextColor(Color.parseColor("#FF9A4A"))
		}

		binding.useGeminiTtsSwitch.isVisible = isTtsEnabled
		binding.tvGeminiTtsKeyHint.text = when {
			!isTtsEnabled -> "เสียงพากย์ปิดอยู่ — แอปจะใช้ API ที่เลือกสำหรับแปลตัวหนังสืออย่างเดียว"
			geminiApiKey.isNotEmpty() -> "✓ พบ Gemini Key ที่บันทึกไว้ (เปิดสวิตช์ด้านบนเพื่อใช้เสียงพากย์ Gemini AI หรือปิดเพื่อใช้ TTS ในเครื่อง)"
			else -> "หากอยากใช้เสียงพากย์ Gemini AI ให้เลือกแท็บ Gemini AI แล้วบันทึก Gemini Key ไว้ก่อน"
		}
	}

	private fun setMasterTtsEnabled(enabled: Boolean) {
		isTtsEnabled = enabled
		cropPrefs.edit().putBoolean("tts_voice_enabled", enabled).apply()
		if (binding.ttsVoiceSwitch.isChecked != enabled) {
			binding.ttsVoiceSwitch.isChecked = enabled
		}
		if (binding.switchApiMasterTts.isChecked != enabled) {
			binding.switchApiMasterTts.isChecked = enabled
		}
		binding.voiceGenderContainer.isVisible = enabled
		binding.speechSpeedContainer.isVisible = enabled
		binding.ttsQueueModeContainer.isVisible = enabled
		binding.useGeminiTtsSwitch.isVisible = enabled
		if (!enabled) {
			activeUtterances.clear()
			tts?.stop()
			stopGeminiAudioPlayback()
			// Also silence popup dubbing when master dubbing is turned OFF so "แปลอย่างเดียว" is truly silent
			setPopupTtsEnabled(false)
		}
		updateQuickDrawerUI()
		updateGeminiApiStatusUI()
	}

	private fun renderSubtitles() {
		if (subtitleHistory.isEmpty()) {
			binding.subtitleOverlayContainer.isVisible = false
			binding.pureFullscreenText.text = ""
			binding.pureOriginalText.text = ""
			binding.pureFullscreenText1.isVisible = false
			binding.pureFullscreenText2.isVisible = false
			syncTvOverlayState()
			return
		}

		// Slot 0 (Newest)
		val item0 = subtitleHistory.getOrNull(0)
		if (item0 != null) {
			binding.translatedSubText.text = item0.thai
			binding.originalSubText.text = item0.english
			binding.layoutSubItem0.alpha = 1.0f

			binding.pureFullscreenText.text = item0.thai
			binding.pureOriginalText.text = item0.english
		}

		// Slot 1 (Previous 1, faded 0.65)
		val item1 = subtitleHistory.getOrNull(1)
		if (item1 != null && maxHistoryLines >= 2) {
			binding.layoutSubItem1.isVisible = true
			binding.translatedSubText1.text = item1.thai
			binding.originalSubText1.text = item1.english
			binding.layoutSubItem1.alpha = 0.65f

			binding.pureFullscreenText1.text = item1.thai
			binding.pureFullscreenText1.isVisible = true
		} else {
			binding.layoutSubItem1.isVisible = false
			binding.pureFullscreenText1.isVisible = false
		}

		// Slot 2 (Previous 2, faded 0.35)
		val item2 = subtitleHistory.getOrNull(2)
		if (item2 != null && maxHistoryLines >= 3) {
			binding.layoutSubItem2.isVisible = true
			binding.translatedSubText2.text = item2.thai
			binding.originalSubText2.text = item2.english
			binding.layoutSubItem2.alpha = 0.35f

			binding.pureFullscreenText2.text = item2.thai
			binding.pureFullscreenText2.isVisible = true
		} else {
			binding.layoutSubItem2.isVisible = false
			binding.pureFullscreenText2.isVisible = false
		}

		// Keep subtitle window visible until 10s idle timer fires
		if (isSubtitleEnabled && !isTextOnlyMode) {
			binding.subtitleOverlayContainer.isVisible = true
			binding.subtitleOverlayContainer.alpha = 1.0f
			applySubtitleVerticalPosition()
		}
		syncTvOverlayState()
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun setupCropEditor() {
		binding.btnAdjustCropBox.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			activeCropEditTarget = CropEditTarget.SUBTITLE
			openCropEditor()
		}

		binding.btnAdjustNoteCropBox.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			activeCropEditTarget = CropEditTarget.NOTE
			openCropEditor()
		}

		binding.btnAdjustPopupLeftBox.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			activeCropEditTarget = CropEditTarget.POPUP_LEFT
			openCropEditor()
		}

		binding.btnAdjustPopupRightBox.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			activeCropEditTarget = CropEditTarget.POPUP_RIGHT
			openCropEditor()
		}

		binding.btnResetCropBox.setOnClickListener {
			resetCropBoxToDefault()
		}

		binding.btnSaveCropBox.setOnClickListener {
			saveCropBoxSettings()
		}

		binding.filterShortTextSwitch.setOnCheckedChangeListener { _, isChecked ->
			isFilterShortTextEnabled = isChecked
			cropPrefs.edit().putBoolean("filter_short_ui", isChecked).apply()
		}

		// Dragging the crop frame
		var dX = 0f
		var dY = 0f
		binding.cropBoxFrame.setOnTouchListener { view, event ->
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					dX = view.x - event.rawX
					dY = view.y - event.rawY
					true
				}
				MotionEvent.ACTION_MOVE -> {
					val overlayW = binding.cropEditorOverlay.width
					val overlayH = binding.cropEditorOverlay.height
					val topBarH = binding.cropTopBar.height
					val newX = (event.rawX + dX).coerceIn(0f, (overlayW - view.width).toFloat().coerceAtLeast(0f))
					val newY = (event.rawY + dY).coerceIn(topBarH.toFloat(), (overlayH - view.height).toFloat().coerceAtLeast(topBarH.toFloat()))
					view.x = newX
					view.y = newY
					true
				}
				else -> true
			}
		}

		// Resizing the crop frame via corner handle
		var initialTouchX = 0f
		var initialTouchY = 0f
		var initialWidth = 0
		var initialHeight = 0
		binding.cropResizeHandle.setOnTouchListener { _, event ->
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					initialTouchX = event.rawX
					initialTouchY = event.rawY
					initialWidth = binding.cropBoxFrame.width
					initialHeight = binding.cropBoxFrame.height
					true
				}
				MotionEvent.ACTION_MOVE -> {
					val overlayW = binding.cropEditorOverlay.width
					val overlayH = binding.cropEditorOverlay.height
					val frameX = binding.cropBoxFrame.x.toInt()
					val frameY = binding.cropBoxFrame.y.toInt()

					val newW = (initialWidth + (event.rawX - initialTouchX).toInt())
						.coerceIn(dpToPx(80), (overlayW - frameX).coerceAtLeast(dpToPx(80)))
					val newH = (initialHeight + (event.rawY - initialTouchY).toInt())
						.coerceIn(dpToPx(36), (overlayH - frameY).coerceAtLeast(dpToPx(36)))

					val params = binding.cropBoxFrame.layoutParams
					params.width = newW
					params.height = newH
					binding.cropBoxFrame.layoutParams = params
					true
				}
				else -> true
			}
		}
	}

	private fun getCropDefaults(target: CropEditTarget): FloatArray {
		return when (target) {
			CropEditTarget.SUBTITLE -> floatArrayOf(0.18f, 0.72f, 0.64f, 0.22f)
			CropEditTarget.NOTE -> floatArrayOf(0.20f, 0.15f, 0.60f, 0.65f)
			CropEditTarget.POPUP_LEFT -> floatArrayOf(0.02f, 0.04f, 0.34f, 0.44f)
			CropEditTarget.POPUP_RIGHT -> floatArrayOf(0.60f, 0.50f, 0.38f, 0.44f)
		}
	}

	private fun getCropPrefix(target: CropEditTarget): String {
		return when (target) {
			CropEditTarget.SUBTITLE -> "crop_"
			CropEditTarget.NOTE -> "note_crop_"
			CropEditTarget.POPUP_LEFT -> "popup_left_"
			CropEditTarget.POPUP_RIGHT -> "popup_right_"
		}
	}

	private fun openCropEditor() {
		when (activeCropEditTarget) {
			CropEditTarget.NOTE -> {
				binding.cropEditorTitle.text = "ลากกรอบสีฟ้าไปวางตรงพื้นที่เอกสาร/คำแนะนำ"
				binding.cropBoxGuideText.text = "กรอบแปลเฉพาะจุด (ลากเพื่อย้าย)"
				binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E5FF"))
				binding.cropBoxFrame.setBackgroundResource(R.drawable.bg_note_crop_box)
			}
			CropEditTarget.POPUP_LEFT -> {
				binding.cropEditorTitle.text = "ลากกรอบสีทองไปวางตรงพื้นที่ป๊อปอัพด้านซ้าย (คำแนะนำ)"
				binding.cropBoxGuideText.text = "โซนป๊อปอัพซ้าย (คำแนะนำ/Tutorial)"
				binding.cropBoxGuideText.setTextColor(Color.parseColor("#FFB74D"))
				binding.cropBoxFrame.setBackgroundResource(R.drawable.bg_popup_crop_left)
			}
			CropEditTarget.POPUP_RIGHT -> {
				binding.cropEditorTitle.text = "ลากกรอบสีฟ้าไปวางตรงมือถือ/แจ้งเตือนด้านขวา (เว้นปุ่มกดล่างขวา)"
				binding.cropBoxGuideText.text = "โซนป๊อปอัพขวา (มือถือ/แจ้งเตือน)"
				binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E5FF"))
				binding.cropBoxFrame.setBackgroundResource(R.drawable.bg_popup_crop_right)
			}
			CropEditTarget.SUBTITLE -> {
				binding.cropEditorTitle.text = "ลากกรอบสีเขียวไปวางตรงพื้นที่ซับไตเติ้ลเกม"
				binding.cropBoxGuideText.text = "กรอบอ่านซับ (ลากเพื่อย้าย)"
				binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E676"))
				binding.cropBoxFrame.setBackgroundResource(R.drawable.bg_crop_box)
			}
		}
		binding.cropEditorOverlay.isVisible = true
		binding.cropEditorOverlay.post {
			val overlayW = binding.cropEditorOverlay.width
			val overlayH = binding.cropEditorOverlay.height
			val prefix = getCropPrefix(activeCropEditTarget)
			val defs = getCropDefaults(activeCropEditTarget)
			val leftNorm = cropPrefs.getFloat("${prefix}left", defs[0])
			val topNorm = cropPrefs.getFloat("${prefix}top", defs[1])
			val widthNorm = cropPrefs.getFloat("${prefix}width", defs[2])
			val heightNorm = cropPrefs.getFloat("${prefix}height", defs[3])

			val w = (overlayW * widthNorm).toInt().coerceAtLeast(dpToPx(80))
			val h = (overlayH * heightNorm).toInt().coerceAtLeast(dpToPx(36))
			val x = (overlayW * leftNorm).coerceIn(0f, (overlayW - w).toFloat().coerceAtLeast(0f))
			val y = (overlayH * topNorm).coerceIn(binding.cropTopBar.height.toFloat(), (overlayH - h).toFloat().coerceAtLeast(binding.cropTopBar.height.toFloat()))

			val params = binding.cropBoxFrame.layoutParams
			params.width = w
			params.height = h
			binding.cropBoxFrame.layoutParams = params
			binding.cropBoxFrame.x = x
			binding.cropBoxFrame.y = y
		}
	}

	private fun resetCropBoxToDefault() {
		val overlayW = binding.cropEditorOverlay.width
		val overlayH = binding.cropEditorOverlay.height

		val defs = getCropDefaults(activeCropEditTarget)
		val w = (overlayW * defs[2]).toInt().coerceAtLeast(dpToPx(80))
		val h = (overlayH * defs[3]).toInt().coerceAtLeast(dpToPx(36))
		val x = overlayW * defs[0]
		val y = (overlayH * defs[1]).coerceAtLeast(binding.cropTopBar.height.toFloat())

		val params = binding.cropBoxFrame.layoutParams
		params.width = w
		params.height = h
		binding.cropBoxFrame.layoutParams = params
		binding.cropBoxFrame.x = x
		binding.cropBoxFrame.y = y
	}

	private fun saveCropBoxSettings() {
		val overlayW = binding.cropEditorOverlay.width.toFloat().coerceAtLeast(1f)
		val overlayH = binding.cropEditorOverlay.height.toFloat().coerceAtLeast(1f)

		val leftNorm = (binding.cropBoxFrame.x / overlayW).coerceIn(0f, 0.90f)
		val topNorm = (binding.cropBoxFrame.y / overlayH).coerceIn(0f, 0.90f)
		val widthNorm = (binding.cropBoxFrame.width / overlayW).coerceIn(0.08f, 1f - leftNorm)
		val heightNorm = (binding.cropBoxFrame.height / overlayH).coerceIn(0.06f, 1f - topNorm)

		val prefix = getCropPrefix(activeCropEditTarget)
		cropPrefs.edit()
			.putFloat("${prefix}left", leftNorm)
			.putFloat("${prefix}top", topNorm)
			.putFloat("${prefix}width", widthNorm)
			.putFloat("${prefix}height", heightNorm)
			.apply()

		binding.cropEditorOverlay.isVisible = false
		resetMenuButtonTimer()
		val msg = when (activeCropEditTarget) {
			CropEditTarget.NOTE -> "บันทึกกรอบแปลเฉพาะจุด (เอกสาร/คำแนะนำ) แล้ว"
			CropEditTarget.POPUP_LEFT -> "บันทึกกรอบป๊อปอัพซ้ายเรียบร้อยแล้ว"
			CropEditTarget.POPUP_RIGHT -> "บันทึกกรอบป๊อปอัพขวาเรียบร้อยแล้ว"
			CropEditTarget.SUBTITLE -> "บันทึกกรอบพื้นที่อ่านซับเรียบร้อยแล้ว"
		}
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun setupTopQuickDrawer() {
		var startY = 0f
		binding.topDrawerHandle.setOnTouchListener { _, event ->
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					startY = event.rawY
					true
				}
				MotionEvent.ACTION_UP -> {
					val dy = event.rawY - startY
					if (dy > dpToPx(12) || kotlin.math.abs(dy) < dpToPx(12)) {
						openTopQuickDrawer()
					}
					true
				}
				else -> false
			}
		}

		binding.btnQuickTts.setOnClickListener {
			binding.ttsVoiceSwitch.isChecked = !binding.ttsVoiceSwitch.isChecked
			updateQuickDrawerUI()
			resetTopDrawerTimer()
		}

		binding.btnQuickMuteGame.setOnClickListener {
			binding.btnMuteAudio.performClick()
			updateQuickDrawerUI()
			resetTopDrawerTimer()
		}

		binding.btnQuickSub.setOnClickListener {
			binding.subOverlaySwitch.isChecked = !binding.subOverlaySwitch.isChecked
			updateQuickDrawerUI()
			resetTopDrawerTimer()
		}

		binding.btnQuickNoteBox.setOnClickListener {
			if (isPopupZonesMode) {
				setPopupZonesMode(false)
			}
			setFloatingNoteCropBoxVisible(!isNoteCropBoxVisible)
			updateQuickDrawerUI()
			resetTopDrawerTimer()
		}

		binding.btnQuickPopupMode.setOnClickListener {
			setPopupZonesMode(!isPopupZonesMode)
			updateQuickDrawerUI()
			resetTopDrawerTimer()
		}

		binding.btnQuickTvMode.setOnClickListener {
			setTvBlackScreenMode(true)
		}
	}

	private fun openTopQuickDrawer() {
		updateQuickDrawerUI()
		binding.topDrawerHandle.isVisible = false
		binding.topQuickDrawerPanel.isVisible = true
		binding.topQuickDrawerPanel.translationY = -dpToPx(64).toFloat()
		binding.topQuickDrawerPanel.scaleX = 0.92f
		binding.topQuickDrawerPanel.scaleY = 0.92f
		binding.topQuickDrawerPanel.alpha = 0f
		binding.topQuickDrawerPanel.animate().alpha(1f).setDuration(140).start()
		SpringAnimation(binding.topQuickDrawerPanel, DynamicAnimation.TRANSLATION_Y, 0f).apply {
			spring = SpringForce(0f).apply {
				stiffness = 440f
				dampingRatio = 0.66f
			}
			start()
		}
		SpringAnimation(binding.topQuickDrawerPanel, DynamicAnimation.SCALE_X, 1f).apply {
			spring = SpringForce(1f).apply { stiffness = 480f; dampingRatio = 0.68f }
			start()
		}
		SpringAnimation(binding.topQuickDrawerPanel, DynamicAnimation.SCALE_Y, 1f).apply {
			spring = SpringForce(1f).apply { stiffness = 480f; dampingRatio = 0.68f }
			start()
		}
		resetTopDrawerTimer()
	}

	private fun closeTopQuickDrawer() {
		uiVisibilityHandler.removeCallbacks(hideTopDrawerRunnable)
		if (!binding.topQuickDrawerPanel.isVisible) return
		binding.topQuickDrawerPanel.animate()
			.translationY(-dpToPx(60).toFloat())
			.alpha(0f)
			.setDuration(180)
			.withEndAction {
				binding.topQuickDrawerPanel.isVisible = false
				binding.topDrawerHandle.isVisible = true
			}
			.start()
	}

	private fun resetTopDrawerTimer() {
		uiVisibilityHandler.removeCallbacks(hideTopDrawerRunnable)
		uiVisibilityHandler.postDelayed(hideTopDrawerRunnable, 4000)
	}

	private fun updateQuickDrawerUI() {
		if (!::binding.isInitialized) return
		binding.btnQuickTts.text = if (isTtsEnabled) "พากย์: เปิด" else "พากย์: ปิด"
		binding.btnQuickTts.setTextColor(Color.parseColor(if (isTtsEnabled) "#00FF88" else "#FFFFFF"))
		binding.btnQuickTts.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isTtsEnabled) "#00FF88" else "#66FFFFFF"))

		binding.btnQuickMuteGame.text = if (isAudioMuted) "เสียงเกม: ปิด" else "เสียงเกม: เปิด"
		binding.btnQuickMuteGame.setTextColor(Color.parseColor(if (isAudioMuted) "#FF6B6B" else "#FFFFFF"))
		binding.btnQuickMuteGame.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isAudioMuted) "#FF6B6B" else "#66FFFFFF"))

		binding.btnQuickSub.text = if (isSubtitleEnabled) "ซับ: เปิด" else "ซับ: ปิด"
		binding.btnQuickSub.setTextColor(Color.parseColor(if (isSubtitleEnabled) "#00FF88" else "#FFFFFF"))
		binding.btnQuickSub.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isSubtitleEnabled) "#00FF88" else "#66FFFFFF"))

		binding.btnQuickNoteBox.text = if (isNoteCropBoxVisible) "กรอบ: แสดง" else "กรอบ: ซ่อน"
		binding.btnQuickNoteBox.setTextColor(Color.parseColor(if (isNoteCropBoxVisible) "#00E5FF" else "#FFFFFF"))
		binding.btnQuickNoteBox.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isNoteCropBoxVisible) "#00E5FF" else "#66FFFFFF"))

		binding.btnQuickPopupMode.text = if (isPopupZonesMode) "โหมด: ป๊อปอัพ" else "โหมด: เฉพาะจุด"
		binding.btnQuickPopupMode.setTextColor(Color.parseColor(if (isPopupZonesMode) "#FFB74D" else "#00E5FF"))
		binding.btnQuickPopupMode.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(if (isPopupZonesMode) "#FFB74D" else "#00E5FF"))
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun setupNoteTranslationFeature() {
		isNoteCropBoxVisible = cropPrefs.getBoolean("note_crop_visible", false)
		isPopupZonesMode = cropPrefs.getBoolean("popup_zones_mode", false)
		binding.extraTranslateModeToggleGroup.check(if (isPopupZonesMode) R.id.btnModePopupZones else R.id.btnModeSpot)
		binding.extraTranslateModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				setPopupZonesMode(checkedId == R.id.btnModePopupZones)
			}
		}
		applyExtraTranslateModeUI()

		binding.btnTvModeFloat.setOnClickListener {
			setTvBlackScreenMode(true)
		}
		binding.tvBlackLockOverlay.setOnClickListener {
			val now = System.currentTimeMillis()
			if (now - lastTvOverlayTapTimeMs in 40..450) {
				lastTvOverlayTapTimeMs = 0L
				setTvBlackScreenMode(false)
			} else {
				lastTvOverlayTapTimeMs = now
			}
		}
		binding.btnScanNoteFloat.setOnClickListener {
			captureAndTranslateNote()
		}
		binding.btnTextOnlyFloat.setOnClickListener {
			isTextOnlyMode = !isTextOnlyMode
			updateTextOnlyModeUI()
		}
		binding.btnScanNoteFloat.setOnLongClickListener {
			setFloatingNoteCropBoxVisible(!isNoteCropBoxVisible)
			true
		}
		binding.btnFloatNoteScan.setOnClickListener {
			captureAndTranslateNote()
		}
		binding.btnFloatNoteHide.setOnClickListener {
			setFloatingNoteCropBoxVisible(false)
		}
		binding.btnCloseNote.setOnClickListener {
			hideNoteTranslationCard()
		}

		// Live drag for persistent floating blue crop box
		var dX = 0f
		var dY = 0f
		binding.floatingNoteCropBox.setOnTouchListener { view, event ->
			val parentW = binding.mainStreamLayout.width.toFloat()
			val parentH = binding.mainStreamLayout.height.toFloat()
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					dX = view.x - event.rawX
					dY = view.y - event.rawY
					true
				}
				MotionEvent.ACTION_MOVE -> {
					view.x = (event.rawX + dX).coerceIn(0f, (parentW - view.width).coerceAtLeast(0f))
					view.y = (event.rawY + dY).coerceIn(0f, (parentH - view.height).coerceAtLeast(0f))
					true
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					saveFloatingNoteCropBoxCoords()
					true
				}
				else -> false
			}
		}

		// Live resize handle for persistent floating blue crop box
		var initTouchX = 0f
		var initTouchY = 0f
		var initW = 0
		var initH = 0
		binding.noteResizeHandle.setOnTouchListener { _, event ->
			val parentW = binding.mainStreamLayout.width
			val parentH = binding.mainStreamLayout.height
			val boxX = binding.floatingNoteCropBox.x.toInt()
			val boxY = binding.floatingNoteCropBox.y.toInt()
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					initTouchX = event.rawX
					initTouchY = event.rawY
					initW = binding.floatingNoteCropBox.width
					initH = binding.floatingNoteCropBox.height
					true
				}
				MotionEvent.ACTION_MOVE -> {
					val newW = (initW + (event.rawX - initTouchX).toInt()).coerceIn(dpToPx(140), (parentW - boxX).coerceAtLeast(dpToPx(140)))
					val newH = (initH + (event.rawY - initTouchY).toInt()).coerceIn(dpToPx(70), (parentH - boxY).coerceAtLeast(dpToPx(70)))
					val lp = binding.floatingNoteCropBox.layoutParams
					lp.width = newW
					lp.height = newH
					binding.floatingNoteCropBox.layoutParams = lp
					true
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					saveFloatingNoteCropBoxCoords()
					true
				}
				else -> false
			}
		}
		binding.btnSpeakNote.setOnClickListener {
			if (lastNoteThai.isNotEmpty()) {
				val isFemale = when (voiceGenderMode) {
					VoiceGenderMode.AUTO_AI -> isFemaleCharacter(lastNoteEnglish, lastNoteThai)
					VoiceGenderMode.FEMALE -> true
					VoiceGenderMode.MALE -> false
				}
				val now = System.currentTimeMillis()
				if (geminiApiKey.isNotEmpty() && isGeminiTtsEnabled) {
					ocrScope.launch {
						val wavFile = withContext(Dispatchers.IO) {
							synthesizeGeminiTts(lastNoteThai, isFemale, now)
						}
						if (wavFile != null && !isDestroyed && !isFinishing) {
							enqueueGeminiAudio(wavFile)
						} else if (!isDestroyed && !isFinishing) {
							speakWithLocalTts(lastNoteThai, isFemale, now)
						}
					}
				} else {
					speakWithLocalTts(lastNoteThai, isFemale, now)
				}
			}
			// Extend visibility while listening
			uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
			uiVisibilityHandler.postDelayed(hideNoteCardRunnable, 20000)
		}
	}

	private fun captureAndTranslateNote() {
		val surfaceView = binding.surfaceView
		if (surfaceView.holder?.surface?.isValid != true || surfaceView.width <= 100 || surfaceView.height <= 100) {
			Toast.makeText(this, "ยังไม่พบภาพสตรีมสำหรับสแกน", Toast.LENGTH_SHORT).show()
			return
		}
		if (isScanningNote) return

		isScanningNote = true
		binding.btnScanNoteFloat.alpha = 0.45f
		val wasTextOnlyVisible = isTextOnlyMode && binding.pureFullscreenLayout.isVisible
		val wasNoteCardVisible = binding.noteTranslationCard.isVisible
		val wasFloatingBoxVisible = binding.floatingNoteCropBox.isVisible
		if (wasTextOnlyVisible) {
			binding.pureFullscreenLayout.visibility = View.INVISIBLE
		}
		if (wasNoteCardVisible) {
			binding.noteTranslationCard.visibility = View.INVISIBLE
		}
		if (wasFloatingBoxVisible) {
			binding.floatingNoteCropBox.visibility = View.INVISIBLE
		}

		val runPixelCopy = {
			val fullBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
			try {
				PixelCopy.request(surfaceView, fullBitmap, { copyResult ->
					if (wasTextOnlyVisible && isTextOnlyMode) {
						binding.pureFullscreenLayout.visibility = View.VISIBLE
					}
					if (wasNoteCardVisible && lastNoteThai.isNotEmpty()) {
						binding.noteTranslationCard.visibility = View.VISIBLE
					}
					if (wasFloatingBoxVisible && isNoteCropBoxVisible && !isTextOnlyMode) {
						binding.floatingNoteCropBox.visibility = View.VISIBLE
					}
					if (copyResult == PixelCopy.SUCCESS) {
					try {
						val cropLeftNorm = cropPrefs.getFloat("note_crop_left", 0.20f).coerceIn(0f, 0.85f)
						val cropTopNorm = cropPrefs.getFloat("note_crop_top", 0.15f).coerceIn(0f, 0.90f)
						val cropWidthNorm = cropPrefs.getFloat("note_crop_width", 0.60f).coerceIn(0.10f, 1f - cropLeftNorm)
						val cropHeightNorm = cropPrefs.getFloat("note_crop_height", 0.65f).coerceIn(0.06f, 1f - cropTopNorm)

						val cropX = (1920 * cropLeftNorm).toInt().coerceIn(0, 1919)
						val cropY = (1080 * cropTopNorm).toInt().coerceIn(0, 1079)
						val cropW = (1920 * cropWidthNorm).toInt().coerceIn(40, 1920 - cropX)
						val cropH = (1080 * cropHeightNorm).toInt().coerceIn(30, 1080 - cropY)

						val subBitmap = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
						val scale = if (cropW * 2 <= 2400 && cropH * 2 <= 1600) 2 else 1
						val scaledSub = if (scale == 2) Bitmap.createScaledBitmap(subBitmap, cropW * 2, cropH * 2, true) else subBitmap
						val bmpW = scaledSub.width
						val bmpH = scaledSub.height
						val image = InputImage.fromBitmap(scaledSub, 0)
						textRecognizer.process(image)
							.addOnSuccessListener { visionText ->
								val noteBlocks = visionText.textBlocks
									.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
									.mapNotNull { block ->
										val t = block.text.trim().replace(Regex("\\s+"), " ")
										val box = block.boundingBox
										if (t.length >= 2 && box != null) NoteBlockItem(t, Rect(box)) else null
									}
								if (noteBlocks.isEmpty()) {
									isScanningNote = false
									binding.btnScanNoteFloat.alpha = 1.0f
									Toast.makeText(this, "ไม่พบข้อความในกรอบเฉพาะจุด", Toast.LENGTH_SHORT).show()
								} else {
									ocrScope.launch {
										try {
											val joinedEng = noteBlocks.joinToString("\n|||\n") { it.english }
											val joinedThai = translateToThai(joinedEng)
											val parts = joinedThai.split(Regex("\\s*\\|\\|\\|\\s*"))
											if (parts.size == noteBlocks.size) {
												for (i in noteBlocks.indices) {
													noteBlocks[i].thai = parts[i].trim()
												}
											} else {
												for (item in noteBlocks) {
													item.thai = translateToThai(item.english)
												}
											}
											lastNoteEnglish = noteBlocks.joinToString("\n") { it.english }
											lastNoteThai = noteBlocks.joinToString("\n") { it.thai }
											showNoteTranslationCard(noteBlocks, bmpW, bmpH)
										} finally {
											isScanningNote = false
											binding.btnScanNoteFloat.alpha = 1.0f
										}
									}
								}
							}
							.addOnFailureListener {
								isScanningNote = false
								binding.btnScanNoteFloat.alpha = 1.0f
							}
							.addOnCompleteListener {
								fullBitmap.recycle()
								subBitmap.recycle()
								if (scaledSub !== subBitmap) scaledSub.recycle()
							}
					} catch (_: Exception) {
						isScanningNote = false
						binding.btnScanNoteFloat.alpha = 1.0f
						fullBitmap.recycle()
					}
				} else {
					isScanningNote = false
					binding.btnScanNoteFloat.alpha = 1.0f
					fullBitmap.recycle()
				}
			}, Handler(Looper.getMainLooper()))
			} catch (_: Exception) {
				if (wasTextOnlyVisible && isTextOnlyMode) {
					binding.pureFullscreenLayout.visibility = View.VISIBLE
				}
				if (wasFloatingBoxVisible && isNoteCropBoxVisible && !isTextOnlyMode) {
					binding.floatingNoteCropBox.visibility = View.VISIBLE
				}
				isScanningNote = false
				binding.btnScanNoteFloat.alpha = 1.0f
				fullBitmap.recycle()
			}
		}

		if (wasTextOnlyVisible || wasNoteCardVisible || wasFloatingBoxVisible) {
			uiVisibilityHandler.postDelayed(runPixelCopy, 40)
		} else {
			runPixelCopy()
		}
	}

	private fun setFloatingNoteCropBoxVisible(visible: Boolean) {
		isNoteCropBoxVisible = visible
		cropPrefs.edit().putBoolean("note_crop_visible", visible).apply()
		if (visible && !isTextOnlyMode) {
			binding.floatingNoteCropBox.isVisible = true
			syncFloatingNoteCropBox()
		} else {
			binding.floatingNoteCropBox.isVisible = false
		}
		updateQuickDrawerUI()
	}

	private fun syncFloatingNoteCropBox() {
		binding.mainStreamLayout.post {
			val parentW = binding.mainStreamLayout.width
			val parentH = binding.mainStreamLayout.height
			if (parentW <= 0 || parentH <= 0) return@post

			val leftNorm = cropPrefs.getFloat("note_crop_left", 0.20f)
			val topNorm = cropPrefs.getFloat("note_crop_top", 0.15f)
			val widthNorm = cropPrefs.getFloat("note_crop_width", 0.60f)
			val heightNorm = cropPrefs.getFloat("note_crop_height", 0.65f)

			val w = (parentW * widthNorm).toInt().coerceIn(dpToPx(140), parentW)
			val h = (parentH * heightNorm).toInt().coerceIn(dpToPx(70), parentH)
			val lp = binding.floatingNoteCropBox.layoutParams
			lp.width = w
			lp.height = h
			binding.floatingNoteCropBox.layoutParams = lp
			binding.floatingNoteCropBox.x = (parentW * leftNorm).coerceIn(0f, (parentW - w).toFloat().coerceAtLeast(0f))
			binding.floatingNoteCropBox.y = (parentH * topNorm).coerceIn(0f, (parentH - h).toFloat().coerceAtLeast(0f))
		}
	}

	private fun saveFloatingNoteCropBoxCoords() {
		val parentW = binding.mainStreamLayout.width.toFloat()
		val parentH = binding.mainStreamLayout.height.toFloat()
		if (parentW <= 0f || parentH <= 0f) return

		val leftNorm = (binding.floatingNoteCropBox.x / parentW).coerceIn(0f, 0.85f)
		val topNorm = (binding.floatingNoteCropBox.y / parentH).coerceIn(0f, 0.90f)
		val widthNorm = (binding.floatingNoteCropBox.width / parentW).coerceIn(0.10f, 1f - leftNorm)
		val heightNorm = (binding.floatingNoteCropBox.height / parentH).coerceIn(0.06f, 1f - topNorm)

		cropPrefs.edit()
			.putFloat("note_crop_left", leftNorm)
			.putFloat("note_crop_top", topNorm)
			.putFloat("note_crop_width", widthNorm)
			.putFloat("note_crop_height", heightNorm)
			.apply()
	}

	private fun showNoteTranslationCard(blocks: List<NoteBlockItem>, bmpW: Int, bmpH: Int) {
		uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)

		val parentW = binding.mainStreamLayout.width
		val parentH = binding.mainStreamLayout.height
		val cardLp = binding.noteTranslationCard.layoutParams as FrameLayout.LayoutParams
		val targetW: Int
		if (!isTextOnlyMode && parentW > 0 && parentH > 0) {
			val leftNorm = cropPrefs.getFloat("note_crop_left", 0.20f)
			val topNorm = cropPrefs.getFloat("note_crop_top", 0.15f)
			val widthNorm = cropPrefs.getFloat("note_crop_width", 0.60f)
			targetW = (parentW * widthNorm).toInt().coerceIn(dpToPx(220), parentW - dpToPx(24))
			val targetX = (parentW * leftNorm).coerceIn(dpToPx(12).toFloat(), (parentW - targetW - dpToPx(12)).toFloat().coerceAtLeast(0f))
			val targetY = (parentH * topNorm).coerceIn(dpToPx(16).toFloat(), (parentH - dpToPx(140)).toFloat().coerceAtLeast(dpToPx(16).toFloat()))

			cardLp.gravity = Gravity.TOP or Gravity.START
			cardLp.width = targetW
			cardLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
			binding.noteTranslationCard.layoutParams = cardLp
			binding.noteTranslationCard.x = targetX
			binding.noteTranslationCard.y = targetY
		} else {
			targetW = (parentW - dpToPx(96)).coerceAtLeast(dpToPx(240))
			cardLp.gravity = Gravity.TOP or Gravity.START
			cardLp.width = targetW
			cardLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
			binding.noteTranslationCard.layoutParams = cardLp
			binding.noteTranslationCard.x = dpToPx(24).toFloat()
			binding.noteTranslationCard.y = dpToPx(40).toFloat()
		}

		// Render translated Thai blocks: compact stacked at top in Text-Only mode, or positional in normal mode
		binding.notePositionalCanvas.removeAllViews()
		val innerW = (targetW - dpToPx(28)).coerceAtLeast(dpToPx(180))
		var maxCanvasBottom = dpToPx(40)

		if (isTextOnlyMode) {
			var curTop = 0
			for (item in blocks) {
				if (item.thai.isBlank()) continue
				val tv = android.widget.TextView(this).apply {
					text = item.thai
					setTextColor(Color.WHITE)
					setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
					setTypeface(typeface, android.graphics.Typeface.BOLD)
					setLineSpacing(dpToPx(2).toFloat(), 1.08f)
					maxWidth = innerW
				}
				tv.measure(
					View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.AT_MOST),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
				)
				val lp = FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.WRAP_CONTENT,
					FrameLayout.LayoutParams.WRAP_CONTENT
				).apply {
					leftMargin = 0
					topMargin = curTop
				}
				binding.notePositionalCanvas.addView(tv, lp)
				curTop += tv.measuredHeight + dpToPx(6)
			}
			maxCanvasBottom = curTop.coerceAtLeast(dpToPx(40))
		} else {
			val scaleRatio = if (bmpW > 0) innerW.toFloat() / bmpW.toFloat() else 1f
			val placedRects = mutableListOf<Rect>()

			for (item in blocks) {
				if (item.thai.isBlank()) continue
				val leftPx = (item.rect.left * scaleRatio).toInt().coerceIn(0, (innerW - dpToPx(80)).coerceAtLeast(0))
				var topPx = (item.rect.top * scaleRatio).toInt().coerceAtLeast(0)
				val maxW = (innerW - leftPx).coerceAtLeast(dpToPx(90))
				val boxHeightPx = (item.rect.height() * scaleRatio).toInt()
				val textSp = (boxHeightPx / resources.displayMetrics.density * 0.52f).coerceIn(13f, 18f)

				val tv = android.widget.TextView(this).apply {
					text = item.thai
					setTextColor(Color.WHITE)
					setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
					setTypeface(typeface, android.graphics.Typeface.BOLD)
					setLineSpacing(dpToPx(2).toFloat(), 1.05f)
					maxWidth = maxW
				}

				tv.measure(
					View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
				)
				val measuredW = tv.measuredWidth.coerceAtLeast(dpToPx(24))
				val measuredH = tv.measuredHeight.coerceAtLeast(dpToPx(18))

				for (prev in placedRects) {
					val horizOverlap = leftPx < prev.right && (leftPx + measuredW) > prev.left
					if (horizOverlap && topPx < prev.bottom + dpToPx(4)) {
						topPx = prev.bottom + dpToPx(4)
					}
				}

				val lp = FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.WRAP_CONTENT,
					FrameLayout.LayoutParams.WRAP_CONTENT
				).apply {
					leftMargin = leftPx
					topMargin = topPx
				}
				binding.notePositionalCanvas.addView(tv, lp)
				val finalRect = Rect(leftPx, topPx, leftPx + measuredW, topPx + measuredH)
				placedRects.add(finalRect)
				if (finalRect.bottom > maxCanvasBottom) {
					maxCanvasBottom = finalRect.bottom
				}
			}
		}

		binding.notePositionalCanvas.minimumHeight = (maxCanvasBottom + dpToPx(8)).coerceAtLeast(dpToPx(40))
		// Cap ScrollView height so card never overflows screen or covers bottom subtitles in DeX/Text-Only mode
		val maxScrollH = if (isTextOnlyMode) {
			(parentH * 0.40f).toInt().coerceAtLeast(dpToPx(120))
		} else {
			(parentH - binding.noteTranslationCard.y.toInt() - dpToPx(84)).coerceAtLeast(dpToPx(120))
		}
		val scrollLp = binding.noteScrollView.layoutParams
		scrollLp.height = if (maxCanvasBottom + dpToPx(12) > maxScrollH) maxScrollH else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
		binding.noteScrollView.layoutParams = scrollLp

		binding.noteTranslationCard.isVisible = true
		binding.noteTranslationCard.alpha = 0f
		binding.noteTranslationCard.scaleX = 0.92f
		binding.noteTranslationCard.scaleY = 0.92f
		binding.noteTranslationCard.animate().alpha(1f).setDuration(150).start()
		SpringAnimation(binding.noteTranslationCard, DynamicAnimation.SCALE_X, 1f).apply {
			spring = SpringForce(1f).apply { stiffness = 480f; dampingRatio = 0.65f }
			start()
		}
		SpringAnimation(binding.noteTranslationCard, DynamicAnimation.SCALE_Y, 1f).apply {
			spring = SpringForce(1f).apply { stiffness = 480f; dampingRatio = 0.65f }
			start()
		}

		// In Text-Only fullscreen mode, shift subtitles to bottom temporarily so note card sits cleanly on top
		val lp = binding.pureFullscreenInner.layoutParams as FrameLayout.LayoutParams
		lp.gravity = Gravity.BOTTOM
		binding.pureFullscreenInner.layoutParams = lp

		uiVisibilityHandler.postDelayed(hideNoteCardRunnable, 20000)
		syncTvOverlayState()
	}

	private fun setupPopupZonesControls() {
		isPopupLeftEnabled = cropPrefs.getBoolean("popup_left_enabled", true)
		isPopupRightEnabled = cropPrefs.getBoolean("popup_right_enabled", true)
		isFilterPopupHudEnabled = cropPrefs.getBoolean("popup_filter_hud", true)
		popupHoldDurationMs = cropPrefs.getLong("popup_hold_duration_ms", 5000L)

		binding.switchPopupLeftZone.isChecked = isPopupLeftEnabled
		binding.switchPopupRightZone.isChecked = isPopupRightEnabled
		binding.switchFilterPopupHud.isChecked = isFilterPopupHudEnabled

		when (popupHoldDurationMs) {
			3000L -> binding.popupHoldToggleGroup.check(R.id.btnPopupHold3)
			8000L -> binding.popupHoldToggleGroup.check(R.id.btnPopupHold8)
			else -> binding.popupHoldToggleGroup.check(R.id.btnPopupHold5)
		}

		binding.switchPopupLeftZone.setOnCheckedChangeListener { _, isChecked ->
			isPopupLeftEnabled = isChecked
			cropPrefs.edit().putBoolean("popup_left_enabled", isChecked).apply()
			if (!isChecked) hidePopupCard(PopupSide.LEFT)
		}

		binding.switchPopupRightZone.setOnCheckedChangeListener { _, isChecked ->
			isPopupRightEnabled = isChecked
			cropPrefs.edit().putBoolean("popup_right_enabled", isChecked).apply()
			if (!isChecked) hidePopupCard(PopupSide.RIGHT)
		}

		binding.switchFilterPopupHud.setOnCheckedChangeListener { _, isChecked ->
			isFilterPopupHudEnabled = isChecked
			cropPrefs.edit().putBoolean("popup_filter_hud", isChecked).apply()
		}

		binding.popupHoldToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				popupHoldDurationMs = when (checkedId) {
					R.id.btnPopupHold3 -> 3000L
					R.id.btnPopupHold8 -> 8000L
					else -> 5000L
				}
				cropPrefs.edit().putLong("popup_hold_duration_ms", popupHoldDurationMs).apply()
			}
		}

		binding.switchPopupTtsQuick.isChecked = isPopupTtsEnabled
		binding.switchPopupTtsQuick.setOnCheckedChangeListener { _, isChecked ->
			setPopupTtsEnabled(isChecked)
		}

		// Left & Right Popup Vertical Position Sliders (0% = Top .. 100% = Bottom)
		binding.popupLeftVerticalSeekBar.progress = popupLeftVerticalPercent
		binding.tvPopupLeftPosLabel.text = "ตำแหน่งกล่องซ้าย (ขึ้น–ลง): $popupLeftVerticalPercent%"
		binding.popupLeftVerticalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (!fromUser) return
				popupLeftVerticalPercent = progress.coerceIn(0, 100)
				binding.tvPopupLeftPosLabel.text = "ตำแหน่งกล่องซ้าย (ขึ้น–ลง): $popupLeftVerticalPercent%"
				applyPopupVerticalPositions()
				syncTvOverlayState()
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				cropPrefs.edit().putInt("popup_left_vertical_percent", popupLeftVerticalPercent).apply()
			}
		})

		binding.popupRightVerticalSeekBar.progress = popupRightVerticalPercent
		binding.tvPopupRightPosLabel.text = "ตำแหน่งกล่องขวา (ขึ้น–ลง): $popupRightVerticalPercent%"
		binding.popupRightVerticalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				if (!fromUser) return
				popupRightVerticalPercent = progress.coerceIn(0, 100)
				binding.tvPopupRightPosLabel.text = "ตำแหน่งกล่องขวา (ขึ้น–ลง): $popupRightVerticalPercent%"
				applyPopupVerticalPositions()
				syncTvOverlayState()
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				cropPrefs.edit().putInt("popup_right_vertical_percent", popupRightVerticalPercent).apply()
			}
		})
		applyPopupVerticalPositions()

		// Tap-to-read & Close buttons on both Normal & Pure Black popup cards
		binding.popupLeftCard.setOnClickListener {
			if (lastPopupLeftThai.isNotEmpty()) speakPopupNow(PopupSide.LEFT, lastPopupLeftThai)
		}
		binding.popupLeftCard.setOnLongClickListener {
			hidePopupCard(PopupSide.LEFT)
			true
		}
		binding.purePopupLeftCard.setOnClickListener {
			if (lastPopupLeftThai.isNotEmpty()) speakPopupNow(PopupSide.LEFT, lastPopupLeftThai)
		}
		binding.purePopupLeftCard.setOnLongClickListener {
			hidePopupCard(PopupSide.LEFT)
			true
		}
		binding.popupRightCard.setOnClickListener {
			if (lastPopupRightThai.isNotEmpty()) speakPopupNow(PopupSide.RIGHT, lastPopupRightThai)
		}
		binding.purePopupRightCard.setOnClickListener {
			if (lastPopupRightThai.isNotEmpty()) speakPopupNow(PopupSide.RIGHT, lastPopupRightThai)
		}

		binding.btnClosePopupRight.setOnClickListener { hidePopupCard(PopupSide.RIGHT) }
		binding.btnClosePurePopupRight.setOnClickListener { hidePopupCard(PopupSide.RIGHT) }

		binding.popupZonesConfigContainer.isVisible = isPopupZonesMode
	}

	private fun applyPopupVerticalPositions() {
		val screenH = (binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
		val leftTopPx = ((0.04f + (popupLeftVerticalPercent / 100f) * 0.72f) * screenH).toInt().coerceAtLeast(dpToPx(8))
		val rightTopPx = ((0.04f + (popupRightVerticalPercent / 100f) * 0.72f) * screenH).toInt().coerceAtLeast(dpToPx(8))

		fun applyTop(view: View, gravityH: Int, topMarginPx: Int) {
			val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
			lp.gravity = gravityH or Gravity.TOP
			lp.topMargin = topMarginPx
			lp.bottomMargin = 0
			view.layoutParams = lp
		}

		applyTop(binding.popupLeftCard, Gravity.START, leftTopPx)
		applyTop(binding.purePopupLeftCard, Gravity.START, leftTopPx)
		applyTop(binding.popupRightCard, Gravity.END, rightTopPx)
		applyTop(binding.purePopupRightCard, Gravity.END, rightTopPx)
	}

	private fun setupPopupAudioControls() {
		isPopupTtsEnabled = cropPrefs.getBoolean("popup_tts_enabled", true)
		popupTtsMode = try {
			PopupTtsMode.valueOf(cropPrefs.getString("popup_tts_mode", PopupTtsMode.SMART_QUEUE.name) ?: PopupTtsMode.SMART_QUEUE.name)
		} catch (_: Exception) {
			PopupTtsMode.SMART_QUEUE
		}
		popupVoiceTone = try {
			PopupVoiceTone.valueOf(cropPrefs.getString("popup_voice_tone", PopupVoiceTone.BRIGHT.name) ?: PopupVoiceTone.BRIGHT.name)
		} catch (_: Exception) {
			PopupVoiceTone.BRIGHT
		}
		popupVolume = cropPrefs.getFloat("popup_volume", 0.75f).coerceIn(0.30f, 1.0f)
		isPopupStereoPanEnabled = cropPrefs.getBoolean("popup_stereo_pan", false)
		isPopupChimeEnabled = cropPrefs.getBoolean("popup_chime", true)

		binding.switchPopupTts.isChecked = isPopupTtsEnabled
		binding.popupVoiceSettingsContainer.isVisible = isPopupTtsEnabled
		binding.switchPopupStereoPan.isChecked = isPopupStereoPanEnabled
		binding.switchPopupChime.isChecked = isPopupChimeEnabled

		binding.popupTtsModeToggleGroup.check(
			if (popupTtsMode == PopupTtsMode.DUAL_CHANNEL) R.id.btnPopupTtsDual else R.id.btnPopupTtsSmart
		)
		binding.popupVoiceToneToggleGroup.check(
			when (popupVoiceTone) {
				PopupVoiceTone.DEEP -> R.id.btnPopupToneDeep
				PopupVoiceTone.NORMAL -> R.id.btnPopupToneNormal
				else -> R.id.btnPopupToneBright
			}
		)

		val volPct = (popupVolume * 100).toInt().coerceIn(30, 100)
		binding.popupVolumeSeekBar.progress = volPct - 30
		binding.tvPopupVolume.text = "$volPct%"

		binding.switchPopupTts.setOnCheckedChangeListener { _, isChecked ->
			setPopupTtsEnabled(isChecked)
		}

		binding.popupTtsModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				popupTtsMode = if (checkedId == R.id.btnPopupTtsDual) PopupTtsMode.DUAL_CHANNEL else PopupTtsMode.SMART_QUEUE
				cropPrefs.edit().putString("popup_tts_mode", popupTtsMode.name).apply()
			}
		}

		binding.popupVoiceToneToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				popupVoiceTone = when (checkedId) {
					R.id.btnPopupToneDeep -> PopupVoiceTone.DEEP
					R.id.btnPopupToneNormal -> PopupVoiceTone.NORMAL
					else -> PopupVoiceTone.BRIGHT
				}
				cropPrefs.edit().putString("popup_voice_tone", popupVoiceTone.name).apply()
			}
		}

		binding.popupVolumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				val pct = (progress + 30).coerceIn(30, 100)
				popupVolume = pct / 100f
				binding.tvPopupVolume.text = "$pct%"
			}
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			override fun onStopTrackingTouch(seekBar: SeekBar?) {
				cropPrefs.edit().putFloat("popup_volume", popupVolume).apply()
			}
		})

		binding.switchPopupStereoPan.setOnCheckedChangeListener { _, isChecked ->
			isPopupStereoPanEnabled = isChecked
			cropPrefs.edit().putBoolean("popup_stereo_pan", isChecked).apply()
		}

		binding.switchPopupChime.setOnCheckedChangeListener { _, isChecked ->
			isPopupChimeEnabled = isChecked
			cropPrefs.edit().putBoolean("popup_chime", isChecked).apply()
		}
	}

	private fun setPopupTtsEnabled(enabled: Boolean) {
		if (isPopupTtsEnabled == enabled &&
			binding.switchPopupTts.isChecked == enabled &&
			binding.switchPopupTtsQuick.isChecked == enabled
		) {
			return
		}
		isPopupTtsEnabled = enabled
		cropPrefs.edit().putBoolean("popup_tts_enabled", enabled).apply()
		if (binding.switchPopupTts.isChecked != enabled) {
			binding.switchPopupTts.isChecked = enabled
		}
		if (binding.switchPopupTtsQuick.isChecked != enabled) {
			binding.switchPopupTtsQuick.isChecked = enabled
		}
		binding.popupVoiceSettingsContainer.isVisible = enabled
		if (!enabled) {
			pendingSmartPopupSpeech = null
			popupTts?.stop()
			stopPopupGeminiAudioPlayback()
		}
		updateGeminiApiStatusUI()
	}

	private fun setPopupZonesMode(enable: Boolean) {
		isPopupZonesMode = enable
		cropPrefs.edit().putBoolean("popup_zones_mode", enable).apply()
		binding.extraTranslateModeToggleGroup.check(if (enable) R.id.btnModePopupZones else R.id.btnModeSpot)
		applyExtraTranslateModeUI()
	}

	private fun applyExtraTranslateModeUI() {
		binding.popupZonesConfigContainer.isVisible = isPopupZonesMode
		if (isPopupZonesMode) {
			// Auto Popup Zones mode: hide floating spot translate icon & spot box
			binding.btnScanNoteFloat.isVisible = false
			binding.floatingNoteCropBox.isVisible = false
			hideNoteTranslationCard()
		} else {
			// On-demand Spot Translation mode: hide popup cards and show floating translate icon
			hidePopupCard(PopupSide.LEFT)
			hidePopupCard(PopupSide.RIGHT)
			lastPopupLeftEnglish = ""
			lastPopupRightEnglish = ""
			binding.btnScanNoteFloat.isVisible = true
			binding.floatingNoteCropBox.isVisible = isNoteCropBoxVisible && !isTextOnlyMode
			if (isNoteCropBoxVisible && !isTextOnlyMode) {
				syncFloatingNoteCropBox()
			}
		}
		syncPopupCardVisibility()
		updateQuickDrawerUI()
	}

	private fun cropPopupZoneBitmap(fullBitmap: Bitmap, side: PopupSide): Bitmap? {
		return try {
			val target = if (side == PopupSide.LEFT) CropEditTarget.POPUP_LEFT else CropEditTarget.POPUP_RIGHT
			val prefix = getCropPrefix(target)
			val defs = getCropDefaults(target)
			val leftNorm = cropPrefs.getFloat("${prefix}left", defs[0]).coerceIn(0f, 0.90f)
			val topNorm = cropPrefs.getFloat("${prefix}top", defs[1]).coerceIn(0f, 0.90f)
			val widthNorm = cropPrefs.getFloat("${prefix}width", defs[2]).coerceIn(0.08f, 1f - leftNorm)
			val heightNorm = cropPrefs.getFloat("${prefix}height", defs[3]).coerceIn(0.06f, 1f - topNorm)

			val cropX = (1920 * leftNorm).toInt().coerceIn(0, 1919)
			val cropY = (1080 * topNorm).toInt().coerceIn(0, 1079)
			val cropW = (1920 * widthNorm).toInt().coerceIn(40, 1920 - cropX)
			val cropH = (1080 * heightNorm).toInt().coerceIn(30, 1080 - cropY)

			val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
			if (cropW < 520) {
				val scaled = Bitmap.createScaledBitmap(cropped, (cropW * 1.5f).toInt(), (cropH * 1.5f).toInt(), true)
				cropped.recycle()
				scaled
			} else {
				cropped
			}
		} catch (e: Exception) {
			android.util.Log.w("PSTranslatorPopup", "Crop error ($side): ${e.message}")
			null
		}
	}

	private fun extractStructuredPopupText(
		visionText: com.google.mlkit.vision.text.Text,
		side: PopupSide,
		cropW: Int
	): String {
		data class OcrLineItem(val text: String, val top: Int, val left: Int, val right: Int)

		val rawLines = mutableListOf<OcrLineItem>()
		for (block in visionText.textBlocks) {
			for (line in block.lines) {
				val box = line.boundingBox ?: block.boundingBox
				val cleaned = fixOcrText(line.text.trim())
					.replace(Regex("^(?:->|→|»|//|vv|✓+)\\s*", RegexOption.IGNORE_CASE), "")
					.replace(Regex("\\s+(?://|vv|✓+)$", RegexOption.IGNORE_CASE), "")
					.replace(Regex("\\s+"), " ")
					.trim()
				val letters = cleaned.count { it.isLetter() }
				if (letters < 2) continue
				rawLines.add(
					OcrLineItem(
						text = cleaned,
						top = box?.top ?: 0,
						left = box?.left ?: 0,
						right = box?.right ?: 0
					)
				)
			}
		}
		if (rawLines.isEmpty()) return ""

		// Sort top-to-bottom (group lines on the same visual row within 14px, then left-to-right)
		val sorted = rawLines.sortedWith { a, b ->
			if (kotlin.math.abs(a.top - b.top) <= 14) {
				a.left.compareTo(b.left)
			} else {
				a.top.compareTo(b.top)
			}
		}.toMutableList()

		// On right phone popup, if the bottom-most line is a short right-aligned reply button (e.g. "-> Brian"), drop it
		if (side == PopupSide.RIGHT && sorted.size >= 3 && cropW > 100) {
			val last = sorted.last()
			val lastWords = last.text.split(" ").filter { it.isNotEmpty() }
			if (lastWords.size <= 2 && last.text.length <= 16 && last.left > (cropW * 0.46f)) {
				sorted.removeAt(sorted.size - 1)
			}
		}

		// Merge lines that are on the exact same horizontal row
		val mergedRows = mutableListOf<String>()
		var prevTop = -9999
		for (item in sorted) {
			if (mergedRows.isNotEmpty() && kotlin.math.abs(item.top - prevTop) <= 14) {
				val prev = mergedRows.removeAt(mergedRows.size - 1)
				// Ignore trailing 2-char read-receipt artifact on the same row as sender name
				if (item.text.length <= 2 && !item.text.any { it.isDigit() }) {
					mergedRows.add(prev)
				} else {
					mergedRows.add("$prev ${item.text}")
				}
			} else {
				mergedRows.add(item.text)
				prevTop = item.top
			}
		}
		if (mergedRows.isEmpty()) return ""

		// Only RIGHT chat popups have a sender header (e.g. "Jason", "Mandy"); LEFT tutorial boxes are plain body text
		val first = mergedRows[0]
		val firstWords = first.split(" ").filter { it.isNotEmpty() }
		val isSenderHeader = side == PopupSide.RIGHT &&
			mergedRows.size >= 2 &&
			firstWords.size in 1..2 &&
			first.length in 2..20 &&
			!first.endsWith(".") &&
			!first.endsWith("?") &&
			!first.endsWith("!") &&
			!first.endsWith(":") &&
			first[0].isUpperCase()

		return if (isSenderHeader) {
			val body = mergedRows.drop(1).joinToString(" ").replace(Regex("\\s+"), " ").trim()
			"$first: $body"
		} else {
			mergedRows.joinToString(" ").replace(Regex("\\s+"), " ").trim()
		}
	}

	private fun isNearlyIdenticalPopupText(a: String, b: String): Boolean {
		if (a.isBlank() || b.isBlank()) return false
		val cleanA = a.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
		val cleanB = b.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
		if (cleanA.isEmpty() || cleanB.isEmpty()) return false
		if (cleanA == cleanB) return true
		// If length grew by 3+ characters, it is still typing/expanding -> NOT identical!
		if (kotlin.math.abs(cleanA.length - cleanB.length) >= 3) return false
		val maxLen = maxOf(cleanA.length, cleanB.length)
		val dist = levenshteinDistance(cleanA, cleanB)
		return (1.0 - dist.toDouble() / maxLen) >= 0.92
	}

	private fun hasDanglingMidWordTypingTail(text: String): Boolean {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return false
		val lastChar = trimmed.last()
		if (lastChar in listOf('.', '!', '?', '"', '\'', ')')) return false
		val lastToken = trimmed.substringAfterLast(' ').lowercase()
		// A single trailing consonant letter (like "as a f" or "Got u") indicates typewriter animation mid-word
		return lastToken.length == 1 && lastToken[0] in 'b'..'z' && lastToken != "i" && lastToken != "a"
	}

	private fun preparePopupBitmapForOcr(src: Bitmap): Bitmap {
		// Upscale small HUD/tutorial text by 2x and add dark padding so ML Kit never clips edge words (e.g. "Vehicles in ... be stolen.")
		val scale = if (src.width < 900) 2 else 1
		val scaledW = (src.width * scale).coerceAtLeast(1)
		val scaledH = (src.height * scale).coerceAtLeast(1)
		val pad = 24
		val out = Bitmap.createBitmap(scaledW + pad * 2, scaledH + pad * 2, Bitmap.Config.ARGB_8888)
		val canvas = android.graphics.Canvas(out)
		canvas.drawColor(Color.rgb(24, 26, 32))
		val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
		val dstRect = Rect(pad, pad, pad + scaledW, pad + scaledH)
		canvas.drawBitmap(src, null, dstRect, paint)
		return out
	}

	private fun processPopupZoneBitmap(side: PopupSide, ocrBitmap: Bitmap) {
		val enhancedBitmap = try {
			preparePopupBitmapForOcr(ocrBitmap)
		} catch (_: Exception) {
			ocrBitmap
		}
		val cropW = enhancedBitmap.width
		val image = InputImage.fromBitmap(enhancedBitmap, 0)
		textRecognizer.process(image)
			.addOnSuccessListener { visionText ->
				if (!isPopupZonesMode) return@addOnSuccessListener
				val text = extractStructuredPopupText(visionText, side, cropW)

				if (text.isEmpty() || !isValidPopupText(text)) {
					if (side == PopupSide.LEFT) {
						pendingPopupLeftCandidate = ""
						pendingPopupLeftStableTicks = 0
					} else {
						pendingPopupRightCandidate = ""
						pendingPopupRightStableTicks = 0
					}
					return@addOnSuccessListener
				}

				// Skip if identical to current main subtitle
				if (lastTranslatedEnglish.isNotEmpty() && isSimilarText(text, lastTranslatedEnglish)) return@addOnSuccessListener

				val now = System.currentTimeMillis()
				if (side == PopupSide.LEFT) {
					// Keep visible card alive if text is already displayed and hasn't grown
					if (isPopupLeftVisible && isNearlyIdenticalPopupText(text, lastPopupLeftEnglish)) {
						uiVisibilityHandler.removeCallbacks(hidePopupLeftRunnable)
						uiVisibilityHandler.postDelayed(hidePopupLeftRunnable, popupHoldDurationMs)
						pendingPopupLeftCandidate = text
						pendingPopupLeftStableTicks = 2
						return@addOnSuccessListener
					}

					// Typewriter / fade-in settling: wait until text stops growing between consecutive scans
					if (pendingPopupLeftCandidate.isEmpty() ||
						text.length > pendingPopupLeftCandidate.length + 1 ||
						!isNearlyIdenticalPopupText(text, pendingPopupLeftCandidate)
					) {
						pendingPopupLeftCandidate = text
						pendingPopupLeftStableTicks = 1
						pendingPopupLeftFirstSeenMs = now
						return@addOnSuccessListener
					}

					pendingPopupLeftStableTicks++
					if (text.length > pendingPopupLeftCandidate.length) {
						pendingPopupLeftCandidate = text
					}
					val settledText = pendingPopupLeftCandidate
					// If ending has a single mid-word consonant ("as a f"), wait up to 1.6s for typing to complete
					if (hasDanglingMidWordTypingTail(settledText) && (now - pendingPopupLeftFirstSeenMs) < 1600L) {
						return@addOnSuccessListener
					}

					if (pendingPopupLeftStableTicks >= 2 && !isNearlyIdenticalPopupText(settledText, lastPopupLeftEnglish)) {
						if (isTranslatingPopupLeft) {
							queuedPopupLeftEnglish = settledText
						} else {
							translateAndShowPopup(PopupSide.LEFT, settledText)
						}
					}
				} else {
					if (isPopupRightVisible && isNearlyIdenticalPopupText(text, lastPopupRightEnglish)) {
						uiVisibilityHandler.removeCallbacks(hidePopupRightRunnable)
						uiVisibilityHandler.postDelayed(hidePopupRightRunnable, popupHoldDurationMs)
						pendingPopupRightCandidate = text
						pendingPopupRightStableTicks = 2
						return@addOnSuccessListener
					}

					if (pendingPopupRightCandidate.isEmpty() ||
						text.length > pendingPopupRightCandidate.length + 1 ||
						!isNearlyIdenticalPopupText(text, pendingPopupRightCandidate)
					) {
						pendingPopupRightCandidate = text
						pendingPopupRightStableTicks = 1
						pendingPopupRightFirstSeenMs = now
						return@addOnSuccessListener
					}

					pendingPopupRightStableTicks++
					if (text.length > pendingPopupRightCandidate.length) {
						pendingPopupRightCandidate = text
					}
					val settledText = pendingPopupRightCandidate
					if (hasDanglingMidWordTypingTail(settledText) && (now - pendingPopupRightFirstSeenMs) < 1600L) {
						return@addOnSuccessListener
					}

					if (pendingPopupRightStableTicks >= 2 && !isNearlyIdenticalPopupText(settledText, lastPopupRightEnglish)) {
						if (isTranslatingPopupRight) {
							queuedPopupRightEnglish = settledText
						} else {
							translateAndShowPopup(PopupSide.RIGHT, settledText)
						}
					}
				}
			}
			.addOnFailureListener { e ->
				android.util.Log.w("PSTranslatorPopup", "OCR error ($side): ${e.message}")
			}
			.addOnCompleteListener {
				if (!ocrBitmap.isRecycled) ocrBitmap.recycle()
				if (side == PopupSide.LEFT) isScanningPopupLeft = false else isScanningPopupRight = false
			}
	}

	private fun isValidPopupText(text: String): Boolean {
		val letterCount = text.count { it.isLetter() }
		if (!isFilterPopupHudEnabled) {
			return letterCount >= 4
		}

		val nonSpaceCount = text.count { !it.isWhitespace() }.coerceAtLeast(1)
		// Reject counters, ammo, speedometers, timers, money ($12,400, 24/120, 85 MPH, 02:45)
		if (letterCount < 8) return false
		if (letterCount.toFloat() / nonSpaceCount < 0.52f) return false

		val rawWords = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
		val alphaWords = rawWords.map { it.lowercase().replace(Regex("[^a-z0-9-]"), "") }.filter { it.isNotEmpty() }
		val realWords = alphaWords.filter { it.length >= 2 && it.any { c -> c in 'a'..'z' } }
		if (realWords.size < 2 && letterCount < 12) return false

		// 1. Reject HUD item/ammo counters like "WATER (2/2)", "24/120", "1/3" in short HUD stacks
		val hasCounterFraction = Regex("\\b\\d+\\s*/\\s*\\d+\\b").containsMatchIn(text)
		if (hasCounterFraction && alphaWords.size <= 6) return false

		// 2. Reject short bracketed controller button prompts like "DRINK (R1) PUT DOWN" (<= 5 words)
		val hasButtonTag = Regex("[(\\[{]\\s*(?:r1|r2|r3|ri|rl|l1|l2|l3|li|ll|x|o|0|a)\\s*[)\\]}]", RegexOption.IGNORE_CASE)
			.containsMatchIn(text)
		if (hasButtonTag && alphaWords.size <= 5) return false

		// 3. Reject ALL-CAPS short HUD action menus like "DRINK (RI) PUT DOWN WATER" / "OPEN DOOR"
		val upperCount = text.count { it.isUpperCase() }
		val upperRatio = upperCount.toFloat() / letterCount.coerceAtLeast(1)
		if (upperRatio >= 0.88f && (alphaWords.size <= 5 || hasButtonTag || hasCounterFraction)) {
			return false
		}

		// Only filter very short 1-4 word button/HUD clusters (allow full 5+ word tutorial sentences!)
		if (alphaWords.size <= 4) {
			val buttonTokens = setOf(
				"x", "o", "a", "l1", "l2", "l3", "li", "ll", "r1", "r2", "r3", "ri", "rl",
				"ps", "ls", "rs", "dpad", "d-pad", "pad", "touchpad", "options", "share"
			)
			val shortHudWords = setOf(
				"hold", "press", "tap", "aim", "fire", "shoot", "reload", "sprint",
				"drink", "drinks", "eat", "put", "water", "beer", "sit", "stand",
				"mph", "kmh", "fps", "ammo", "health", "armor", "stamina"
			)
			val hudHitCount = alphaWords.count { it in buttonTokens || it in shortHudWords }
			if (hudHitCount >= 2) return false
		}

		return true
	}

	private fun translateAndShowPopup(side: PopupSide, englishText: String) {
		if (side == PopupSide.LEFT) {
			isTranslatingPopupLeft = true
		} else {
			isTranslatingPopupRight = true
		}

		ocrScope.launch {
			try {
				val thai = translateToThai(englishText, isPopup = true).trim()
				if (thai.isEmpty() || !isPopupZonesMode) return@launch

				if (side == PopupSide.LEFT) {
					val isUpgrade = englishText.length >= lastPopupLeftEnglish.length + 3 ||
						!isNearlyIdenticalPopupText(englishText, lastPopupLeftEnglish)
					if (isPopupLeftVisible && !isUpgrade && isSimilarThai(thai, lastPopupLeftThai)) return@launch
					lastPopupLeftEnglish = englishText
					lastPopupLeftThai = thai
					showPopupCard(PopupSide.LEFT, thai)
				} else {
					val isUpgrade = englishText.length >= lastPopupRightEnglish.length + 3 ||
						!isNearlyIdenticalPopupText(englishText, lastPopupRightEnglish)
					if (isPopupRightVisible && !isUpgrade && isSimilarThai(thai, lastPopupRightThai)) return@launch
					lastPopupRightEnglish = englishText
					lastPopupRightThai = thai
					showPopupCard(PopupSide.RIGHT, thai)
				}

				if (isPopupTtsEnabled) {
					speakPopupTranslation(side, englishText, thai)
				}
			} finally {
				if (side == PopupSide.LEFT) {
					isTranslatingPopupLeft = false
					val nextLeft = queuedPopupLeftEnglish
					if (nextLeft.isNotEmpty()) {
						queuedPopupLeftEnglish = ""
						if (!isNearlyIdenticalPopupText(nextLeft, lastPopupLeftEnglish)) {
							translateAndShowPopup(PopupSide.LEFT, nextLeft)
						}
					}
				} else {
					isTranslatingPopupRight = false
					val nextRight = queuedPopupRightEnglish
					if (nextRight.isNotEmpty()) {
						queuedPopupRightEnglish = ""
						if (!isNearlyIdenticalPopupText(nextRight, lastPopupRightEnglish)) {
							translateAndShowPopup(PopupSide.RIGHT, nextRight)
						}
					}
				}
			}
		}
	}

	private fun parsePopupSenderAndBody(side: PopupSide, englishText: String, thaiText: String): Pair<String, String> {
		val defaultSender = if (side == PopupSide.LEFT) "คำแนะนำ" else "ข้อความ"
		val colonIdx = thaiText.indexOf(':')
		if (colonIdx in 1..24) {
			val prefix = thaiText.substring(0, colonIdx).trim()
			val body = thaiText.substring(colonIdx + 1).trim()
			if (prefix.isNotEmpty() && body.isNotEmpty() && prefix.split(Regex("\\s+")).size <= 3) {
				return prefix to body
			}
		}
		val engColonIdx = englishText.indexOf(':')
		if (engColonIdx in 1..20) {
			val engPrefix = englishText.substring(0, engColonIdx).trim()
			if (engPrefix.isNotEmpty() && engPrefix.split(Regex("\\s+")).size <= 2) {
				val body = if (thaiText.startsWith(engPrefix, ignoreCase = true)) {
					thaiText.removePrefix(engPrefix).trim().removePrefix(":").trim().ifEmpty { thaiText }
				} else {
					thaiText
				}
				return engPrefix to body
			}
		}
		return defaultSender to thaiText
	}

	private fun showPopupCard(side: PopupSide, thaiText: String) {
		if (side == PopupSide.LEFT) {
			isPopupLeftVisible = true
			binding.tvPopupLeftText.text = thaiText
			binding.tvPurePopupLeftText.text = thaiText
			uiVisibilityHandler.removeCallbacks(hidePopupLeftRunnable)
			uiVisibilityHandler.postDelayed(hidePopupLeftRunnable, popupHoldDurationMs)
		} else {
			isPopupRightVisible = true
			val (sender, body) = parsePopupSenderAndBody(PopupSide.RIGHT, lastPopupRightEnglish, thaiText)
			val avatarChar = sender.firstOrNull()?.uppercaseChar()?.toString() ?: "M"
			binding.tvPopupRightAvatar.text = avatarChar
			binding.tvPurePopupRightAvatar.text = avatarChar
			binding.tvPopupRightSender.text = sender
			binding.tvPurePopupRightSender.text = sender
			binding.tvPopupRightText.text = body
			binding.tvPurePopupRightText.text = body
			uiVisibilityHandler.removeCallbacks(hidePopupRightRunnable)
			uiVisibilityHandler.postDelayed(hidePopupRightRunnable, popupHoldDurationMs)
		}
		syncPopupCardVisibility()
	}

	private fun hidePopupCard(side: PopupSide) {
		if (side == PopupSide.LEFT) {
			isPopupLeftVisible = false
			uiVisibilityHandler.removeCallbacks(hidePopupLeftRunnable)
			binding.popupLeftCard.isVisible = false
			binding.purePopupLeftCard.isVisible = false
		} else {
			isPopupRightVisible = false
			uiVisibilityHandler.removeCallbacks(hidePopupRightRunnable)
			binding.popupRightCard.isVisible = false
			binding.purePopupRightCard.isVisible = false
		}
		syncTvOverlayState()
	}

	private fun syncPopupCardVisibility() {
		if (!::binding.isInitialized) return
		val showLeft = isPopupZonesMode && isPopupLeftEnabled && isPopupLeftVisible && lastPopupLeftThai.isNotEmpty()
		val showRight = isPopupZonesMode && isPopupRightEnabled && isPopupRightVisible && lastPopupRightThai.isNotEmpty()
		binding.popupLeftCard.isVisible = showLeft && !isTextOnlyMode
		binding.popupRightCard.isVisible = showRight && !isTextOnlyMode
		binding.purePopupLeftCard.isVisible = showLeft && isTextOnlyMode
		binding.purePopupRightCard.isVisible = showRight && isTextOnlyMode
		syncTvOverlayState()
	}

	private fun isMainTtsBusy(): Boolean {
		return tts?.isSpeaking == true ||
			activeUtterances.isNotEmpty() ||
			geminiMediaPlayer != null ||
			geminiAudioQueue.isNotEmpty()
	}

	private fun playPopupChime() {
		if (!isPopupChimeEnabled) return
		try {
			if (popupChimeToneGenerator == null) {
				popupChimeToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 38)
			}
			popupChimeToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 55)
		} catch (_: Exception) {}
	}

	private fun speakPopupTranslation(side: PopupSide, englishText: String, thaiText: String) {
		if (popupTtsMode == PopupTtsMode.SMART_QUEUE && isMainTtsBusy()) {
			pendingSmartPopupSpeech = PendingPopupSpeech(side, englishText, thaiText, System.currentTimeMillis())
			// Safety fallback check in case local TTS engine misses onDone callback
			uiVisibilityHandler.postDelayed({ tryFlushPendingSmartPopupSpeech() }, 2800)
			return
		}
		speakPopupNow(side, thaiText)
	}

	private fun tryFlushPendingSmartPopupSpeech() {
		val pending = pendingSmartPopupSpeech ?: return
		if (isMainTtsBusy()) return
		pendingSmartPopupSpeech = null
		if (!isPopupTtsEnabled || isDestroyed || isFinishing) return
		if (System.currentTimeMillis() - pending.createdAtMs <= 6500L) {
			speakPopupNow(pending.side, pending.thai)
		}
	}

	private fun speakPopupNow(side: PopupSide, thaiText: String) {
		if (thaiText.isBlank() || isDestroyed || isFinishing) return
		playPopupChime()
		val now = System.currentTimeMillis()

		if (geminiApiKey.isNotEmpty() && isGeminiTtsEnabled) {
			val popupVoiceName = when (popupVoiceTone) {
				PopupVoiceTone.BRIGHT -> "Puck"
				PopupVoiceTone.DEEP -> "Fenrir"
				PopupVoiceTone.NORMAL -> "Aoede"
			}
			ocrScope.launch {
				val wavFile = withContext(Dispatchers.IO) {
					synthesizeGeminiTts(thaiText, popupVoiceName, now + 17)
				}
				if (wavFile != null && !isDestroyed && !isFinishing) {
					enqueuePopupGeminiAudio(wavFile, side)
				} else if (!isDestroyed && !isFinishing) {
					speakPopupWithLocalTts(side, thaiText, now)
				}
			}
			return
		}

		speakPopupWithLocalTts(side, thaiText, now)
	}

	private fun speakPopupWithLocalTts(side: PopupSide, thaiText: String, now: Long) {
		val engine = if (isPopupTtsReady && popupTts != null) popupTts else tts
		if (engine == null) return

		val pitch = when (popupVoiceTone) {
			PopupVoiceTone.BRIGHT -> 1.24f
			PopupVoiceTone.DEEP -> 0.78f
			PopupVoiceTone.NORMAL -> 1.02f
		}
		val rate = (speechSpeedRate * 1.05f).coerceIn(1.0f, 1.75f)
		engine.setPitch(pitch)
		engine.setSpeechRate(rate)

		val params = Bundle().apply {
			putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, popupVolume)
			if (isPopupStereoPanEnabled) {
				putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, if (side == PopupSide.LEFT) -0.65f else 0.65f)
			} else {
				putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
			}
		}

		// Flush previous stale popup on the dedicated popup channel so popups stay snappy
		val queueMode = if (engine === popupTts) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
		engine.speak(thaiText, queueMode, params, "popup_${side.name}_$now")
	}

	private fun enqueuePopupGeminiAudio(wavFile: java.io.File, side: PopupSide) {
		// Replace older queued popup audio so popup narration stays current
		while (popupGeminiAudioQueue.size >= 1) {
			popupGeminiAudioQueue.pollFirst()?.first?.delete()
		}
		popupGeminiAudioQueue.addLast(Pair(wavFile, side))
		if (popupMediaPlayer == null) {
			playNextPopupGeminiAudio()
		}
	}

	private fun playNextPopupGeminiAudio() {
		if (isDestroyed || isFinishing) {
			stopPopupGeminiAudioPlayback()
			return
		}
		val nextItem = popupGeminiAudioQueue.pollFirst() ?: return
		val nextFile = nextItem.first
		val side = nextItem.second
		try {
			val mp = MediaPlayer()
			mp.setDataSource(nextFile.absolutePath)
			mp.prepare()
			val leftVol = if (isPopupStereoPanEnabled) {
				if (side == PopupSide.LEFT) popupVolume else popupVolume * 0.30f
			} else {
				popupVolume
			}
			val rightVol = if (isPopupStereoPanEnabled) {
				if (side == PopupSide.RIGHT) popupVolume else popupVolume * 0.30f
			} else {
				popupVolume
			}
			mp.setVolume(leftVol, rightVol)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				try {
					mp.playbackParams = PlaybackParams().setSpeed((speechSpeedRate * 1.04f).coerceIn(1.0f, 1.65f))
				} catch (_: Exception) {}
			}
			mp.setOnCompletionListener {
				it.release()
				if (popupMediaPlayer == it) popupMediaPlayer = null
				nextFile.delete()
				playNextPopupGeminiAudio()
			}
			mp.setOnErrorListener { player, _, _ ->
				player.release()
				if (popupMediaPlayer == player) popupMediaPlayer = null
				nextFile.delete()
				playNextPopupGeminiAudio()
				true
			}
			popupMediaPlayer = mp
			mp.start()
		} catch (_: Exception) {
			nextFile.delete()
			popupMediaPlayer = null
			playNextPopupGeminiAudio()
		}
	}

	private fun stopPopupGeminiAudioPlayback() {
		try {
			popupMediaPlayer?.stop()
		} catch (_: Exception) {}
		try {
			popupMediaPlayer?.release()
		} catch (_: Exception) {}
		popupMediaPlayer = null
		while (popupGeminiAudioQueue.isNotEmpty()) {
			popupGeminiAudioQueue.pollFirst()?.first?.delete()
		}
	}

	private suspend fun translateGoogleFree(englishText: String): String = withContext(Dispatchers.IO) {
		val encoded = try {
			URLEncoder.encode(englishText, "UTF-8")
		} catch (_: Exception) {
			return@withContext englishText
		}

		// Primary endpoint: translate.googleapis.com GTX
		try {
			val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&q=$encoded"
			val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
				setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
				connectTimeout = 4500
				readTimeout = 4500
			}
			if (conn.responseCode in 200..299) {
				val res = conn.inputStream.bufferedReader().use { it.readText() }
				conn.disconnect()
				val json = JSONArray(res)
				val sentences = json.optJSONArray(0)
				if (sentences != null) {
					val sb = StringBuilder()
					for (i in 0 until sentences.length()) {
						val seg = sentences.optJSONArray(i) ?: continue
						val part = seg.optString(0, "")
						if (part.isNotEmpty() && part != "null") {
							sb.append(part)
						}
					}
					val result = sb.toString().trim()
					if (result.isNotEmpty() && result != englishText) {
						return@withContext result
					}
				}
			} else {
				conn.disconnect()
			}
		} catch (_: Exception) {}

		// Backup endpoint: clients5.google.com dict-chrome-ex
		try {
			val url2 = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=th&q=$encoded"
			val conn2 = (URL(url2).openConnection() as java.net.HttpURLConnection).apply {
				setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
				connectTimeout = 4500
				readTimeout = 4500
			}
			if (conn2.responseCode in 200..299) {
				val res2 = conn2.inputStream.bufferedReader().use { it.readText() }
				conn2.disconnect()
				val arr = JSONArray(res2)
				if (arr.length() > 0) {
					val first = arr.optString(0, "").trim()
					if (first.isNotEmpty()) {
						return@withContext first
					}
				}
			} else {
				conn2.disconnect()
			}
		} catch (_: Exception) {}

		englishText
	}

	private fun hideNoteTranslationCard() {
		uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
		binding.noteTranslationCard.isVisible = false
		val lp = binding.pureFullscreenInner.layoutParams as FrameLayout.LayoutParams
		lp.gravity = Gravity.CENTER
		binding.pureFullscreenInner.layoutParams = lp
		syncTvOverlayState()
	}

	private fun openSettingsSheet() {
		showMenuPage("MAIN")
		binding.btnMenuToggle.alpha = 1.0f
		uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)

		binding.menuPanelContainer.isVisible = true
		binding.menuPanelContainer.alpha = 0f
		binding.menuPanelContainer.animate().alpha(1f).setDuration(160).start()

		binding.menuSheetContainer.post {
			binding.menuSheetContainer.pivotX = binding.menuSheetContainer.width.toFloat()
			binding.menuSheetContainer.pivotY = 0f
			binding.menuSheetContainer.scaleX = 0.86f
			binding.menuSheetContainer.scaleY = 0.86f
			binding.menuSheetContainer.translationY = -dpToPx(18).toFloat()
			binding.menuContentWrapper.translationY = -dpToPx(10).toFloat()

			SpringAnimation(binding.menuSheetContainer, DynamicAnimation.SCALE_X, 1f).apply {
				spring = SpringForce(1f).apply { stiffness = 460f; dampingRatio = 0.68f }
				start()
			}
			SpringAnimation(binding.menuSheetContainer, DynamicAnimation.SCALE_Y, 1f).apply {
				spring = SpringForce(1f).apply { stiffness = 460f; dampingRatio = 0.68f }
				start()
			}
			SpringAnimation(binding.menuSheetContainer, DynamicAnimation.TRANSLATION_Y, 0f).apply {
				spring = SpringForce(0f).apply { stiffness = 420f; dampingRatio = 0.70f }
				start()
			}
			SpringAnimation(binding.menuContentWrapper, DynamicAnimation.TRANSLATION_Y, 0f).apply {
				spring = SpringForce(0f).apply { stiffness = 350f; dampingRatio = 0.56f }
				start()
			}
		}
	}

	private fun closeSettingsSheet() {
		if (!binding.menuPanelContainer.isVisible) return
		binding.menuSheetContainer.pivotX = binding.menuSheetContainer.width.toFloat()
		binding.menuSheetContainer.pivotY = 0f
		binding.menuPanelContainer.animate().alpha(0f).setDuration(150).start()
		binding.menuSheetContainer.animate()
			.scaleX(0.88f)
			.scaleY(0.88f)
			.translationY(-dpToPx(14).toFloat())
			.setDuration(150)
			.withEndAction {
				binding.menuPanelContainer.isVisible = false
				binding.menuPanelContainer.alpha = 1f
				binding.menuSheetContainer.scaleX = 1f
				binding.menuSheetContainer.scaleY = 1f
				binding.menuSheetContainer.translationY = 0f
				binding.menuContentWrapper.translationY = 0f
				resetMenuButtonTimer()
			}
			.start()
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun setupLiquidGlassMotion() {
		var startRawY = 0f
		var velocityTracker: VelocityTracker? = null

		binding.menuDragArea.setOnTouchListener { _, event ->
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					startRawY = event.rawY
					velocityTracker?.recycle()
					velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
					true
				}
				MotionEvent.ACTION_MOVE -> {
					velocityTracker?.addMovement(event)
					val dy = event.rawY - startRawY
					// Rubber-band resistance when pulled up past top limit
					val clampedY = if (dy < 0f) dy * 0.18f else dy
					binding.menuSheetContainer.translationY = clampedY
					// Child follow-through inertia
					binding.menuContentWrapper.translationY = clampedY * 0.12f
					true
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					velocityTracker?.addMovement(event)
					velocityTracker?.computeCurrentVelocity(1000)
					val velY = velocityTracker?.yVelocity ?: 0f
					velocityTracker?.recycle()
					velocityTracker = null

					val dy = binding.menuSheetContainer.translationY
					if (dy > dpToPx(110) || velY > 1200f) {
						closeSettingsSheet()
					} else {
						SpringAnimation(binding.menuSheetContainer, DynamicAnimation.TRANSLATION_Y, 0f).apply {
							spring = SpringForce(0f).apply {
								stiffness = 420f
								dampingRatio = 0.72f
							}
							start()
						}
						SpringAnimation(binding.menuContentWrapper, DynamicAnimation.TRANSLATION_Y, 0f).apply {
							spring = SpringForce(0f).apply {
								stiffness = 350f
								dampingRatio = 0.52f
							}
							start()
						}
					}
					true
				}
				else -> false
			}
		}

		attachLiquidGlassPressPhysics(binding.menuSheetContainer)
		attachLiquidGlassPressPhysics(binding.topQuickDrawerPanel)
		attachLiquidGlassPressPhysics(binding.btnMenuToggle)
		attachLiquidGlassPressPhysics(binding.btnScanNoteFloat)
		attachLiquidGlassPressPhysics(binding.btnTextOnlyFloat)
		attachLiquidGlassPressPhysics(binding.btnFloatNoteScan)
		attachLiquidGlassPressPhysics(binding.btnFloatNoteHide)
		attachLiquidGlassPressPhysics(binding.btnSpeakNote)
		attachLiquidGlassPressPhysics(binding.btnCloseNote)
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun attachLiquidGlassPressPhysics(view: View) {
		if (view is com.google.android.material.button.MaterialButton || view is android.widget.ImageButton) {
			view.setOnTouchListener { v, event ->
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(75).start()
					}
					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						SpringAnimation(v, DynamicAnimation.SCALE_X, 1f).apply {
							spring = SpringForce(1f).apply {
								stiffness = 520f
								dampingRatio = 0.55f
							}
							start()
						}
						SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f).apply {
							spring = SpringForce(1f).apply {
								stiffness = 520f
								dampingRatio = 0.55f
							}
							start()
						}
					}
				}
				false // Keep normal click & toggle listeners working
			}
		} else if (view is ViewGroup) {
			for (i in 0 until view.childCount) {
				attachLiquidGlassPressPhysics(view.getChildAt(i))
			}
		}
	}

	private fun levenshteinDistance(s1: String, s2: String): Int {
		val a = s1.lowercase()
		val b = s2.lowercase()
		val costs = IntArray(b.length + 1) { it }
		for (i in 1..a.length) {
			costs[0] = i
			var nw = i - 1
			for (j in 1..b.length) {
				val cj = minOf(1 + minOf(costs[j], costs[j - 1]), if (a[i - 1] == b[j - 1]) nw else nw + 1)
				nw = costs[j]
				costs[j] = cj
			}
		}
		return costs[b.length]
	}

	private fun isSimilarText(a: String, b: String): Boolean {
		if (a.isBlank() || b.isBlank()) return false
		if (a.equals(b, ignoreCase = true)) return true
		val cleanA = a.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
		val cleanB = b.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
		if (cleanA.isEmpty() || cleanB.isEmpty()) return false
		if (cleanA == cleanB) return true
		val maxLen = maxOf(cleanA.length, cleanB.length)
		val dist = levenshteinDistance(cleanA, cleanB)
		return (1.0 - dist.toDouble() / maxLen) >= 0.72
	}

	private fun isSimilarThai(a: String, b: String): Boolean {
		if (a.isBlank() || b.isBlank()) return false
		val cleanA = a.replace(Regex("[\\s.,!?\"'()\\-…]"), "")
		val cleanB = b.replace(Regex("[\\s.,!?\"'()\\-…]"), "")
		if (cleanA.isEmpty() || cleanB.isEmpty()) return false
		if (cleanA == cleanB) return true
		val maxLen = maxOf(cleanA.length, cleanB.length)
		val dist = levenshteinDistance(cleanA, cleanB)
		return (1.0 - dist.toDouble() / maxLen) >= 0.80
	}

	private fun isDialogueSubtitle(text: String): Boolean {
		val letterCount = text.count { it.isLetter() }
		if (letterCount == 0) return false
		if (!isFilterShortTextEnabled) return text.length >= 3

		val rawWords = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
		val cleanWords = rawWords.map { it.lowercase().replace(Regex("[^a-z0-9-]"), "") }.filter { it.isNotEmpty() }
		// Ignore isolated single-letter button icon OCR artifacts like "A O DRINKS" / "△ ○ DRINKS" / "X OPEN"
		val meaningfulWords = cleanWords.filter { it.length >= 2 }

		if (meaningfulWords.isEmpty()) return false
		val hasSentencePunctuation = text.any { it == '.' || it == '?' || it == '!' || it == ',' }

		// Reject single-word HUD prompts (even with leading icon symbols like "A O DRINKS", "RADIO", "RAIN")
		if (meaningfulWords.size == 1 && (meaningfulWords[0].length < 10 || !hasSentencePunctuation)) {
			return false
		}

		// Reject ALL-CAPS short/medium HUD interaction prompts (e.g. "DRINKS", "PUT DOWN", "DRINK (RI) PUT DOWN WATER")
		val upperCount = text.count { it.isUpperCase() }
		val upperRatio = upperCount.toFloat() / letterCount.coerceAtLeast(1)
		if (upperRatio >= 0.85f && meaningfulWords.size <= 5) {
			return false
		}

		// Reject HUD button tags & counters like "(R1)", "(RI)", "(2/2)" in short strings
		if (cleanWords.size <= 6 && (
			Regex("\\b\\d+\\s*/\\s*\\d+\\b").containsMatchIn(text) ||
			Regex("[(\\[{]\\s*(?:r1|r2|r3|ri|rl|l1|l2|l3|li|ll|x|o|0|a)\\s*[)\\]}]", RegexOption.IGNORE_CASE).containsMatchIn(text)
		)) {
			return false
		}

		val promptWords = setOf(
			"talk", "skip", "attack", "dodge", "interact", "examine", "open", "back", "next",
			"continue", "menu", "cancel", "r1", "r2", "l1", "l2", "r3", "l3", "ri", "li",
			"drink", "drinks", "radio", "water", "put", "down", "hold", "press", "use", "pick"
		)
		if (meaningfulWords.size <= 2 && meaningfulWords.all { it in promptWords }) return false
		if (cleanWords.size <= 3 && cleanWords.any { it in setOf("r1", "r2", "l1", "l2", "r3", "l3", "ri", "li") }) return false

		return true
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}

	private suspend fun translateToThai(englishText: String, isPopup: Boolean = false): String = withContext(Dispatchers.IO) {
		val provider = activeTranslationProvider
		val apiKey = getProviderApiKey(provider)
		val isUsingAi = provider != TranslationProvider.GOOGLE_FREE && apiKey.isNotEmpty()
		val useCache = isLocalCacheEnabled && !englishText.contains("|||")
		val cacheKey = if (isUsingAi) {
			"ai_${if (isPopup) "pop" else "sub"}"
		} else {
			"free_${if (isPopup) "pop" else "sub"}"
		}

		if (useCache && ::translationCacheDb.isInitialized) {
			val cached = translationCacheDb.get(cacheKey, englishText)
			if (!cached.isNullOrBlank()) {
				return@withContext cached
			}
		}

		if (isUsingAi) {
			try {
				val aiResult = when (provider) {
					TranslationProvider.GEMINI -> translateWithGemini(englishText, apiKey, getProviderModel(provider), isPopup)
					TranslationProvider.GROQ,
					TranslationProvider.CEREBRAS,
					TranslationProvider.OPENAI,
					TranslationProvider.OPENROUTER,
					TranslationProvider.CUSTOM -> {
						val endpoint = if (provider == TranslationProvider.CUSTOM) getCustomEndpointUrl() else provider.defaultEndpoint
						translateWithOpenAiCompatible(
							englishText = englishText,
							endpoint = endpoint,
							apiKey = apiKey,
							model = getProviderModel(provider),
							provider = provider,
							isPopup = isPopup
						)
					}
					TranslationProvider.GOOGLE_FREE -> null
				}
				if (!aiResult.isNullOrBlank()) {
					if (useCache && ::translationCacheDb.isInitialized) {
						translationCacheDb.put(cacheKey, englishText, aiResult)
					}
					return@withContext aiResult
				}
			} catch (e: Exception) {
				android.util.Log.w("PSTranslatorAPI", "${provider.displayName} fallback to Google Free: ${e.message}")
			}
		}

		val freeResult = translateGoogleFree(englishText)
		if (!isUsingAi && useCache && ::translationCacheDb.isInitialized && freeResult != englishText) {
			translationCacheDb.put(cacheKey, englishText, freeResult)
		}
		freeResult
	}

	private fun buildTranslationPrompt(englishText: String, isPopup: Boolean): String {
		return if (isPopup) {
			"Translate this in-game tutorial box or phone text message from English into complete, accurate, natural modern Thai (casual American texting/slang tone when it's a chat message). Do NOT summarize, shorten, or omit any words or sentences. If it begins with 'Name: message', keep the format 'ชื่อ: ข้อความ'. Return ONLY the Thai translation:\n$englishText"
		} else {
			"Translate this American video game subtitle (GTA style) from English into raw, realistic, modern spoken Thai. Use authentic modern Thai slang, street pronouns (กู/มึง/แก/เพื่อน/พวก), uncensored profanity (แม่ง/เชี่ย/เหี้ย/ฉิบหาย/โคตร), and natural exclamations (เฮ้ย/โห/จริงดิ/เก็ตป่ะ/ว่ะ) matching the speaker's American attitude—never stiff textbook Thai or polite ครับ/ค่ะ unless explicitly formal. Return ONLY the Thai translation:\n$englishText"
		}
	}

	private fun cleanAiTranslationOutput(raw: String): String? {
		val cleaned = raw
			.replace(Regex("(?s)<think>.*?</think>"), "")
			.replace(Regex("^(?:คำแปล|แปลว่า|แปล|Thai|TH)\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
			.trim()
			.removeSurrounding("\"")
			.removeSurrounding("'")
			.trim()
		return cleaned.ifEmpty { null }
	}

	private fun translateWithGemini(englishText: String, apiKey: String, model: String, isPopup: Boolean): String? {
		val prompt = buildTranslationPrompt(englishText, isPopup)
		val lowerModel = model.lowercase()

		// Primary fast path: Gemini Interactions API (store=false + minimal/off thinking for lowest latency)
		try {
			val conn = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as java.net.HttpURLConnection).apply {
				requestMethod = "POST"
				setRequestProperty("x-goog-api-key", apiKey)
				setRequestProperty("Content-Type", "application/json")
				setRequestProperty("Api-Revision", "2026-05-20")
				connectTimeout = 4000
				readTimeout = 5000
				doOutput = true
			}

			val genConfig = org.json.JSONObject().apply {
				put("max_output_tokens", 240)
				when {
					lowerModel.contains("2.5-flash-lite") -> {
						// Thinking is Off by default on gemini-2.5-flash-lite
					}
					lowerModel.contains("3.5-flash-lite") || lowerModel.contains("3.6-flash") || lowerModel.contains("3-flash") -> {
						put("thinking_level", "minimal")
					}
					lowerModel.contains("3.8-flash") || lowerModel.contains("2.5-flash") || lowerModel.contains("4-argon") -> {
						put("thinking_level", "low")
					}
				}
			}

			val body = org.json.JSONObject().apply {
				put("model", model)
				put("store", false)
				put("input", prompt)
				put("generation_config", genConfig)
			}

			conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
			if (conn.responseCode in 200..299) {
				val resText = conn.inputStream.bufferedReader().use { it.readText() }
				conn.disconnect()
				val json = org.json.JSONObject(resText)

				val sb = StringBuilder()
				val steps = json.optJSONArray("steps")
				if (steps != null) {
					for (i in 0 until steps.length()) {
						val step = steps.getJSONObject(i)
						if (step.optString("type") == "model_output") {
							val content = step.optJSONArray("content") ?: continue
							for (j in 0 until content.length()) {
								val item = content.getJSONObject(j)
								if (item.optString("type") == "text") {
									sb.append(item.optString("text", ""))
								}
							}
						}
					}
				}
				if (sb.isEmpty()) {
					val outputs = json.optJSONArray("outputs")
					if (outputs != null) {
						for (i in 0 until outputs.length()) {
							val out = outputs.getJSONObject(i)
							if (out.optString("type") == "text") {
								sb.append(out.optString("text", ""))
							}
						}
					}
				}
				val parsed = cleanAiTranslationOutput(sb.toString())
				if (parsed != null) return parsed
			} else {
				conn.disconnect()
			}
		} catch (e: Exception) {
			android.util.Log.w("PSTranslatorAPI", "Gemini Interactions fallback to generateContent: ${e.message}")
		}

		// Secondary fallback path: classic generateContent endpoint (try selected model, then gemini-2.5-flash-lite with thinkingBudget=0)
		val modelsToTry = if (lowerModel == "gemini-2.5-flash-lite") {
			listOf(model)
		} else {
			listOf(model, "gemini-2.5-flash-lite")
		}

		for (candidateModel in modelsToTry) {
			try {
				val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" +
					URLEncoder.encode(candidateModel, "UTF-8") +
					":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")
				val conn = (URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
					requestMethod = "POST"
					setRequestProperty("Content-Type", "application/json")
					connectTimeout = 4000
					readTimeout = 5000
					doOutput = true
				}

				val safetyCategories = listOf(
					"HARM_CATEGORY_HARASSMENT",
					"HARM_CATEGORY_HATE_SPEECH",
					"HARM_CATEGORY_SEXUALLY_EXPLICIT",
					"HARM_CATEGORY_DANGEROUS_CONTENT"
				)
				val safetyArray = JSONArray().apply {
					for (cat in safetyCategories) {
						put(org.json.JSONObject().apply {
							put("category", cat)
							put("threshold", "BLOCK_NONE")
						})
					}
				}
				val genConfig = org.json.JSONObject().apply {
					put("temperature", 0.35)
					put("maxOutputTokens", 240)
					if (candidateModel.lowercase().contains("2.5-flash")) {
						put("thinkingConfig", org.json.JSONObject().put("thinkingBudget", 0))
					}
				}
				val body = org.json.JSONObject().apply {
					put("contents", JSONArray().put(
						org.json.JSONObject().put("parts", JSONArray().put(
							org.json.JSONObject().put("text", prompt)
						))
					))
					put("safetySettings", safetyArray)
					put("generationConfig", genConfig)
				}

				conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
				if (conn.responseCode !in 200..299) {
					conn.disconnect()
					continue
				}
				val resText = conn.inputStream.bufferedReader().use { it.readText() }
				conn.disconnect()

				val json = org.json.JSONObject(resText)
				val candidates = json.optJSONArray("candidates") ?: continue
				if (candidates.length() == 0) continue
				val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: continue
				if (parts.length() == 0) continue
				val parsed = cleanAiTranslationOutput(parts.getJSONObject(0).optString("text", ""))
				if (parsed != null) return parsed
			} catch (_: Exception) {
				continue
			}
		}
		return null
	}

	private fun translateWithOpenAiCompatible(
		englishText: String,
		endpoint: String,
		apiKey: String,
		model: String,
		provider: TranslationProvider,
		isPopup: Boolean
	): String? {
		val systemPrompt = if (isPopup) {
			"You are a fast video game translator. Translate the given English HUD popup, tutorial tip, or in-game phone message into complete, natural modern Thai (casual American texting/slang tone for messages, without summarizing). Return ONLY the Thai text."
		} else {
			"You are a fast American video game subtitle translator (GTA style). Translate the given English subtitle into raw, realistic, modern spoken Thai using authentic Thai slang, street pronouns, uncensored profanity, and natural modern exclamations matching the speaker's attitude—never stiff textbook Thai. Return ONLY the Thai text."
		}

		val conn = (URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
			requestMethod = "POST"
			setRequestProperty("Authorization", "Bearer $apiKey")
			setRequestProperty("Content-Type", "application/json")
			if (provider == TranslationProvider.OPENROUTER) {
				setRequestProperty("HTTP-Referer", "https://github.com/teeradream/ps-translator")
				setRequestProperty("X-Title", "PS Translator")
			}
			connectTimeout = 3500
			readTimeout = 4500
			doOutput = true
		}

		val messages = JSONArray().apply {
			put(org.json.JSONObject().apply {
				put("role", "system")
				put("content", systemPrompt)
			})
			put(org.json.JSONObject().apply {
				put("role", "user")
				put("content", englishText)
			})
		}

		val body = org.json.JSONObject().apply {
			put("model", model)
			put("temperature", 0.3)
			put("max_tokens", 200)
			put("messages", messages)
		}

		conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
		if (conn.responseCode !in 200..299) {
			conn.disconnect()
			return null
		}
		val resText = conn.inputStream.bufferedReader().use { it.readText() }
		conn.disconnect()

		val json = org.json.JSONObject(resText)
		val choices = json.optJSONArray("choices") ?: return null
		if (choices.length() == 0) return null
		val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
		return cleanAiTranslationOutput(message.optString("content", ""))
	}

	private fun isFemaleCharacter(englishText: String, thaiText: String): Boolean {
		val engLower = englishText.lowercase()
		val thaiLower = thaiText.lowercase()

		var femaleScore = 0
		var maleScore = 0

		val femaleWords = listOf(
			"she", "her", "hers", "lady", "milady", "girl", "woman", "women",
			"sister", "mother", "queen", "princess", "madam", "miss", "mrs",
			"daughter", "wife", "aunt", "witch", "goddess", "priestess", "empress"
		)
		for (w in femaleWords) {
			if (Regex("\\b$w\\b").containsMatchIn(engLower)) femaleScore += 2
		}
		if (thaiLower.contains("ค่ะ") || thaiLower.contains("คะ") || thaiLower.contains("ดิฉัน") || thaiLower.contains("เจ้าแม่") || thaiLower.contains("นาง")) {
			femaleScore += 3
		}

		val maleWords = listOf(
			"he", "him", "his", "man", "men", "boy", "sir", "lord", "milord",
			"brother", "father", "king", "prince", "master", "mr", "uncle",
			"son", "monk", "daoist", "fellow", "lad", "young friend", "old man",
			"warrior", "emperor"
		)
		for (w in maleWords) {
			if (Regex("\\b$w\\b").containsMatchIn(engLower)) maleScore += 2
		}
		if (thaiLower.contains("ครับ") || thaiLower.contains("ผม") || thaiLower.contains("กระผม") || thaiLower.contains("เจ้าหนุ่ม") || thaiLower.contains("ศิษย์น้อง")) {
			maleScore += 3
		}

		return femaleScore > maleScore
	}

	private fun fixOcrText(text: String): String {
		var result = text
		val replacements = mapOf(
			"eould" to "could",
			"eouldn't" to "couldn't",
			"ean" to "can",
			"eannot" to "cannot",
			"ean't" to "can't",
			"eome" to "come",
			"eomes" to "comes",
			"eoming" to "coming",
			"eame" to "came",
			"eall" to "call",
			"ealled" to "called",
			"ealling" to "calling",
			"ealls" to "calls",
			"eare" to "care",
			"eareful" to "careful",
			"earefully" to "carefully",
			"eatch" to "catch",
			"eause" to "cause",
			"eauses" to "causes",
			"eaused" to "caused",
			"eertain" to "certain",
			"eertainly" to "certainly",
			"ehance" to "chance",
			"ehoose" to "choose",
			"ehoice" to "choice",
			"ehoices" to "choices",
			"eity" to "city",
			"elear" to "clear",
			"elearly" to "clearly",
			"elose" to "close",
			"elosed" to "closed",
			"eold" to "cold",
			"eontrol" to "control",
			"eontinue" to "continue",
			"eorrect" to "correct",
			"eount" to "count",
			"eountry" to "country",
			"eourse" to "course",
			"eouple" to "couple",
			"eover" to "cover",
			"eraft" to "craft",
			"ereature" to "creature",
			"erime" to "crime",
			"ery" to "cry",
			"eurse" to "curse",
			"eut" to "cut",
			"eaptain" to "captain",
			"eenter" to "center",
			"ehild" to "child",
			"ehildren" to "children",
			"eircle" to "circle",
			"elimb" to "climb",
			"eloak" to "cloak",
			"elothes" to "clothes",
			"eloud" to "cloud",
			"eoat" to "coat",
			"eoin" to "coin",
			"eolor" to "color",
			"eommon" to "common",
			"eompany" to "company",
			"eomplete" to "complete",
			"eoncern" to "concern",
			"eondition" to "condition",
			"eonnect" to "connect",
			"eonquer" to "conquer",
			"eontain" to "contain",
			"eopy" to "copy",
			"eorner" to "corner",
			"eost" to "cost",
			"erazy" to "crazy",
			"eross" to "cross",
			"erowd" to "crowd",
			"erown" to "crown",
			"eruel" to "cruel",
			"eure" to "cure",
			"eurious" to "curious",
			"eurrent" to "current",
			"eustom" to "custom",
			"beoome" to "become",
			"beeome" to "become",
			"beoause" to "because",
			"beeause" to "because",
			"espeoially" to "especially",
			"plaee" to "place",
			"voiee" to "voice",
			"faee" to "face",
			"graee" to "grace",
			"spaee" to "space",
			"peaee" to "peace",
			"pieee" to "piece",
			"priee" to "price",
			"notiee" to "notice",
			"offiee" to "office",
			"serviee" to "service",
			"poliee" to "police",
			"foree" to "force",
			"choiee" to "choice",
			"destruetion" to "destruction",
			"proteet" to "protect",
			"direet" to "direct",
			"perfeet" to "perfect",
			"seeret" to "secret",
			"seene" to "scene",
			"aecept" to "accept",
			"effeet" to "effect",
			"respeet" to "respect",
			"expeet" to "expect",
			"inspeet" to "inspect"
		)

		for ((typo, fix) in replacements) {
			val regex = Regex("(?i)\\b$typo\\b")
			result = regex.replace(result) { matchResult ->
				val original = matchResult.value
				if (original.isNotEmpty() && original[0].isUpperCase()) {
					fix.replaceFirstChar { it.uppercase() }
				} else {
					fix
				}
			}
		}
		return result
	}

	private fun speakTranslation(englishText: String, thaiText: String) {
		val now = System.currentTimeMillis()
		if (isSimilarThai(thaiText, lastSpokenThai) && (now - lastSpokenTimeMs) < 8000) {
			return
		}
		lastSpokenThai = thaiText
		lastSpokenTimeMs = now

		val isFemale = when (voiceGenderMode) {
			VoiceGenderMode.AUTO_AI -> isFemaleCharacter(englishText, thaiText)
			VoiceGenderMode.FEMALE -> true
			VoiceGenderMode.MALE -> false
		}

		val spokenThai = if (isSkipSpeakerNameInTts) {
			stripSpeakerPrefix(thaiText)
		} else {
			thaiText
		}

		if (geminiApiKey.isNotEmpty() && isGeminiTtsEnabled) {
			ocrScope.launch {
				val wavFile = withContext(Dispatchers.IO) {
					synthesizeGeminiTts(spokenThai, isFemale, now)
				}
				if (wavFile != null && isTtsEnabled && !isDestroyed && !isFinishing) {
					enqueueGeminiAudio(wavFile)
				} else if (isTtsEnabled && !isDestroyed && !isFinishing) {
					speakWithLocalTts(spokenThai, isFemale, now)
				}
			}
			return
		}

		speakWithLocalTts(spokenThai, isFemale, now)
	}

	private fun stripSpeakerPrefix(text: String): String {
		val stripped = text
			.replace(Regex("^\\[[^\\]]{1,28}\\]\\s*"), "")
			.replace(Regex("^\\([^)]{1,28}\\)\\s*"), "")
			.replace(Regex("^[^:\\uFF1A\\n]{1,28}[:\\uFF1A]\\s*"), "")
			.replace(Regex("^[A-Z][A-Za-z0-9\\s'.]{0,22}\\s-\\s+"), "")
			.trim()
		return if (stripped.isNotEmpty()) stripped else text
	}

	private fun synthesizeGeminiTts(thaiText: String, isFemale: Boolean, timestamp: Long): java.io.File? {
		return synthesizeGeminiTts(thaiText, if (isFemale) "Kore" else "Charon", timestamp)
	}

	private fun synthesizeGeminiTts(thaiText: String, voiceName: String, timestamp: Long): java.io.File? {
		return try {
			val conn = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as java.net.HttpURLConnection).apply {
				requestMethod = "POST"
				setRequestProperty("x-goog-api-key", geminiApiKey)
				setRequestProperty("Content-Type", "application/json")
				connectTimeout = 6000
				readTimeout = 10000
				doOutput = true
			}

			val body = org.json.JSONObject().apply {
				put("model", "gemini-3.8-flash-lite-tts")
				put("input", JSONArray().put(
					org.json.JSONObject().apply {
						put("type", "user_input")
						put("content", JSONArray().put(
							org.json.JSONObject().apply {
								put("type", "text")
								put("text", thaiText)
							}
						))
					}
				))
				put("response_format", org.json.JSONObject().put("type", "audio"))
				put("generation_config", org.json.JSONObject().put(
					"speech_config", JSONArray().put(
						org.json.JSONObject().put("voice", voiceName)
					)
				))
			}

			conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
			if (conn.responseCode !in 200..299) {
				conn.disconnect()
				return null
			}
			val resText = conn.inputStream.bufferedReader().use { it.readText() }
			conn.disconnect()

			val json = org.json.JSONObject(resText)
			val steps = json.optJSONArray("steps") ?: return null
			var base64Audio: String? = null
			for (i in 0 until steps.length()) {
				val step = steps.getJSONObject(i)
				if (step.optString("type") == "model_output") {
					val content = step.optJSONArray("content") ?: continue
					for (j in 0 until content.length()) {
						val item = content.getJSONObject(j)
						if (item.optString("type") == "audio") {
							base64Audio = item.optString("data", "")
						}
					}
				}
			}
			if (base64Audio.isNullOrEmpty()) return null

			val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
			val outFile = java.io.File(cacheDir, "gemini_tts_${timestamp}.wav")
			outFile.writeBytes(audioBytes)
			outFile
		} catch (e: Exception) {
			android.util.Log.w("PSTranslatorGemini", "Gemini TTS fallback: ${e.message}")
			null
		}
	}

	private fun enqueueGeminiAudio(wavFile: java.io.File) {
		when (ttsQueueMode) {
			TtsQueueMode.QUEUE_ALL -> {
				geminiAudioQueue.addLast(wavFile)
				if (geminiMediaPlayer == null) {
					playNextGeminiAudio()
				}
			}
			TtsQueueMode.FLUSH_IMMEDIATE -> {
				stopGeminiAudioPlayback()
				geminiAudioQueue.addLast(wavFile)
				playNextGeminiAudio()
			}
			TtsQueueMode.SMART_QUEUE -> {
				if (geminiMediaPlayer == null && geminiAudioQueue.isEmpty()) {
					geminiAudioQueue.addLast(wavFile)
					playNextGeminiAudio()
				} else if (geminiAudioQueue.size <= 1) {
					geminiAudioQueue.addLast(wavFile)
				} else {
					stopGeminiAudioPlayback()
					geminiAudioQueue.addLast(wavFile)
					playNextGeminiAudio()
				}
			}
		}
	}

	private fun playNextGeminiAudio() {
		if (!isTtsEnabled || isDestroyed || isFinishing) {
			stopGeminiAudioPlayback()
			return
		}
		val nextFile = geminiAudioQueue.pollFirst()
		if (nextFile == null) {
			tryFlushPendingSmartPopupSpeech()
			return
		}
		try {
			val mp = MediaPlayer()
			mp.setDataSource(nextFile.absolutePath)
			mp.prepare()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				try {
					mp.playbackParams = PlaybackParams().setSpeed(speechSpeedRate)
				} catch (_: Exception) {}
			}
			mp.setOnCompletionListener {
				it.release()
				if (geminiMediaPlayer == it) geminiMediaPlayer = null
				nextFile.delete()
				playNextGeminiAudio()
			}
			mp.setOnErrorListener { player, _, _ ->
				player.release()
				if (geminiMediaPlayer == player) geminiMediaPlayer = null
				nextFile.delete()
				playNextGeminiAudio()
				true
			}
			geminiMediaPlayer = mp
			mp.start()
		} catch (_: Exception) {
			nextFile.delete()
			geminiMediaPlayer = null
			playNextGeminiAudio()
		}
	}

	private fun stopGeminiAudioPlayback() {
		try {
			geminiMediaPlayer?.stop()
		} catch (_: Exception) {}
		try {
			geminiMediaPlayer?.release()
		} catch (_: Exception) {}
		geminiMediaPlayer = null
		while (geminiAudioQueue.isNotEmpty()) {
			geminiAudioQueue.pollFirst()?.delete()
		}
	}

	private fun speakWithLocalTts(thaiText: String, isFemale: Boolean, now: Long) {
		val ttsEngine = tts ?: return

		try {
			val voices = ttsEngine.voices
			if (voices != null) {
				val thVoices = voices.filter { it.locale.language.equals("th", ignoreCase = true) }
				if (thVoices.isNotEmpty()) {
					val targetVoice = if (isFemale) {
						thVoices.firstOrNull { v ->
							val name = v.name.lowercase()
							(name.contains("female") || name.contains("woman") || name.contains("thb") || name.contains("thc") || name.contains("cfn") || name.contains("-b-") || name.contains("-c-"))
						} ?: if (thVoices.size > 1) thVoices[1] else thVoices.first()
					} else {
						thVoices.firstOrNull { v ->
							val name = v.name.lowercase()
							(!name.contains("female") && (name.contains("male") || name.contains("man"))) || name.contains("tha") || name.contains("thd") || name.contains("cfa") || name.contains("-a-") || name.contains("-d-")
						} ?: thVoices.first()
					}
					if (targetVoice != null) {
						ttsEngine.voice = targetVoice
					}
				}
			}
		} catch (_: Exception) {}

		if (isFemale) {
			ttsEngine.setPitch(1.15f)
		} else {
			ttsEngine.setPitch(0.75f)
		}
		ttsEngine.setSpeechRate(speechSpeedRate)

		val utteranceId = "sub_voice_${now}"
		val queueMode = when (ttsQueueMode) {
			TtsQueueMode.QUEUE_ALL -> TextToSpeech.QUEUE_ADD
			TtsQueueMode.FLUSH_IMMEDIATE -> {
				activeUtterances.clear()
				TextToSpeech.QUEUE_FLUSH
			}
			TtsQueueMode.SMART_QUEUE -> {
				if (!ttsEngine.isSpeaking || activeUtterances.size <= 1) {
					TextToSpeech.QUEUE_ADD
				} else {
					activeUtterances.clear()
					TextToSpeech.QUEUE_FLUSH
				}
			}
		}
		activeUtterances.add(utteranceId)
		ttsEngine.speak(thaiText, queueMode, null, utteranceId)
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.session.resume()
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		// Persist a fresh snapshot of all settings + PS5 hosts whenever leaving the stream
		com.metallic.chiaki.common.AppStateBackupManager.autoBackupAsync(applicationContext)
		if (!isInMultiWindowMode && !isChangingConfigurations) {
			viewModel.session.pause()
		}
	}

	override fun onStop()
	{
		super.onStop()
		if (!isChangingConfigurations) {
			viewModel.session.pause()
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration)
	{
		super.onConfigurationChanged(newConfig)
		adjustStreamViewAspect()
		if (::binding.isInitialized && binding.cropEditorOverlay.isVisible) {
			openCropEditor()
		}
	}

	override fun onDestroy()
	{
		super.onDestroy()
		uiVisibilityHandler.removeCallbacks(captureRunnable)
		uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
		uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
		uiVisibilityHandler.removeCallbacks(hideTopDrawerRunnable)
		uiVisibilityHandler.removeCallbacks(hidePopupLeftRunnable)
		uiVisibilityHandler.removeCallbacks(hidePopupRightRunnable)
		uiVisibilityHandler.removeCallbacks(autoHideSubtitleRunnable)
		tvOverlayServer?.stop()
		tvOverlayServer = null
		activeUtterances.clear()
		pendingSmartPopupSpeech = null
		stopGeminiAudioPlayback()
		stopPopupGeminiAudioPlayback()
		try { popupChimeToneGenerator?.release() } catch (_: Exception) {}
		popupChimeToneGenerator = null
		ocrScope.cancel()
		ocrHandlerThread.quitSafely()
		tts?.stop()
		tts?.shutdown()
		popupTts?.stop()
		popupTts?.shutdown()
		if (::translationCacheDb.isInitialized) {
			try { translationCacheDb.close() } catch (_: Exception) {}
		}
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			resetMenuButtonTimer()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus) {
			hideSystemUI()
		}
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateConnected ->
			{
				binding.subtitleOverlayContainer.isVisible = false
			}

			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}

			else -> {}
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	// Controllers disabled: no joypad inputs sent back to console
	override fun dispatchKeyEvent(event: KeyEvent) = super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}
