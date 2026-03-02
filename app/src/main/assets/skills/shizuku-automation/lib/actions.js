'use strict';

const path = require('path');
const os = require('os');

const KEY_MAP = {
  home: 3,
  back: 4,
  call: 5,
  endcall: 6,
  enter: 66,
  delete: 67,
  recent: 187,
  power: 26,
  volumeup: 24,
  volumedown: 25,
  paste: 279,
  copy: 278,
  cut: 277,
  selectall: 232,
  tab: 61,
  space: 62,
  escape: 111,
};

const CLIPBOARD_BROADCAST_ACTION = 'app.botdrop.SET_CLIPBOARD';

class Actions {
  constructor(bridgeClient, uiEngine) {
    this._bridge = bridgeClient;
    this._ui = uiEngine;
  }

  // ── Basic Input ──────────────────────────────────────────────────────────

  async tap(x, y) {
    const res = await this._bridge.execOrThrow(`input tap ${Math.round(x)} ${Math.round(y)}`);
    return { ok: true, x: Math.round(x), y: Math.round(y) };
  }

  async tapElement(selector) {
    return this._ui.tap(selector);
  }

  async swipe(x1, y1, x2, y2, durationMs = 300) {
    await this._bridge.execOrThrow(
      `input swipe ${Math.round(x1)} ${Math.round(y1)} ${Math.round(x2)} ${Math.round(y2)} ${durationMs}`
    );
    return { ok: true };
  }

  async press(key) {
    const code = typeof key === 'number' ? key : KEY_MAP[key.toLowerCase()];
    if (code === undefined) {
      throw Object.assign(new Error(`Unknown key: ${key}. Valid keys: ${Object.keys(KEY_MAP).join(', ')}`), {
        code: 'INVALID_KEY',
      });
    }
    await this._bridge.execOrThrow(`input keyevent ${code}`);
    return { ok: true, key, keycode: code };
  }

  // ── Text Input ───────────────────────────────────────────────────────────

  /**
   * Auto-detect: ASCII-only → input text, any non-ASCII → clipboard method.
   */
  async type(text) {
    const hasNonAscii = /[^\x00-\x7F]/.test(text);
    if (hasNonAscii) {
      return this.typeViaClipboard(text);
    }
    return this.typeAscii(text);
  }

