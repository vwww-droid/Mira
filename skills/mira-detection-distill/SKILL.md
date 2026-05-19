---
name: mira-detection-distill
description: Route Mira detection findings into a reusable knowledge pipeline. Use when Codex needs to turn a new detection clue, risk-environment observation, or research note into topic confirmation, case capture, topic maintenance, and article update suggestions inside the Mira repository.
---

# Mira Detection Distill

## Overview

Use this skill as the entry point for Mira detection knowledge work.
Do not predefine topic trees.
Start from the user's current clue, object, phenomenon, or draft note.
Always confirm topic dynamically before creating or updating topic assets.

## Core Contract

Treat the pipeline as four layers:

1. `case` for one concrete detection record.
2. `topic` for one evolving research theme.
3. `pattern` for reusable judgment distilled from multiple cases.
4. `article` for English-first publication drafts and Chinese adaptations.

Do not merge these layers into one artifact.
Do not write article prose into case files.
Do not turn skill instructions into article text.

## Workflow

### 1. Triage the request

Classify the current task as one of:

1. new case capture.
2. existing topic update.
3. article update suggestion.
4. mixed request spanning multiple layers.

### 2. Confirm topic dynamically

Never invent a permanent topic silently.
Ask for topic confirmation using the minimum needed prompt.
Base the suggestion on the current material only.

When the user has not named a topic, provide 1 to 3 candidate topic directions:

1. the most likely topic suggestion.
2. why it fits.
3. how it differs from adjacent directions.
4. whether creating a new topic is justified.

Wait for user confirmation before creating or updating `knowledge/topics/<topic-slug>/`.

### 3. Route to the correct execution skill

Use:

1. `$mira-case-capture` for one concrete case.
2. `$mira-topic-maintainer` after topic confirmation.
3. `$mira-article-updater` when the user wants article updates, or when enough new material may justify article changes.

### 4. Produce explicit next actions

Always end with the smallest useful next step.
Examples:

1. confirm topic.
2. capture case only.
3. update topic and backlog.
4. suggest article delta only.

## Output Rules

Keep outputs separate by path:

1. `knowledge/cases/YYYY/...` for raw case records.
2. `knowledge/topics/<topic-slug>/...` for topic assets.
3. `knowledge/articles/en/<topic-slug>.md` for English-first drafts.
4. `knowledge/articles/zh/<topic-slug>.md` for Chinese adaptations.
5. reusable scripts under the closest existing tooling path, such as `tools/android/...`, and link them from cases or topics instead of duplicating them in article prose.

When context is incomplete, do not emit a fake template.
Instead, state what is missing and preserve the pipeline state.

## Script Artifact Routing

When a detection finding includes a reusable shell script or command harness:

1. create or update the script artifact in the appropriate tools path.
2. capture execution semantics in the case, including how to run it inside Mira.
3. link the script from any topic pattern that depends on it.
4. keep articles focused on method and boundaries, not full script dumps, unless the article is explicitly script-centered.
5. preserve known-bad invocation forms and parameter pitfalls as case evidence.

## Dynamic Topic Protocol

Use this short protocol whenever topic is unclear:

1. state the current signal in one sentence.
2. propose up to 3 topic directions.
3. explain each direction with one discriminator.
4. ask the user to confirm or rename the topic.

## Quality Bar

Before finishing, verify:

1. topic was not predefined without confirmation.
2. case, topic, pattern, and article responsibilities stayed separate.
3. at least one reusable judgment or next-check idea was surfaced.
4. the response kept the English-first publication strategy in mind.
