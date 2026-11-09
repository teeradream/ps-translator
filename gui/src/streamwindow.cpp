// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <streamwindow.h>
#include <streamsession.h>
#include <avopenglwidget.h>
#include <loginpindialog.h>
#include <settings.h>
#include <cropboxwidget.h>
#include <subtitleoverlaywidget.h>
#include <translator.h>
#include <translatormenudialog.h>

#include <QLabel>
#include <QPushButton>
#include <QTimer>
#include <QMessageBox>
#include <QCoreApplication>
#include <QAction>
#include <QMenu>

StreamWindow::StreamWindow(const StreamSessionConnectInfo &connect_info, QWidget *parent)
	: QMainWindow(parent),
	connect_info(connect_info),
	session(nullptr),
	av_widget(nullptr),
	crop_widget(nullptr),
	subtitle_widget(nullptr),
	menu_button(nullptr),
	translator(nullptr),
	ocr_timer(nullptr),
	black_screen_widget(nullptr),
	is_black_screen(false)
{
	setAttribute(Qt::WA_DeleteOnClose);
	setWindowTitle(qApp->applicationName() + " | Stream");

	try
	{
		Init();
	}
	catch(const Exception &e)
	{
		QMessageBox::critical(this, tr("Stream failed"), tr("Failed to initialize Stream Session: %1").arg(e.what()));
		close();
	}
}

StreamWindow::~StreamWindow()
{
	// make sure av_widget is always deleted before the session
	delete av_widget;
}

#include <QGuiApplication>

