using System.IO;
using System.Net.Http;
using System.Web;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace PSTranslatorWin
{
    public class SpeedWaveProvider : ISampleProvider
    {
        private readonly ISampleProvider _source;

        public SpeedWaveProvider(ISampleProvider source, float speed)
        {
            _source = source;
            float clampedSpeed = Math.Clamp(speed, 0.8f, 1.8f);
            int newRate = (int)(_source.WaveFormat.SampleRate * clampedSpeed);
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(newRate, _source.WaveFormat.Channels);
        }

        public WaveFormat WaveFormat { get; }

        public int Read(float[] buffer, int offset, int count)
        {
            return _source.Read(buffer, offset, count);
        }
    }

    public static class AudioTtsHelper
    {
        private static readonly HttpClient _http = new HttpClient();
        private static WaveOutEvent? _outputDevice;
        private static AudioFileReader? _audioFile;
        private static readonly object _lock = new object();

        public static async Task SpeakThaiAsync(string text, bool isFemale, float speedRate)
        {
            if (string.IsNullOrWhiteSpace(text)) return;

            string tempFile = Path.Combine(Path.GetTempPath(), $"ps_trans_tts_{Guid.NewGuid():N}.mp3");

            try
            {
                string encoded = HttpUtility.UrlEncode(text);
                string url = $"https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=th&q={encoded}";
                _http.DefaultRequestHeaders.Remove("User-Agent");
                _http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                byte[] mp3Bytes = await _http.GetByteArrayAsync(url);
                await File.WriteAllBytesAsync(tempFile, mp3Bytes);

                await Task.Run(() =>
                {
                    lock (_lock)
                    {
                        StopCurrentPlayback();
                        _audioFile = new AudioFileReader(tempFile);
                        ISampleProvider sampleProvider = _audioFile.ToSampleProvider();

                        if (!isFemale)
                        {
                            // Shift pitch down to a natural male baritone voice while preserving speech tempo
                            var pitchShifter = new SmbPitchShiftingSampleProvider(sampleProvider)
                            {
                                PitchFactor = 0.74f
                            };
                            sampleProvider = pitchShifter;
                        }
                        else
                        {
                            // Crisp natural female voice
                            var pitchShifter = new SmbPitchShiftingSampleProvider(sampleProvider)
                            {
                                PitchFactor = 1.04f
                            };
                            sampleProvider = pitchShifter;
                        }

                        if (Math.Abs(speedRate - 1.0f) > 0.05f)
                        {
                            sampleProvider = new SpeedWaveProvider(sampleProvider, speedRate);
                        }

                        _outputDevice = new WaveOutEvent();
                        _outputDevice.Init(sampleProvider);
                        _outputDevice.Play();
                    }
                });
            }
            catch { }
        }

        public static void StopCurrentPlayback()
        {
            try
            {
                if (_outputDevice != null)
                {
                    _outputDevice.Stop();
                    _outputDevice.Dispose();
                    _outputDevice = null;
                }
                if (_audioFile != null)
                {
                    string oldFile = _audioFile.FileName;
                    _audioFile.Dispose();
                    _audioFile = null;
                    try { if (File.Exists(oldFile)) File.Delete(oldFile); } catch { }
                }
            }
            catch { }
        }
    }
}
