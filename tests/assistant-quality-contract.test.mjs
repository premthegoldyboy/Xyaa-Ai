import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');

test('Gemini internal thought parts are excluded and final text streams into chat', () => {
  const activity = read('app/src/main/java/ai/xyaa/assistant/MainActivity.kt');
  assert.match(activity, /part\.optBoolean\("thought", false\)/);
  assert.match(activity, /AssistantTextFormatter\.forStreamingDisplay\(partial\)/);
  assert.match(activity, /Never output private reasoning, internal dialogue, scratchpad/);
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
  assert.match(research, /isGamblingTopic/);
  assert.match(research, /isGamblingSource/);
  assert.equal(research.split('optBoolean("thought", false)').length - 1, 2);
});

test('natural device commands are normalized and phone calls remain user-confirmed', () => {
  const skills = read('app/src/main/java/ai/xyaa/assistant/SkillEngine.kt');
  assert.match(skills, /repeat\(2\)/);
  assert.match(skills, /can you/);
  assert.match(skills, /Intent\.ACTION_DIAL/);
  assert.match(skills, /will not place a call automatically/);
  assert.match(skills, /Settings\.ACTION_WIFI_SETTINGS/);
});


test('a newer chat request owns the transcript and speech output', () => {
  const activity = read('app/src/main/java/ai/xyaa/assistant/MainActivity.kt');
  assert.match(activity, /AtomicLong\(0L\)/);
  assert.match(activity, /requestSequence\.incrementAndGet\(\)/);
  assert.match(activity, /requestSequence\.get\(\) == requestId/);
  assert.match(activity, /stopSpeech\(completeInterrupted = false\)/);
  assert.match(activity, /rememberForRequest/);
  assert.match(activity, /sayForRequest/);
});


test('remember-note commands are not misrouted into personal memory', () => {
  const activity = read('app/src/main/java/ai/xyaa/assistant/MainActivity.kt');
  assert.ok(activity.includes('remember\\\\s+(?:a\\\\s+)?note\\\\b'));
  assert.match(activity, /rememberMatch != null/);
});


test('streaming chat never renders an unfinished internal reasoning block', () => {
  const formatter = read('app/src/main/java/ai/xyaa/assistant/AssistantTextFormatter.kt');
  const activity = read('app/src/main/java/ai/xyaa/assistant/MainActivity.kt');
  assert.match(formatter, /fun forStreamingDisplay/);
  assert.match(formatter, /lastOpen > lastClose/);
  assert.equal(activity.split('AssistantTextFormatter.forStreamingDisplay(partial)').length - 1, 2);
});


test('Gemini research filters prohibited source categories without shifting grounding citations', () => {
  const research = read('app/src/main/java/ai/xyaa/assistant/ResearchEngine.kt');
  assert.match(research, /if \(isGamblingSource\(source\)/);
  assert.match(research, /citationNumbers\[i\] = sources\.size/);
  assert.match(research, /applyGroundingCitations\(raw, candidate, citationNumbers\)/);
  assert.match(research, /citationNumbers\[indices\.optInt\(it, -1\)\]/);
});


test('speech recognition submits the transcript through the same visible chat flow', () => {
  const activity = read('app/src/main/java/ai/xyaa/assistant/MainActivity.kt');
  const ui = read('app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt');
  assert.match(activity, /onVoice = \{ onRecognized -> startVoiceInput\(onRecognized\) \}/);
  assert.match(activity, /pendingVoiceRecognition\?\.invoke\(spoken\)/);
  assert.match(activity, /startVoiceInput\(onRecognized: \(\(String\) -> Unit\)\? = null\)/);
  assert.match(ui, /onVoice: \(\(String\) -> Unit\) -> Unit/);
  assert.equal((ui.match(/onVoice \{ spoken -> submit\(spoken\) \}/g) || []).length, 3);
});
