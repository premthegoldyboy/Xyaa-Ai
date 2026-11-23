import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');

test('Gemini internal thought parts are excluded and final text streams into chat', () => {
  const activity = read('app/src/main/java/ai/xyaa/assistant/MainActivity.kt');
  assert.match(activity, /part\.optBoolean\("thought", false\)/);
  assert.match(activity, /AssistantTextFormatter\.forRichDisplay\(partial\)/);
  assert.match(activity, /Never reveal private chain-of-thought/);
  assert.match(activity, /answer\(visibleAnswer, true\)/);
});

test('backup AI providers stream only assistant content deltas', () => {
  const fallback = read('app/src/main/java/ai/xyaa/assistant/ProviderFallbackClient.kt');
  assert.match(fallback, /\.put\("stream", true\)/);
  assert.match(fallback, /optJSONObject\("delta"\)/);
  assert.match(fallback, /delta\.optString\("content"\)/);
  assert.match(fallback, /AssistantTextFormatter\.forRichDisplay/);
});

test('hidden reasoning and protocol wrappers are filtered from user-facing text', () => {
  const formatter = read('app/src/main/java/ai/xyaa/assistant/AssistantTextFormatter.kt');
  assert.match(formatter, /think\|analysis\|reasoning/);
  assert.match(formatter, /private thoughts\?/);
  assert.match(formatter, /chain of thought/);
});

test('recent-trends intent routes to research and applies a 30-day relevance pipeline', () => {
  const router = read('app/src/main/java/ai/xyaa/assistant/XYAAAgentOrchestrator.kt');
  const research = read('app/src/main/java/ai/xyaa/assistant/ResearchEngine.kt');
  assert.match(router, /community sentiment/);
  assert.match(router, /last.*30.*days/);
  assert.match(research, /after:\$\{java\.time\.LocalDate\.now\(\)\.minusDays\(30\)\}/);
  assert.match(research, /time_range", "month"/);
  assert.match(research, /rankRecentSources/);
  assert.match(research, /engagementScore/);
  assert.match(research, /Exclude gambling-related sources/);
  assert.equal((research.match(/optBoolean\\("thought", false\\)/g) || []).length, 2);
});

test('natural device commands are normalized and phone calls remain user-confirmed', () => {
  const skills = read('app/src/main/java/ai/xyaa/assistant/SkillEngine.kt');
  assert.match(skills, /repeat\(2\)/);
  assert.match(skills, /can you/);
  assert.match(skills, /Intent\.ACTION_DIAL/);
  assert.match(skills, /will not place a call automatically/);
  assert.match(skills, /Settings\.ACTION_WIFI_SETTINGS/);
});
