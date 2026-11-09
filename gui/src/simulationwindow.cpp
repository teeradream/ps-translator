// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "simulationwindow.h"
#include "translatormenudialog.h"
#include <QPainter>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QResizeEvent>
#include <QCloseEvent>
#include <QCoreApplication>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class SimulatedCanvasWidget : public QWidget
{
	Q_OBJECT
public:
	explicit SimulatedCanvasWidget(QWidget *parent = nullptr)
		: QWidget(parent),
		speaker_name("Commander Arthur"),
		subtitle_text("The perimeter defense has been breached! Fall back to Sector 4 immediately!"),
		is_female(false),
		pitch_hz(110)
	{
		setMinimumSize(640, 360);
	}

	void SetDialogue(const QString &speaker, const QString &text, bool female, int pitch)
	{
		speaker_name = speaker;
		subtitle_text = text;
		is_female = female;
		pitch_hz = pitch;
		RenderOffscreenFrame();
		update();
	}

	const QImage &GetOffscreenFrame() const { return offscreen_frame; }

protected:
	void resizeEvent(QResizeEvent *) override
	{
		RenderOffscreenFrame();
	}

	void paintEvent(QPaintEvent *) override
	{
		QPainter p(this);
		if (!offscreen_frame.isNull())
		{
			p.drawImage(0, 0, offscreen_frame);
		}
	}

private:
	void RenderOffscreenFrame()
	{
		if (width() < 100 || height() < 100) return;

		offscreen_frame = QImage(size(), QImage::Format_RGB32);
		QPainter p(&offscreen_frame);
		p.setRenderHint(QPainter::Antialiasing);

		// 1. Dark Sci-Fi Game Background
		QLinearGradient bgGrad(0, 0, width(), height());
		bgGrad.setColorAt(0.0, QColor(10, 14, 26));
		bgGrad.setColorAt(0.5, QColor(18, 24, 44));
		bgGrad.setColorAt(1.0, QColor(8, 10, 20));
		p.fillRect(rect(), bgGrad);

		// Subtle cyber grid lines
		p.setPen(QPen(QColor(0, 230, 118, 20), 1));
		for (int x = 0; x < width(); x += 80)
		{
			p.drawLine(x, 0, x, height());
		}
		for (int y = 0; y < height(); y += 80)
		{
			p.drawLine(0, y, width(), y);
		}

		// 2. Game HUD at top left
		p.setPen(QColor(0, 230, 118));
		p.setFont(QFont("Segoe UI", 12, QFont::Bold));
		p.drawText(30, 80, QString::fromUtf8("PS-SIMULATOR // MISSION 07: SECTOR ASSAULT"));

		p.setPen(QColor(160, 190, 220));
		p.setFont(QFont("Segoe UI", 10));
		p.drawText(30, 102, QString::fromUtf8("HEALTH: [||||||||||||||||||] 100%   SHIELD: [||||||||||||] 84%"));

		// 3. Subtitle Box (in lower-center, matching default crop box: left 18%, top 72%, width 64%, height 22%)
		int boxX = qRound(width() * 0.18);
		int boxY = qRound(height() * 0.72);
		int boxW = qRound(width() * 0.64);
		int boxH = qRound(height() * 0.22);

		QRect subBoxRect(boxX, boxY, boxW, boxH);

		// Box background
		p.setPen(QPen(is_female ? QColor(255, 64, 129, 220) : QColor(0, 230, 118, 220), 2));
		p.setBrush(QColor(12, 16, 28, 230));
		p.drawRoundedRect(subBoxRect, 10, 10);

		// Speaker Name Tag
		QRect nameRect(boxX + 20, boxY + 12, boxW - 40, 26);
		p.setFont(QFont("Segoe UI", 12, QFont::Bold));
		p.setPen(is_female ? QColor(255, 64, 129) : QColor(0, 230, 118));
		QString tag = QString("%1 (%2 / %3 Hz)").arg(speaker_name).arg(is_female ? "Female Voice" : "Male Voice").arg(pitch_hz);
		p.drawText(nameRect, Qt::AlignLeft | Qt::AlignVCenter, tag);

		// High-contrast English Subtitle (crisp for OCR)
		QRect textRect(boxX + 20, boxY + 44, boxW - 40, boxH - 52);
		p.setFont(QFont("Arial", 16, QFont::Bold));
		p.setPen(Qt::white);
		p.drawText(textRect, Qt::AlignLeft | Qt::AlignTop | Qt::TextWordWrap, subtitle_text);
	}

	QString speaker_name;
	QString subtitle_text;
	bool is_female;
	int pitch_hz;
	QImage offscreen_frame;
};

#include "simulationwindow.moc"

