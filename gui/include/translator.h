// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_TRANSLATOR_H
#define CHIAKI_GUI_TRANSLATOR_H

#include <QObject>
#include <QImage>
#include <QString>
#include <QVector>
#include <QMutex>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <atomic>

enum class VoiceGenderMode {
	AUTO_AI,
	MALE,
	FEMALE
};

enum class TranslationEngine {
	GOOGLE_FREE,
	GEMINI_FLASH
};

class Translator : public QObject
{
	Q_OBJECT

public:
	explicit Translator(QObject *parent = nullptr);
	~Translator() override;

	void ProcessFrame(const QImage &sub_image);

	bool IsSubtitleEnabled() const { return subtitle_enabled; }
	void SetSubtitleEnabled(bool enabled);

	bool IsProcessing() const { return is_processing.load(); }

	bool IsTtsEnabled() const { return tts_enabled; }
	void SetTtsEnabled(bool enabled);

	VoiceGenderMode GetVoiceGenderMode() const { return voice_gender_mode; }
	void SetVoiceGenderMode(VoiceGenderMode mode);

	TranslationEngine GetTranslationEngine() const { return translation_engine; }
	void SetTranslationEngine(TranslationEngine engine);

	QString GetGeminiApiKey() const { return gemini_api_key; }
	void SetGeminiApiKey(const QString &key);

	double GetSpeechSpeed() const { return speech_speed; }
	void SetSpeechSpeed(double speed);

	bool IsFilterShortText() const { return filter_short_text; }
	void SetFilterShortText(bool filter);

	// Feed audio samples from game stream (interleaved stereo PCM 16-bit 48kHz)
	void FeedAudioSamples(const int16_t *buf, size_t samples_count);

	void SpeakThai(const QString &thaiText, const QString &englishText = QString());
	bool HasOcrEngineAvailable() const;
	QString GetActiveOcrEngineName() const;

signals:
	void SubtitleReady(const QString &thai, const QString &english);
	void StatusUpdated(const QString &status);
	void ErrorOccurred(const QString &error);

private slots:
	void OnTranslationReply(QNetworkReply *reply);

private:
	QNetworkAccessManager *net_manager;
	bool subtitle_enabled;
	bool tts_enabled;
	VoiceGenderMode voice_gender_mode;
	TranslationEngine translation_engine;
	QString gemini_api_key;
	double speech_speed;
	bool filter_short_text;
	std::atomic<bool> is_processing;
	int empty_frame_count;

	QString last_recognized_english;
	QString last_gemini_gender;

	// Audio pitch analysis buffer & mutex
	QMutex audio_mutex;
	QVector<int16_t> audio_buffer;
	int DetectVoiceGenderFromAudio();

	QString PerformOcr(const QImage &image);
	QString FixOcrTypos(const QString &text);
	bool IsDialogueSubtitle(const QString &text);
	bool IsSimilar(const QString &a, const QString &b);
	bool DetectFemale(const QString &en, const QString &th);

	QString TranslateTextSync(const QString &englishText);
	void RequestTranslation(const QString &englishText);
	void RequestTranslationGoogle(const QString &englishText);
	void RequestTranslationGemini(const QString &englishText);

	void InitTts();
	void FiniTts();
	void LoadSettings();
	void SaveSettings();

	void *sapi_voice; // ISpVoice pointer on Windows
};

#endif // CHIAKI_GUI_TRANSLATOR_H
