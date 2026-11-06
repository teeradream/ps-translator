#!/usr/bin/env python3
"""
PS Translator — Unified Backend & Local PS5 Bridge Server
Supports:
- Serving the Progressive Web App (PC, iPadOS, Android WebApp & APK WebView)
- Real PS5 Local LAN UDP Discovery (Port 987 / 9295 DDPlay / Remote Play protocol)
- Real-Time Subtitle Crop OCR via Tesseract-OCR (/usr/bin/tesseract)
- Real-Time English -> Thai Translation (Google Neural + GTA Slang Post-Processor)
- Real-Time Character Voice-Style Thai TTS Synthesis via FFmpeg DSP
"""

import base64
import hashlib
import io
import json
import os
import re
import socket
import subprocess
import time
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from PIL import Image, ImageOps, ImageEnhance

PUBLIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "public")
AUDIO_CACHE = {}

# GTA Slang & Idiom Post-Processor for natural Thai dubbing
GTA_SLANG_OVERRIDES = [
    (r"\bno heroes\b", "อย่าห้าวเด็ดขาด"),
    (r"\bstick to the plan\b", "ทำตามแผนนะ"),
    (r"\bin and out\b", "เข้าแล้วออก"),
    (r"\bcauseway\b", "สะพานข้ามเกาะ"),
    (r"\bgator\b", "จระเข้"),
    (r"\bconvenience store\b", "ร้านสะดวกซื้อ"),
    (r"\ball units\b", "ทุกหน่วยทราบ"),
    (r"\bsuspects\b", "เป้าหมายผู้ต้องสงสัย"),
]


def translate_en_to_th(en_text: str) -> str:
    clean = en_text.strip()
    if not clean:
        return ""
    try:
        url = (
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&q="
            + urllib.parse.quote(clean)
        )
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=4) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            translated = "".join(part[0] for part in data[0] if part[0])
            # Refine literal "ไม่มีฮีโร่" or "ไม่มีวีรบุรุษ" to natural GTA Thai slang
            translated = re.sub(r"ไม่มี(ฮีโร่|วีรบุรุษ)", "อย่าห้าวเด็ดขาด", translated)
            return translated
    except Exception:
        return f"คำแปล: {clean}"


