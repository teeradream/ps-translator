// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_STREAMWINDOW_H
#define CHIAKI_GUI_STREAMWINDOW_H

#include <QMainWindow>

#include "streamsession.h"

class QLabel;
class QPushButton;
class QTimer;
class AVOpenGLWidget;
class CropBoxWidget;
class SubtitleOverlayWidget;
class Translator;
class TranslatorMenuDialog;

class StreamWindow: public QMainWindow
{
	Q_OBJECT

	public:
		explicit StreamWindow(const StreamSessionConnectInfo &connect_info, QWidget *parent = nullptr);
		~StreamWindow() override;

	private:
		const StreamSessionConnectInfo connect_info;
		StreamSession *session;

		QAction *fullscreen_action;
		QAction *stretch_action;
		QAction *zoom_action;
		AVOpenGLWidget *av_widget;

		CropBoxWidget *crop_widget;
		SubtitleOverlayWidget *subtitle_widget;
		QPushButton *menu_button;
		Translator *translator;
		QTimer *ocr_timer;
		QWidget *black_screen_widget;
		bool is_black_screen;

		void Init();
		void UpdateVideoTransform();
		void UpdateTransformModeActions();
		void UpdateOverlayPositions();

	protected:
		void keyPressEvent(QKeyEvent *event) override;
		void keyReleaseEvent(QKeyEvent *event) override;
		void closeEvent(QCloseEvent *event) override;
		void mousePressEvent(QMouseEvent *event) override;
		void mouseReleaseEvent(QMouseEvent *event) override;
		void mouseDoubleClickEvent(QMouseEvent *event) override;
		void resizeEvent(QResizeEvent *event) override;
		void moveEvent(QMoveEvent *event) override;
		void changeEvent(QEvent *event) override;

	private slots:
		void SessionQuit(ChiakiQuitReason reason, const QString &reason_str);
		void LoginPINRequested(bool incorrect);
		void ToggleFullscreen();
		void ToggleStretch();
		void ToggleZoom();
		void Quit();

		void CaptureAndProcessOcr();
		void OnSubtitleReady(const QString &thai, const QString &english);
		void OpenTranslatorMenu();
		void SetBlackScreenMode(bool blackScreen);
};

#endif // CHIAKI_GUI_STREAMWINDOW_H
