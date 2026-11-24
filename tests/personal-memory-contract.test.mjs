import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const store = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/PersonalMemoryStore.kt', import.meta.url), 'utf8');
const activity = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/MainActivity.kt', import.meta.url), 'utf8');
const ui = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/XyaaPremiumUi.kt', import.meta.url), 'utf8');

test('personal memory edits preserve the surviving identity when merging duplicates', () => {
  assert.match(store, /fun update\(id: String, content: String\)/);
  assert.match(store, /items\[duplicate\]\.copy\(category = updated\.category, updatedAt = updated\.updatedAt\)/);
  assert.match(store, /items\.removeAt\(index\)/);
  assert.match(store, /writeUnsafe\(items\)\s+result/);
});

test('personal memory remains opt-in and paused memory cannot be added or edited', () => {
  assert.match(store, /if \(cleaned\.isBlank\(\) \|\| isPaused\(\) \|\| containsCredentialLikeData\(cleaned\)\)/);
  assert.match(store, /fun setPaused\(paused: Boolean\)/);
  assert.match(activity, /rememberMatch/);
  assert.match(activity, /personalMemoryStore\.add/);
  assert.match(ui, /onPausePersonalMemory/);
});

test('personal memory prompt context is relevance-filtered, bounded, and disabled while paused', () => {
  assert.match(store, /fun contextForPrompt\(query: String, limit: Int = 8\)/);
  assert.match(store, /if \(isPaused\(\)\) return@synchronized ""/);
  assert.match(store, /ranked\.filter \{ it\.second > 0 \}\.take\(limit\)/);
  assert.match(store, /selected\.joinToString\("\\n"\)/);
  assert.match(store, /take\(2400\)/);
});

test('personal memory rejects credential-like content before encryption', () => {
  for (const phrase of ['password', 'passcode', 'api key', 'access token', 'refresh token', 'client secret', 'recovery code']) {
    assert.ok(store.includes(phrase), 'missing credential filter: ' + phrase);
  }
  assert.match(store, /PRIVATE KEY/);
  assert.ok(store.includes("AES/GCM/NoPadding"));
  assert.match(store, /AndroidKeyStore/);
});


test('recent conversation history does not persist credential-like user or assistant text', () => {
  const history = readFileSync(new URL('../app/src/main/java/ai/xyaa/assistant/ConversationMemory.kt', import.meta.url), 'utf8');
  assert.match(history, /containsCredentialLikeData\(text\)/);
  assert.ok(history.includes("text.isBlank() || containsCredentialLikeData(text)"));
  assert.match(history, /password.*passcode.*api key.*access token/);
  assert.match(history, /PRIVATE KEY/);
  assert.match(history, /A-Za-z0-9_-\]\{32,\}/);
});

test('memory edit controls are disabled while personal memory is paused', () => {
  assert.match(ui, /IconButton\(onClick=\{editingItem=item;editContent=item\.content\}, enabled=!paused\)/);
  assert.match(ui, /onClick=\{editPersonal\(item\.id,editContent\);editingItem=null\}, enabled=!paused/);
});

test('memory UI discloses provider sharing of relevant local context', () => {
  assert.match(ui, /matching saved facts and recent conversation context may be sent with your prompt/);
  assert.match(ui, /Relevant context may be sent with your prompt to the AI provider/);
  assert.match(ui, /no background cloud sync/);
});

test('personal memory relevance uses exact normalized terms instead of substring collisions', () => {
  // Reuse the module-level source fixture.
  assert.match(store, /private fun memoryTerms\(value: String\)/);
  assert.match(store, /terms\.intersect\(itemTerms\)\.size/);
  assert.match(store, /"favorite", "favorites", "favourite", "favourites" -> "favourite"/);
  assert.match(store, /"color", "colour" -> "color"/);
});


test('unreadable encrypted memory is preserved instead of overwritten by a later write', () => {
  const conversation = read('app/src/main/java/ai/xyaa/assistant/ConversationMemory.kt');
  const personal = read('app/src/main/java/ai/xyaa/assistant/PersonalMemoryStore.kt');
  assert.match(conversation, /getOrElse \{ storageUnreadable = true; emptyList\(\) \}/);
  assert.match(conversation, /if \(storageUnreadable\) return@synchronized/);
  assert.match(conversation, /if \(parsed\.isFailure\)/);
  assert.match(personal, /getOrElse \{ storageUnreadable = true; emptyList\(\) \}/);
  assert.match(personal, /if \(storageUnreadable\) return@synchronized null/);
  assert.match(personal, /storageUnreadable = false/);
});