void StreamWindow::Init()
{
	session = new StreamSession(connect_info, this);

	connect(session, &StreamSession::SessionQuit, this, &StreamWindow::SessionQuit);
	connect(session, &StreamSession::LoginPINRequested, this, &StreamWindow::LoginPINRequested);

	const QKeySequence fullscreen_shortcut = Qt::Key_F11;
	const QKeySequence stretch_shortcut = Qt::CTRL + Qt::Key_S;
	const QKeySequence zoom_shortcut = Qt::CTRL + Qt::Key_Z;

	fullscreen_action = new QAction(tr("Fullscreen"), this);
	fullscreen_action->setCheckable(true);
	fullscreen_action->setShortcut(fullscreen_shortcut);
	addAction(fullscreen_action);
	connect(fullscreen_action, &QAction::triggered, this, &StreamWindow::ToggleFullscreen);

	if(session->GetFfmpegDecoder())
	{
		av_widget = new AVOpenGLWidget(session, this, connect_info.transform_mode);
		setCentralWidget(av_widget);

		av_widget->setContextMenuPolicy(Qt::CustomContextMenu);
		connect(av_widget, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos) {
			av_widget->ResetMouseTimeout();

			QMenu menu(av_widget);
			menu.addAction(fullscreen_action);
			menu.addSeparator();
			menu.addAction(stretch_action);
			menu.addAction(zoom_action);
			menu.addSeparator();
			menu.addAction(QString::fromUtf8("☰ ตั้งค่าแปลภาษา & เสียงพากย์ (PS-Translator)..."), this, &StreamWindow::OpenTranslatorMenu);
			releaseKeyboard();
			connect(&menu, &QMenu::aboutToHide, this, [this] {
				grabKeyboard();
			});
			menu.exec(av_widget->mapToGlobal(pos));
		});
	}
	else
	{
		QWidget *bg_widget = new QWidget(this);
		bg_widget->setStyleSheet("background-color: black;");
		setCentralWidget(bg_widget);
	}

	grabKeyboard();

	session->Start();

	stretch_action = new QAction(tr("Stretch"), this);
	stretch_action->setCheckable(true);
	stretch_action->setShortcut(stretch_shortcut);
	addAction(stretch_action);
	connect(stretch_action, &QAction::triggered, this, &StreamWindow::ToggleStretch);

	zoom_action = new QAction(tr("Zoom"), this);
	zoom_action->setCheckable(true);
	zoom_action->setShortcut(zoom_shortcut);
	addAction(zoom_action);
	connect(zoom_action, &QAction::triggered, this, &StreamWindow::ToggleZoom);

	auto translator_menu_action = new QAction(tr("Translator Menu"), this);
	translator_menu_action->setShortcut(Qt::Key_F2);
	addAction(translator_menu_action);
	connect(translator_menu_action, &QAction::triggered, this, &StreamWindow::OpenTranslatorMenu);

	auto quit_action = new QAction(tr("Quit"), this);
	quit_action->setShortcut(Qt::CTRL + Qt::Key_Q);
	addAction(quit_action);
	connect(quit_action, &QAction::triggered, this, &StreamWindow::Quit);

	resize(connect_info.video_profile.width, connect_info.video_profile.height);

	if(connect_info.fullscreen)
	{
		showFullScreen();
		fullscreen_action->setChecked(true);
	}
	else
		show();

	UpdateTransformModeActions();

	// Initialize PS-Translator components on top of av_widget (or this)
	translator = new Translator(this);
	connect(translator, &Translator::SubtitleReady, this, &StreamWindow::OnSubtitleReady);
	if (session)
	{
		connect(session, &StreamSession::AudioSamplesAvailable, translator, &Translator::FeedAudioSamples, Qt::DirectConnection);
	}

	QWidget *overlay_parent = av_widget ? static_cast<QWidget*>(av_widget) : this;

	subtitle_widget = new SubtitleOverlayWidget(overlay_parent);
	crop_widget = new CropBoxWidget(overlay_parent);
	crop_widget->show(); // Show crop box by default so user sees translation area!

	black_screen_widget = new QWidget(overlay_parent);
	black_screen_widget->setStyleSheet("background-color: black;");
	black_screen_widget->hide();

	menu_button = new QPushButton(QString::fromUtf8("☰"), overlay_parent);
	menu_button->setFixedSize(44, 44);
	menu_button->setCursor(Qt::PointingHandCursor);
	menu_button->setStyleSheet(
		"QPushButton {"
		"  background-color: rgba(24, 28, 36, 0.90);"
		"  color: #00E676;"
		"  font-size: 22px;"
		"  font-weight: bold;"
		"  border-radius: 22px;"
		"  border: 2px solid #00E676;"
		"}"
		"QPushButton:hover {"
		"  background-color: #00E676;"
		"  color: black;"
		"}");
	connect(menu_button, &QPushButton::clicked, this, &StreamWindow::OpenTranslatorMenu);
	menu_button->show();

	ocr_timer = new QTimer(this);
	connect(ocr_timer, &QTimer::timeout, this, &StreamWindow::CaptureAndProcessOcr);
	ocr_timer->start(800);

	UpdateOverlayPositions();
}

void StreamWindow::keyPressEvent(QKeyEvent *event)
{
	if(session)
		session->HandleKeyboardEvent(event);
}

void StreamWindow::keyReleaseEvent(QKeyEvent *event)
{
	if(session)
		session->HandleKeyboardEvent(event);
}

void StreamWindow::Quit()
{
	close();
}

void StreamWindow::mousePressEvent(QMouseEvent *event)
{
	if(session && session->HandleMouseEvent(event))
		return;
	QMainWindow::mousePressEvent(event);
}

void StreamWindow::mouseReleaseEvent(QMouseEvent *event)
{
	if(session && session->HandleMouseEvent(event))
		return;
	QMainWindow::mouseReleaseEvent(event);
}

void StreamWindow::mouseDoubleClickEvent(QMouseEvent *event)
{
	if(event->button() == Qt::MouseButton::LeftButton)
	{
		ToggleFullscreen();
		return;
	}
	QMainWindow::mouseDoubleClickEvent(event);
}

