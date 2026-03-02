'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');

const DEFAULT_CONFIG_PATH = path.join(
  process.env.HOME || '/data/data/com.termux/files/home',
  '.openclaw',
  'shizuku-bridge.json'
);

const ERROR = {
  BRIDGE_NOT_FOUND: 'BRIDGE_NOT_FOUND',
  BRIDGE_UNREACHABLE: 'BRIDGE_UNREACHABLE',
  SHIZUKU_NOT_READY: 'SHIZUKU_NOT_READY',
  EXEC_FAILED: 'EXEC_FAILED',
  TIMEOUT: 'TIMEOUT',
};

class BridgeClient {
  constructor(configPath) {
    this._configPath = configPath || DEFAULT_CONFIG_PATH;
  }

  _readConfig() {
    try {
      const raw = fs.readFileSync(this._configPath, 'utf8');
      const cfg = JSON.parse(raw);
      if (!cfg.host || !cfg.port || !cfg.token) {
        return null;
      }
      return cfg;
    } catch {
      return null;
    }
  }

  _request(method, path, body, timeoutMs) {
    const cfg = this._readConfig();
    if (!cfg) {
      return Promise.resolve({
        ok: false,
        error: ERROR.BRIDGE_NOT_FOUND,
        message: 'Bridge config not found at ' + this._configPath,
        exitCode: -1,
        stdout: '',
        stderr: '',
      });
    }

    return new Promise((resolve) => {
      const payload = body ? JSON.stringify(body) : null;
      const options = {
        hostname: cfg.host,
        port: cfg.port,
        path,
        method,
        timeout: timeoutMs || 30000,
        headers: {
          Authorization: 'Bearer ' + cfg.token,
          ...(payload
            ? {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
              }
            : {}),
        },
      };

      const req = http.request(options, (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8');
          try {
            resolve(JSON.parse(raw));
          } catch {
            resolve({
              ok: false,
              error: ERROR.EXEC_FAILED,
              message: 'Invalid JSON response: ' + raw.slice(0, 200),
              exitCode: -1,
              stdout: '',
              stderr: '',
            });
          }
        });
      });

      req.on('timeout', () => {
        req.destroy();
        resolve({
          ok: false,
          error: ERROR.TIMEOUT,
          message: 'Request timed out after ' + (timeoutMs || 30000) + 'ms',
          exitCode: -1,
          stdout: '',
          stderr: '',
        });
      });

      req.on('error', (err) => {
        resolve({
          ok: false,
          error: ERROR.BRIDGE_UNREACHABLE,
          message: 'Bridge unreachable: ' + err.message,
          exitCode: -1,
          stdout: '',
          stderr: '',
        });
      });

      if (payload) req.write(payload);
      req.end();
    });
  }

  async isAvailable() {
    const cfg = this._readConfig();
    if (!cfg) {
      return { available: false, error: ERROR.BRIDGE_NOT_FOUND, message: 'Config file not found' };
    }

    const res = await this._request('GET', '/shizuku/status', null, 5000);
    if (res.error === ERROR.BRIDGE_UNREACHABLE) {
      return { available: false, error: ERROR.BRIDGE_UNREACHABLE, message: res.message };
    }
    if (res.status && res.status !== 'READY') {
      return {
        available: false,
        error: ERROR.SHIZUKU_NOT_READY,
        message: 'Shizuku status: ' + res.status,
        status: res.status,
        serviceBound: res.serviceBound,
      };
    }

    return {
      available: true,
      status: res.status || 'READY',
      serviceBound: res.serviceBound !== undefined ? res.serviceBound : true,
    };
  }

  async exec(command, timeoutMs = 30000) {
    return this._request('POST', '/shizuku/exec', { command, timeoutMs }, timeoutMs + 5000);
  }

  async execOrThrow(command, timeoutMs = 30000) {
    const res = await this.exec(command, timeoutMs);
    if (!res.ok) {
      const err = new Error(res.message || res.stderr || res.error || 'exec failed');
      err.code = res.error || ERROR.EXEC_FAILED;
      err.result = res;
      throw err;
    }
    return res;
  }
}

module.exports = { BridgeClient, ERROR, DEFAULT_CONFIG_PATH };
