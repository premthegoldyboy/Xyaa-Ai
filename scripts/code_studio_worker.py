#!/usr/bin/env python3
"""Generate a tightly scoped, review-only patch for an approved XYAA Android task."""
import json
import os
import pathlib
import re
import subprocess
import urllib.request

ROOT = pathlib.Path.cwd()
task = os.environ["CODE_STUDIO_TASK"].strip()
api_key = os.environ["GEMINI_API_KEY"].strip()
model = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")

if not task or len(task) > 6000:
    raise SystemExit("Task must contain 1–6000 characters.")
if not api_key:
    raise SystemExit("Missing GEMINI_API_KEY repository secret.")

# Only application Kotlin and Android resources may enter model context or be patched.
# Build scripts, Gradle properties, local.properties, CI, tests, and credentials stay out.
SAFE_ROOTS = ("app/src/main/java/ai/xyaa/assistant/", "app/src/main/res/")
SAFE_SUFFIXES = {".kt", ".xml"}
SKIP_PARTS = {".git", ".gradle", "build", ".idea", "node_modules", "dist", "target"}
SECRET_NAME = re.compile(r"(local\\.properties|gradle\\.properties|keystore|\\.jks$|\\.keystore$|\\.pem$|\\.p12$)", re.I)
files, total = [], 0
for path in sorted(ROOT.rglob("*")):
    relative = path.relative_to(ROOT).as_posix()
    if not path.is_file() or any(part in SKIP_PARTS for part in path.parts):
        continue
    if SECRET_NAME.search(path.name) or not relative.startswith(SAFE_ROOTS) or path.suffix not in SAFE_SUFFIXES:
        continue
    try:
        body = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    if len(body) > 24000:
        body = body[:24000] + "\\n[TRUNCATED]"
    entry = f"\\n===== FILE: {relative} =====\\n{body}\\n"
    if total + len(entry) > 180000:
        break
    files.append(entry)
    total += len(entry)

if not files:
    raise SystemExit("No allowlisted Android source files were available.")

prompt = """You are the implementation worker for the XYAA Android/Kotlin repository.
Implement the requested change in the checked-out repository. Return ONLY a unified git diff.
You may add or edit files ONLY under app/src/main/java/ai/xyaa/assistant/ or app/src/main/res/.
Do not modify build scripts, dependency manifests, tests, workflows, documentation, credentials,
signing configuration, CI permissions, or deployment settings. Do not add network exfiltration,
telemetry, obfuscated code, destructive commands, or hidden behavior. Preserve architecture.
If the task cannot safely be implemented from supplied source, return exactly NO_SAFE_PATCH.
TASK:
""" + task + "\\nALLOWLISTED ANDROID SOURCE FILES:\\n" + "".join(files)

payload = json.dumps({
    "contents": [{"parts": [{"text": prompt}]}],
    "generationConfig": {"temperature": 0.15, "maxOutputTokens": 16000},
}).encode()
url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
request = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(request, timeout=180) as response:
    result = json.loads(response.read())

parts = result.get("candidates", [{}])[0].get("content", {}).get("parts", [])
patch = "".join(part.get("text", "") for part in parts).strip()
if patch.startswith("```"):
    patch = patch.split("\\n", 1)[1].rsplit("```", 1)[0].strip()
if patch == "NO_SAFE_PATCH" or not patch:
    raise SystemExit("Worker did not produce a safe patch.")
if not patch.startswith("diff --git "):
    raise SystemExit("Model output was not a valid unified git diff; refusing to apply.")

def validate_path(raw_path: str) -> None:
    path = raw_path.strip()
    if path == "/dev/null":
        return
    if path.startswith(("a/", "b/")):
        path = path[2:]
    candidate = pathlib.PurePosixPath(path)
    if candidate.is_absolute() or ".." in candidate.parts or "\\\\" in path:
        raise SystemExit(f"Patch contains an unsafe path: {raw_path}")
    normalized = candidate.as_posix()
    if not normalized.startswith(SAFE_ROOTS) or candidate.suffix not in SAFE_SUFFIXES:
        raise SystemExit(f"Patch touches a path outside the Android source allowlist: {raw_path}")

# Validate both git headers and file headers, including rename/copy metadata.
for line in patch.splitlines():
    if line.startswith("diff --git "):
        match = re.fullmatch(r"diff --git a/(.+) b/(.+)", line)
        if not match:
            raise SystemExit("Patch contains an ambiguous or quoted git path.")
        validate_path("a/" + match.group(1))
        validate_path("b/" + match.group(2))
    elif line.startswith(("+++ ", "--- ")):
        validate_path(line[4:].split("\\t", 1)[0])
    elif line.startswith(("rename from ", "rename to ", "copy from ", "copy to ")):
        validate_path(line.split(" ", 2)[2])

patch_file = ROOT / "code-studio.patch"
patch_file.write_text(patch + "\\n", encoding="utf-8")
subprocess.run(["git", "apply", "--check", str(patch_file)], check=True)
subprocess.run(["git", "apply", str(patch_file)], check=True)
print(f"Applied allowlisted review-only patch ({len(patch)} characters).")