void StreamWindow::closeEvent(QCloseEvent *event)
{
	if(session)
	{
		if(session->IsConnected())
		{
			bool sleep = false;
			switch(connect_info.settings->GetDisconnectAction())
			{
				case DisconnectAction::Ask: {
					auto res = QMessageBox::question(this, tr("Disconnect Session"), tr("Do you want the Console to go into sleep mode?"),
							QMessageBox::Yes | QMessageBox::No | QMessageBox::Cancel);
					switch(res)
					{
						case QMessageBox::Yes:
							sleep = true;
							break;
						case QMessageBox::Cancel:
							event->ignore();
							return;
						default:
							break;
					}
					break;
				}
				case DisconnectAction::AlwaysSleep:
					sleep = true;
					break;
				default:
					break;
			}
			if(sleep)
				session->GoToBed();
		}
		session->Stop();
	}
}

void StreamWindow::SessionQuit(ChiakiQuitReason reason, const QString &reason_str)
{
	if(chiaki_quit_reason_is_error(reason))
	{
		QString m = tr("Chiaki Session has quit") + ":\n" + chiaki_quit_reason_string(reason);
		if(!reason_str.isEmpty())
			m += "\n" + tr("Reason") + ": \"" + reason_str + "\"";
		QMessageBox::critical(this, tr("Session has quit"), m);
	}
	close();
}

void StreamWindow::LoginPINRequested(bool incorrect)
{
	auto dialog = new LoginPINDialog(incorrect, this);
	dialog->setAttribute(Qt::WA_DeleteOnClose);
	connect(dialog, &QDialog::finished, this, [this, dialog](int result) {
		grabKeyboard();

		if(!session)
			return;

		if(result == QDialog::Accepted)
			session->SetLoginPIN(dialog->GetPIN());
		else
			session->Stop();
	});
	releaseKeyboard();
	dialog->show();
}

void StreamWindow::ToggleFullscreen()
{
	if(isFullScreen())
	{
		showNormal();
		fullscreen_action->setChecked(false);
	}
	else
	{
		showFullScreen();
		if(av_widget)
			av_widget->HideMouse();
		fullscreen_action->setChecked(true);
	}
}

void StreamWindow::UpdateTransformModeActions()
{
	TransformMode tm = av_widget ? av_widget->GetTransformMode() : TransformMode::Fit;
	stretch_action->setChecked(tm == TransformMode::Stretch);
	zoom_action->setChecked(tm == TransformMode::Zoom);
}

void StreamWindow::ToggleStretch()
{
	if(!av_widget)
		return;
	av_widget->SetTransformMode(
			av_widget->GetTransformMode() == TransformMode::Stretch
			? TransformMode::Fit
			: TransformMode::Stretch);
	UpdateTransformModeActions();
}

void StreamWindow::ToggleZoom()
{
	if(!av_widget)
		return;
	av_widget->SetTransformMode(
			av_widget->GetTransformMode() == TransformMode::Zoom
			? TransformMode::Fit
			: TransformMode::Zoom);
	UpdateTransformModeActions();
}

void StreamWindow::resizeEvent(QResizeEvent *event)
{
	QMainWindow::resizeEvent(event);
	UpdateVideoTransform();
	UpdateOverlayPositions();
}

void StreamWindow::moveEvent(QMoveEvent *event)
{
	QMainWindow::moveEvent(event);
	UpdateVideoTransform();
}

void StreamWindow::changeEvent(QEvent *event)
{
	QMainWindow::changeEvent(event);
	if(event->type() == QEvent::ActivationChange)
		UpdateVideoTransform();
}

void StreamWindow::UpdateVideoTransform()
{
#if CHIAKI_LIB_ENABLE_PI_DECODER
	ChiakiPiDecoder *pi_decoder = session->GetPiDecoder();
	if(pi_decoder)
	{
		QRect r = geometry();
		chiaki_pi_decoder_set_params(pi_decoder, r.x(), r.y(), r.width(), r.height(), isActiveWindow());
	}
#endif
}

