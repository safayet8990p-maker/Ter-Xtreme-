(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const termWrap = $("term-wrap");
  const tabsNav = $("tabs");
  const tabDrawer = $("tab-drawer");
  const drawerBackdrop = $("drawer-backdrop");
  const keysBar = $("keys");
  const freeWriteBar = $("free-write");
  const freeWriteInput = $("fw-input");
  const statusEl = $("hp");
  const btnUpdate = $("btn-update");
  const setModal = $("set");
  const plugModal = $("plug");

  const hasBridge = typeof window.TermBridge !== "undefined";

  const THEME_PACKS = [
    { id: "amber", name: "Amber Theme Pack", desc: "Warm retro amber CRT palette" },
    { id: "ice", name: "Ice Theme Pack", desc: "Arctic cyan dark terminal palette" },
    { id: "matrix", name: "Matrix Theme Pack", desc: "High-contrast phosphor green palette" },
    { id: "dracula", name: "Dracula Theme Pack", desc: "Classic twilight purple & pink palette" },
    { id: "nord", name: "Nord Theme Pack", desc: "Muted Scandinavian slate-blue palette" },
  ];

  let settings = {
    theme: "auto",
    font: 14,
    keys: true,
    themePacks: [],
    updateSeen: false,
    storageGranted: false,
  };

  if (hasBridge && typeof window.TermBridge.loadSettings === "function") {
    try {
      const nativeSaved = JSON.parse(window.TermBridge.loadSettings() || "null");
      if (nativeSaved) settings = { ...settings, ...nativeSaved };
    } catch (_) {}
  } else {
    try {
      const saved = JSON.parse(localStorage.getItem("terx_ui_v2") || "null");
      if (saved) settings = { ...settings, ...saved };
    } catch (_) {}
  }

  function saveSettings() {
    if (hasBridge && typeof window.TermBridge.saveSettings === "function") {
      window.TermBridge.saveSettings(JSON.stringify(settings));
    } else {
      try {
        localStorage.setItem("terx_ui_v2", JSON.stringify(settings));
      } catch (_) {}
    }
  }

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function currentXtermTheme() {
    const isLight =
      settings.theme === "light" ||
      (settings.theme === "auto" &&
        window.matchMedia &&
        !window.matchMedia("(prefers-color-scheme: dark)").matches);

    const bg = cssVar("--bg") || "#0b0a14";
    const fg = cssVar("--fg") || "#e8e6ff";
    const ac = cssVar("--ac") || "#ff3d81";
    const ac2 = cssVar("--ac2") || "#36e2ff";
    const dim = cssVar("--dim") || "#7f7ab0";
    const err = cssVar("--err") || "#ff6b6b";
    const sel = cssVar("--sel") || "rgba(255,61,129,0.28)";

    return {
      background: bg,
      foreground: fg,
      cursor: ac,
      cursorAccent: bg,
      selectionBackground: sel,
      black: isLight ? "#141133" : "#15132a",
      red: err,
      green: isLight ? "#15803d" : "#4ade80",
      yellow: isLight ? "#b45309" : "#facc15",
      blue: isLight ? "#1d4ed8" : "#60a5fa",
      magenta: ac,
      cyan: ac2,
      white: fg,
      brightBlack: dim,
      brightRed: err,
      brightGreen: isLight ? "#16a34a" : "#86efac",
      brightYellow: isLight ? "#d97706" : "#fde047",
      brightBlue: isLight ? "#2563eb" : "#93c5fd",
      brightMagenta: ac,
      brightCyan: ac2,
      brightWhite: isLight ? "#0b0a14" : "#ffffff",
    };
  }

  function strToBase64(str) {
    const bytes = new TextEncoder().encode(str);
    let bin = "";
    for (let i = 0; i < bytes.length; i++) {
      bin += String.fromCharCode(bytes[i]);
    }
    return btoa(bin);
  }

  function base64ToUint8(b64) {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    return bytes;
  }

  const modState = { ctrl: 0, alt: 0 };

  function updateModButtons() {
    keysBar.querySelectorAll("button[data-mod]").forEach((btn) => {
      const m = btn.dataset.mod;
      const st = modState[m] || 0;
      btn.classList.toggle("mod-on", st === 1);
      btn.classList.toggle("mod-lock", st === 2);
      btn.setAttribute("aria-pressed", st > 0 ? "true" : "false");
    });
  }

  function toggleMod(mod) {
    modState[mod] = (modState[mod] + 1) % 3;
    updateModButtons();
  }

  function consumeStickyMods() {
    let changed = false;
    if (modState.ctrl === 1) {
      modState.ctrl = 0;
      changed = true;
    }
    if (modState.alt === 1) {
      modState.alt = 0;
      changed = true;
    }
    if (changed) updateModButtons();
  }

  function applyModifiers(data) {
    if (!data || (modState.ctrl === 0 && modState.alt === 0)) {
      return data;
    }
    let out = data;
    if (modState.ctrl > 0 && out.length === 1) {
      const ch = out.charCodeAt(0);
      if (ch >= 0x61 && ch <= 0x7a) {
        out = String.fromCharCode(ch - 0x60);
      } else if (ch >= 0x41 && ch <= 0x5a) {
        out = String.fromCharCode(ch - 0x40);
      } else if (ch === 0x20 || ch === 0x40) {
        out = "\x00";
      } else if (ch === 0x5b) {
        out = "\x1b";
      } else if (ch === 0x5c) {
        out = "\x1c";
      } else if (ch === 0x5d) {
        out = "\x1d";
      } else if (ch === 0x5e) {
        out = "\x1e";
      } else if (ch === 0x5f) {
        out = "\x1f";
      }
    }
    if (modState.alt > 0) {
      out = "\x1b" + out;
    }
    consumeStickyMods();
    return out;
  }

  const tabs = [];
  let activeTabId = null;
  let nextTabNum = 1;
  let freeWriteOpen = false;

  function activeTab() {
    return tabs.find((t) => t.id === activeTabId) || null;
  }

  function focusTerminal(tab, openKeyboard) {
    if (!tab) return;
    if (freeWriteOpen) return;
    if (hasBridge) {
      window.TermBridge.setTerminalInputMode(true);
    }
    tab.term.focus();
    if (openKeyboard && hasBridge) {
      window.TermBridge.showKeyboard();
    }
  }

  function sendInputToNative(tabId, text) {
    if (!text) return;
    const b64 = strToBase64(text);
    if (hasBridge) {
      window.TermBridge.write(tabId, b64);
    }
  }

  function fitTab(tab) {
    if (!tab || tab.id !== activeTabId) return;
    try {
      tab.fitAddon.fit();
      const cols = tab.term.cols;
      const rows = tab.term.rows;
      if (cols > 0 && rows > 0 && (cols !== tab.cols || rows !== tab.rows)) {
        tab.cols = cols;
        tab.rows = rows;
        if (hasBridge) {
          window.TermBridge.onResize(tab.id, cols, rows);
        }
      }
      tab.term.scrollToBottom();
    } catch (_) {}
  }

  function syncFixedKeyBarPosition() {
    const vv = window.visualViewport;
    let keyboardBottomOffset = 0;
    if (vv) {
      keyboardBottomOffset = Math.max(0, Math.round(window.innerHeight - (vv.height + vv.offsetTop)));
    }
    const keysVisible = settings.keys && setModal.hidden && plugModal.hidden;
    keysBar.style.display = keysVisible ? "grid" : "none";
    keysBar.style.bottom = keyboardBottomOffset + "px";

    const keysHeight = keysVisible ? keysBar.offsetHeight : 0;
    const fwVisible = freeWriteOpen && setModal.hidden && plugModal.hidden;
    freeWriteBar.hidden = !fwVisible;
    freeWriteBar.style.bottom = keyboardBottomOffset + keysHeight + "px";
    const fwHeight = fwVisible ? freeWriteBar.offsetHeight : 0;

    termWrap.style.paddingBottom = 6 + keyboardBottomOffset + keysHeight + fwHeight + "px";
    scheduleFit();
  }

  function openTabDrawer() {
    drawTabs();
    tabDrawer.hidden = false;
    drawerBackdrop.hidden = false;
  }

  function closeTabDrawer() {
    tabDrawer.hidden = true;
    drawerBackdrop.hidden = true;
  }

  $("drawer-close").onclick = closeTabDrawer;
  drawerBackdrop.onclick = closeTabDrawer;
  $("tab-add").onclick = () => {
    createTab();
    closeTabDrawer();
  };

  function drawTabs() {
    tabsNav.innerHTML = "";
    tabs.forEach((t) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = t.id === activeTabId ? "on" : "";
      btn.setAttribute("role", "tab");
      btn.setAttribute("aria-selected", t.id === activeTabId ? "true" : "false");

      const label = document.createElement("span");
      label.textContent = "term " + t.num;
      btn.appendChild(label);

      if (tabs.length > 1) {
        const close = document.createElement("span");
        close.className = "x";
        close.textContent = "×";
        close.setAttribute("aria-label", "Close tab " + t.num);
        close.onclick = (e) => {
          e.stopPropagation();
          closeTab(t.id);
        };
        btn.appendChild(close);
      }

      btn.onclick = () => {
        selectTab(t.id);
        closeTabDrawer();
      };
      tabsNav.appendChild(btn);
    });
  }

  function configureTerminalTextarea(term, tabId) {
    const ta = term.textarea;
    if (!ta) return;
    ta.setAttribute("inputmode", "text");
    ta.setAttribute("enterkeyhint", "enter");
    ta.setAttribute("autocomplete", "off");
    ta.setAttribute("autocorrect", "off");
    ta.setAttribute("autocapitalize", "none");
    ta.setAttribute("spellcheck", "false");

    ta.addEventListener("focus", () => {
      if (hasBridge && !freeWriteOpen) window.TermBridge.setTerminalInputMode(true);
    });

    ta.addEventListener("beforeinput", (e) => {
      if (e.inputType === "deleteContentBackward" && ta.value === "") {
        e.preventDefault();
        sendInputToNative(tabId, "\x7f");
      }
    });
  }

  function createTab() {
    const num = nextTabNum++;
    const id = "tab-" + num;
    const pane = document.createElement("div");
    pane.className = "term-pane";
    pane.id = "pane-" + id;
    termWrap.appendChild(pane);

    const term = new window.Terminal({
      fontFamily: '"Space Mono", ui-monospace, Menlo, Consolas, monospace',
      fontSize: settings.font,
      lineHeight: 1.25,
      cursorBlink: true,
      cursorStyle: "block",
      scrollback: 4000,
      convertEol: false,
      allowProposedApi: true,
      theme: currentXtermTheme(),
    });

    const fitAddon = new window.FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(pane);
    configureTerminalTextarea(term, id);

    const tab = { id, num, pane, term, fitAddon, cols: 80, rows: 24 };
    tabs.push(tab);

    term.onData((data) => {
      const modified = applyModifiers(data);
      sendInputToNative(id, modified);
    });

    selectTab(id);
    fitTab(tab);

    if (hasBridge) {
      window.TermBridge.onTabCreated(id, tab.cols, tab.rows);
    } else {
      term.write("Android TermBridge unavailable.\r\n");
    }
    return tab;
  }

  function selectTab(tabId) {
    activeTabId = tabId;
    tabs.forEach((t) => {
      t.pane.classList.toggle("on", t.id === tabId);
    });
    drawTabs();
    const t = activeTab();
    if (t) {
      requestAnimationFrame(() => {
        fitTab(t);
        focusTerminal(t, false);
      });
      if (hasBridge) {
        window.TermBridge.onTabSelected(t.id);
      }
    }
  }

  function closeTab(tabId) {
    if (tabs.length <= 1) return;
    const idx = tabs.findIndex((t) => t.id === tabId);
    if (idx < 0) return;
    const [removed] = tabs.splice(idx, 1);
    removed.term.dispose();
    removed.pane.remove();
    if (hasBridge) {
      window.TermBridge.onTabClosed(tabId);
    }
    if (activeTabId === tabId) {
      const next = tabs[Math.max(0, idx - 1)];
      selectTab(next.id);
    } else {
      drawTabs();
    }
  }

  let lastFwCloseMs = 0;

  function openFreeWrite() {
    freeWriteOpen = true;
    syncFixedKeyBarPosition();
    if (hasBridge) window.TermBridge.setTerminalInputMode(false);
    setTimeout(() => {
      freeWriteInput.focus();
      if (hasBridge) window.TermBridge.showKeyboard();
    }, 30);
  }

  function closeFreeWrite() {
    freeWriteOpen = false;
    lastFwCloseMs = Date.now();
    if (keysBar.scrollLeft > 0) {
      keysBar.scrollLeft = 0;
    }
    syncFixedKeyBarPosition();
    if (hasBridge) window.TermBridge.setTerminalInputMode(true);
    focusTerminal(activeTab(), false);
  }

  function sendFreeWrite(withEnter) {
    const t = activeTab();
    const val = freeWriteInput.value;
    if (t && (val || withEnter)) {
      sendInputToNative(t.id, withEnter ? val + "\r" : val);
      freeWriteInput.value = "";
    }
  }

  freeWriteInput.addEventListener("focus", () => {
    if (hasBridge) window.TermBridge.setTerminalInputMode(false);
  });
  freeWriteInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      sendFreeWrite(true);
    }
  });
  $("fw-send").onclick = () => sendFreeWrite(false);
  $("fw-enter").onclick = () => sendFreeWrite(true);
  $("fw-close").onclick = closeFreeWrite;

  // Keys bar: left side has ≡ (tab drawer), real TAB, --help, sticky CTRL/ALT, D-pad, right end reveals Free Write
  const KEYS = [
    { label: "≡", action: "drawer" },
    { label: "TAB", seq: "\t" },
    { label: "ESC", seq: "\x1b" },
    { label: "--help", seq: " --help" },
    { label: "CTRL", mod: "ctrl" },
    { label: "ALT", mod: "alt" },
    { label: "/", seq: "/" },
    { label: "-", seq: "-" },
    { label: "HOME", seq: "\x1b[H" },
    { label: "←", seq: "\x1b[D" },
    { label: "↑", seq: "\x1b[A" },
    { label: "↓", seq: "\x1b[B" },
    { label: "END", seq: "\x1b[F" },
    { label: "→", seq: "\x1b[C" },
    { label: "PGUP", seq: "\x1b[5~" },
    { label: "PGDN", seq: "\x1b[6~" },
    { label: "~", seq: "~" },
    { label: "|", seq: "|" },
    { label: ">", seq: ">" },
    { label: "Copy", action: "copy" },
    { label: "Paste", action: "paste" },
    { label: "✎ Write", action: "free_write" },
    { label: "⌨", action: "hide_kb" },
  ];

  function copyActiveSelection() {
    const t = activeTab();
    if (!t) return;
    const sel = t.term.getSelection();
    if (!sel) {
      statusEl.textContent = "nothing selected";
      return;
    }
    if (hasBridge) {
      window.TermBridge.copyText(sel);
    } else if (navigator.clipboard) {
      navigator.clipboard.writeText(sel).catch(() => {});
    }
    t.term.clearSelection();
    statusEl.textContent = "copied " + sel.length + " chars";
  }

  function pasteToActive() {
    const t = activeTab();
    if (!t) return;
    if (hasBridge) {
      const clip = window.TermBridge.getClipboardText();
      if (clip) sendInputToNative(t.id, clip);
    } else if (navigator.clipboard) {
      navigator.clipboard.readText().then((txt) => {
        if (txt) sendInputToNative(t.id, txt);
      }).catch(() => {});
    }
  }

  KEYS.forEach((k) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = k.label;
    if (k.mod) btn.dataset.mod = k.mod;
    btn.onmousedown = (e) => e.preventDefault();
    btn.ontouchstart = (e) => e.stopPropagation();
    btn.onclick = () => {
      const t = activeTab();
      if (k.action === "drawer") {
        if (tabDrawer.hidden) openTabDrawer();
        else closeTabDrawer();
        return;
      } else if (k.action === "free_write") {
        if (freeWriteOpen) closeFreeWrite();
        else openFreeWrite();
        return;
      } else if (k.mod) {
        toggleMod(k.mod);
      } else if (k.action === "copy") {
        copyActiveSelection();
      } else if (k.action === "paste") {
        pasteToActive();
      } else if (k.action === "hide_kb") {
        if (hasBridge) window.TermBridge.hideKeyboard();
        return;
      } else if (k.seq && t) {
        const out = applyModifiers(k.seq);
        sendInputToNative(t.id, out);
      }
      focusTerminal(t, false);
    };
    keysBar.appendChild(btn);
  });

  // Scrolling #keys all the way to the right edge automatically toggles Free Writing Bar
  keysBar.addEventListener("scroll", () => {
    if (Date.now() - lastFwCloseMs < 350) return;
    const maxScroll = keysBar.scrollWidth - keysBar.clientWidth;
    if (maxScroll > 20 && keysBar.scrollLeft >= maxScroll - 4 && !freeWriteOpen) {
      openFreeWrite();
    }
  });

  function rebuildThemeDropdown() {
    const sel = $("s-theme");
    sel.innerHTML = "";
    const baseOptions = [
      ["auto", "Auto (Dark / Light)"],
      ["dark", "Full Dark"],
      ["light", "Full Light"],
    ];
    baseOptions.forEach(([val, text]) => {
      const opt = document.createElement("option");
      opt.value = val;
      opt.textContent = text;
      sel.appendChild(opt);
    });
    const installed = Array.isArray(settings.themePacks) ? settings.themePacks : [];
    THEME_PACKS.forEach((pack) => {
      if (installed.includes(pack.id)) {
        const opt = document.createElement("option");
        opt.value = pack.id;
        opt.textContent = pack.name;
        sel.appendChild(opt);
      }
    });
    sel.value = settings.theme;
  }

  function applySettings() {
    const rootEl = document.documentElement;
    if (settings.theme === "auto") {
      delete rootEl.dataset.theme;
    } else {
      rootEl.dataset.theme = settings.theme;
    }
    rebuildThemeDropdown();
    const themeObj = currentXtermTheme();
    tabs.forEach((t) => {
      t.term.options.theme = themeObj;
      t.term.options.fontSize = settings.font;
    });
    $("s-font-val").textContent = String(settings.font);
    btnUpdate.hidden = !!settings.updateSeen;
    syncFixedKeyBarPosition();
  }

  if (window.matchMedia) {
    window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
      if (settings.theme === "auto") applySettings();
    });
  }

  let pinchStartDist = 0;
  let pinchStartFont = settings.font;
  let tapStartX = 0;
  let tapStartY = 0;
  let tapMoved = false;

  function touchDist(touches) {
    const dx = touches[0].clientX - touches[1].clientX;
    const dy = touches[0].clientY - touches[1].clientY;
    return Math.hypot(dx, dy);
  }

  termWrap.addEventListener(
    "touchstart",
    (e) => {
      if (e.touches.length === 2) {
        pinchStartDist = touchDist(e.touches);
        pinchStartFont = settings.font;
        tapMoved = true;
      } else if (e.touches.length === 1) {
        tapStartX = e.touches[0].clientX;
        tapStartY = e.touches[0].clientY;
        tapMoved = false;
      }
    },
    { passive: true }
  );

  termWrap.addEventListener(
    "touchmove",
    (e) => {
      if (e.touches.length === 2 && pinchStartDist > 0) {
        const ratio = touchDist(e.touches) / pinchStartDist;
        const nextFont = Math.max(10, Math.min(24, Math.round(pinchStartFont * ratio)));
        if (nextFont !== settings.font) {
          settings.font = nextFont;
          $("s-font").value = String(nextFont);
          applySettings();
          saveSettings();
        }
      } else if (e.touches.length === 1 && !tapMoved) {
        const dx = Math.abs(e.touches[0].clientX - tapStartX);
        const dy = Math.abs(e.touches[0].clientY - tapStartY);
        if (dx > 10 || dy > 10) tapMoved = true;
      }
    },
    { passive: true }
  );

  termWrap.addEventListener("touchend", (e) => {
    if (e.touches.length === 0 && !tapMoved) {
      const t = activeTab();
      if (t && !t.term.hasSelection()) {
        focusTerminal(t, true);
      }
    }
  });

  let resizeRaf = 0;
  function scheduleFit() {
    if (resizeRaf) cancelAnimationFrame(resizeRaf);
    resizeRaf = requestAnimationFrame(() => {
      resizeRaf = 0;
      fitTab(activeTab());
    });
  }

  if (typeof ResizeObserver !== "undefined") {
    new ResizeObserver(scheduleFit).observe(termWrap);
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", syncFixedKeyBarPosition);
    window.visualViewport.addEventListener("scroll", syncFixedKeyBarPosition);
  }
  window.addEventListener("resize", syncFixedKeyBarPosition);

  ["p-search"].forEach((id) => {
    const el = $(id);
    if (!el) return;
    el.addEventListener("focus", () => {
      if (hasBridge) window.TermBridge.setTerminalInputMode(false);
    });
    el.addEventListener("blur", () => {
      if (setModal.hidden && plugModal.hidden && !freeWriteOpen && hasBridge) {
        window.TermBridge.setTerminalInputMode(true);
      }
    });
  });

  function updateStorageButtonLabel() {
    const granted = hasBridge && typeof window.TermBridge.hasStoragePermission === "function"
      ? window.TermBridge.hasStoragePermission()
      : settings.storageGranted;
    settings.storageGranted = granted;
    $("s-storage").textContent = granted ? "Granted ✓" : "Grant Access";
  }

  window.addEventListener("focus", updateStorageButtonLabel);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) updateStorageButtonLabel();
  });

  function openSettings() {
    closeTabDrawer();
    rebuildThemeDropdown();
    $("s-font").value = String(settings.font);
    $("s-font-val").textContent = String(settings.font);
    $("s-keys").checked = settings.keys;
    updateStorageButtonLabel();
    if (hasBridge) window.TermBridge.hideKeyboard();
    setModal.hidden = false;
    syncFixedKeyBarPosition();
  }

  function closeSettings() {
    setModal.hidden = true;
    syncFixedKeyBarPosition();
    focusTerminal(activeTab(), false);
  }

  $("gear").onclick = openSettings;
  $("s-close").onclick = closeSettings;

  $("s-storage").onclick = () => {
    if (hasBridge && typeof window.TermBridge.requestStoragePermission === "function") {
      window.TermBridge.requestStoragePermission();
    }
  };

  btnUpdate.onclick = () => {
    settings.updateSeen = true;
    btnUpdate.hidden = true;
    saveSettings();
    if (hasBridge) {
      window.TermBridge.markLinuxUpdateSeen();
      window.TermBridge.hideKeyboard();
      window.TermBridge.openSelfTest();
    }
  };

  $("s-update").onclick = () => {
    setModal.hidden = true;
    settings.updateSeen = true;
    btnUpdate.hidden = true;
    syncFixedKeyBarPosition();
    if (hasBridge) {
      window.TermBridge.hideKeyboard();
      window.TermBridge.openSelfTest();
    }
  };

  $("s-bindbg").onclick = () => {
    setModal.hidden = true;
    syncFixedKeyBarPosition();
    if (hasBridge && typeof window.TermBridge.openBinaryDebug === "function") {
      window.TermBridge.hideKeyboard();
      window.TermBridge.openBinaryDebug();
    }
  };

  setModal.addEventListener("input", () => {
    settings.theme = $("s-theme").value;
    settings.font = Number($("s-font").value) || 14;
    settings.keys = $("s-keys").checked;
    applySettings();
    saveSettings();
  });

  $("s-wipe").onclick = () => {
    if (hasBridge && typeof window.TermBridge.resetSettings === "function") {
      window.TermBridge.resetSettings();
    }
    try {
      localStorage.removeItem("terx_ui_v2");
    } catch (_) {}
    settings = {
      theme: "auto",
      font: 14,
      keys: true,
      themePacks: [],
      updateSeen: settings.updateSeen,
      storageGranted: settings.storageGranted,
    };
    applySettings();
    closeSettings();
  };

  const CATALOG = [
    ...THEME_PACKS.map((p) => [p.id, "terxtreme/themes", p.desc, "Themes", "install theme pack: " + p.name, p.id]),
    ["git", "git/git", "Distributed version control", "Dev", "apk add git", null],
    ["openssh", "openssh/openssh-portable", "SSH client and tools", "Network", "apk add openssh", null],
    ["curl", "curl/curl", "Transfer data with URLs", "Network", "apk add curl", null],
    ["wget", "mirror/wget", "Network retriever", "Network", "apk add wget", null],
    ["python3", "python/cpython", "Python 3 runtime and pip", "Dev", "apk add python3 py3-pip", null],
    ["nodejs", "nodejs/node", "JavaScript runtime and npm", "Dev", "apk add nodejs npm", null],
    ["neovim", "neovim/neovim", "Hyperextensible Vim-based text editor", "Editors", "apk add neovim", null],
    ["nano", "built-in", "Friendly terminal text editor", "Editors", "apk add nano", null],
    ["tmux", "tmux/tmux", "Terminal multiplexer", "Shell", "apk add tmux", null],
    ["zsh", "zsh-users/zsh", "Powerful interactive shell", "Shell", "apk add zsh", null],
    ["fzf", "junegunn/fzf", "Command-line fuzzy finder", "Shell", "apk add fzf", null],
    ["ripgrep", "BurntSushi/ripgrep", "Fast recursive regex search", "Files", "apk add ripgrep", null],
    ["fd", "sharkdp/fd", "Simple, fast alternative to find", "Files", "apk add fd", null],
    ["bat", "sharkdp/bat", "cat clone with syntax highlighting", "Files", "apk add bat", null],
    ["jq", "jqlang/jq", "Command-line JSON processor", "Files", "apk add jq", null],
    ["htop", "htop-dev/htop", "Interactive process viewer", "System", "apk add htop", null],
    ["fastfetch", "fastfetch-cli/fastfetch", "Fast system information tool", "System", "apk add fastfetch", null],
    ["ohmyzsh", "ohmyzsh/ohmyzsh", "Community zsh framework", "Shell", "git clone https://github.com/ohmyzsh/ohmyzsh", null],
    ["lazygit", "jesseduffield/lazygit", "Simple terminal UI for git commands", "Dev", "apk add lazygit", null],
  ];

  let plugCat = "All";
  let plugQuery = "";

  function drawPlugins() {
    const catsEl = $("p-cats");
    const listEl = $("p-list");
    const groups = ["All", ...new Set(CATALOG.map((x) => x[3]))];

    catsEl.innerHTML = "";
    groups.forEach((g) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = g;
      b.className = g === plugCat ? "on" : "";
      b.onclick = () => {
        plugCat = g;
        drawPlugins();
      };
      catsEl.appendChild(b);
    });

    listEl.innerHTML = "";
    const installedPacks = Array.isArray(settings.themePacks) ? settings.themePacks : [];
    const filtered = CATALOG.filter(([name, repo, desc, grp, cmd]) => {
      const matchesCat = plugCat === "All" || grp === plugCat;
      const haystack = (name + " " + repo + " " + desc + " " + cmd).toLowerCase();
      return matchesCat && haystack.includes(plugQuery);
    });

    if (!filtered.length) {
      listEl.textContent = "No package or theme pack matches.";
      return;
    }

    filtered.forEach(([name, repo, desc, _grp, cmd, themePackId]) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "it";

      const isPack = !!themePackId;
      const packInstalled = isPack && installedPacks.includes(themePackId);

      const title = document.createElement("b");
      title.textContent = name + (packInstalled ? " · installed ✓" : "");

      const sub = document.createElement("small");
      sub.textContent = desc + (repo === "built-in" ? "" : " (" + repo + ")");

      const code = document.createElement("code");
      code.textContent = isPack
        ? (packInstalled ? "Tap to apply theme: " + themePackId : "Tap to install & apply theme pack")
        : cmd;

      btn.append(title, sub, code);
      btn.onclick = () => {
        if (isPack) {
          if (!settings.themePacks.includes(themePackId)) {
            settings.themePacks = [...settings.themePacks, themePackId];
          }
          settings.theme = themePackId;
          if (hasBridge && typeof window.TermBridge.installThemePack === "function") {
            window.TermBridge.installThemePack(themePackId);
          } else {
            saveSettings();
          }
          applySettings();
          statusEl.textContent = "theme: " + themePackId;
          drawPlugins();
          return;
        }
        plugModal.hidden = true;
        setModal.hidden = true;
        syncFixedKeyBarPosition();
        if (hasBridge) window.TermBridge.hideKeyboard();
        const t = activeTab();
        if (t) {
          sendInputToNative(t.id, cmd);
          focusTerminal(t, false);
        }
      };
      listEl.appendChild(btn);
    });
  }

  $("s-plug").onclick = () => {
    setModal.hidden = true;
    plugModal.hidden = false;
    syncFixedKeyBarPosition();
    drawPlugins();
  };

  $("p-back").onclick = () => {
    plugModal.hidden = true;
    setModal.hidden = false;
    syncFixedKeyBarPosition();
    if (hasBridge) window.TermBridge.hideKeyboard();
  };

  $("p-search").oninput = (e) => {
    plugQuery = e.target.value.toLowerCase();
    drawPlugins();
  };

  window.terBridge = {
    onNativeOutput(tabId, base64Bytes) {
      const tab = tabs.find((t) => t.id === tabId);
      if (!tab) return;
      try {
        const bytes = base64ToUint8(base64Bytes);
        tab.term.write(bytes);
      } catch (err) {
        if (hasBridge) window.TermBridge.logJsError("decode output: " + err.message);
      }
    },
    onNativeStatus(tabId, status, message) {
      if (tabId === activeTabId || !tabId) {
        statusEl.textContent = message || status;
      }
    },
    insertCommand(cmd) {
      const t = activeTab();
      if (t && cmd) {
        sendInputToNative(t.id, cmd);
        focusTerminal(t, false);
      }
    },
    handleBackPress() {
      if (!tabDrawer.hidden) {
        closeTabDrawer();
        return true;
      }
      if (!plugModal.hidden) {
        plugModal.hidden = true;
        syncFixedKeyBarPosition();
        if (hasBridge) window.TermBridge.hideKeyboard();
        focusTerminal(activeTab(), false);
        return true;
      }
      if (!setModal.hidden) {
        closeSettings();
        return true;
      }
      if (freeWriteOpen) {
        closeFreeWrite();
        return true;
      }
      return false;
    },
  };

  applySettings();
  createTab();
  if (hasBridge) {
    window.TermBridge.onReady();
  }
})();
