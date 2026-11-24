package com.metallic.chiaki.stream

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Built-in local HTTP server (port 8791) for casting live Thai subtitles, left/right popup cards,
 * and note translations directly onto a Samsung Tizen TV (.wgt) or LG/Android TV overlay over Wi-Fi.
 *
 * Compatible with both PS-Translator-Samsung-TV.wgt and vi-translator-tizen-tv.wgt.
 * Auto-pairs immediately on the local home network without requiring manual code entry.
 */
class TvOverlayServer(
	private val context: Context,
	private val onTvConnectionChanged: (String?) -> Unit
) {
	companion object {
		const val PORT = 8791
		// Must be 32..128 lowercase hex chars (/^[0-9a-f]{32,128}$/) to pass Tizen app.js TOKEN check
		private const val DEFAULT_TOKEN = "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123"

		fun getLocalWifiIp(context: Context): String {
			try {
				val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
				val ipInt = wm?.connectionInfo?.ipAddress ?: 0
				if (ipInt != 0) {
					val b1 = ipInt and 0xff
					val b2 = (ipInt shr 8) and 0xff
					val b3 = (ipInt shr 16) and 0xff
					val b4 = (ipInt shr 24) and 0xff
					val ip = "$b1.$b2.$b3.$b4"
					if (ip != "0.0.0.0" && !ip.startsWith("127.")) return ip
				}
			} catch (_: Exception) {}

			try {
				val interfaces = NetworkInterface.getNetworkInterfaces()
				while (interfaces.hasMoreElements()) {
					val nif = interfaces.nextElement()
					if (!nif.isUp || nif.isLoopback) continue
					val addrs = nif.inetAddresses
					while (addrs.hasMoreElements()) {
						val addr = addrs.nextElement()
						if (addr is Inet4Address && !addr.isLoopbackAddress) {
							val ip = addr.hostAddress ?: continue
							if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
								return ip
							}
						}
					}
				}
			} catch (_: Exception) {}
			return "ไม่พบ IP Wi-Fi"
		}
	}

	private val running = AtomicBoolean(false)
	private var serverSocket: ServerSocket? = null
	private val executor = Executors.newCachedThreadPool()

	@Volatile
	var isTvCastEnabled: Boolean = true

	@Volatile
	var connectedTvName: String? = null
		private set

	@Volatile
	private var lastTvSeenMs: Long = 0L

	@Volatile
	private var stateJsonString: String = buildEmptyStateJson()

	@Volatile
	private var stateSeq: Long = 1L

	fun start() {
		if (running.getAndSet(true)) return
		executor.execute {
			try {
				val ss = ServerSocket(PORT)
				ss.reuseAddress = true
				serverSocket = ss
				while (running.get()) {
					val client = try {
						ss.accept()
					} catch (_: Exception) {
						break
					}
					executor.execute { handleClient(client) }
				}
			} catch (e: Exception) {
				android.util.Log.w("PSTranslatorTV", "TvOverlayServer failed to bind port $PORT: ${e.message}")
			}
		}
	}

	fun stop() {
		running.set(false)
		try {
			serverSocket?.close()
		} catch (_: Exception) {}
		serverSocket = null
	}

	fun updateOverlayState(
		subtitles: List<Pair<String, String>>,
		maxHistoryLines: Int,
		fontSizeSp: Int,
		textColorInt: Int,
		bgOpacityPercent: Int,
		subBgEnabled: Boolean,
		position: String,
		verticalPercent: Int,
		isSubtitleEnabled: Boolean,
		popupLeftText: String?,
		popupRightText: String?,
		popupLeftVerticalPercent: Int,
		popupRightVerticalPercent: Int,
		noteText: String?
	) {
		val seq = ++stateSeq
		val colorHex = String.format("#%06X", 0xFFFFFF and textColorInt)
		val subsArray = JSONArray()
		if (isSubtitleEnabled) {
			val limit = maxHistoryLines.coerceIn(1, 3)
			for (i in 0 until minOf(subtitles.size, limit)) {
				val (en, th) = subtitles[i]
				subsArray.put(JSONObject().apply {
					put("en", en)
					put("th", th)
				})
			}
		}
		val obj = JSONObject().apply {
			put("seq", seq)
			put("areas", true)
			put("overlays", isTvCastEnabled)
			put("fontSizeSp", fontSizeSp)
			put("textColor", colorHex)
			put("bgOpacity", bgOpacityPercent)
			put("subBgEnabled", subBgEnabled)
			put("position", position)
			put("verticalPercent", verticalPercent.coerceIn(0, 100))
			put("maxLines", maxHistoryLines)
			put("subtitles", subsArray)
			put("popupLeft", popupLeftText ?: "")
			put("popupRight", popupRightText ?: "")
			put("popupLeftVerticalPercent", popupLeftVerticalPercent.coerceIn(0, 100))
			put("popupRightVerticalPercent", popupRightVerticalPercent.coerceIn(0, 100))
			put("noteText", noteText ?: "")
		}
		stateJsonString = obj.toString()
	}

	private fun buildEmptyStateJson(): String {
		return JSONObject().apply {
			put("seq", 0)
			put("areas", true)
			put("overlays", true)
			put("fontSizeSp", 26)
			put("textColor", "#00FF88")
			put("bgOpacity", 80)
			put("position", "BOTTOM")
			put("verticalPercent", 15)
			put("maxLines", 3)
			put("subtitles", JSONArray())
			put("popupLeft", "")
			put("popupRight", "")
			put("noteText", "")
		}.toString()
	}

	private fun handleClient(socket: Socket) {
		try {
			socket.soTimeout = 5000
			val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
			val requestLine = reader.readLine() ?: return
			val parts = requestLine.split(" ")
			if (parts.size < 2) return
			val method = parts[0].uppercase()
			val fullPath = parts[1]
			val path = fullPath.substringBefore('?')

			var contentLength = 0
			while (true) {
				val hdr = reader.readLine() ?: break
				if (hdr.isEmpty()) break
				if (hdr.startsWith("Content-Length:", ignoreCase = true)) {
					contentLength = hdr.substringAfter(':').trim().toIntOrNull() ?: 0
				}
			}

			val body = if (contentLength in 1..65536) {
				val buf = CharArray(contentLength)
				var readTotal = 0
				while (readTotal < contentLength) {
					val r = reader.read(buf, readTotal, contentLength - readTotal)
					if (r <= 0) break
					readTotal += r
				}
				String(buf, 0, readTotal)
			} else {
				""
			}

			when {
				method == "OPTIONS" -> {
					sendResponse(socket, 200, "text/plain", "")
				}
				path == "/vit-tv/offer" -> {
					if (!isTvCastEnabled) {
						sendResponse(socket, 404, "application/json", "{\"error\":\"disabled\"}")
						return
					}
					var tvName = "Samsung TV"
					try {
						val json = JSONObject(body)
						val n = json.optString("name", "").trim()
						if (n.isNotEmpty()) tvName = n
					} catch (_: Exception) {}

					markTvConnected(tvName)
					val resp = JSONObject().apply {
						put("token", DEFAULT_TOKEN)
						put("pc_name", "PS Translator")
					}.toString()
					sendResponse(socket, 200, "application/json; charset=utf-8", resp)
				}
				path == "/tv.json" || path == "/state.json" -> {
					lastTvSeenMs = System.currentTimeMillis()
					if (connectedTvName == null) {
						markTvConnected("Samsung TV")
					}
					sendResponse(socket, 200, "application/json; charset=utf-8", stateJsonString)
				}
				path == "/tv/forget" -> {
					connectedTvName = null
					onTvConnectionChanged(null)
					sendResponse(socket, 200, "application/json", "{\"ok\":true}")
				}
				path == "/tv" || path == "/" -> {
					lastTvSeenMs = System.currentTimeMillis()
					if (connectedTvName == null) {
						markTvConnected("Samsung TV")
					}
					sendResponse(socket, 200, "text/html; charset=utf-8", TV_OVERLAY_HTML)
				}
				path == "/font.ttf" -> {
					val fontBytes = try {
						context.assets.open("gta_prompt_light.ttf").use { it.readBytes() }
					} catch (_: Exception) {
						null
					}
					if (fontBytes != null) {
						sendBinaryResponse(socket, 200, "font/ttf", fontBytes)
					} else {
						sendResponse(socket, 404, "text/plain", "Not Found")
					}
				}
				path == "/font-sub.ttf" -> {
					val fontBytes = try {
						context.assets.open("gta_prompt_semibold.ttf").use { it.readBytes() }
					} catch (_: Exception) {
						null
					}
					if (fontBytes != null) {
						sendBinaryResponse(socket, 200, "font/ttf", fontBytes)
					} else {
						sendResponse(socket, 404, "text/plain", "Not Found")
					}
				}
				else -> {
					sendResponse(socket, 404, "text/plain", "Not Found")
				}
			}
		} catch (_: Exception) {
		} finally {
			try { socket.close() } catch (_: Exception) {}
		}
	}

	private fun markTvConnected(tvName: String) {
		lastTvSeenMs = System.currentTimeMillis()
		if (connectedTvName != tvName) {
			connectedTvName = tvName
			onTvConnectionChanged(tvName)
		}
	}

	private fun sendResponse(socket: Socket, statusCode: Int, contentType: String, body: String) {
		sendBinaryResponse(socket, statusCode, contentType, body.toByteArray(Charsets.UTF_8))
	}

	private fun sendBinaryResponse(socket: Socket, statusCode: Int, contentType: String, bytes: ByteArray) {
		val statusText = when (statusCode) {
			200 -> "OK"
			403 -> "Forbidden"
			404 -> "Not Found"
			else -> "OK"
		}
		val header = buildString {
			append("HTTP/1.1 $statusCode $statusText\r\n")
			append("Content-Type: $contentType\r\n")
			append("Content-Length: ${bytes.size}\r\n")
			append("Access-Control-Allow-Origin: *\r\n")
			append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
			append("Access-Control-Allow-Headers: Content-Type, X-VIT\r\n")
			append("Cache-Control: no-store, no-cache\r\n")
			append("Connection: close\r\n\r\n")
		}
		val out = socket.getOutputStream()
		out.write(header.toByteArray(Charsets.UTF_8))
		out.write(bytes)
		out.flush()
	}
}

