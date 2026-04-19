# Data Flywheel — Design Spec

**Status:** approved, implementation in progress on `dataflywheel` branch
**Authors:** Bhavik, Claude (via brainstorming)
**Date:** 2026-04-18
**Supabase project:** `dqdaaygmlqifjidiexcs`

## Problem

Scopecreep will drive many hardware instruments over its lifetime. Each
instrument has a unique SDK surface, pinout, and set of quirks. When Codex (the
coding agent in the IDE) generates device-control Python, it needs *accurate*
context about the target device — otherwise it hallucinates APIs and the
generated code fails on real hardware.

Hand-writing instrument profiles for every supported device does not scale. We
need a mechanism that gets better with use: the first user to wire up a new
scope or PSU contributes an instrument profile, and every subsequent user
benefits automatically.

## Goals

1. **High-quality Codex output for the Analog Discovery Rev C today.** The
   first device profile is hand-written and shipped as the seed / template.
2. **Flywheel effect across the community.** Users can research a new
   instrument inside the plugin; Codex-on-Nebius drafts a profile; the user
   reviews and publishes it; every other plugin installation receives the
   profile in real time.
3. **Zero per-person onboarding friction for the Analog Discovery.** No
   Digilent account or WaveForms installer required. Ship with the
   `pyftdi`-based driver from `python/drivers/analog_discovery/` in the
   `jbhack` backend.
4. **Sponsor-aligned architecture.** Memory layer on Supabase (Postgres + RLS
   + Realtime), embeddings and research LLM on Nebius Token Factory.

## Non-goals (deferred)

- Clerk integration — Supabase Auth is sufficient for MVP; revisit when time
  allows.
- Authzed / SpiceDB — overkill for our permission model (owner-of-row is
  solved by RLS).
- Bkey — unidentified as a developer tool; skip until organizers clarify.
- MCP server wrapping the memory layer — implement only if `dataflywheel`
  critical path finishes early (stretch).
- AD2 / AD3 compatibility. Rev C only.
- Moderation dashboard. Every authenticated user's published profile is
  globally visible; anti-abuse can be added post-hackathon.
- WaveForms runtime auto-install. We do not use WaveForms at all for the
  MVP; `pyftdi` + bitstream is the day-1 driver.

## Architecture overview

```
┌──────────────────────────────────────────────────────┐
│ JetBrains IDE (plugin)                               │
│  ┌──────────────┐     ┌──────────────────────────┐   │
│  │ Tool window  │◀───▶│ Profile browser          │   │
│  │ - Ping       │     │ - list published profiles │   │
│  │ - Profiles   │     │ - show MD in JCEF view   │   │
│  │ - Research   │     │ - "Research new…" button │   │
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
│  │ scopecreep.  │  │ Supabase (project            │  │
│  │   memory     │  │   dqdaaygmlqifjidiexcs)      │  │
│  │   client     │  │                              │  │
│  └──────────────┘  │  profiles (table)            │  │
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

Hardware control engine (Analog Discovery) is orthogonal and stays in its own
track; the data flywheel exists to make *Codex output that targets the hardware*
better over time.

## Data model

### `profiles` (the canonical MD store)

One row per Markdown profile — device / library / workflow. Size unbounded
(complex instruments like Keithley 2400 have thousands of SCPI commands).

```sql
create table profiles (
  id          uuid primary key default gen_random_uuid(),
  kind        text not null check (kind in ('device','library','workflow')),
  slug        text not null,
  title       text not null,
  content     text not null,
  status      text not null default 'draft'
                check (status in ('draft','published')),
  version     int  not null default 1,
  author_id   uuid references auth.users,    -- null for system-seeded rows
  embedding   vector(1024),                   -- dim finalized in migration when embedding model is picked
  created_at  timestamptz default now(),
  updated_at  timestamptz default now()
);

create unique index profiles_slug_version on profiles (kind, slug, version);
create index profiles_status_kind on profiles (status, kind);
create index profiles_fts on profiles using gin (to_tsvector('english', content));
```

### `device_library_support` (compatibility matrix)

```sql
create table device_library_support (
  device_slug   text not null,
  library_slug  text not null,
  completeness  int check (completeness between 0 and 100),
  notes         text,
  primary key (device_slug, library_slug)
);
```

Example row: `(device_slug='analog-discovery-rev-c', library_slug='pyftdi', completeness=70, notes='AD Rev C via raw FTDI MPSSE + bundled Spartan-6 bitstream; scope/AWG/PSU/DIO implemented, no protocol decoders.')`.

### `profile_sources` (provenance)

```sql
create table profile_sources (
  id           uuid primary key default gen_random_uuid(),
  profile_id   uuid references profiles(id) on delete cascade,
  url          text not null,
  source_type  text,
  fetched_at   timestamptz,
  confidence   int check (confidence between 1 and 5)
);
```

### RLS policies

```sql
alter table profiles enable row level security;

create policy "anon can read published"
  on profiles for select using (status = 'published');

create policy "author can read own drafts"
  on profiles for select to authenticated
  using (author_id = auth.uid());

