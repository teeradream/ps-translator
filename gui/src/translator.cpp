// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "translator.h"
#include <QUrl>
#include <QUrlQuery>
#include <QNetworkRequest>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QSettings>
#include <QRegularExpression>
#include <QBuffer>
#include <QMutexLocker>
#include <QtConcurrent>
#include <QProcess>
#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <cmath>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef _SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS
#define _SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS
#endif
#include <windows.h>
#include <winhttp.h>
#include <sapi.h>
#include <sphelper.h>
#pragma comment(lib, "winhttp.lib")
#if defined(_MSC_VER)
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Ocr.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/Windows.Globalization.h>
#endif
#endif

Translator::Translator(QObject *parent)
	: QObject(parent),
	subtitle_enabled(true),
	tts_enabled(false),
	voice_gender_mode(VoiceGenderMode::AUTO_AI),
	translation_engine(TranslationEngine::GOOGLE_FREE),
	gemini_api_key(""),
	speech_speed(1.25),
	filter_short_text(true),
	is_processing(false),
	empty_frame_count(0),
	sapi_voice(nullptr)
{
	net_manager = new QNetworkAccessManager(this);
	connect(net_manager, &QNetworkAccessManager::finished, this, &Translator::OnTranslationReply);

	LoadSettings();
}

Translator::~Translator()
{
	FiniTts();
}

void Translator::LoadSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	subtitle_enabled = settings.value("subtitle_enabled", true).toBool();
	tts_enabled = settings.value("tts_enabled", false).toBool();
	int mode = settings.value("voice_gender_mode", 0).toInt();
	voice_gender_mode = static_cast<VoiceGenderMode>(mode);
	int engine = settings.value("translation_engine", 0).toInt();
	translation_engine = static_cast<TranslationEngine>(engine);
	gemini_api_key = settings.value("gemini_api_key", "").toString();
	speech_speed = settings.value("speech_speed", 1.25).toDouble();
	filter_short_text = settings.value("filter_short_text", true).toBool();
}

void Translator::SaveSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	settings.setValue("subtitle_enabled", subtitle_enabled);
	settings.setValue("tts_enabled", tts_enabled);
	settings.setValue("voice_gender_mode", static_cast<int>(voice_gender_mode));
	settings.setValue("translation_engine", static_cast<int>(translation_engine));
	settings.setValue("gemini_api_key", gemini_api_key);
	settings.setValue("speech_speed", speech_speed);
	settings.setValue("filter_short_text", filter_short_text);
}

void Translator::SetSubtitleEnabled(bool enabled)
{
	subtitle_enabled = enabled;
	SaveSettings();
}

void Translator::SetTtsEnabled(bool enabled)
{
	tts_enabled = enabled;
	SaveSettings();
}

void Translator::SetVoiceGenderMode(VoiceGenderMode mode)
{
	voice_gender_mode = mode;
	SaveSettings();
}

void Translator::SetTranslationEngine(TranslationEngine engine)
{
	translation_engine = engine;
	SaveSettings();
}

void Translator::SetGeminiApiKey(const QString &key)
{
	gemini_api_key = key;
	SaveSettings();
}

void Translator::SetSpeechSpeed(double speed)
{
	speech_speed = speed;
	SaveSettings();
}

void Translator::SetFilterShortText(bool filter)
{
	filter_short_text = filter;
	SaveSettings();
}

void Translator::FeedAudioSamples(const int16_t *buf, size_t samples_count)
{
	if (!buf || samples_count == 0 || !tts_enabled || voice_gender_mode != VoiceGenderMode::AUTO_AI)
		return;

	QMutexLocker locker(&audio_mutex);
	// Buffer is interleaved stereo 16-bit 48000 Hz. Downmix to mono and keep last 24,000 samples (0.5s of audio)
	for (size_t i = 0; i < samples_count; i++)
	{
		int32_t mono = (static_cast<int32_t>(buf[2 * i]) + static_cast<int32_t>(buf[2 * i + 1])) / 2;
		audio_buffer.append(static_cast<int16_t>(mono));
	}

	if (audio_buffer.size() > 24000)
	{
		audio_buffer.remove(0, audio_buffer.size() - 24000);
	}
}

