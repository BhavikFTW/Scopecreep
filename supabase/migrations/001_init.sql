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
