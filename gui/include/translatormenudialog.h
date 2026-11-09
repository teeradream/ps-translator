// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_TRANSLATORMENUDIALOG_H
#define CHIAKI_GUI_TRANSLATORMENUDIALOG_H

#include <QDialog>
#include <QCheckBox>
#include <QRadioButton>
#include <QSlider>
#include <QPushButton>
#include <QLabel>
#include <QButtonGroup>
#include "translator.h"
#include "subtitleoverlaywidget.h"
#include "cropboxwidget.h"

class TranslatorMenuDialog : public QDialog
{
	Q_OBJECT

public:
	explicit TranslatorMenuDialog(Translator *translator,
								 SubtitleOverlayWidget *subOverlay,
								 CropBoxWidget *cropWidget,
								 QWidget *parent = nullptr);

	void SetGameAudioMuted(bool muted);
	void SetTextOnlyMode(bool textOnly);

signals:
	void MuteGameAudioToggled(bool muted);
	void TextOnlyModeToggled(bool textOnly);
	void FullscreenRequested();

private slots:
	void OnColorButtonClicked(const QString &colorHex);

private:
	Translator *translator;
	SubtitleOverlayWidget *sub_overlay;
	CropBoxWidget *crop_widget;

	QCheckBox *sub_enable_check;
	QCheckBox *tts_enable_check;

	QButtonGroup *gender_group;
	QRadioButton *gender_auto_radio;
	QRadioButton *gender_male_radio;
	QRadioButton *gender_female_radio;

	QSlider *speed_slider;
	QLabel *speed_label;

	QSlider *thai_size_slider;
	QLabel *thai_size_label;
	QSlider *en_size_slider;
	QLabel *en_size_label;

	QSlider *opacity_slider;
	QLabel *opacity_label;

	QPushButton *adjust_crop_btn;
	QPushButton *mute_audio_btn;
	QPushButton *text_only_btn;
	QPushButton *fullscreen_btn;

	bool is_audio_muted;
	bool is_text_only;

	void UpdateDialogStyle();
};

#endif // CHIAKI_GUI_TRANSLATORMENUDIALOG_H