int Translator::DetectVoiceGenderFromAudio()
{
	// 0 = Unclear/Silent, 1 = Male (Pitch < 160Hz), 2 = Female (Pitch >= 160Hz)
	QVector<int16_t> samples;
	{
		QMutexLocker locker(&audio_mutex);
		if (audio_buffer.size() < 4800) // Need at least 0.1s of audio
			return 0;
		samples = audio_buffer;
	}

	// Downsample by 4: 48000 Hz -> 12000 Hz
	const int step = 4;
	int ds_len = samples.size() / step;
	if (ds_len < 1000)
		return 0;

	QVector<double> ds(ds_len);
	double energy = 0.0;
	for (int i = 0; i < ds_len; i++)
	{
		double val = samples[i * step];
		ds[i] = val;
		energy += val * val;
	}

	double rms = std::sqrt(energy / ds_len);
	if (rms < 350.0) // Audio is too quiet / ambient silence
		return 0;

	// Pitch range for human voice: 75 Hz to 350 Hz at 12000 Hz
	int min_lag = 12000 / 350; // ~34
	int max_lag = 12000 / 75;  // ~160

	double best_corr = 0.0;
	int best_lag = 0;

	for (int lag = min_lag; lag <= max_lag; lag++)
	{
		double corr = 0.0;
		int count = ds_len - lag;
		for (int i = 0; i < count; i++)
		{
			corr += ds[i] * ds[i + lag];
		}
		if (corr > best_corr)
		{
			best_corr = corr;
			best_lag = lag;
		}
	}

	if (best_lag > 0 && best_corr > 0.30 * energy)
	{
		double pitch = 12000.0 / best_lag;
		if (pitch < 160.0)
			return 1; // Male
		else
			return 2; // Female
	}

	return 0; // Unclear pitch
}

void Translator::InitTts()
{
#ifdef _WIN32
	if (sapi_voice) return;
	CoInitialize(nullptr);
	ISpVoice *voice = nullptr;
	HRESULT hr = CoCreateInstance(CLSID_SpVoice, nullptr, CLSCTX_ALL, IID_ISpVoice, (void **)&voice);
	if (SUCCEEDED(hr))
	{
		sapi_voice = voice;
	}
#endif
}

void Translator::FiniTts()
{
#ifdef _WIN32
	if (sapi_voice)
	{
		ISpVoice *voice = reinterpret_cast<ISpVoice *>(sapi_voice);
		voice->Release();
		sapi_voice = nullptr;
		CoUninitialize();
	}
#endif
}

QString Translator::FixOcrTypos(const QString &text)
{
	QString result = text;
	// Remove common OCR border artifacts like vertical bars or stray brackets
	result.replace('|', ' ');
	result.replace(QRegularExpression("^[\\s_~=`^\\-*•]+|[\\s_~=`^\\-*•]+$"), "");

	struct TypoPair { const char *typo; const char *fix; };
	static const TypoPair replacements[] = {
		{"alls", "all is"},
		{"eannot", "cannot"},
		{"eome", "come"},
		{"eall", "call"},
		{"eould", "could"},
		{"eause", "cause"},
		{"reoeive", "receive"},
		{"aecept", "accept"},
		{"effeet", "effect"},
		{"respeet", "respect"},
		{"expeet", "expect"},
		{"inspeet", "inspect"},
		{"eareful", "careful"},
		{"eontrol", "control"},
		{"seeond", "second"},
		{"direet", "direct"}
	};

	for (const auto &pair : replacements)
	{
		QRegularExpression re(QString("\\b%1\\b").arg(pair.typo), QRegularExpression::CaseInsensitiveOption);
		result.replace(re, pair.fix);
	}
	return result;
}

bool Translator::IsDialogueSubtitle(const QString &text)
{
	QString trimmed = text.trimmed();
	if (trimmed.isEmpty()) return false;
	if (filter_short_text && trimmed.length() < 3) return false;

	// Require at least 2 English letters to filter out random symbol noise
	int alphaCount = 0;
	for (QChar c : trimmed)
	{
		if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
			alphaCount++;
	}
	if (alphaCount < 2) return false;

	static const QStringList uiKeywords = {
		"fps", "press", "hold", "options", "loading", "save", "load",
		"inventory", "equipped", "r1", "r2", "l1", "l2", "pause", "resume"
	};

	QString lower = trimmed.toLower();
	for (const QString &kw : uiKeywords)
	{
		if (lower == kw) return false;
	}
	return true;
}

bool Translator::IsSimilar(const QString &a, const QString &b)
{
	if (a.compare(b, Qt::CaseInsensitive) == 0) return true;
	if (a.isEmpty() || b.isEmpty()) return false;
	if (a.contains(b, Qt::CaseInsensitive) || b.contains(a, Qt::CaseInsensitive))
	{
		int diff = std::abs(a.length() - b.length());
		if (diff <= 4) return true;
	}
	return false;
}

