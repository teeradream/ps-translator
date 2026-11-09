// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "subtitleoverlaywidget.h"
#include <QMouseEvent>
#include <QGraphicsOpacityEffect>
#include <QSettings>

SubtitleOverlayWidget::SubtitleOverlayWidget(QWidget *parent)
	: QWidget(parent),
	is_dragging(false),
	pos_y_norm(0.80)
{
	setAttribute(Qt::WA_TranslucentBackground);
	setMouseTracking(true);

	layout = new QVBoxLayout(this);
	layout->setContentsMargins(18, 12, 18, 12);
	layout->setSpacing(6);

	english_label = new QLabel(this);
	english_label->setAlignment(Qt::AlignCenter);
	english_label->setWordWrap(true);

	thai_label = new QLabel(this);
	thai_label->setAlignment(Qt::AlignCenter);
	thai_label->setWordWrap(true);

	layout->addWidget(english_label);
	layout->addWidget(thai_label);

	fade_timer = new QTimer(this);
	fade_timer->setSingleShot(true);
	connect(fade_timer, &QTimer::timeout, this, [this]() {
		hide();
	});

	LoadSettings();
	UpdateStyle();
	hide();
}

void SubtitleOverlayWidget::LoadSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	thai_font_size = settings.value("thai_font_size", 24).toInt();
	english_font_size = settings.value("english_font_size", 13).toInt();
	thai_color = QColor(settings.value("thai_color", "#FFD54F").toString());
	english_color = QColor(settings.value("english_color", "#E0E0E0").toString());
	bg_opacity = settings.value("bg_opacity", 0.78).toDouble();
	pos_y_norm = settings.value("sub_pos_y_norm", 0.80).toDouble();
}

void SubtitleOverlayWidget::SaveSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	settings.setValue("thai_font_size", thai_font_size);
	settings.setValue("english_font_size", english_font_size);
	settings.setValue("thai_color", thai_color.name());
	settings.setValue("english_color", english_color.name());
	settings.setValue("bg_opacity", bg_opacity);
	settings.setValue("sub_pos_y_norm", pos_y_norm);
}

void SubtitleOverlayWidget::UpdateStyle()
{
	int alpha = qBound(20, static_cast<int>(bg_opacity * 255.0), 255);
	setStyleSheet(QString("SubtitleOverlayWidget {"
						  "  background-color: rgba(12, 12, 14, %1);"
						  "  border: 1px solid rgba(255, 255, 255, 0.18);"
						  "  border-radius: 10px;"
						  "}").arg(alpha));

	QFont thFont("Segoe UI", thai_font_size, QFont::Bold);
	thai_label->setFont(thFont);
	thai_label->setStyleSheet(QString("color: %1; background: transparent;").arg(thai_color.name()));

	QFont enFont("Segoe UI", english_font_size, QFont::Normal);
	english_label->setFont(enFont);
	english_label->setStyleSheet(QString("color: %1; background: transparent;").arg(english_color.name()));
}

void SubtitleOverlayWidget::SetThaiFontSize(int pt)
{
	thai_font_size = pt;
	UpdateStyle();
	SaveSettings();
}

void SubtitleOverlayWidget::SetEnglishFontSize(int pt)
{
	english_font_size = pt;
	UpdateStyle();
	SaveSettings();
}

void SubtitleOverlayWidget::SetThaiColor(const QColor &color)
{
	thai_color = color;
	UpdateStyle();
	SaveSettings();
}

void SubtitleOverlayWidget::SetEnglishColor(const QColor &color)
{
	english_color = color;
	UpdateStyle();
	SaveSettings();
}

void SubtitleOverlayWidget::SetBackgroundOpacity(double opacity)
{
	bg_opacity = opacity;
	UpdateStyle();
	SaveSettings();
}

void SubtitleOverlayWidget::ShowSubtitle(const QString &thai, const QString &english)
{
	thai_label->setText(thai);
	english_label->setText(english);
	english_label->setVisible(!english.isEmpty());

	adjustSize();
	UpdatePositionFromParent();
	show();
	raise();

	fade_timer->start(4500);
}

void SubtitleOverlayWidget::ClearSubtitle()
{
	fade_timer->stop();
	hide();
}

void SubtitleOverlayWidget::UpdatePositionFromParent()
{
	if (!parentWidget()) return;
	int parentW = parentWidget()->width();
	int parentH = parentWidget()->height();

	int maxW = qMin(parentW - 40, qMax(400, parentW * 3 / 4));
	setMaximumWidth(maxW);
	adjustSize();

	int x = (parentW - width()) / 2;
	int y = qBound(20, qRound(pos_y_norm * parentH), parentH - height() - 10);
	move(x, y);
}

void SubtitleOverlayWidget::mousePressEvent(QMouseEvent *event)
{
	if (event->button() == Qt::LeftButton)
	{
		is_dragging = true;
		drag_start_pos = event->globalPos();
		widget_start_pos = pos();
		event->accept();
	}
}

void SubtitleOverlayWidget::mouseMoveEvent(QMouseEvent *event)
{
	setCursor(Qt::SizeAllCursor);
	if (is_dragging && parentWidget())
	{
		QPoint delta = event->globalPos() - drag_start_pos;
		int parentH = parentWidget()->height();
		int newY = qBound(20, widget_start_pos.y() + delta.y(), parentH - height() - 10);
		move(x(), newY);

		pos_y_norm = static_cast<double>(newY) / parentH;
		event->accept();
	}
}

void SubtitleOverlayWidget::mouseReleaseEvent(QMouseEvent *event)
{
	if (event->button() == Qt::LeftButton)
	{
		is_dragging = false;
		SaveSettings();
		event->accept();
	}
}
