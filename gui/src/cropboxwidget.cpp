// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "cropboxwidget.h"
#include <QPainter>
#include <QMouseEvent>
#include <QSettings>

CropBoxWidget::CropBoxWidget(QWidget *parent)
	: QWidget(parent),
	is_dragging(false),
	is_resizing(false)
{
	setAttribute(Qt::WA_TranslucentBackground);
	setMouseTracking(true);
	LoadSettings();
}

void CropBoxWidget::LoadSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	double left = settings.value("crop_left", 0.18).toDouble();
	double top = settings.value("crop_top", 0.72).toDouble();
	double width = settings.value("crop_width", 0.64).toDouble();
	double height = settings.value("crop_height", 0.22).toDouble();
	norm_rect = QRectF(left, top, width, height);
}

void CropBoxWidget::SaveSettings()
{
	QSettings settings("PS-Translator", "Chiaki");
	settings.setValue("crop_left", norm_rect.left());
	settings.setValue("crop_top", norm_rect.top());
	settings.setValue("crop_width", norm_rect.width());
	settings.setValue("crop_height", norm_rect.height());
}

void CropBoxWidget::SetNormalizedRect(const QRectF &rect)
{
	norm_rect = rect;
	SaveSettings();
	UpdatePositionFromParent();
	emit RectChanged(norm_rect);
}

void CropBoxWidget::UpdatePositionFromParent()
{
	if (!parentWidget()) return;
	int parentW = parentWidget()->width();
	int parentH = parentWidget()->height();

	int x = qRound(norm_rect.left() * parentW);
	int y = qRound(norm_rect.top() * parentH);
	int w = qRound(norm_rect.width() * parentW);
	int h = qRound(norm_rect.height() * parentH);

	setGeometry(x, y, qMax(80, w), qMax(40, h));
	update();
}

bool CropBoxWidget::IsInHandle(const QPoint &pos) const
{
	QRect handleRect(width() - handle_size, height() - handle_size, handle_size, handle_size);
	return handleRect.contains(pos);
}

void CropBoxWidget::paintEvent(QPaintEvent *)
{
	QPainter p(this);
	p.setRenderHint(QPainter::Antialiasing);

	// Translucent faint green background
	p.fillRect(rect(), QColor(0, 230, 118, 15));

	// Bright green border
	QPen pen(QColor(0, 230, 118), 2, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
	p.setPen(pen);
	p.drawRect(rect().adjusted(1, 1, -2, -2));

	// Compact top corner badge (does not span entire width or block game subtitles)
	int badgeW = qMin(220, width());
	QRect bannerRect(0, 0, badgeW, 18);
	p.fillRect(bannerRect, QColor(0, 200, 83, 200));
	p.setPen(Qt::white);
	QFont f = p.font();
	f.setBold(true);
	f.setPointSize(8);
	p.setFont(f);
	p.drawText(bannerRect.adjusted(6, 0, -6, 0), Qt::AlignVCenter | Qt::AlignLeft, QString::fromUtf8("พื้นที่อ่านซับ (ลากย้ายได้)"));

	// Bottom-right resize handle
	QRect handleRect(width() - handle_size, height() - handle_size, handle_size, handle_size);
	p.fillRect(handleRect, QColor(0, 230, 118, 240));
	p.setPen(QPen(Qt::white, 2));
	p.drawLine(width() - 4, height() - 14, width() - 14, height() - 4);
	p.drawLine(width() - 4, height() - 9, width() - 9, height() - 4);
}

void CropBoxWidget::mousePressEvent(QMouseEvent *event)
{
	if (event->button() == Qt::LeftButton)
	{
		drag_start_pos = event->globalPos();
		start_geom = geometry();

		if (IsInHandle(event->pos()))
		{
			is_resizing = true;
			is_dragging = false;
		}
		else
		{
			is_dragging = true;
			is_resizing = false;
		}
		event->accept();
	}
}

void CropBoxWidget::mouseMoveEvent(QMouseEvent *event)
{
	if (IsInHandle(event->pos()))
		setCursor(Qt::SizeFDiagCursor);
	else
		setCursor(Qt::SizeAllCursor);

	if (!parentWidget()) return;
	int parentW = parentWidget()->width();
	int parentH = parentWidget()->height();

	QPoint delta = event->globalPos() - drag_start_pos;

	if (is_dragging)
	{
		int newX = qBound(0, start_geom.x() + delta.x(), parentW - width());
		int newY = qBound(0, start_geom.y() + delta.y(), parentH - height());
		move(newX, newY);

		norm_rect.moveLeft(static_cast<double>(newX) / parentW);
		norm_rect.moveTop(static_cast<double>(newY) / parentH);
		emit RectChanged(norm_rect);
		event->accept();
	}
	else if (is_resizing)
	{
		int newW = qBound(80, start_geom.width() + delta.x(), parentW - x());
		int newH = qBound(40, start_geom.height() + delta.y(), parentH - y());
		resize(newW, newH);

		norm_rect.setWidth(static_cast<double>(newW) / parentW);
		norm_rect.setHeight(static_cast<double>(newH) / parentH);
		emit RectChanged(norm_rect);
		event->accept();
	}
}

void CropBoxWidget::mouseReleaseEvent(QMouseEvent *event)
{
	if (event->button() == Qt::LeftButton)
	{
		is_dragging = false;
		is_resizing = false;
		SaveSettings();
		event->accept();
	}
}