bool Translator::DetectFemale(const QString &en, const QString &th)
{
	QString lowerEn = en.toLower();
	if (lowerEn.contains("she") || lowerEn.contains("her") || lowerEn.contains("mrs") ||
		lowerEn.contains("lady") || lowerEn.contains("woman") || lowerEn.contains("girl") ||
		lowerEn.contains("sister") || lowerEn.contains("mother") || lowerEn.contains("queen") ||
		lowerEn.contains("princess"))
	{
		return true;
	}

	if (th.contains(QString::fromUtf8("ค่ะ")) || th.contains(QString::fromUtf8("คะ")) ||
		th.contains(QString::fromUtf8("หนู")) || th.contains(QString::fromUtf8("ฉัน")) ||
		th.contains(QString::fromUtf8("แม่")) || th.contains(QString::fromUtf8("พี่สาว")) ||
		th.contains(QString::fromUtf8("น้องสาว")))
	{
		return true;
	}

	return false;
}

void Translator::ProcessFrame(const QImage &sub_image)
{
	if (!subtitle_enabled || sub_image.isNull())
		return;

	bool expected = false;
	if (!is_processing.compare_exchange_strong(expected, true))
		return;

	QtConcurrent::run([this, sub_image]() {
		struct ProcessingGuard {
			std::atomic<bool> &flag;
			~ProcessingGuard() { flag.store(false); }
		} guard{this->is_processing};

#if defined(_WIN32) && defined(_MSC_VER)
		try {
			winrt::init_apartment(winrt::apartment_type::multi_threaded);
		} catch (...) {}
#endif

		if (!this->HasOcrEngineAvailable())
		{
			QMetaObject::invokeMethod(this, [this]() {
				emit StatusUpdated(QString::fromUtf8("⚠️ ไม่พบโฟลเดอร์ tesseract (กรุณาแตกไฟล์ zip ให้ครบ)"));
			});
			return;
		}

		QString rawText = this->PerformOcr(sub_image);
		QString fixed = this->FixOcrTypos(rawText).simplified().trimmed();

		if (!fixed.isEmpty() && this->IsDialogueSubtitle(fixed))
		{
			this->empty_frame_count = 0;
			if (!this->IsSimilar(fixed, this->last_recognized_english))
			{
				this->last_recognized_english = fixed;
				QMetaObject::invokeMethod(this, [this, fixed]() {
					emit StatusUpdated(QString::fromUtf8("อ่านได้: \"%1\" (กำลังแปล...)").arg(fixed.left(34)));
				});

				QString thaiResult = this->TranslateTextSync(fixed).trimmed();

				QMetaObject::invokeMethod(this, [this, thaiResult, fixed]() {
					if (!thaiResult.isEmpty())
					{
						emit StatusUpdated(QString::fromUtf8("แปลแล้ว: \"%1\"").arg(thaiResult.left(34)));
						emit SubtitleReady(thaiResult, fixed);
						if (this->tts_enabled)
						{
							this->SpeakThai(thaiResult, fixed);
						}
					}
					else
					{
						emit StatusUpdated(QString::fromUtf8("อ่านได้: \"%1\" (ออฟไลน์)").arg(fixed.left(34)));
						emit SubtitleReady(fixed, fixed);
					}
				});
			}
		}
		else
		{
			this->empty_frame_count++;
			if (this->empty_frame_count >= 3)
			{
				this->last_recognized_english.clear();
				QString engineName = this->GetActiveOcrEngineName();
				QMetaObject::invokeMethod(this, [this, engineName]() {
					emit StatusUpdated(QString::fromUtf8("กำลังสแกนซับอังกฤษ... (%1)").arg(engineName));
				});
			}
		}
	});
}

#if defined(_WIN32) && defined(_MSC_VER)
using namespace winrt;
using namespace Windows::Media::Ocr;
using namespace Windows::Graphics::Imaging;
using namespace Windows::Storage::Streams;

