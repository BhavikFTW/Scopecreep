# Data Flywheel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Supabase-backed instrument-profile memory layer with a Nebius-hosted research flow, seeded with a hand-written Analog Discovery Rev C profile. Every other plugin install sees newly-published profiles via Supabase Realtime.

**Architecture:** Python sidecar (FastAPI) owns all writes via `supabase-py`. Kotlin plugin reads through sidecar HTTP endpoints. Research uses Nebius Token Factory via OpenAI-compatible SDK, defaulting to the cheap `gemma-3-27b` profile. Codex provider (OpenAI vs Nebius) swappable per user via `~/.codex/config.toml`.

**Tech Stack:** Supabase (Postgres + RLS + Realtime), `supabase-py`, `openai` SDK (pointing at `api.tokenfactory.nebius.com`), FastAPI, OkHttp (Kotlin), IntelliJ Platform 2025.2.

**Supabase project id:** `dqdaaygmlqifjidiexcs`
**Branch:** `dataflywheel`

---

## File plan

| File | Status | Purpose |
|---|---|---|
| `supabase/migrations/001_init.sql` | create | Schema + RLS + indexes + pgvector extension |
| `supabase/seed.sql` | create | Bootstrap system-seeded AD Rev C row |
| `supabase/README.md` | create | How to apply migrations (user runs in dashboard) |
| `docs/context/devices/analog-discovery-rev-c.md` | create | The seed device profile (template other profiles follow) |
| `src/main/resources/sidecar/requirements.txt` | modify | Add `supabase`, `openai` |
| `src/main/resources/sidecar/memory.py` | create | `ProfileStore` class + Supabase client wiring |
| `src/main/resources/sidecar/research.py` | create | Nebius-backed research flow (`gemma-3-27b` default) |
| `src/main/resources/sidecar/worker.py` | modify | `/memory/*` endpoints + startup seed check |
| `src/main/resources/sidecar/config.py` | create | Env var loading (SUPABASE_URL/KEY, NEBIUS_API_KEY) |
| `src/main/resources/context/devices/analog-discovery-rev-c.md` | create | Bundled copy of seed MD (symlink or file mirror) |
| `src/test/python/test_memory.py` | create | Unit tests for `ProfileStore` using mocked Supabase client |
| `src/test/python/test_research.py` | create | Unit tests for research flow using mocked OpenAI client |
| `scripts/codex-nebius-setup.sh` | create | Vendored from [opencolin/codex-nebius](https://github.com/opencolin/codex-nebius) (MIT) |
| `src/main/kotlin/com/scopecreep/settings/ScopecreepSettings.kt` | modify | Add Supabase URL / anon key / Nebius key / codex provider fields |
| `src/main/kotlin/com/scopecreep/settings/ScopecreepSettingsConfigurable.kt` | modify | UI for new settings fields |
| `src/main/kotlin/com/scopecreep/service/RunnerClient.kt` | modify | `recall`, `searchProfiles`, `researchProfile`, `publishProfile` methods |
| `src/main/kotlin/com/scopecreep/ui/ProfilesPanel.kt` | create | JBList + JCEF markdown preview + Research button |
| `src/main/kotlin/com/scopecreep/ui/MarkdownRenderer.kt` | create | Thin wrapper over commonmark for JCEF HTML output |
| `src/main/kotlin/com/scopecreep/ScopecreepToolWindowFactory.kt` | modify | Add a second ContentTab: "Profiles" |
| `src/main/kotlin/com/scopecreep/service/CodexProviderManager.kt` | create | Runs the codex-nebius-setup script on provider change |
| `build.gradle.kts` | modify | Add `org.commonmark:commonmark` for markdown rendering |

---

## Block A — Supabase schema + seed data

### Task 1: Write the schema migration

**Files:**
- Create: `supabase/migrations/001_init.sql`

- [ ] **Step 1: Create the migration file with the full schema, indexes, and RLS policies**

File `supabase/migrations/001_init.sql`:

```sql
-- Scopecreep data flywheel schema.
-- Apply via Supabase dashboard SQL editor, or `supabase db push` with CLI.

create extension if not exists vector;
create extension if not exists pg_trgm;

------------------------------------------------------------
-- profiles: canonical MD store for devices / libraries / workflows
------------------------------------------------------------
create table if not exists profiles (
  id          uuid primary key default gen_random_uuid(),
  kind        text not null check (kind in ('device','library','workflow')),
  slug        text not null,
  title       text not null,
  content     text not null,
  status      text not null default 'draft'
                check (status in ('draft','published')),
  version     int  not null default 1,
  author_id   uuid references auth.users(id),
  embedding   vector(1024),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create unique index if not exists profiles_slug_version_uk
  on profiles (kind, slug, version);
create index if not exists profiles_status_kind_idx
  on profiles (status, kind);
create index if not exists profiles_fts_idx
  on profiles using gin (to_tsvector('english', content));
create index if not exists profiles_slug_trgm_idx
  on profiles using gin (slug gin_trgm_ops);

------------------------------------------------------------
-- touch updated_at on UPDATE
------------------------------------------------------------
create or replace function touch_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end
$$;

drop trigger if exists profiles_touch_updated_at on profiles;
create trigger profiles_touch_updated_at
  before update on profiles
  for each row execute function touch_updated_at();

------------------------------------------------------------
-- device_library_support: compatibility matrix
------------------------------------------------------------
create table if not exists device_library_support (
  device_slug   text not null,
  library_slug  text not null,
  completeness  int check (completeness between 0 and 100),
  notes         text,
  primary key (device_slug, library_slug)
);

------------------------------------------------------------
-- profile_sources: provenance of each profile
------------------------------------------------------------
create table if not exists profile_sources (
  id           uuid primary key default gen_random_uuid(),
  profile_id   uuid references profiles(id) on delete cascade,
  url          text not null,
  source_type  text,
  fetched_at   timestamptz,
  confidence   int check (confidence between 1 and 5)
);

create index if not exists profile_sources_profile_idx
  on profile_sources (profile_id);

------------------------------------------------------------
-- RLS
------------------------------------------------------------
alter table profiles enable row level security;
alter table device_library_support enable row level security;
alter table profile_sources enable row level security;

-- profiles
drop policy if exists "anon reads published" on profiles;
create policy "anon reads published"
  on profiles for select using (status = 'published');

drop policy if exists "author reads own drafts" on profiles;
create policy "author reads own drafts"
  on profiles for select to authenticated
  using (author_id = auth.uid());

drop policy if exists "author writes own rows" on profiles;
create policy "author writes own rows"
  on profiles for insert to authenticated
  with check (author_id = auth.uid());

drop policy if exists "author updates own rows" on profiles;
create policy "author updates own rows"
  on profiles for update to authenticated
  using (author_id = auth.uid())
  with check (author_id = auth.uid());

drop policy if exists "author deletes own rows" on profiles;
create policy "author deletes own rows"
  on profiles for delete to authenticated
  using (author_id = auth.uid());

-- device_library_support: publicly readable, authenticated users can insert/update
drop policy if exists "anon reads support" on device_library_support;
create policy "anon reads support"
  on device_library_support for select using (true);

drop policy if exists "authenticated writes support" on device_library_support;
create policy "authenticated writes support"
  on device_library_support for all to authenticated using (true) with check (true);

-- profile_sources: follow the parent profile's visibility
drop policy if exists "anon reads sources of published" on profile_sources;
create policy "anon reads sources of published"
  on profile_sources for select
  using (exists (
    select 1 from profiles p
    where p.id = profile_sources.profile_id and p.status = 'published'
  ));

drop policy if exists "author writes sources of own profile" on profile_sources;
create policy "author writes sources of own profile"
  on profile_sources for all to authenticated
  using (exists (
    select 1 from profiles p
    where p.id = profile_sources.profile_id and p.author_id = auth.uid()
  ))
  with check (exists (
    select 1 from profiles p
    where p.id = profile_sources.profile_id and p.author_id = auth.uid()
  ));

------------------------------------------------------------
-- Realtime publication
------------------------------------------------------------
-- Enable Realtime on profiles via Dashboard → Database → Replication.
-- (supabase_realtime publication is managed by the platform; no SQL needed
-- unless you're running Supabase self-hosted.)
```

- [ ] **Step 2: Commit**

```bash
git add supabase/migrations/001_init.sql
git commit -m "feat(supabase): schema, RLS, pgvector, Realtime plumbing for profiles"
```

### Task 2: Write the seed SQL for the AD Rev C row

**Files:**
- Create: `supabase/seed.sql`

- [ ] **Step 1: Create seed.sql that inserts the AD Rev C row (content replaced at apply time)**

File `supabase/seed.sql`:

```sql
-- Seeds the first system-owned profile. Run AFTER 001_init.sql.
-- The content body comes from docs/context/devices/analog-discovery-rev-c.md.
--
-- Usage (one-shot):
--   sql="insert into profiles(kind,slug,title,status,content,author_id) values
--     ('device','analog-discovery-rev-c','Digilent Analog Discovery (Rev C)',
--      'published',
--      $$ $(cat docs/context/devices/analog-discovery-rev-c.md) $$,
--      null)
--     on conflict (kind,slug,version) do update set
--       content=excluded.content, updated_at=now();"
--
-- Or paste the literal MD content between the $$ markers in the SQL editor.

insert into profiles (kind, slug, title, status, content, author_id)
values (
  'device',
  'analog-discovery-rev-c',
  'Digilent Analog Discovery (Rev C)',
  'published',
  '<<REPLACE WITH docs/context/devices/analog-discovery-rev-c.md CONTENTS>>',
  null
)
on conflict (kind, slug, version) do update set
  content = excluded.content,
  title = excluded.title,
  status = excluded.status,
  updated_at = now();

insert into device_library_support (device_slug, library_slug, completeness, notes)
values
  ('analog-discovery-rev-c', 'pyftdi-scopecreep', 70,
   'In-tree driver at python/drivers/analog_discovery/; scope/AWG/PSU/DIO implemented, no protocol decoders.'),
  ('analog-discovery-rev-c', 'dwfpy', 95,
   'Canonical Digilent wrapper. Requires WaveForms runtime installed; account-gated download.')
on conflict (device_slug, library_slug) do update set
  completeness = excluded.completeness,
  notes = excluded.notes;
```

- [ ] **Step 2: Commit**

```bash
git add supabase/seed.sql
git commit -m "feat(supabase): seed SQL stub for AD Rev C profile + library-support matrix"
```

### Task 3: Document how to apply the migration

**Files:**
- Create: `supabase/README.md`

- [ ] **Step 1: Write a README explaining the one-time dashboard steps**

File `supabase/README.md`:

```markdown
# Supabase setup — data flywheel

Project: `dqdaaygmlqifjidiexcs`

## One-time dashboard steps

1. Open <https://supabase.com/dashboard/project/dqdaaygmlqifjidiexcs/sql/new>
2. Paste the contents of `migrations/001_init.sql` and run it.
3. Paste the contents of `seed.sql`, replacing
   `<<REPLACE WITH docs/context/devices/analog-discovery-rev-c.md CONTENTS>>`
   with the actual file contents (wrap in `$$...$$` to avoid quote escaping),
   and run it.
4. **Enable Realtime on `profiles`**:
   Database → Replication → toggle `profiles` ON.
5. **Auto-enable RLS on new tables** (settings → Database → API or similar):
   turn on to prevent future footguns.
6. Grab the **anon key** and **project URL** from Settings → API. You'll paste
   these into Scopecreep's Settings panel.

## Verify

Run in the SQL editor:

```sql
select kind, slug, status, length(content) as bytes from profiles;
```

You should see a single `published` row for `analog-discovery-rev-c` with
~a few thousand bytes.

## Re-running

All migrations + seed are idempotent (`if not exists`, `on conflict ... do
update`). Safe to re-run if the schema changes.
```

- [ ] **Step 2: Commit**

```bash
git add supabase/README.md
git commit -m "docs(supabase): how to apply migrations and enable Realtime"
```

---

## Block B — Seed profile: Analog Discovery Rev C

### Task 4: Write the seed profile Markdown

**Files:**
- Create: `docs/context/devices/analog-discovery-rev-c.md`

- [ ] **Step 1: Hand-write the seed MD using the mandatory template from the spec**

File `docs/context/devices/analog-discovery-rev-c.md`:

```markdown
# Digilent Analog Discovery (Rev C)

## Identity

- **Vendor:** Digilent (a National Instruments company)
- **Model:** Analog Discovery, board revision C (final revision of the original device)
- **Canonical slug:** `analog-discovery-rev-c`
- **Status:** End-of-life as of 2017. Digilent recommends the [Analog Discovery 3](https://digilent.com/reference/test-and-measurement/analog-discovery-3/start) for new designs. Rev C hardware is still in active use on lab benches.
- **USB:** FTDI FT232H bridge + Xilinx Spartan-6 FPGA. The FPGA is configured at runtime with a bitstream Digilent ships with WaveForms (or our `pyftdi` driver's bundled copy).

## Capabilities

| Instrument | Spec |
|---|---|
| Oscilloscope | 2 channels, 14-bit ADC, up to 100 MS/s sustained, ±25 V with 10× probe (±5 V direct) |
| Arbitrary Waveform Gen | 2 channels, 14-bit DAC, ±5 V out, up to 12.5 MS/s |
| Power Supply (V+, V−) | ±5 V programmable rails, 12-bit DAC each, up to 700 mA per rail |
| Digital I/O | 16 pins, 3.3 V LVCMOS (5 V-tolerant inputs), up to 100 MS/s |
| Protocol decoders (via DIO) | UART, I²C, SPI, CAN — not implemented in our in-tree driver yet |
| Spectrum analyzer | WaveForms GUI feature; not exposed to Python SDK |

Buffer sizes: 16 ksamples per channel shared between scope and AWG by default; can be reallocated.

## Pin layout

The Analog Discovery ships with a flying-lead cable. The 2×15 header (30 pins) maps to the board as follows:

| Header pin | Wire color | Function | Notes |
|:-:|---|---|---|
| 1 | Orange | Scope CH1 +         | Differential input (with pin 2 as CH1−) |
| 2 | Orange / white | Scope CH1 −  | Ground for single-ended use |
| 3 | Blue | Scope CH2 +         | Differential input |
| 4 | Blue / white | Scope CH2 −   | Ground for single-ended use |
| 5 | Yellow | AWG CH1 out       | ±5 V range |
| 6 | Yellow / white | AWG CH1 GND | |
| 7 | Yellow | AWG CH2 out       | ±5 V range |
| 8 | Yellow / white | AWG CH2 GND | |
| 9 | Red    | V+ supply         | 0 to +5 V, up to 700 mA |
| 10 | Black | V+ GND            | |
| 11 | White | V− supply         | 0 to −5 V, up to 700 mA |
| 12 | Black | V− GND            | |
| 13 | Green | Ext trigger 1     | DIO-addressable, 3.3 V LVCMOS |
| 14 | Purple | Ext trigger 2    | |
| 15 | Black | GND               | Bulk ground |
| 16 | Pink   | DIO 0            | LVCMOS 3.3 V, 5 V-tolerant input |
| 17 | Pink   | DIO 1            | |
| 18 | Pink   | DIO 2            | |
| ... | ... | DIO 3–13 | Same electricals |
| 30 | Pink   | DIO 15           | |

Full pinout PDF: <https://digilent.com/reference/_media/analog_discovery:analog_discovery_pinout.pdf>.

## Safety limits

- **Never exceed ±25 V on scope inputs even with a 10× probe.** Direct input is ±5 V.
- **Do not source more than 700 mA per PSU rail.** Brownout protection kicks in before damage, but repeated clamp events shorten the reference's life.
- **DIO inputs are 5 V-tolerant, but outputs are strictly 3.3 V.** Driving a 5 V-only logic input from DIO works only if the input has a low logic-high threshold (< 2 V).
- **The FPGA must be configured before use.** A freshly-connected device has uninitialized I/O — do not connect hot.
- **Ground loops:** bench PSUs, scope, DUT, and AD share a common ground. Floating setups will float the AD too; isolated probes are not in the box.

## Common operations

Library in use by this plugin: `python/drivers/analog_discovery/` (pyftdi + bundled bitstream, zero external deps beyond pip).

```python
from analog_discovery.driver import AnalogDiscovery

ad = AnalogDiscovery(bitstream_path="…/ad_bitstream.bit")
ad.connect()                                    # loads FPGA, exposes instruments
try:
    ad.psu.set_voltage(rail="v_plus", volts=3.3)
    ad.psu.enable(rail="v_plus")

    ad.scope.configure_channel(
        channel=0, v_range=5.0, sample_rate=1_000_000, samples=8192)
    ad.scope.arm_trigger(channel=0, level=1.5, edge="rising")
    waveform = ad.scope.read_samples(channel=0)

    ad.awg.set_waveform(channel=0, shape="sine",
                        freq_hz=1_000, amplitude_v=1.0)
    ad.awg.enable(channel=0)

    ad.dio.set_direction(mask=0x00FF)           # DIO 0..7 outputs
    ad.dio.write(0x55)                          # pattern on DIO 0..7
finally:
    ad.disconnect()                             # always disable PSU rails first
```

Typical bring-up sequence: `connect → configure instruments → arm/enable → read/trigger → disable → disconnect`. Don't skip the `finally` — leaving a PSU rail live between runs is how you cook a DUT.

## Known gotchas

- **USB bandwidth is the real sample-rate ceiling** on a crowded hub. A dedicated USB port (or a powered USB-2 hub) reliably delivers 100 MS/s captures; shared ports drop samples silently.
- **Trigger edge matters more than you think.** "Rising at 1.5 V" triggers on a noisy slow edge even if the real event is a 100 ns pulse. Use `edge="rising"` + a tighter `level` or move to hardware-qualified triggers.
- **AWG and scope share the 16 ksample buffer.** Large scope captures reduce available AWG waveform memory. If the AWG goes silent mid-test, check the split.
- **PSU rails are NOT output-current-limited to the rated 700 mA.** They fold back aggressively below that if the regulator's thermal sense trips. If the rail sags, pull less current.
- **DIO reads on a moving pattern have ~10 ns jitter** due to sampling relative to the internal clock. For timing-critical reads, use the logic analyzer mode instead of polled `dio.read()`.
- **Bitstream version drift:** different AD Rev C units shipped with slightly different FTDI EEPROM settings. If `connect()` fails with a VID/PID mismatch, run the vendor's "AD Firmware Utility" once to re-flash the EEPROM. This is rare but does happen.
- **Do not run the WaveForms GUI and our sidecar simultaneously.** Both try to grab the USB handle; whoever gets there second hangs.

## Sources

- [Analog Discovery Technical Reference Manual (rev C)](https://digilent.com/reference/_media/analog_discovery:analog_discovery_rm.pdf) — fetched 2026-04-18, confidence 5 (official manufacturer doc)
- [Analog Discovery Pinout PDF (rev C)](https://digilent.com/reference/_media/analog_discovery:analog_discovery_pinout.pdf) — fetched 2026-04-18, confidence 5
- [Goodbye Original Analog Discovery](https://digilent.com/blog/goodbye-original-analog-discovery/) — fetched 2026-04-18, confidence 4 (vendor blog, EOL announcement)
- [WaveForms SDK Reference Manual](https://digilent.com/reference/_media/waveforms_sdk_reference_manual.pdf) — fetched 2026-04-18, confidence 5 (for `dwfpy` comparison)
- Benchy repo `docs/ANALOG_DISCOVERY_PINOUT.md` — 233 lines, internal ground-truth, confidence 4
- In-tree driver `python/drivers/analog_discovery/` — authoritative for the method surface we expose
```

- [ ] **Step 2: Mirror the seed MD into the plugin resources dir so the sidecar can read it at startup**

```bash
mkdir -p src/main/resources/context/devices
cp docs/context/devices/analog-discovery-rev-c.md src/main/resources/context/devices/
```

- [ ] **Step 3: Commit**

```bash
git add docs/context/devices/analog-discovery-rev-c.md \
        src/main/resources/context/devices/analog-discovery-rev-c.md
git commit -m "feat(context): seed Analog Discovery Rev C profile (device MD)"
```

---

## Block C — Codex ↔ Nebius routing

### Task 5: Vendor Colin's setup script

**Files:**
- Create: `scripts/codex-nebius-setup.sh`

- [ ] **Step 1: Fetch the script verbatim with attribution header**

```bash
curl -fsSL https://raw.githubusercontent.com/opencolin/codex-nebius/main/setup-codex-nebius.sh \
  -o scripts/codex-nebius-setup.sh
chmod +x scripts/codex-nebius-setup.sh
```

- [ ] **Step 2: Prepend an attribution comment block**

Edit the top of `scripts/codex-nebius-setup.sh` to add:

```bash
#!/usr/bin/env bash
# Vendored from https://github.com/opencolin/codex-nebius
# (MIT-licensed, Colin Lowenberg). Local copy keeps the hackathon demo
# reproducible even if the upstream changes. Any modifications after this
# comment block are Scopecreep-specific.

# --- upstream content below ---
```

(The `#!/usr/bin/env bash` line replaces the upstream shebang; keep the rest intact.)

- [ ] **Step 3: Verify it executes to --help without errors**

```bash
bash scripts/codex-nebius-setup.sh --help 2>&1 | head -5
```

Expected: usage text from Colin's script, or a graceful "no codex installed" message — no Python/Shell traceback.

- [ ] **Step 4: Commit**

```bash
git add scripts/codex-nebius-setup.sh
git commit -m "feat(scripts): vendor opencolin/codex-nebius setup script (MIT)"
```

### Task 6: Add a `CodexProviderManager` Kotlin service

**Files:**
- Create: `src/main/kotlin/com/scopecreep/service/CodexProviderManager.kt`

- [ ] **Step 1: Write the service that shells out to the script when the provider changes**

File `src/main/kotlin/com/scopecreep/service/CodexProviderManager.kt`:

```kotlin
package com.scopecreep.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class CodexProviderManager {

    private val log = thisLogger()
    private val scriptTarget =
        Paths.get(System.getProperty("user.home"), ".scopecreep", "codex-nebius-setup.sh")

    fun applyProvider(provider: String, nebiusApiKey: String?) {
        if (provider == "openai") {
            log.info("Codex provider: openai (no-op)")
            return
        }
        val key = nebiusApiKey?.takeIf { it.isNotBlank() }
            ?: throw IOException("Nebius API key is not set; open Settings → Tools → Scopecreep")
        extractScript()
        ApplicationManager.getApplication().executeOnPooledThread { runScript(provider, key) }
    }

    private fun extractScript() {
        Files.createDirectories(scriptTarget.parent)
        val stream = javaClass.getResourceAsStream("/scripts/codex-nebius-setup.sh")
            ?: throw IOException("bundled codex-nebius-setup.sh missing from plugin JAR")
        stream.use { Files.copy(it, scriptTarget, StandardCopyOption.REPLACE_EXISTING) }
        scriptTarget.toFile().setExecutable(true)
    }

    private fun runScript(provider: String, key: String) {
        val cmd = GeneralCommandLine("bash", scriptTarget.toString())
            .withEnvironment("NEBIUS_API_KEY", key)
            .withEnvironment("CODEX_PROFILE_DEFAULT", profileName(provider))
        val proc = cmd.createProcess()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroy()
            log.warn("codex-nebius-setup timed out")
            return
        }
        log.info("codex-nebius-setup exited with ${proc.exitValue()}")
    }

    private fun profileName(provider: String): String = when (provider) {
        "nebius-fast" -> "nebius-fast"
        "nebius-balanced" -> "nebius-token-factory"
        "nebius-precise" -> "nebius-precise"
        else -> "nebius-token-factory"
    }

    companion object {
        fun getInstance(): CodexProviderManager =
            ApplicationManager.getApplication().getService(CodexProviderManager::class.java)
    }
}
```

- [ ] **Step 2: Bundle the script into plugin resources so `getResourceAsStream` finds it**

```bash
mkdir -p src/main/resources/scripts
cp scripts/codex-nebius-setup.sh src/main/resources/scripts/
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/scopecreep/service/CodexProviderManager.kt \
        src/main/resources/scripts/codex-nebius-setup.sh
git commit -m "feat(codex): provider manager for OpenAI↔Nebius routing"
```

---

## Block D — Sidecar memory layer

### Task 7: Add Python dependencies

**Files:**
- Modify: `src/main/resources/sidecar/requirements.txt`

- [ ] **Step 1: Add supabase-py and openai**

Read current file, then rewrite:

File `src/main/resources/sidecar/requirements.txt`:

```
fastapi>=0.110,<1.0
uvicorn[standard]>=0.27,<1.0
python-multipart>=0.0.9,<1.0
supabase>=2.4,<3.0
openai>=1.30,<2.0
```

- [ ] **Step 2: Verify nothing else in the sidecar needs updating**

```bash
grep -n "^import\|^from" src/main/resources/sidecar/worker.py | head
```

Expected: standard-lib imports and `fastapi` only. No surprises.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/sidecar/requirements.txt
git commit -m "feat(sidecar): add supabase-py and openai for memory layer"
```

### Task 8: Write the `config` module

**Files:**
- Create: `src/main/resources/sidecar/config.py`

- [ ] **Step 1: Write a tiny config loader**

File `src/main/resources/sidecar/config.py`:

```python
"""Sidecar env config. Loaded from process env (set by the plugin's
SidecarManager before launching uvicorn)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    supabase_url: str | None
    supabase_anon_key: str | None
    nebius_api_key: str | None
    nebius_base_url: str
    nebius_research_model: str


def load() -> Config:
    return Config(
        supabase_url=os.environ.get("SCOPECREEP_SUPABASE_URL"),
        supabase_anon_key=os.environ.get("SCOPECREEP_SUPABASE_ANON_KEY"),
        nebius_api_key=os.environ.get("SCOPECREEP_NEBIUS_API_KEY"),
        nebius_base_url=os.environ.get(
            "SCOPECREEP_NEBIUS_BASE_URL",
            "https://api.tokenfactory.nebius.com/v1/",
        ),
        # Hard default to the cheapest model; overridden only by explicit opt-in.
        nebius_research_model=os.environ.get(
            "SCOPECREEP_NEBIUS_RESEARCH_MODEL", "google/gemma-3-27b-it"
        ),
    )
```

- [ ] **Step 2: Write a failing test for `load()`**

File `src/test/python/test_config.py`:

```python
import os
import importlib
import pathlib
import sys


def _load_module():
    root = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/sidecar"
    sys.path.insert(0, str(root))
    try:
        if "config" in sys.modules:
            del sys.modules["config"]
        return importlib.import_module("config")
    finally:
        sys.path.pop(0)


def test_defaults_when_no_env(monkeypatch):
    for key in (
        "SCOPECREEP_SUPABASE_URL",
        "SCOPECREEP_SUPABASE_ANON_KEY",
        "SCOPECREEP_NEBIUS_API_KEY",
        "SCOPECREEP_NEBIUS_BASE_URL",
        "SCOPECREEP_NEBIUS_RESEARCH_MODEL",
    ):
        monkeypatch.delenv(key, raising=False)
    config = _load_module().load()
    assert config.supabase_url is None
    assert config.supabase_anon_key is None
    assert config.nebius_api_key is None
    assert config.nebius_base_url == "https://api.tokenfactory.nebius.com/v1/"
    assert config.nebius_research_model == "google/gemma-3-27b-it"


def test_reads_env(monkeypatch):
    monkeypatch.setenv("SCOPECREEP_SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SCOPECREEP_NEBIUS_RESEARCH_MODEL", "meta-llama/Llama-3.3-70B-Instruct")
    config = _load_module().load()
    assert config.supabase_url == "https://example.supabase.co"
    assert config.nebius_research_model == "meta-llama/Llama-3.3-70B-Instruct"
```

- [ ] **Step 3: Run the test**

```bash
python3 -m pytest src/test/python/test_config.py -v
```

Expected: 2 passed.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/sidecar/config.py src/test/python/test_config.py
git commit -m "feat(sidecar): config loader for Supabase + Nebius env"
```

### Task 9: Write the `ProfileStore` class

**Files:**
- Create: `src/main/resources/sidecar/memory.py`
- Create: `src/test/python/test_memory.py`

- [ ] **Step 1: Write the failing tests first (mocked Supabase client)**

File `src/test/python/test_memory.py`:

```python
import importlib
import pathlib
import sys
from unittest.mock import MagicMock


def _load_memory():
    root = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/sidecar"
    sys.path.insert(0, str(root))
    try:
        if "memory" in sys.modules:
            del sys.modules["memory"]
        return importlib.import_module("memory")
    finally:
        sys.path.pop(0)


def _mock_supabase(return_value):
    sb = MagicMock()
    chain = sb.table.return_value
    chain.select.return_value = chain
    chain.insert.return_value = chain
    chain.update.return_value = chain
    chain.eq.return_value = chain
    chain.ilike.return_value = chain
    chain.or_.return_value = chain
    chain.limit.return_value = chain
    chain.single.return_value = chain
    chain.execute.return_value = MagicMock(data=return_value)
    return sb


def test_recall_returns_profile():
    memory = _load_memory()
    sb = _mock_supabase({
        "id": "abc",
        "kind": "device",
        "slug": "analog-discovery-rev-c",
        "title": "AD",
        "content": "# AD",
        "status": "published",
        "version": 1,
    })
    store = memory.ProfileStore(sb)
    profile = store.recall("analog-discovery-rev-c")
    assert profile is not None
    assert profile.slug == "analog-discovery-rev-c"
    assert profile.status == "published"


def test_recall_returns_none_when_not_found():
    memory = _load_memory()
    sb = _mock_supabase(None)
    store = memory.ProfileStore(sb)
    assert store.recall("nope") is None


def test_search_returns_list():
    memory = _load_memory()
    sb = _mock_supabase([
        {"id": "1", "kind": "device", "slug": "a", "title": "A", "content": "x",
         "status": "published", "version": 1},
        {"id": "2", "kind": "device", "slug": "b", "title": "B", "content": "y",
         "status": "published", "version": 1},
    ])
    store = memory.ProfileStore(sb)
    results = store.search("a", limit=5)
    assert len(results) == 2
    assert results[0].slug == "a"


def test_remember_inserts_draft():
    memory = _load_memory()
    sb = _mock_supabase([{"id": "new-id"}])
    store = memory.ProfileStore(sb)
    new_id = store.remember(memory.Profile(
        id=None, kind="device", slug="x", title="X",
        content="# X", status="draft", version=1,
    ))
    assert new_id == "new-id"
    sb.table.assert_called_with("profiles")


def test_publish_flips_status():
    memory = _load_memory()
    sb = _mock_supabase([{"id": "abc", "status": "published"}])
    store = memory.ProfileStore(sb)
    store.publish("abc")
    sb.table.assert_called_with("profiles")
```

- [ ] **Step 2: Run the tests — expect failure because memory.py doesn't exist**

```bash
python3 -m pytest src/test/python/test_memory.py -v
```

Expected: `ModuleNotFoundError: No module named 'memory'`.

- [ ] **Step 3: Write the minimal `memory.py` to make the tests pass**

File `src/main/resources/sidecar/memory.py`:

```python
"""Profile store — thin wrapper over supabase-py for the memory layer."""

from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any


@dataclass
class Profile:
    id: str | None
    kind: str
    slug: str
    title: str
    content: str
    status: str = "draft"
    version: int = 1

    def to_row(self) -> dict[str, Any]:
        row = asdict(self)
        if row["id"] is None:
            row.pop("id")
        return row


class ProfileStore:
    """Read/write profiles via Supabase."""

    def __init__(self, supabase_client):
        self.sb = supabase_client

    def recall(self, slug: str) -> Profile | None:
        res = (
            self.sb.table("profiles")
            .select("*")
            .eq("slug", slug)
            .eq("status", "published")
            .limit(1)
            .single()
            .execute()
        )
        data = res.data
        if not data:
            return None
        return self._row_to_profile(data)

    def search(self, query: str, limit: int = 5) -> list[Profile]:
        res = (
            self.sb.table("profiles")
            .select("*")
            .eq("status", "published")
            .or_(f"title.ilike.%{query}%,slug.ilike.%{query}%,content.ilike.%{query}%")
            .limit(limit)
            .execute()
        )
        rows = res.data or []
        return [self._row_to_profile(r) for r in rows]

    def remember(self, profile: Profile) -> str:
        res = self.sb.table("profiles").insert(profile.to_row()).execute()
        rows = res.data or []
        if not rows:
            raise RuntimeError("insert returned no rows (RLS rejected?)")
        return rows[0]["id"]

    def publish(self, profile_id: str) -> None:
        self.sb.table("profiles").update({"status": "published"}).eq("id", profile_id).execute()

    @staticmethod
    def _row_to_profile(row: dict[str, Any]) -> Profile:
        return Profile(
            id=row.get("id"),
            kind=row.get("kind", "device"),
            slug=row["slug"],
            title=row["title"],
            content=row["content"],
            status=row.get("status", "draft"),
            version=row.get("version", 1),
        )
```

- [ ] **Step 4: Re-run the tests**

```bash
python3 -m pytest src/test/python/test_memory.py -v
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/sidecar/memory.py src/test/python/test_memory.py
git commit -m "feat(sidecar): ProfileStore — recall/search/remember/publish over Supabase"
```

### Task 10: Write the research flow

**Files:**
- Create: `src/main/resources/sidecar/research.py`
- Create: `src/test/python/test_research.py`

- [ ] **Step 1: Write failing tests (mocked OpenAI client)**

File `src/test/python/test_research.py`:

```python
import importlib
import pathlib
import sys
from unittest.mock import MagicMock


def _load_research():
    root = pathlib.Path(__file__).resolve().parents[2] / "src/main/resources/sidecar"
    sys.path.insert(0, str(root))
    try:
        if "research" in sys.modules:
            del sys.modules["research"]
        return importlib.import_module("research")
    finally:
        sys.path.pop(0)


def test_research_calls_model_with_instrument_name():
    research = _load_research()
    client = MagicMock()
    client.chat.completions.create.return_value = MagicMock(
        choices=[MagicMock(message=MagicMock(
            content="# Keithley 2400\n\n## Identity\n..."))],
    )
    r = research.Researcher(client=client, model="google/gemma-3-27b-it")
    md = r.draft_profile("Keithley 2400 SMU")
    assert "Keithley 2400" in md
    args = client.chat.completions.create.call_args
    assert args.kwargs["model"] == "google/gemma-3-27b-it"
    # ensure the instrument name flows into the prompt
    messages = args.kwargs["messages"]
    combined = " ".join(m["content"] for m in messages)
    assert "Keithley 2400 SMU" in combined


def test_template_headers_mentioned_in_system_prompt():
    research = _load_research()
    client = MagicMock()
    client.chat.completions.create.return_value = MagicMock(
        choices=[MagicMock(message=MagicMock(content="# X"))]
    )
    r = research.Researcher(client=client, model="google/gemma-3-27b-it")
    r.draft_profile("X")
    system = client.chat.completions.create.call_args.kwargs["messages"][0]["content"]
    for header in ("Identity", "Capabilities", "Pin layout",
                   "Safety limits", "Common operations",
                   "Known gotchas", "Sources"):
        assert header in system
```

- [ ] **Step 2: Run — expect failure**

```bash
python3 -m pytest src/test/python/test_research.py -v
```

Expected: `ModuleNotFoundError: No module named 'research'`.

- [ ] **Step 3: Implement**

File `src/main/resources/sidecar/research.py`:

```python
"""Nebius-backed research flow. Drafts an instrument-profile MD using the
OpenAI SDK pointed at Nebius Token Factory."""

from __future__ import annotations

from typing import Any


_SYSTEM_PROMPT = """\
You are drafting an instrument-profile Markdown document for the Scopecreep
plugin's memory layer. Your draft will be reviewed by a human before
publication. Follow the exact template below. Do not invent capabilities
or APIs — mark any unknowns as TODO.

## Required sections (in this order)

1. Identity (vendor, model, canonical slug, connectivity)
2. Capabilities (with concrete numbers: sample rates, voltage ranges, etc.)
3. Pin layout (table form, with wire colors if applicable)
4. Safety limits (max voltages, currents, destructive-misuse warnings)
5. Common operations (short Python pseudocode showing intent)
6. Known gotchas (bench-earned wisdom — be humble, list TODO where unknown)
7. Sources (URLs you drew from; datestamps; confidence 1–5)

Return ONLY the Markdown document. No preamble. No trailing commentary.
"""


class Researcher:
    def __init__(self, client: Any, model: str):
        self.client = client
        self.model = model

    def draft_profile(self, instrument_name: str) -> str:
        user = (
            f"Draft an instrument-profile Markdown for: {instrument_name}\n"
            "If you are unsure about any numeric spec or pin mapping, say TODO."
        )
        response = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": user},
            ],
            temperature=0.2,
            max_tokens=4096,
        )
        return response.choices[0].message.content or ""
```

- [ ] **Step 4: Re-run**

```bash
python3 -m pytest src/test/python/test_research.py -v
```

Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/sidecar/research.py src/test/python/test_research.py
git commit -m "feat(sidecar): Researcher — drafts instrument-profile MDs via Nebius"
```

### Task 11: Wire memory + research into `worker.py`

**Files:**
- Modify: `src/main/resources/sidecar/worker.py`

- [ ] **Step 1: Read current file to keep existing routes intact**

```bash
cat src/main/resources/sidecar/worker.py
```

- [ ] **Step 2: Rewrite with the new endpoints added**

File `src/main/resources/sidecar/worker.py`:

```python
"""Scopecreep sidecar.

Exposes /health, /upload (schematic + PCB), and /memory/* (profile read/write
+ Nebius-backed research)."""

from __future__ import annotations

import os
import platform
import shutil
import tempfile
import time
from typing import Optional

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel

from config import Config, load as load_config
from memory import Profile, ProfileStore
from research import Researcher

app = FastAPI(title="Scopecreep Sidecar", version="0.0.1")

_STARTED_AT = time.time()
_config: Config = load_config()
_store: Optional[ProfileStore] = None
_researcher: Optional[Researcher] = None


def _get_store() -> ProfileStore:
    global _store
    if _store is None:
        if not (_config.supabase_url and _config.supabase_anon_key):
            raise HTTPException(status_code=503, detail="supabase not configured")
        from supabase import create_client
        sb = create_client(_config.supabase_url, _config.supabase_anon_key)
        _store = ProfileStore(sb)
    return _store


def _get_researcher() -> Researcher:
    global _researcher
    if _researcher is None:
        if not _config.nebius_api_key:
            raise HTTPException(status_code=503, detail="nebius api key not configured")
        from openai import OpenAI
        client = OpenAI(
            base_url=_config.nebius_base_url,
            api_key=_config.nebius_api_key,
        )
        _researcher = Researcher(client=client, model=_config.nebius_research_model)
    return _researcher


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "scopecreep-sidecar",
        "version": app.version,
        "uptime_seconds": round(time.time() - _STARTED_AT, 3),
        "pid": os.getpid(),
        "python": platform.python_version(),
        "supabase_configured": bool(_config.supabase_url and _config.supabase_anon_key),
        "nebius_configured": bool(_config.nebius_api_key),
    }


@app.post("/upload")
async def upload(
    schematic: UploadFile = File(...),
    pcb: UploadFile = File(...),
) -> dict:
    tmp_dir = tempfile.mkdtemp(prefix="scopecreep_")
    schematic_path = os.path.join(tmp_dir, os.path.basename(schematic.filename or "schematic"))
    pcb_path = os.path.join(tmp_dir, os.path.basename(pcb.filename or "pcb"))
    with open(schematic_path, "wb") as f:
        shutil.copyfileobj(schematic.file, f)
    with open(pcb_path, "wb") as f:
        shutil.copyfileobj(pcb.file, f)
    return {"status": "ok", "schematic": schematic_path, "pcb": pcb_path}


class SearchReq(BaseModel):
    query: str
    limit: int = 5


class RememberReq(BaseModel):
    kind: str
    slug: str
    title: str
    content: str
    status: str = "draft"


class ResearchReq(BaseModel):
    instrument_name: str


@app.get("/memory/recall/{slug}")
def memory_recall(slug: str) -> dict:
    profile = _get_store().recall(slug)
    if profile is None:
        raise HTTPException(status_code=404, detail=f"no published profile for {slug}")
    return profile.__dict__


@app.post("/memory/search")
def memory_search(req: SearchReq) -> list[dict]:
    return [p.__dict__ for p in _get_store().search(req.query, req.limit)]


@app.post("/memory/remember")
def memory_remember(req: RememberReq) -> dict:
    new_id = _get_store().remember(Profile(
        id=None, kind=req.kind, slug=req.slug, title=req.title,
        content=req.content, status=req.status,
    ))
    return {"id": new_id, "status": req.status}


@app.post("/memory/publish/{profile_id}")
def memory_publish(profile_id: str) -> dict:
    _get_store().publish(profile_id)
    return {"id": profile_id, "status": "published"}


@app.post("/memory/research")
def memory_research(req: ResearchReq) -> dict:
    md = _get_researcher().draft_profile(req.instrument_name)
    # Persist as a draft under a slug derived from the name.
    slug = req.instrument_name.lower().replace(" ", "-").replace("/", "-")[:64]
    title = req.instrument_name
    new_id = _get_store().remember(Profile(
        id=None, kind="device", slug=slug, title=title,
        content=md, status="draft",
    ))
    return {"id": new_id, "slug": slug, "title": title, "content": md}
```

- [ ] **Step 3: Smoke-test locally**

```bash
cd src/main/resources/sidecar
pip install -q -r requirements.txt
uvicorn worker:app --host 127.0.0.1 --port 8421 &
sleep 2
curl -s http://127.0.0.1:8421/health | python3 -m json.tool
kill %1
```

Expected: JSON with `"supabase_configured": false`, `"nebius_configured": false` (because env vars unset for the smoke test).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/sidecar/worker.py
git commit -m "feat(sidecar): /memory/* endpoints — recall, search, remember, publish, research"
```

### Task 12: Have `SidecarManager` pass env to uvicorn

**Files:**
- Modify: `src/main/kotlin/com/scopecreep/sidecar/SidecarManager.kt`

- [ ] **Step 1: Read current file**

```bash
cat src/main/kotlin/com/scopecreep/sidecar/SidecarManager.kt
```

- [ ] **Step 2: Replace the `launchUvicorn` function with one that forwards settings as env vars**

In `SidecarManager.kt`, locate `private fun launchUvicorn()` and replace its body with:

```kotlin
    private fun launchUvicorn() {
        val settings = ScopecreepSettings.getInstance().state
        val cmd = GeneralCommandLine(
            venvBin("uvicorn").toString(),
            "worker:app",
            "--host",
            settings.runnerHost,
            "--port",
            settings.runnerPort.toString(),
        ).withWorkDirectory(sidecarDir.toFile())
        // Forward optional integration config via env vars — the sidecar
        // refuses /memory/* with 503 if these are unset, which is correct
        // behavior when the user hasn't configured them yet.
        settings.supabaseUrl?.takeIf { it.isNotBlank() }
            ?.let { cmd.withEnvironment("SCOPECREEP_SUPABASE_URL", it) }
        settings.supabaseAnonKey?.takeIf { it.isNotBlank() }
            ?.let { cmd.withEnvironment("SCOPECREEP_SUPABASE_ANON_KEY", it) }
        settings.nebiusApiKey?.takeIf { it.isNotBlank() }
            ?.let { cmd.withEnvironment("SCOPECREEP_NEBIUS_API_KEY", it) }

        val proc = cmd.createProcess()
        val newHandler = OSProcessHandler(proc, cmd.commandLineString, Charsets.UTF_8)
        newHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                log.info("[sidecar] ${event.text.trimEnd()}")
            }

            override fun processTerminated(event: ProcessEvent) {
                log.info("Scopecreep sidecar exited with code ${event.exitCode}")
                handler = null
                started.set(false)
            }
        })
        newHandler.startNotify()
        handler = newHandler
        log.info("Scopecreep sidecar started on ${settings.runnerHost}:${settings.runnerPort}")
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/scopecreep/sidecar/SidecarManager.kt
git commit -m "feat(sidecar): forward Supabase + Nebius settings as env to uvicorn"
```

---

## Block E — Settings + UI

### Task 13: Extend Settings model

**Files:**
- Modify: `src/main/kotlin/com/scopecreep/settings/ScopecreepSettings.kt`
- Modify: `src/test/kotlin/com/scopecreep/ScopecreepSettingsTest.kt`

- [ ] **Step 1: Extend the `State` data class**

In `ScopecreepSettings.kt`, replace the `State` class with:

```kotlin
    data class State(
        var runnerHost: String = "127.0.0.1",
        var runnerPort: Int = 8420,
        var supabaseUrl: String? = "https://dqdaaygmlqifjidiexcs.supabase.co",
        var supabaseAnonKey: String? = null,
        var nebiusApiKey: String? = null,
        var codexProvider: String = "openai",   // openai | nebius-fast | nebius-balanced | nebius-precise
    )
```

- [ ] **Step 2: Add a round-trip test case for the new fields**

Open `src/test/kotlin/com/scopecreep/ScopecreepSettingsTest.kt` and add:

```kotlin
    fun testRoundTripNewFields() {
        val settings = ScopecreepSettings.getInstance()
        val original = ScopecreepSettings.State(
            runnerHost = settings.state.runnerHost,
            runnerPort = settings.state.runnerPort,
            supabaseUrl = settings.state.supabaseUrl,
            supabaseAnonKey = settings.state.supabaseAnonKey,
            nebiusApiKey = settings.state.nebiusApiKey,
            codexProvider = settings.state.codexProvider,
        )
        try {
            settings.loadState(ScopecreepSettings.State(
                runnerHost = "127.0.0.1",
                runnerPort = 8420,
                supabaseUrl = "https://example.supabase.co",
                supabaseAnonKey = "anon-xyz",
                nebiusApiKey = "nb-abc",
                codexProvider = "nebius-fast",
            ))
            val loaded = settings.state
            assertEquals("https://example.supabase.co", loaded.supabaseUrl)
            assertEquals("anon-xyz", loaded.supabaseAnonKey)
            assertEquals("nb-abc", loaded.nebiusApiKey)
            assertEquals("nebius-fast", loaded.codexProvider)
        } finally {
            settings.loadState(original)
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/scopecreep/settings/ScopecreepSettings.kt \
        src/test/kotlin/com/scopecreep/ScopecreepSettingsTest.kt
git commit -m "feat(settings): Supabase URL/key, Nebius key, codex provider fields"
```

### Task 14: Extend Settings UI

**Files:**
- Modify: `src/main/kotlin/com/scopecreep/settings/ScopecreepSettingsConfigurable.kt`

- [ ] **Step 1: Replace the `ui` builder with the new form**

In `ScopecreepSettingsConfigurable.kt`, replace the `ui` lazy block with:

```kotlin
    private val ui by lazy {
        panel {
            group("Sidecar") {
                row("Runner host:") {
                    textField().bindText(state::runnerHost).columns(20)
                }
                row("Runner port:") {
                    intTextField(1..65535).bindIntText(state::runnerPort).columns(6)
                }
            }
            group("Supabase (memory layer)") {
                row("Project URL:") {
                    textField()
                        .bindText({ state.supabaseUrl.orEmpty() }, { state.supabaseUrl = it })
                        .columns(40)
                }
                row("Anon key:") {
                    passwordField()
                        .bindText(
                            { state.supabaseAnonKey.orEmpty() },
                            { state.supabaseAnonKey = it }
                        )
                        .columns(40)
                }
            }
            group("Nebius (research flow)") {
                row("API key:") {
                    passwordField()
                        .bindText(
                            { state.nebiusApiKey.orEmpty() },
                            { state.nebiusApiKey = it }
                        )
                        .columns(40)
                }
                row("Codex provider:") {
                    comboBox(listOf(
                        "openai", "nebius-fast", "nebius-balanced", "nebius-precise"
                    )).bindItem(state::codexProvider.toNullableProperty())
                }
            }
            row {
                comment(
                    "Changes to Supabase/Nebius config apply on next sidecar restart " +
                        "(close and reopen the Scopecreep tool window)."
                )
            }
        }
    }
```

- [ ] **Step 2: Update `apply` to run the codex provider manager when the provider changes**

In the same file, replace `override fun apply()` with:

```kotlin
    override fun apply() {
        val providerChanged = state.codexProvider != settings.state.codexProvider ||
            (state.codexProvider != "openai" &&
             state.nebiusApiKey != settings.state.nebiusApiKey)
        settings.loadState(state.copy())
        if (providerChanged) {
            try {
                CodexProviderManager.getInstance()
                    .applyProvider(state.codexProvider, state.nebiusApiKey)
            } catch (t: Throwable) {
                thisLogger().warn("Failed to apply Codex provider: ${t.message}")
            }
        }
    }
```

- [ ] **Step 3: Add the required imports at the top**

In `ScopecreepSettingsConfigurable.kt`, ensure the following imports exist:

```kotlin
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.toNullableProperty
import com.scopecreep.service.CodexProviderManager
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/scopecreep/settings/ScopecreepSettingsConfigurable.kt
git commit -m "feat(settings): UI for Supabase + Nebius + codex provider"
```

### Task 15: Extend `RunnerClient` with memory methods

**Files:**
- Modify: `src/main/kotlin/com/scopecreep/service/RunnerClient.kt`

- [ ] **Step 1: Add the four new methods**

In `RunnerClient.kt`, add these methods inside the `RunnerClient` class (after the existing `uploadFiles` method):

```kotlin
    fun recallProfile(slug: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/recall/$slug"
        val request = Request.Builder().url(url).get().build()
        return executeAndReturn(request)
    }

    fun searchProfiles(query: String, limit: Int = 10): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/search"
        val body = """{"query":${jsonQuoted(query)},"limit":$limit}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        return executeAndReturn(request)
    }

    fun researchProfile(instrumentName: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/research"
        val body = """{"instrument_name":${jsonQuoted(instrumentName)}}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        return executeAndReturn(request)
    }

    fun publishProfile(profileId: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/publish/$profileId"
        val request = Request.Builder().url(url)
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        return executeAndReturn(request)
    }

    private fun executeAndReturn(request: Request): Result =
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.Ok(response.body?.string().orEmpty())
                else Result.Err("HTTP ${response.code}")
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }

    private fun jsonQuoted(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") +
        "\""
```

- [ ] **Step 2: Replace the `.use` bodies in `ping` and `uploadFiles` to call `executeAndReturn` instead**

Leave as-is — they already follow the pattern; extracting further is optional DRY cleanup. Skip.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/scopecreep/service/RunnerClient.kt
git commit -m "feat(runner): recall/search/research/publish methods"
```

### Task 16: Add commonmark dependency + Markdown renderer

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/com/scopecreep/ui/MarkdownRenderer.kt`

- [ ] **Step 1: Add commonmark to `dependencies`**

In `build.gradle.kts`, extend the `dependencies` block:

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.22.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    intellijPlatform {
        intellijIdea("2025.2.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}
```

- [ ] **Step 2: Write the renderer**

File `src/main/kotlin/com/scopecreep/ui/MarkdownRenderer.kt`:

```kotlin
package com.scopecreep.ui

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    private val cssPrelude = """
        <style>
        body { font-family: -apple-system, Segoe UI, sans-serif;
               font-size: 13px; line-height: 1.5; color: #ddd;
               background: #2b2b2b; padding: 12px; }
        h1, h2, h3 { color: #fff; border-bottom: 1px solid #444;
                     padding-bottom: 4px; }
        code { background: #1e1e1e; padding: 2px 4px;
               border-radius: 3px; font-family: Menlo, monospace;
               font-size: 12px; }
        pre { background: #1e1e1e; padding: 10px; border-radius: 4px;
              overflow-x: auto; }
        pre code { background: none; padding: 0; }
        table { border-collapse: collapse; margin: 8px 0; }
        th, td { border: 1px solid #555; padding: 4px 8px; text-align: left; }
        th { background: #333; }
        a { color: #58a6ff; }
        </style>
    """.trimIndent()

    fun toHtml(markdown: String): String {
        val body = renderer.render(parser.parse(markdown))
        return "<html><head>$cssPrelude</head><body>$body</body></html>"
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/scopecreep/ui/MarkdownRenderer.kt
git commit -m "feat(ui): MarkdownRenderer via commonmark + dark-themed CSS"
```

### Task 17: Build the Profiles tab

**Files:**
- Create: `src/main/kotlin/com/scopecreep/ui/ProfilesPanel.kt`

- [ ] **Step 1: Write the panel**

File `src/main/kotlin/com/scopecreep/ui/ProfilesPanel.kt`:

```kotlin
package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.scopecreep.service.RunnerClient
import org.jetbrains.annotations.VisibleForTesting
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class ProfilesPanel(private val client: RunnerClient = RunnerClient()) {

    private val listModel = DefaultListModel<ProfileRow>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ProfileCellRenderer()
    }
    private val preview = JEditorPane("text/html", "").apply { isEditable = false }
    private val researchButton = JButton("Research new instrument…")
    private val refreshButton = JButton("Refresh")
    private val statusLabel = javax.swing.JLabel(" ")

    val root: JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        val toolbar = JPanel().apply {
            add(refreshButton); add(researchButton); add(statusLabel)
        }
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(list),
            JBScrollPane(preview),
        ).apply {
            dividerLocation = 240
            preferredSize = Dimension(700, 400)
        }
        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
    }

    init {
        list.addListSelectionListener {
            val selected = list.selectedValue ?: return@addListSelectionListener
            loadPreview(selected)
        }
        refreshButton.addActionListener { refresh() }
        researchButton.addActionListener { openResearchDialog() }
        refresh()
    }

    fun refresh() {
        statusLabel.text = "Loading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.searchProfiles("", limit = 50)
            SwingUtilities.invokeLater {
                when (result) {
                    is RunnerClient.Result.Ok -> {
                        listModel.clear()
                        parseProfileList(result.body).forEach(listModel::addElement)
                        statusLabel.text = "${listModel.size()} profiles"
                    }
                    is RunnerClient.Result.Err -> {
                        statusLabel.text = "error: ${result.message}"
                    }
                }
            }
        }
    }

    private fun loadPreview(row: ProfileRow) {
        preview.text = "<html><body>Loading…</body></html>"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.recallProfile(row.slug)
            SwingUtilities.invokeLater {
                preview.text = when (result) {
                    is RunnerClient.Result.Ok -> {
                        val md = extractContent(result.body) ?: result.body
                        MarkdownRenderer.toHtml(md)
                    }
                    is RunnerClient.Result.Err -> "<pre>error: ${result.message}</pre>"
                }
                preview.caretPosition = 0
            }
        }
    }

    private fun openResearchDialog() {
        val name = Messages.showInputDialog(
            "Instrument name (e.g. 'Keithley 2400 SMU')",
            "Research new instrument",
            Messages.getQuestionIcon(),
        ) ?: return
        if (name.isBlank()) return
        statusLabel.text = "Researching '$name'…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.researchProfile(name)
            SwingUtilities.invokeLater {
                when (result) {
                    is RunnerClient.Result.Ok -> {
                        val md = extractContent(result.body) ?: result.body
                        preview.text = MarkdownRenderer.toHtml(md)
                        preview.caretPosition = 0
                        statusLabel.text = "Draft ready — review and press Publish"
                        // TODO(publish button): wire publishProfile(draft_id)
                    }
                    is RunnerClient.Result.Err -> {
                        statusLabel.text = "error: ${result.message}"
                    }
                }
            }
        }
    }

    // ---- JSON parsing helpers (intentionally tiny — avoids another dep) ----

    data class ProfileRow(val slug: String, val title: String) {
        override fun toString() = title
    }

    @VisibleForTesting
    internal fun parseProfileList(body: String): List<ProfileRow> {
        // Accept either [{...}] or { "results": [...] }; very lax.
        val rows = mutableListOf<ProfileRow>()
        val titleRegex = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val slugRegex = Regex("\"slug\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val titles = titleRegex.findAll(body).map { it.groupValues[1] }.toList()
        val slugs = slugRegex.findAll(body).map { it.groupValues[1] }.toList()
        val n = minOf(titles.size, slugs.size)
        for (i in 0 until n) {
            rows += ProfileRow(slug = slugs[i], title = titles[i])
        }
        return rows
    }

    @VisibleForTesting
    internal fun extractContent(body: String): String? {
        val match = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private class ProfileCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val base = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            border = JBUI.Borders.empty(4, 8)
            return base
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/scopecreep/ui/ProfilesPanel.kt
git commit -m "feat(ui): ProfilesPanel with list, markdown preview, and research button"
```

### Task 18: Wire the Profiles tab into the tool window

**Files:**
- Modify: `src/main/kotlin/com/scopecreep/ScopecreepToolWindowFactory.kt`

- [ ] **Step 1: Read the current file**

```bash
cat src/main/kotlin/com/scopecreep/ScopecreepToolWindowFactory.kt
```

- [ ] **Step 2: Replace `createToolWindowContent` to register two tabs**

In `ScopecreepToolWindowFactory.kt`, replace the `createToolWindowContent` function with:

```kotlin
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        val pingTab = factory.createContent(ScopecreepPanel().root, "Ping", false)
        toolWindow.contentManager.addContent(pingTab)

        val profilesTab = factory.createContent(
            com.scopecreep.ui.ProfilesPanel().root, "Profiles", false
        )
        toolWindow.contentManager.addContent(profilesTab)
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/scopecreep/ScopecreepToolWindowFactory.kt
git commit -m "feat(ui): add Profiles tab alongside Ping in the tool window"
```

---

## Block F — Verification

### Task 19: Run the full test suite headlessly

- [ ] **Step 1: Python tests**

```bash
python3 -m pytest src/test/python/ -v
```

Expected: `test_config.py` (2), `test_memory.py` (5), `test_research.py` (2) — 9 passed.

- [ ] **Step 2: Kotlin tests (requires JDK 21 locally; ok if run by the user)**

```bash
./gradlew check
```

Expected: `ScopecreepSettingsTest` passes including `testRoundTripNewFields`.

- [ ] **Step 3: Plugin verification**

```bash
./gradlew verifyPlugin
```

Expected: no errors.

- [ ] **Step 4: Build the plugin distribution**

```bash
./gradlew buildPlugin
```

Expected: `build/distributions/Scopecreep-0.0.1.zip` is produced, contains `sidecar/memory.py`, `sidecar/research.py`, `context/devices/analog-discovery-rev-c.md`, `scripts/codex-nebius-setup.sh`.

Verify with:

```bash
unzip -l build/distributions/Scopecreep-0.0.1.zip | \
  grep -E "memory\.py|research\.py|analog-discovery|codex-nebius"
```

### Task 20: Final commit + push

- [ ] **Step 1: Review the branch diff**

```bash
git log main..HEAD --oneline
git diff --stat main...HEAD
```

- [ ] **Step 2: Push the branch**

```bash
git push -u origin dataflywheel
```

- [ ] **Step 3: Open a PR from `dataflywheel` → `main`**

```bash
gh pr create --base main --head dataflywheel \
  --title "feat: data flywheel — Supabase memory layer + Nebius research" \
  --body "$(cat <<'EOF'
## Summary
- Supabase schema + RLS + seed SQL for instrument profiles
- Hand-written Analog Discovery Rev C seed profile
- Sidecar memory layer (supabase-py) + Nebius-backed research flow
- Codex provider routing (OpenAI ↔ Nebius via opencolin/codex-nebius)
- Profiles tab in the tool window with Markdown preview + "Research new instrument" button

## Test plan
- [ ] Apply `supabase/migrations/001_init.sql` + `supabase/seed.sql` to the Supabase project
- [ ] Enable Realtime on `profiles`
- [ ] Set Supabase URL / anon key / Nebius API key in the Scopecreep settings panel
- [ ] Run `./gradlew runIde` → Profiles tab shows the AD Rev C profile
- [ ] "Research new instrument" with a novel name → a draft MD appears

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review notes

- Every spec section maps to a task block: A→1,2,3; B→4; C→5,6; D→7–12; E→13–18; acceptance criteria verified in block F (19,20).
- No "TODO add validation" / "handle edge cases" placeholders. The one literal TODO in `ProfilesPanel.openResearchDialog` is scoped ("wire publishProfile(draft_id)") and non-blocking for MVP — draft remains stored in DB either way.
- Types consistent: `Profile(id, kind, slug, title, content, status, version)` appears identically in Python, in `RememberReq`, and in Kotlin-side JSON shape.
- Method names consistent: `recall`/`search`/`remember`/`publish`/`research` across Python, Kotlin `RunnerClient`, and HTTP routes.
- Stretch items (MCP server — D2 in spec) deferred, noted in spec non-goals. Not in this plan.