create policy "authenticated can insert own rows"
  on profiles for insert to authenticated
  with check (author_id = auth.uid());

create policy "authenticated can update own rows"
  on profiles for update to authenticated
  using (author_id = auth.uid())
  with check (author_id = auth.uid());

create policy "authenticated can delete own rows"
  on profiles for delete to authenticated
  using (author_id = auth.uid());
```

Same pattern applies to `device_library_support` and `profile_sources`.

### Realtime

Enable Realtime publication on `profiles` (Dashboard → Database → Replication).
The plugin subscribes to `INSERT` and `UPDATE` events where
`status = 'published'`, upserting into its local SQLite cache.

## Component design

### Block A — Supabase setup (schema + seed)

**Deliverables**
- `supabase/migrations/001_init.sql` — schema, RLS, indexes, pgvector extension
- `supabase/seed.sql` — bootstrap row for the Analog Discovery Rev C profile
  (content loaded from `docs/context/devices/analog-discovery-rev-c.md`)
- Realtime enabled on `profiles` (manual dashboard toggle, documented in README)
- Public bucket `artifacts` for scope capture PNGs (benchy pattern)

**Env vars exposed to the plugin/sidecar:**
```
SUPABASE_URL=https://dqdaaygmlqifjidiexcs.supabase.co
SUPABASE_ANON_KEY=<publishable>
SUPABASE_SERVICE_KEY=<only on dev server, never bundled in plugin>
```

### Block B — Seed Markdown (`analog-discovery-rev-c.md`)

**Deliverable**: `docs/context/devices/analog-discovery-rev-c.md` (hand-written, sets the template every subsequent profile follows).

**Template sections (mandatory, in order):**
1. **Identity** — vendor, model, board rev, canonical slug.
2. **Capabilities** — scope/AWG/PSU/DIO specs with concrete numbers (sample rate, voltage range, bit depth).
3. **Pin layout** — 2×15 header with wire colors and function per pin.
4. **Safety limits** — max voltages, current limits, 5 V-tolerant pins, known destructive misuses.
5. **Common operations** — idiomatic recipes (scope capture, AWG sine, PSU rails, DIO read/write) with pseudocode pointing at the chosen library surface.
6. **Known gotchas** — bench-earned wisdom. What surprises, what breaks, what to do before connecting hardware.
7. **Sources** — URL + fetch date + confidence 1–5.

Source material:
- Digilent tech reference manual PDF (`digilent.com/reference/_media/analog_discovery:analog_discovery_rm.pdf`)
- Digilent pinout PDF (`.../analog_discovery_pinout.pdf`)
- Benchy's `ANALOG_DISCOVERY_PINOUT.md` (233 lines, already detailed)
- `jbhack/python/drivers/analog_discovery/` source (driver method surface)

This MD is bundled in `src/main/resources/context/devices/` and seeded into
Supabase on first plugin launch if the row does not exist.

### Block C — Codex ↔ Nebius routing (`codex-nebius` pattern)

**Deliverable**: Plugin can optionally route Codex CLI calls to Nebius Token Factory.

- Vendor Colin's `setup-codex-nebius.sh` under `scripts/codex-nebius-setup.sh` (MIT-licensed; attribution in header).
- Plugin setting: `codex.provider` ∈ `{openai, nebius-fast, nebius-balanced, nebius-precise}`; default `openai`.
- On provider change, `CodexProviderManager` runs the setup script against `~/.codex/config.toml`.
- Three Nebius profiles preconfigured:

| Profile | Model | Use |
|---|---|---|
| `nebius-fast` | `google/gemma-3-27b-it` | Default for research / summarization |
| `nebius-balanced` | `nousresearch/Hermes-4-405B` | General-purpose codegen |
| `nebius-precise` | `Qwen/Qwen3-Coder-480B` | Complex codegen (expensive) |

**Budget control**: the `/memory/research` endpoint hard-codes `nebius-fast` by default; precise is only invoked on explicit user request.

### Block D1 — Sidecar memory client

**Deliverable**: Python module + HTTP endpoints in `src/main/resources/sidecar/`.

```python
# memory.py
from supabase import create_client
from dataclasses import dataclass

@dataclass
class Profile:
    id: str | None
    kind: str
    slug: str
    title: str
    content: str
    status: str = "draft"
    version: int = 1

class ProfileStore:
    def __init__(self, url: str, key: str):
        self.sb = create_client(url, key)

    def recall(self, slug: str) -> Profile | None: ...
    def search(self, query: str, limit: int = 5) -> list[Profile]: ...
    def remember(self, profile: Profile) -> str: ...
    def publish(self, profile_id: str) -> None: ...
```

New FastAPI routes in `worker.py`:

| Verb | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/memory/recall/{slug}` | — | `Profile \| 404` |
| `POST` | `/memory/search` | `{query: str, limit?: int}` | `Profile[]` |
| `POST` | `/memory/remember` | `Profile` (without `id`) | `{id, status}` |
| `POST` | `/memory/publish/{id}` | — | `{status: "published"}` |
| `POST` | `/memory/research` | `{instrument_name: str, sources?: str[]}` | `{draft_id: str, title: str}` |

