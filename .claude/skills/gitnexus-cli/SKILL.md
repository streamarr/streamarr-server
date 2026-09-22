---
name: gitnexus-cli
description: "Use when the user needs to run GitNexus CLI commands like analyze/index a repo, check status, clean the index, generate a wiki, or list indexed repos. Examples: \"Index this repo\", \"Reanalyze the codebase\", \"Generate a wiki\""
---

# GitNexus CLI Commands

These instructions target GitNexus **1.6.12** and require Node `^22.18.0 || >=24.11.0`. Bun can provide the package runner when npm, npx, or pnpm is unavailable, but the documented CLI and `node .gitnexus/run.cjs` commands still require Node.

Commands below use the generated project-local runner, `node .gitnexus/run.cjs <command>`. Before using it, install `npm install --global gitnexus@1.6.12` and verify `gitnexus --version` reports `1.6.12`. The runner selects a global `gitnexus` first; its automatic package-manager fallbacks can fetch `latest`, so use the pinned alternatives below when the matching global installation is unavailable. Change the documented version deliberately when upgrading these instructions.

> **Runner missing, or no matching global installation?** Replace `node .gitnexus/run.cjs` in any command with `npx gitnexus@1.6.12`, `bunx gitnexus@1.6.12`, or (pnpm 10.2+) `pnpm --allow-build=@ladybugdb/core --allow-build=gitnexus --allow-build=tree-sitter dlx gitnexus@1.6.12`. For first-time setup or deliberate guide regeneration, append `analyze`; for routine refreshes, append `analyze --index-only`. On **npm 11.x**, if `npx` crashes during install (`node.target is null`), use the pinned global install, Bun, or pnpm alternative. See [#1939](https://github.com/abhigyanpatwari/GitNexus/issues/1939).

## Commands

### analyze — Build or refresh the index

```bash
node .gitnexus/run.cjs analyze
```

Run from the project root. This parses all source files, builds the knowledge graph, writes it to `.gitnexus/`, and generates CLAUDE.md / AGENTS.md context files.

| Flag           | Effect                                                           |
| -------------- | ---------------------------------------------------------------- |
| `--index-only` | Refresh the index without rewriting AGENTS.md / CLAUDE.md or skills |
| `--watch`      | Keep a Git repository index current with serialized refreshes    |
| `--debounce <ms>` | Watch quiet period before refresh (default: 300 ms)            |
| `--force`      | Force full re-index even if up to date                           |
| `--embeddings` | Enable embedding generation for semantic search (off by default) |
| `--drop-embeddings` | Drop existing embeddings on rebuild. By default, an `analyze` without `--embeddings` preserves them. |
| `--pdg` | Build the program-dependence layers used by `explain` and `pdg_query` (taint, CDG, and REACHING_DEF). |
| `--spring-actuator <path>` | Import opt-in Spring Boot Actuator mappings, beans, conditions, configprops, and env snapshots. Forces a full rebuild; unsupported with `--watch`. |
| `--asyncapi-spec <path>` | Read opt-in AsyncAPI 3.x documents (directory or single file) and mint `Destination` nodes from their operations. 2.x is refused, not mapped. Unsupported with `--watch`. |

**When to run:** Use full `analyze` for first-time setup or deliberate regeneration of agent guides and skills. After code changes or a stale-index warning, use `analyze --index-only` to preserve tracked guidance. In Claude Code, the PostToolUse hook reports staleness after `git commit` and `git merge`; it does not run analysis itself.

For Spring runtime enrichment, pass a JSON bundle, one endpoint JSON file, or a directory containing endpoint files. Route evidence is authoritative only when `runtimeConfirmed === true`; `runtimeSource` records provenance and may also accompany `handler-conflict`. Env/configprops values are never persisted.

## Index storage and retention

Default location is `<repo>/.gitnexus/`. Override with environment variables (also documented in README):

| Env | Effect |
| --- | ------ |
| `GITNEXUS_STORAGE_PATH` | One complete external index directory. Wins if both storage vars are set. |
| `GITNEXUS_STORAGE_ROOT` | Absolute root; GitNexus creates an isolated `<repo-basename>-<12-hex>/` slot per repository. |
| `GITNEXUS_CONTENT_RETENTION` | `full` (default) keeps file text; `symbol` keeps snippets; `none` keeps the graph only. |

`list_repos`, `gitnexus://repo/{name}/context`, and HTTP `GET /api/repos` / `GET /api/repo` expose `storagePath`, `contentRetention`, and `sourceAvailable`. HTTP `/api/file` and `/api/grep` return 410 unless retention is `full`. MCP `include_content` may still return symbol spans when retention is `symbol`.

Use `node .gitnexus/run.cjs analyze --watch` for a long-lived local Git repository. It performs an initial analysis, queues scanner-admitted file changes, and retries intact failed batches with bounded backoff. Watch refreshes update only the graph: they skip AGENTS.md / CLAUDE.md injection and standard skill installation, so run a one-shot `analyze` when those generated files need updating. Watch rejects one-shot or context-output flags including `--force`, embedding flags, `--skills`, `--default-branch`, `--skip-agents-md`, `--skip-skills`, `--no-stats`, `--self-commit`, `--index-only`, and `--skip-git`. It never pulls remotes. Scheduled remote clone/pull is a different command: `gitnexus auto-sync`. Bare `gitnexus watch` is reserved and does not start either job. Running MCP and `serve` processes periodically check for a published replacement and reopen it without a restart. MCP checks are throttled to once every five seconds, so a tool call before the next check can briefly use the previous index.

### status — Check index freshness

```bash
node .gitnexus/run.cjs status
```

Shows whether the current repo has a GitNexus index, when it was last updated, and symbol/relationship counts. Use this to check if re-indexing is needed.

### clean — Delete the index

```bash
node .gitnexus/run.cjs clean
```

Without `--force`, previews removal of the index and its registry entry. With `--force`, deletes the index and unregisters the repo. Use before re-indexing if the index is corrupt or after removing GitNexus from a project. Check the previewed paths against the authorized scope before applying deletion; existing authorization is sufficient, but cleaning unrelated repositories requires authorization for that wider scope.

| Flag      | Effect                                            |
| --------- | ------------------------------------------------- |
| `--force` | Apply the previewed deletion instead of returning without changes |
| `--all`   | Select all indexed repos; deletion still requires `--force` |

### wiki — Generate documentation from the graph

```bash
node .gitnexus/run.cjs wiki
```

Generates repository documentation from the knowledge graph using an LLM. HTTP providers require an API key (saved to `~/.gitnexus/config.json` on first use). Local CLI providers (`--provider cursor|claude|codex|opencode|grok`) use your existing CLI login.

| Flag                | Effect                                    |
| ------------------- | ----------------------------------------- |
| `--force`           | Force full regeneration, also required to re-generate an existing wiki in a different language |
| `--provider <name>` | LLM provider: minimax, openai, openrouter, azure, custom, cursor, claude, codex, opencode, or grok (default: minimax). Local CLIs (`cursor`, `claude`, `codex`, `opencode`, `grok`) use your existing CLI login and skip `--api-key`. |
| `--model <model>`   | LLM model (default: MiniMax-M3)           |
| `--base-url <url>`  | LLM API base URL                          |
| `--api-key <key>`   | LLM API key                               |
| `--concurrency <n>` | Parallel LLM calls (default: 3)           |
| `--timeout <seconds>` | LLM request timeout in seconds (default: disabled) |
| `--retries <n>`     | Max LLM retry attempts per request (default: 3) |
| `--lang <lang>`     | Output language for generated documentation (e.g. english, chinese, spanish, japanese) |
| `--gist`            | Publish wiki as a public GitHub Gist      |

### list — Show all indexed repos

```bash
node .gitnexus/run.cjs list
```

Lists all repositories registered in `~/.gitnexus/registry.json`. The MCP `list_repos` tool provides the same information.

## After Indexing

1. **Read `gitnexus://repo/{name}/context`** to verify the index loaded
2. Use `gitnexus-exploring`, `gitnexus-debugging`, `gitnexus-impact-analysis`, or `gitnexus-refactoring` for your task

## Troubleshooting

- **"Not inside a git repository"**: Run from a directory inside a git repo
- **Index is stale after re-analyzing**: Wait for the next MCP tool call to reopen the published index; this normally takes no more than five seconds
- **Embeddings slow**: Omit `--embeddings` (it's off by default) or set `OPENAI_API_KEY` for faster API-based embedding
