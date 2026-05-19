---
name: mira-case-capture
description: Capture one Mira detection case as a structured record. Use when Codex needs to turn a concrete finding, experiment, command output, or risk-environment clue into a reusable case with smells, key clues, judgment seeds, and next checks under the Mira knowledge pipeline.
---

# Mira Case Capture

## Overview

Use this skill for one concrete detection record.
Assume topic is either already confirmed, or still only a candidate that must be labeled explicitly.
Focus on evidence, judgment signals, and reusable clues.
Do not drift into full article writing.

## Required Structure

Write each case around these fields:

1. detection object.
2. initial suspicion.
3. topic candidate.
4. confirmed topic.
5. smells.
6. key clues.
7. validation actions.
8. result.
9. false-positive risk.
10. distilled judgment seeds.
11. suggested next checks.
12. linked articles.

## Capture Rules

Separate observation from interpretation.
For every important point, distinguish:

1. what was seen.
2. what it may imply.
3. what still needs verification.

Prefer compact, high-signal records over long chronology.
Keep ephemeral noise out unless it explains a likely misread.

## Shell Script Capture Rules

When a detection case depends on a shell script, command sequence, or Mira PTY execution behavior, the case must capture the script as an executable method, not just as supporting evidence. Include:

1. script artifact path when a reusable script is created.
2. exact invocation model, such as paste into current PTY, source with `. file`, or run through `mira_run_command`.
3. forbidden or misleading invocation forms, such as `sh file` when it changes behavior.
4. tunable parameters and known-good defaults.
5. environment assumptions, including current shell process, PTY state, log buffers, and available applets.
6. observed failure modes caused by chunk size, timing, buffering, rate limits, or command noise.
7. minimal validation command proving the script still works.

Do not bury reusable script behavior only in docs. A future reader should be able to rerun or adapt the script from the case record and linked artifact.

## Smells And Clues

Always try to extract:

1. `smells` as why this feels suspicious.
2. `key clues` as what most sharply increases confidence.
3. `noise or misdirection` as what could waste future time.
4. `judgment seeds` as the reusable pattern fragments not yet mature enough for topic-wide patterns.

## Output Path

Save case files under:

`knowledge/cases/YYYY/YYYY-MM-DD-<object>-<signal-surface>.md`

Use an English slug in the filename.
Keep human-readable Chinese explanation inside the file body.

## Minimal Good Case

A good case should let a future reader answer:

1. what was being detected.
2. why it looked suspicious.
3. what evidence mattered most.
4. what conclusion was actually supported.
5. what should be checked next.

## Quality Bar

Before finishing, verify:

1. the case is useful without article context.
2. the core smells and clues are explicit.
3. the result does not overclaim beyond the evidence.
4. at least one next-check item exists when certainty is incomplete.