void StreamWindow::UpdateOverlayPositions()
{
	QWidget *p = av_widget ? static_cast<QWidget*>(av_widget) : this;
	int pw = p->width();
	int ph = p->height();

	if (crop_widget)
	{
		crop_widget->UpdatePositionFromParent();
		crop_widget->raise();
	}
	if (subtitle_widget)
	{
		subtitle_widget->UpdatePositionFromParent();
		subtitle_widget->raise();
	}
	if (menu_button)
	{
		menu_button->move(pw - menu_button->width() - 24, 24);
		menu_button->raise();
		menu_button->show();
	}
	if (black_screen_widget)
		black_screen_widget->setGeometry(0, 0, pw, ph);
}

void StreamWindow::CaptureAndProcessOcr()
{
	if (!av_widget || !translator || !translator->IsSubtitleEnabled() || !session || !session->IsConnected())
		return;

	if (translator->IsProcessing())
		return;

	if (av_widget->width() < 100 || av_widget->height() < 100)
		return;

	bool wasVisible = crop_widget && crop_widget->isVisible();
	if (wasVisible)
		crop_widget->setVisible(false);

	QImage fullFrame = av_widget->grabFramebuffer();

	if (wasVisible)
		crop_widget->setVisible(true);

	if (fullFrame.isNull() || fullFrame.width() < 100) return;

	QRectF norm = crop_widget->GetNormalizedRect();
	int cx = qBound(0, qRound(norm.left() * fullFrame.width()), fullFrame.width() - 10);
	int cy = qBound(0, qRound(norm.top() * fullFrame.height()), fullFrame.height() - 10);
	int cw = qBound(20, qRound(norm.width() * fullFrame.width()), fullFrame.width() - cx);
	int ch = qBound(10, qRound(norm.height() * fullFrame.height()), fullFrame.height() - cy);

	QImage cropped = fullFrame.copy(cx, cy, cw, ch);
	QImage ocr_input = cropped;
	if (cropped.width() < 800 && cropped.height() < 400)
	{
		ocr_input = cropped.scaled(cropped.width() * 2, cropped.height() * 2, Qt::KeepAspectRatio, Qt::SmoothTransformation);
	}
	else if (cropped.width() > 2000 || cropped.height() > 2000)
	{
		ocr_input = cropped.scaled(2000, 2000, Qt::KeepAspectRatio, Qt::SmoothTransformation);
	}

	translator->ProcessFrame(ocr_input);
}

void StreamWindow::OnSubtitleReady(const QString &thai, const QString &english)
{
	if (subtitle_widget && translator && translator->IsSubtitleEnabled())
	{
		subtitle_widget->ShowSubtitle(thai, english);
	}
}

void StreamWindow::OpenTranslatorMenu()
{
	releaseKeyboard();
	TranslatorMenuDialog dlg(translator, subtitle_widget, crop_widget, this);
	dlg.SetGameAudioMuted(session ? session->IsAudioMuted() : false);
	dlg.SetTextOnlyMode(is_black_screen);

	connect(&dlg, &TranslatorMenuDialog::MuteGameAudioToggled, this, [this](bool muted) {
		if (session) session->SetAudioMuted(muted);
	});
	connect(&dlg, &TranslatorMenuDialog::TextOnlyModeToggled, this, &StreamWindow::SetBlackScreenMode);
	connect(&dlg, &TranslatorMenuDialog::FullscreenRequested, this, &StreamWindow::ToggleFullscreen);

	dlg.exec();
	grabKeyboard();
}

void StreamWindow::SetBlackScreenMode(bool blackScreen)
{
	is_black_screen = blackScreen;
	if (black_screen_widget)
	{
		black_screen_widget->setVisible(blackScreen);
		if (blackScreen)
		{
			black_screen_widget->raise();
			if (subtitle_widget) subtitle_widget->raise();
			if (menu_button) menu_button->raise();
		}
	}
}
