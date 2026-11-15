import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const manifest = JSON.parse(readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'));
const sw = readFileSync(new URL('../sw.js', import.meta.url), 'utf8');

test('app document contains the XYAA identity and required controls', () => {
  assert.match(html, /<title>XYAA<\/title>/);
  for (const id of ['chat', 'mic', 'handsfree', 'composer', 'prefs', 'apiKey']) {
    assert.match(html, new RegExp('id="' + id + '"'));
  }
  assert.doesNotMatch(html, /\b(?:Jarvis|Nova)\b/i);
});

test('inline application JavaScript parses', () => {
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/gi)];
  assert.equal(scripts.length, 1, 'expected one inline application script');
  assert.doesNotThrow(() => new Script(scripts[0][1]));
});

test('PWA manifest is valid and points to the app icon', () => {
  assert.equal(manifest.name, 'XYAA Assistant');
  assert.equal(manifest.start_url, './index.html');
  assert.ok(manifest.icons.some(icon => icon.src === 'icon.svg'));
});

test('service worker JavaScript parses and uses a versioned cache', () => {
  assert.doesNotThrow(() => new Script(sw));
  assert.match(sw, /xyaa-cache-v\d+/);
});

test('assistant actions are allowlisted and sensitive launches require confirmation', () => {
  for (const action of ['open_url', 'search', 'youtube', 'call', 'whatsapp', 'timer', 'time']) {
    assert.ok(html.includes("'" + action + "'"));
  }
  assert.match(html, /confirm\('Open phone app to call/);
  assert.match(html, /confirm\('Open WhatsApp chat with/);
});
