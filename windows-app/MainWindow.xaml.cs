using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Web;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using NAudio.Wave;
using Windows.Globalization;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;

namespace PSTranslatorWin
{
    public class RegisteredConsoleInfo
    {
        public string ServerNickname { get; set; } = "PlayStation 5";
        public string HostIp { get; set; } = "";
        public string ServerMacHex { get; set; } = "";
        public bool IsPs5 { get; set; } = true;
        public string RegistKeyBase64 { get; set; } = "";
        public string MorningKeyBase64 { get; set; } = "";
    }

    public class DiscoveredConsoleItem
    {
        public string HostAddr { get; set; } = "";
        public string HostName { get; set; } = "";
        public string HostId { get; set; } = "";
        public string HostType { get; set; } = "";
        public int State { get; set; } = 0; // 1 = Standby, 2 = Ready
        public string RunningAppName { get; set; } = "";
        public bool IsPs5 => HostType != null && HostType.Contains("PS5", StringComparison.OrdinalIgnoreCase);
    }

    public class AppConfig
    {
        public double CropLeftRatio { get; set; } = 0.16;
        public double CropTopRatio { get; set; } = 0.74;
        public double CropWidthRatio { get; set; } = 0.68;
        public double CropHeightRatio { get; set; } = 0.18;

        public double ThaiFontSize { get; set; } = 28;
        public double EngFontSize { get; set; } = 16;
        public string ThaiTextColorHex { get; set; } = "#FFD54F";
        public string EngTextColorHex { get; set; } = "#CCCCCC";
        public bool ShowEnglishText { get; set; } = true;
        public double SubtitleBgOpacityPercent { get; set; } = 85;
        public double SubtitleBottomMargin { get; set; } = 32;

        public string AspectMode { get; set; } = "FIT";
        public string VoiceGenderMode { get; set; } = "AUTO_AI";
        public float SpeechSpeedRate { get; set; } = 1.25f;
        public bool IsTtsEnabled { get; set; } = true;
        public bool FilterShortUi { get; set; } = true;
        public string SavedPsnId { get; set; } = "";
        public bool RegTargetIsPs5 { get; set; } = true;

        public List<RegisteredConsoleInfo> RegisteredConsoles { get; set; } = new List<RegisteredConsoleInfo>();
    }

    public partial class MainWindow : Window
    {
        private readonly DispatcherTimer _ocrTimer;
        private readonly DispatcherTimer _controllerTimer;
        private readonly HttpClient _http = new HttpClient();
        private OcrEngine? _ocrEngine;
        private AppConfig _config = new AppConfig();
        private readonly string _configPath;

        // Keep native callback delegates alive to prevent GC collection
        private readonly ChiakiNative.CwDiscoveryHostCallback _discoveryCb;
        private readonly ChiakiNative.CwRegistEventCallback _registCb;
        private readonly ChiakiNative.CwVideoFrameCallback _videoCb;
        private readonly ChiakiNative.CwAudioFrameCallback _audioCb;
        private readonly ChiakiNative.CwSessionEventCallback _eventCb;

        private IntPtr _discoveryPtr = IntPtr.Zero;
        private IntPtr _registPtr = IntPtr.Zero;
        private IntPtr _sessionPtr = IntPtr.Zero;

        private readonly List<DiscoveredConsoleItem> _discoveredBuffer = new List<DiscoveredConsoleItem>();
        private List<DiscoveredConsoleItem> _discoveredConsoles = new List<DiscoveredConsoleItem>();

        // Native Chiaki Video & Audio state
        private WriteableBitmap? _videoWriteableBmp;
        private System.Drawing.Bitmap? _latestStreamFrame;
        private readonly object _frameLock = new object();

        private WaveOutEvent? _gameAudioOut;
        private BufferedWaveProvider? _gameAudioBuffer;
        private int _gameAudioRate = 0;
        private int _gameAudioChannels = 0;
        private bool _isGameAudioMuted = false;

        // Keyboard controller fallback state
        private uint _keyboardButtons = 0;

        // Translator & UI state
        private bool _isRunning = true;
        private bool _isBusyOcr = false;
        private bool _isBlackScreenMode = false;
        private bool _isFullscreen = false;
        private string _lastRecognizedEnglish = "";

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

            _discoveryCb = OnNativeDiscoveryHost;
            _registCb = OnNativeRegistEvent;
            _videoCb = OnNativeVideoFrame;
            _audioCb = OnNativeAudioFrame;
            _eventCb = OnNativeSessionEvent;

            _configPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "ps_translator_config.json");
            LoadConfig();
            InitializeWindowsOcr();
            ApplyConfigToUi();

            Loaded += (_, _) =>
            {
                StartChiakiDiscovery();
                RebuildConsoleCardsUi();
                SyncCropBoxPixelsFromRatios();
            };

