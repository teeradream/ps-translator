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
	setFixedWidth(480);
	setWindowFlags(windowFlags() & ~Qt::WindowContextHelpButtonHint);

	UpdateDialogStyle();

	QVBoxLayout *mainLayout = new QVBoxLayout(this);
	mainLayout->setSpacing(12);
	mainLayout->setContentsMargins(18, 18, 18, 18);

	// 1. General switches
	QGroupBox *switchGroup = new QGroupBox(QString::fromUtf8("ระบบเปิด/ปิดการทำงาน"), this);
	QVBoxLayout *switchLayout = new QVBoxLayout(switchGroup);

	sub_enable_check = new QCheckBox(QString::fromUtf8("เปิดแสดงซับไตเติ้ลแปลไทย"), switchGroup);
	sub_enable_check->setChecked(translator->IsSubtitleEnabled());
	connect(sub_enable_check, &QCheckBox::toggled, translator, &Translator::SetSubtitleEnabled);

	tts_enable_check = new QCheckBox(QString::fromUtf8("เปิดเสียงพากย์ไทย (AI Voice Dubbing)"), switchGroup);
	tts_enable_check->setChecked(translator->IsTtsEnabled());
	connect(tts_enable_check, &QCheckBox::toggled, translator, &Translator::SetTtsEnabled);

	switchLayout->addWidget(sub_enable_check);
	switchLayout->addWidget(tts_enable_check);

	QPushButton *testBtn = new QPushButton(QString::fromUtf8("🔊 ทดสอบซับไตเติ้ล & เสียงพากย์"), switchGroup);
	testBtn->setStyleSheet("background-color: #2e3842; color: #00E676; font-weight: bold; padding: 7px; border: 1px solid #00E676; border-radius: 4px;");
	connect(testBtn, &QPushButton::clicked, this, [this]() {
		if (sub_overlay)
			sub_overlay->ShowSubtitle(QString::fromUtf8("ทดสอบระบบแปลภาษาและเสียงพากย์ไทย พร้อมใช้งาน!"), "PS-Translator Thai Dubbing Test: System Ready!");
		if (translator)
			translator->SpeakThai(QString::fromUtf8("ทดสอบระบบแปลภาษาและเสียงพากย์ไทย พร้อมใช้งาน"));
	});
	switchLayout->addWidget(testBtn);
	mainLayout->addWidget(switchGroup);

	// 2. Translation Engine (Google Free vs Gemini Flash)
	QGroupBox *engineGroup = new QGroupBox(QString::fromUtf8("เอนจินการแปลภาษา (Translation Engine)"), this);
	QVBoxLayout *engineLayout = new QVBoxLayout(engineGroup);

	engine_group = new QButtonGroup(this);
	engine_google_radio = new QRadioButton(QString::fromUtf8("Google Translate (ฟรี - ค่าเริ่มต้น)"), engineGroup);
	engine_gemini_radio = new QRadioButton(QString::fromUtf8("Gemini AI Flash (สำนวนพากย์เกม + วิเคราะห์บริบท)"), engineGroup);

	engine_group->addButton(engine_google_radio, 0);
	engine_group->addButton(engine_gemini_radio, 1);

	if (translator->GetTranslationEngine() == TranslationEngine::GEMINI_FLASH)
		engine_gemini_radio->setChecked(true);
	else
		engine_google_radio->setChecked(true);

	gemini_container = new QWidget(engineGroup);
	QVBoxLayout *geminiLayout = new QVBoxLayout(gemini_container);
	geminiLayout->setContentsMargins(8, 2, 8, 2);

	QLabel *keyLabel = new QLabel(QString::fromUtf8("Gemini API Key (จาก Google AI Studio):"), gemini_container);
	gemini_key_edit = new QLineEdit(gemini_container);
	gemini_key_edit->setEchoMode(QLineEdit::Password);
	gemini_key_edit->setPlaceholderText("AIzaSy...");
	gemini_key_edit->setText(translator->GetGeminiApiKey());
	gemini_key_edit->setStyleSheet("background-color: #1e1e24; color: #00E676; padding: 6px; border: 1px solid #444; border-radius: 4px;");

	connect(gemini_key_edit, &QLineEdit::textChanged, this, [this](const QString &text) {
		this->translator->SetGeminiApiKey(text.trimmed());
	});

	geminiLayout->addWidget(keyLabel);
	geminiLayout->addWidget(gemini_key_edit);
	gemini_container->setVisible(translator->GetTranslationEngine() == TranslationEngine::GEMINI_FLASH);

	connect(engine_group, QOverload<int>::of(&QButtonGroup::buttonClicked), this, [this](int id) {
		TranslationEngine eng = (id == 1) ? TranslationEngine::GEMINI_FLASH : TranslationEngine::GOOGLE_FREE;
		this->translator->SetTranslationEngine(eng);
		this->gemini_container->setVisible(eng == TranslationEngine::GEMINI_FLASH);
	});

	engineLayout->addWidget(engine_google_radio);
	engineLayout->addWidget(engine_gemini_radio);
	engineLayout->addWidget(gemini_container);
	mainLayout->addWidget(engineGroup);

	// 3. Voice Gender Mode
	QGroupBox *genderGroup = new QGroupBox(QString::fromUtf8("เลือกเพศเสียงพากย์ (Voice Gender)"), this);
	QVBoxLayout *genderContainerLayout = new QVBoxLayout(genderGroup);
	QHBoxLayout *genderLayout = new QHBoxLayout();

	gender_group = new QButtonGroup(this);
	gender_auto_radio = new QRadioButton(QString::fromUtf8("AI ฟังเสียงสดในเกม (Auto)"), genderGroup);
	gender_auto_radio->setToolTip(QString::fromUtf8("ดักฟังย่านความถี่เสียงตัวละครจากเกมสดๆ เพื่อสลับชาย/หญิงให้อัตโนมัติ แม้กดปิดเสียงเกมก็ยังทำงาน"));

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
	genderContainerLayout->addLayout(genderLayout);

	QLabel *genderTip = new QLabel(QString::fromUtf8("* โหมด Auto จะวิเคราะห์ความถี่เสียงตัวละครจากเกมสดๆ แม้ปิดเสียงเกมไว้ก็ยังตรวจจับได้"), genderGroup);
	genderTip->setStyleSheet("color: #90A4AE; font-size: 11px;");
	genderContainerLayout->addWidget(genderTip);

	mainLayout->addWidget(genderGroup);

	// 4. Speech Speed
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

	// 5. Subtitle Typography
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

	// 6. Actions & Modes
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

	// 7. Close / Done button
	QPushButton *closeBtn = new QPushButton(QString::fromUtf8("เสร็จสิ้น"), this);
	closeBtn->setStyleSheet("background-color: #2196F3; color: white; font-weight: bold; padding: 10px; border-radius: 6px; font-size: 15px;");
	connect(closeBtn, &QPushButton::clicked, this, &QDialog::accept);
	mainLayout->addWidget(closeBtn);
}

void TranslatorMenuDialog::UpdateDialogStyle()
{
	setStyleSheet("QDialog {"
				  "  background-color: #121216;"
				  "  color: #ECEFF1;"
				  "  font-family: 'Segoe UI', 'Sarabun', sans-serif;"
				  "  font-size: 13px;"
				  "}"
				  "QGroupBox {"
				  "  border: 1px solid #33333C;"
				  "  border-radius: 8px;"
				  "  margin-top: 10px;"
				  "  padding-top: 10px;"
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
