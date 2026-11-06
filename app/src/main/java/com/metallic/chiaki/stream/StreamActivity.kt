// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.EditText
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
	private lateinit var audioManager: AudioManager

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())
	private val ocrScope = CoroutineScope(Dispatchers.Main + Job())
	private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

	private var tts: TextToSpeech? = null
	private var isTtsEnabled = false
	private var isSubtitleEnabled = true
	private var isAudioMuted = false
	private var isTextOnlyMode = false
	private var isCapturing = false
	private var lastRecognizedEnglish = ""

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
			uiVisibilityHandler.postDelayed(this, 800)
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

		audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
		tts = TextToSpeech(this, this)

		// 3-Lines Circular Menu Button
		binding.btnMenuToggle.setOnClickListener {
			val show = !binding.menuPanelContainer.isVisible
			binding.menuPanelContainer.isVisible = show
			if (show) {
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

		// Subtitle overlay switch
		binding.subOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
			isSubtitleEnabled = isChecked
			binding.subtitleOverlayContainer.isVisible = isChecked && !isTextOnlyMode && binding.translatedSubText.text.isNotEmpty()
		}

		// TTS voice switch
		binding.ttsVoiceSwitch.setOnCheckedChangeListener { _, isChecked ->
			isTtsEnabled = isChecked
		}

		// Mute / Unmute audio button
		binding.btnMuteAudio.setOnClickListener {
			isAudioMuted = !isAudioMuted
			if (isAudioMuted) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
				} else {
					@Suppress("DEPRECATION")
					audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
				}
				binding.btnMuteAudio.text = "เปิดเสียงเกมมือถือ"
				binding.btnMuteAudio.setIconResource(R.drawable.ic_volume_off)
			} else {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
				} else {
					@Suppress("DEPRECATION")
					audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
				}
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
			binding.aspectRatioLayout.visibility = View.INVISIBLE
			binding.subtitleOverlayContainer.isVisible = false
			binding.btnTextOnlyMode.text = "สลับเป็นโหมดสตรีมภาพ"
		} else {
			binding.pureFullscreenLayout.isVisible = false
			binding.aspectRatioLayout.visibility = View.VISIBLE
			binding.subtitleOverlayContainer.isVisible = isSubtitleEnabled && binding.translatedSubText.text.isNotEmpty()
			binding.btnTextOnlyMode.text = "โหมดโชว์เฉพาะตัวหนังสือ (จอดำ)"
		}
	}

	override fun onInit(status: Int) {
		if (status == TextToSpeech.SUCCESS) {
			tts?.language = Locale("th", "TH")
		}
	}

	private fun captureAndProcessSubtitle() {
		val surfaceView = binding.surfaceView
		if (surfaceView.width <= 100 || surfaceView.height <= 100) return

		val w = surfaceView.width
		val h = surfaceView.height
		val cropHeight = (h * 0.22).toInt().coerceAtLeast(60)
		val cropTop = (h - cropHeight).coerceAtLeast(0)
		val rect = Rect(0, cropTop, w, h)
		val bitmap = Bitmap.createBitmap(w, cropHeight, Bitmap.Config.ARGB_8888)

		isCapturing = true
		PixelCopy.request(surfaceView, rect, bitmap, { copyResult ->
			isCapturing = false
			if (copyResult == PixelCopy.SUCCESS) {
				val image = InputImage.fromBitmap(bitmap, 0)
				textRecognizer.process(image)
					.addOnSuccessListener { visionText ->
						val text = visionText.text.trim().replace("\n", " ")
						if (text.length >= 3 && text != lastRecognizedEnglish) {
							lastRecognizedEnglish = text
							ocrScope.launch {
								val thai = translateToThai(text)
								binding.originalSubText.text = text
								binding.originalSubText.isVisible = true
								binding.translatedSubText.text = thai
								if (isSubtitleEnabled && !isTextOnlyMode) {
									binding.subtitleOverlayContainer.isVisible = true
								}
								binding.pureOriginalText.text = text
								binding.pureOriginalText.isVisible = true
								binding.pureFullscreenText.text = thai
								if (isTtsEnabled) {
									tts?.speak(thai, TextToSpeech.QUEUE_FLUSH, null, "sub_voice")
								}
							}
						}
					}
			}
		}, Handler(Looper.getMainLooper()))
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

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		viewModel.session.pause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		uiVisibilityHandler.removeCallbacks(captureRunnable)
		uiVisibilityHandler.removeCallbacks(hideMenuButtonRunnable)
		ocrScope.cancel()
		tts?.stop()
		tts?.shutdown()
		if (isAudioMuted) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
			} else {
				@Suppress("DEPRECATION")
				audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
			}
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
