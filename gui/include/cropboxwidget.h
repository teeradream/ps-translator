// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_CROPBOXWIDGET_H
#define CHIAKI_GUI_CROPBOXWIDGET_H

#include <QWidget>
#include <QRectF>
#include <QPoint>

class CropBoxWidget : public QWidget
{
	Q_OBJECT

public:
	explicit CropBoxWidget(QWidget *parent = nullptr);

	QRectF GetNormalizedRect() const { return norm_rect; }
	void SetNormalizedRect(const QRectF &rect);
	void UpdatePositionFromParent();

signals:
	void RectChanged(const QRectF &rect);
	void Closed();

protected:
	void paintEvent(QPaintEvent *event) override;
	void mousePressEvent(QMouseEvent *event) override;
	void mouseMoveEvent(QMouseEvent *event) override;
	void mouseReleaseEvent(QMouseEvent *event) override;

private:
	QRectF norm_rect;
	bool is_dragging;
	bool is_resizing;
	QPoint drag_start_pos;
	QRect start_geom;

	const int handle_size = 24;
	bool IsInHandle(const QPoint &pos) const;
	void SaveSettings();
	void LoadSettings();
};

#endif // CHIAKI_GUI_CROPBOXWIDGET_H
