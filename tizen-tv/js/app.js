/*
 * VI Translator on a Samsung (Tizen) TV: the translations from VI Translator on the PC, over the
 * game from the console on an HDMI input.
 *
 * A Samsung TV lets no app draw over another input or app, so this app shows the console's HDMI
 * input itself (the TV window, behind this page) with the PC's TV page, transparent, on top.
 *
 * Not connected, it shows a code. A Tizen app can't be reached on the home network (it can't
 * listen), so it looks for VI Translator on the PC itself and offers it this TV's code there
 * (POST /vit-tv/offer); once the code is typed on the PC, the PC answers with its key for this
 * TV. From then on it's the same as the Android TV app: the TV page (/tv?t=<key>), a 403 when the
 * TV was removed on the PC (back to a code), /tv/forget on Disconnect.
 *
 * Plain ES5: it runs on the 2018 TVs' web engine (Chromium 56).
 */
(function () {
  "use strict";

  /** where VI Translator on the PC answers on the home network (its default port) */
  var PC_PORT = 8791;
  /** after the page couldn't be loaded (the PC off, the app closed) */
  var RETRY_MS = 5000;
  /** how often the PC is asked how it is */
  var CHECK_MS = 10000;
  /** how often a PC found on the network is offered the code */
  var OFFER_MS = 2000;
  /** pause between looks through the whole home network for the PC, while showing a code */
  var SWEEP_MS = 2500;
  /** hosts asked at once while looking through the network */
  var PARALLEL = 48;
  var HINT_MS = 6000;
  var TOKEN = /^[0-9a-zA-Z_-]{16,128}$/;

  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27 };

  // ---------------------------------------------------------------- strings

  /** The desktop app's interface languages, in its order: the strings' tag, and the name in itself. */
  var LANGUAGES = [
    ["th", "ไทย"], ["en", "English"], ["ar", "العربية"], ["hi", "हिन्दी"], ["bn", "বাংলা"], ["ur", "اردو"],
    ["id", "Bahasa Indonesia"], ["tr", "Türkçe"], ["vi", "Tiếng Việt"], ["fa", "فارسی"],
    ["ta", "தமிழ்"], ["te", "తెలుగు"], ["mr", "मराठी"], ["uk", "Українська"], ["nl", "Nederlands"],
    ["fil", "Filipino"], ["ms", "Bahasa Melayu"], ["sw", "Kiswahili"], ["ro", "Română"], ["el", "Ελληνικά"],
    ["cs", "Čeština"], ["fr", "Français"], ["it", "Italiano"], ["de", "Deutsch"], ["ru", "Русский"],
    ["ja", "日本語"], ["pl", "Polski"], ["ko", "한국어"], ["zh-Hans", "简体中文"], ["zh-Hant", "繁體中文"],
    ["es-MX", "Español (México)"], ["es", "Español (España)"], ["pt-BR", "Português (Brasil)"]
  ];

  var lang = "th";
  var S = STRINGS.th || STRINGS.en;

  /** The language picked in the app (saved as "language"), defaulting to Thai ("th"). */
  function setLanguage() {
    var chosen = chosenLanguage();
    var tag = /[?&]lang=([^&]+)/.test(location.search) ? RegExp.$1 : chosen ? chosen[0] : "th";
    lang = pickLanguage(tag);
    S = STRINGS[lang] || STRINGS.th || STRINGS.en;
  }

  /** The chosen language's [tag, name], or null for the TV's own. */
  function chosenLanguage() {
    var tag = prefs.get("language");
    for (var i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i][0] === tag) return LANGUAGES[i];
    return null;
  }

  /** The best of the app's languages for a tag ("pt-BR", "zh-TW", "es-AR", "en_US"). */
  function pickLanguage(tag) {
    tag = String(tag).replace("_", "-");
    var parts = tag.split("-");
    var base = parts[0].toLowerCase();
    var region = (parts[1] || "").toUpperCase();
    if (base === "in") base = "id";
    var exact = region ? base + "-" + region : base;
    if (STRINGS[exact]) return exact;
    if (base === "zh") return region === "TW" || region === "HK" || region === "MO" || /hant/i.test(tag) ? "zh-Hant" : "zh-Hans";
    if (base === "es" && region && region !== "ES") return "es-419";
    if (STRINGS[base]) return base;
    return "en";
  }

  function t(name, arg) {
    var s = S[name] || STRINGS.en[name] || "";
    return arg === undefined ? s : s.replace("%1$s", arg);
  }

  // ---------------------------------------------------------------- settings

  /** This TV's settings: the PC it's connected to (its address, its key for this TV), the input. */
  var prefs = {
    get: function (k) {
      try {
        return localStorage.getItem("vit." + k) || "";
      } catch (e) {
        return "";
      }
    },
    set: function (k, v) {
      try {
        if (v) localStorage.setItem("vit." + k, v);
        else localStorage.removeItem("vit." + k);
      } catch (e) {
        // (no storage: connected until the app closes)
      }
    },
    /** this app's own id: the PC knows this TV by it (connected again, it replaces the old key) */
    id: function () {
      var id = prefs.get("id");
      if (!id) {
        id = uuid();
        prefs.set("id", id);
      }
      return id;
    },
    connected: function () {
      return !!(prefs.get("pc") && prefs.get("token"));
    },
    connect: function (pc, token, pcName) {
      prefs.set("pc", pc);
      prefs.set("token", token);
      prefs.set("pc_name", pcName);
    },
    disconnect: function () {
      prefs.set("pc", "");
      prefs.set("token", "");
      prefs.set("pc_name", "");
    }
  };

  function random(n) {
    var a = new Uint32Array(1);
    (window.crypto || window.msCrypto).getRandomValues(a);
    return a[0] % n;
  }

  function uuid() {
    var h = "";
    var b = new Uint8Array(16);
    window.crypto.getRandomValues(b);
    b[6] = (b[6] & 15) | 64;
    b[8] = (b[8] & 63) | 128;
    for (var i = 0; i < 16; i++) {
      h += (b[i] < 16 ? "0" : "") + b[i].toString(16);
      if (i === 3 || i === 5 || i === 7 || i === 9) h += "-";
    }
    return h;
  }

  // ---------------------------------------------------------------- the TV

  /** The TV's name for the PC app: "Samsung QE55Q60R". */
  function tvName() {
    var m = "";
    try {
      m = webapis.productinfo.getRealModel() || webapis.productinfo.getModel() || "";
    } catch (e) {
      // (not on a TV)
    }
    // (the emulator's model is "0")
    return /[a-z]/i.test(m) ? "Samsung " + m : "Samsung TV";
  }

  /** This TV's IPv4 address on the home network, or "" (then cb). */
  function ownAddress(cb) {
    var ip = "";
    try {
      ip = webapis.network.getIp() || "";
    } catch (e) {
      // (older firmware, or not on a TV)
    }
    if (home(ip)) return cb(ip);
    // (in a browser, to try the app: the address it was opened at, tools/mock-pc.js)
    if (!window.tizen || !tizen.systeminfo) return cb(home(location.hostname) ? location.hostname : "");
    var props = ["ETHERNET_NETWORK", "WIFI_NETWORK"];
    (function next() {
      var p = props.shift();
      if (!p) return cb("");
      try {
        tizen.systeminfo.getPropertyValue(
          p,
          function (n) {
            if (n && home(n.ipAddress)) cb(n.ipAddress);
            else next();
          },
          next
        );
      } catch (e) {
        next();
      }
    })();
  }

  /** An IPv4 address on a home network. */
  function home(ip) {
    var m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip || "");
    if (!m) return false;
    var a = +m[1];
    var b = +m[2];
    if (+m[3] > 255 || +m[4] > 255) return false;
    return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }

  /** "ip:port", an IPv4 address on a home network and a port; null otherwise. */
  function address(s) {
    var m = /^([0-9.]+):(\d{1,5})$/.exec(s || "");
    if (!m || !home(m[1]) || +m[2] < 1 || +m[2] > 65535) return null;
    return m[1] + ":" + +m[2];
  }

  // ---------------------------------------------------------------- the PC

  /** An HTTP request: cb(status, body), status 0 for no answer. */
  function request(method, url, body, timeoutMs, cb) {
    var x = new XMLHttpRequest();
    var done = false;
    function end(status, text) {
      if (done) return;
      done = true;
      cb(status, text);
    }
    try {
      x.open(method, url, true);
      x.timeout = timeoutMs;
      x.setRequestHeader("X-VIT", "1");
      if (body !== null) x.setRequestHeader("Content-Type", "application/json");
      x.onload = function () {
        end(x.status, x.responseText || "");
      };
      x.onerror = x.ontimeout = x.onabort = function () {
        end(0, "");
      };
      x.send(body);
    } catch (e) {
      end(0, "");
    }
    return x;
  }

  var State = { OK: "ok", OVERLAYS_OFF: "off", REMOVED: "removed", UNREACHABLE: "unreachable" };

  function pageUrl(pc, token) {
    return "http://" + pc + "/tv?t=" + encodeURIComponent(token);
  }

  /** How the PC app is, for this TV's key. */
  function ask(pc, token, timeoutMs, cb) {
    request("GET", "http://" + pc + "/tv.json?h=&t=" + encodeURIComponent(token) + "&_=" + Date.now(), null, timeoutMs, function (s, body) {
      if (s === 403) return cb(State.REMOVED);
      if (s !== 200 || body.indexOf('"areas"') < 0) return cb(State.UNREACHABLE);
      cb(body.indexOf('"overlays":false') >= 0 ? State.OVERLAYS_OFF : State.OK);
    });
  }

  /** Tell the PC this TV disconnected (its key is forgotten there). Best effort. */
  function forget(pc, token) {
    request("GET", "http://" + pc + "/tv/forget?t=" + encodeURIComponent(token), null, 2000, function () {});
  }

  /**
   * Ask every host of this TV's /24 network, PARALLEL at a time: ask(address, cb(stop)) for each
   * "a.b.c.i:port". Done (cb) when all were asked or one said stop.
   */
  function sweep(port, each, cb) {
    ownAddress(function (ip) {
      if (!ip) return cb();
      var p = ip.split(".");
      var prefix = p[0] + "." + p[1] + "." + p[2] + ".";
      var me = +p[3];
      var i = 0;
      var running = 0;
      var stopped = false;
      var finished = false;
      function more() {
        while (!stopped && running < PARALLEL && i < 254) {
          i++;
          if (i === me) continue;
          running++;
          ask1(prefix + i + ":" + port);
        }
        if (!finished && running === 0 && (stopped || i >= 254)) {
          finished = true;
          cb();
        }
      }
      function ask1(a) {
        var once = false;
        each(a, function (stop) {
          if (once) return;
          once = true;
          running--;
          if (stop) stopped = true;
          // (later: an answer given at once doesn't grow the stack)
          setTimeout(more, 0);
        });
      }
      more();
    });
  }

  // ---------------------------------------------------------------- waiting: the code

  /**
   * While not connected: show a code, and offer it to VI Translator on the PC. The PC answers 404
   * while nobody is connecting a TV there, 403 for another code, 429 after too many wrong ones (a
   * new code then), and 200 with its key for this TV once the code typed there is this one.
   */
  var waiting = {
    on: false,
    code: "",
    pcs: {}, // addresses where VI Translator answered
    offerTimer: 0,
    sweepTimer: 0,
    sweeping: false,

    start: function () {
      if (waiting.on) return;
      waiting.on = true;
      waiting.newCode();
      var last = address(prefs.get("last_pc"));
      if (last) waiting.pcs[last] = true;
      // (in a browser: tools/mock-pc.js, on the computer the app was opened from)
      if (!window.tizen && home(location.hostname)) waiting.pcs[address(/[?&]pc=([^&]+)/.test(location.search) ? decodeURIComponent(RegExp.$1) : location.hostname + ":" + PC_PORT)] = true;
      // (the TV emulator is behind its own NAT, 10.0.2.x: the PC it runs on is 10.0.2.2)
      ownAddress(function (ip) {
        if (/^10\.0\.2\.\d+$/.test(ip)) waiting.pcs["10.0.2.2:" + PC_PORT] = true;
      });
      waiting.offerAll();
      waiting.sweep();
    },

    stop: function () {
      waiting.on = false;
      clearTimeout(waiting.offerTimer);
      clearTimeout(waiting.sweepTimer);
    },

    newCode: function () {
      var c = String(1000000 + random(1000000)).slice(1);
      waiting.code = c;
      $("code").textContent = c.slice(0, 3) + " " + c.slice(3);
    },

    body: function () {
      return JSON.stringify({ app: "vi-translator-tizen", id: prefs.id(), name: tvName(), code: waiting.code });
    },

    /** Offer the code at `pc`: cb(true) when VI Translator answered there. */
    offer: function (pc, timeoutMs, cb) {
      request("POST", "http://" + pc + "/vit-tv/offer", waiting.body(), timeoutMs, function (s, body) {
        if (!waiting.on) return cb(false);
        if (s === 200) {
          var o = null;
          try {
            o = JSON.parse(body);
          } catch (e) {
            // (not VI Translator)
          }
          if (o && TOKEN.test(o.token || "")) {
            waiting.connected(pc, o.token, String(o.pc_name || "").trim().slice(0, 60) || "PC");
            return cb(true);
          }
          return cb(false);
        }
        if (s === 429) waiting.newCode();
        cb(s === 403 || s === 404 || s === 429);
      });
    },

    /** The PCs found so far, every OFFER_MS. */
    offerAll: function () {
      clearTimeout(waiting.offerTimer);
      if (!waiting.on) return;
      var list = Object.keys(waiting.pcs);
      var left = list.length;
      if (!left) return (waiting.offerTimer = setTimeout(waiting.offerAll, OFFER_MS));
      list.forEach(function (pc) {
        waiting.offer(pc, 1500, function () {
          if (--left === 0 && waiting.on) waiting.offerTimer = setTimeout(waiting.offerAll, OFFER_MS);
        });
      });
    },

    /** Look through the home network for VI Translator, again SWEEP_MS after each look. */
    sweep: function () {
      clearTimeout(waiting.sweepTimer);
      if (!waiting.on || waiting.sweeping) return;
      waiting.sweeping = true;
      sweep(
        PC_PORT,
        function (pc, done) {
          if (!waiting.on) return done(true);
          if (waiting.pcs[pc]) return done(false);
          waiting.offer(pc, 1500, function (found) {
            if (found) waiting.pcs[pc] = true;
            done(!waiting.on);
          });
        },
        function () {
          waiting.sweeping = false;
          if (waiting.on) waiting.sweepTimer = setTimeout(waiting.sweep, SWEEP_MS);
        }
      );
    },

    connected: function (pc, token, pcName) {
      waiting.stop();
      prefs.connect(pc, token, pcName);
      prefs.set("last_pc", pc);
      render();
    }
  };

  // ---------------------------------------------------------------- the HDMI picture

  /**
   * The TV window: the console's HDMI input, full screen behind this page. "Input" picks which
   * HDMI input (saved as "HDMI:2"); by default the one on screen when the app opened, or the
   * first one connected.
   */
  var tv = {
    inputs: [], // the connected HDMI inputs: {type, number, ...} from the TV
    shown: false,
    supported: function () {
      return !!(window.tizen && tizen.tvwindow && tizen.systeminfo);
    },

    /** Read the connected HDMI inputs, then cb. */
    load: function (cb) {
      if (!tv.supported()) return cb();
      try {
        tizen.systeminfo.getPropertyValue(
          "VIDEOSOURCE",
          function (v) {
            tv.inputs = (v.connected || []).filter(function (s) {
              return s.type === "HDMI";
            });
            if (!prefs.get("input")) {
              // (the input on screen when the app was opened: the console, most likely)
              try {
                var now = tizen.tvwindow.getSource("MAIN");
                if (now && now.type === "HDMI") prefs.set("input", "HDMI:" + now.number);
              } catch (e) {
                // (none yet)
              }
            }
            cb();
          },
          function () {
            cb();
          }
        );
      } catch (e) {
        cb();
      }
    },

    current: function () {
      var want = prefs.get("input");
      for (var i = 0; i < tv.inputs.length; i++) {
        if ("HDMI:" + tv.inputs[i].number === want) return tv.inputs[i];
      }
      return tv.inputs[0] || null;
    },

    label: function () {
      var c = tv.current();
      return c ? "HDMI " + c.number : t("no_hdmi");
    },

    /** Show the chosen input full screen, behind this page. */
    show: function () {
      var src = tv.current();
      if (!tv.supported() || !src) return;
      try {
        tizen.tvwindow.setSource(
          src,
          function () {
            try {
              tizen.tvwindow.show(
                function () {
                  tv.shown = true;
                },
                function (e) {
                  console.log("tvwindow.show: " + (e && e.message));
                },
                ["0", "0", "100%", "100%"],
                "MAIN",
                "BEHIND"
              );
            } catch (e) {
              console.log("tvwindow.show: " + e.message);
            }
          },
          function (e) {
            console.log("tvwindow.setSource: " + (e && e.message));
          },
          "MAIN"
        );
      } catch (e) {
        console.log("tvwindow: " + e.message);
      }
    },

    hide: function () {
      if (!tv.supported() || !tv.shown) return;
      tv.shown = false;
      try {
        tizen.tvwindow.hide(function () {}, function () {}, "MAIN");
      } catch (e) {
        // (not shown)
      }
    },

    /** Input: each connected HDMI input in turn. */
    next: function () {
      if (!tv.inputs.length) return;
      var i = tv.inputs.indexOf(tv.current());
      var n = tv.inputs[(i + 1) % tv.inputs.length];
      prefs.set("input", "HDMI:" + n.number);
      tv.show();
    }
  };

  // ---------------------------------------------------------------- connected: the overlay

  /**
   * While connected: the PC's TV page over the HDMI picture. Also notices when the PC removed
   * this TV (back to a code), and finds the PC again when it got a new address.
   */
  var overlay = {
    on: false,
    url: "",
    state: State.UNREACHABLE,
    unreachable: 0,
    finding: false,
    retryTimer: 0,
    checkTimer: 0,

    start: function () {
      if (overlay.on) return;
      overlay.on = true;
      overlay.state = State.UNREACHABLE;
      overlay.unreachable = 0;
      tv.load(function () {
        if (!overlay.on) return;
        tv.show();
        menu.refresh();
      });
      overlay.show(pageUrl(prefs.get("pc"), prefs.get("token")));
      overlay.check();
    },

    stop: function () {
      overlay.on = false;
      clearTimeout(overlay.retryTimer);
      clearTimeout(overlay.checkTimer);
      overlay.url = "";
      $("overlay").src = "about:blank";
      tv.hide();
    },

    show: function (url) {
      if (url === overlay.url) return;
      overlay.url = url;
      overlay.load();
    },

    /** Only once the PC answers this TV as connected: an error page never shows over the game. */
    load: function () {
      clearTimeout(overlay.retryTimer);
      var u = overlay.url;
      if (!u) return;
      ask(prefs.get("pc"), prefs.get("token"), 4000, function (s) {
        if (!overlay.on || u !== overlay.url) return;
        overlay.setState(s);
        if (s === State.OK || s === State.OVERLAYS_OFF) $("overlay").src = u;
        else overlay.failed();
      });
    },

    failed: function () {
      $("overlay").src = "about:blank";
      clearTimeout(overlay.retryTimer);
      overlay.retryTimer = setTimeout(overlay.load, RETRY_MS);
    },

    setState: function (s) {
      overlay.state = s;
      if (s === State.REMOVED) {
        prefs.disconnect();
        render();
        return;
      }
      menu.refresh();
    },

    /** How the PC is: removed this TV (disconnect), or gone a while (look for it at a new address). */
    check: function () {
      clearTimeout(overlay.checkTimer);
      if (!overlay.on) return;
      var pc = prefs.get("pc");
      var token = prefs.get("token");
      ask(pc, token, 4000, function (s) {
        if (!overlay.on) return;
        overlay.setState(s);
        if (!overlay.on) return;
        overlay.unreachable = s === State.UNREACHABLE ? overlay.unreachable + 1 : 0;
        if (overlay.unreachable >= 3 && !overlay.finding) overlay.find(pc, token);
        // (it was down when the page was to be loaded)
        if (s !== State.UNREACHABLE && $("overlay").src === "about:blank") overlay.load();
        overlay.checkTimer = setTimeout(overlay.check, CHECK_MS);
      });
    },

    /** The PC got a new address (from the router): look for it on this TV's /24, same port, same key. */
    find: function (pc, token) {
      overlay.finding = true;
      var found = null;
      sweep(
        +pc.split(":")[1],
        function (a, done) {
          if (!overlay.on || found) return done(true);
          ask(a, token, 1500, function (s) {
            if (s === State.OK || s === State.OVERLAYS_OFF) found = found || a;
            done(!!found);
          });
        },
        function () {
          overlay.finding = false;
          if (overlay.on && found && found !== prefs.get("pc")) {
            prefs.set("pc", found);
            prefs.set("last_pc", found);
            overlay.unreachable = 0;
            overlay.show(pageUrl(found, token));
          }
        }
      );
    }
  };

  // ---------------------------------------------------------------- the menu

  var menu = {
    open: false,
    buttons: function () {
      return [$("input"), $("menu-language"), $("disconnect")].filter(function (b) {
        return !b.classList.contains("hidden");
      });
    },

    show: function () {
      menu.open = true;
      hint.hide();
      menu.refresh();
      $("menu").classList.remove("hidden");
      var b = menu.buttons();
      if (b.length) b[0].focus();
    },

    hide: function () {
      menu.open = false;
      $("menu").classList.add("hidden");
      if (document.activeElement) document.activeElement.blur();
    },

    refresh: function () {
      var pc = prefs.get("pc_name") || "PC";
      var dot = $("dot");
      dot.className = "dot";
      if (overlay.state === State.OK) {
        dot.classList.add("ok");
        $("status").textContent = t("connected_to", pc);
      } else if (overlay.state === State.OVERLAYS_OFF) {
        dot.classList.add("off");
        $("status").textContent = t("overlays_off", pc);
      } else {
        $("status").textContent = t("looking_for", pc);
      }
      $("input").textContent = t("input", tv.label());
      $("input").classList.toggle("hidden", !tv.supported());
    },

    move: function (d) {
      var b = menu.buttons();
      var i = b.indexOf(document.activeElement);
      // (in a right-to-left language the buttons run from the right)
      if (document.documentElement.dir === "rtl") d = -d;
      i = Math.max(0, Math.min(b.length - 1, i + d));
      if (b[i]) b[i].focus();
    },

    press: function () {
      var a = document.activeElement;
      if (a === $("input")) {
        tv.next();
        menu.refresh();
      } else if (a === $("menu-language")) {
        picker.show();
      } else if (a === $("disconnect")) {
        disconnect();
      }
    }
  };

  // ---------------------------------------------------------------- the language list

  var GLOBE =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">' +
    '<circle cx="12" cy="12" r="9.5"/><path d="M2.5 12h19M12 2.5c2.6 2.8 3.9 6 3.9 9.5S14.6 18.7 12 21.5M12 2.5C9.4 5.3 8.1 8.5 8.1 12s1.3 6.7 3.9 9.5"/></svg>';

  /** The TV's own language first, then each of the desktop app's by its own name. Up/Down, OK, Back. */
  var picker = {
    open: false,
    back: null, // the button to go back to

    show: function () {
      picker.open = true;
      picker.back = document.activeElement;
      $("languages-title").textContent = t("language");
      var list = $("language-list");
      list.innerHTML = "";
      var now = prefs.get("language");
      var all = [["", t("language_tv")]].concat(LANGUAGES);
      var focus = null;
      all.forEach(function (l) {
        var b = document.createElement("button");
        b.className = "button item";
        // (each name in its own direction, all on the page's side)
        var s = document.createElement("span");
        s.textContent = l[1];
        s.dir = "auto";
        b.appendChild(s);
        b.setAttribute("data-tag", l[0]);
        if (l[0] === (chosenLanguage() ? now : "")) {
          b.classList.add("chosen");
          focus = b;
        }
        b.addEventListener("click", function () {
          picker.pick(l[0]);
        });
        list.appendChild(b);
      });
      $("languages").classList.remove("hidden");
      (focus || list.firstChild).focus();
    },

    hide: function () {
      picker.open = false;
      $("languages").classList.add("hidden");
      if (picker.back && !picker.back.closest(".hidden")) picker.back.focus();
      else focusDefault();
    },

    move: function (d) {
      var items = $("language-list").children;
      var i = Array.prototype.indexOf.call(items, document.activeElement);
      i = Math.max(0, Math.min(items.length - 1, i + d));
      items[i].focus();
    },

    pick: function (tag) {
      prefs.set("language", tag);
      picker.hide();
      setLanguage();
      texts();
    }
  };

  /** The language button's text: a globe and the language in use. */
  function languageLabel(b) {
    var c = chosenLanguage();
    b.innerHTML = GLOBE;
    var s = document.createElement("span");
    s.textContent = c ? c[1] : t("language_tv");
    s.dir = "auto";
    b.appendChild(s);
    b.setAttribute("aria-label", t("language"));
  }

  /** The waiting screen's only button, so OK works at once. */
  function focusDefault() {
    if (!prefs.connected()) $("language").focus();
  }

  /** "Press OK for the menu", for a moment after the game shows. */
  var hint = {
    timer: 0,
    show: function () {
      $("hint").textContent = t("menu_hint");
      $("hint").classList.remove("hidden");
      clearTimeout(hint.timer);
      hint.timer = setTimeout(hint.hide, HINT_MS);
    },
    hide: function () {
      clearTimeout(hint.timer);
      $("hint").classList.add("hidden");
    }
  };

  function disconnect() {
    forget(prefs.get("pc"), prefs.get("token"));
    prefs.disconnect();
    render();
  }

  // ---------------------------------------------------------------- the screen

  function $(id) {
    return document.getElementById(id);
  }

  /** Which part shows, for whether this TV is connected. */
  function render() {
    var on = prefs.connected();
    $("waiting").classList.toggle("hidden", on);
    if (on) {
      waiting.stop();
      overlay.start();
      menu.hide();
      hint.show();
    } else {
      overlay.stop();
      menu.hide();
      hint.hide();
      $("this-tv").textContent = t("this_tv", tvName());
      waiting.start();
      focusDefault();
    }
  }

  function exit() {
    try {
      tizen.application.getCurrentApplication().exit();
    } catch (e) {
      // (not on a TV)
    }
  }

  document.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (picker.open) {
      if (k === KEY.BACK || k === KEY.ESC) picker.hide();
      else if (k === KEY.UP) picker.move(-1);
      else if (k === KEY.DOWN) picker.move(1);
      else if (k === KEY.ENTER) picker.pick(document.activeElement.getAttribute("data-tag") || "");
      else return;
      e.preventDefault();
      return;
    }
    if (!prefs.connected()) {
      if (k === KEY.BACK || k === KEY.ESC) exit();
      else if (k === KEY.ENTER) picker.show();
      else return;
      e.preventDefault();
      return;
    }
    if (menu.open) {
      if (k === KEY.BACK || k === KEY.ESC) menu.hide();
      else if (k === KEY.LEFT) menu.move(-1);
      else if (k === KEY.RIGHT) menu.move(1);
      else if (k === KEY.ENTER) menu.press();
      else return;
    } else if (k === KEY.ENTER) {
      menu.show();
    } else if (k === KEY.BACK || k === KEY.ESC) {
      exit();
    } else {
      return;
    }
    e.preventDefault();
  });

  // back from another app or input: the TV window is shown again, and the PC asked at once
  document.addEventListener("visibilitychange", function () {
    if (document.hidden || !overlay.on) return;
    tv.load(function () {
      tv.show();
      menu.refresh();
    });
    overlay.check();
  });

  /** Every text on screen, in the app's language. */
  function texts() {
    document.documentElement.lang = lang;
    document.documentElement.dir = /^(ar|fa|ur|he)/.test(lang) ? "rtl" : "ltr";
    $("enter-code").textContent = t("enter_code");
    $("where").textContent = t("where_on_pc");
    $("this-tv").textContent = t("this_tv", tvName());
    $("disconnect").textContent = t("disconnect");
    languageLabel($("language"));
    languageLabel($("menu-language"));
    if (prefs.connected()) menu.refresh();
  }

  function init() {
    setLanguage();
    texts();
    $("language").addEventListener("click", picker.show);
    $("menu-language").addEventListener("click", picker.show);
    // clicks (the emulator's mouse) work like OK on the button
    $("input").addEventListener("click", function () {
      tv.next();
      menu.refresh();
    });
    $("disconnect").addEventListener("click", disconnect);
    render();
  }

  init();
})();
