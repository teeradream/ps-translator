// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_TRANSLATOR_H
#define CHIAKI_GUI_TRANSLATOR_H

#include <QObject>
#include <QImage>
#include <QString>
#include <QNetworkAccessManager>
#include <QNetworkReply>

enum class VoiceGenderMode {
	AUTO_AI,
	MALE,
	FEMALE
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

	bool IsTtsEnabled() const { return tts_enabled; }
	void SetTtsEnabled(bool enabled);

	VoiceGenderMode GetVoiceGenderMode() const { return voice_gender_mode; }
	void SetVoiceGenderMode(VoiceGenderMode mode);

	double GetSpeechSpeed() const { return speech_speed; }
	void SetSpeechSpeed(double speed);

	bool IsFilterShortText() const { return filter_short_text; }
	void SetFilterShortText(bool filter);

signals:
	void SubtitleReady(const QString &thai, const QString &english);
	void ErrorOccurred(const QString &error);

private slots:
	void OnTranslationReply(QNetworkReply *reply);

private:
	QNetworkAccessManager *net_manager;
	bool subtitle_enabled;
	bool tts_enabled;
	VoiceGenderMode voice_gender_mode;
	double speech_speed;
	bool filter_short_text;
	bool is_processing;

	QString last_recognized_english;

	QString PerformOcr(const QImage &image);
	QString FixOcrTypos(const QString &text);
	bool IsDialogueSubtitle(const QString &text);
	bool IsSimilar(const QString &a, const QString &b);
	bool DetectFemale(const QString &en, const QString &th);

	void RequestTranslation(const QString &englishText);
	void SpeakThai(const QString &thaiText, const QString &englishText);

	void InitTts();
	void FiniTts();
	void LoadSettings();
	void SaveSettings();

	void *sapi_voice; // ISpVoice pointer on Windows
};

#endif // CHIAKI_GUI_TRANSLATOR_H