SimulationWindow::SimulationWindow(QWidget *parent)
	: QMainWindow(parent),
	canvas_widget(nullptr),
	crop_widget(nullptr),
	subtitle_widget(nullptr),
	menu_button(nullptr),
	top_banner(nullptr),
	translator(nullptr),
	ocr_timer(nullptr),
	dialogue_timer(nullptr),
	audio_timer(nullptr),
	current_dialogue_idx(0),
	is_audio_muted(false),
	audio_sample_index(0)
{
	setAttribute(Qt::WA_DeleteOnClose);
	setWindowTitle(qApp->applicationName() + QString::fromUtf8(" | โหมดทดสอบจำลอง (Simulation Mode)"));
	resize(1280, 720);

	// 1. Central Canvas
	canvas_widget = new SimulatedCanvasWidget(this);
	setCentralWidget(canvas_widget);

	// 2. Dialogue sequence
	dialogues = {
		{
			QString::fromUtf8("Commander Arthur"),
			"The perimeter defense has been breached! Fall back to Sector 4 immediately!",
			110,
			false
		},
		{
			QString::fromUtf8("Agent Elena"),
			"Negative, Arthur. I have eyes on the hostile forces. Requesting covering fire!",
			230,
			true
		},
		{
			QString::fromUtf8("Commander Arthur"),
			"Enemy reinforcements are arriving from the northern ridge. Watch your flank!",
			115,
			false
		},
		{
			QString::fromUtf8("Agent Elena"),
			"Understood. Deploying EMP grenade to disable their shields. Move out now!",
			235,
			true
		},
		{
			QString::fromUtf8("Commander Arthur"),
			"Good shot! All strike teams proceed to extraction point Alpha.",
			110,
			false
		}
	};

	// 3. Translator components
	translator = new Translator(this);
	connect(translator, &Translator::SubtitleReady, this, &SimulationWindow::OnSubtitleReady);

	subtitle_widget = new SubtitleOverlayWidget(this);
	crop_widget = new CropBoxWidget(this);
	crop_widget->show(); // Show green crop box by default in demo mode

	// 4. Menu button (☰)
	menu_button = new QPushButton(QString::fromUtf8("☰"), this);
	menu_button->setFixedSize(42, 42);
	menu_button->setCursor(Qt::PointingHandCursor);
	menu_button->setStyleSheet(
		"QPushButton {"
		"  background-color: rgba(30, 30, 36, 0.85);"
		"  color: #00E676;"
		"  font-size: 20px;"
		"  font-weight: bold;"
		"  border-radius: 21px;"
		"  border: 2px solid #00E676;"
		"}"
		"QPushButton:hover {"
		"  background-color: #00E676;"
		"  color: black;"
		"}");
	connect(menu_button, &QPushButton::clicked, this, &SimulationWindow::OpenTranslatorMenu);

	// 5. Top banner guide
	top_banner = new QWidget(this);
	top_banner->setStyleSheet("background-color: rgba(18, 24, 38, 0.90); border-bottom: 2px solid #00E676;");
	QHBoxLayout *bannerLayout = new QHBoxLayout(top_banner);
	bannerLayout->setContentsMargins(16, 6, 16, 6);

	QLabel *bannerLabel = new QLabel(
		QString::fromUtf8("🎮 <b>โหมดทดสอบจำลอง (Simulation Demo)</b> &nbsp;|&nbsp; "
						  "ลากกรอบสีเขียวครอบซับอังกฤษด้านล่าง &nbsp;|&nbsp; "
						  "กดปุ่ม <b>☰</b> เพื่อตั้งค่าแปลภาษาและเลือกเสียงพากย์"), top_banner);
	bannerLabel->setStyleSheet("color: #E0E0E0; font-size: 13px;");

	QPushButton *exitBtn = new QPushButton(QString::fromUtf8("✕ ปิดโหมดจำลอง"), top_banner);
	exitBtn->setStyleSheet("background-color: #D32F2F; color: white; font-weight: bold; padding: 4px 12px; border-radius: 4px; border: none;");
	exitBtn->setCursor(Qt::PointingHandCursor);
	connect(exitBtn, &QPushButton::clicked, this, &SimulationWindow::close);

	bannerLayout->addWidget(bannerLabel);
	bannerLayout->addStretch();
	bannerLayout->addWidget(exitBtn);

	// 6. Timers
	ocr_timer = new QTimer(this);
	connect(ocr_timer, &QTimer::timeout, this, &SimulationWindow::CaptureAndProcessOcr);
	ocr_timer->start(800); // OCR scan every 800ms

	dialogue_timer = new QTimer(this);
	connect(dialogue_timer, &QTimer::timeout, this, &SimulationWindow::AdvanceDialogue);
	dialogue_timer->start(7000); // Change dialogue line every 7s

	audio_timer = new QTimer(this);
	connect(audio_timer, &QTimer::timeout, this, &SimulationWindow::FeedAudioSamples);
	audio_timer->start(100); // Feed 100ms audio chunks in memory to pitch detection

	UpdateOverlayPositions();
}

