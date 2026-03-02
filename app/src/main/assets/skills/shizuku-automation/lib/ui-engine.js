'use strict';

const DUMP_TIMEOUT_MS = 15000;
const DEFAULT_WAIT_TIMEOUT_MS = 10000;
const POLL_INTERVAL_MS = 500;
const TMP_DUMP_PATH = '/data/local/tmp/shizuku-ui-dump.xml';

/**
 * Parse uiautomator XML dump into a flat list of elements.
 * Uses lightweight regex — no external XML dependency.
 */
function parseXml(xml) {
  const elements = [];
  // Match all <node ... /> or <node ...> tags
  const nodeRe = /<node([^>]*)\/?>|<node([^>]*)>/g;
  let m;
  while ((m = nodeRe.exec(xml)) !== null) {
    const attrs = m[1] || m[2] || '';
    const el = parseAttrs(attrs);
    if (el) elements.push(el);
  }
  return elements;
}

function parseAttrs(attrs) {
  const get = (name) => {
    const re = new RegExp(name + '="([^"]*)"');
    const m = attrs.match(re);
    return m ? m[1] : '';
  };

  const boundsStr = get('bounds');
  const bounds = parseBounds(boundsStr);

  return {
    text: get('text'),
    resourceId: get('resource-id'),
    className: get('class'),
    description: get('content-desc'),
    bounds,
    center: bounds
      ? {
          x: Math.round((bounds.left + bounds.right) / 2),
          y: Math.round((bounds.top + bounds.bottom) / 2),
        }
      : null,
    clickable: get('clickable') === 'true',
    enabled: get('enabled') === 'true',
    focusable: get('focusable') === 'true',
    scrollable: get('scrollable') === 'true',
    packageName: get('package'),
  };
}

function parseBounds(str) {
  // Format: [left,top][right,bottom]
  const m = str.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  if (!m) return null;
  return {
    left: parseInt(m[1], 10),
    top: parseInt(m[2], 10),
    right: parseInt(m[3], 10),
    bottom: parseInt(m[4], 10),
  };
}

/**
 * Match a single element against a selector (all fields are AND logic).
 */
function matchesSelector(el, selector) {
  if (!selector || typeof selector !== 'object') return false;

  if (selector.text !== undefined && el.text !== selector.text) return false;
  if (selector.textContains !== undefined && !el.text.includes(selector.textContains)) return false;
  if (selector.resourceId !== undefined && el.resourceId !== selector.resourceId) return false;
  if (selector.className !== undefined && el.className !== selector.className) return false;
  if (selector.description !== undefined && el.description !== selector.description) return false;
  if (selector.descriptionContains !== undefined && !el.description.includes(selector.descriptionContains)) return false;
  if (selector.clickable !== undefined && el.clickable !== selector.clickable) return false;
  if (selector.enabled !== undefined && el.enabled !== selector.enabled) return false;
  if (selector.packageName !== undefined && el.packageName !== selector.packageName) return false;

  return true;
}

class UIEngine {
  constructor(bridgeClient) {
    this._bridge = bridgeClient;
  }

  /**
   * Dump UI hierarchy and return parsed element list.
   */
  async dump() {
    // Try /dev/stdout first (no temp file needed)
    let res = await this._bridge.exec(`uiautomator dump /dev/stdout 2>/dev/null`, DUMP_TIMEOUT_MS);
    if (res.ok && res.stdout && res.stdout.includes('<hierarchy')) {
      return parseXml(res.stdout);
    }

    // Fallback: dump to temp file then cat
    await this._bridge.exec(`uiautomator dump ${TMP_DUMP_PATH} 2>/dev/null`, DUMP_TIMEOUT_MS);
    res = await this._bridge.exec(`cat ${TMP_DUMP_PATH} 2>/dev/null`, 5000);
    if (res.ok && res.stdout && res.stdout.includes('<hierarchy')) {
      return parseXml(res.stdout);
    }

    throw Object.assign(new Error('UI dump failed: ' + (res.stderr || res.message || 'unknown error')), {
      code: 'DUMP_FAILED',
    });
  }

  /**
   * Find all elements matching selector.
   */
  async find(selector) {
    const elements = await this.dump();
    return elements.filter((el) => matchesSelector(el, selector));
  }

  /**
   * Find first element matching selector, or null.
   */
  async findOne(selector) {
    const elements = await this.dump();
    return elements.find((el) => matchesSelector(el, selector)) || null;
  }

  /**
   * Check if element matching selector exists.
   */
  async exists(selector) {
    const el = await this.findOne(selector);
    return el !== null;
  }

  /**
   * Wait until an element matching selector appears.
   * Returns the element when found, throws on timeout.
   */
  async waitFor(selector, timeoutMs = DEFAULT_WAIT_TIMEOUT_MS) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const el = await this.findOne(selector);
      if (el) return el;
      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      await sleep(Math.min(POLL_INTERVAL_MS, remaining));
    }
    throw Object.assign(
      new Error('Element not found within ' + timeoutMs + 'ms: ' + JSON.stringify(selector)),
      { code: 'ELEMENT_NOT_FOUND' }
    );
  }

  /**
   * Find element by selector and tap its center.
   */
  async tap(selector) {
    const el = await this.findOne(selector);
    if (!el) {
      throw Object.assign(
        new Error('Element not found: ' + JSON.stringify(selector)),
        { code: 'ELEMENT_NOT_FOUND' }
      );
    }
    if (!el.center) {
      throw Object.assign(
        new Error('Element has no bounds: ' + JSON.stringify(selector)),
        { code: 'ELEMENT_NO_BOUNDS' }
      );
    }
    const res = await this._bridge.exec(`input tap ${el.center.x} ${el.center.y}`);
    if (!res.ok) {
      throw Object.assign(new Error('Tap failed: ' + res.stderr), { code: 'EXEC_FAILED' });
    }
    return { element: el, tapped: el.center };
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { UIEngine, parseXml, matchesSelector };
