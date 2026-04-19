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