static OcrEngine GetWinRtOcrEngine()
{
	static OcrEngine cachedEngine = nullptr;
	static bool initialized = false;
	if (initialized)
		return cachedEngine;

	initialized = true;

	try
	{
		// Priority 1: Check en-US
		Windows::Globalization::Language enUs(L"en-US");
		if (OcrEngine::IsLanguageSupported(enUs))
		{
			cachedEngine = OcrEngine::TryCreateFromLanguage(enUs);
			if (cachedEngine) return cachedEngine;
		}
	}
	catch (...) {}

	try
	{
		// Priority 2: Any English variant (en-GB, en-AU, en-CA, etc.)
		auto langs = OcrEngine::AvailableRecognizerLanguages();
		for (auto const &lang : langs)
		{
			std::wstring tag = lang.LanguageTag().c_str();
			if (tag.rfind(L"en", 0) == 0)
			{
				cachedEngine = OcrEngine::TryCreateFromLanguage(lang);
				if (cachedEngine) return cachedEngine;
			}
		}
	}
	catch (...) {}

	try
	{
		// Priority 3: UserProfileLanguages
		cachedEngine = OcrEngine::TryCreateFromUserProfileLanguages();
		if (cachedEngine) return cachedEngine;
	}
	catch (...) {}

	try
	{
		// Priority 4: Any installed OCR language on system
		auto langs = OcrEngine::AvailableRecognizerLanguages();
		if (langs.Size() > 0)
		{
			cachedEngine = OcrEngine::TryCreateFromLanguage(langs.GetAt(0));
			if (cachedEngine) return cachedEngine;
		}
	}
	catch (...) {}

	return nullptr;
}

static QString WinRtOcrEngineRecognize(const QImage &img, const OcrEngine &engine)
{
	try
	{
		int w = img.width();
		int h = img.height();
		if (w < 20 || h < 15) return QString();

		// Convert to ARGB32_Premultiplied (in-memory little-endian byte order: BGRA)
		QImage argb = img.convertToFormat(QImage::Format_ARGB32_Premultiplied);
		w = argb.width();
		h = argb.height();

		// Pack tightly into contiguous byte buffer (stride w * 4)
		QByteArray rawBytes;
		rawBytes.resize(w * h * 4);
		uint8_t *dst = reinterpret_cast<uint8_t*>(rawBytes.data());
		for (int y = 0; y < h; y++)
		{
			const uint8_t *src = argb.constScanLine(y);
			memcpy(dst + y * w * 4, src, w * 4);
		}

		DataWriter writer;
		writer.WriteBytes(winrt::array_view<const uint8_t>(
			reinterpret_cast<const uint8_t*>(rawBytes.constData()),
			static_cast<uint32_t>(rawBytes.size())
		));
		IBuffer buffer = writer.DetachBuffer();

		SoftwareBitmap swBmp = SoftwareBitmap::CreateCopyFromBuffer(
			buffer,
			BitmapPixelFormat::Bgra8,
			w,
			h,
			BitmapAlphaMode::Premultiplied
		);

		OcrResult res = engine.RecognizeAsync(swBmp).get();
		if (res)
		{
			std::wstring recognized = res.Text().c_str();
			return QString::fromStdWString(recognized);
		}
	}
	catch (const winrt::hresult_error &e)
	{
		qWarning("WinRtOcrEngineRecognize HRESULT: 0x%08X: %ls", e.code().value, e.message().c_str());
	}
	catch (const std::exception &e)
	{
		qWarning("WinRtOcrEngineRecognize std: %s", e.what());
	}
	catch (...)
	{
		qWarning("WinRtOcrEngineRecognize unknown exception");
	}
	return QString();
}

static QString WinRtOcr(const QImage &img)
{
	if (img.isNull() || img.width() < 20 || img.height() < 15)
		return QString();

	OcrEngine engine = GetWinRtOcrEngine();
	if (!engine)
	{
		qWarning("WinRtOcr: No OCR engine available on this Windows system");
		return QString();
	}

	// Clamp image dimensions to safe bounds (MaxImageDimension)
	QImage processed = img;
	if (processed.height() < 60 && processed.width() < 1000)
	{
		processed = processed.scaled(processed.width() * 2, processed.height() * 2, Qt::KeepAspectRatio, Qt::SmoothTransformation);
	}
	uint32_t maxDim = 2048;
	try {
		maxDim = qBound<uint32_t>(1000, OcrEngine::MaxImageDimension(), 2500);
	} catch (...) {}

	if ((uint32_t)processed.width() > maxDim || (uint32_t)processed.height() > maxDim)
	{
		processed = processed.scaled(maxDim, maxDim, Qt::KeepAspectRatio, Qt::SmoothTransformation);
	}

	// Pass 1: Original image
	QString text = WinRtOcrEngineRecognize(processed, engine);
	if (!text.trimmed().isEmpty())
	{
		return text;
	}

	// Pass 2: High-contrast binarization for game subtitles (white text on dark/variable background)
	QImage contrastImg = processed.convertToFormat(QImage::Format_RGB32);
	int w = contrastImg.width();
	int h = contrastImg.height();
	for (int y = 0; y < h; y++)
	{
		QRgb *line = reinterpret_cast<QRgb*>(contrastImg.scanLine(y));
		for (int x = 0; x < w; x++)
		{
			QRgb pixel = line[x];
			int r = qRed(pixel);
			int g = qGreen(pixel);
			int b = qBlue(pixel);
			if (r > 165 && g > 165 && b > 165)
			{
				line[x] = qRgb(0, 0, 0);
			}
			else
			{
				line[x] = qRgb(255, 255, 255);
			}
		}
	}

	text = WinRtOcrEngineRecognize(contrastImg, engine);
	return text;
}
#endif

