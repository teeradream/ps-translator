using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace PSTranslatorWin
{
    public partial class CropBoxWindow : Window
    {
        public event Action? OnCropSaved;

        public CropBoxWindow()
        {
            InitializeComponent();
        }

        private void Border_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ButtonState == MouseButtonState.Pressed)
            {
                try { DragMove(); } catch { }
            }
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            OnCropSaved?.Invoke();
            Hide();
        }

        public System.Drawing.Rectangle GetPhysicalScreenRect()
        {
            var source = PresentationSource.FromVisual(this);
            double scaleX = 1.0;
            double scaleY = 1.0;
            if (source?.CompositionTarget != null)
            {
                Matrix m = source.CompositionTarget.TransformToDevice;
                scaleX = m.M11;
                scaleY = m.M22;
            }

            int x = (int)Math.Round(Left * scaleX);
            int y = (int)Math.Round(Top * scaleY);
            int w = Math.Max(100, (int)Math.Round(ActualWidth * scaleX));
            int h = Math.Max(40, (int)Math.Round(ActualHeight * scaleY));

            return new System.Drawing.Rectangle(x, y, w, h);
        }
    }
}
