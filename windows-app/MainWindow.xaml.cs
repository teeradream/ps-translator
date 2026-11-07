using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Web;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Windows.Globalization;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;

namespace PSTranslatorWin
{
    public class AppConfig
    {
        public double CropLeft { get; set; } = 320;
        public double CropTop { get; set; } = 740;
        public double CropWidth { get; set; } = 860;
        public double CropHeight { get; set; } = 160;
        public double OverlayLeft { get; set; } = 280;
        public double OverlayTop { get; set; } = 60;
        public string VoiceGenderMode { get; set; } = "AUTO_AI";
        public float SpeechSpeedRate { get; set; } = 1.25f;
        public bool IsTtsEnabled { get; set; } = true;
        public bool IsOverlayEnabled { get; set; } = true;
        public bool FilterShortUi { get; set; } = true;
        public string SavedPsnId { get; set; } = "";
    }

    public partial class MainWindow : Window
    {
        private readonly CropBoxWindow _cropWindow;
        private readonly SubtitleOverlayWindow _overlayWindow;
        private readonly DispatcherTimer _ocrTimer;
        private readonly DispatcherTimer _hideSubtitleTimer;
        private readonly HttpClient _http = new HttpClient();
        private OcrEngine? _ocrEngine;
        private AppConfig _config = new AppConfig();
        private readonly string _configPath;

        private bool _isRunning = true;
        private bool _isBusyOcr = false;
        private bool _isBlackScreenMode = false;
        private string _lastRecognizedEnglish = "";

        public MainWindow()
        {
            InitializeComponent();

            _configPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "ps_translator_config.json");
            LoadConfig();

            _cropWindow = new CropBoxWindow();
            _cropWindow.Left = _config.CropLeft;
            _cropWindow.Top = _config.CropTop;
            _cropWindow.Width = _config.CropWidth;
            _cropWindow.Height = _config.CropHeight;
            _cropWindow.OnCropSaved += SaveCurrentPositions;

            _overlayWindow = new SubtitleOverlayWindow();
            _overlayWindow.Left = _config.OverlayLeft;
            _overlayWindow.Top = _config.OverlayTop;
            if (_config.IsOverlayEnabled)
            {
                _overlayWindow.Show();
            }

            InitializeWindowsOcr();
            ApplyConfigToUi();

            _hideSubtitleTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(4500)
            };
            _hideSubtitleTimer.Tick += (_, _) =>
            {
                _hideSubtitleTimer.Stop();
                if (!_isBlackScreenMode)
                {
                    _overlayWindow.Opacity = 0.0;
                }
            };