            // 60Hz Controller Polling Timer (XInput Gamepad + Keyboard)
            _controllerTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(16)
            };
            _controllerTimer.Tick += (_, _) => PollAndSendControllerState();
            _controllerTimer.Start();

            // Real-time OCR Timer (every 450ms directly from Chiaki stream frame)
            _ocrTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(450)
            };
            _ocrTimer.Tick += async (_, _) => await ProcessOcrFromLatestFrameAsync();
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

            if (_config.RegisteredConsoles.Count > 0 && !string.IsNullOrEmpty(_config.RegisteredConsoles[0].HostIp))
            {
                TxtQuickIp.Text = _config.RegisteredConsoles[0].HostIp;
            }

            InWindowSubtitleBox.Margin = new Thickness(40, 20, 40, _config.SubtitleBottomMargin);

            UpdateRegTargetButtonsHighlight();
            ApplySubtitleStyling();
            ApplyAspectRatioMode();
            UpdateVoiceButtonsHighlight();
            UpdateSpeedButtonsHighlight();
        }

        #region Native Chiaki Discovery, Wakeup & Registration
        private void StartChiakiDiscovery()
        {
            if (!ChiakiNative.IsLoaded)
            {
                TxtDiscoveryStatus.Text = "พร้อมเชื่อมต่อเครื่อง PS5 / PS4 (กรอก IP หรือลงทะเบียนเครื่องด้านขวามือ)";
                return;
            }

            try
            {
                if (_discoveryPtr != IntPtr.Zero)
                {
                    ChiakiNative.cw_discovery_stop(_discoveryPtr);
                    _discoveryPtr = IntPtr.Zero;
                }
                _discoveryPtr = ChiakiNative.cw_discovery_start(_discoveryCb);
                TxtDiscoveryStatus.Text = "กำลังค้นหาเครื่อง PS5 / PS4 ในวงแลนอัตโนมัติ (UDP Port 987 & 9302)...";
            }
            catch { }
        }

        private void BtnRescanLan_Click(object sender, RoutedEventArgs e)
        {
            StartChiakiDiscovery();
            RebuildConsoleCardsUi();
        }

        private void OnNativeDiscoveryHost(
            int index,
            int totalCount,
            int state,
            string hostAddr,
            string hostName,
            string hostId,
            string hostType,
            string runningAppName,
            string runningAppTitleId)
        {
            Dispatcher.BeginInvoke(() =>
            {
                if (index < 0 || totalCount == 0)
                {
                    _discoveredBuffer.Clear();
                    _discoveredConsoles = new List<DiscoveredConsoleItem>();
                    RebuildConsoleCardsUi();
                    return;
                }

                if (index == 0)
                {
                    _discoveredBuffer.Clear();
                }

                _discoveredBuffer.Add(new DiscoveredConsoleItem
                {
                    HostAddr = hostAddr ?? "",
                    HostName = string.IsNullOrWhiteSpace(hostName) ? "PlayStation Console" : hostName,
                    HostId = hostId ?? "",
                    HostType = hostType ?? "PS5",
                    State = state,
                    RunningAppName = runningAppName ?? ""
                });

                if (index == totalCount - 1)
                {
                    _discoveredConsoles = _discoveredBuffer.ToList();
                    if (_discoveredConsoles.Count > 0)
                    {
                        var first = _discoveredConsoles[0];
                        if (TxtRegHostIp.Text == "255.255.255.255" || string.IsNullOrWhiteSpace(TxtRegHostIp.Text))
                        {
                            TxtRegHostIp.Text = first.HostAddr;
                        }
                        if (string.IsNullOrWhiteSpace(TxtQuickIp.Text))
                        {
                            TxtQuickIp.Text = first.HostAddr;
                        }
                        TxtDiscoveryStatus.Text = $"ตรวจพบเครื่องในวงแลน {_discoveredConsoles.Count} เครื่อง";
                    }
                    RebuildConsoleCardsUi();
                }
            });
        }

        private void RebuildConsoleCardsUi()
        {
            if (ConsoleCardsPanel == null) return;
            ConsoleCardsPanel.Children.Clear();

            // Merge registered consoles + live discovered consoles
            var displayedHosts = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

            foreach (var reg in _config.RegisteredConsoles)
            {
                var live = _discoveredConsoles.FirstOrDefault(d =>
                    (!string.IsNullOrEmpty(reg.ServerMacHex) && d.HostId.Equals(reg.ServerMacHex, StringComparison.OrdinalIgnoreCase)) ||
                    (!string.IsNullOrEmpty(reg.HostIp) && d.HostAddr.Equals(reg.HostIp, StringComparison.OrdinalIgnoreCase)));

                if (live != null && !string.IsNullOrEmpty(live.HostAddr))
                {
                    reg.HostIp = live.HostAddr;
                    displayedHosts.Add(live.HostAddr);
                }

                ConsoleCardsPanel.Children.Add(CreateConsoleCardElement(reg, live));
            }

            foreach (var disc in _discoveredConsoles)
            {
                if (displayedHosts.Contains(disc.HostAddr)) continue;
                ConsoleCardsPanel.Children.Add(CreateDiscoveredOnlyCardElement(disc));
            }

            if (ConsoleCardsPanel.Children.Count == 0)
            {
                var emptyBorder = new Border
                {
                    Background = new SolidColorBrush(Color.FromRgb(22, 28, 40)),
                    CornerRadius = new CornerRadius(10),
                    Padding = new Thickness(20),
                    BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
                    BorderThickness = new Thickness(1)
                };
                var sp = new StackPanel();
                sp.Children.Add(new TextBlock
                {
                    Text = "ยังไม่มีเครื่อง PS5 / PS4 ที่ลงทะเบียนไว้",
                    Foreground = new SolidColorBrush(Color.FromRgb(255, 213, 79)),
                    FontSize = 15,
                    FontWeight = FontWeights.Bold,
                    Margin = new Thickness(0, 0, 0, 6)
                });
                sp.Children.Add(new TextBlock
                {
                    Text = "วิธีเริ่มใช้งานครั้งแรก:\n1. เปิดเครื่อง PS5 ไปที่ Settings -> System -> Remote Play -> Enable Remote Play แล้วกด Link Device เพื่อดูรหัส PIN 8 หลัก\n2. กรอก PSN Account ID (Base64) และรหัส PIN 8 หลักในช่องด้านขวามือ แล้วกดปุ่ม 'ลงทะเบียนเชื่อมต่อเครื่อง PS5 ทันที'\n3. เมื่อลงทะเบียนสำเร็จ การ์ดเครื่อง PS5 จะปรากฏที่นี่ กดปุ่ม 'เริ่มสตรีมและแปลภาษา' ได้ทันทีครับ",
                    Foreground = new SolidColorBrush(Color.FromRgb(185, 185, 185)),
                    FontSize = 12.5,
                    TextWrapping = TextWrapping.Wrap
                });
                emptyBorder.Child = sp;
                ConsoleCardsPanel.Children.Add(emptyBorder);
            }
        }

        private UIElement CreateConsoleCardElement(RegisteredConsoleInfo reg, DiscoveredConsoleItem? live)
        {
            bool isReady = live?.State == 2;
            bool isStandby = live?.State == 1;

            var card = new Border
            {
                Background = new SolidColorBrush(Color.FromRgb(22, 29, 42)),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(16, 14, 16, 14),
                Margin = new Thickness(0, 0, 0, 10),
                BorderBrush = new SolidColorBrush(isReady ? Color.FromRgb(0, 230, 118) : Color.FromArgb(70, 255, 255, 255)),
                BorderThickness = new Thickness(isReady ? 1.5 : 1)
            };

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var infoStack = new StackPanel();
            string typeBadge = reg.IsPs5 ? "[PS5]" : "[PS4]";
            string statusText = isReady ? "พร้อมเล่น (Ready)" : (isStandby ? "โหมดพักเครื่อง (Standby)" : "ลงทะเบียนแล้ว (Registered)");

            infoStack.Children.Add(new TextBlock
            {
                Text = $"{typeBadge} {reg.ServerNickname} — {statusText}",
                Foreground = new SolidColorBrush(isReady ? Color.FromRgb(0, 230, 118) : Color.FromRgb(255, 213, 79)),
                FontSize = 15,
                FontWeight = FontWeights.Bold
            });

            string subLine = $"IP: {(string.IsNullOrEmpty(reg.HostIp) ? "ไม่ระบุ (กรอกในช่อง IP ด้านล่าง)" : reg.HostIp)}";
            if (live != null && !string.IsNullOrEmpty(live.RunningAppName))
            {
                subLine += $" | กำลังเปิดเกม: {live.RunningAppName}";
            }
            infoStack.Children.Add(new TextBlock
            {
                Text = subLine,
                Foreground = new SolidColorBrush(Color.FromRgb(190, 190, 190)),
                FontSize = 12,
                Margin = new Thickness(0, 4, 0, 0)
            });

            var btnStack = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };

            var btnPlay = new Button
            {
                Content = isStandby ? "ปลุกและเชื่อมต่อ" : "เริ่มสตรีมและแปลภาษา",
                Height = 36,
                Padding = new Thickness(14, 0, 14, 0),
                Margin = new Thickness(0, 0, 6, 0),
                Background = new SolidColorBrush(Color.FromRgb(46, 125, 50)),
                Foreground = Brushes.White,
                BorderBrush = new SolidColorBrush(Color.FromRgb(0, 230, 118)),
                BorderThickness = new Thickness(1.5),
                FontWeight = FontWeights.Bold,
                FontSize = 12.5,
                Cursor = Cursors.Hand
            };
            btnPlay.Click += (_, _) => ConnectToRegisteredConsole(reg, sendWakeupFirst: isStandby);

            var btnWake = new Button
            {
                Content = "ปลุกเครื่อง",
                Height = 36,
                Padding = new Thickness(10, 0, 10, 0),
                Margin = new Thickness(0, 0, 6, 0),
                Background = new SolidColorBrush(Color.FromRgb(37, 42, 52)),
                Foreground = Brushes.White,
                BorderBrush = new SolidColorBrush(Color.FromArgb(85, 255, 255, 255)),
                FontSize = 12,
                Cursor = Cursors.Hand
            };
            btnWake.Click += (_, _) => WakeupRegisteredConsole(reg);

            var btnDel = new Button
            {
                Content = "ลบ",
                Height = 36,
                Padding = new Thickness(10, 0, 10, 0),
                Background = new SolidColorBrush(Color.FromRgb(80, 24, 24)),
                Foreground = Brushes.White,
                BorderBrush = new SolidColorBrush(Color.FromArgb(85, 255, 100, 100)),
                FontSize = 12,
                Cursor = Cursors.Hand
            };
            btnDel.Click += (_, _) =>
            {
                _config.RegisteredConsoles.Remove(reg);
                SaveConfig();
                RebuildConsoleCardsUi();
            };

            btnStack.Children.Add(btnPlay);
            btnStack.Children.Add(btnWake);
            btnStack.Children.Add(btnDel);

            Grid.SetColumn(infoStack, 0);
            Grid.SetColumn(btnStack, 1);
            grid.Children.Add(infoStack);
            grid.Children.Add(btnStack);
            card.Child = grid;
            return card;
        }

        private UIElement CreateDiscoveredOnlyCardElement(DiscoveredConsoleItem disc)
        {
            var card = new Border
            {
                Background = new SolidColorBrush(Color.FromRgb(20, 25, 36)),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(16, 12, 16, 12),
                Margin = new Thickness(0, 0, 0, 10),
                BorderBrush = new SolidColorBrush(Color.FromArgb(80, 64, 196, 255)),
                BorderThickness = new Thickness(1)
            };

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var infoStack = new StackPanel();
            string stateStr = disc.State == 2 ? "พร้อมลงทะเบียน (Ready)" : "โหมดพักเครื่อง (Standby)";
            infoStack.Children.Add(new TextBlock
            {
                Text = $"[{disc.HostType}] {disc.HostName} ({stateStr})",
                Foreground = new SolidColorBrush(Color.FromRgb(64, 196, 255)),
                FontSize = 14,
                FontWeight = FontWeights.Bold
            });
            infoStack.Children.Add(new TextBlock
            {
                Text = $"ตรวจพบที่ IP: {disc.HostAddr} (กดเลือกเพื่อดึง IP ไปลงทะเบียนด้านขวามือ)",
                Foreground = new SolidColorBrush(Color.FromRgb(180, 180, 180)),
                FontSize = 12,
                Margin = new Thickness(0, 3, 0, 0)
            });

            var btnUseIp = new Button
            {
                Content = "เลือกเครื่องนี้เพื่อลงทะเบียน",
                Height = 34,
                Padding = new Thickness(12, 0, 12, 0),
                Background = new SolidColorBrush(Color.FromRgb(21, 101, 192)),
                Foreground = Brushes.White,
                BorderBrush = new SolidColorBrush(Color.FromRgb(64, 196, 255)),
                FontWeight = FontWeights.Bold,
                FontSize = 12,
                Cursor = Cursors.Hand
            };
            btnUseIp.Click += (_, _) =>
            {
                TxtRegHostIp.Text = disc.HostAddr;
                _config.RegTargetIsPs5 = disc.IsPs5;
                UpdateRegTargetButtonsHighlight();
                TxtRegPin.Focus();
            };

            Grid.SetColumn(infoStack, 0);
            Grid.SetColumn(btnUseIp, 1);
            grid.Children.Add(infoStack);
            grid.Children.Add(btnUseIp);
            card.Child = grid;
            return card;
        }

        private void UpdateRegTargetButtonsHighlight()
        {
            SetButtonHighlight(BtnRegTargetPs5, _config.RegTargetIsPs5);
            SetButtonHighlight(BtnRegTargetPs4, !_config.RegTargetIsPs5);
            if (BtnStartRegister != null)
            {
                BtnStartRegister.Content = _config.RegTargetIsPs5
                    ? "ลงทะเบียนเชื่อมต่อเครื่อง PS5 ทันที"
                    : "ลงทะเบียนเชื่อมต่อเครื่อง PS4 ทันที";
            }
        }

        private void BtnRegTargetPs5_Click(object sender, RoutedEventArgs e)
        {
            _config.RegTargetIsPs5 = true;
            UpdateRegTargetButtonsHighlight();
            SaveConfig();
        }

        private void BtnRegTargetPs4_Click(object sender, RoutedEventArgs e)
        {
            _config.RegTargetIsPs5 = false;
            UpdateRegTargetButtonsHighlight();
            SaveConfig();
        }

        private void BtnStartRegister_Click(object sender, RoutedEventArgs e)
        {
            string hostIp = TxtRegHostIp.Text.Trim();
            if (string.IsNullOrEmpty(hostIp)) hostIp = "255.255.255.255";

            string psnB64 = TxtPsnId.Text.Trim();
            byte[] accountIdBytes;
            try
            {
                accountIdBytes = Convert.FromBase64String(psnB64);
                if (accountIdBytes.Length != 8)
                {
                    TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                    TxtRegistStatus.Text = "PSN Account ID (Base64) ไม่ถูกต้อง (เมื่อถอดรหัสแล้วต้องมีขนาด 8 ไบต์ เช่นเดียวกับในแอปมือถือ)";
                    return;
                }
            }
            catch
            {
                TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                TxtRegistStatus.Text = "รูปแบบ PSN Account ID (Base64) ไม่ถูกต้อง กรุณาตรวจสอบอีกครั้ง";
                return;
            }

            string pinStr = Regex.Replace(TxtRegPin.Text.Trim(), @"[^0-9]", "");
            if (pinStr.Length != 8 || !uint.TryParse(pinStr, out uint pinVal))
            {
                TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                TxtRegistStatus.Text = "กรุณากรอกรหัส PIN ตัวเลข 8 หลักจากหน้าจอ PS5 / PS4 ให้ครบถ้วน";
                return;
            }

            _config.SavedPsnId = psnB64;
            SaveConfig();

            if (!ChiakiNative.IsLoaded)
            {
                TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                TxtRegistStatus.Text = "ไม่พบโมดูล chiaki_win_bridge.dll ในระบบ";
                return;
            }

            try
            {
                if (_registPtr != IntPtr.Zero)
                {
                    ChiakiNative.cw_regist_stop(_registPtr);
                    _registPtr = IntPtr.Zero;
                }

                BtnStartRegister.IsEnabled = false;
                TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 213, 79));
                TxtRegistStatus.Text = $"กำลังส่งคำขอลงทะเบียนไปยัง {hostIp} ด้วยรหัส PIN {pinStr}...";

                int broadcast = (hostIp == "255.255.255.255") ? 1 : 0;
                int targetType = _config.RegTargetIsPs5 ? 1 : 2;
                _registPtr = ChiakiNative.cw_regist_start(hostIp, broadcast, accountIdBytes, pinVal, targetType, _registCb);

                if (_registPtr == IntPtr.Zero)
                {
                    BtnStartRegister.IsEnabled = true;
                    TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                    TxtRegistStatus.Text = "ไม่สามารถเริ่มกระบวนการลงทะเบียนได้ กรุณาตรวจสอบการเชื่อมต่อเครือข่าย";
                }
            }
            catch (Exception ex)
            {
                BtnStartRegister.IsEnabled = true;
                TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                TxtRegistStatus.Text = $"เกิดข้อผิดพลาด: {ex.Message}";
            }
        }

        private void OnNativeRegistEvent(
            int eventType,
            int isPs5,
            string serverNickname,
            IntPtr serverMac6,
            IntPtr rpRegistKey16,
            IntPtr rpKey16)
        {
            byte[] macBytes = new byte[6];
            byte[] registKeyBytes = new byte[16];
            byte[] morningBytes = new byte[16];

            if (serverMac6 != IntPtr.Zero) Marshal.Copy(serverMac6, macBytes, 0, 6);
            if (rpRegistKey16 != IntPtr.Zero) Marshal.Copy(rpRegistKey16, registKeyBytes, 0, 16);
            if (rpKey16 != IntPtr.Zero) Marshal.Copy(rpKey16, morningBytes, 0, 16);

            Dispatcher.BeginInvoke(() =>
            {
                BtnStartRegister.IsEnabled = true;

                // CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS == 2
                if (eventType == 2)
                {
                    string macHex = BitConverter.ToString(macBytes).Replace("-", "");
                    string hostIp = TxtRegHostIp.Text.Trim();
                    if (hostIp == "255.255.255.255" && _discoveredConsoles.Count > 0)
                    {
                        hostIp = _discoveredConsoles[0].HostAddr;
                    }

                    var newConsole = new RegisteredConsoleInfo
                    {
                        ServerNickname = string.IsNullOrWhiteSpace(serverNickname) ? (isPs5 != 0 ? "PlayStation 5" : "PlayStation 4") : serverNickname,
                        HostIp = hostIp == "255.255.255.255" ? "" : hostIp,
                        ServerMacHex = macHex,
                        IsPs5 = isPs5 != 0,
                        RegistKeyBase64 = Convert.ToBase64String(registKeyBytes),
                        MorningKeyBase64 = Convert.ToBase64String(morningBytes)
                    };

                    _config.RegisteredConsoles.RemoveAll(c =>
                        (!string.IsNullOrEmpty(macHex) && c.ServerMacHex.Equals(macHex, StringComparison.OrdinalIgnoreCase)) ||
                        (!string.IsNullOrEmpty(newConsole.HostIp) && c.HostIp.Equals(newConsole.HostIp, StringComparison.OrdinalIgnoreCase)));

                    _config.RegisteredConsoles.Insert(0, newConsole);
                    SaveConfig();
                    RebuildConsoleCardsUi();

                    TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(0, 230, 118));
                    TxtRegistStatus.Text = $"ลงทะเบียนเครื่อง '{newConsole.ServerNickname}' สำเร็จเรียบร้อย! สามารถกดปุ่ม 'เริ่มสตรีมและแปลภาษา' ด้านซ้ายมือได้ทันทีครับ";
                }
                else
                {
                    TxtRegistStatus.Foreground = new SolidColorBrush(Color.FromRgb(255, 82, 82));
                    TxtRegistStatus.Text = "การลงทะเบียนไม่สำเร็จ กรุณาตรวจสอบว่าเครื่อง PS5 เปิดหน้า Link Device (PIN 8 หลัก) อยู่ และอยู่ในวงแลนเดียวกัน";
                }
            });
        }

        private void WakeupRegisteredConsole(RegisteredConsoleInfo reg)
        {
            if (!ChiakiNative.IsLoaded) return;
            string ip = !string.IsNullOrWhiteSpace(reg.HostIp) ? reg.HostIp : TxtQuickIp.Text.Trim();
            if (string.IsNullOrEmpty(ip)) ip = "255.255.255.255";

            try
            {
                byte[] registKey = Convert.FromBase64String(reg.RegistKeyBase64);
                ChiakiNative.cw_wakeup(_discoveryPtr, ip, registKey, reg.IsPs5 ? 1 : 0);
                TxtDiscoveryStatus.Text = $"ส่งสัญญาณปลุกเครื่อง (Wakeup Packet) ไปยัง {reg.ServerNickname} ({ip}) เรียบร้อยแล้ว...";
            }
            catch { }
        }

        private void BtnQuickConnectIp_Click(object sender, RoutedEventArgs e)
        {
            string ip = TxtQuickIp.Text.Trim();
            if (string.IsNullOrEmpty(ip)) return;

            var reg = _config.RegisteredConsoles.FirstOrDefault(c => c.HostIp.Equals(ip, StringComparison.OrdinalIgnoreCase))
                      ?? _config.RegisteredConsoles.FirstOrDefault();

            if (reg != null)
            {
                reg.HostIp = ip;
                SaveConfig();
                ConnectToRegisteredConsole(reg, sendWakeupFirst: false);
            }
            else
            {
                MessageBox.Show("กรุณาลงทะเบียนเครื่อง PS5 / PS4 ครั้งแรกในช่องด้านขวามือก่อนเชื่อมต่อครับ", "PS Translator", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }
        #endregion

        #region Native Chiaki Session Streaming (Video + Audio + Controller)
        private async void ConnectToRegisteredConsole(RegisteredConsoleInfo reg, bool sendWakeupFirst)
        {
            string ip = !string.IsNullOrWhiteSpace(reg.HostIp) ? reg.HostIp : TxtQuickIp.Text.Trim();
            if (string.IsNullOrWhiteSpace(ip))
            {
                MessageBox.Show("กรุณาระบุ IP Address ของเครื่อง PS5 ในช่องด้านล่างซ้ายก่อนเชื่อมต่อครับ", "PS Translator", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            reg.HostIp = ip;
            SaveConfig();

            ChiakiHomeView.Visibility = Visibility.Collapsed;
            ChiakiStreamView.Visibility = Visibility.Visible;
            TxtStreamCenterStatus.Visibility = Visibility.Visible;
            SyncCropBoxPixelsFromRatios();

            if (sendWakeupFirst)
            {
                TxtStreamCenterStatus.Text = $"กำลังส่งสัญญาณปลุกเครื่อง {reg.ServerNickname} ({ip})...";
                WakeupRegisteredConsole(reg);
                await Task.Delay(3500);
            }

            StartChiakiStreamSession(reg);
        }

        private void StartChiakiStreamSession(RegisteredConsoleInfo reg)
        {
            StopChiakiStreamSession();

            if (!ChiakiNative.IsLoaded)
            {
                TxtStreamCenterStatus.Text = "พร้อมแปลซับไตเติ้ลภาษาไทยในหน้าต่างโปรแกรม";
                return;
            }

            try
            {
                byte[] registKey = Convert.FromBase64String(reg.RegistKeyBase64);
                byte[] morningKey = Convert.FromBase64String(reg.MorningKeyBase64);

                TxtStreamCenterStatus.Text = $"กำลังเชื่อมต่อสตรีม PS5 ({reg.HostIp}) ที่ความละเอียด 1080p 60fps...";
                _sessionPtr = ChiakiNative.cw_session_start(
                    reg.HostIp,
                    registKey,
                    morningKey,
                    reg.IsPs5 ? 1 : 0,
                    1920,
                    1080,
                    60,
                    15000,
                    _videoCb,
                    _audioCb,
                    _eventCb
                );

                if (_sessionPtr == IntPtr.Zero)
                {
                    TxtStreamCenterStatus.Text = "ไม่สามารถเริ่มเซสชันสตรีมได้ กรุณาตรวจสอบว่าเครื่อง PS5 เปิดอยู่";
                }
            }
            catch (Exception ex)
            {
                TxtStreamCenterStatus.Text = $"เกิดข้อผิดพลาดในการเชื่อมต่อ: {ex.Message}";
            }
        }

        private void StopChiakiStreamSession()
        {
            try
            {
                if (_sessionPtr != IntPtr.Zero)
                {
                    IntPtr ptr = _sessionPtr;
                    _sessionPtr = IntPtr.Zero;
                    Task.Run(() =>
                    {
                        try { ChiakiNative.cw_session_stop(ptr); } catch { }
                    });
                }

                if (_gameAudioOut != null)
                {
                    _gameAudioOut.Stop();
                    _gameAudioOut.Dispose();
                    _gameAudioOut = null;
                    _gameAudioBuffer = null;
                }
            }
            catch { }
        }

        private void OnNativeVideoFrame(IntPtr bgraData, int width, int height, int stride)
        {
            if (bgraData == IntPtr.Zero || width <= 0 || height <= 0) return;

            try
            {
                // Copy frame for OCR in background
                lock (_frameLock)
                {
                    if (_latestStreamFrame == null || _latestStreamFrame.Width != width || _latestStreamFrame.Height != height)
                    {
                        _latestStreamFrame?.Dispose();
                        _latestStreamFrame = new System.Drawing.Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                    }

                    var rect = new System.Drawing.Rectangle(0, 0, width, height);
                    var bmpData = _latestStreamFrame.LockBits(rect, System.Drawing.Imaging.ImageLockMode.WriteOnly, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                    try
                    {
                        int bytes = stride * height;
                        unsafe
                        {
                            Buffer.MemoryCopy((void*)bgraData, (void*)bmpData.Scan0, bmpData.Stride * height, bytes);
                        }
                    }
                    finally
                    {
                        _latestStreamFrame.UnlockBits(bmpData);
                    }
                }

                if (_isBlackScreenMode) return;

                byte[] managedFrame = new byte[stride * height];
                Marshal.Copy(bgraData, managedFrame, 0, managedFrame.Length);

                Dispatcher.BeginInvoke(DispatcherPriority.Render, () =>
                {
                    if (_isBlackScreenMode) return;
                    if (TxtStreamCenterStatus.Visibility == Visibility.Visible)
                    {
                        TxtStreamCenterStatus.Visibility = Visibility.Collapsed;
                    }

                    if (_videoWriteableBmp == null || _videoWriteableBmp.PixelWidth != width || _videoWriteableBmp.PixelHeight != height)
                    {
                        _videoWriteableBmp = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgra32, null);
                        GameVideoImage.Source = _videoWriteableBmp;
                    }

                    _videoWriteableBmp.WritePixels(new Int32Rect(0, 0, width, height), managedFrame, stride, 0);
                });
            }
            catch { }
        }

        private void OnNativeAudioFrame(IntPtr pcmSamples, int samplesCount, int channels, int sampleRate)
        {
            if (_isGameAudioMuted || pcmSamples == IntPtr.Zero || samplesCount <= 0) return;

            try
            {
                if (_gameAudioOut == null || _gameAudioBuffer == null || _gameAudioRate != sampleRate || _gameAudioChannels != channels)
                {
                    _gameAudioOut?.Stop();
                    _gameAudioOut?.Dispose();

                    _gameAudioRate = sampleRate > 0 ? sampleRate : 48000;
                    _gameAudioChannels = channels > 0 ? channels : 2;

                    var format = new WaveFormat(_gameAudioRate, 16, _gameAudioChannels);
                    _gameAudioBuffer = new BufferedWaveProvider(format)
                    {
                        DiscardOnBufferOverflow = true,
                        BufferDuration = TimeSpan.FromMilliseconds(400)
                    };
                    _gameAudioOut = new WaveOutEvent { DesiredLatency = 80 };
                    _gameAudioOut.Init(_gameAudioBuffer);
                    _gameAudioOut.Play();
                }

                int byteLen = samplesCount * _gameAudioChannels * 2;
                byte[] audioBytes = new byte[byteLen];
                Marshal.Copy(pcmSamples, audioBytes, 0, byteLen);
                _gameAudioBuffer.AddSamples(audioBytes, 0, byteLen);
            }
            catch { }
        }

        private void OnNativeSessionEvent(int eventType, int quitReason, string reasonStr)
        {
            Dispatcher.BeginInvoke(() =>
            {
                // CHIAKI_EVENT_CONNECTED == 0, CHIAKI_EVENT_QUIT == 2
                if (eventType == 0)
                {
                    TxtStreamCenterStatus.Visibility = Visibility.Collapsed;
                }
                else if (eventType == 2)
                {
                    TxtStreamCenterStatus.Visibility = Visibility.Visible;
                    TxtStreamCenterStatus.Text = $"การเชื่อมต่อสตรีมสิ้นสุดลง ({reasonStr})";
                }
            });
        }

        private void PollAndSendControllerState()
        {
            if (_sessionPtr == IntPtr.Zero || !ChiakiNative.IsLoaded) return;

            uint buttons = _keyboardButtons;
            byte l2 = 0, r2 = 0;
            short lx = 0, ly = 0, rx = 0, ry = 0;

            if (XInputGamepad.TryGetState(out var xstate))
            {
                ushort wb = xstate.Gamepad.wButtons;
                if ((wb & XInputGamepad.XINPUT_A) != 0) buttons |= ChiakiNative.BTN_CROSS;
                if ((wb & XInputGamepad.XINPUT_B) != 0) buttons |= ChiakiNative.BTN_MOON;
                if ((wb & XInputGamepad.XINPUT_X) != 0) buttons |= ChiakiNative.BTN_BOX;
                if ((wb & XInputGamepad.XINPUT_Y) != 0) buttons |= ChiakiNative.BTN_PYRAMID;
                if ((wb & XInputGamepad.XINPUT_DPAD_LEFT) != 0) buttons |= ChiakiNative.BTN_DPAD_LEFT;
                if ((wb & XInputGamepad.XINPUT_DPAD_RIGHT) != 0) buttons |= ChiakiNative.BTN_DPAD_RIGHT;
                if ((wb & XInputGamepad.XINPUT_DPAD_UP) != 0) buttons |= ChiakiNative.BTN_DPAD_UP;
                if ((wb & XInputGamepad.XINPUT_DPAD_DOWN) != 0) buttons |= ChiakiNative.BTN_DPAD_DOWN;
                if ((wb & XInputGamepad.XINPUT_LEFT_SHOULDER) != 0) buttons |= ChiakiNative.BTN_L1;
                if ((wb & XInputGamepad.XINPUT_RIGHT_SHOULDER) != 0) buttons |= ChiakiNative.BTN_R1;
                if ((wb & XInputGamepad.XINPUT_LEFT_THUMB) != 0) buttons |= ChiakiNative.BTN_L3;
                if ((wb & XInputGamepad.XINPUT_RIGHT_THUMB) != 0) buttons |= ChiakiNative.BTN_R3;
                if ((wb & XInputGamepad.XINPUT_START) != 0) buttons |= ChiakiNative.BTN_OPTIONS;
                if ((wb & XInputGamepad.XINPUT_BACK) != 0) buttons |= ChiakiNative.BTN_TOUCHPAD;

                l2 = xstate.Gamepad.bLeftTrigger;
                r2 = xstate.Gamepad.bRightTrigger;
                lx = xstate.Gamepad.sThumbLX;
                // Invert Y axis from XInput (up = positive) to Chiaki (up = negative)
                ly = (short)Math.Clamp(-(int)xstate.Gamepad.sThumbLY, short.MinValue, short.MaxValue);
                rx = xstate.Gamepad.sThumbRX;
                ry = (short)Math.Clamp(-(int)xstate.Gamepad.sThumbRY, short.MinValue, short.MaxValue);
            }

            try
            {
                ChiakiNative.cw_session_set_controller(_sessionPtr, buttons, l2, r2, lx, ly, rx, ry);
            }
            catch { }
        }

        private void BtnEnterStreamView_Click(object sender, RoutedEventArgs e)
        {
            ChiakiHomeView.Visibility = Visibility.Collapsed;
            ChiakiStreamView.Visibility = Visibility.Visible;
            SyncCropBoxPixelsFromRatios();
        }

        private void BtnBackToHome_Click(object sender, RoutedEventArgs e)
        {
            StopChiakiStreamSession();
            ChiakiStreamView.Visibility = Visibility.Collapsed;
            ChiakiHomeView.Visibility = Visibility.Visible;
            RebuildConsoleCardsUi();
        }
        #endregion

        #region Subtitle Styling, Font Size, Font Color & Opacity
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
                var color = (Color)ColorConverter.ConvertFromString(_config.ThaiTextColorHex);
                TranslatedSubtitleText.Foreground = new SolidColorBrush(color);
            }
            catch
            {
                TranslatedSubtitleText.Foreground = new SolidColorBrush(Color.FromRgb(255, 213, 79));
            }

            try
            {
                var engColor = (Color)ColorConverter.ConvertFromString(_config.EngTextColorHex);
                OriginalSubtitleText.Foreground = new SolidColorBrush(engColor);
            }
            catch
            {
                OriginalSubtitleText.Foreground = new SolidColorBrush(Color.FromRgb(204, 204, 204));
            }

            byte alpha = (byte)Math.Clamp((int)Math.Round(_config.SubtitleBgOpacityPercent * 2.55), 0, 255);
            InWindowSubtitleBox.Background = new SolidColorBrush(Color.FromArgb(alpha, 8, 10, 15));
            InWindowSubtitleBox.BorderBrush = alpha < 15
                ? Brushes.Transparent
                : new SolidColorBrush(Color.FromArgb(110, 0, 230, 118));

            UpdateColorButtonsHighlight();
        }

        private void SetButtonHighlight(Button btn, bool active)
        {
            if (btn == null) return;
            if (active)
            {
                btn.Background = new SolidColorBrush(Color.FromRgb(46, 125, 50)); // #2E7D32
                btn.BorderBrush = new SolidColorBrush(Color.FromRgb(0, 230, 118)); // #00E676
                btn.BorderThickness = new Thickness(2);
                btn.Foreground = Brushes.White;
            }
            else
            {
                btn.Background = new SolidColorBrush(Color.FromRgb(37, 42, 52)); // #252A34
                btn.BorderBrush = new SolidColorBrush(Color.FromArgb(85, 255, 255, 255));
                btn.BorderThickness = new Thickness(1);
                btn.Foreground = new SolidColorBrush(Color.FromRgb(190, 190, 190));
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
            GameVideoImage.Stretch = _config.AspectMode == "STRETCH" ? Stretch.Fill : Stretch.Uniform;
            SetButtonHighlight(BtnAspect169, _config.AspectMode == "16_9");
            SetButtonHighlight(BtnAspectFit, _config.AspectMode == "FIT");
            SetButtonHighlight(BtnAspectStretch, _config.AspectMode == "STRETCH");
        }
        #endregion

        #region Real-Time 2x Bicubic OCR & Smart c/e Correction (Directly on Stream Frame)
        private async Task ProcessOcrFromLatestFrameAsync()
        {
            if (!_isRunning || _isBusyOcr || _ocrEngine == null || ChiakiStreamView.Visibility != Visibility.Visible) return;

            System.Drawing.Bitmap? croppedRaw = null;
            lock (_frameLock)
            {
                if (_latestStreamFrame == null) return;
                int fw = _latestStreamFrame.Width;
                int fh = _latestStreamFrame.Height;

                int cx = Math.Clamp((int)Math.Round(_config.CropLeftRatio * fw), 0, fw - 40);
                int cy = Math.Clamp((int)Math.Round(_config.CropTopRatio * fh), 0, fh - 20);
                int cw = Math.Clamp((int)Math.Round(_config.CropWidthRatio * fw), 40, fw - cx);
                int ch = Math.Clamp((int)Math.Round(_config.CropHeightRatio * fh), 20, fh - cy);

                croppedRaw = new System.Drawing.Bitmap(cw, ch, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
                using (var g = System.Drawing.Graphics.FromImage(croppedRaw))
                {
                    g.DrawImage(_latestStreamFrame,
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

        #region In-Window Green Crop Box & Subtitle Dragging
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

        #region UI Event Handlers (Font Size, Color, Fullscreen, Mute, Voice, Speed)
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
                return;
            }
            if (e.Key == Key.Escape && _isFullscreen)
            {
                ToggleFullscreen();
                return;
            }

            UpdateKeyboardControllerFlag(e.Key, pressed: true);
        }

        private void Window_KeyUp(object sender, KeyEventArgs e)
        {
            UpdateKeyboardControllerFlag(e.Key, pressed: false);
        }

        private void UpdateKeyboardControllerFlag(Key key, bool pressed)
        {
            uint mask = key switch
            {
                Key.Enter or Key.Space => ChiakiNative.BTN_CROSS,
                Key.Back => ChiakiNative.BTN_MOON,
                Key.Left => ChiakiNative.BTN_DPAD_LEFT,
                Key.Right => ChiakiNative.BTN_DPAD_RIGHT,
                Key.Up => ChiakiNative.BTN_DPAD_UP,
                Key.Down => ChiakiNative.BTN_DPAD_DOWN,
                _ => 0U
            };
            if (mask == 0) return;
            if (pressed) _keyboardButtons |= mask;
            else _keyboardButtons &= ~mask;
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

        private void BtnMuteGameAudio_Click(object sender, RoutedEventArgs e)
        {
            _isGameAudioMuted = !_isGameAudioMuted;
            if (_isGameAudioMuted)
            {
                _gameAudioBuffer?.ClearBuffer();
                BtnQuickMuteGame.Content = "เปิดเสียงเกม";
                SetButtonHighlight(BtnQuickMuteGame, true);
            }
            else
            {
                BtnQuickMuteGame.Content = "ปิดเสียงเกม";
                SetButtonHighlight(BtnQuickMuteGame, false);
            }
        }

        private void BtnToggleCapture_Click(object sender, RoutedEventArgs e)
        {
            _isRunning = !_isRunning;
            if (_isRunning)
            {
                BtnToggleCapture.Content = "ระบบแปลภาษาทำงานอยู่ (คลิกเพื่อหยุดชั่วคราว)";
                BtnToggleCapture.Background = new SolidColorBrush(Color.FromRgb(46, 125, 50));
                BtnToggleCapture.BorderBrush = new SolidColorBrush(Color.FromRgb(0, 230, 118));
            }
            else
            {
                BtnToggleCapture.Content = "หยุดแปลภาษาชั่วคราว (คลิกเพื่อเริ่มทำงานต่อ)";
                BtnToggleCapture.Background = new SolidColorBrush(Color.FromRgb(183, 28, 28));
                BtnToggleCapture.BorderBrush = new SolidColorBrush(Color.FromRgb(255, 82, 82));
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
        #endregion

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            SaveConfig();
            _controllerTimer.Stop();
            _ocrTimer.Stop();
            StopChiakiStreamSession();
            if (_discoveryPtr != IntPtr.Zero)
            {
                try { ChiakiNative.cw_discovery_stop(_discoveryPtr); } catch { }
                _discoveryPtr = IntPtr.Zero;
            }
            AudioTtsHelper.StopCurrentPlayback();
        }
    }
}
