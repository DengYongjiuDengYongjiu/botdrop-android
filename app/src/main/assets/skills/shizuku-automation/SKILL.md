---
name: shizuku-automation
description: "Control Android apps via Shizuku Bridge. Use when: user asks to operate phone apps (open/close/switch), interact with screen (tap/swipe/type), take screenshots, inspect UI elements, or get device info. Requires BotDrop Android with Shizuku Bridge running."
---

# Shizuku Android Automation

Control Android device and apps through Shizuku Bridge from OpenClaw.

## Prerequisites

- BotDrop Android running with Shizuku authorized
- Bridge Server listening (check with `status` command)
- Config at `~/.openclaw/shizuku-bridge.json` (auto-written by BotDrop)

## Commands

All commands via: `node <skill-dir>/cli.js <command> [args...]`

### Connection
```bash
node cli.js status                          # Check Bridge + Shizuku status
```

### App Management
```bash
node cli.js launch <package> [activity]     # Launch app (e.g. com.tencent.mm)
node cli.js kill <package>                  # Force stop app
node cli.js current-app                     # Get foreground app info
```

### Screen Interaction
```bash
node cli.js tap <x> <y>                     # Tap coordinates
node cli.js tap-element '{"text":"发送"}'    # Find element by selector and tap
node cli.js swipe <x1> <y1> <x2> <y2> [ms] # Swipe gesture
node cli.js press <key>                     # Key press: home/back/enter/recent
```

### Text Input
```bash
node cli.js type "Hello"                    # Auto-detect: ASCII → input text, Chinese → clipboard
```

### UI Inspection
```bash
node cli.js ui-dump                         # Dump full UI tree
node cli.js ui-dump --find '{"text":"OK"}'  # Dump + filter elements
node cli.js wait-for '{"text":"OK"}' --timeout 10000  # Wait for element
```

### Screen Capture
```bash
node cli.js screenshot                      # Screenshot → /tmp/shizuku-screenshot.png
node cli.js screenshot --output /path.png   # Screenshot to specific path
```

### Device Info
```bash
node cli.js device-info                     # Device model, Android version, etc.
```

### Raw Command
```bash
node cli.js exec "dumpsys battery"          # Execute any shell command via Shizuku
```

## Selector Format

JSON object, fields combine with AND logic:

```json
{"text": "发送"}                     // exact text match
{"textContains": "发"}               // text contains
{"resourceId": "com.xx:id/btn"}      // resource-id
{"className": "android.widget.Button"}
{"description": "Send button"}       // content-desc
{"text": "OK", "clickable": true}    // combined
```

## Output Format

All commands output JSON to stdout:
```json
{"ok": true, "data": {...}}
{"ok": false, "error": "BRIDGE_NOT_FOUND", "message": "Bridge config not found"}
```

## Common Workflows

**Open an app and take screenshot:**
```bash
node cli.js launch com.tencent.mm
sleep 2
node cli.js screenshot
```

**Find and tap a button:**
```bash
node cli.js tap-element '{"text":"发送"}'
```

**Type Chinese text in a field:**
```bash
node cli.js tap-element '{"resourceId":"com.xx:id/input"}'
node cli.js type "你好世界"
```

**Wait for page to load then act:**
```bash
node cli.js wait-for '{"text":"加载完成"}' --timeout 15000
node cli.js tap-element '{"text":"下一步"}'
```

## Error Codes

- `BRIDGE_NOT_FOUND` — `~/.openclaw/shizuku-bridge.json` missing, Bridge not running
- `BRIDGE_UNREACHABLE` — Bridge port not responding
- `SHIZUKU_NOT_READY` — Bridge up but Shizuku not authorized
- `EXEC_FAILED` — Command execution failed
- `ELEMENT_NOT_FOUND` — UI element matching selector not found
- `TIMEOUT` — Operation timed out
