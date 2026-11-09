// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "translator.h"
#include <QUrl>
#include <QUrlQuery>
#include <QNetworkRequest>
#include <QJsonDocument>
#include <QJsonArray>
#include <QSettings>
#include <QRegularExpression>
#include <QBuffer>
#include <cmath>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <sapi.h>
#include <sphelper.h>
#endif

Translator::Translator(QObject *parent)
	: QObject(parent),
	subtitle_enabled(true),
	tts_enabled(false),
	voice_gender_mode(VoiceGenderMode::AUTO_AI),
	speech_speed(1.25),
	filter_short_text(true),
	is_processing(false),
	sapi_voice(nullptr)
{
	net_manager = new QNetworkAccessManager(this);
	connect(net_manager, &QNetworkAccessManager::finished, this, &Translator::OnTranslationReply);

	LoadSettings();
	InitTts();
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
	speech_speed = settings.value("speech_speed", 1.25).toDouble();
	filter_short_text = settings.value("filter_short_text", true).toBool();
}

void Translator::SaveSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	settings.setValue("subtitle_enabled", subtitle_enabled);
	settings.setValue("tts_enabled", tts_enabled);
	settings.setValue("voice_gender_mode", static_cast<int>(voice_gender_mode));
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

void Translator::InitTts()
{
#ifdef _WIN32
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
	struct TypoPair { const char *typo; const char *fix; };
	static const TypoPair replacements[] = {
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
	if (text.trimmed().isEmpty()) return false;
	if (filter_short_text && text.length() < 3) return false;

	static const QStringList uiKeywords = {
		"fps", "press", "hold", "options", "loading", "save", "load",
		"inventory", "equipped", "r1", "r2", "l1", "l2", "pause", "resume"
	};

	QString lower = text.toLower();
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
		if (diff <= 3) return true;
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
	if (!subtitle_enabled || is_processing || sub_image.isNull())
		return;

	is_processing = true;

	QString rawText = PerformOcr(sub_image);
	QString fixed = FixOcrTypos(rawText).simplified().trimmed();

	if (!fixed.isEmpty() && IsDialogueSubtitle(fixed))
	{
		if (!IsSimilar(fixed, last_recognized_english))
		{
			last_recognized_english = fixed;
			RequestTranslation(fixed);
			return;
		}
	}

	is_processing = false;
}

#if defined(_WIN32) && defined(_MSC_VER)
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Ocr.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/Windows.Globalization.h>

using namespace winrt;
using namespace Windows::Media::Ocr;
using namespace Windows::Graphics::Imaging;
using namespace Windows::Storage::Streams;

static QString WinRtOcr(const QImage &img)
{
	try
	{
		QImage rgba = img.convertToFormat(QImage::Format_RGBA8888);
		int w = rgba.width();
		int h = rgba.height();

		InMemoryRandomAccessStream stream;
		DataWriter writer(stream);

		QByteArray pngData;
		QBuffer buf(&pngData);
		buf.open(QIODevice::WriteOnly);
		rgba.save(&buf, "PNG");

		writer.WriteBytes(winrt::array_view<const uint8_t>(reinterpret_cast<const uint8_t*>(pngData.constData()), pngData.size()));
		writer.StoreAsync().get();
		writer.FlushAsync().get();
		writer.DetachStream();

		stream.Seek(0);
		BitmapDecoder decoder = BitmapDecoder::CreateAsync(stream).get();
		SoftwareBitmap swBmp = decoder.GetSoftwareBitmapAsync().get();

		OcrEngine engine = OcrEngine::TryCreateFromUserProfileLanguages();
		if (!engine)
		{
			Windows::Globalization::Language lang(L"en-US");
			engine = OcrEngine::TryCreateFromLanguage(lang);
		}
		if (engine)
		{
			OcrResult res = engine.RecognizeAsync(swBmp).get();
			std::wstring recognized = res.Text().c_str();
			return QString::fromStdWString(recognized);
		}
	}
	catch (...) {}
	return QString();
}
#endif

QString Translator::PerformOcr(const QImage &image)
{
#if defined(_WIN32) && defined(_MSC_VER)
	QString text = WinRtOcr(image);
	if (!text.isEmpty()) return text;
#else
	Q_UNUSED(image);
#endif
	return QString();
}

void Translator::RequestTranslation(const QString &englishText)
{
	QString encoded = QUrl::toPercentEncoding(englishText);
	QString urlStr = QString("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&q=%1").arg(encoded);

	QNetworkRequest req((QUrl(urlStr)));
	req.setHeader(QNetworkRequest::UserAgentHeader, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
	req.setAttribute(QNetworkRequest::User, englishText);

	net_manager->get(req);
}

void Translator::OnTranslationReply(QNetworkReply *reply)
{
	is_processing = false;

	if (reply->error() != QNetworkReply::NoError)
	{
		reply->deleteLater();
		return;
	}

	QString englishText = reply->request().attribute(QNetworkRequest::User).toString();
	QByteArray data = reply->readAll();
	reply->deleteLater();

	QJsonDocument doc = QJsonDocument::fromJson(data);
	if (!doc.isArray()) return;

	QJsonArray rootArray = doc.array();
	if (rootArray.isEmpty() || !rootArray[0].isArray()) return;

	QJsonArray chunksArray = rootArray[0].toArray();
	QString thaiResult;
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

	thaiResult = thaiResult.trimmed();
	if (!thaiResult.isEmpty())
	{
		emit SubtitleReady(thaiResult, englishText);

		if (tts_enabled)
		{
			SpeakThai(thaiResult, englishText);
		}
	}
}

void Translator::SpeakThai(const QString &thaiText, const QString &englishText)
{
#ifdef _WIN32
	if (!sapi_voice) return;

	ISpVoice *voice = reinterpret_cast<ISpVoice *>(sapi_voice);

	bool isFemale = false;
	switch (voice_gender_mode)
	{
		case VoiceGenderMode::FEMALE: isFemale = true; break;
		case VoiceGenderMode::MALE: isFemale = false; break;
		case VoiceGenderMode::AUTO_AI: isFemale = DetectFemale(englishText, thaiText); break;
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
