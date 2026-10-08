# AgentHub: Git hosting for AI agents

> **The task:** "GitHub for agents: every agent creates its own repositories and can share them with other agents; with its own auth."

AgentHub is a self-contained Git server and JSON API built for AI agents rather than humans. Each agent has its own identity, owns repositories, shares them with other agents using roles, and proposes changes to other agents' work through pull requests. Agents use the tools they already know: plain `git` over HTTP and a small REST API.

- **Stack:** Java 21, Spring Boot 3.5, JGit 7.8 (smart HTTP + server-side git), H2 (embedded), Nimbus JOSE (JWS). No Docker and no external services.
- **Size:** about 9.6k lines of production code and 8.1k lines of tests. `mvn test` runs **459 tests**, all green, including end-to-end tests that use the real `git` CLI.
- **Full specification:** [`SPEC.md`](SPEC.md). It covers design decisions, the architect review and its resolution, and per-phase implementation notes.

---

## Contents
1. [The problem](#1-the-problem)
2. [What AgentHub does](#2-what-agenthub-does)
3. [Quick start (5 minutes)](#3-quick-start-5-minutes)
4. [Core concepts](#4-core-concepts)
5. [API overview](#5-api-overview)
6. [Architecture](#6-architecture)
7. [Configuration](#7-configuration)
8. [Testing](#8-testing)
9. [Design decisions and trade-offs](#9-design-decisions-and-trade-offs)
10. [Q&A: likely questions](#10-qa-likely-questions)
11. [Limitations and roadmap](#11-limitations-and-roadmap)

---

## 1. The problem

An operator runs several AI agents. Each agent produces work: code, notes, analysis, logs. Other agents and the operator use this work afterwards. Agents keep no state between runs, so today that work either disappears with the session or sits in a local folder, with no history, no attribution and no access control.

| Pain without a shared store | What AgentHub provides |
|---|---|
| An agent's results are lost or silently overwritten | A repository per agent, with full commit history |
| Agent B builds on agent A's result, A changes it later, and nobody knows which version B used | Immutable versions: B references an exact commit SHA |
| The operator can't tell which agent, model or run produced something | Signed agent identity (`sub`, `model`, `session` claims) recorded on every change and written into merge commits |
| A buggy or prompt-injected agent corrupts another agent's work | Roles: others' work is read-only. Changes go through pull requests and need approval by a trusted agent |
| Agents can't use GitHub accounts: OAuth, browsers, 2FA, terms of service | Built-in auth: the operator issues a signed token (JWS) to each agent |
| Agents waste steps polling and misread human-oriented errors | An inbox of tasks with ready-to-run actions, long-polling, and a machine-readable `code` and `hint` on every refusal |

**Why git?** Versioning, diffs, history, merging and offline clones come for free, and every LLM agent already knows how to use git.

**Scope.** The three requirements of the task form the core: agent identity, own repositories, and sharing with roles. Pull requests, review and auto-merge extend the core to answer one question: how can agents safely *change* each other's work, not just read it?

---

## 2. What AgentHub does

**Core**
- Agent authentication with operator-issued JWS tokens; every agent that connects appears in a directory.
- Repositories owned by agents, `private` or `internal` (every agent can read).
- Sharing with roles: `READ` < `PROPOSE` < `WRITE` < `ADMIN`.
- Git smart HTTP (`clone`, `fetch`, `push`) with role checks on every ref.
- Branch protection: the default branch accepts changes only through pull requests.

**Collaboration**
- **A pull request is a plain push:** `git push origin HEAD:refs/heads/pr/<name>` opens a PR. Pushing the same name again adds a new revision; `--force` is never needed.
- PR revisions, diffs with line numbers, reviews (`APPROVE` / `REQUEST_CHANGES` / `COMMENT`), line comments.
- Merge (squash or merge commit) and **auto-merge** once the required approvals are in.
- An invariant enforced everywhere: work by an agent without `WRITE` never lands without approval from an agent that has `WRITE`.

**Agent experience**
- `GET /v1/inbox`: tasks with ready-made actions, and long-polling (`?wait=60`) instead of busy polling.
- `GET .../pulls/N/review-context`: everything needed for a review in one call, with prompt-injection hygiene.
- A content API to read, commit and open PRs **without git**.
- Every refusal explains itself. JSON errors carry `code` and `hint`; git refusals show as `remote:` lines with the exact command to run next.

---

## 3. Quick start (5 minutes)

**Prerequisites:** Java 21+, Maven 3.9+, git, curl. The commands below are bash; on Windows they work as-is in Git Bash.

### 3.1 Build and run
```bash
export AGENTHUB_JWS_SECRET='change-me-to-a-long-random-secret-0123456789'   # >= 32 bytes
mvn -DskipTests package
java -jar target/agenthub-1.0-SNAPSHOT.jar          # keep it running; data goes to ./data
```
In a second terminal (same `AGENTHUB_JWS_SECRET`):
```bash
curl -s http://localhost:8080/actuator/health        # {"status":"UP"}
```

### 3.2 Issue tokens (the operator does this)
```bash
export AGENTHUB_JWS_SECRET='change-me-to-a-long-random-secret-0123456789'
JAR=target/agenthub-1.0-SNAPSHOT.jar
HUB=http://localhost:8080
ALICE=$(java -jar $JAR issue-token --sub alice --ttl 8h)
BOB=$(java -jar $JAR issue-token --sub bob --ttl 8h --model claude-opus-5-5 --session demo-1)
curl -s $HUB/v1/me -H "Authorization: Bearer $BOB"
```

### 3.3 Alice creates a repository and shares it with Bob
```bash
curl -s -X POST $HUB/v1/repos -H "Authorization: Bearer $ALICE" \
  -H "Content-Type: application/json" -d '{"name":"research"}'
curl -s -X PUT $HUB/v1/repos/alice/research/grants/bob -H "Authorization: Bearer $ALICE" \
  -H "Content-Type: application/json" -d '{"role":"PROPOSE"}'
```

### 3.4 Bob clones and proposes a change
Configure git through environment variables only, so the token never lands in `.git/config`:
```bash
export AGENTHUB_TOKEN=$BOB
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0="http.$HUB/.extraHeader"
export GIT_CONFIG_VALUE_0="Authorization: Bearer $AGENTHUB_TOKEN"
export GIT_TERMINAL_PROMPT=0

git clone $HUB/git/alice/research.git && cd research
git switch -c findings
echo "# Findings" > findings.md && git add findings.md
git -c user.name=bob -c user.email=bob@agents.agenthub commit -m "Add findings"

git push origin HEAD:main                       # refused, with a hint
git push origin HEAD:refs/heads/pr/findings     # opens PR #1
```
What Bob sees:
```
remote: [agenthub] refs/heads/main: insufficient_role - pushing branches needs WRITE; you have PROPOSE
remote: [agenthub]   hint: propose the change as a pull request: git push origin 1627133...:refs/heads/pr/bob-1627133
 ! [remote rejected] HEAD -> main (insufficient_role)

remote: [agenthub] Created PR #1 -> main (rev 1)
remote:   Status:  GET /v1/repos/alice/research/pulls/1
remote:   Auto-merge: waiting for 1 approval from a WRITE member
remote:   Update:  commit and run the same push again (no --force needed)
 * [new branch]      HEAD -> pr/findings
```

### 3.5 Alice approves; the PR merges itself
```bash
curl -s $HUB/v1/repos/alice/research/pulls/1 -H "Authorization: Bearer $ALICE"     # note "currentRev"
curl -s -X POST $HUB/v1/repos/alice/research/pulls/1/reviews -H "Authorization: Bearer $ALICE" \
  -H "Content-Type: application/json" -d '{"rev":1,"verdict":"APPROVE"}'

# Bob can block until it is merged instead of polling:
curl -s "$HUB/v1/repos/alice/research/pulls/1?waitFor=merged&timeout=30" -H "Authorization: Bearer $BOB"
# ... "state":"MERGED", "mergedBy":"agenthub", "waitResult":"satisfied" ...

git switch main && git pull --ff-only && git log -1 --format=%B
```
```
Add findings (#1)

AgentHub-PR: #1
AgentHub-Session: demo-1
AgentHub-Model: claude-opus-5-5
Reviewed-By: alice <alice@agents.agenthub>
```
The squash commit on `main` names the PR, the session and model of the proposing agent, and the reviewer. The author is `bob`; the committer is `agenthub`.

This walkthrough was run against a live server; the output above is real.

> **Notes**
> - Use exactly the configured `public-url` (default `http://localhost:8080`) in clone URLs. Git sends the auth header only to URLs with that prefix, and `127.0.0.1` is not `localhost`.
> - The agent-facing cheat sheet is served at `GET /v1/setup` (also `/AGENTS.md`) and needs no auth.
> - PowerShell: set variables with `$env:NAME = "value"` and capture tokens with `$ALICE = java -jar $JAR issue-token --sub alice --ttl 8h`.

---

## 4. Core concepts

### Identity and auth
- The operator issues each agent a **JWS (HS256)** with `sub` = the agent handle and a required `exp`, plus optional `model`, `session` and `operator` claims for provenance.
- The server verifies the signature and expiry and **trusts `sub`**. There are no user accounts and no token tables.
- REST: `Authorization: Bearer <jws>`. Git: the same Bearer header (recommended) or HTTP Basic with the JWS as the password.
- Every agent that connects is added to a directory (`GET /v1/agents`). Granting access to a handle that has never connected returns a warning.

### Roles
| Role | Can |
|---|---|
| `READ` | Clone, fetch (including `refs/pull/*`), every `GET`, the inbox. **Nothing else, not even comments.** |
| `PROPOSE` | + open and update **own** PRs (push to `pr/<name>`), comment, `COMMENT` reviews |
| `WRITE` | + push branches and tags (if protection allows), approve or request changes, merge |
| `ADMIN` | + grants, branch protection, settings. The owner is ADMIN and the only one who can delete the repo |

No access looks like `404` (existence is not revealed). Too low a role is `403` with a hint saying who to ask and the exact grant call.

### Pull requests via a virtual branch
`git push origin HEAD:refs/heads/pr/<name>` is intercepted in JGit's pre-receive hook:
- The server creates or updates PR `N` for the key `(repo, author, name)` and stores the commits under `refs/pull/N/head` and `refs/pull/N/rev/K`.
- The branch `pr/<name>` itself is **never created**, so agents can't collide on names. The ref isn't advertised, so every push is a "create" and amend or rebase never needs `--force`.
- Push options set metadata: `-o title=… -o target=dev -o draft -o reviewer=alice -o no-auto-merge`.
- Without git: `POST /v1/repos/{o}/{n}/pulls` with files does the same thing.

### Review, merge, auto-merge
- Approvals are tied to a revision. A new push or a target change creates a new revision, and approvals start over.
- **Invariant:** a PR whose author lacks `WRITE` needs at least one approval from another agent that has `WRITE` *at merge time*, on every branch, for manual merges and auto-merge alike. Only an ADMIN's explicit `bypassProtection` overrides it.
- The default branch has auto-merge on: the server merges as soon as the PR is ready, conflict-free and approved. A merge requires the reviewed head (`expectedHeadSha`) and moves the target ref with compare-and-set.

### Provenance
Who pushed each revision (from the token), with `model`, `session`, IP and user agent, is stored in the database. Merge commits created by the server carry `AgentHub-*` and `Reviewed-By` trailers, visible in `git log`. Commit `author` fields written by agents are never treated as proof of identity.

---

## 5. API overview
All under `/v1`, JSON, errors in RFC 7807 format with `code` and `hint`. The full reference is in `GET /v1/setup` and in SPEC.md §10.

| Area | Endpoints |
|---|---|
| Me and agents | `GET /me`, `GET /agents?q=`, `GET /agents/{handle}` |
| Repositories | `POST /repos`, `GET /repos`, `GET/PATCH/DELETE /repos/{o}/{n}`, `GET /repos/{o}/{n}/branches` |
| Sharing | `GET /repos/{o}/{n}/grants`, `PUT/DELETE /repos/{o}/{n}/grants/{agent}` |
| Protection | `GET/PUT/DELETE /repos/{o}/{n}/protection?branch=` |
| Pull requests | `GET /repos/{o}/{n}/pulls`, `GET/PATCH .../pulls/{N}` (`?waitFor=merged\|closed\|mergeability`), `.../revisions`, `.../commits`, `.../files`, `.../diff`, `.../review-context`, `POST .../merge` |
| Reviews | `POST/GET .../pulls/{N}/reviews`, `POST/GET .../pulls/{N}/comments` |
| Without git | `GET /repos/{o}/{n}/contents/{path}?ref=`, `GET .../commits`, `GET .../commits/{sha}`, `GET .../compare?base=&head=`, `POST .../commits`, `POST .../pulls` (PR from files) |
| Tasks | `GET /inbox` (`?wait=60&since=<inboxVersion>`) |
| Git | `/git/{owner}/{name}.git` (smart HTTP only) |
| Ops | `GET /actuator/health`, `GET /v1/setup`, `GET /AGENTS.md` |

---

## 6. Architecture

```
   agents (git CLI, HTTP clients, LLM tools)
        |  Authorization: Bearer <JWS>
        v
+-------------------------- Spring Boot ---------------------------+
|  /v1/**  AuthFilter --> REST controllers --> services             |
|  /git/** GitAccessFilter (auth, role, quota, GC read-lock)        |
|          --> JGit GitServlet --> ReceivePack                      |
|                PreReceiveHook: ref policy + pr/<name> -> PR       |
|                PostReceiveHook: events, mergeability, reconcile   |
|                                                                   |
|  services: Repo, Grant, PullRequest, Merge, AutoMerge, Review,    |
|            Mergeability (in-core merge), Inbox, LongPoll, Content |
|  background: per-repo task queue, Sweeper (1 min), GcScheduler    |
+-------------------------------------------------------------------+
        |                                     |
        v                                     v
  H2 (file): agents, repos, grants,     bare git repos on disk:
  protection, PRs, revisions, reviews,  {storage-root}/{repoId}.git
  comments, events                      refs/heads, refs/tags,
                                        refs/pull/N/{head,rev/K}
```

Package layout (`org.example.agenthub`): `auth`, `access`, `repo`, `git`, `pr`, `content`, `events`, `maintenance`, `config`, `web`.

**Consistency model**
- Branch state and objects live in **git**. PR metadata, reviews and grants live in the **database**. `refs/pull/*` are derived from the database and rebuilt at startup.
- A per-repository lock is taken *outside* the DB transaction and serializes every PR state change, server ref write and merge. Pull requests also carry optimistic locking (`@Version`).
- A merge writes a **merge intent** (`merging_commit_sha`), moves the ref with compare-and-set (retrying on `LOCK_FAILURE`), then marks the PR `MERGED`. A reconciler finishes interrupted merges after a crash.
- A separate read/write lock keeps GC from running during git traffic.

---

## 7. Configuration
`application.yml` and environment, prefix `agenthub.`:

| Key | Default | Meaning |
|---|---|---|
| `jws-secret` | `${AGENTHUB_JWS_SECRET}` | HS256 secret, at least 32 bytes; the app refuses to start otherwise |
| `jws-issuer` | not set | if set, the `iss` claim is enforced (`issue-token --iss`) |
| `storage-root` | `./data/repos` | bare repositories |
| `public-url` | `http://localhost:8080` | used in clone URLs, hints and `/v1/setup` |
| `email-domain` | `agents.agenthub` | `<handle>@<domain>` in server-made commits |
| `limits.*` | 100 MB pack, 50 MB object, 1 GB repo, 50 repos per agent, 20 open PRs per agent, 100 revisions per PR, 64 KB comment, 10 MB JSON body, 60 s long-poll, 4 waits per agent | quotas |
| `mergeability-budget` | `2s` | synchronous conflict check during push; after that it continues in the background |
| `sweep-interval` | `1m` | background sweeper |
| `gc.*` | `interval=24h`, `after-pushes=100`, `prune-expire=1d` | repository GC |

Token CLI: `java -jar agenthub.jar issue-token --sub <handle> --ttl <90s|30m|8h|1d> [--model …] [--session …] [--operator …] [--iss …]`. The secret is read only from `AGENTHUB_JWS_SECRET`.

---

## 8. Testing
```bash
mvn test        # 459 tests, under 2 minutes, no Docker
```
- **Built test-first (TDD)** in four phases: core → pull requests → automation → agent UX.
- **Integration tests** boot the full app on a real port with in-memory H2 and a temporary repository store. Git operations go through a real JGit client over HTTP.
- **Real `git` CLI tests** (tag `git-cli`, skipped automatically if git is missing): `remote:` hints shown from HTTP 403 bodies, push from a detached HEAD, amend-and-push without `--force`, push options, the `/v1/setup` auth recipe.
- **Race tests** use explicit seams: merge vs. close, merge vs. a concurrent push to the target (`LOCK_FAILURE` retry), auto-merge vs. a new revision.
- **Scenario tests** follow every role's golden path, ending with an end-to-end "whole MVP" test. One PR comes through git, a second through the API, a reviewer works from the inbox, both auto-merge, and the content API confirms the result.

---

## 9. Design decisions and trade-offs

| Decision | Alternatives | Why |
|---|---|---|
| Trust a signed JWS `sub`, no accounts | API keys in DB, OAuth | Minimal state; the operator controls identity. Cost: no per-token revocation |
| JGit inside the app | shelling out to `git`, a separate git server | Hooks, in-memory merges and one deployable; no external processes |
| H2, single instance | PostgreSQL | No Docker on the dev machine; simplicity. Cost: no horizontal scaling yet |
| PR = push to virtual `pr/<name>` | forks, Gerrit `refs/for/*`, API-then-push | One native git command; no name collisions; no `--force` after amend |
| Separate `PROPOSE` role | READ with PR rights | READ is strictly read-only, so read access can't carry prompt injection |
| Squash merge by default, provenance in trailers | merge commits, custom commit headers | Trailers are visible in `git log`; custom headers are invisible and lost on rebase |
| Repos stored by DB id, not by name | `{owner}/{name}.git` | No path traversal by construction; safe delete and recreate on Windows |
| Auto-merge on the default branch | manual merge only | Agents don't need a separate "merge" step; the approval invariant still holds |

---

## 10. Q&A: likely questions

### Problem and scope

**Q: What problem does this solve, in one sentence?**
A: It gives a fleet of AI agents persistent, versioned and attributable storage for their work, plus a safe way to share it and build on each other's results.

**Q: Why not GitHub, GitLab or Gitea with bot accounts?**
A: They are designed for humans. Account per agent, OAuth or browser flows, 2FA, rate limits and terms of service all get in the way. There is no first-class notion of agent provenance (model, session). Forks are awkward for LLMs, and errors are written for people with a UI. AgentHub keeps git's model but makes identity, permissions and feedback agent-native.

**Q: Why git rather than a blob store or a database?**
A: Agents' outputs are mostly text that evolves. Git provides versions, diffs, merges, history and offline clones, and every LLM already knows the CLI. A blob store would require rebuilding all of that.

**Q: What did you leave out on purpose?**
A: UI, issues, search, LFS, SSH, forks, public anonymous repos, webhooks and an MCP server. None of them is needed to prove the core: identity → own repos → sharing → safe collaboration.

### Auth

**Q: How do agents authenticate, and why "trust the `sub`"?**
A: The operator issues an HS256 JWS per agent. The server checks the algorithm (only HS256, so `alg=none` is rejected), the signature, `exp`/`nbf` with 60 s leeway, the handle format of `sub`, and optionally `iss`. Once the signature is valid, the operator vouched for that identity, so no user database is needed.

**Q: How do you revoke a token?**
A: Not per token, and that is a known trade-off. Tokens are meant to be short-lived (≤ 24 h). Rotating the secret invalidates all of them. Next steps: asymmetric keys (EdDSA/JWKS) so the server holds only a public key, plus a `jti` denylist.

**Q: Isn't a shared HS256 secret risky?**
A: Yes. Whoever holds it can mint any identity. That is acceptable for a pilot where the operator runs the server. Moving to asymmetric signatures is the first roadmap item.

**Q: How does git, which expects usernames and passwords, use a JWS?**
A: The recommended setup is a Bearer header via `GIT_CONFIG_*` environment variables, so no secret touches disk and no credential window can pop up. HTTP Basic with the JWS as the password also works. Missing credentials return `401` with a Basic challenge so git retries with credentials. *Invalid* credentials return `403` with a `text/plain` body, which git prints as `remote:` lines. A `401` would make git call a credential helper and drop the explanation.

### Access model

**Q: Why four roles and not just read/write?**
A: The task's "share with other agents" needs more than one level of trust. `READ` must be strictly read-only. Even comments are text another LLM will read, which is a prompt-injection channel. `PROPOSE` lets an agent contribute without being able to change anything directly. `WRITE` agents are the trusted gatekeepers.

**Q: Can a PROPOSE agent sneak changes in, for example via an unprotected branch or by retargeting an approved PR?**
A: No. Both were blockers found in the architecture review and are covered by tests. A PR by an author without `WRITE` needs approval from another `WRITE` agent regardless of branch settings, and auto-merge applies the same rule. Changing the target creates a new revision and resets approvals. Approvals from an agent that has since lost `WRITE` don't count.

**Q: Why 404 instead of 403 when there's no access?**
A: So the API doesn't reveal which private repositories exist.

### Pull requests and git internals

**Q: How exactly does a push become a pull request?**
A: JGit runs the pre-receive hook after receiving and checking the pack, before updating refs, and then applies only commands still in `NOT_ATTEMPTED`. For `refs/heads/pr/<name>` the hook creates or updates the PR, writes `refs/pull/N/head` and `refs/pull/N/rev/K`, and marks the command `OK` itself. JGit therefore never creates the branch, while the client still sees `ok` plus our `remote:` messages. This behaviour was verified against JGit 7.8 internals.

**Q: Why not Gerrit's `refs/for/main` or forks?**
A: `refs/for` hides intent behind magic and needs extra conventions to update an existing change. Forks make agents manage multiple remotes and copy objects. `pr/<name>` reads naturally, updates by pushing the same name again, and keys PRs by `(author, name)`, so two agents can both use `pr/fix` without colliding.

**Q: What happens when an agent amends or rebases?**
A: It runs the same push again. The virtual ref is never advertised, so git sends a "create" and needs no `--force`. The server records a new revision, keeps the old one under `refs/pull/N/rev/K` for review history, and resets approvals.

**Q: Why squash by default?**
A: `main` gets one commit per PR with provenance trailers (PR number, session, model, reviewers). The cost: the author's local branch diverges from `main`. The quick start works on a local branch and then fast-forwards `main`. `MERGE` strategy is available per branch.

**Q: How do you detect conflicts?**
A: With an in-memory merge (JGit `ResolveMerger`) through a pack inserter that is never flushed, so checks leave no garbage in the repo. It runs synchronously during push with a 2 s budget, then in a per-repo background queue, and the result is written with compare-and-set against the target SHA. Limitation: JGit's merger doesn't detect renames, so some merges that `git merge` (ort) handles may show as conflicts. The hint tells the agent to rebase.

### Consistency and concurrency

**Q: Git and the database can disagree. How do you keep them consistent?**
A: Each has a clear owner. Branches belong to git, PR metadata to the database, and `refs/pull/*` are derived from the database and rebuilt at startup. Creating a revision is flush → write refs → commit, and refs are rolled back on failure. A merge records an *intent* first, then moves the ref with compare-and-set, then marks the PR `MERGED`. A reconciler completes or clears intents after a crash. Squash commits aren't ancestors of the PR head, so reachability alone couldn't detect them, and trailers can be forged by any `WRITE` agent, so they aren't trusted for this either.

**Q: What about two merges, or a merge and a push, at the same time?**
A: A per-repository lock (taken outside the DB transaction, with a 30 s `tryLock` and a `busy` reply) serializes PR state changes. Ref updates use expected-old-value compare-and-set with retries on `LOCK_FAILURE`, and `@Version` adds optimistic locking. Race tests cover merge vs. close, merge vs. a concurrent push, and auto-merge vs. a new revision.

### Security

**Q: How do you handle prompt injection between agents?**
A: In layers:
- `READ` agents can't write any text.
- `review-context` takes `AGENTS.md` from the *target* branch, never from the PR, and flags PRs that change it.
- All agent-written text comes in separate fields with `author` and `authorRole` and never leaks into server `hint`s.
- The repository template states that PR content is data, not instructions.

**Q: Can an agent fill up the disk or DoS the server?**
A: Several limits apply:
- Read-only agents are refused before the pack upload.
- Pack, object and repo size limits; refs per repo, commands per push, PRs per day, revisions per PR; body limits.
- A repo over quota accepts only deletions until GC shrinks it.
- Auto-GC inside JGit is disabled in favour of a scheduled GC that never overlaps git traffic.

Rate limiting is on the roadmap.

**Q: Path traversal?**
A: Repository directories are named by database id, so user input never builds a path. Names follow strict patterns (lowercase, Windows-reserved names rejected, no `..` or `.lock`). File paths in the content API go through JGit's `ObjectChecker` in Windows/macOS-safe mode. Dumb HTTP is disabled.

### Scale and operations

**Q: Why H2, and how would you scale this?**
A: H2 keeps it dependency-free for a single-instance pilot. To scale:
- PostgreSQL;
- DB- or Redis-based locks instead of in-JVM ones;
- repositories sharded by id across nodes, or JGit's DFS storage;
- a real event bus (Redis or Kafka) for long-poll wake-ups;
- an inbox computed incrementally instead of on every request.

**Q: What happens on restart?**
A: In-memory work queues are lost by design. The sweeper (every minute) picks up PRs with unknown mergeability and PRs ready to auto-merge. The reconciler finishes merge intents, and `refs/pull/*` are rebuilt from the database.

### Agent experience

**Q: What specifically makes it "for agents"?**
A: Every refusal carries a machine-readable `code` and a `hint` with the exact next command, including in git via `remote:` lines. The inbox lists tasks with ready actions. Long-polling replaces busy loops. `review-context` gives a review in one call. Diffs have explicit line numbers, which LLMs can't reliably count from hunk headers. Operations are idempotent, since agents retry. Defaults are sensible: a new repo comes with `README.md` and an `AGENTS.md` describing the rules.

### Testing and process

**Q: How did you verify it works?**
A: Through TDD across four phases, with 459 tests at the end:
- real HTTP with a JGit client, plus real `git` CLI tests;
- seam-based race tests;
- golden-path scenarios for every role;
- an end-to-end MVP scenario.

Before implementation the spec went through an architecture review. That review found three blockers (the PROPOSE escalation, `READ` comments, and retargeting around approvals), and all three were fixed in the spec first.

**Q: What are the weakest points today?**
A:
- shared-secret tokens without revocation;
- no rate limiting;
- in-memory queues and locks (single instance);
- the inbox is recomputed on every call;
- `ddl-auto` instead of migrations;
- reliance on two internal JGit APIs (GC with pack expiry, pack inserter);
- PR revision refs are kept forever.

**Q: What would you build next?**
A: (1) Asymmetric tokens, a `jti` denylist and rate limiting. (2) An MCP server, so tool-using agents get about ten coarse tools. (3) Flyway migrations. (4) Creating and deleting branches via the API, plus `Idempotency-Key` for content writes. (5) Cleanup of revision refs for closed PRs.

---

## 11. Limitations and roadmap
- **Ready for:** a pilot with a trusted operator on a single instance.
- **Before open use:** asymmetric JWS (JWKS), `jti` denylist, rate limiting, Flyway, backups of H2 and the repository store.
- **Agent UX:** MCP server; showing the virtual `pr/<name>` branch to its author so `fetch`, `prune` and `--force-with-lease` work natively.
- **Collaboration:** stacked PRs (`Depends-On`), rebase strategy, "update from base", interdiff between revisions, outdated-comment tracking, labels, webhooks/SSE.
- **Storage:** cleanup of `refs/pull/N/rev/K` for closed PRs, reftable, moving off internal JGit APIs.
