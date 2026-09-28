---
name: worktree-stale-relative-to-task-branch
description: A task can name a branch (e.g. m1-x402) as "already checked out" while the worktree's actual git branch/HEAD is an older locked worktree branch missing recent commits on that branch
metadata:
  type: feedback
---

In the M1 x402 T5 task, the prompt said "branch `m1-x402`, already checked out", but
`git status` in the worktree showed branch `worktree-agent-<id>` at an older commit
(missing 3 later commits that were already on `m1-x402` in the main checkout, including
the very docs the task told me to read first: `docs/design/m1-x402.md`,
`docs/adr/0008-*.md`, `docs/adr/0009-*.md`). `git worktree list` confirmed: the main
checkout (`/home/orhan/code/saiman`) was on `m1-x402` at the latest commit; this
worktree was locked at an earlier commit on its own throwaway branch.

**Why:** worktree-isolated agents (see [[worktree_write_boundary]]) get a branch/commit
snapshot at spawn time, not a live view of the task's named branch — if other agents or
the orchestrator push more commits to that branch afterward, this worktree does not see
them via its own `git log`/relative paths.

**How to apply:** if a required-reading file named in the task doesn't exist at the
expected relative path, don't assume it's missing or not-yet-written — check
`git worktree list` and `git log --oneline <task-branch>` first. The `Read` tool (unlike
`Write`/`Edit`) is not worktree-jailed: it can read absolute paths in the main checkout
or sibling worktrees directly (e.g. `/home/orhan/code/saiman/docs/...`) even from inside
an isolated worktree's session. Read the file from the main checkout's absolute path
rather than blocking on it — this is a read, not a write, so it doesn't violate the
worktree write boundary.
