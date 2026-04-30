---
name: mira-article-updater
description: Update Mira topic articles from cases and patterns. Use when Codex needs to decide whether a confirmed topic has enough new material to update its English-first article, adapt the content for Chinese platforms, and preserve links back to cases and topic assets.
---

# Mira Article Updater

## Overview

Use this skill for publication artifacts only.
Treat English as the primary publication surface.
Treat Chinese as an adaptation for domestic security platforms, not a literal translation target.

## Source Priority

Build article updates from these sources in order:

1. topic definition and patterns.
2. newly linked cases.
3. backlog items that change the article boundary.
4. existing article body and published links.

Do not use raw chat phrasing as final article prose when topic assets already contain better structure.

## Update Decision

Do not rewrite the article automatically every time.
First judge whether new material warrants an article delta.
Suggest one of:

1. no article update yet.
2. add one new section.
3. revise one existing section.
4. refresh both English and Chinese drafts.

Explain the reason briefly.

## English-First Rules

Optimize the English article for:

1. stable terminology.
2. method-oriented framing.
3. searchable titles and section names.
4. explicit boundaries and false-positive discussion.
5. toolchain-driven explanation anchored in Mira.

## Chinese Adaptation Rules

Adapt for domestic readers by:

1. keeping the same core claim.
2. using more direct practical phrasing.
3. preserving method and boundary discussion.
4. avoiding line-by-line translation when a better Chinese structure exists.

## Recommended Article Skeleton

For each topic article, prefer:

1. what this pattern detects.
2. why the signal matters.
3. where it commonly appears.
4. what Mira makes observable.
5. strongest smells and clues.
6. misleading noise.
7. representative cases.
8. distilled judgment pattern.
9. boundaries and evasion paths.
10. next research directions.

## Linking Rules

When updating article files, keep links aligned with:

1. `knowledge/topics/<topic-slug>/TOPIC.md`
2. `knowledge/topics/<topic-slug>/references.md`
3. cited case files in `knowledge/cases/`

## Quality Bar

Before finishing, verify:

1. the English draft is not just translated Chinese.
2. the Chinese version remains an adaptation, not a literal copy.
3. every major claim can be traced back to cases or patterns.
4. the proposed update size matches the amount of new evidence.
