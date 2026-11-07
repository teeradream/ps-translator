using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace PSTranslatorWin
{
    public partial class SubtitleOverlayWindow : Window
    {
        private bool _isBlackScreenMode = false;

        public SubtitleOverlayWindow()
        {
            InitializeComponent();
        }

        private void OverlayBorder_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (!_isBlackScreenMode && e.ButtonState == MouseButtonState.Pressed)
            {
                try { DragMove(); } catch { }
            }
        }

        public void SetSubtitle(string english, string thai)
        {
            OriginalText.Text = english;
            TranslatedText.Text = thai;
        }

        public void SetBlackScreenMode(bool enabled)
        {
            _isBlackScreenMode = enabled;
            if (enabled)
            {
                Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 0, 0));
                WindowState = WindowState.Maximized;
                OverlayBorder.Background = System.Windows.Media.Brushes.Transparent;
                OverlayBorder.BorderThickness = new Thickness(0);
                OriginalText.FontSize = 22;
                TranslatedText.FontSize = 36;
            }
            else
            {
                Background = System.Windows.Media.Brushes.Transparent;
                WindowState = WindowState.Normal;
                Width = 960;
                Height = 160;
                OverlayBorder.Background = new SolidColorBrush(System.Windows.Media.Color.FromArgb(216, 8, 8, 8));
                OverlayBorder.BorderThickness = new Thickness(1);
                OriginalText.FontSize = 16;
                TranslatedText.FontSize = 26;
            }
        }
    }
}
