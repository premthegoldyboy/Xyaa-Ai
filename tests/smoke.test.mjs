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
  assert.ok(docs.includes('(?:as|into)\\\\s+'));
  assert.match(docs, /save\|convert/);
  const router = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XYAAAgentOrchestrator.kt', import.meta.url), 'utf8');
  assert.match(router, /val research = explicitResearch \|\| PdfAgent\.needsResearch/);
  assert.match(router, /what was \(\?:that\|my\)/);
});


test('Android chat renders structured Markdown and selectable code blocks', () => {
  const ui = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt', import.meta.url), 'utf8');
  assert.match(ui, /private fun MarkdownMessage/);
  assert.match(ui, /SelectionContainer/);
  assert.match(ui, /Copy code/);
  assert.match(ui, /horizontalScroll/);
  assert.match(ui, /buildMarkdownInline/);
});


test('spoken chat transcript enters smoothly and bubble growth is animated', () => {
  const ui = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt', import.meta.url), 'utf8');
  assert.match(ui, /AnimatedVisibility/);
  assert.match(ui, /fadeIn\(animationSpec = tween\(220\)\)/);
  assert.match(ui, /\.animateContentSize\(animationSpec = tween\(110\)\)/);
});

test('chat keeps streamed replies anchored at the bottom and avoids repetitive identity intros', () => {
  const ui = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt', import.meta.url), 'utf8');
  const activity = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/MainActivity.kt', import.meta.url), 'utf8');
  assert.match(ui, /snapshotFlow \{/);
  assert.match(ui, /last\.offset \+ last\.size - layout\.viewportEndOffset/);
  assert.match(ui, /if \(messages\.isNotEmpty\(\) && followOutput\)/);
  assert.doesNotMatch(ui, /scrollToItem\(messages\.lastIndex, scrollOffset = Int\.MAX_VALUE\)/);
  assert.match(activity, /Do not introduce yourself, repeat your name, or begin routine replies/);
});


test('Molten orb uses a continuous glossy liquid surface instead of polygon fragments', () => {
  const orb = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/MoltenOrb.kt', import.meta.url), 'utf8');
  assert.match(orb, /continuous liquid-glass body/);
  assert.match(orb, /Broad glass reflection/);
  assert.match(orb, /private const val MOLTEN_GRID = 128/);
  assert.match(orb, /FilterQuality.Medium/);
  assert.doesNotMatch(orb, /union of blurred polygons/);
});


test('Molten orb uses a continuous glossy liquid surface instead of polygon fragments', () => {
  const orb = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/MoltenOrb.kt', import.meta.url), 'utf8');
  assert.match(orb, /continuous liquid-glass body/);
  assert.match(orb, /Broad glass reflection/);
  assert.match(orb, /private const val MOLTEN_GRID = 128/);
  assert.match(orb, /FilterQuality.Medium/);
  assert.doesNotMatch(orb, /union of blurred polygons/);
});


test('Gemini Live voice uses XYAA playful personality and natural expressive delivery', () => {
  const live = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/GeminiLiveVoiceSession.kt', import.meta.url), 'utf8');
  assert.match(live, /playful, upbeat, warmly feminine conversational style/);
  assert.match(live, /bright, friendly, youthful energy/);
  assert.match(live, /natural pacing, clear pronunciation, and varied but comfortable intonation/);
  assert.match(live, /without mocking, flirting, or pretending to be human/);
  assert.match(live, /never claim to have done something you did not do/);
});


test('personal memory is encrypted, explicit, bounded, and independently controllable', () => {
  const store = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/PersonalMemoryStore.kt', import.meta.url), 'utf8');
  const activity = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/MainActivity.kt', import.meta.url), 'utf8');
  const ui = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt', import.meta.url), 'utf8');
  assert.match(store, /AndroidKeyStore/);
  assert.ok(store.includes("AES/GCM/NoPadding"));
  assert.match(store, /fun setPaused/);
  assert.match(store, /fun delete/);
  assert.match(store, /maxItems = 200/);
  assert.match(store, /if \(cleaned\.isBlank\(\) \|\| isPaused\(\) \|\| containsCredentialLikeData\(cleaned\)\)/);
  assert.match(store, /recovery code/);
  assert.match(store, /client secret/);
  assert.match(store, /PRIVATE KEY/);
  assert.match(store, /api\[_ -\]\?key/);
  assert.match(store, /safeCategory = category\.trim\(\)\.lowercase\(\)\.take\(32\)/);
  assert.match(activity, /rememberMatch/);
  assert.match(activity, /forgetMatch/);
  assert.ok(activity.includes("personalMemoryStore.contextForPrompt"));
  assert.match(ui, /PERSONAL MEMORY/);
  assert.match(ui, /onPausePersonalMemory/);
  assert.match(ui, /onDeletePersonalMemory/);
});

test('Code Studio captures encrypted development tasks without executing code on-device', () => {
  const store = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/CodeStudioTaskStore.kt', import.meta.url), 'utf8');
  const ui = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt', import.meta.url), 'utf8');
  assert.match(store, /AndroidKeyStore/);
  assert.ok(store.includes("AES/GCM/NoPadding"));
  assert.match(store, /Queued · runner not connected/);
  assert.match(store, /fun savePlan/);
  assert.match(store, /fun setApproved/);
  assert.match(store, /fun setPlanning\(/);
  assert.match(store, /fun setPlanningFailed\(/);
  assert.match(store, /approved: Boolean = false/);
  assert.match(store, /expectedRequest != null && tasks\[index\]\.request != expectedRequest/);
  assert.match(store, /request = cleaned, plan = "", approved = false/);
  assert.match(ui, /private fun CodeStudioPage/);
  assert.ok(ui.includes("isolated cloud coding/build runner is not connected yet"));
  assert.match(ui, /Generate plan/);
  assert.match(ui, /Approval required/);
  assert.match(ui, /Repository execution remains disabled/);
  assert.match(ui, /enabled=task.status!="Planning…"/);
});


test('cross-chat conversation history is encrypted and migrated from the legacy local format', () => {
  const history = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/ConversationMemory.kt', import.meta.url), 'utf8');
  assert.match(history, /AndroidKeyStore/);
  assert.ok(history.includes("AES/GCM/NoPadding"));
  assert.match(history, /maxTurns = 120/);
  assert.match(history, /One-time migration/);
  assert.match(history, /fun recentTurns/);
});

test('Code Studio AI worker is isolated, review-only, and validates generated patches', () => {
  const worker = readFileSync(new URL('../scripts/code_studio_worker.py', import.meta.url), 'utf8');
  const workflow = readFileSync(new URL('../.github/workflows/code-studio-ai-worker.yml', import.meta.url), 'utf8');
  assert.match(worker, /GEMINI_API_KEY/);
  assert.match(worker, /git", "apply", "--check/);
  assert.match(worker, /NO_SAFE_PATCH/);
  assert.match(worker, /\.github\/workflows\//);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /permissions:\s+contents: read/);
  assert.match(workflow, /persist-credentials: false/);
  assert.match(workflow, /node --test/);
  assert.match(workflow, /gradle assembleDebug/);
  assert.match(workflow, /upload-artifact@v4/);
  assert.doesNotMatch(workflow, /contents: write|git push|pull-requests: write/);
});


test('Code Studio AI worker sends and patches only allowlisted Android app source', () => {
  const worker = readFileSync(new URL('../scripts/code_studio_worker.py', import.meta.url), 'utf8');
  assert.match(worker, /SAFE_ROOTS = \("app\\/src\\/main\\/java\\/ai\\/xyaa\\/assistant\\/", "app\\/src\\/main\\/res\\/"\)/);
  assert.match(worker, /SAFE_SUFFIXES = \{".kt", ".xml"\}/);
  assert.match(worker, /SECRET_NAME/);
  assert.match(worker, /Patch touches a path outside the Android source allowlist/);
  assert.match(worker, /ALLOWLISTED ANDROID SOURCE FILES/);
  assert.doesNotMatch(worker, /\\.github\\/workflows\\//);
  assert.doesNotMatch(worker, /p\\.suffix not in allowed/);
});