static QString GetTesseractExecutablePath()
{
	QString appDir = QCoreApplication::applicationDirPath();
	QStringList candidatePaths = {
		appDir + "/tesseract/tesseract.exe",
		appDir + "/tesseract.exe",
		appDir + "/../tesseract/tesseract.exe",
		"C:/Program Files/Tesseract-OCR/tesseract.exe",
		"C:/Program Files (x86)/Tesseract-OCR/tesseract.exe"
	};

	for (const QString &p : candidatePaths)
	{
		if (QFileInfo::exists(p))
			return QFileInfo(p).absoluteFilePath();
	}

	// Try system PATH
	QString pathEnv = QString::fromLocal8Bit(qgetenv("PATH"));
	for (const QString &dir : pathEnv.split(';'))
	{
		if (dir.trimmed().isEmpty()) continue;
		QString candidate = QDir(dir).filePath("tesseract.exe");
		if (QFileInfo::exists(candidate))
			return QFileInfo(candidate).absoluteFilePath();
	}

#ifndef _WIN32
	if (QFileInfo::exists("/usr/bin/tesseract"))
		return "/usr/bin/tesseract";
#endif

	return QString();
}

static QString ExecuteTesseractProcess(const QImage &img, const QString &tesseractBin, const QString &tempName)
{
	QString tempImgPath = QDir::toNativeSeparators(QDir::tempPath() + "/" + tempName);
	if (!img.save(tempImgPath, "PNG"))
		return QString();

	QFileInfo tinfo(tesseractBin);
	QString tessDir = QDir::toNativeSeparators(tinfo.dir().absolutePath());
	QString tessdataDir = tinfo.dir().filePath("tessdata");
	if (!QDir(tessdataDir).exists())
	{
		QString altTessdata = QCoreApplication::applicationDirPath() + "/tessdata";
		if (QDir(altTessdata).exists())
			tessdataDir = altTessdata;
	}
	QString nativeTessdataDir = QDir::toNativeSeparators(tessdataDir);

	QProcess proc;
	proc.setWorkingDirectory(tessDir);

	QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
	env.insert("PATH", tessDir + ";" + env.value("PATH"));
	if (QDir(tessdataDir).exists())
	{
		env.insert("TESSDATA_PREFIX", nativeTessdataDir);
	}
	proc.setProcessEnvironment(env);

#ifdef _WIN32
	proc.setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *args) {
		args->flags |= CREATE_NO_WINDOW;
	});
#endif

	QStringList args;
	args << tempImgPath << "stdout";
	if (QDir(tessdataDir).exists())
	{
		args << "--tessdata-dir" << nativeTessdataDir;
	}
	args << "-l" << "eng" << "--psm" << "6";

	proc.start(QDir::toNativeSeparators(tesseractBin), args);
	if (!proc.waitForFinished(4000))
	{
		proc.kill();
		return QString();
	}

	QByteArray output = proc.readAllStandardOutput();
	return QString::fromUtf8(output).trimmed();
}

