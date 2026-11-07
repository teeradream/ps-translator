using System.IO;
using System.Runtime.InteropServices;

namespace PSTranslatorWin
{
    public static class ChiakiNative
    {
        private const string DllName = "chiaki_win_bridge.dll";

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool SetDllDirectory(string lpPathName);

        public static bool IsLoaded { get; private set; }

        static ChiakiNative()
        {
            try
            {
                string baseDir = AppContext.BaseDirectory;
                SetDllDirectory(baseDir);
                int rc = cw_init();
                IsLoaded = (rc == 0);
            }
            catch
            {
                IsLoaded = false;
            }
        }

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        public delegate void CwDiscoveryHostCallback(
            int index,
            int totalCount,
            int state,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostAddr,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostName,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostId,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostType,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string runningAppName,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string runningAppTitleId
        );

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        public delegate void CwRegistEventCallback(
            int eventType,
            int isPs5,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string serverNickname,
            IntPtr serverMac6,
            IntPtr rpRegistKey16,
            IntPtr rpKey16
        );

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        public delegate void CwVideoFrameCallback(
            IntPtr bgraData,
            int width,
            int height,
            int stride
        );

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        public delegate void CwAudioFrameCallback(
            IntPtr pcmSamples,
            int samplesCount,
            int channels,
            int sampleRate
        );

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
        public delegate void CwSessionEventCallback(
            int eventType,
            int quitReason,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string reasonStr
        );

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern int cw_init();

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr cw_discovery_start(CwDiscoveryHostCallback cb);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void cw_discovery_stop(IntPtr ptr);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern int cw_wakeup(
            IntPtr discPtr,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostAddr,
            byte[] registKey16,
            int isPs5
        );

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr cw_regist_start(
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostAddr,
            int broadcast,
            byte[] psnAccountId8,
            uint pin,
            int targetType,
            CwRegistEventCallback cb
        );

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void cw_regist_stop(IntPtr ptr);

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr cw_session_start(
            [MarshalAs(UnmanagedType.LPUTF8Str)] string hostAddr,
            byte[] registKey16,
            byte[] morning16,
            int isPs5,
            int width,
            int height,
            int maxFps,
            int bitrate,
            CwVideoFrameCallback videoCb,
            CwAudioFrameCallback audioCb,
            CwSessionEventCallback eventCb
        );

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void cw_session_set_controller(
            IntPtr ptr,
            uint buttons,
            byte l2State,
            byte r2State,
            short leftX,
            short leftY,
            short rightX,
            short rightY
        );

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void cw_session_set_login_pin(
            IntPtr ptr,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string pin
        );

        [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
        public static extern void cw_session_stop(IntPtr ptr);

        // Chiaki Controller Button Bitmasks (from chiaki/controller.h)
        public const uint BTN_CROSS     = (1U << 0);
        public const uint BTN_MOON      = (1U << 1); // Circle
        public const uint BTN_BOX       = (1U << 2); // Square
        public const uint BTN_PYRAMID   = (1U << 3); // Triangle
        public const uint BTN_DPAD_LEFT = (1U << 4);
        public const uint BTN_DPAD_RIGHT= (1U << 5);
        public const uint BTN_DPAD_UP   = (1U << 6);
        public const uint BTN_DPAD_DOWN = (1U << 7);
        public const uint BTN_L1        = (1U << 8);
        public const uint BTN_R1        = (1U << 9);
        public const uint BTN_L3        = (1U << 10);
        public const uint BTN_R3        = (1U << 11);
        public const uint BTN_OPTIONS   = (1U << 12);
        public const uint BTN_SHARE     = (1U << 13);
        public const uint BTN_TOUCHPAD  = (1U << 14);
        public const uint BTN_PS        = (1U << 15);
    }

    public static class XInputGamepad
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct XINPUT_GAMEPAD
        {
            public ushort wButtons;
            public byte bLeftTrigger;
            public byte bRightTrigger;
            public short sThumbLX;
            public short sThumbLY;
            public short sThumbRX;
            public short sThumbRY;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct XINPUT_STATE
        {
            public uint dwPacketNumber;
            public XINPUT_GAMEPAD Gamepad;
        }

        [DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")]
        private static extern uint XInputGetState14(uint dwUserIndex, out XINPUT_STATE pState);

        [DllImport("xinput9_1_0.dll", EntryPoint = "XInputGetState")]
        private static extern uint XInputGetState910(uint dwUserIndex, out XINPUT_STATE pState);

        public static bool TryGetState(out XINPUT_STATE state)
        {
            state = default;
            for (uint i = 0; i < 4; i++)
            {
                try
                {
                    if (XInputGetState14(i, out state) == 0) return true;
                }
                catch
                {
                    try
                    {
                        if (XInputGetState910(i, out state) == 0) return true;
                    }
                    catch { }
                }
            }
            return false;
        }

        // XInput masks
        public const ushort XINPUT_DPAD_UP        = 0x0001;
        public const ushort XINPUT_DPAD_DOWN      = 0x0002;
        public const ushort XINPUT_DPAD_LEFT      = 0x0004;
        public const ushort XINPUT_DPAD_RIGHT     = 0x0008;
        public const ushort XINPUT_START          = 0x0010;
        public const ushort XINPUT_BACK           = 0x0020;
        public const ushort XINPUT_LEFT_THUMB     = 0x0040;
        public const ushort XINPUT_RIGHT_THUMB    = 0x0080;
        public const ushort XINPUT_LEFT_SHOULDER  = 0x0100;
        public const ushort XINPUT_RIGHT_SHOULDER = 0x0200;
        public const ushort XINPUT_A              = 0x1000;
        public const ushort XINPUT_B              = 0x2000;
        public const ushort XINPUT_X              = 0x4000;
        public const ushort XINPUT_Y              = 0x8000;
    }
}
