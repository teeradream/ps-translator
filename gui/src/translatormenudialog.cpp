// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "translatormenudialog.h"
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QGroupBox>
#include <QSettings>

TranslatorMenuDialog::TranslatorMenuDialog(Translator *translator,
										   SubtitleOverlayWidget *subOverlay,
										   CropBoxWidget *cropWidget,
										   QWidget *parent)
	: QDialog(parent),
	translator(translator),
	sub_overlay(subOverlay),
	crop_widget(cropWidget),
	is_audio_muted(false),
	is_text_only(false)
{
	setWindowTitle(QString::fromUtf8("ตั้งค่าซับไตเติ้ล & เสียงพากย์ไทย (PS-Translator)"));
	setFixedWidth(460);
	setWindowFlags(windowFlags() & ~Qt::WindowContextHelpButtonHint);

	UpdateDialogStyle();

	QVBoxLayout *mainLayout = new QVBoxLayout(this);
	mainLayout->setSpacing(14);
	mainLayout->setContentsMargins(20, 20, 20, 20);

	// 1. General switches
	QGroupBox *switchGroup = new QGroupBox(QString::fromUtf8("ระบบแปลภาษา & เสียงพากย์"), this);
	QVBoxLayout *switchLayout = new QVBoxLayout(switchGroup);

	sub_enable_check = new QCheckBox(QString::fromUtf8("เปิดแสดงซับไตเติ้ลแปลไทย"), switchGroup);
	sub_enable_check->setChecked(translator->IsSubtitleEnabled());
	connect(sub_enable_check, &QCheckBox::toggled, translator, &Translator::SetSubtitleEnabled);

	tts_enable_check = new QCheckBox(QString::fromUtf8("เปิดเสียงพากย์ไทย (AI Voice Dubbing)"), switchGroup);
	tts_enable_check->setChecked(translator->IsTtsEnabled());
	connect(tts_enable_check, &QCheckBox::toggled, translator, &Translator::SetTtsEnabled);

	switchLayout->addWidget(sub_enable_check);
	switchLayout->addWidget(tts_enable_check);
	mainLayout->addWidget(switchGroup);

	// 2. Voice Gender
	QGroupBox *genderGroup = new QGroupBox(QString::fromUtf8("เลือกเพศเสียงพากย์"), this);
	QHBoxLayout *genderLayout = new QHBoxLayout(genderGroup);

	gender_group = new QButtonGroup(this);
	gender_auto_radio = new QRadioButton(QString::fromUtf8("AI วิเคราะห์อัตโนมัติ"), genderGroup);
	gender_male_radio = new QRadioButton(QString::fromUtf8("เสียงผู้ชาย"), genderGroup);
	gender_female_radio = new QRadioButton(QString::fromUtf8("เสียงผู้หญิง"), genderGroup);

	gender_group->addButton(gender_auto_radio, 0);
	gender_group->addButton(gender_male_radio, 1);
	gender_group->addButton(gender_female_radio, 2);

	switch (translator->GetVoiceGenderMode())
	{
		case VoiceGenderMode::AUTO_AI: gender_auto_radio->setChecked(true); break;
		case VoiceGenderMode::MALE: gender_male_radio->setChecked(true); break;
		case VoiceGenderMode::FEMALE: gender_female_radio->setChecked(true); break;
	}

	connect(gender_group, QOverload<int>::of(&QButtonGroup::buttonClicked), this, [this](int id) {
		VoiceGenderMode mode = VoiceGenderMode::AUTO_AI;
		if (id == 1) mode = VoiceGenderMode::MALE;
		else if (id == 2) mode = VoiceGenderMode::FEMALE;
		this->translator->SetVoiceGenderMode(mode);
	});

	genderLayout->addWidget(gender_auto_radio);
	genderLayout->addWidget(gender_male_radio);
	genderLayout->addWidget(gender_female_radio);
	mainLayout->addWidget(genderGroup);

	// 3. Speech Speed
	QHBoxLayout *speedLayout = new QHBoxLayout();
	QLabel *speedTitle = new QLabel(QString::fromUtf8("ความเร็วเสียงพูด:"), this);
	speed_label = new QLabel(QString::asprintf("%.2fx", translator->GetSpeechSpeed()), this);
	speed_slider = new QSlider(Qt::Horizontal, this);
	speed_slider->setRange(80, 200);
	speed_slider->setValue(qRound(translator->GetSpeechSpeed() * 100));
	connect(speed_slider, &QSlider::valueChanged, this, [this](int val) {
		double spd = val / 100.0;
		speed_label->setText(QString::asprintf("%.2fx", spd));
		this->translator->SetSpeechSpeed(spd);
	});
	speedLayout->addWidget(speedTitle);
	speedLayout->addWidget(speed_slider);
	speedLayout->addWidget(speed_label);
	mainLayout->addLayout(speedLayout);

	// 4. Subtitle Typography
	QGroupBox *fontGroup = new QGroupBox(QString::fromUtf8("ปรับขนาดและสีตัวหนังสือ"), this);
	QVBoxLayout *fontLayout = new QVBoxLayout(fontGroup);

	// Thai font size
	QHBoxLayout *thSizeLayout = new QHBoxLayout();
	QLabel *thSizeTitle = new QLabel(QString::fromUtf8("ขนาดตัวหนังสือไทย:"), fontGroup);
	thai_size_label = new QLabel("24 pt", fontGroup);
	thai_size_slider = new QSlider(Qt::Horizontal, fontGroup);
	thai_size_slider->setRange(16, 72);

	QSettings settings("PS-Translator", "Chiaki");
	int thSize = settings.value("thai_font_size", 24).toInt();
	thai_size_slider->setValue(thSize);
	thai_size_label->setText(QString("%1 pt").arg(thSize));

	connect(thai_size_slider, &QSlider::valueChanged, this, [this](int val) {
		thai_size_label->setText(QString("%1 pt").arg(val));
		sub_overlay->SetThaiFontSize(val);
	});
	thSizeLayout->addWidget(thSizeTitle);
	thSizeLayout->addWidget(thai_size_slider);
	thSizeLayout->addWidget(thai_size_label);
	fontLayout->addLayout(thSizeLayout);

	// English font size
	QHBoxLayout *enSizeLayout = new QHBoxLayout();
	QLabel *enSizeTitle = new QLabel(QString::fromUtf8("ขนาดซับอังกฤษ:"), fontGroup);
	en_size_label = new QLabel("13 pt", fontGroup);
	en_size_slider = new QSlider(Qt::Horizontal, fontGroup);
	en_size_slider->setRange(10, 36);
	int enSize = settings.value("english_font_size", 13).toInt();
	en_size_slider->setValue(enSize);
	en_size_label->setText(QString("%1 pt").arg(enSize));

	connect(en_size_slider, &QSlider::valueChanged, this, [this](int val) {
		en_size_label->setText(QString("%1 pt").arg(val));
		sub_overlay->SetEnglishFontSize(val);
	});
	enSizeLayout->addWidget(enSizeTitle);
	enSizeLayout->addWidget(en_size_slider);
	enSizeLayout->addWidget(en_size_label);
	fontLayout->addLayout(enSizeLayout);

	// Thai colors
	QHBoxLayout *colorLayout = new QHBoxLayout();
	QLabel *colorTitle = new QLabel(QString::fromUtf8("สีตัวหนังสือไทย:"), fontGroup);
	colorLayout->addWidget(colorTitle);

	struct ColorChoice { const char *hex; const char *tip; };
	static const ColorChoice colors[] = {
		{"#FFD54F", "ทอง"},
		{"#FFFFFF", "ขาว"},
		{"#00E676", "เขียว"},
		{"#40C4FF", "ฟ้า"},
		{"#FF80AB", "ชมพู"},
		{"#FFAB40", "ส้ม"}
	};
	for (const auto &c : colors)
	{
		QPushButton *cBtn = new QPushButton(this);
		cBtn->setFixedSize(28, 28);
		cBtn->setToolTip(QString::fromUtf8(c.tip));
		cBtn->setStyleSheet(QString("background-color: %1; border-radius: 14px; border: 2px solid #555;").arg(c.hex));
		connect(cBtn, &QPushButton::clicked, this, [this, hex = QString(c.hex)]() {
			OnColorButtonClicked(hex);
		});
		colorLayout->addWidget(cBtn);
	}
	colorLayout->addStretch();
	fontLayout->addLayout(colorLayout);

	// Background Opacity
	QHBoxLayout *opLayout = new QHBoxLayout();
	QLabel *opTitle = new QLabel(QString::fromUtf8("ความทึบพื้นหลัง:"), fontGroup);
	opacity_label = new QLabel("78%", fontGroup);
	opacity_slider = new QSlider(Qt::Horizontal, fontGroup);
	opacity_slider->setRange(15, 100);
	int opVal = qRound(settings.value("bg_opacity", 0.78).toDouble() * 100);
	opacity_slider->setValue(opVal);
	opacity_label->setText(QString("%1%").arg(opVal));
	connect(opacity_slider, &QSlider::valueChanged, this, [this](int val) {
		opacity_label->setText(QString("%1%").arg(val));
		sub_overlay->SetBackgroundOpacity(val / 100.0);
	});
	opLayout->addWidget(opTitle);
	opLayout->addWidget(opacity_slider);
	opLayout->addWidget(opacity_label);
	fontLayout->addLayout(opLayout);

	mainLayout->addWidget(fontGroup);

	// 5. Actions & Modes
	QHBoxLayout *actionsLayout = new QHBoxLayout();

	adjust_crop_btn = new QPushButton(QString::fromUtf8("ปรับกรอบอ่านซับ"), this);
	adjust_crop_btn->setStyleSheet("background-color: #00E676; color: black; font-weight: bold; padding: 8px; border-radius: 6px;");
	connect(adjust_crop_btn, &QPushButton::clicked, this, [this]() {
		crop_widget->setVisible(!crop_widget->isVisible());
		if (crop_widget->isVisible())
			crop_widget->raise();
	});

	mute_audio_btn = new QPushButton(QString::fromUtf8("ปิดเสียงเกม"), this);
	mute_audio_btn->setStyleSheet("background-color: #37474F; color: white; padding: 8px; border-radius: 6px;");
	connect(mute_audio_btn, &QPushButton::clicked, this, [this]() {
		is_audio_muted = !is_audio_muted;
		SetGameAudioMuted(is_audio_muted);
		emit MuteGameAudioToggled(is_audio_muted);
	});

	text_only_btn = new QPushButton(QString::fromUtf8("โหมดเฉพาะตัวหนังสือ"), this);
	text_only_btn->setStyleSheet("background-color: #424242; color: white; padding: 8px; border-radius: 6px;");
	connect(text_only_btn, &QPushButton::clicked, this, [this]() {
		is_text_only = !is_text_only;
		SetTextOnlyMode(is_text_only);
		emit TextOnlyModeToggled(is_text_only);
	});

	actionsLayout->addWidget(adjust_crop_btn);
	actionsLayout->addWidget(mute_audio_btn);
	actionsLayout->addWidget(text_only_btn);
	mainLayout->addLayout(actionsLayout);

	// 6. Close / Done button
	QPushButton *closeBtn = new QPushButton(QString::fromUtf8("เสร็จสิ้น"), this);
	closeBtn->setStyleSheet("background-color: #2196F3; color: white; font-weight: bold; padding: 10px; border-radius: 6px;");
	connect(closeBtn, &QPushButton::clicked, this, &QDialog::accept);
	mainLayout->addWidget(closeBtn);
}

