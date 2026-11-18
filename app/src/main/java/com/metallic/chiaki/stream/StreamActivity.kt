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
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
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
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())
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

	data class SubtitleHistoryItem(
		val english: String,
		val thai: String,
		val timestamp: Long = System.currentTimeMillis()
	)

	private var tts: TextToSpeech? = null
	private var isTtsEnabled = false
	private var voiceGenderMode = VoiceGenderMode.AUTO_AI
	private var ttsQueueMode = TtsQueueMode.SMART_QUEUE
	private val activeUtterances = java.util.Collections.synchronizedSet(mutableSetOf<String>())
	private var isSubtitleEnabled = true
	private var isAudioMuted = false
	private var isTextOnlyMode = false
	private var isCapturing = false
	private var isTranslating = false
	private var pendingOcrEnglish = ""
	private var lastTranslatedEnglish = ""
	private var lastTranslatedThai = ""
	private var lastSpokenThai = ""
	private var lastSpokenTimeMs = 0L
	private lateinit var cropPrefs: SharedPreferences
	private var isFilterShortTextEnabled = true
	private var isSkipSpeakerNameInTts = true
	private var speechSpeedRate = 1.25f

	// Gemini API settings (for natural translation & AI voice dubbing)
	private var geminiApiKey = ""
	private var isGeminiTranslateEnabled = true
	private var isGeminiTtsEnabled = true
	private var geminiMediaPlayer: MediaPlayer? = null
	private val geminiAudioQueue = java.util.ArrayDeque<java.io.File>()

	data class NoteBlockItem(
		val english: String,
		val rect: Rect,
		var thai: String = ""
	)

	// On-demand Note/Tutorial Crop Box & Top Quick Drawer
	private var isEditingNoteCrop = false
	private var isNoteCropBoxVisible = false
	private var isScanningNote = false
	private var lastNoteThai = ""
	private var lastNoteEnglish = ""

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
	private var currentSubPosition = "CENTER"
	private var currentSubBgOpacity = 80
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
			uiVisibilityHandler.postDelayed(this, 600)
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

		cropPrefs = getSharedPreferences("ps_translator_crop", Context.MODE_PRIVATE)
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
		currentSubPosition = cropPrefs.getString("sub_position", "CENTER") ?: "CENTER"
		currentSubBgOpacity = cropPrefs.getInt("sub_bg_opacity", 80).coerceIn(0, 100)
		maxHistoryLines = cropPrefs.getInt("sub_history_limit", 3).coerceIn(1, 3)

		setupSubtitleCustomizationUI()
		applySubtitleStyles()

		// TTS voice switch (persisted)
		isTtsEnabled = cropPrefs.getBoolean("tts_voice_enabled", false)
		isSkipSpeakerNameInTts = cropPrefs.getBoolean("skip_speaker_name_tts", true)
		binding.ttsVoiceSwitch.isChecked = isTtsEnabled
		binding.skipSpeakerNameInTtsSwitch.isChecked = isSkipSpeakerNameInTts
		binding.skipSpeakerNameInTtsSwitch.isVisible = isTtsEnabled
		binding.voiceGenderContainer.isVisible = isTtsEnabled
		binding.speechSpeedContainer.isVisible = isTtsEnabled
		binding.ttsQueueModeContainer.isVisible = isTtsEnabled
		binding.ttsVoiceSwitch.setOnCheckedChangeListener { _, isChecked ->
			isTtsEnabled = isChecked
			cropPrefs.edit().putBoolean("tts_voice_enabled", isChecked).apply()
			binding.skipSpeakerNameInTtsSwitch.isVisible = isChecked
			binding.voiceGenderContainer.isVisible = isChecked
			binding.speechSpeedContainer.isVisible = isChecked
			binding.ttsQueueModeContainer.isVisible = isChecked
			if (!isChecked) {
				activeUtterances.clear()
				tts?.stop()
				stopGeminiAudioPlayback()
			}
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

		// Mute / Unmute game audio directly in native stream (keeps TTS translation voice loud and clear!)
		binding.btnMuteAudio.setOnClickListener {
			isAudioMuted = !isAudioMuted
			viewModel.session.isAudioMuted = isAudioMuted
			if (isAudioMuted) {
				binding.btnMuteAudio.text = "เปิดเสียงเกมมือถือ"
				binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_off)
			} else {
				binding.btnMuteAudio.text = "ปิดเสียงเกมจากมือถือ"
				binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_up)
			}
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

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
		}

		// Display streaming directly via SurfaceView
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		// Start periodic OCR capture loop
		setupTopQuickDrawer()
		setupNoteTranslationFeature()
		updateQuickDrawerUI()
		uiVisibilityHandler.postDelayed(captureRunnable, 1500)
		resetMenuButtonTimer()
	}

	private fun resetMenuButtonTimer() {
		binding.btnMenuToggle.alpha = 1.0f
		uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
		uiVisibilityHandler.postDelayed(hideMenuButtonRunnable, 3500)
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
			binding.floatingNoteCropBox.isVisible = isNoteCropBoxVisible
			if (isNoteCropBoxVisible) syncFloatingNoteCropBox()
			binding.btnTextOnlyMode.text = "โหมดโชว์เฉพาะตัวหนังสือ (จอดำ)"
			binding.btnTextOnlyFloat.setColorFilter(Color.parseColor("#FFFFFF"))
		}
	}

	override fun onInit(status: Int) {
		if (status == TextToSpeech.SUCCESS) {
			tts?.language = Locale("th", "TH")
			tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
				override fun onStart(utteranceId: String?) {}
				override fun onDone(utteranceId: String?) {
					if (utteranceId != null) activeUtterances.remove(utteranceId)
				}
				override fun onError(utteranceId: String?) {
					if (utteranceId != null) activeUtterances.remove(utteranceId)
				}
				override fun onStop(utteranceId: String?, interrupted: Boolean) {
					if (utteranceId != null) activeUtterances.remove(utteranceId)
				}
			})
		}
	}

	private fun captureAndProcessSubtitle() {
		val surfaceView = binding.surfaceView
		if (surfaceView.holder?.surface?.isValid != true) return
		if (surfaceView.width <= 100 || surfaceView.height <= 100) return
		if (isCapturing || isScanningNote) return

		val fullBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
		isCapturing = true

		try {
			PixelCopy.request(surfaceView, fullBitmap, { copyResult ->
				isCapturing = false
				if (copyResult == PixelCopy.SUCCESS) {
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
						val scaledSub = Bitmap.createScaledBitmap(subBitmap, cropW * 2, cropH * 2, true)
						val image = InputImage.fromBitmap(scaledSub, 0)
						textRecognizer.process(image)
							.addOnSuccessListener { visionText ->
								val rawText = visionText.text.trim().replace("\n", " ")
								val fixedText = fixOcrText(rawText)
								val text = fixedText.replace(Regex("\\s+"), " ").trim()
								if (text.isNotEmpty() && isDialogueSubtitle(text)) {
									val isDuplicateOfPending = isTranslating && isSimilarText(text, pendingOcrEnglish)
									val isDuplicateOfLast = isSimilarText(text, lastTranslatedEnglish)
									val isDuplicateOfRecent = subtitleHistory.take(2).any { isSimilarText(text, it.english) }

									if (!isTranslating && !isDuplicateOfPending && !isDuplicateOfLast && !isDuplicateOfRecent) {
										isTranslating = true
										pendingOcrEnglish = text
										android.util.Log.d("PSTranslatorOCR", "Detected dialogue: $text")
										ocrScope.launch {
											try {
												val thai = translateToThai(text)
												android.util.Log.d("PSTranslatorOCR", "Translated text: $thai")
												val isDuplicateThai = isSimilarThai(thai, lastTranslatedThai) ||
													subtitleHistory.take(2).any { isSimilarThai(thai, it.thai) }
												if (!isDuplicateThai) {
													lastTranslatedEnglish = text
													lastTranslatedThai = thai
													// Prepend newest subtitle at top
													subtitleHistory.add(0, SubtitleHistoryItem(text, thai))
													while (subtitleHistory.size > maxHistoryLines) {
														subtitleHistory.removeAt(subtitleHistory.size - 1)
													}
													renderSubtitles()
													if (isTtsEnabled) {
														speakTranslation(text, thai)
													}
												}
											} finally {
												isTranslating = false
											}
										}
									}
								}
							}
							.addOnFailureListener { e ->
								android.util.Log.e("PSTranslatorOCR", "OCR recognition error", e)
							}
					} catch (e: Exception) {
						android.util.Log.e("PSTranslatorOCR", "Bitmap processing error", e)
					}
				} else {
					android.util.Log.w("PSTranslatorOCR", "PixelCopy failed with status: $copyResult")
				}
			}, Handler(Looper.getMainLooper()))
		} catch (e: Exception) {
			isCapturing = false
			android.util.Log.e("PSTranslatorOCR", "PixelCopy request error", e)
		}
	}

	private fun setupSubtitleCustomizationUI() {
		// 1. Position toggle (Center vs Bottom)
		if (currentSubPosition == "BOTTOM") {
			binding.positionToggleGroup.check(R.id.btnPosBottom)
		} else {
			binding.positionToggleGroup.check(R.id.btnPosCenter)
		}
		binding.positionToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				currentSubPosition = if (checkedId == R.id.btnPosBottom) "BOTTOM" else "CENTER"
				cropPrefs.edit().putString("sub_position", currentSubPosition).apply()
				applySubtitleStyles()
			}
		}

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
		// 1. Background drawable
		val alphaInt = (currentSubBgOpacity * 255 / 100).coerceIn(0, 255)
		val bgDrawable = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = dpToPx(18).toFloat()
			setColor(Color.argb(alphaInt, 12, 18, 32))
			if (alphaInt > 0) {
				setStroke(dpToPx(1), Color.argb(85, 255, 255, 255))
			}
		}
		binding.subtitleOverlayContainer.background = bgDrawable

		// 2. Position (Center vs Bottom)
		val lp = binding.subtitleOverlayContainer.layoutParams as FrameLayout.LayoutParams
		if (currentSubPosition == "BOTTOM") {
			lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
			lp.bottomMargin = dpToPx(24)
		} else {
			lp.gravity = Gravity.CENTER
			lp.bottomMargin = 0
		}
		binding.subtitleOverlayContainer.layoutParams = lp

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

		// 4. Update Live Preview in Settings Menu
		val previewBg = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = dpToPx(10).toFloat()
			setColor(Color.argb(alphaInt, 6, 8, 16))
			if (alphaInt > 0) {
				setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
			}
		}
		binding.previewSubContainer.background = previewBg
		binding.previewSubNew.setTextColor(currentSubTextColor)
		binding.previewSubNew.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.8f).coerceIn(10f, 34f))
		binding.previewSubOld.setTextColor(currentSubTextColor)
		binding.previewSubOld.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.7f).coerceIn(9f, 28f))
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
			"API" -> "ตั้งค่า Gemini API"
			else -> "ตั้งค่า PS Translator"
		}
	}

	private fun setupGeminiApiControls() {
		geminiApiKey = cropPrefs.getString("gemini_api_key", "")?.trim() ?: ""
		isGeminiTranslateEnabled = cropPrefs.getBoolean("gemini_translate_enabled", true)
		isGeminiTtsEnabled = cropPrefs.getBoolean("gemini_tts_enabled", true)

		binding.etGeminiApiKey.setText(geminiApiKey)
		binding.useGeminiTranslateSwitch.isChecked = isGeminiTranslateEnabled
		binding.useGeminiTtsSwitch.isChecked = isGeminiTtsEnabled
		updateGeminiApiStatusUI()

		binding.btnSaveGeminiApiKey.setOnClickListener {
			val key = binding.etGeminiApiKey.text?.toString()?.trim() ?: ""
			geminiApiKey = key
			cropPrefs.edit().putString("gemini_api_key", key).apply()
			updateGeminiApiStatusUI()
			if (key.isNotEmpty()) {
				Toast.makeText(this, "บันทึก Gemini API Key เรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
			} else {
				Toast.makeText(this, "ยังไม่ได้กรอก API Key (ใช้ระบบฟรีเดิม)", Toast.LENGTH_SHORT).show()
			}
		}

		binding.btnClearGeminiApiKey.setOnClickListener {
			geminiApiKey = ""
			binding.etGeminiApiKey.setText("")
			cropPrefs.edit().remove("gemini_api_key").apply()
			updateGeminiApiStatusUI()
			Toast.makeText(this, "ล้าง API Key แล้ว (กลับไปใช้ระบบฟรีเดิม)", Toast.LENGTH_SHORT).show()
		}

		binding.useGeminiTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
			isGeminiTranslateEnabled = isChecked
			cropPrefs.edit().putBoolean("gemini_translate_enabled", isChecked).apply()
		}

		binding.useGeminiTtsSwitch.setOnCheckedChangeListener { _, isChecked ->
			isGeminiTtsEnabled = isChecked
			cropPrefs.edit().putBoolean("gemini_tts_enabled", isChecked).apply()
			if (!isChecked) {
				stopGeminiAudioPlayback()
			}
		}
	}

	private fun updateGeminiApiStatusUI() {
		if (geminiApiKey.isNotEmpty()) {
			binding.tvGeminiApiStatus.text = "สถานะ: เชื่อมต่อ Gemini API แล้ว (พร้อมใช้งาน)"
			binding.tvGeminiApiStatus.setTextColor(Color.parseColor("#00E676"))
		} else {
			binding.tvGeminiApiStatus.text = "สถานะ: ใช้ระบบแปลฟรี + TTS ในเครื่อง"
			binding.tvGeminiApiStatus.setTextColor(Color.parseColor("#AAAAAA"))
		}
	}

	private fun renderSubtitles() {
		if (subtitleHistory.isEmpty()) {
			binding.subtitleOverlayContainer.isVisible = false
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

		// Keep subtitle window permanently visible without auto-fading out
		if (isSubtitleEnabled && !isTextOnlyMode) {
			binding.subtitleOverlayContainer.isVisible = true
			binding.subtitleOverlayContainer.alpha = 1.0f
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun setupCropEditor() {
		binding.btnAdjustCropBox.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			isEditingNoteCrop = false
			openCropEditor()
		}

		binding.btnAdjustNoteCropBox.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			isEditingNoteCrop = true
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
						.coerceIn(dpToPx(120), (overlayW - frameX).coerceAtLeast(dpToPx(120)))
					val newH = (initialHeight + (event.rawY - initialTouchY).toInt())
						.coerceIn(dpToPx(40), (overlayH - frameY).coerceAtLeast(dpToPx(40)))

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

	private fun openCropEditor() {
		if (isEditingNoteCrop) {
			binding.cropEditorTitle.text = "ลากกรอบสีฟ้าไปวางตรงพื้นที่เอกสาร/คำแนะนำ"
			binding.cropBoxGuideText.text = "กรอบแปลเฉพาะจุด (ลากเพื่อย้าย)"
			binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E5FF"))
		} else {
			binding.cropEditorTitle.text = "ลากกรอบสีเขียวไปวางตรงพื้นที่ซับไตเติ้ลเกม"
			binding.cropBoxGuideText.text = "กรอบอ่านซับ (ลากเพื่อย้าย)"
			binding.cropBoxGuideText.setTextColor(Color.parseColor("#00E676"))
		}
		binding.cropEditorOverlay.isVisible = true
		binding.cropEditorOverlay.post {
			val overlayW = binding.cropEditorOverlay.width
			val overlayH = binding.cropEditorOverlay.height
			val prefix = if (isEditingNoteCrop) "note_crop_" else "crop_"
			val leftNorm = cropPrefs.getFloat("${prefix}left", if (isEditingNoteCrop) 0.20f else 0.18f)
			val topNorm = cropPrefs.getFloat("${prefix}top", if (isEditingNoteCrop) 0.15f else 0.72f)
			val widthNorm = cropPrefs.getFloat("${prefix}width", if (isEditingNoteCrop) 0.60f else 0.64f)
			val heightNorm = cropPrefs.getFloat("${prefix}height", if (isEditingNoteCrop) 0.65f else 0.22f)

			val w = (overlayW * widthNorm).toInt().coerceAtLeast(dpToPx(120))
			val h = (overlayH * heightNorm).toInt().coerceAtLeast(dpToPx(40))
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

		val defW = if (isEditingNoteCrop) 0.60f else 0.64f
		val defH = if (isEditingNoteCrop) 0.65f else 0.22f
		val defX = if (isEditingNoteCrop) 0.20f else 0.18f
		val defY = if (isEditingNoteCrop) 0.15f else 0.72f

		val w = (overlayW * defW).toInt().coerceAtLeast(dpToPx(120))
		val h = (overlayH * defH).toInt().coerceAtLeast(dpToPx(40))
		val x = overlayW * defX
		val y = overlayH * defY

		val params = binding.cropBoxFrame.layoutParams
		params.width = w
		params.height = h
		binding.cropBoxFrame.layoutParams = params
		binding.cropBoxFrame.x = x
		binding.cropBoxFrame.y = y
	}

	private fun saveCropBoxSettings() {
		val overlayW = binding.cropEditorOverlay.width.toFloat()
		val overlayH = binding.cropEditorOverlay.height.toFloat()

		val leftNorm = (binding.cropBoxFrame.x / overlayW).coerceIn(0f, 0.85f)
		val topNorm = (binding.cropBoxFrame.y / overlayH).coerceIn(0f, 0.90f)
		val widthNorm = (binding.cropBoxFrame.width / overlayW).coerceIn(0.10f, 1f - leftNorm)
		val heightNorm = (binding.cropBoxFrame.height / overlayH).coerceIn(0.06f, 1f - topNorm)

		val prefix = if (isEditingNoteCrop) "note_crop_" else "crop_"
		cropPrefs.edit()
			.putFloat("${prefix}left", leftNorm)
			.putFloat("${prefix}top", topNorm)
			.putFloat("${prefix}width", widthNorm)
			.putFloat("${prefix}height", heightNorm)
			.apply()

		binding.cropEditorOverlay.isVisible = false
		resetMenuButtonTimer()
		val msg = if (isEditingNoteCrop) "บันทึกกรอบแปลเฉพาะจุด (เอกสาร/คำแนะนำ) แล้ว" else "บันทึกกรอบพื้นที่อ่านซับเรียบร้อยแล้ว"
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
			setFloatingNoteCropBoxVisible(!isNoteCropBoxVisible)
			updateQuickDrawerUI()
			resetTopDrawerTimer()
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
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun setupNoteTranslationFeature() {
		isNoteCropBoxVisible = cropPrefs.getBoolean("note_crop_visible", false)
		if (isNoteCropBoxVisible && !isTextOnlyMode) {
			binding.floatingNoteCropBox.isVisible = true
			syncFloatingNoteCropBox()
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
	}

	private fun hideNoteTranslationCard() {
		uiVisibilityHandler.removeCallbacks(hideNoteCardRunnable)
		binding.noteTranslationCard.isVisible = false
		val lp = binding.pureFullscreenInner.layoutParams as FrameLayout.LayoutParams
		lp.gravity = Gravity.CENTER
		binding.pureFullscreenInner.layoutParams = lp
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
		if (!isFilterShortTextEnabled) return text.length >= 3 && text.any { it.isLetter() }

		val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
		// Exclude single word prompts under 8 characters
		if (words.size < 2 && text.length < 8) return false

		// Exclude common prompt words
		val lower = text.lowercase()
		val promptWords = setOf("talk", "skip", "attack", "dodge", "interact", "examine", "open", "back", "next", "continue", "menu", "cancel", "r1", "r2", "l1", "l2", "r3", "l3")
		if (words.size == 1 && promptWords.contains(lower)) return false
		if (words.size == 2 && words.any { it.lowercase() in listOf("r1", "r2", "l1", "l2") }) return false

		return text.any { it.isLetter() }
	}

	private fun dpToPx(dp: Int): Int {
		return (dp * resources.displayMetrics.density).toInt()
	}

	private suspend fun translateToThai(englishText: String): String = withContext(Dispatchers.IO) {
		if (geminiApiKey.isNotEmpty() && isGeminiTranslateEnabled) {
			try {
				val geminiRes = translateWithGemini(englishText)
				if (!geminiRes.isNullOrBlank()) {
					return@withContext geminiRes
				}
			} catch (e: Exception) {
				android.util.Log.w("PSTranslatorGemini", "Gemini translate fallback: ${e.message}")
			}
		}
		try {
			val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=th&dt=t&q=" + URLEncoder.encode(englishText, "UTF-8")
			val conn = URL(url).openConnection()
			conn.setRequestProperty("User-Agent", "Mozilla/5.0")
			conn.connectTimeout = 3000
			conn.readTimeout = 3000
			val res = conn.getInputStream().bufferedReader().use { it.readText() }
			val json = JSONArray(res)
			val sentences = json.getJSONArray(0)
			val sb = StringBuilder()
			for (i in 0 until sentences.length()) {
				sb.append(sentences.getJSONArray(i).getString(0))
			}
			val result = sb.toString().trim()
			if (result.isNotEmpty()) result else englishText
		} catch (e: Exception) {
			englishText
		}
	}

	private fun translateWithGemini(englishText: String): String? {
		val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" +
			URLEncoder.encode(geminiApiKey, "UTF-8")
		val conn = (URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
			requestMethod = "POST"
			setRequestProperty("Content-Type", "application/json")
			connectTimeout = 5000
			readTimeout = 6000
			doOutput = true
		}

		val prompt = "Translate this video game subtitle from English to natural, punchy spoken Thai (movie/game subtitle style). Accurately translate slang, idioms, and profanity/swear words matching the character's raw emotion without censoring or making it overly polite. Return ONLY the Thai translation:\n$englishText"
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
		val body = org.json.JSONObject().apply {
			put("contents", JSONArray().put(
				org.json.JSONObject().put("parts", JSONArray().put(
					org.json.JSONObject().put("text", prompt)
				))
			))
			put("safetySettings", safetyArray)
			put("generationConfig", org.json.JSONObject().apply {
				put("temperature", 0.35)
				put("maxOutputTokens", 200)
			})
		}

		conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
		if (conn.responseCode !in 200..299) {
			conn.disconnect()
			return null
		}
		val resText = conn.inputStream.bufferedReader().use { it.readText() }
		conn.disconnect()

		val json = org.json.JSONObject(resText)
		val candidates = json.optJSONArray("candidates") ?: return null
		if (candidates.length() == 0) return null
		val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
		if (parts.length() == 0) return null
		return parts.getJSONObject(0).optString("text", "").trim().ifEmpty { null }
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
			.replace(Regex("^\\[[^\\]]{1,26}\\]\\s*"), "")
			.replace(Regex("^[^:\\n]{1,26}:\\s*"), "")
			.trim()
		return if (stripped.isNotEmpty()) stripped else text
	}

	private fun synthesizeGeminiTts(thaiText: String, isFemale: Boolean, timestamp: Long): java.io.File? {
		return try {
			val conn = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as java.net.HttpURLConnection).apply {
				requestMethod = "POST"
				setRequestProperty("x-goog-api-key", geminiApiKey)
				setRequestProperty("Content-Type", "application/json")
				connectTimeout = 6000
				readTimeout = 10000
				doOutput = true
			}

			val voiceName = if (isFemale) "Kore" else "Charon"
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
		val nextFile = geminiAudioQueue.pollFirst() ?: return
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
		activeUtterances.clear()
		stopGeminiAudioPlayback()
		ocrScope.cancel()
		tts?.stop()
		tts?.shutdown()
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
