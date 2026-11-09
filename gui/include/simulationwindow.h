// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_SIMULATIONWINDOW_H
#define CHIAKI_GUI_SIMULATIONWINDOW_H

#include <QMainWindow>
#include <QTimer>
#include <QVector>
#include <QString>
#include <QWidget>
#include <QPushButton>
#include <QLabel>
#include <QAudioOutput>
#include <QIODevice>
#include "translator.h"
#include "subtitleoverlaywidget.h"
#include "cropboxwidget.h"

struct SimDialogueLine {
	QString speaker;
	QString text;
	int pitchHz;
	bool isFemale;
};

class SimulatedCanvasWidget;

class SimulationWindow : public QMainWindow
{
	Q_OBJECT

public:
	explicit SimulationWindow(QWidget *parent = nullptr);
	~SimulationWindow() override;

	void SetGameAudioMuted(bool muted);

protected:
	void resizeEvent(QResizeEvent *event) override;
	void closeEvent(QCloseEvent *event) override;

private slots:
	void CaptureAndProcessOcr();
	void OnSubtitleReady(const QString &thai, const QString &english);
	void OpenTranslatorMenu();
	void AdvanceDialogue();
	void FeedAudioSamples();

private:
	void UpdateOverlayPositions();

	SimulatedCanvasWidget *canvas_widget;
	CropBoxWidget *crop_widget;
	SubtitleOverlayWidget *subtitle_widget;
	QPushButton *menu_button;
	QWidget *top_banner;
	Translator *translator;
	QTimer *ocr_timer;
	QTimer *dialogue_timer;
	QTimer *audio_timer;

	QVector<SimDialogueLine> dialogues;
	int current_dialogue_idx;

	bool is_audio_muted;
	int audio_sample_index;

	QAudioOutput *audio_output;
	QIODevice *audio_io;
};

#endif // CHIAKI_GUI_SIMULATIONWINDOW_H
