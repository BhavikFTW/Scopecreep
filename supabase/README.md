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
5. **Auto-enable RLS on new tables** (Settings → Database → API or similar):
   turn on to prevent future footguns.
6. Grab the **anon key** and **project URL** from Settings → API. You'll paste
   these into Scopecreep's Settings panel.

## Verify

Run in the SQL editor:

```sql
select kind, slug, status, length(content) as bytes from profiles;
```

You should see a single `published` row for `analog-discovery-rev-c` with
a few thousand bytes of content.

## Re-running

All migrations + seed are idempotent (`if not exists`, `on conflict ... do
update`). Safe to re-run if the schema changes.
