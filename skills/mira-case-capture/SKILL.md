---
name: mira-case-capture
description: Capture Mira detection experiments locally, then distill selected evidence into a tracked case only when the user explicitly requests promotion into a report or the knowledge repository.
---

# Mira Case Capture

## Default: local investigation

Testing, exploring a device, collecting evidence, and asking for a summary do not authorize promotion into the knowledge repository.

Write working notes, command output, screenshots, scripts, failed attempts, and draft reports under `reports/local/<YYYY-MM-DD>-<investigation>/`. Verify this path is Git-ignored before writing. Preserve relative subdirectories when moving an existing investigation so its links remain usable. Do not stage, commit, or push these files.

Do not create files in `knowledge/cases/`, `knowledge/topics/`, `knowledge/articles/`, or maintained `tools/` merely because an experiment is interesting or succeeds. Experimental scripts stay beside local evidence. Ordinary product fixes explicitly requested by the user are separate from this evidence-staging rule.

Capture only what the investigation needs: the tested object, app identity and permission context, commands or script snapshots, exact invocation and parameters, observations, failures, and unresolved questions. Distinguish observations from interpretation. No mandatory bilingual draft or large template during testing. Redact credentials and unnecessary device/instance identifiers.

## Promotion: only selected, authorized material

Promote when the user explicitly asks to distill specified findings into a report, case, or the knowledge repository. Confirmation of a research topic, permission to test, or a request to continue is not promotion authorization. If selected material is clear, proceed without asking again; otherwise keep work local and clarify the scope.

Before writing the tracked case:

1. Select validated, reproducible findings covered by the request.
2. Exclude raw dumps, transient logs, timestamps/instance values, tool chatter, speculative claims, duplicates, and unrelated failures. Include a failure only when it explains a method's reliability or a likely misinterpretation.
3. Keep observations, supported interpretation, and remaining uncertainty distinct. Document false positives and measurement-tool artifacts.
4. Include enough method to reproduce: script path, invocation, environment, parameters, known limitations, and a minimal verification. Promote a maintained script only if requested or necessary for the authorized case. Never embed detector logic into Mira App components.

Write English and Chinese cases under `knowledge/cases/en/YYYY/YYYY-MM-DD-<slug>.md` and `knowledge/cases/zh/YYYY/YYYY-MM-DD-<slug>.md`. Only small, necessary executable snapshots belong in `knowledge/cases/artifacts/YYYY/`; raw investigation evidence remains local. Use the existing tooling directory for an authorized reusable script.

A promoted case should explain the detection object, initial suspicion, topic status, key clues, validation, result, false-positive risks, reusable judgment, remaining checks, and related articles when relevant. Do not invent a confirmed topic or article. Avoid full article prose and empty fields.

## Verification

- Local-only investigation: `git status --short` must not list its working artifacts; `git check-ignore` must confirm the destination.
- Promotion: every tracked finding fits the authorized scope, is useful without the conversation, and links to reproducible methods without requiring unavailable local dumps.
