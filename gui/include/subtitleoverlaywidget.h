// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_GUI_SUBTITLEOVERLAYWIDGET_H
#define CHIAKI_GUI_SUBTITLEOVERLAYWIDGET_H

#include <QWidget>
#include <QLabel>
#include <QVBoxLayout>
#include <QTimer>
#include <QColor>
#include <QPoint>

class SubtitleOverlayWidget : public QWidget
{
	Q_OBJECT

public:
	explicit SubtitleOverlayWidget(QWidget *parent = nullptr);

	void ShowSubtitle(const QString &thai, const QString &english);
	void ClearSubtitle();

	void SetThaiFontSize(int pt);
	void SetEnglishFontSize(int pt);
	void SetThaiColor(const QColor &color);
	void SetEnglishColor(const QColor &color);
	void SetBackgroundOpacity(double opacity);
	void UpdateStyle();

	void UpdatePositionFromParent();

protected:
	void mousePressEvent(QMouseEvent *event) override;
	void mouseMoveEvent(QMouseEvent *event) override;
	void mouseReleaseEvent(QMouseEvent *event) override;

private:
	QLabel *thai_label;
	QLabel *english_label;
	QVBoxLayout *layout;
	QTimer *fade_timer;

	int thai_font_size;
	int english_font_size;
	QColor thai_color;
	QColor english_color;
	double bg_opacity;

	bool is_dragging;
	QPoint drag_start_pos;
	QPoint widget_start_pos;
	double pos_y_norm;

	void LoadSettings();
	void SaveSettings();
};

#endif // CHIAKI_GUI_SUBTITLEOVERLAYWIDGET_H
