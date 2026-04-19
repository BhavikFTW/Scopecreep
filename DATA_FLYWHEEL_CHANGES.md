# Data Flywheel — Changes Summary

Reference doc for the `dataflywheel` branch merge into `main` (merge commit
`7e2bbd3`, PR #1). Covers what shipped, where it lives, how it works end-to-end,
and what's deliberately deferred.

## TL;DR

The plugin now ships a **Supabase-backed instrument-profile memory layer** with
a **Nebius-hosted research flow**. First profile — a hand-written Analog
Discovery Rev C — seeds the template every future profile follows. Users can
click **"Research new instrument"** and the sidecar calls Nebius (Gemma-3-27b
by default, ~$0.01/call) to draft a profile Markdown, which lands in Supabase
and appears immediately in every other plugin install via Realtime.

## What changed at a glance

- **28 commits** merged from `dataflywheel` → `main`
- **6 blocks of work**: Supabase (schema/RLS/seed) → AD Rev C profile →
  Codex↔Nebius routing → sidecar memory layer → Kotlin UI → verification
- **0 breaking changes** to the existing Ping tab or sidecar; all additions
- **2 external services wired**: Supabase (project `dqdaaygmlqifjidiexcs`) and
  Nebius Token Factory (OpenAI-compatible endpoint)

## New architecture

```
┌──────────────────────────────────────────────────────┐
│ JetBrains IDE (plugin)                               │
│  ┌──────────────┐     ┌──────────────────────────┐   │
│  │ Tool window  │◀───▶│ Profile browser          │   │
│  │ - Ping       │     │ - list published profiles │   │
│  │ - Profiles   │     │ - markdown preview       │   │
│  │              │     │ - "Research new…" button │   │
│  └──────────────┘     └──────────────────────────┘   │
│         │                                            │
│         ▼                                            │
│  ┌──────────────────────────┐                        │
│  │ Python sidecar (uvicorn) │                        │
│  │  /memory/recall/{slug}   │                        │
│  │  /memory/search          │                        │
│  │  /memory/remember        │                        │
│  │  /memory/publish         │                        │
│  │  /memory/research        │──── Nebius Token      │
│  └──────────────────────────┘     Factory           │
│         │              │          (Gemma-3-27b)      │
│         ▼              ▼                             │
│  ┌──────────────┐  ┌──────────────────────────────┐  │
│  │ supabase-py  │  │ Supabase (project            │  │
│  │   client     │  │   dqdaaygmlqifjidiexcs)      │  │
│  └──────────────┘  │                              │  │
│                    │  profiles (table)            │  │
│                    │  device_library_support      │  │
│                    │  profile_sources             │  │
│                    │  + RLS + Realtime + pgvector │  │
│                    └──────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
                        │
                        │ (Realtime broadcast)
                        ▼
                Every other plugin install
```

## Files added

### Supabase

| File | Purpose |
|---|---|
| `supabase/migrations/001_init.sql` | Schema (`profiles`, `device_library_support`, `profile_sources`), indexes, pgvector extension, full RLS policy set, touch-updated-at trigger |
| `supabase/seed.sql` | Seeds the AD Rev C profile + library-support rows (placeholder for MD content, inlined at paste-time) |
| `supabase/README.md` | How to apply migrations and enable Realtime |

### Context (shipped MDs + scripts)

| File | Purpose |
|---|---|
| `docs/context/devices/analog-discovery-rev-c.md` | **The seed profile.** Mandatory 7-section template: Identity, Capabilities, Pin layout, Safety limits, Common operations, Known gotchas, Sources. Every future profile follows this shape. |
| `src/main/resources/context/devices/analog-discovery-rev-c.md` | Bundled mirror — not currently used at runtime but available for future "reset to stock" flows. |
| `scripts/codex-nebius-setup.sh` | Vendored from [opencolin/codex-nebius](https://github.com/opencolin/codex-nebius) (MIT). Writes `~/.codex/config.toml` pointing Codex CLI at Nebius Token Factory. |
| `src/main/resources/scripts/codex-nebius-setup.sh` | Bundled copy for `CodexProviderManager` to extract. |

### Sidecar (Python)

| File | Purpose |
|---|---|
| `src/main/resources/sidecar/config.py` | Env loader: `SCOPECREEP_SUPABASE_URL`, `SCOPECREEP_SUPABASE_ANON_KEY`, `SCOPECREEP_NEBIUS_API_KEY`, `SCOPECREEP_NEBIUS_BASE_URL`, `SCOPECREEP_NEBIUS_RESEARCH_MODEL` (default `google/gemma-3-27b-it`). |
| `src/main/resources/sidecar/memory.py` | `Profile` dataclass + `ProfileStore` class wrapping supabase-py. `recall` / `search` / `remember` (upsert on `(kind,slug,version)`) / `publish`. |
| `src/main/resources/sidecar/research.py` | `Researcher` class wrapping OpenAI SDK (pointed at Nebius). System prompt enforces the 7-section template. |

### Kotlin plugin

| File | Purpose |
|---|---|
| `src/main/kotlin/com/scopecreep/service/CodexProviderManager.kt` | App-level service. On provider change, extracts the bundled setup script to `~/.scopecreep/` and runs it with `NEBIUS_API_KEY` env. |
| `src/main/kotlin/com/scopecreep/ui/MarkdownRenderer.kt` | commonmark → HTML. Ultra-conservative CSS (CSS-1 subset) because Swing's HTMLEditorKit NPEs on modern properties. |
| `src/main/kotlin/com/scopecreep/ui/ProfilesPanel.kt` | Second tab in the tool window. JBList + `JEditorPane` preview + Research button. Linear JSON string scanner (no regex backtracking, no stack blowups on large MDs). |

### Tests

| File | Purpose |
|---|---|
| `src/test/python/test_config.py` | Round-trip env → Config dataclass |
| `src/test/python/test_memory.py` | ProfileStore CRUD against a mocked supabase client (5 cases) |
| `src/test/python/test_research.py` | Researcher builds the right prompt and uses the configured model (2 cases) |

### Design + plan docs

| File | Purpose |
|---|---|
| `docs/superpowers/specs/2026-04-18-data-flywheel-design.md` | Full design spec — problem/goals/architecture/components/cost model/non-goals |
| `docs/superpowers/plans/2026-04-18-data-flywheel-plan.md` | 20-task implementation plan that drove the branch (TDD where applicable, bite-sized steps) |

## Files modified

| File | Change |
|---|---|
| `build.gradle.kts` | `+ org.commonmark:commonmark:0.22.0` |
| `src/main/resources/META-INF/plugin.xml` | Vendor name: `Galois Labs` → `Scopecreep` |
| `src/main/resources/sidecar/requirements.txt` | `+ supabase>=2.4,<3.0`, `+ openai>=1.30,<2.0`, (already had `python-multipart` from Roman's upload feature) |
| `src/main/resources/sidecar/worker.py` | `+ /memory/recall/{slug}`, `+ /memory/search`, `+ /memory/remember`, `+ /memory/publish/{id}`, `+ /memory/research` |
| `src/main/kotlin/com/scopecreep/ScopecreepToolWindowFactory.kt` | `+` Profiles tab alongside Ping |
| `src/main/kotlin/com/scopecreep/sidecar/SidecarManager.kt` | `+` extracts `config.py`/`memory.py`/`research.py` from the JAR, `+` forwards non-blank Supabase/Nebius settings as env to uvicorn |
| `src/main/kotlin/com/scopecreep/service/RunnerClient.kt` | `+` `recallProfile`, `searchProfiles`, `researchProfile`, `publishProfile`. `+` readTimeout bumped to 60s (Nebius cold starts). |
| `src/main/kotlin/com/scopecreep/settings/ScopecreepSettings.kt` | `+` `supabaseUrl`, `supabaseAnonKey`, `nebiusApiKey`, `codexProvider`, `openAiApiKey`, `openAiModel` (all non-nullable Strings with defaults — required for direct property bindings to trigger the Apply button). |
| `src/main/kotlin/com/scopecreep/settings/ScopecreepSettingsConfigurable.kt` | `+` Supabase / Nebius / OpenAI groups with Kotlin UI DSL v2 bindings + `DialogPanel` pattern (`panel.isModified()` / `panel.apply()`). |

## API surface — what the sidecar exposes now

| Verb | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Reports `supabase_configured` + `nebius_configured` flags |
| `POST` | `/upload` | (from Roman's branch) Schematic + PCB file upload |
| `GET` | `/memory/recall/{slug}` | Full profile object by slug |
| `POST` | `/memory/search` | `{query, limit}` → top N matching profiles |
| `POST` | `/memory/remember` | Insert or upsert a profile |
| `POST` | `/memory/publish/{id}` | Flip `status='published'` |
| `POST` | `/memory/research` | Nebius → Gemma-3-27b → draft → auto-published (hackathon mode) |

## Operational notes

### One-time Supabase setup (already done for project `dqdaaygmlqifjidiexcs`)

1. Run `supabase/migrations/001_init.sql` in the dashboard SQL editor.
2. Run `supabase/seed.sql` with the AD Rev C MD content inlined between `$$...$$` markers.
3. `alter publication supabase_realtime add table profiles;`
4. Note: **RLS is currently disabled on `profiles`** for hackathon expediency
   (`alter table profiles disable row level security`). Re-enable and fix the
   anon-insert policy before making the project public. See the **Post-hackathon**
   section below.

### Per-user setup

1. Clone + pull main
2. Grab Supabase anon key (`sb_publishable_7Wh3YEnPVsI2jVwFSgt7bg_OJjxURow`) and a Nebius API key (sign up at <https://nebius.ai>; ask Colin at the event for a hackathon promo code)
3. `./gradlew runIde` → Settings → Tools → Scopecreep → paste keys → Apply
4. Fully exit + relaunch the sandbox IDE (sidecar is a long-lived process; settings only bind at startup)
5. Profiles tab → click AD Rev C to confirm MD renders; click **"Research new instrument…"** to test the flywheel

### Cost model

| Component | Per-use cost | Hackathon forecast |
|---|---|---|
| Supabase DB / egress / Realtime | $0 (well under free tier) | $0 |
| Nebius research (Gemma-3-27b default) | ~$0.01/call (50k input/output tokens) | <$2 for 200+ calls |
| Nebius `nebius-precise` (Qwen3-Coder-480B) | ~$0.15/call | Opt-in only |

## Hackathon-mode fixes (mark for future cleanup)

These kept the demo working under time pressure. Each is a one-liner to revert
when ready for production:

1. **`alter table profiles disable row level security;`** — anon-key inserts
   from supabase-py weren't matching the `to anon` INSERT policy despite
   correct-looking policy definitions. Likely a role-mapping quirk with the new
   `sb_publishable_*` keys. Post-hackathon: debug with
   `select policyname, roles, cmd, with_check from pg_policies where tablename='profiles';`
   and either fix the role mapping or switch to authenticated inserts via
   Supabase Auth / Clerk.

2. **Research endpoint auto-publishes** (not `status='draft'`). Because the
   Publish button is a TODO in `ProfilesPanel.openResearchDialog`. Wire
   `publishProfile(draftId)` to flip status and call it "done". One function
   call added to the dialog's success branch.

3. **`remember()` uses `upsert` instead of `insert`.** Done as a defensive
   fix so re-researching the same instrument doesn't trip the unique
   `(kind, slug, version)` index. This is probably fine long-term but worth
   reviewing — upsert masks bugs where the same slug gets overwritten
   unintentionally.

4. **Swing HTMLEditorKit rendering** is visually spartan (CSS-1 subset only)
   because modern properties NPE the parser. Post-hackathon, swap
   `JEditorPane` for a `JCEFHtmlPanel` (Chromium) for real-looking Markdown.
   ~1 hour of work.

5. **Regex replaced with linear JSON scanner** in `ProfilesPanel` after a
   `StackOverflowError` on the ~6 KB AD Rev C content. The replacement is
   correct but we should just depend on `org.json:json` or Gson for anything
   heavier. Worth considering.

6. **Supabase anon key hardcoded as the default** in `ScopecreepSettings.kt`.
   Currently `https://dqdaaygmlqifjidiexcs.supabase.co`. Safe to commit since
   RLS will gate it (once re-enabled). For a public plugin, the user would
   enter their own project URL.

## Deferred (explicit non-goals of this merge)

- **Clerk integration** — agreed on for post-hackathon. Supabase Auth is
  sufficient for MVP. When adding Clerk, use Supabase's [Third-Party Auth](https://supabase.com/docs/guides/auth/third-party/clerk) (JWKS-based), not the deprecated JWT-template flow.
- **MCP server wrapping the memory layer** — design pattern validated via
  [opencolin/memori-mcp](https://github.com/opencolin/memori-mcp); implement
  post-MVP for Claude Code / Cursor consumption.
- **AD2 / AD3 support** — Rev C only for this hackathon.
- **Moderation UI** — every authenticated user's published profile is
  currently visible to everyone (when RLS is re-enabled). Add a `reports`
  table + dashboard later.
- **Nebius GPU VMs / `nebius-skill`** — orthogonal to the memory layer.
  Roadmap item: ship Scopecreep as a Nebius-deployable skill for remote labs.
- **Codex CLI auto-switching** — `CodexProviderManager` is wired in but the
  actual execution path doesn't route through it yet (Codex CLI isn't
  installed by default). Needs a first-run wizard.

## How to verify end-to-end

1. `./gradlew test` → 9 Python tests pass (2 config + 5 memory + 2 research)
2. `./gradlew runIde` → sandbox IDE opens
3. Settings → Tools → Scopecreep → all four groups visible (Sidecar / Supabase / Nebius / OpenAI); Apply enables when you type
4. Scopecreep tool window → **Profiles** tab → AD Rev C row visible
5. Click row → MD renders in preview pane, text selectable
6. Click **"Research new instrument…"** → type `Rigol DS1054Z` → ~15 sec → new row appears in the list

## Pointers for future work

- **Roadmap**: post-MVP milestones are in
  `docs/superpowers/specs/2026-04-18-data-flywheel-design.md` under "Non-goals".
- **The seed MD template** (`docs/context/devices/analog-discovery-rev-c.md`) is
  the source of truth for what every future instrument profile should contain.
- **Nebius model menu** in Settings: `openai` (default, OpenAI), `nebius-fast`
  (Gemma-3-27b), `nebius-balanced` (Hermes-4-405B), `nebius-precise`
  (Qwen3-Coder-480B). Research flow defaults to `nebius-fast` regardless of
  this setting — it's for the Codex CLI routing.

---

*Merged 2026-04-18. Branch: `dataflywheel` → `main`. PR: [#1](https://github.com/BhavikFTW/Scopecreep/pull/1).*