private const val TV_OVERLAY_HTML = """<!DOCTYPE html>
<html lang="th">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=1920, height=1080">
<title>PS Translator TV Overlay</title>
<style>
  @font-face {
    font-family: 'GTAPromptLight';
    src: url('/font.ttf') format('truetype');
    font-weight: 100 900;
  }
  @font-face {
    font-family: 'GTAPromptSemiBold';
    src: url('/font-sub.ttf') format('truetype');
    font-weight: 100 900;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body {
    width: 100%;
    height: 100%;
    background: transparent !important;
    overflow: hidden;
    font-family: 'GTAPromptLight', 'Noto Sans Thai', 'Leelawadee UI', 'Tahoma', sans-serif;
    user-select: none;
  }
  #stage {
    position: relative;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }
  /* Main Subtitle Anchor — integer-pixel flex centering (NO transform sub-pixel blur!) */
  #sub-anchor {
    position: absolute;
    left: 0;
    right: 0;
    display: flex;
    justify-content: center;
    pointer-events: none;
  }
  /* Main Subtitle Box — floating borderless, auto-fits subtitle length */
  #sub-box {
    width: fit-content;
    max-width: 88%;
    min-width: 0;
    padding: 12px 26px;
    border-radius: 14px;
    border: none;
    box-shadow: none;
    text-align: center;
    transition: opacity 0.14s ease;
    display: none;
  }
  .sub-line {
    font-family: 'GTAPromptSemiBold', 'GTAPromptLight', 'Noto Sans Thai', 'Leelawadee UI', 'Tahoma', sans-serif;
    font-weight: 700;
    line-height: 1.32;
    letter-spacing: 0.3px;
    -webkit-font-smoothing: antialiased;
    text-rendering: geometricPrecision;
    text-shadow: 0 2px 3px rgba(0,0,0,0.98), 0 0 2px rgba(0,0,0,0.98), 0 4px 10px rgba(0,0,0,0.78);
    word-wrap: break-word;
  }
  .sub-line + .sub-line {
    margin-top: 6px;
  }
  /* Left Auto Popup Card — Authentic GTA 6 Dark Charcoal Tutorial Box (No Header / No Ticks) */
  #popup-left {
    position: absolute;
    top: 64px;
    left: 44px;
    width: fit-content;
    max-width: 36%;
    min-width: 240px;
    padding: 16px 24px;
    border-radius: 6px;
    background: rgba(30, 32, 40, 0.90);
    border: none;
    box-shadow: none;
    color: #FFFFFF;
    font-size: 27px;
    font-weight: 600;
    line-height: 1.34;
    word-wrap: break-word;
    display: none;
  }
  /* Right Auto Popup Card — GTA 6 Chat Bubble Style */
  #popup-right {
    position: absolute;
    top: 64px;
    right: 44px;
    max-width: 34%;
    min-width: 310px;
    padding: 16px 22px 18px;
    border-radius: 18px;
    border-bottom-right-radius: 4px;
    background: linear-gradient(180deg, rgba(242, 248, 253, 0.96) 0%, rgba(221, 235, 248, 0.95) 100%);
    border: 1.5px solid rgba(186, 208, 230, 0.90);
    box-shadow: 0 12px 30px rgba(0, 0, 0, 0.48);
    color: #243546;
    display: none;
  }
  #popup-right::after {
    content: '';
    position: absolute;
    bottom: -1px;
    right: -10px;
    width: 0;
    height: 0;
    border-style: solid;
    border-width: 14px 0 0 12px;
    border-color: transparent transparent transparent rgba(221, 235, 248, 0.95);
  }
  .chat-header {
    display: flex;
    align-items: center;
    margin-bottom: 8px;
  }
  .chat-avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: linear-gradient(135deg, #3A506B 0%, #1C2B3A 100%);
    border: 1.5px solid rgba(255,255,255,0.75);
    color: #FFFFFF;
    font-size: 17px;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 11px;
    flex-shrink: 0;
  }
  .chat-sender {
    flex: 1;
    color: #1B2A3A;
    font-size: 24px;
    font-weight: 700;
    line-height: 1.15;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .chat-ticks {
    color: #6C859E;
    font-size: 20px;
    font-weight: 700;
    margin-left: 10px;
    letter-spacing: -2px;
  }
  .chat-body {
    color: #243546;
    font-size: 26px;
    font-weight: 500;
    line-height: 1.34;
    word-wrap: break-word;
  }
  /* Scan Note Card (Liquid Glass) */
  #note-card {
    position: absolute;
    top: 10%;
    left: 50%;
    transform: translateX(-50%);
    max-width: 74%;
    padding: 24px 36px;
    border-radius: 24px;
    background: linear-gradient(180deg, rgba(26, 36, 56, 0.92) 0%, rgba(16, 24, 40, 0.90) 55%, rgba(10, 16, 27, 0.94) 100%);
    border: 2px solid rgba(0, 229, 255, 0.82);
    box-shadow: 0 16px 44px rgba(0, 0, 0, 0.75), inset 0 1.5px 0 rgba(255, 255, 255, 0.26);
    color: #FFFFFF;
    font-size: 30px;
    font-weight: 500;
    line-height: 1.45;
    text-shadow: 0 2px 6px rgba(0,0,0,0.95);
    white-space: pre-wrap;
    display: none;
  }
</style>
</head>
<body>
<div id="stage">
  <div id="popup-left"></div>
  <div id="popup-right">
    <div class="chat-header">
      <div id="pop-r-avatar" class="chat-avatar">M</div>
      <div id="pop-r-sender" class="chat-sender">ข้อความ</div>
      <div class="chat-ticks">✓✓</div>
    </div>
    <div id="pop-r-body" class="chat-body"></div>
  </div>
  <div id="note-card"></div>
  <div id="sub-anchor">
    <div id="sub-box">
      <div id="sub-0" class="sub-line"></div>
      <div id="sub-1" class="sub-line" style="opacity:0.65;display:none;"></div>
      <div id="sub-2" class="sub-line" style="opacity:0.35;display:none;"></div>
    </div>
  </div>
</div>
<script>
(function() {
  var lastSeq = -1;
  var subAnchor = document.getElementById('sub-anchor');
  var subBox = document.getElementById('sub-box');
  var sub0 = document.getElementById('sub-0');
  var sub1 = document.getElementById('sub-1');
  var sub2 = document.getElementById('sub-2');
  var popL = document.getElementById('popup-left');
  var popR = document.getElementById('popup-right');
  var popRAvatar = document.getElementById('pop-r-avatar');
  var popRSender = document.getElementById('pop-r-sender');
  var popRBody = document.getElementById('pop-r-body');
  var noteC = document.getElementById('note-card');

  function splitSenderBody(text, defSender) {
    var idx = text.indexOf(':');
    if (idx > 0 && idx <= 24) {
      var pre = text.slice(0, idx).trim();
      var bod = text.slice(idx + 1).trim();
      if (pre && bod && pre.split(/\s+/).length <= 3) {
        return { sender: pre, body: bod };
      }
    }
    return { sender: defSender, body: text };
  }

  function poll() {
    var x = new XMLHttpRequest();
    x.open('GET', '/state.json?_=' + Date.now(), true);
    x.timeout = 1800;
    x.onload = function() {
      if (x.status === 200) {
        try {
          var s = JSON.parse(x.responseText);
          if (s.seq !== lastSeq) {
            lastSeq = s.seq;
            render(s);
          }
        } catch (e) {}
      }
      setTimeout(poll, 180);
    };
    x.onerror = x.ontimeout = function() {
      setTimeout(poll, 600);
    };
    x.send(null);
  }

  function render(s) {
    if (!s.overlays) {
      subBox.style.display = 'none';
      popL.style.display = 'none';
      popR.style.display = 'none';
      noteC.style.display = 'none';
      return;
    }

    // Scale mobile sp to 1080p TV px nicely (e.g. 26sp -> ~40px on TV)
    var basePx = Math.round((s.fontSizeSp || 26) * 1.55);
    var color = s.textColor || '#00FF88';
    var bgEnabled = (s.subBgEnabled !== undefined) ? !!s.subBgEnabled : true;
    var alpha = bgEnabled ? (Math.max(0, Math.min(100, s.bgOpacity !== undefined ? s.bgOpacity : 80)) / 100.0) : 0;

    var vPct = (s.verticalPercent !== undefined) ? Math.max(0, Math.min(100, s.verticalPercent)) : ((s.position === 'CENTER') ? 50 : 15);
    var bottomPct = 3.5 + (vPct * 0.80);
    subAnchor.style.top = 'auto';
    subAnchor.style.bottom = bottomPct.toFixed(1) + '%';
    subBox.className = '';
    subBox.style.background = alpha > 0 ? ('rgba(16, 18, 26, ' + alpha.toFixed(2) + ')') : 'transparent';
    subBox.style.border = 'none';
    subBox.style.boxShadow = 'none';
    subBox.style.padding = alpha > 0 ? '12px 26px' : '4px 10px';

    var subs = s.subtitles || [];
    if (subs.length === 0) {
      subBox.style.display = 'none';
    } else {
      subBox.style.display = 'inline-block';
      sub0.textContent = subs[0].th || '';
      sub0.style.color = color;
      sub0.style.fontSize = basePx + 'px';

      if (subs.length >= 2 && (s.maxLines || 3) >= 2) {
        sub1.style.display = 'block';
        sub1.textContent = subs[1].th || '';
        sub1.style.color = color;
        sub1.style.fontSize = Math.round(basePx * 0.86) + 'px';
      } else {
        sub1.style.display = 'none';
      }

      if (subs.length >= 3 && (s.maxLines || 3) >= 3) {
        sub2.style.display = 'block';
        sub2.textContent = subs[2].th || '';
        sub2.style.color = color;
        sub2.style.fontSize = Math.round(basePx * 0.76) + 'px';
      } else {
        sub2.style.display = 'none';
      }
    }

    var popPx = Math.max(22, Math.round(basePx * 0.68));
    var popLeftPct = (s.popupLeftVerticalPercent !== undefined) ? Math.max(0, Math.min(100, s.popupLeftVerticalPercent)) : 8;
    var popRightPct = (s.popupRightVerticalPercent !== undefined) ? Math.max(0, Math.min(100, s.popupRightVerticalPercent)) : 8;

    if (s.popupLeft && s.popupLeft.length > 0) {
      popL.textContent = s.popupLeft;
      popL.style.fontSize = popPx + 'px';
      popL.style.top = (4 + popLeftPct * 0.76).toFixed(1) + '%';
      popL.style.display = 'block';
    } else {
      popL.style.display = 'none';
    }

    if (s.popupRight && s.popupRight.length > 0) {
      var parsedR = splitSenderBody(s.popupRight, 'ข้อความ');
      popRAvatar.textContent = (parsedR.sender.charAt(0) || 'M').toUpperCase();
      popRSender.textContent = parsedR.sender;
      popRSender.style.fontSize = Math.max(21, Math.round(popPx * 0.92)) + 'px';
      popRBody.textContent = parsedR.body;
      popRBody.style.fontSize = popPx + 'px';
      popR.style.top = (4 + popRightPct * 0.76).toFixed(1) + '%';
      popR.style.display = 'block';
    } else {
      popR.style.display = 'none';
    }

    if (s.noteText && s.noteText.length > 0) {
      noteC.textContent = s.noteText;
      noteC.style.display = 'block';
    } else {
      noteC.style.display = 'none';
    }
  }

  poll();
})();
</script>
</body>
</html>"""