static QString RunTesseractOcr(const QImage &img)
{
	if (img.isNull() || img.width() < 20 || img.height() < 10)
		return QString();

	QString tesseractBin = GetTesseractExecutablePath();
	if (tesseractBin.isEmpty())
		return QString();

	// Pass 1: Direct image
	QString text = ExecuteTesseractProcess(img, tesseractBin, "ps_translator_ocr_1.png");
	if (text.simplified().length() >= 3)
		return text;

	// Check if image has bright subtitle-like pixels before running Pass 2
	QImage rgb = img.convertToFormat(QImage::Format_RGB32);
	int w = rgb.width();
	int h = rgb.height();
	int brightCount = 0;
	int totalPixels = w * h;
	for (int y = 0; y < h; y++)
	{
		const QRgb *line = reinterpret_cast<const QRgb *>(rgb.constScanLine(y));
		for (int x = 0; x < w; x++)
		{
			if (qGreen(line[x]) > 175)
				brightCount++;
		}
	}

	double brightRatio = (double)brightCount / (double)qMax(1, totalPixels);
	if (brightRatio >= 0.005 && brightRatio <= 0.40)
	{
		// Pass 2: Binarized black-on-white with 8px white margin for Tesseract
		const int pad = 8;
		QImage binImg(w + pad * 2, h + pad * 2, QImage::Format_RGB32);
		binImg.fill(qRgb(255, 255, 255));
		for (int y = 0; y < h; y++)
		{
			const QRgb *srcLine = reinterpret_cast<const QRgb *>(rgb.constScanLine(y));
			QRgb *dstLine = reinterpret_cast<QRgb *>(binImg.scanLine(y + pad)) + pad;
			for (int x = 0; x < w; x++)
			{
				QRgb p = srcLine[x];
				if (qRed(p) > 175 && qGreen(p) > 175 && qBlue(p) > 175)
					dstLine[x] = qRgb(0, 0, 0);
				else
					dstLine[x] = qRgb(255, 255, 255);
			}
		}
		QString text2 = ExecuteTesseractProcess(binImg, tesseractBin, "ps_translator_ocr_2.png");
		if (text2.simplified().length() > text.simplified().length())
			return text2;
	}

	return text;
}

bool Translator::HasOcrEngineAvailable() const
{
	if (!GetTesseractExecutablePath().isEmpty())
		return true;
#if defined(_WIN32) && defined(_MSC_VER)
	if (GetWinRtOcrEngine() != nullptr)
		return true;
#endif
	return false;
}

QString Translator::GetActiveOcrEngineName() const
{
	QString tess = GetTesseractExecutablePath();
	if (!tess.isEmpty())
		return QString::fromUtf8("Tesseract OCR");
#if defined(_WIN32) && defined(_MSC_VER)
	if (GetWinRtOcrEngine() != nullptr)
		return QString::fromUtf8("Windows WinRT OCR");
#endif
	return QString::fromUtf8("ไม่พบเอนจิน OCR");
}

QString Translator::PerformOcr(const QImage &image)
{
	// Priority 1: Check bundled / local Tesseract OCR (works on all Windows editions without language pack)
	QString text = RunTesseractOcr(image);
	if (!text.isEmpty())
		return text;

#if defined(_WIN32) && defined(_MSC_VER)
	// Priority 2: Native Windows WinRT OCR (if Windows OCR package is present)
	text = WinRtOcr(image);
	if (!text.isEmpty())
		return text;
#endif

	return QString();
}