void TranslatorMenuDialog::UpdateDialogStyle()
{
	setStyleSheet("QDialog {"
				  "  background-color: #1E1E24;"
				  "  color: #ECEFF1;"
				  "  font-family: 'Segoe UI', sans-serif;"
				  "}"
				  "QGroupBox {"
				  "  border: 1px solid #37474F;"
				  "  border-radius: 8px;"
				  "  margin-top: 14px;"
				  "  padding-top: 14px;"
				  "  font-weight: bold;"
				  "  color: #00E676;"
				  "}"
				  "QGroupBox::title {"
				  "  subcontrol-origin: margin;"
				  "  subcontrol-position: top left;"
				  "  padding: 0 6px;"
				  "}"
				  "QLabel, QCheckBox, QRadioButton {"
				  "  color: #ECEFF1;"
				  "}");
}

void TranslatorMenuDialog::OnColorButtonClicked(const QString &colorHex)
{
	sub_overlay->SetThaiColor(QColor(colorHex));
}

void TranslatorMenuDialog::SetGameAudioMuted(bool muted)
{
	is_audio_muted = muted;
	if (muted)
	{
		mute_audio_btn->setText(QString::fromUtf8("เปิดเสียงเกม"));
		mute_audio_btn->setStyleSheet("background-color: #D32F2F; color: white; padding: 8px; border-radius: 6px; font-weight: bold;");
	}
	else
	{
		mute_audio_btn->setText(QString::fromUtf8("ปิดเสียงเกม"));
		mute_audio_btn->setStyleSheet("background-color: #37474F; color: white; padding: 8px; border-radius: 6px;");
	}
}

void TranslatorMenuDialog::SetTextOnlyMode(bool textOnly)
{
	is_text_only = textOnly;
	if (textOnly)
	{
		text_only_btn->setText(QString::fromUtf8("สลับเป็นโหมดเกม"));
		text_only_btn->setStyleSheet("background-color: #7B1FA2; color: white; padding: 8px; border-radius: 6px; font-weight: bold;");
	}
	else
	{
		text_only_btn->setText(QString::fromUtf8("โหมดเฉพาะตัวหนังสือ"));
		text_only_btn->setStyleSheet("background-color: #424242; color: white; padding: 8px; border-radius: 6px;");
	}
}
