#!/usr/bin/env node
'use strict';

const { BridgeClient } = require('./lib/bridge-client');
const { UIEngine } = require('./lib/ui-engine');
const { Actions } = require('./lib/actions');

function ok(data) {
  process.stdout.write(JSON.stringify({ ok: true, data }) + '\n');
  process.exit(0);
}

function fail(error, message, extra = {}) {
  process.stdout.write(JSON.stringify({ ok: false, error, message, ...extra }) + '\n');
  process.exit(1);
}

function parseSelector(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    fail('INVALID_ARGS', 'Selector must be valid JSON: ' + raw);
  }
}

function parseArgs(argv) {
  const args = { flags: {}, positional: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) {
        args.flags[key] = next;
        i++;
      } else {
        args.flags[key] = true;
      }
    } else {
      args.positional.push(a);
    }
  }
  return args;
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.length === 0 || argv[0] === 'help' || argv[0] === '--help') {
    showHelp();
    process.exit(0);
  }

  const [command, ...rest] = argv;
  const args = parseArgs(rest);

  const bridge = new BridgeClient(args.flags.config || undefined);
  const ui = new UIEngine(bridge);
  const actions = new Actions(bridge, ui);

  try {
    switch (command) {
      case 'status': {
        const res = await bridge.isAvailable();
        ok(res);
        break;
      }

      case 'screenshot': {
        const res = await actions.screenshot(args.flags.output || null);
        ok(res);
        break;
      }

      case 'current-app': {
        const res = await actions.currentApp();
        ok(res);
        break;
      }

      case 'launch': {
        const pkg = args.positional[0];
        const activity = args.positional[1] || args.flags.activity || null;
        if (!pkg) fail('INVALID_ARGS', 'Usage: launch <package> [activity]');
        const res = await actions.launch(pkg, activity);
        ok(res);
        break;
      }

      case 'kill': {
        const pkg = args.positional[0];
        if (!pkg) fail('INVALID_ARGS', 'Usage: kill <package>');
        const res = await actions.kill(pkg);
        ok(res);
        break;
      }

      case 'tap': {
        const x = parseFloat(args.positional[0]);
        const y = parseFloat(args.positional[1]);
        if (isNaN(x) || isNaN(y)) fail('INVALID_ARGS', 'Usage: tap <x> <y>');
        const res = await actions.tap(x, y);
        ok(res);
        break;
      }

      case 'tap-element': {
        const raw = args.positional[0];
        if (!raw) fail('INVALID_ARGS', 'Usage: tap-element \'{"text":"OK"}\'');
        const selector = parseSelector(raw);
        const res = await actions.tapElement(selector);
        ok(res);
        break;
      }

      case 'swipe': {
        const [x1, y1, x2, y2, dur] = args.positional.map(Number);
        if ([x1, y1, x2, y2].some(isNaN)) {
          fail('INVALID_ARGS', 'Usage: swipe <x1> <y1> <x2> <y2> [durationMs]');
        }
        const res = await actions.swipe(x1, y1, x2, y2, dur || 300);
        ok(res);
        break;
      }

      case 'press': {
        const key = args.positional[0];
        if (!key) fail('INVALID_ARGS', 'Usage: press <key> (home/back/enter/recent/paste/...)');
        const res = await actions.press(key);
        ok(res);
        break;
      }

      case 'type': {
        const text = args.positional[0] !== undefined
          ? args.positional.join(' ')
          : args.flags.text;
        if (text === undefined) fail('INVALID_ARGS', 'Usage: type <text>');
        const res = await actions.type(text);
        ok(res);
        break;
      }

      case 'ui-dump': {
        const rawSelector = args.flags.find || null;
        const selector = rawSelector ? parseSelector(rawSelector) : null;
        const elements = await actions.uiDump(selector);
        ok({ elements, count: elements.length });
        break;
      }

      case 'wait-for': {
        const raw = args.positional[0];
        if (!raw) fail('INVALID_ARGS', "Usage: wait-for '{\"text\":\"OK\"}' [--timeout ms]");
        const selector = parseSelector(raw);
        const timeout = parseInt(args.flags.timeout || '10000', 10);
        const el = await actions.waitFor(selector, timeout);
        ok({ element: el });
        break;
      }

      case 'device-info': {
        const res = await actions.deviceInfo();
        ok(res);
        break;
      }

      case 'battery': {
        const res = await actions.batteryInfo();
        ok(res);
        break;
      }

      case 'installed-apps': {
        const res = await actions.installedApps();
        ok(res);
        break;
      }

      case 'screen-size': {
        const res = await actions.screenSize();
        ok(res);
        break;
      }

      case 'exec': {
        const cmd = args.positional.join(' ');
        if (!cmd) fail('INVALID_ARGS', 'Usage: exec <shell command>');
        const timeout = parseInt(args.flags.timeout || '30000', 10);
        const res = await actions.exec(cmd, timeout);
        ok(res);
        break;
      }

      default:
        fail('UNKNOWN_COMMAND', `Unknown command: ${command}. Run 'help' for usage.`);
    }
  } catch (err) {
    fail(err.code || 'ERROR', err.message, { stack: err.stack });
  }
}

function showHelp() {
  console.log(`
Shizuku Android Automation — OpenClaw Skill

USAGE: node cli.js <command> [args...]

COMMANDS:
  status                              Check Bridge + Shizuku status
  screenshot [--output <path>]        Take screenshot
  current-app                         Get foreground app info
  launch <pkg> [activity]             Launch app by package name
  kill <pkg>                          Force stop app
  tap <x> <y>                         Tap screen coordinates
  tap-element '<selector>'            Find element and tap it
  swipe <x1> <y1> <x2> <y2> [ms]     Swipe gesture
  press <key>                         Press key (home/back/enter/recent/paste)
  type <text>                         Input text (auto handles Chinese)
  ui-dump [--find '<selector>']       Dump UI tree (optionally filtered)
  wait-for '<selector>' [--timeout]   Wait for element to appear
  device-info                         Device model, Android version
  battery                             Battery status
  installed-apps                      List installed packages
  screen-size                         Screen dimensions
  exec <cmd>                          Run raw shell command via Shizuku

SELECTOR FORMAT (JSON):
  {"text":"发送"}                     Exact text match
  {"textContains":"发"}               Text contains
  {"resourceId":"com.xx:id/btn"}      Resource ID
  {"className":"android.widget.Button"}
  {"description":"Send"}              Content description
  {"text":"OK","clickable":true}      Combined (AND logic)

OUTPUT: Always JSON — {"ok":true,"data":{...}} or {"ok":false,"error":"CODE","message":"..."}
`.trim());
}

main().catch((err) => {
  fail('UNCAUGHT', err.message);
});