#ifdef _WIN32
static QByteArray WinHttpFetch(const std::wstring &host, const std::wstring &path, const QByteArray &postData = QByteArray())
{
	QByteArray result;
	HINTERNET hSession = WinHttpOpen(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
		WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
		WINHTTP_NO_PROXY_NAME,
		WINHTTP_NO_PROXY_BYPASS, 0);
	if (!hSession)
		return result;

	WinHttpSetTimeouts(hSession, 3000, 3000, 4000, 4000);

	HINTERNET hConnect = WinHttpConnect(hSession, host.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
	if (!hConnect)
	{
		WinHttpCloseHandle(hSession);
		return result;
	}

	const wchar_t *verb = postData.isEmpty() ? L"GET" : L"POST";
	HINTERNET hRequest = WinHttpOpenRequest(hConnect, verb, path.c_str(),
		nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
	if (!hRequest)
	{
		WinHttpCloseHandle(hConnect);
		WinHttpCloseHandle(hSession);
		return result;
	}

	const wchar_t *headers = postData.isEmpty()
		? WINHTTP_NO_ADDITIONAL_HEADERS
		: L"Content-Type: application/json\r\n";
	DWORD headersLen = postData.isEmpty() ? 0 : (DWORD)-1L;

	BOOL bResults = WinHttpSendRequest(hRequest,
		headers, headersLen,
		postData.isEmpty() ? WINHTTP_NO_REQUEST_DATA : (LPVOID)postData.constData(),
		(DWORD)postData.size(),
		(DWORD)postData.size(), 0);

	if (bResults)
		bResults = WinHttpReceiveResponse(hRequest, nullptr);

	if (bResults)
	{
		DWORD dwSize = 0;
		do
		{
			dwSize = 0;
			if (!WinHttpQueryDataAvailable(hRequest, &dwSize) || dwSize == 0)
				break;

			QByteArray chunk(dwSize, 0);
			DWORD dwDownloaded = 0;
			if (WinHttpReadData(hRequest, chunk.data(), dwSize, &dwDownloaded) && dwDownloaded > 0)
			{
				result.append(chunk.constData(), dwDownloaded);
			}
		} while (dwSize > 0);
	}

	WinHttpCloseHandle(hRequest);
	WinHttpCloseHandle(hConnect);
	WinHttpCloseHandle(hSession);
	return result;
}
#endif

QString Translator::TranslateTextSync(const QString &englishText)
{
	// 1. Try Gemini Flash if configured
	if (translation_engine == TranslationEngine::GEMINI_FLASH && !gemini_api_key.trimmed().isEmpty())
	{
		QJsonObject rootObj;
		QJsonArray contentsArray;
		QJsonObject contentObj;
		QJsonArray partsArray;
		QJsonObject partObj;

		QString prompt = QString(
			"You are a professional video game subtitle translator and Thai voice dubbing director.\n"
			"Translate the following English game subtitle into natural, emotional, authentic Thai game dubbing dialogue (สำนวนบทพากย์เกมภาษาไทยแท้ๆ มีอารมณ์ร่วม ไม่แปลตรงตัวทื่อๆ).\n"
			"Also determine the speaker gender ('male' or 'female') based on context.\n"
			"Respond ONLY with valid JSON in this exact structure: {\"thai\": \"คำแปลบทพากย์\", \"gender\": \"male\" or \"female\"}\n\n"
			"Subtitle: \"%1\"").arg(englishText);

		partObj["text"] = prompt;
		partsArray.append(partObj);
		contentObj["parts"] = partsArray;
		contentsArray.append(contentObj);
		rootObj["contents"] = contentsArray;

		QJsonObject genConfig;
		genConfig["responseMimeType"] = "application/json";
		genConfig["temperature"] = 0.3;
		rootObj["generationConfig"] = genConfig;

		QByteArray postData = QJsonDocument(rootObj).toJson(QJsonDocument::Compact);
		QByteArray geminiData;
#ifdef _WIN32
		std::wstring path = QString("/v1beta/models/gemini-1.5-flash:generateContent?key=%1").arg(gemini_api_key.trimmed()).toStdWString();
		geminiData = WinHttpFetch(L"generativelanguage.googleapis.com", path, postData);
#endif
		if (!geminiData.isEmpty())
		{
			QJsonDocument doc = QJsonDocument::fromJson(geminiData);
			if (doc.isObject())
			{
				QJsonArray candidates = doc.object()["candidates"].toArray();
				if (!candidates.isEmpty())
				{
					QJsonArray parts = candidates[0].toObject()["content"].toObject()["parts"].toArray();
					if (!parts.isEmpty())
					{
						QString text = parts[0].toObject()["text"].toString().trimmed();
						QJsonDocument parsedJson = QJsonDocument::fromJson(text.toUtf8());
						if (parsedJson.isObject())
						{
							QString thai = parsedJson.object()["thai"].toString().trimmed();
							last_gemini_gender = parsedJson.object()["gender"].toString().trimmed().toLower();
							if (!thai.isEmpty())
								return thai;
						}
						else if (!text.isEmpty())
						{
							return text;
						}
					}
				}
			}
		}
	}

	// 2. Google Translate Free via WinHTTP (native Windows Schannel TLS, zero OpenSSL dependency)
	QString encoded = QString::fromLatin1(QUrl::toPercentEncoding(englishText));
	QByteArray googleData;
#ifdef _WIN32
	std::wstring gPath = QString("/translate_a/single?client=gtx&sl=en&tl=th&dt=t&q=%1").arg(encoded).toStdWString();
	googleData = WinHttpFetch(L"translate.googleapis.com", gPath);
#endif
	if (googleData.isEmpty())
	{
		// Fallback to curl if available
		QProcess curlProc;
#ifdef _WIN32
		curlProc.setCreateProcessArgumentsModifier([](QProcess::CreateProcessArguments *args) {
			args->flags |= CREATE_NO_WINDOW;
		});
#endif
		QString urlStr = QString("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&q=%1").arg(encoded);
		curlProc.start("curl.exe", QStringList() << "-s" << "-m" << "4" << urlStr);
		if (curlProc.waitForFinished(4500))
			googleData = curlProc.readAllStandardOutput();
	}

	if (!googleData.isEmpty())
	{
		QJsonDocument doc = QJsonDocument::fromJson(googleData);
		if (doc.isArray())
		{
			QJsonArray rootArray = doc.array();
			if (!rootArray.isEmpty() && rootArray[0].isArray())
			{
				QString thaiResult;
				QJsonArray chunksArray = rootArray[0].toArray();
				for (const QJsonValue &chunkVal : chunksArray)
				{
					if (chunkVal.isArray())
					{
						QJsonArray chunk = chunkVal.toArray();
						if (!chunk.isEmpty() && chunk[0].isString())
						{
							thaiResult += chunk[0].toString();
						}
					}
				}
				return thaiResult.trimmed();
			}
		}
	}

	return QString();
}

void Translator::RequestTranslation(const QString &englishText)
{
	Q_UNUSED(englishText);
}

void Translator::RequestTranslationGoogle(const QString &englishText)
{
	Q_UNUSED(englishText);
}

void Translator::RequestTranslationGemini(const QString &englishText)
{
	Q_UNUSED(englishText);
}

void Translator::OnTranslationReply(QNetworkReply *reply)
{
	if (reply)
		reply->deleteLater();
	is_processing = false;
}

void Translator::SpeakThai(const QString &thaiText, const QString &englishText)
{
#ifdef _WIN32
	if (!sapi_voice)
	{
		InitTts();
		if (!sapi_voice) return;
	}

	ISpVoice *voice = reinterpret_cast<ISpVoice *>(sapi_voice);

	bool isFemale = false;
	switch (voice_gender_mode)
	{
		case VoiceGenderMode::FEMALE: 
			isFemale = true; 
			break;
		case VoiceGenderMode::MALE: 
			isFemale = false; 
			break;
		case VoiceGenderMode::AUTO_AI: {
			// Step 1: Detect pitch directly from real game voice audio stream
			int audioGender = DetectVoiceGenderFromAudio();
			if (audioGender == 1)
			{
				isFemale = false; // Male pitch (< 160 Hz)
			}
			else if (audioGender == 2)
			{
				isFemale = true; // Female pitch (>= 160 Hz)
			}
			else if (!last_gemini_gender.isEmpty())
			{
				// Step 2: Use Gemini Context if available
				isFemale = (last_gemini_gender == "female");
			}
			else
			{
				// Step 3: Heuristic text fallback
				isFemale = DetectFemale(englishText, thaiText);
			}
			break;
		}
	}

	// Try to select Thai voice (Language=41e)
	// 1. First check Windows OneCore voices (Windows 10/11 default for Thai: Niwat, Premwadee, Achara)
	bool voiceSet = false;
	ISpObjectTokenCategory *cat = nullptr;
	if (SUCCEEDED(CoCreateInstance(CLSID_SpObjectTokenCategory, nullptr, CLSCTX_ALL, IID_ISpObjectTokenCategory, (void**)&cat)))
	{
		if (SUCCEEDED(cat->SetId(L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech_OneCore\\Voices", FALSE)))
		{
			IEnumSpObjectTokens *enumTokens = nullptr;
			const wchar_t *filter = isFemale ? L"Language=41e;Gender=Female" : L"Language=41e;Gender=Male";
			if (FAILED(cat->EnumTokens(filter, nullptr, &enumTokens)) || !enumTokens)
			{
				cat->EnumTokens(L"Language=41e", nullptr, &enumTokens);
			}

			if (enumTokens)
			{
				ISpObjectToken *token = nullptr;
				if (SUCCEEDED(enumTokens->Next(1, &token, nullptr)) && token)
				{
					voice->SetVoice(token);
					token->Release();
					voiceSet = true;
				}
				enumTokens->Release();
			}
		}
		cat->Release();
	}

	// 2. Fallback to classic SAPI 5 voices if OneCore didn't find Thai
	if (!voiceSet)
	{
		ISpObjectToken *token = nullptr;
		HRESULT hr = SpFindBestToken(SPCAT_VOICES, isFemale ? L"Language=41e;Gender=Female" : L"Language=41e;Gender=Male", nullptr, &token);
		if (FAILED(hr) || !token)
		{
			hr = SpFindBestToken(SPCAT_VOICES, L"Language=41e", nullptr, &token);
		}
		if (SUCCEEDED(hr) && token)
		{
			voice->SetVoice(token);
			token->Release();
			voiceSet = true;
		}
	}

	long rate = 0;
	if (speech_speed <= 0.9) rate = -2;
	else if (speech_speed >= 1.45) rate = 3;
	else if (speech_speed >= 1.2) rate = 1;
	voice->SetRate(rate);

	std::wstring wText = thaiText.toStdWString();
	voice->Speak(wText.c_str(), SPF_ASYNC | SPF_PURGEBEFORESPEAK, nullptr);
#else
	Q_UNUSED(thaiText);
	Q_UNUSED(englishText);
#endif
}
