# 1. Record architecture decisions

Date: 2026-10-05

## Status

Accepted

## Context

Design decisions are made in discussions and pull requests. Code and documentation say what
bikesched does, rarely why it does not do it differently, so the same alternatives come up again,
and agents working on the code cannot know them.

## Decision

We record significant decisions as Architecture Decision Records in `docs/decisions/`, one
Markdown file per decision, numbered in order (`NNNN-short-title.md`), in the format of Michael
Nygard: Status, Context, Decision, Consequences. Earlier decisions were recorded retroactively.
An accepted ADR is not rewritten; a changed decision gets a new ADR that supersedes it.

## Consequences

- New significant decisions come with an ADR in the same change (see `AGENTS.md`).
- `docs/decisions/README.md` lists all ADRs.
