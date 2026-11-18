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

test('Android agent router gives PDF and explicit research intents priority over generic skills', () => {
  const router = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XYAAAgentOrchestrator.kt', import.meta.url), 'utf8');
  const activity = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/MainActivity.kt', import.meta.url), 'utf8');
  assert.match(router, /if \(pdf\)/);
  assert.match(router, /isExplicitResearchRequest/);
  assert.match(router, /search\\\\s\+for/);
  assert.match(router, /WEB_RESEARCH/);
  assert.ok(activity.indexOf('val agentPlan = XYAAAgentOrchestrator.plan') < activity.indexOf('val local = if (pdfRequest || agentPlan.needsResearch) null else skills.execute(prompt)'));
  assert.match(activity, /val researchRequest = agentPlan.needsResearch/);
});

test('conversation-memory agent retrieves existing turns rather than inventing history', () => {
  const router = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XYAAAgentOrchestrator.kt', import.meta.url), 'utf8');
  assert.match(router, /memory\.recentTurns\(\)\.dropLast\(1\)/);
  assert.match(router, /couldn't find that detail in the recent conversation/);
});

test('PDF intent recognizes export-as-PDF phrasing and keeps research separate', () => {
  const docs = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/DocumentAgents.kt', import.meta.url), 'utf8');
  assert.match(docs, /as\\\\s\+\(\?:a/);
  assert.match(docs, /save\|convert/);
  const router = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XYAAAgentOrchestrator.kt', import.meta.url), 'utf8');
  assert.match(router, /val research = explicitResearch \|\| PdfAgent\.needsResearch/);
  assert.match(router, /what was \(\?:that\|my\)/);
});
