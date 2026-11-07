using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Web;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using NAudio.CoreAudioApi;
using Windows.Globalization;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;

namespace PSTranslatorWin
{
    public class AppConfig
    {
        // Normalized crop coordinates (0.0 to 1.0) inside the video viewport
        public double CropLeftRatio { get; set; } = 0.16;
        public double CropTopRatio { get; set; } = 0.74;
        public double CropWidthRatio { get; set; } = 0.68;
        public double CropHeightRatio { get; set; } = 0.18;

        // Subtitle appearance inside window
        public double ThaiFontSize { get; set; } = 28;
        public double EngFontSize { get; set; } = 16;
        public string ThaiTextColorHex { get; set; } = "#FFD54F";
        public string EngTextColorHex { get; set; } = "#CCCCCC";
        public bool ShowEnglishText { get; set; } = true;
        public double SubtitleBgOpacityPercent { get; set; } = 85;
        public double SubtitleBottomMargin { get; set; } = 32;

        // Display & Aspect Ratio
        public string AspectMode { get; set; } = "FIT"; // "16_9", "FIT", "STRETCH"

        // Voice & OCR Settings
        public string VoiceGenderMode { get; set; } = "AUTO_AI";
        public float SpeechSpeedRate { get; set; } = 1.25f;
        public bool IsTtsEnabled { get; set; } = true;
        public bool FilterShortUi { get; set; } = true;
        public string SavedPsnId { get; set; } = "";
    }

    public class CaptureTargetItem
    {
        public string Title { get; set; } = "";
        public IntPtr Hwnd { get; set; } = IntPtr.Zero;
        public uint ProcessId { get; set; } = 0;
        public bool IsDesktopFallback => Hwnd == IntPtr.Zero;
        public override string ToString() => Title;
    }

    public partial class MainWindow : Window
    {
        #region Win32 Interop
        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc enumProc, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder strText, int maxCount);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowTextLength(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll")]
        private static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);