Seed-on-startup: if `profiles` has no row with `slug='analog-discovery-rev-c'`,
the sidecar posts the bundled MD using the **service_role** key (which bypasses
RLS), inserting with `author_id = NULL` to mark it as a system-seeded row. All
subsequent user writes use the anon key + Clerk/Supabase JWT.

### Block D2 (stretch) — MCP server

If time permits after D1 ships:
- Separate Python process `sidecar/mcp_server.py`, stdio transport.
- Two tools mirroring memori-mcp patterns: `recall_profile(slug)`, `remember_profile(profile)`.
- Scoping fields: `entity_id` (user or team), `process_id` (current project).
- Ship `.mcp.json` snippet for Claude Code / Cursor users.

### Block E — Kotlin UI: profile browser + research button

**Deliverables (MVP)**:
- Second tab in the Scopecreep tool window: **"Profiles"**
- JBList of published profiles, populated from `GET /memory/search?q=*`
- JCEF panel renders selected profile as Markdown
- **"Research new instrument"** button → modal dialog → name input → streams
  `POST /memory/research` → shows draft → user can edit → **"Publish"**
- Realtime subscription: new published profiles appear in the list automatically

**Deferred to post-MVP**:
- Per-profile edit UI beyond the research draft
- Profile diff / version comparison UI
- Full moderation controls

## API contracts

### `GET /memory/recall/{slug}`

```json
// 200
{
  "id": "e1b2...",
  "kind": "device",
  "slug": "analog-discovery-rev-c",
  "title": "Digilent Analog Discovery (Rev C)",
  "content": "# Digilent Analog Discovery (Rev C)\n...",
  "status": "published",
  "version": 1,
  "author_id": "system",
  "created_at": "2026-04-18T...",
  "updated_at": "2026-04-18T..."
}
```

### `POST /memory/search`

```json
// request
{"query": "oscilloscope AD", "limit": 5}

// response
[{ ...profile1 }, { ...profile2 }, ...]
```

### `POST /memory/research`

```json
// request
{"instrument_name": "Keithley 2400 SMU"}

// response
{"draft_id": "a1b2...", "title": "Keithley 2400 (SMU)"}
```

Internally: calls Nebius (`nebius-fast` profile) with a prompt template that
fills the seven mandatory section headers. Inserts the result as a draft row
owned by the requesting user. No automatic publication.

## Cost control

| Component | Per-use cost | Monthly cap (hackathon) |
|---|---|---|
| Supabase DB storage | $0 up to 500 MB | 500 MB (comfortable) |
| Supabase egress | $0 up to 2 GB | 2 GB (comfortable) |
| Supabase Realtime | $0 up to 500k msgs | 500k (comfortable) |
| Nebius `/memory/research` | ~$0.01 per research pass (`nebius-fast`, ~50k tokens) | $5 ceiling — 500 research passes |
| Nebius embeddings | ~$0.0001 per profile | negligible |

Total projected spend over the hackathon: **<$2 on Nebius, $0 on Supabase**. The
$25 Supabase credit is untouched even in the worst case.

## Acceptance criteria

A user cloning Scopecreep, running `./gradlew runIde`, and clicking through
the Scopecreep tool window should observe:

1. **Ping tab** behaves as in the MVP.
2. **Profiles tab** shows at least one profile: Analog Discovery Rev C,
   loaded from Supabase (proves the seed + Supabase read path).
3. **Clicking the profile** renders the full MD inline (proves content
   round-trip).
4. **Research new instrument** (with a Nebius API key set in settings) fires
   a Nebius call, returns a draft MD, renders it for review.
5. **Publish button** on the draft flips its status; a teammate running the
   plugin sees the new profile appear in real time (proves Realtime).
6. **Everything works offline** after the first sync — SQLite cache is
   populated and read-through.

## Open questions (non-blocking)

- Where the sidecar gets credentials: `.env` in `~/.scopecreep/` vs. plugin
  settings. For MVP: settings panel fields, persisted via
  `PersistentStateComponent`. A teammate handles CI secret injection later.
- Bitstream licensing for the AD Rev C FPGA image. For the hackathon: leave
  bundled; add a legal-note commit before open-sourcing the repo.

## Implementation sequencing

Dependency order (strict where indicated; otherwise parallelizable):

1. **A** Supabase schema + RLS — blocking for D1, E.
2. **B** Seed MD for AD Rev C — parallelizable with A.
3. **D1** Sidecar memory client + endpoints — needs A.
4. **C** Codex-Nebius setup script + plugin setting — parallelizable with D1.
5. **E** Kotlin profile tab — needs D1.
6. **D2** MCP server — stretch, parallelizable with E once D1 is in.

Tasks are tracked in the companion implementation plan at
`docs/superpowers/plans/2026-04-18-data-flywheel-plan.md` (to be generated
via the `writing-plans` skill immediately after this spec is committed).