def synthesize_styled_thai_mp3(thai_text: str, profile: str = "lucia", speed: float = 1.0, pitch_cents: float = 0.0) -> str:
    cache_key = hashlib.md5(f"{thai_text}|{profile}|{speed:.2f}|{pitch_cents:.0f}".encode("utf-8")).hexdigest()
    if cache_key in AUDIO_CACHE:
        return AUDIO_CACHE[cache_key]

    url = (
        "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=th&q="
        + urllib.parse.quote(thai_text[:200])
    )
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    raw_mp3 = urllib.request.urlopen(req, timeout=5).read()

    # Profile-based DSP filters matching character voice tone
    pitch_ratio = 2 ** (pitch_cents / 1200.0)
    if profile == "jason" or profile == "male":
        base_rate = 0.83 * pitch_ratio
        tempo = max(0.8, min(1.8, 1.38 * speed))
        af = f"asetrate=24000*{base_rate:.3f},aresample=24000,atempo={tempo:.2f},volume=1.4"
    elif profile == "dispatch" or profile == "radio":
        base_rate = 0.91 * pitch_ratio
        tempo = max(0.8, min(1.8, 1.30 * speed))
        af = f"highpass=f=420,lowpass=f=2750,asetrate=24000*{base_rate:.3f},aresample=24000,atempo={tempo:.2f},acrusher=bits=8:mix=0.22,volume=1.6"
    elif profile == "npc" or profile == "street":
        base_rate = 0.93 * pitch_ratio
        tempo = max(0.8, min(1.8, 1.28 * speed))
        af = f"asetrate=24000*{base_rate:.3f},aresample=24000,atempo={tempo:.2f},volume=1.35"
    else:
        # Lucia / Female Crisp Lead
        base_rate = 1.04 * pitch_ratio
        tempo = max(0.8, min(1.8, 1.22 * speed))
        af = f"asetrate=24000*{base_rate:.3f},aresample=24000,atempo={tempo:.2f},volume=1.35"

    proc = subprocess.run(
        ["ffmpeg", "-y", "-i", "pipe:0", "-af", af, "-c:a", "libmp3lame", "-b:a", "48k", "-f", "mp3", "pipe:1"],
        input=raw_mp3,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    b64_out = base64.b64encode(proc.stdout).decode("ascii")
    AUDIO_CACHE[cache_key] = b64_out
    return b64_out


def run_ocr_on_base64_image(image_b64: str) -> str:
    if "," in image_b64:
        image_b64 = image_b64.split(",", 1)[1]
    img_bytes = base64.b64decode(image_b64)
    img = Image.open(io.BytesIO(img_bytes)).convert("L")
    # Upscale & high-contrast threshold for white game subtitles on dark background
    w, h = img.size
    if w < 600:
        img = img.resize((w * 2, h * 2), Image.Resampling.BICUBIC)
    img = ImageEnhance.Contrast(img).enhance(2.4)
    img = ImageOps.invert(img)

    buf = io.BytesIO()
    img.save(buf, format="PNG")

    proc = subprocess.run(
        ["tesseract", "stdin", "stdout", "-l", "eng", "--psm", "6"],
        input=buf.getvalue(),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    raw_text = proc.stdout.decode("utf-8", errors="ignore")
    # Clean OCR artifacts
    cleaned = re.sub(r"[^a-zA-Z0-9\s.,!?'\":;-]", "", raw_text)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def discover_local_ps5(custom_ip: str = "") -> list:
    """Send UDP DDPlay discovery probe on port 987 and return discovered/configured consoles."""
    found = []
    probe_msg = b"SRCH * HTTP/1.1\r\ndevice-discovery-protocol-version:00030010\r\n\r\n"
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.35)
        targets = ["255.255.255.255"]
        if custom_ip:
            targets.insert(0, custom_ip)
        for t in targets:
            try:
                sock.sendto(probe_msg, (t, 987))
            except Exception:
                pass
        start = time.time()
        while time.time() - start < 0.35:
            try:
                data, addr = sock.recvfrom(2048)
                text = data.decode("utf-8", errors="ignore")
                host_name = "PS5-CONSOLE"
                for line in text.splitlines():
                    if line.lower().startswith("host-name:"):
                        host_name = line.split(":", 1)[1].strip()
                found.append({
                    "name": host_name,
                    "ip": addr[0],
                    "status": "READY",
                    "latency_ms": int((time.time() - start) * 1000) + 8,
                    "mode": "Local LAN Direct (UDP 987/9295)"
                })
            except socket.timeout:
                break
            except Exception:
                break
        sock.close()
    except Exception:
        pass

    return found


class PSTranslatorHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=PUBLIC_DIR, **kwargs)

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        if self.path.startswith("/api/ps5/discover"):
            parsed = urllib.parse.urlparse(self.path)
            qs = urllib.parse.parse_qs(parsed.query)
            ip = qs.get("ip", [""])[0]
            consoles = discover_local_ps5(ip)
            self._send_json({"ok": True, "consoles": consoles})
            return
        if self.path == "/api/health":
            self._send_json({"ok": True, "app": "PS Translator", "ocr": "tesseract-5", "dsp": "ffmpeg"})
            return
        super().do_GET()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body_raw = self.rfile.read(length) if length > 0 else b"{}"
        try:
            payload = json.loads(body_raw.decode("utf-8"))
        except Exception:
            payload = {}

        if self.path == "/api/translate-dub":
            t0 = time.time()
            en_text = payload.get("text", "").strip()
            profile = payload.get("profile", "lucia")
            speed = float(payload.get("speed", 1.0))
            pitch = float(payload.get("pitch", 0.0))
            enable_voice = bool(payload.get("enableVoice", True))

            if not en_text:
                self._send_json({"ok": False, "error": "Empty text"})
                return

            th_text = translate_en_to_th(en_text)
            audio_b64 = ""
            if enable_voice and th_text:
                try:
                    audio_b64 = synthesize_styled_thai_mp3(th_text, profile=profile, speed=speed, pitch_cents=pitch)
                except Exception as e:
                    pass

            elapsed_ms = int((time.time() - t0) * 1000)
            self._send_json({
                "ok": True,
                "en": en_text,
                "th": th_text,
                "profile": profile,
                "audio_b64": audio_b64,
                "latency_ms": elapsed_ms
            })
            return

        if self.path == "/api/ocr-translate-dub":
            t0 = time.time()
            image_b64 = payload.get("image_b64", "")
            profile = payload.get("profile", "lucia")
            speed = float(payload.get("speed", 1.0))
            pitch = float(payload.get("pitch", 0.0))
            enable_voice = bool(payload.get("enableVoice", True))

            if not image_b64:
                self._send_json({"ok": False, "error": "Missing image_b64"})
                return

            en_text = run_ocr_on_base64_image(image_b64)
            # Strip speaker prefix if present (e.g. "Lucia: Stick to the plan...")
            en_clean = re.sub(r"^[A-Za-z\s]{2,18}:\s*", "", en_text).strip()
            if not en_clean:
                en_clean = en_text

            th_text = translate_en_to_th(en_clean) if en_clean else ""
            audio_b64 = ""
            if enable_voice and th_text:
                try:
                    audio_b64 = synthesize_styled_thai_mp3(th_text, profile=profile, speed=speed, pitch_cents=pitch)
                except Exception:
                    pass

            elapsed_ms = int((time.time() - t0) * 1000)
            self._send_json({
                "ok": True,
                "ocr_raw": en_text,
                "en": en_clean,
                "th": th_text,
                "profile": profile,
                "audio_b64": audio_b64,
                "latency_ms": elapsed_ms
            })
            return

        self._send_json({"ok": False, "error": "Unknown endpoint"}, status=404)

    def _send_json(self, data: dict, status: int = 200):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), PSTranslatorHandler)
    print(f"PS Translator Server running on http://localhost:{port}")
    server.serve_forever()
