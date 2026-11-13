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
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
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
	private var speechSpeedRate = 1.25f

	// Subtitle cascade history and styling options
	private val subtitleHistory = mutableListOf<SubtitleHistoryItem>()
	private var maxHistoryLines = 3
	private var currentSubTextColor = Color.parseColor("#00FF88")
	private var currentSubFontSize = 26
	private var currentSubPosition = "CENTER"
	private var currentSubBgOpacity = 80

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

		// 3-Lines Circular Menu Button
		binding.btnMenuToggle.setOnClickListener {
			val show = !binding.menuPanelContainer.isVisible
			binding.menuPanelContainer.isVisible = show
			if (show) {
				showMenuPage("MAIN")
				binding.btnMenuToggle.alpha = 1.0f
				uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
			} else {
				resetMenuButtonTimer()
			}
		}

		binding.btnCloseMenu.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			resetMenuButtonTimer()
		}

		binding.menuPanelContainer.setOnClickListener {
			binding.menuPanelContainer.isVisible = false
			resetMenuButtonTimer()
		}

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

		// Subtitle overlay switch
		binding.subOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
			isSubtitleEnabled = isChecked
			binding.subCustomizationContainer.isVisible = isChecked
			binding.subtitleOverlayContainer.isVisible = isChecked && !isTextOnlyMode && subtitleHistory.isNotEmpty()
		}

		// Load subtitle customization preferences
		currentSubTextColor = try {
			Color.parseColor(cropPrefs.getString("sub_text_color", "#00FF88") ?: "#00FF88")
		} catch (_: Exception) {
			Color.parseColor("#00FF88")
		}
		currentSubFontSize = cropPrefs.getInt("sub_font_size_sp", 26).coerceIn(16, 42)
		currentSubPosition = cropPrefs.getString("sub_position", "CENTER") ?: "CENTER"
		currentSubBgOpacity = cropPrefs.getInt("sub_bg_opacity", 80).coerceIn(0, 100)
		maxHistoryLines = cropPrefs.getInt("sub_history_limit", 3).coerceIn(1, 3)

		setupSubtitleCustomizationUI()
		applySubtitleStyles()

		// TTS voice switch
		binding.ttsVoiceSwitch.setOnCheckedChangeListener { _, isChecked ->
			isTtsEnabled = isChecked
			binding.voiceGenderContainer.isVisible = isChecked
			binding.speechSpeedContainer.isVisible = isChecked
			binding.ttsQueueModeContainer.isVisible = isChecked
			if (!isChecked) {
				activeUtterances.clear()
				tts?.stop()
			}
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
			binding.btnTextOnlyMode.text = "สลับเป็นโหมดสตรีมภาพ"
		} else {
			binding.pureFullscreenLayout.isVisible = false
			binding.aspectRatioLayout.visibility = View.VISIBLE
			binding.subtitleOverlayContainer.isVisible = isSubtitleEnabled && subtitleHistory.isNotEmpty()
			binding.btnTextOnlyMode.text = "โหมดโชว์เฉพาะตัวหนังสือ (จอดำ)"
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
		if (isCapturing) return

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

		// 3. Font Size Presets & Stepper
		syncFontSizeToggleState()
		binding.fontSizeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				currentSubFontSize = when (checkedId) {
					R.id.btnSizeSmall -> 18
					R.id.btnSizeMedium -> 22
					R.id.btnSizeLarge -> 26
					R.id.btnSizeXLarge -> 34
					else -> 26
				}
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
				applySubtitleStyles()
			}
		}

		binding.btnFontDecrease.setOnClickListener {
			if (currentSubFontSize > 16) {
				currentSubFontSize -= 2
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
				syncFontSizeToggleState()
				applySubtitleStyles()
			}
		}

		binding.btnFontIncrease.setOnClickListener {
			if (currentSubFontSize < 42) {
				currentSubFontSize += 2
				cropPrefs.edit().putInt("sub_font_size_sp", currentSubFontSize).apply()
				syncFontSizeToggleState()
				applySubtitleStyles()
			}
		}

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
		binding.fontSizeToggleGroup.clearChecked()
		when (currentSubFontSize) {
			18 -> binding.fontSizeToggleGroup.check(R.id.btnSizeSmall)
			22 -> binding.fontSizeToggleGroup.check(R.id.btnSizeMedium)
			26 -> binding.fontSizeToggleGroup.check(R.id.btnSizeLarge)
			34 -> binding.fontSizeToggleGroup.check(R.id.btnSizeXLarge)
		}
	}

	private fun applySubtitleStyles() {
		// 1. Background drawable
		val alphaInt = (currentSubBgOpacity * 255 / 100).coerceIn(0, 255)
		val bgDrawable = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = dpToPx(12).toFloat()
			setColor(Color.argb(alphaInt, 6, 8, 16))
			if (alphaInt > 0) {
				setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
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
		binding.translatedSubText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.88f).coerceAtLeast(14f))

		binding.translatedSubText2.setTextColor(currentSubTextColor)
		binding.translatedSubText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.78f).coerceAtLeast(13f))

		// Text-only mode text styling (updates BOTH color and font size live!)
		val fullSize = (currentSubFontSize + 4).toFloat()
		binding.pureFullscreenText.setTextColor(currentSubTextColor)
		binding.pureFullscreenText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fullSize)
		binding.pureFullscreenText1.setTextColor(currentSubTextColor)
		binding.pureFullscreenText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fullSize * 0.85f).coerceAtLeast(16f))
		binding.pureFullscreenText2.setTextColor(currentSubTextColor)
		binding.pureFullscreenText2.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fullSize * 0.72f).coerceAtLeast(14f))

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
		binding.previewSubNew.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.8f).coerceAtLeast(14f))
		binding.previewSubOld.setTextColor(currentSubTextColor)
		binding.previewSubOld.setTextSize(TypedValue.COMPLEX_UNIT_SP, (currentSubFontSize * 0.7f).coerceAtLeast(12f))
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
		binding.btnMenuBack.isVisible = (page != "MAIN")
		binding.tvMenuTitle.text = when (page) {
			"TEXT" -> "ปรับแต่งตัวหนังสือ"
			"VOICE" -> "การออกเสียงพากย์"
			"MODE" -> "โหมดและการแสดงผล"
			else -> "ตั้งค่า PS Translator"
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
		binding.cropEditorOverlay.isVisible = true
		binding.cropEditorOverlay.post {
			val overlayW = binding.cropEditorOverlay.width
			val overlayH = binding.cropEditorOverlay.height
			val leftNorm = cropPrefs.getFloat("crop_left", 0.18f)
			val topNorm = cropPrefs.getFloat("crop_top", 0.72f)
			val widthNorm = cropPrefs.getFloat("crop_width", 0.64f)
			val heightNorm = cropPrefs.getFloat("crop_height", 0.22f)

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

		val w = (overlayW * 0.64f).toInt().coerceAtLeast(dpToPx(120))
		val h = (overlayH * 0.22f).toInt().coerceAtLeast(dpToPx(40))
		val x = overlayW * 0.18f
		val y = overlayH * 0.72f

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

		cropPrefs.edit()
			.putFloat("crop_left", leftNorm)
			.putFloat("crop_top", topNorm)
			.putFloat("crop_width", widthNorm)
			.putFloat("crop_height", heightNorm)
			.apply()

		binding.cropEditorOverlay.isVisible = false
		resetMenuButtonTimer()
		Toast.makeText(this, "บันทึกกรอบพื้นที่อ่านซับเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
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
		val ttsEngine = tts ?: return
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
		activeUtterances.clear()
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