        [DllImport("user32.dll")]
        private static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint nFlags);

        [DllImport("user32.dll")]
        private static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint dwAffinity);

        private const uint WDA_NONE = 0x00000000;
        private const uint WDA_EXCLUDEFROMCAPTURE = 0x00000011;
        private const uint PW_RENDERFULLCONTENT = 0x00000002;

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }
        #endregion

        private readonly DispatcherTimer _videoRenderTimer;
        private readonly DispatcherTimer _ocrTimer;
        private readonly HttpClient _http = new HttpClient();
        private OcrEngine? _ocrEngine;
        private AppConfig _config = new AppConfig();
        private readonly string _configPath;

        private bool _isRunning = true;
        private bool _isBusyOcr = false;
        private bool _isBlackScreenMode = false;
        private bool _isFullscreen = false;
        private bool _isGameAudioMuted = false;
        private string _lastRecognizedEnglish = "";

        private System.Drawing.Bitmap? _latestCapturedFrame;
        private readonly object _frameLock = new object();

        // In-Window Crop Box Drag/Resize State
        private bool _isDraggingCrop = false;
        private bool _isResizingCrop = false;
        private System.Windows.Point _cropDragStartMouse;
        private double _cropStartLeft, _cropStartTop, _cropStartW, _cropStartH;

        // In-Window Subtitle Vertical Drag State
        private bool _isDraggingSubtitle = false;
        private System.Windows.Point _subDragStartMouse;
        private double _subStartBottomMargin;

        public MainWindow()
        {
            InitializeComponent();

            _configPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "ps_translator_config.json");
            LoadConfig();
            InitializeWindowsOcr();
            ApplyConfigToUi();

            Loaded += (_, _) =>
            {
                RefreshCaptureTargets(autoSelectPs5: true);
                SyncCropBoxPixelsFromRatios();
                UpdateDisplayAffinity();
            };

            // 30 FPS smooth live viewport timer inside our single window
            _videoRenderTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(35)
            };
            _videoRenderTimer.Tick += (_, _) => RenderLiveViewportFrame();
            _videoRenderTimer.Start();

            // Real-time OCR timer (every 480ms)
            _ocrTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(480)
            };
            _ocrTimer.Tick += async (_, _) => await ProcessOcrFromLatestFrameAsync();
            _ocrTimer.Start();
        }

        private void UpdateDisplayAffinity()
        {
            try
            {
                IntPtr myHwnd = new WindowInteropHelper(this).Handle;
                if (myHwnd != IntPtr.Zero)
                {
                    var selected = CmbCaptureTarget.SelectedItem as CaptureTargetItem;
                    // When capturing whole desktop fallback, exclude our own window so it never mirrors itself
                    uint affinity = (selected == null || selected.IsDesktopFallback) ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE;
                    SetWindowDisplayAffinity(myHwnd, affinity);
                }
            }
            catch { }
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
            }
            catch { }
        }

        private void SaveConfig()
        {
            try
            {
                string json = JsonSerializer.Serialize(_config, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_configPath, json);
            }
            catch { }
        }

        private void ApplyConfigToUi()
        {
            SliderThaiFontSize.Value = _config.ThaiFontSize;
            SliderEngFontSize.Value = _config.EngFontSize;
            ChkShowEnglish.IsChecked = _config.ShowEnglishText;
            SliderBgOpacity.Value = _config.SubtitleBgOpacityPercent;
            ChkTtsEnabled.IsChecked = _config.IsTtsEnabled;
            ChkFilterShort.IsChecked = _config.FilterShortUi;
            TxtPsnId.Text = _config.SavedPsnId;

            InWindowSubtitleBox.Margin = new Thickness(40, 20, 40, _config.SubtitleBottomMargin);

            ApplySubtitleStyling();
            ApplyAspectRatioMode();
            UpdateVoiceButtonsHighlight();
            UpdateSpeedButtonsHighlight();
        }

        private void ApplySubtitleStyling()
        {
            if (TranslatedSubtitleText == null || OriginalSubtitleText == null || InWindowSubtitleBox == null) return;

            TranslatedSubtitleText.FontSize = _config.ThaiFontSize;
            OriginalSubtitleText.FontSize = _config.EngFontSize;
            OriginalSubtitleText.Visibility = _config.ShowEnglishText ? Visibility.Visible : Visibility.Collapsed;

            if (TxtThaiFontSizeVal != null) TxtThaiFontSizeVal.Text = $"{(int)_config.ThaiFontSize} px";
            if (TxtEngFontSizeVal != null) TxtEngFontSizeVal.Text = $"{(int)_config.EngFontSize} px";
            if (TxtBgOpacityVal != null) TxtBgOpacityVal.Text = $"{(int)_config.SubtitleBgOpacityPercent}%";

            try
            {
                var color = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(_config.ThaiTextColorHex);
                TranslatedSubtitleText.Foreground = new SolidColorBrush(color);
            }
            catch
            {
                TranslatedSubtitleText.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(255, 213, 79));
            }

            try
            {
                var engColor = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(_config.EngTextColorHex);
                OriginalSubtitleText.Foreground = new SolidColorBrush(engColor);
            }
            catch
            {
                OriginalSubtitleText.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(204, 204, 204));
            }

            byte alpha = (byte)Math.Clamp((int)Math.Round(_config.SubtitleBgOpacityPercent * 2.55), 0, 255);
            InWindowSubtitleBox.Background = new SolidColorBrush(System.Windows.Media.Color.FromArgb(alpha, 8, 10, 15));
            InWindowSubtitleBox.BorderBrush = alpha < 15
                ? System.Windows.Media.Brushes.Transparent
                : new SolidColorBrush(System.Windows.Media.Color.FromArgb(110, 0, 230, 118));

            UpdateColorButtonsHighlight();
        }

        private void SetButtonHighlight(Button btn, bool active)
        {
            if (btn == null) return;
            if (active)
            {
                btn.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(46, 125, 50)); // #2E7D32
                btn.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 230, 118)); // #00E676
                btn.BorderThickness = new Thickness(2);
                btn.Foreground = System.Windows.Media.Brushes.White;
            }
            else
            {
                btn.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(37, 42, 52)); // #252A34
                btn.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromArgb(85, 255, 255, 255));
                btn.BorderThickness = new Thickness(1);
                btn.Foreground = new SolidColorBrush(System.Windows.Media.Color.FromRgb(190, 190, 190));
            }
        }

        private void UpdateColorButtonsHighlight()
        {
            string c = _config.ThaiTextColorHex.ToUpperInvariant();
            SetButtonHighlight(BtnColorGold, c == "#FFD54F");
            SetButtonHighlight(BtnColorWhite, c == "#FFFFFF");
            SetButtonHighlight(BtnColorGreen, c == "#00E676");
            SetButtonHighlight(BtnColorCyan, c == "#40C4FF");
            SetButtonHighlight(BtnColorPink, c == "#FF80AB");
            SetButtonHighlight(BtnColorOrange, c == "#FFAB40");

            string ec = _config.EngTextColorHex.ToUpperInvariant();
            SetButtonHighlight(BtnEngColorGray, ec == "#CCCCCC");
            SetButtonHighlight(BtnEngColorWhite, ec == "#FFFFFF");
            SetButtonHighlight(BtnEngColorBlue, ec == "#80D8FF");
            SetButtonHighlight(BtnEngColorYellow, ec == "#FFF59D");
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

        private void ApplyAspectRatioMode()
        {
            if (GameVideoImage == null) return;
            switch (_config.AspectMode)
            {
                case "STRETCH":
                    GameVideoImage.Stretch = Stretch.Fill;
                    VideoViewportContainer.ClearValue(FrameworkElement.MaxWidthProperty);
                    VideoViewportContainer.ClearValue(FrameworkElement.MaxHeightProperty);
                    break;
                case "16_9":
                    GameVideoImage.Stretch = Stretch.Uniform;
                    break;
                default: // FIT
                    GameVideoImage.Stretch = Stretch.Uniform;
                    break;
            }
            SetButtonHighlight(BtnAspect169, _config.AspectMode == "16_9");
            SetButtonHighlight(BtnAspectFit, _config.AspectMode == "FIT");
            SetButtonHighlight(BtnAspectStretch, _config.AspectMode == "STRETCH");
        }

        #region Capture Target Discovery & Live In-Window Stream Rendering
        private void RefreshCaptureTargets(bool autoSelectPs5)
        {
            IntPtr myHwnd = new WindowInteropHelper(this).Handle;
            var items = new List<CaptureTargetItem>();

            EnumWindows((hWnd, _) =>
            {
                if (hWnd == myHwnd || !IsWindowVisible(hWnd)) return true;
                int len = GetWindowTextLength(hWnd);
                if (len <= 0) return true;

                var sb = new StringBuilder(len + 1);
                GetWindowText(hWnd, sb, sb.Capacity);
                string title = sb.ToString().Trim();
                if (string.IsNullOrEmpty(title) || title == "Program Manager" || title == "Settings") return true;

                GetClientRect(hWnd, out RECT rc);
                if ((rc.Right - rc.Left) < 240 || (rc.Bottom - rc.Top) < 160) return true;

                GetWindowThreadProcessId(hWnd, out uint pid);
                items.Add(new CaptureTargetItem
                {
                    Title = title,
                    Hwnd = hWnd,
                    ProcessId = pid
                });
                return true;
            }, IntPtr.Zero);

            items.Add(new CaptureTargetItem
            {
                Title = "หน้าจอหลัก (Desktop Screen)",
                Hwnd = IntPtr.Zero,
                ProcessId = 0
            });

            CmbCaptureTarget.ItemsSource = items;

            if (autoSelectPs5)
            {
                var ps5Win = items.FirstOrDefault(i =>
                    i.Title.Contains("Chiaki", StringComparison.OrdinalIgnoreCase) ||
                    i.Title.Contains("Remote Play", StringComparison.OrdinalIgnoreCase) ||
                    i.Title.Contains("PS5", StringComparison.OrdinalIgnoreCase) ||
                    i.Title.Contains("PlayStation", StringComparison.OrdinalIgnoreCase));

                if (ps5Win != null)
                {
                    CmbCaptureTarget.SelectedItem = ps5Win;
                    return;
                }
            }

            if (CmbCaptureTarget.SelectedIndex < 0 && items.Count > 0)
            {
                CmbCaptureTarget.SelectedIndex = items.Count - 1; // Default to Desktop Screen
            }
        }

        private void CmbCaptureTarget_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            UpdateDisplayAffinity();
        }

        private void BtnRefreshTargets_Click(object sender, RoutedEventArgs e)
        {
            RefreshCaptureTargets(autoSelectPs5: true);
        }

        private void RenderLiveViewportFrame()
        {
            if (!_isRunning) return;

            try
            {
                var target = CmbCaptureTarget.SelectedItem as CaptureTargetItem;
                System.Drawing.Bitmap? frame = null;

                if (target != null && !target.IsDesktopFallback && IsWindowVisible(target.Hwnd))
                {
                    GetClientRect(target.Hwnd, out RECT rc);
                    int w = Math.Max(320, rc.Right - rc.Left);
                    int h = Math.Max(180, rc.Bottom - rc.Top);
                    frame = new System.Drawing.Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                    using (var g = System.Drawing.Graphics.FromImage(frame))
                    {
                        IntPtr hdc = g.GetHdc();
                        PrintWindow(target.Hwnd, hdc, PW_RENDERFULLCONTENT);
                        g.ReleaseHdc(hdc);
                    }
                }
                else
                {
                    var source = PresentationSource.FromVisual(this);
                    double scaleX = source?.CompositionTarget?.TransformToDevice.M11 ?? 1.0;
                    double scaleY = source?.CompositionTarget?.TransformToDevice.M22 ?? 1.0;
                    int sw = Math.Max(640, (int)Math.Round(SystemParameters.PrimaryScreenWidth * scaleX));
                    int sh = Math.Max(360, (int)Math.Round(SystemParameters.PrimaryScreenHeight * scaleY));

                    frame = new System.Drawing.Bitmap(sw, sh, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                    using (var g = System.Drawing.Graphics.FromImage(frame))
                    {
                        g.CopyFromScreen(0, 0, 0, 0, new System.Drawing.Size(sw, sh), System.Drawing.CopyPixelOperation.SourceCopy);
                    }
                }

                if (frame != null)
                {
                    if (!_isBlackScreenMode)
                    {
                        GameVideoImage.Source = ConvertBitmapToBitmapSource(frame);
                        GameVideoImage.Visibility = Visibility.Visible;
                    }
                    else
                    {
                        GameVideoImage.Visibility = Visibility.Collapsed;
                    }

                    lock (_frameLock)
                    {
                        _latestCapturedFrame?.Dispose();
                        _latestCapturedFrame = frame;
                    }
                }
            }
            catch { }
        }

        private static BitmapSource ConvertBitmapToBitmapSource(System.Drawing.Bitmap bitmap)
        {
            var rect = new System.Drawing.Rectangle(0, 0, bitmap.Width, bitmap.Height);
            var bmpData = bitmap.LockBits(rect, System.Drawing.Imaging.ImageLockMode.ReadOnly, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
            try
            {
                var source = BitmapSource.Create(
                    bmpData.Width,
                    bmpData.Height,
                    96,
                    96,
                    PixelFormats.Bgra32,
                    null,
                    bmpData.Scan0,
                    bmpData.Stride * bmpData.Height,
                    bmpData.Stride);
                source.Freeze();
                return source;
            }
            finally
            {
                bitmap.UnlockBits(bmpData);
            }
        }
        #endregion

        #region Real-Time 2x Bicubic OCR & Smart c/e Correction
        private async Task ProcessOcrFromLatestFrameAsync()
        {
            if (!_isRunning || _isBusyOcr || _ocrEngine == null) return;

            System.Drawing.Bitmap? croppedRaw = null;
            lock (_frameLock)
            {
                if (_latestCapturedFrame == null) return;
                int fw = _latestCapturedFrame.Width;
                int fh = _latestCapturedFrame.Height;

                int cx = Math.Clamp((int)Math.Round(_config.CropLeftRatio * fw), 0, fw - 40);
                int cy = Math.Clamp((int)Math.Round(_config.CropTopRatio * fh), 0, fh - 20);
                int cw = Math.Clamp((int)Math.Round(_config.CropWidthRatio * fw), 40, fw - cx);
                int ch = Math.Clamp((int)Math.Round(_config.CropHeightRatio * fh), 20, fh - cy);

                croppedRaw = new System.Drawing.Bitmap(cw, ch, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                using (var g = System.Drawing.Graphics.FromImage(croppedRaw))
                {
                    g.DrawImage(_latestCapturedFrame,
                        new System.Drawing.Rectangle(0, 0, cw, ch),
                        new System.Drawing.Rectangle(cx, cy, cw, ch),
                        System.Drawing.GraphicsUnit.Pixel);
                }
            }

            if (croppedRaw == null) return;
            _isBusyOcr = true;

            try
            {
                using (croppedRaw)
                {
                    int w = croppedRaw.Width;
                    int h = croppedRaw.Height;

                    // 2x High-Quality Bicubic Upscaling for ultra-sharp small font recognition ('c' vs 'e')
                    using var scaledBmp = new System.Drawing.Bitmap(w * 2, h * 2, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                    using (var g2 = System.Drawing.Graphics.FromImage(scaledBmp))
                    {
                        g2.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                        g2.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.HighQuality;
                        g2.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
                        g2.DrawImage(croppedRaw, new System.Drawing.Rectangle(0, 0, w * 2, h * 2));
                    }

                    using var ms = new MemoryStream();
                    scaledBmp.Save(ms, System.Drawing.Imaging.ImageFormat.Bmp);
                    ms.Position = 0;

                    var winrtStream = ms.AsRandomAccessStream();
                    var decoder = await Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(winrtStream);
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

                            OriginalSubtitleText.Text = cleanText;
                            TranslatedSubtitleText.Text = thai;

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
                var sb = new StringBuilder();
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
        #endregion

        #region In-Window Green Crop Box Interaction
        private void Window_SizeChanged(object sender, SizeChangedEventArgs e)
        {
            SyncCropBoxPixelsFromRatios();
        }

        private void SyncCropBoxPixelsFromRatios()
        {
            double vw = Math.Max(300, VideoViewportContainer.ActualWidth);
            double vh = Math.Max(200, VideoViewportContainer.ActualHeight);

            Canvas.SetLeft(InWindowCropBorder, _config.CropLeftRatio * vw);
            Canvas.SetTop(InWindowCropBorder, _config.CropTopRatio * vh);
            InWindowCropBorder.Width = Math.Max(120, _config.CropWidthRatio * vw);
            InWindowCropBorder.Height = Math.Max(45, _config.CropHeightRatio * vh);
        }

        private void SyncRatiosFromCropBoxPixels()
        {
            double vw = Math.Max(300, VideoViewportContainer.ActualWidth);
            double vh = Math.Max(200, VideoViewportContainer.ActualHeight);

            _config.CropLeftRatio = Math.Clamp(Canvas.GetLeft(InWindowCropBorder) / vw, 0.0, 0.90);
            _config.CropTopRatio = Math.Clamp(Canvas.GetTop(InWindowCropBorder) / vh, 0.0, 0.90);
            _config.CropWidthRatio = Math.Clamp(InWindowCropBorder.Width / vw, 0.08, 1.0 - _config.CropLeftRatio);
            _config.CropHeightRatio = Math.Clamp(InWindowCropBorder.Height / vh, 0.05, 1.0 - _config.CropTopRatio);
            SaveConfig();
        }

        private void CropBorder_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.Source == CropResizeHandle) return;
            _isDraggingCrop = true;
            _cropDragStartMouse = e.GetPosition(CropOverlayCanvas);
            _cropStartLeft = Canvas.GetLeft(InWindowCropBorder);
            _cropStartTop = Canvas.GetTop(InWindowCropBorder);
            InWindowCropBorder.CaptureMouse();
            e.Handled = true;
        }

        private void CropResizeHandle_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            _isResizingCrop = true;
            _cropDragStartMouse = e.GetPosition(CropOverlayCanvas);
            _cropStartW = InWindowCropBorder.Width;
            _cropStartH = InWindowCropBorder.Height;
            CropResizeHandle.CaptureMouse();
            e.Handled = true;
        }

        private void CropCanvas_MouseMove(object sender, MouseEventArgs e)
        {
            if (!_isDraggingCrop && !_isResizingCrop) return;
            var pos = e.GetPosition(CropOverlayCanvas);
            double dx = pos.X - _cropDragStartMouse.X;
            double dy = pos.Y - _cropDragStartMouse.Y;
            double vw = VideoViewportContainer.ActualWidth;
            double vh = VideoViewportContainer.ActualHeight;

            if (_isDraggingCrop)
            {
                double newLeft = Math.Clamp(_cropStartLeft + dx, 0, Math.Max(0, vw - InWindowCropBorder.Width));
                double newTop = Math.Clamp(_cropStartTop + dy, 0, Math.Max(0, vh - InWindowCropBorder.Height));
                Canvas.SetLeft(InWindowCropBorder, newLeft);
                Canvas.SetTop(InWindowCropBorder, newTop);
            }
            else if (_isResizingCrop)
            {
                double left = Canvas.GetLeft(InWindowCropBorder);
                double top = Canvas.GetTop(InWindowCropBorder);
                double newW = Math.Clamp(_cropStartW + dx, 140, Math.Max(140, vw - left));
                double newH = Math.Clamp(_cropStartH + dy, 50, Math.Max(50, vh - top));
                InWindowCropBorder.Width = newW;
                InWindowCropBorder.Height = newH;
            }
        }

        private void CropCanvas_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            if (_isDraggingCrop || _isResizingCrop)
            {
                _isDraggingCrop = false;
                _isResizingCrop = false;
                InWindowCropBorder.ReleaseMouseCapture();
                CropResizeHandle.ReleaseMouseCapture();
                SyncRatiosFromCropBoxPixels();
            }
        }

        private void BtnShowCropBox_Click(object sender, RoutedEventArgs e)
        {
            if (CropOverlayCanvas.Visibility == Visibility.Visible)
            {
                SyncRatiosFromCropBoxPixels();
                CropOverlayCanvas.Visibility = Visibility.Collapsed;
            }
            else
            {
                SyncCropBoxPixelsFromRatios();
                CropOverlayCanvas.Visibility = Visibility.Visible;
            }
        }

        private void BtnLockCrop_Click(object sender, RoutedEventArgs e)
        {
            SyncRatiosFromCropBoxPixels();
            CropOverlayCanvas.Visibility = Visibility.Collapsed;
        }

        private void BtnResetCropBox_Click(object sender, RoutedEventArgs e)
        {
            _config.CropLeftRatio = 0.16;
            _config.CropTopRatio = 0.74;
            _config.CropWidthRatio = 0.68;
            _config.CropHeightRatio = 0.18;
            SyncCropBoxPixelsFromRatios();
            CropOverlayCanvas.Visibility = Visibility.Visible;
            SaveConfig();
        }
        #endregion

        #region In-Window Subtitle Vertical Dragging
        private void SubtitleBox_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (_isBlackScreenMode) return;
            _isDraggingSubtitle = true;
            _subDragStartMouse = e.GetPosition(RootGrid);
            _subStartBottomMargin = InWindowSubtitleBox.Margin.Bottom;
            InWindowSubtitleBox.CaptureMouse();
        }

        private void SubtitleBox_MouseMove(object sender, MouseEventArgs e)
        {
            if (!_isDraggingSubtitle) return;
            var pos = e.GetPosition(RootGrid);
            double dy = _subDragStartMouse.Y - pos.Y;
            double newBottom = Math.Clamp(_subStartBottomMargin + dy, 12, Math.Max(100, RootGrid.ActualHeight - 180));
            InWindowSubtitleBox.Margin = new Thickness(40, 20, 40, newBottom);
            _config.SubtitleBottomMargin = newBottom;
        }

        private void SubtitleBox_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            if (_isDraggingSubtitle)
            {
                _isDraggingSubtitle = false;
                InWindowSubtitleBox.ReleaseMouseCapture();
                SaveConfig();
            }
        }
        #endregion

        #region Font Size, Font Color & Opacity Handlers
        private void SliderThaiFontSize_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (!IsInitialized) return;
            _config.ThaiFontSize = Math.Round(SliderThaiFontSize.Value);
            ApplySubtitleStyling();
            SaveConfig();
        }

        private void BtnThaiFontSmaller_Click(object sender, RoutedEventArgs e)
        {
            SliderThaiFontSize.Value = Math.Max(16, SliderThaiFontSize.Value - 2);
        }

        private void BtnThaiFontLarger_Click(object sender, RoutedEventArgs e)
        {
            SliderThaiFontSize.Value = Math.Min(72, SliderThaiFontSize.Value + 2);
        }

        private void SliderEngFontSize_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (!IsInitialized) return;
            _config.EngFontSize = Math.Round(SliderEngFontSize.Value);
            ApplySubtitleStyling();
            SaveConfig();
        }

        private void ChkShowEnglish_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.ShowEnglishText = ChkShowEnglish.IsChecked == true;
            ApplySubtitleStyling();
            SaveConfig();
        }

        private void SliderBgOpacity_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (!IsInitialized) return;
            _config.SubtitleBgOpacityPercent = Math.Round(SliderBgOpacity.Value);
            ApplySubtitleStyling();
            SaveConfig();
        }

        private void SetThaiColor(string hex)
        {
            _config.ThaiTextColorHex = hex;
            ApplySubtitleStyling();
            SaveConfig();
        }

        private void BtnColorGold_Click(object sender, RoutedEventArgs e) => SetThaiColor("#FFD54F");
        private void BtnColorWhite_Click(object sender, RoutedEventArgs e) => SetThaiColor("#FFFFFF");
        private void BtnColorGreen_Click(object sender, RoutedEventArgs e) => SetThaiColor("#00E676");
        private void BtnColorCyan_Click(object sender, RoutedEventArgs e) => SetThaiColor("#40C4FF");
        private void BtnColorPink_Click(object sender, RoutedEventArgs e) => SetThaiColor("#FF80AB");
        private void BtnColorOrange_Click(object sender, RoutedEventArgs e) => SetThaiColor("#FFAB40");

        private void SetEngColor(string hex)
        {
            _config.EngTextColorHex = hex;
            ApplySubtitleStyling();
            SaveConfig();
        }

        private void BtnEngColorGray_Click(object sender, RoutedEventArgs e) => SetEngColor("#CCCCCC");
        private void BtnEngColorWhite_Click(object sender, RoutedEventArgs e) => SetEngColor("#FFFFFF");
        private void BtnEngColorBlue_Click(object sender, RoutedEventArgs e) => SetEngColor("#80D8FF");
        private void BtnEngColorYellow_Click(object sender, RoutedEventArgs e) => SetEngColor("#FFF59D");
        #endregion

        #region Fullscreen, Black-Screen Text-Only Mode, Aspect Ratio & Menu Drawer
        private void BtnMenuToggle_Click(object sender, RoutedEventArgs e)
        {
            SettingsDrawer.Visibility = SettingsDrawer.Visibility == Visibility.Visible
                ? Visibility.Collapsed
                : Visibility.Visible;
        }

        private void BtnFullscreen_Click(object sender, RoutedEventArgs e)
        {
            ToggleFullscreen();
        }

        private void Window_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.F11)
            {
                ToggleFullscreen();
            }
            else if (e.Key == Key.Escape && _isFullscreen)
            {
                ToggleFullscreen();
            }
        }

        private void ToggleFullscreen()
        {
            _isFullscreen = !_isFullscreen;
            if (_isFullscreen)
            {
                WindowStyle = WindowStyle.None;
                WindowState = WindowState.Maximized;
                BtnQuickFullscreen.Content = "ย่อหน้าต่าง (ESC)";
                SetButtonHighlight(BtnQuickFullscreen, true);
            }
            else
            {
                WindowStyle = WindowStyle.SingleBorderWindow;
                WindowState = WindowState.Normal;
                BtnQuickFullscreen.Content = "ขยายเต็มจอ (F11)";
                SetButtonHighlight(BtnQuickFullscreen, false);
            }
        }

        private void BtnBlackScreenMode_Click(object sender, RoutedEventArgs e)
        {
            _isBlackScreenMode = !_isBlackScreenMode;
            if (_isBlackScreenMode)
            {
                GameVideoImage.Visibility = Visibility.Collapsed;
                InWindowSubtitleBox.VerticalAlignment = VerticalAlignment.Center;
                InWindowSubtitleBox.Margin = new Thickness(40);
                BtnQuickTextOnly.Content = "แสดงภาพเกม";
                SetButtonHighlight(BtnQuickTextOnly, true);
            }
            else
            {
                GameVideoImage.Visibility = Visibility.Visible;
                InWindowSubtitleBox.VerticalAlignment = VerticalAlignment.Bottom;
                InWindowSubtitleBox.Margin = new Thickness(40, 20, 40, _config.SubtitleBottomMargin);
                BtnQuickTextOnly.Content = "เฉพาะตัวหนังสือ";
                SetButtonHighlight(BtnQuickTextOnly, false);
            }
        }

        private void BtnAspect169_Click(object sender, RoutedEventArgs e)
        {
            _config.AspectMode = "16_9";
            ApplyAspectRatioMode();
            SaveConfig();
        }

        private void BtnAspectFit_Click(object sender, RoutedEventArgs e)
        {
            _config.AspectMode = "FIT";
            ApplyAspectRatioMode();
            SaveConfig();
        }

        private void BtnAspectStretch_Click(object sender, RoutedEventArgs e)
        {
            _config.AspectMode = "STRETCH";
            ApplyAspectRatioMode();
            SaveConfig();
        }
        #endregion

        #region Game Audio Mute (Mutes Game Stream Only so Thai TTS Plays Clearly)
        private void BtnMuteGameAudio_Click(object sender, RoutedEventArgs e)
        {
            _isGameAudioMuted = !_isGameAudioMuted;
            ApplyGameAudioMute(_isGameAudioMuted);

            if (_isGameAudioMuted)
            {
                BtnQuickMuteGame.Content = "เปิดเสียงเกม";
                SetButtonHighlight(BtnQuickMuteGame, true);
            }
            else
            {
                BtnQuickMuteGame.Content = "ปิดเสียงเกม";
                SetButtonHighlight(BtnQuickMuteGame, false);
            }
        }

        private void ApplyGameAudioMute(bool mute)
        {
            try
            {
                int myPid = Environment.ProcessId;
                var target = CmbCaptureTarget.SelectedItem as CaptureTargetItem;
                uint targetPid = target?.ProcessId ?? 0;

                using var enumerator = new MMDeviceEnumerator();
                var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                var sessions = device.AudioSessionManager.Sessions;

                for (int i = 0; i < sessions.Count; i++)
                {
                    var session = sessions[i];
                    uint pid = session.GetProcessID;
                    if (pid == 0 || pid == (uint)myPid) continue; // Never mute our own Thai TTS voice!

                    bool shouldMuteThis = false;
                    if (targetPid != 0 && pid == targetPid)
                    {
                        shouldMuteThis = true;
                    }
                    else
                    {
                        try
                        {
                            using var proc = Process.GetProcessById((int)pid);
                            string pname = proc.ProcessName.ToLowerInvariant();
                            if (pname.Contains("chiaki") || pname.Contains("remoteplay") || pname.Contains("ps5"))
                            {
                                shouldMuteThis = true;
                            }
                        }
                        catch { }
                    }

                    if (shouldMuteThis)
                    {
                        session.SimpleAudioVolume.Mute = mute;
                    }
                }
            }
            catch { }
        }
        #endregion

        #region Voice, Speed, Pause & All-in-One PS5 Embedded Streamer
        private void BtnToggleCapture_Click(object sender, RoutedEventArgs e)
        {
            _isRunning = !_isRunning;
            if (_isRunning)
            {
                BtnToggleCapture.Content = "ระบบแปลภาษาทำงานอยู่ (คลิกเพื่อหยุดชั่วคราว)";
                BtnToggleCapture.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(46, 125, 50));
                BtnToggleCapture.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 230, 118));
            }
            else
            {
                BtnToggleCapture.Content = "หยุดแปลภาษาชั่วคราว (คลิกเพื่อเริ่มทำงานต่อ)";
                BtnToggleCapture.Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(183, 28, 28));
                BtnToggleCapture.BorderBrush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(255, 82, 82));
            }
        }

        private void ChkFilterShort_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.FilterShortUi = ChkFilterShort.IsChecked == true;
            SaveConfig();
        }

        private void ChkTtsEnabled_Changed(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.IsTtsEnabled = ChkTtsEnabled.IsChecked == true;
            if (!_config.IsTtsEnabled) AudioTtsHelper.StopCurrentPlayback();
            SaveConfig();
        }

        private void BtnVoiceAuto_Click(object sender, RoutedEventArgs e)
        {
            _config.VoiceGenderMode = "AUTO_AI";
            UpdateVoiceButtonsHighlight();
            SaveConfig();
        }

        private void BtnVoiceFemale_Click(object sender, RoutedEventArgs e)
        {
            _config.VoiceGenderMode = "FEMALE";
            UpdateVoiceButtonsHighlight();
            SaveConfig();
            _ = AudioTtsHelper.SpeakThaiAsync("เลือกเสียงพากย์ผู้หญิงเรียบร้อยแล้วค่ะ", true, _config.SpeechSpeedRate);
        }

        private void BtnVoiceMale_Click(object sender, RoutedEventArgs e)
        {
            _config.VoiceGenderMode = "MALE";
            UpdateVoiceButtonsHighlight();
            SaveConfig();
            _ = AudioTtsHelper.SpeakThaiAsync("เลือกเสียงพากย์ผู้ชายเรียบร้อยแล้วครับ", false, _config.SpeechSpeedRate);
        }

        private void BtnSpeedNormal_Click(object sender, RoutedEventArgs e)
        {
            _config.SpeechSpeedRate = 1.0f;
            UpdateSpeedButtonsHighlight();
            SaveConfig();
        }

        private void BtnSpeedFast_Click(object sender, RoutedEventArgs e)
        {
            _config.SpeechSpeedRate = 1.25f;
            UpdateSpeedButtonsHighlight();
            SaveConfig();
        }

        private void BtnSpeedVeryFast_Click(object sender, RoutedEventArgs e)
        {
            _config.SpeechSpeedRate = 1.5f;
            UpdateSpeedButtonsHighlight();
            SaveConfig();
        }

        private void TxtPsnId_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (!IsInitialized) return;
            _config.SavedPsnId = TxtPsnId.Text.Trim();
            SaveConfig();
        }

        private void BtnCopyPsnId_Click(object sender, RoutedEventArgs e)
        {
            if (!string.IsNullOrWhiteSpace(TxtPsnId.Text))
            {
                Clipboard.SetText(TxtPsnId.Text.Trim());
                MessageBox.Show("คัดลอก PSN Account ID เรียบร้อยแล้วครับ", "PS Translator", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        private async void BtnEmbedChiaki_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                BtnEmbedChiaki.IsEnabled = false;
                BtnEmbedChiaki.Content = "กำลังเตรียมระบบสตรีม PS5 ในโปรแกรม...";

                string localAppDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PSTranslator", "Chiaki");
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;

                string? foundExe = FindChiakiExecutable(baseDir) ?? FindChiakiExecutable(localAppDir);

                if (foundExe == null)
                {
                    BtnEmbedChiaki.Content = "กำลังติดตั้งโมดูล PS5 อัตโนมัติ (ครั้งแรกครั้งเดียว)...";
                    Directory.CreateDirectory(localAppDir);

                    _http.DefaultRequestHeaders.Remove("User-Agent");
                    _http.DefaultRequestHeaders.UserAgent.ParseAdd("PS-Translator-Windows");
                    string apiJson = await _http.GetStringAsync("https://api.github.com/repos/chiaki-ng/chiaki-ng/releases/latest");
                    using var doc = JsonDocument.Parse(apiJson);
                    string? downloadUrl = null;

                    foreach (var asset in doc.RootElement.GetProperty("assets").EnumerateArray())
                    {
                        string name = asset.GetProperty("name").GetString() ?? "";
                        if (name.Contains("win", StringComparison.OrdinalIgnoreCase) && name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
                        {
                            downloadUrl = asset.GetProperty("browser_download_url").GetString();
                            break;
                        }
                    }

                    if (!string.IsNullOrEmpty(downloadUrl))
                    {
                        string zipPath = Path.Combine(localAppDir, "chiaki-module.zip");
                        byte[] zipBytes = await _http.GetByteArrayAsync(downloadUrl);
                        await File.WriteAllBytesAsync(zipPath, zipBytes);
                        ZipFile.ExtractToDirectory(zipPath, localAppDir, overwriteFiles: true);
                        try { File.Delete(zipPath); } catch { }
                        foundExe = FindChiakiExecutable(localAppDir);
                    }
                }

                if (foundExe != null)
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = foundExe,
                        WorkingDirectory = Path.GetDirectoryName(foundExe)
                    });

                    await Task.Delay(1800);
                    RefreshCaptureTargets(autoSelectPs5: true);
                    BtnEmbedChiaki.Content = "เชื่อมต่อหน้าจอ PS5 เข้ากับหน้าต่างนี้แล้ว";
                }
                else
                {
                    BtnEmbedChiaki.Content = "เปิดระบบสตรีม PS5 ในหน้าต่างนี้ (All-in-One)";
                }
            }
            catch
            {
                BtnEmbedChiaki.Content = "เปิดระบบสตรีม PS5 ในหน้าต่างนี้ (All-in-One)";
            }
            finally
            {
                BtnEmbedChiaki.IsEnabled = true;
            }
        }

        private static string? FindChiakiExecutable(string rootFolder)
        {
            if (!Directory.Exists(rootFolder)) return null;
            try
            {
                var files = Directory.GetFiles(rootFolder, "chiaki*.exe", SearchOption.AllDirectories);
                return files.FirstOrDefault();
            }
            catch
            {
                return null;
            }
        }
        #endregion

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            SaveConfig();
            _videoRenderTimer.Stop();
            _ocrTimer.Stop();
            if (_isGameAudioMuted) ApplyGameAudioMute(false);
            AudioTtsHelper.StopCurrentPlayback();
        }
    }
}
