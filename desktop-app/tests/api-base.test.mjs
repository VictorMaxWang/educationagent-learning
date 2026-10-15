import test from 'node:test';
import assert from 'node:assert/strict';
import { selectApiBaseUrl } from '../src/api/baseUrl.js';

test('手机与 HTTPS 网页使用同源 API，保留 Tauri 与显式远程服务', () => {
  const mobile = { hostname: '192.168.1.10', protocol: 'https:' };
  assert.equal(selectApiBaseUrl('http://localhost:8888', mobile), '');
  assert.equal(selectApiBaseUrl('http://127.0.0.1:8888', mobile), '');
  assert.equal(selectApiBaseUrl('', mobile), '');
  assert.equal(selectApiBaseUrl('', undefined, true), 'http://localhost:8888');
  assert.equal(selectApiBaseUrl('https://example.test/api/', mobile), 'https://example.test/api');
  assert.equal(selectApiBaseUrl('/backend', mobile), '/backend');
});