  /**
   * Force ASCII input via `input text`. Escapes shell special chars.
   */
  async typeAscii(text) {
    // Escape special shell characters
    const escaped = text.replace(/(['"\\$`!])/g, '\\$1').replace(/ /g, '%s');
    await this._bridge.execOrThrow(`input text "${escaped}"`);
    return { ok: true, method: 'input-text', text };
  }

  /**
   * Set text via BotDrop clipboard broadcast + paste keyevent.
   * Requires BotDrop Android ClipboardReceiver to be registered.
   */
  async typeViaClipboard(text) {
    // Escape single quotes for shell
    const escaped = text.replace(/'/g, "'\\''");
    const broadcast = `am broadcast -a ${CLIPBOARD_BROADCAST_ACTION} --es text '${escaped}'`;
    await this._bridge.execOrThrow(broadcast, 10000);
    // Small delay to ensure clipboard is set before paste
    await sleep(200);
    await this._bridge.execOrThrow(`input keyevent ${KEY_MAP.paste}`);
    return { ok: true, method: 'clipboard', text };
  }

  // ── App Management ────────────────────────────────────────────────────────

  async launch(packageName, activity = null) {
    let cmd;
    if (activity) {
      cmd = `am start -n "${packageName}/${activity}"`;
    } else {
      cmd = `monkey -p "${packageName}" -c android.intent.category.LAUNCHER 1`;
    }
    const res = await this._bridge.execOrThrow(cmd, 15000);
    return { ok: true, packageName, activity };
  }

  async kill(packageName) {
    await this._bridge.execOrThrow(`am force-stop "${packageName}"`);
    return { ok: true, packageName };
  }

  async currentApp() {
    const res = await this._bridge.execOrThrow(
      `dumpsys activity top 2>/dev/null | grep -E "ACTIVITY|mCurrentFocus" | head -5`,
      10000
    );
    const stdout = res.stdout || '';

    // Parse package/activity from ACTIVITY line: "ACTIVITY com.pkg/.Activity"
    const actMatch = stdout.match(/ACTIVITY\s+([a-z][a-z0-9._]+)\/([^\s]+)/i);
    // Parse from mCurrentFocus: "mCurrentFocus=Window{... com.pkg/com.pkg.Activity}"
    const focusMatch = stdout.match(/mCurrentFocus=Window\{[^}]+\s+([a-z][a-z0-9._]+)\/([^\s}]+)/i);

    const match = actMatch || focusMatch;
    if (match) {
      return { ok: true, packageName: match[1], activity: match[2], raw: stdout.trim() };
    }
    return { ok: true, packageName: null, activity: null, raw: stdout.trim() };
  }

  async installedApps() {
    const res = await this._bridge.execOrThrow(`pm list packages 2>/dev/null`, 20000);
    const packages = (res.stdout || '')
      .split('\n')
      .map((l) => l.trim().replace(/^package:/, ''))
      .filter(Boolean);
    return { ok: true, packages };
  }

  // ── Screen ───────────────────────────────────────────────────────────────

  async screenshot(outputPath = null) {
    const dest = outputPath || path.join(os.tmpdir(), 'shizuku-screenshot.png');
    // screencap writes to stdout in PNG format; redirect to temp file then pull
    const tmpAndroid = '/data/local/tmp/shizuku-shot.png';
    await this._bridge.execOrThrow(`screencap -p ${tmpAndroid}`, 15000);
    // Copy to Termux-accessible path if needed
    await this._bridge.execOrThrow(`cp ${tmpAndroid} ${dest} 2>/dev/null || cat ${tmpAndroid} > ${dest}`, 10000);
    return { ok: true, path: dest };
  }

  async screenSize() {
    const res = await this._bridge.execOrThrow(`wm size`);
    const m = res.stdout.match(/Physical size:\s*(\d+)x(\d+)/);
    if (m) {
      return { ok: true, width: parseInt(m[1], 10), height: parseInt(m[2], 10) };
    }
    const m2 = res.stdout.match(/(\d+)x(\d+)/);
    if (m2) {
      return { ok: true, width: parseInt(m2[1], 10), height: parseInt(m2[2], 10) };
    }
    return { ok: true, width: null, height: null, raw: res.stdout.trim() };
  }

  // ── Device Info ───────────────────────────────────────────────────────────

  async deviceInfo() {
    const res = await this._bridge.execOrThrow(
      `getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.manufacturer`,
      10000
    );
    const lines = (res.stdout || '').split('\n').map((l) => l.trim());
    return {
      ok: true,
      model: lines[0] || '',
      androidVersion: lines[1] || '',
      sdkVersion: lines[2] || '',
      manufacturer: lines[3] || '',
    };
  }

  async batteryInfo() {
    const res = await this._bridge.execOrThrow(`dumpsys battery 2>/dev/null | head -20`, 10000);
    const stdout = res.stdout || '';
    const get = (key) => {
      const m = stdout.match(new RegExp(key + ':\\s*(\\S+)'));
      return m ? m[1] : null;
    };
    return {
      ok: true,
      level: get('level'),
      charging: get('status') === '2',
      temperature: get('temperature'),
      raw: stdout.trim(),
    };
  }

  // ── UI Helpers ─────────────────────────────────────────────────────────────

  async uiDump(filterSelector = null) {
    const elements = await this._ui.dump();
    if (filterSelector) {
      const { matchesSelector } = require('./ui-engine');
      return elements.filter((el) => matchesSelector(el, filterSelector));
    }
    return elements;
  }

  async waitFor(selector, timeoutMs = 10000) {
    return this._ui.waitFor(selector, timeoutMs);
  }

  // ── Raw Exec ───────────────────────────────────────────────────────────────

  async exec(command, timeoutMs = 30000) {
    return this._bridge.exec(command, timeoutMs);
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

module.exports = { Actions };
