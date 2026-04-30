---
name: mira-topic-maintainer
description: Maintain a Mira detection topic after user confirmation. Use when Codex needs to update topic assets, link cases, distill reusable patterns, track article URLs, or maintain backlog items for an evolving detection theme in the Mira repository.
---

# Mira Topic Maintainer

## Overview

Use this skill only after the user confirms the topic.
Treat a topic as an evolving research theme, not a fixed taxonomy node.
A topic exists to aggregate cases, patterns, backlog, and article links.

## Topic Asset Set

Maintain these files under `knowledge/topics/<topic-slug>/`:

1. `TOPIC.md`
2. `patterns.md`
3. `cases-index.md`
4. `references.md`
5. `backlog.md`

Create only the files actually needed for the current update, but keep the structure consistent over time.

## Responsibilities By File

### `TOPIC.md`

Track:

1. topic name.
2. topic definition.
3. why the topic deserves a series.
4. current coverage.
5. uncovered blind spots.
6. English article link.
7. Chinese article link.

### `patterns.md`

Distill reusable judgment patterns.
For each pattern, capture:

1. pattern name.
2. one-sentence definition.
3. high-discrimination signals.
4. common misreads.
5. scope boundaries.
6. supporting cases.
7. escalation or evasion direction.

### `cases-index.md`

List linked cases with one-line value summaries.
Do not duplicate full case content.

### `references.md`

Track real published article links or external references that help future article updates.
Separate English and Chinese links when both exist.

### `backlog.md`

Track follow-up checks and unanswered questions.
Keep each item actionable and specific.

## Distillation Rules

Promote a judgment from case-level seed to topic-level pattern only when it is reusable beyond one artifact.
Keep uncertain ideas in backlog or as tentative notes instead of forcing them into patterns.

## Linking Rules

Maintain bidirectional traceability whenever possible:

1. topic to cases.
2. topic to articles.
3. cases back to topic.
4. cases back to articles when cited.

## Quality Bar

Before finishing, verify:

1. topic was user-confirmed.
2. patterns are abstractions, not pasted case notes.
3. backlog items remain unanswered research tasks.
4. article links and case links are current.