SimulationWindow::~SimulationWindow()
{
}

void SimulationWindow::SetGameAudioMuted(bool muted)
{
	is_audio_muted = muted;
}

void SimulationWindow::AdvanceDialogue()
{
	if (dialogues.isEmpty()) return;
	current_dialogue_idx = (current_dialogue_idx + 1) % dialogues.size();
	const SimDialogueLine &cur = dialogues[current_dialogue_idx];
	canvas_widget->SetDialogue(cur.speaker, cur.text, cur.isFemale, cur.pitchHz);
}

void SimulationWindow::FeedAudioSamples()
{
	if (dialogues.isEmpty() || !translator) return;

	const SimDialogueLine &cur = dialogues[current_dialogue_idx];
	int freq = cur.pitchHz;

	const int sampleRate = 48000;
	const int framesCount = sampleRate / 10; // 100ms = 4800 frames
	QVector<int16_t> stereoPcm(framesCount * 2);

	double twoPiF = 2.0 * M_PI * freq / sampleRate;
	for (int i = 0; i < framesCount; ++i)
	{
		// Fundamental tone + harmonics
		double s = 0.65 * std::sin(audio_sample_index * twoPiF)
				 + 0.25 * std::sin(audio_sample_index * twoPiF * 2.0)
				 + 0.10 * std::sin(audio_sample_index * twoPiF * 3.0);
		int16_t sampleVal = static_cast<int16_t>(qBound(-32767.0, s * 12000.0, 32767.0));

		stereoPcm[2 * i] = sampleVal;
		stereoPcm[2 * i + 1] = sampleVal;

		audio_sample_index++;
		if (audio_sample_index >= sampleRate)
			audio_sample_index = 0;
	}

	// Feed to pitch detection engine in Translator (interleaved stereo 48kHz)
	translator->FeedAudioSamples(stereoPcm.data(), framesCount);
}

void SimulationWindow::CaptureAndProcessOcr()
{
	if (!canvas_widget || !crop_widget || !translator || !translator->IsSubtitleEnabled())
		return;

	// Use offscreen pre-rendered image instead of grab() to prevent any UI thread deadlocks!
	const QImage &fullFrame = canvas_widget->GetOffscreenFrame();
	if (fullFrame.isNull() || fullFrame.width() < 100) return;

	QRectF norm = crop_widget->GetNormalizedRect();
	int cx = qBound(0, qRound(norm.left() * fullFrame.width()), fullFrame.width() - 10);
	int cy = qBound(0, qRound(norm.top() * fullFrame.height()), fullFrame.height() - 10);
	int cw = qBound(20, qRound(norm.width() * fullFrame.width()), fullFrame.width() - cx);
	int ch = qBound(10, qRound(norm.height() * fullFrame.height()), fullFrame.height() - cy);

	QImage cropped = fullFrame.copy(cx, cy, cw, ch);
	QImage scaled = cropped.scaled(cw * 2, ch * 2, Qt::KeepAspectRatio, Qt::SmoothTransformation);

	translator->ProcessFrame(scaled);
}

void SimulationWindow::OnSubtitleReady(const QString &thai, const QString &english)
{
	if (subtitle_widget && translator && translator->IsSubtitleEnabled())
	{
		subtitle_widget->ShowSubtitle(thai, english);
	}
}

void SimulationWindow::OpenTranslatorMenu()
{
	TranslatorMenuDialog dlg(translator, subtitle_widget, crop_widget, this);
	dlg.SetGameAudioMuted(is_audio_muted);
	dlg.SetTextOnlyMode(false);

	connect(&dlg, &TranslatorMenuDialog::MuteGameAudioToggled, this, &SimulationWindow::SetGameAudioMuted);

	dlg.exec();
}

void SimulationWindow::resizeEvent(QResizeEvent *event)
{
	UpdateOverlayPositions();
	QMainWindow::resizeEvent(event);
}

void SimulationWindow::closeEvent(QCloseEvent *event)
{
	if (ocr_timer) ocr_timer->stop();
	if (dialogue_timer) dialogue_timer->stop();
	if (audio_timer) audio_timer->stop();
	QMainWindow::closeEvent(event);
}

void SimulationWindow::UpdateOverlayPositions()
{
	if (top_banner)
	{
		top_banner->setGeometry(0, 0, width(), 42);
		top_banner->raise();
	}
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
		menu_button->move(width() - menu_button->width() - 20, 52);
		menu_button->raise();
	}
}