            _ocrTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(550)
            };
            _ocrTimer.Tick += async (_, _) => await CaptureAndProcessSubtitleAsync();
            _ocrTimer.Start();
        }

        private void InitializeWindowsOcr()
        {
            try
            {
                var enLang = new Language("en-US");
                if (OcrEngine.IsLanguageSupported(enLang))
                {
                    _ocrEngine = OcrEngine.TryCreateFromLanguage(enLang);
                }
                if (_ocrEngine == null)
                {
                    _ocrEngine = OcrEngine.TryCreateFromUserProfileLanguages();
                }
            }
            catch { }
        }

        private void LoadConfig()
        {
            try
            {
                if (File.Exists(_configPath))
                {
                    string json = File.ReadAllText(_configPath);
                    var loaded = JsonSerializer.Deserialize<AppConfig>(json);
                    if (loaded != null) _config = loaded;
                }
                else
                {
                    // Center default positions based on primary screen
                    double sw = SystemParameters.PrimaryScreenWidth;
                    double sh = SystemParameters.PrimaryScreenHeight;
                    _config.CropWidth = Math.Round(sw * 0.62);
                    _config.CropHeight = Math.Round(sh * 0.18);
                    _config.CropLeft = Math.Round((sw - _config.CropWidth) / 2.0);
                    _config.CropTop = Math.Round(sh * 0.74);

                    _config.OverlayLeft = Math.Round((sw - 960) / 2.0);
                    _config.OverlayTop = Math.Round(sh * 0.05);
                }
            }
            catch { }
        }

        private void SaveCurrentPositions()
        {
            try
            {
                _config.CropLeft = _cropWindow.Left;
                _config.CropTop = _cropWindow.Top;
                _config.CropWidth = _cropWindow.Width;
                _config.CropHeight = _cropWindow.Height;
                if (!_isBlackScreenMode)
                {
                    _config.OverlayLeft = _overlayWindow.Left;
                    _config.OverlayTop = _overlayWindow.Top;
                }
                string json = JsonSerializer.Serialize(_config, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_configPath, json);
            }
            catch { }
        }

        private void ApplyConfigToUi()
        {
            ChkShowOverlay.IsChecked = _config.IsOverlayEnabled;
            ChkTtsEnabled.IsChecked = _config.IsTtsEnabled;
            ChkFilterShort.IsChecked = _config.FilterShortUi;
            TxtPsnId.Text = _config.SavedPsnId;

            UpdateVoiceButtonsHighlight();
            UpdateSpeedButtonsHighlight();
        }

        private void SetButtonHighlight(System.Windows.Controls.Button btn, bool active)
        {
            if (active)
            {
                btn.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(46, 125, 50)); // #2E7D32
                btn.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 230, 118)); // #00E676
                btn.Foreground = System.Windows.Media.Brushes.White;
            }
            else
            {
                btn.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(37, 42, 52)); // #252A34
                btn.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromArgb(85, 255, 255, 255));
                btn.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(187, 187, 187));
            }
        }

        private void UpdateVoiceButtonsHighlight()
        {
            SetButtonHighlight(BtnVoiceAuto, _config.VoiceGenderMode == "AUTO_AI");
            SetButtonHighlight(BtnVoiceFemale, _config.VoiceGenderMode == "FEMALE");
            SetButtonHighlight(BtnVoiceMale, _config.VoiceGenderMode == "MALE");
        }

        private void UpdateSpeedButtonsHighlight()
        {
            SetButtonHighlight(BtnSpeedNormal, _config.SpeechSpeedRate <= 1.05f);
            SetButtonHighlight(BtnSpeedFast, _config.SpeechSpeedRate > 1.05f && _config.SpeechSpeedRate < 1.45f);
            SetButtonHighlight(BtnSpeedVeryFast, _config.SpeechSpeedRate >= 1.45f);
        }

        private async Task CaptureAndProcessSubtitleAsync()
        {
            if (!_isRunning || _isBusyOcr || _ocrEngine == null) return;
            _isBusyOcr = true;

            try
            {
                // Ensure we have valid crop coordinates even when CropWindow is hidden
                var source = PresentationSource.FromVisual(this);
                double scaleX = 1.0, scaleY = 1.0;
                if (source?.CompositionTarget != null)
                {
                    System.Windows.Media.Matrix m = source.CompositionTarget.TransformToDevice;
                    scaleX = m.M11;
                    scaleY = m.M22;
                }

                double cLeft = _cropWindow.IsVisible ? _cropWindow.Left : _config.CropLeft;
                double cTop = _cropWindow.IsVisible ? _cropWindow.Top : _config.CropTop;
                double cWidth = _cropWindow.IsVisible ? _cropWindow.ActualWidth : _config.CropWidth;
                double cHeight = _cropWindow.IsVisible ? _cropWindow.ActualHeight : _config.CropHeight;

                int x = Math.Max(0, (int)Math.Round(cLeft * scaleX));
                int y = Math.Max(0, (int)Math.Round(cTop * scaleY));
                int w = Math.Max(120, (int)Math.Round(cWidth * scaleX));
                int h = Math.Max(45, (int)Math.Round(cHeight * scaleY));

                using var rawBmp = new System.Drawing.Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                using (var g = System.Drawing.Graphics.FromImage(rawBmp))
                {
                    g.CopyFromScreen(x, y, 0, 0, new System.Drawing.Size(w, h), System.Drawing.CopyPixelOperation.SourceCopy);
                }

                // 2x High-Quality Bicubic Upscaling so small subtitle characters ('c' vs 'e') are ultra-sharp
                using var scaledBmp = new System.Drawing.Bitmap(w * 2, h * 2, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                using (var g2 = System.Drawing.Graphics.FromImage(scaledBmp))
                {
                    g2.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                    g2.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.HighQuality;
                    g2.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
                    g2.DrawImage(rawBmp, new System.Drawing.Rectangle(0, 0, w * 2, h * 2));
                }

                using var ms = new MemoryStream();
                scaledBmp.Save(ms, System.Drawing.Imaging.ImageFormat.Bmp);
                ms.Position = 0;

                var winrtStream = ms.AsRandomAccessStream();
                var decoder = await BitmapDecoder.CreateAsync(winrtStream);
                using var softwareBmp = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);

                var ocrResult = await _ocrEngine.RecognizeAsync(softwareBmp);
                string rawText = ocrResult?.Text?.Trim().Replace("\r", " ").Replace("\n", " ") ?? "";
                string fixedText = FixOcrText(rawText);
                string cleanText = Regex.Replace(fixedText, @"\s+", " ").Trim();

                if (!string.IsNullOrEmpty(cleanText) && IsDialogueSubtitle(cleanText))
                {
                    if (!IsSimilarText(cleanText, _lastRecognizedEnglish))
                    {
                        _lastRecognizedEnglish = cleanText;
                        string thai = await TranslateToThaiAsync(cleanText);

                        TxtStatusPreview.Text = $"{cleanText}\n-> {thai}";
                        _overlayWindow.SetSubtitle(cleanText, thai);

                        if (_config.IsOverlayEnabled)
                        {
                            if (!_overlayWindow.IsVisible) _overlayWindow.Show();
                            _overlayWindow.Opacity = 1.0;
                            _hideSubtitleTimer.Stop();
                            _hideSubtitleTimer.Start();
                        }

                        if (_config.IsTtsEnabled)
                        {
                            bool isFemale = _config.VoiceGenderMode switch
                            {
                                "FEMALE" => true,
                                "MALE" => false,
                                _ => IsFemaleCharacter(cleanText, thai)
                            };
                            _ = AudioTtsHelper.SpeakThaiAsync(thai, isFemale, _config.SpeechSpeedRate);
                        }
                    }
                }
            }
            catch { }
            finally
            {
                _isBusyOcr = false;
            }
        }

        private async Task<string> TranslateToThaiAsync(string englishText)
        {
            try
            {
                string url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=th&dt=t&q=" + HttpUtility.UrlEncode(englishText);
                _http.DefaultRequestHeaders.Remove("User-Agent");
                _http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0");
                string res = await _http.GetStringAsync(url);
                using var doc = JsonDocument.Parse(res);
                var sentences = doc.RootElement[0];
                var sb = new System.Text.StringBuilder();
                foreach (var item in sentences.EnumerateArray())
                {
                    if (item.GetArrayLength() > 0 && item[0].ValueKind == JsonValueKind.String)
                    {
                        sb.Append(item[0].GetString());
                    }
                }
                string result = sb.ToString().Trim();
                return string.IsNullOrEmpty(result) ? englishText : result;
            }
            catch
            {
                return englishText;
            }
        }

        private bool IsDialogueSubtitle(string text)
        {
            if (!_config.FilterShortUi) return text.Length >= 3 && text.Any(char.IsLetter);
            var words = text.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (words.Length < 2 && text.Length < 8) return false;

            string lower = text.ToLowerInvariant();
            string[] prompts = { "talk", "skip", "attack", "dodge", "interact", "examine", "open", "back", "next", "continue", "menu", "cancel", "r1", "r2", "l1", "l2", "r3", "l3" };
            if (words.Length == 1 && prompts.Contains(lower)) return false;
            return text.Any(char.IsLetter);
        }

        private bool IsSimilarText(string a, string b)
        {
            if (string.Equals(a, b, StringComparison.OrdinalIgnoreCase)) return true;
            string cleanA = Regex.Replace(a.ToLowerInvariant(), @"[^a-z0-9 ]", "").Trim();
            string cleanB = Regex.Replace(b.ToLowerInvariant(), @"[^a-z0-9 ]", "").Trim();
            if (cleanA == cleanB) return true;
            int maxLen = Math.Max(cleanA.Length, cleanB.Length);
            if (maxLen == 0) return true;
            int dist = LevenshteinDistance(cleanA, cleanB);
            return (1.0 - (double)dist / maxLen) >= 0.75;
        }

        private int LevenshteinDistance(string s1, string s2)
        {
            int[] costs = new int[s2.Length + 1];
            for (int i = 0; i <= s2.Length; i++) costs[i] = i;
            for (int i = 1; i <= s1.Length; i++)
            {
                costs[0] = i;
                int nw = i - 1;
                for (int j = 1; j <= s2.Length; j++)
                {
                    int cj = Math.Min(1 + Math.Min(costs[j], costs[j - 1]), s1[i - 1] == s2[j - 1] ? nw : nw + 1);
                    nw = costs[j];
                    costs[j] = cj;
                }
            }
            return costs[s2.Length];
        }

        private bool IsFemaleCharacter(string englishText, string thaiText)
        {
            string engLower = englishText.ToLowerInvariant();
            string thaiLower = thaiText.ToLowerInvariant();
            int femaleScore = 0, maleScore = 0;

            string[] femaleWords = { "she", "her", "hers", "lady", "milady", "girl", "woman", "women", "sister", "mother", "queen", "princess", "madam", "miss", "mrs", "daughter", "wife", "aunt", "witch", "goddess", "priestess", "empress" };
            foreach (var w in femaleWords)
                if (Regex.IsMatch(engLower, $@"\b{w}\b")) femaleScore += 2;

            if (thaiLower.Contains("ค่ะ") || thaiLower.Contains("คะ") || thaiLower.Contains("ดิฉัน") || thaiLower.Contains("เจ้าแม่") || thaiLower.Contains("นาง"))
                femaleScore += 3;

            string[] maleWords = { "he", "him", "his", "man", "men", "boy", "sir", "lord", "milord", "brother", "father", "king", "prince", "master", "mr", "uncle", "son", "monk", "daoist", "fellow", "lad", "warrior", "emperor" };
            foreach (var w in maleWords)
                if (Regex.IsMatch(engLower, $@"\b{w}\b")) maleScore += 2;

            if (thaiLower.Contains("ครับ") || thaiLower.Contains("ผม") || thaiLower.Contains("กระผม") || thaiLower.Contains("เจ้าหนุ่ม") || thaiLower.Contains("ศิษย์น้อง"))
                maleScore += 3;

            return femaleScore > maleScore;
        }

        private string FixOcrText(string text)
        {
            string result = text;
            var replacements = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["eould"] = "could", ["eouldn't"] = "couldn't", ["ean"] = "can", ["eannot"] = "cannot", ["ean't"] = "can't",
                ["eome"] = "come", ["eomes"] = "comes", ["eoming"] = "coming", ["eame"] = "came", ["eall"] = "call",
                ["ealled"] = "called", ["ealling"] = "calling", ["eare"] = "care", ["eareful"] = "careful", ["earefully"] = "carefully",
                ["eatch"] = "catch", ["eause"] = "cause", ["eertain"] = "certain", ["eertainly"] = "certainly",
                ["ehance"] = "chance", ["ehoose"] = "choose", ["ehoice"] = "choice", ["eity"] = "city",
                ["elear"] = "clear", ["elearly"] = "clearly", ["elose"] = "close", ["elosed"] = "closed",
                ["eold"] = "cold", ["eontrol"] = "control", ["eontinue"] = "continue", ["eorrect"] = "correct",
                ["eount"] = "count", ["eountry"] = "country", ["eourse"] = "course", ["eover"] = "cover",
                ["eraft"] = "craft", ["ereature"] = "creature", ["erime"] = "crime", ["ery"] = "cry",
                ["eurse"] = "curse", ["eut"] = "cut", ["eaptain"] = "captain", ["eenter"] = "center",
                ["ehild"] = "child", ["ehildren"] = "children", ["elimb"] = "climb", ["eloak"] = "cloak",
                ["eloud"] = "cloud", ["eommon"] = "common", ["eomplete"] = "complete", ["eoncern"] = "concern",
                ["eonquer"] = "conquer", ["eorner"] = "corner", ["eost"] = "cost", ["erazy"] = "crazy",
                ["eross"] = "cross", ["erown"] = "crown", ["eruel"] = "cruel", ["eure"] = "cure",
                ["eurious"] = "curious", ["eurrent"] = "current", ["eustom"] = "custom",
                ["beoome"] = "become", ["beeome"] = "become", ["beoause"] = "because", ["beeause"] = "because",
                ["plaee"] = "place", ["voiee"] = "voice", ["faee"] = "face", ["graee"] = "grace",
                ["spaee"] = "space", ["peaee"] = "peace", ["pieee"] = "piece", ["notiee"] = "notice",
                ["foree"] = "force", ["proteet"] = "protect", ["direet"] = "direct", ["perfeet"] = "perfect",
                ["seeret"] = "secret", ["effeet"] = "effect", ["respeet"] = "respect", ["expeet"] = "expect"
            };

            foreach (var kvp in replacements)
            {
                result = Regex.Replace(result, $@"\b{Regex.Escape(kvp.Key)}\b", m =>
                {
                    string orig = m.Value;
                    if (!string.IsNullOrEmpty(orig) && char.IsUpper(orig[0]))
                        return char.ToUpper(kvp.Value[0]) + kvp.Value.Substring(1);
                    return kvp.Value;
                }, RegexOptions.IgnoreCase);
            }
            return result;
        }

        private void BtnToggleCapture_Click(object sender, RoutedEventArgs e)
        {
            _isRunning = !_isRunning;
            if (_isRunning)
            {
                BtnToggleCapture.Content = "เริ่มระบบแปลภาษาอัตโนมัติ (กำลังทำงาน)";
                BtnToggleCapture.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(46, 125, 50));
                BtnToggleCapture.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 230, 118));
            }
            else
            {
                BtnToggleCapture.Content = "หยุดพักการแปลภาษาชั่วคราว (คลิกเพื่อเริ่มต่อ)";
                BtnToggleCapture.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(183, 28, 28));
                BtnToggleCapture.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(255, 82, 82));
            }
        }

        private void BtnShowCropBox_Click(object sender, RoutedEventArgs e)
        {
            if (_cropWindow.IsVisible)
            {
                SaveCurrentPositions();
                _cropWindow.Hide();
            }
            else
            {
                _cropWindow.Show();
                _cropWindow.Activate();
            }
        }

        private void BtnResetCropBox_Click(object sender, RoutedEventArgs e)
        {
            double sw = SystemParameters.PrimaryScreenWidth;
            double sh = SystemParameters.PrimaryScreenHeight;
            _cropWindow.Width = Math.Round(sw * 0.62);
            _cropWindow.Height = Math.Round(sh * 0.18);
            _cropWindow.Left = Math.Round((sw - _cropWindow.Width) / 2.0);
            _cropWindow.Top = Math.Round(sh * 0.74);
            _cropWindow.Show();
            SaveCurrentPositions();
        }

        private void ChkShowOverlay_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.IsOverlayEnabled = ChkShowOverlay.IsChecked == true;
            if (_config.IsOverlayEnabled)
            {
                _overlayWindow.Show();
                _overlayWindow.Opacity = 1.0;
            }
            else
            {
                _overlayWindow.Hide();
            }
            SaveCurrentPositions();
        }

        private void ChkFilterShort_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.FilterShortUi = ChkFilterShort.IsChecked == true;
            SaveCurrentPositions();
        }

        private void BtnBlackScreenMode_Click(object sender, RoutedEventArgs e)
        {
            _isBlackScreenMode = !_isBlackScreenMode;
            _overlayWindow.SetBlackScreenMode(_isBlackScreenMode);
            if (_isBlackScreenMode)
            {
                _overlayWindow.Show();
                _overlayWindow.Opacity = 1.0;
                BtnBlackScreenMode.Content = "ออกจากโหมดจอดำ (กลับสู่โหมดกรอบซับลอย)";
                BtnBlackScreenMode.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(46, 125, 50));
                BtnBlackScreenMode.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 230, 118));
            }
            else
            {
                _overlayWindow.Left = _config.OverlayLeft;
                _overlayWindow.Top = _config.OverlayTop;
                BtnBlackScreenMode.Content = "โหมดโชว์เฉพาะตัวหนังสือ (จอดำเต็มจอ)";
                BtnBlackScreenMode.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(37, 42, 52));
                BtnBlackScreenMode.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromArgb(85, 255, 255, 255));
            }
        }

        private void ChkTtsEnabled_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.IsTtsEnabled = ChkTtsEnabled.IsChecked == true;
            if (!_config.IsTtsEnabled) AudioTtsHelper.StopCurrentPlayback();
            SaveCurrentPositions();
        }

        private void BtnVoiceAuto_Click(object sender, RoutedEventArgs e)
        {
            _config.VoiceGenderMode = "AUTO_AI";
            UpdateVoiceButtonsHighlight();
            SaveCurrentPositions();
        }

        private void BtnVoiceFemale_Click(object sender, RoutedEventArgs e)
        {
            _config.VoiceGenderMode = "FEMALE";
            UpdateVoiceButtonsHighlight();
            SaveCurrentPositions();
            _ = AudioTtsHelper.SpeakThaiAsync("เลือกเสียงพากย์ผู้หญิงเรียบร้อยแล้วค่ะ", true, _config.SpeechSpeedRate);
        }

        private void BtnVoiceMale_Click(object sender, RoutedEventArgs e)
        {
            _config.VoiceGenderMode = "MALE";
            UpdateVoiceButtonsHighlight();
            SaveCurrentPositions();
            _ = AudioTtsHelper.SpeakThaiAsync("เลือกเสียงพากย์ผู้ชายเรียบร้อยแล้วครับ", false, _config.SpeechSpeedRate);
        }

        private void BtnSpeedNormal_Click(object sender, RoutedEventArgs e)
        {
            _config.SpeechSpeedRate = 1.0f;
            UpdateSpeedButtonsHighlight();
            SaveCurrentPositions();
        }

        private void BtnSpeedFast_Click(object sender, RoutedEventArgs e)
        {
            _config.SpeechSpeedRate = 1.25f;
            UpdateSpeedButtonsHighlight();
            SaveCurrentPositions();
        }

        private void BtnSpeedVeryFast_Click(object sender, RoutedEventArgs e)
        {
            _config.SpeechSpeedRate = 1.5f;
            UpdateSpeedButtonsHighlight();
            SaveCurrentPositions();
        }

        private void TxtPsnId_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.SavedPsnId = TxtPsnId.Text.Trim();
            SaveCurrentPositions();
        }

        private void BtnCopyPsnId_Click(object sender, RoutedEventArgs e)
        {
            if (!string.IsNullOrWhiteSpace(TxtPsnId.Text))
            {
                System.Windows.Clipboard.SetText(TxtPsnId.Text.Trim());
                System.Windows.MessageBox.Show("คัดลอก PSN Account ID เรียบร้อยแล้ว สามารถนำไปวางในหน้าลงทะเบียน Chiaki ได้เลยครับ", "PS Translator", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        private void BtnLaunchChiaki_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string[] possiblePaths =
                {
                    Path.Combine(baseDir, "Chiaki", "chiaki.exe"),
                    Path.Combine(baseDir, "Chiaki", "chiaki-ng.exe"),
                    Path.Combine(baseDir, "chiaki.exe"),
                    Path.Combine(baseDir, "chiaki-ng.exe")
                };

                string? foundExe = possiblePaths.FirstOrDefault(File.Exists);
                if (foundExe != null)
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = foundExe,
                        WorkingDirectory = Path.GetDirectoryName(foundExe)
                    });
                }
                else
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = "https://github.com/chiaki-ng/chiaki-ng/releases/latest",
                        UseShellExecute = true
                    });
                }
            }
            catch { }
        }

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            SaveCurrentPositions();
            _ocrTimer.Stop();
            AudioTtsHelper.StopCurrentPlayback();
            _cropWindow.Close();
            _overlayWindow.Close();
        }
    }
}
