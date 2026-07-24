---
name: mira-risk-collect
description: Run Mira environment risk collection. Use when the user says /collect, asks to collect or review device environment risks, wants automatic analysis of Android/iOS/Mira runtime signals, or wants to turn fresh Mira observations into reusable risk clues and follow-up checks.
---

# Mira Risk Collect

## Overview

Use this skill to turn the current Mira-connected environment into a compact risk assessment.
Prefer evidence-first collection over broad speculation.
Treat `/collect` as the canonical user shorthand for this workflow.

## Workflow

1. Identify target platform and available connector.
   - Prefer Mira MCP tools when a device is connected.
   - If no device connector is available, inspect local repository state and provide a blocked collection plan.
2. Capture low-risk baseline context.
   - Device identity, OS version, ABI, app package or bundle, current screen, focused app, and Mira install/session state.
   - Frida status only as environment context unless the user asks for deeper runtime instrumentation.
3. Collect risk surfaces relevant to the current platform.
   - Read `references/risk-surfaces.md` before deciding checks.
   - Run only checks that are supported by the available device primitives.
4. Separate observations from interpretation.
   - Use `observed`, `implies`, and `needsVerification` fields in the final answer.
5. Produce a risk summary.
   - Group findings as `confirmed`, `suspected`, `noise`, and `nextChecks`.
   - Include exact commands or Mira tool calls only when they are useful for reproduction.
6. Capture durable cases when a concrete signal is found.
   - Use `mira-case-capture` if a finding should be saved under `knowledge/cases`.
   - Do not create a case for generic absence of risk.

## Output Shape

Return Chinese output with these sections:

1. `采集范围`.
2. `已观察到的信号`.
3. `风险判断`.
4. `噪音与误判可能`.
5. `建议的下一步检查`.
6. `是否值得沉淀为 case`.

Keep each item evidence-backed.
Do not overclaim root, jailbreak, hook, emulator, or tamper status without a supporting signal.

## Tool Guidance

When Mira MCP tools are available:

1. Use device listing or current-device selection first.
2. Use screen state before shell checks when the user is asking about UI behavior.
3. Use shell checks for environment state, filesystem clues, process state, logcat, SELinux, Magisk, emulator, and input-event surfaces.
4. Keep iOS sessions long-lived; avoid frequent PTY reopen.
5. Prefer one batched Android shell script for related checks instead of many tiny commands.

When only the repository is available:

1. Inspect `tools/android`, `tools/ios`, `knowledge/cases`, and relevant docs.
2. Report which checks can be run later on-device.
3. If a new reusable check is obvious, propose or implement it as a script under `tools/android` or `tools/ios`.

## Quality Bar

Before finishing, verify:

1. Every risk has direct evidence or is explicitly marked as a hypothesis.
2. Every unsupported claim has a next verification step.
3. The final answer is short enough to be used as an operator note.
4. Any durable finding is routed toward `mira-case-capture` rather than buried only in chat.
