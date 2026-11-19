#!/usr/bin/env python3
"""Generate and validate a review-only patch for an approved XYAA Code Studio task."""
import json, os, pathlib, subprocess, sys, urllib.request

ROOT = pathlib.Path.cwd()
task = os.environ["CODE_STUDIO_TASK"].strip()
api_key = os.environ["GEMINI_API_KEY"].strip()
model = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
if not task or len(task) > 6000:
    raise SystemExit("Task must contain 1–6000 characters.")
if not api_key:
    raise SystemExit("Missing GEMINI_API_KEY repository secret.")

# Bound the repository context to source/config/docs and avoid binaries/build output.
allowed = {".kt", ".kts", ".java", ".xml", ".gradle", ".properties", ".md", ".mjs", ".js", ".py", ".yml", ".yaml", ".json", ".toml"}
skip = {".git", ".gradle", "build", ".idea", "node_modules", "dist", "target"}
files, total = [], 0
for p in sorted(ROOT.rglob("*")):
    if not p.is_file() or any(part in skip for part in p.parts):
        continue
    if p.suffix not in allowed and p.name not in {"gradlew", "settings.gradle", "build.gradle"}:
        continue
    try:
        body = p.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    if len(body) > 24000:
        body = body[:24000] + "\n[TRUNCATED]"
    entry = f"\n===== FILE: {p.relative_to(ROOT)} =====\n{body}\n"
    if total + len(entry) > 180000:
        break
    files.append(entry); total += len(entry)

prompt = """You are the implementation worker for the XYAA Android/Kotlin repository.
Implement the requested change in the checked-out repository. Return ONLY a unified diff
with paths relative to repository root. Do not use markdown fences or explanations.
Preserve architecture and existing behavior. Do not modify .github/workflows, secrets,
credentials, signing configuration, CI permissions, or deployment settings. Do not add
network exfiltration, telemetry, obfuscated code, destructive commands, or hidden behavior.
Keep changes minimal and production-quality. Add/update tests where practical. If the task
cannot safely be implemented from the supplied source, return exactly NO_SAFE_PATCH.
TASK:
""" + task + "\nREPOSITORY FILES:\n" + "".join(files)

payload = json.dumps({"contents":[{"parts":[{"text":prompt}]}],"generationConfig":{"temperature":0.15,"maxOutputTokens":16000}}).encode()
url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
req = urllib.request.Request(url, data=payload, headers={"Content-Type":"application/json"}, method="POST")
with urllib.request.urlopen(req, timeout=180) as response:
    result = json.loads(response.read())
parts = result.get("candidates", [{}])[0].get("content", {}).get("parts", [])
patch = "".join(x.get("text","") for x in parts).strip()
if patch.startswith("```"):
    patch = patch.split("\n",1)[1].rsplit("```",1)[0].strip()
if patch == "NO_SAFE_PATCH" or not patch:
    raise SystemExit("Worker did not produce a safe patch.")
if not patch.startswith("diff --git "):
    raise SystemExit("Model output was not a valid unified git diff; refusing to apply.")
# Reject paths escaping repo and protected workflow/credential files.
for line in patch.splitlines():
    if line.startswith(("+++ b/", "--- a/")):
        path = line[6:].strip()
        if path == "/dev/null": continue
        candidate = pathlib.PurePosixPath(path)
        if candidate.is_absolute() or ".." in candidate.parts or path.startswith(".github/workflows/"):
            raise SystemExit(f"Patch touches a prohibited path: {path}")
patch_path = ROOT / "code-studio.patch"
patch_path.write_text(patch + "\n", encoding="utf-8")
subprocess.run(["git", "apply", "--check", str(patch_path)], check=True)
subprocess.run(["git", "apply", str(patch_path)], check=True)
print(f"Applied review-only patch ({len(patch)} characters).")
