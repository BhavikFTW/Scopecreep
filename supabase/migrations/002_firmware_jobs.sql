-- Scopecreep firmware-generation flywheel.
-- Stores the state and artifacts of a LangGraph firmware-generation run
-- (architect → generate → stitch → compile → flash+test). The pipeline
-- writes progress to this table; the IDE plugin subscribes via Realtime.
--
-- The pipeline itself lives out-of-repo (~/benchy/pipeline); this branch
-- only defines the contract so the plugin UI can render runs.

------------------------------------------------------------
-- firmware_jobs
------------------------------------------------------------
create table if not exists firmware_jobs (
  id                 uuid primary key default gen_random_uuid(),
  goal               text not null,
  target             text not null default 'esp32-s3',
  status             text not null default 'queued'
                       check (status in (
                         'queued','architecting','generating','stitching',
                         'compiling','flash_requested','flashing','done','failed'
                       )),
  architecture_spec  jsonb,
  files              jsonb,           -- [{path, content, language}]
  logs               jsonb,           -- [{stage, level, message, at}]
  compile_output     text,
  flash_output       text,
  error              text,
  author_id          uuid references auth.users(id),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create index if not exists firmware_jobs_status_idx on firmware_jobs (status);
create index if not exists firmware_jobs_author_idx on firmware_jobs (author_id);

drop trigger if exists firmware_jobs_touch_updated_at on firmware_jobs;
create trigger firmware_jobs_touch_updated_at
  before update on firmware_jobs
  for each row execute function touch_updated_at();

------------------------------------------------------------
-- RLS: mirrors profiles author-centric policy
------------------------------------------------------------
alter table firmware_jobs enable row level security;

drop policy if exists "author reads own jobs" on firmware_jobs;
create policy "author reads own jobs"
  on firmware_jobs for select to authenticated
  using (author_id = auth.uid());

drop policy if exists "author writes own jobs" on firmware_jobs;
create policy "author writes own jobs"
  on firmware_jobs for insert to authenticated
  with check (author_id = auth.uid());

drop policy if exists "author updates own jobs" on firmware_jobs;
create policy "author updates own jobs"
  on firmware_jobs for update to authenticated
  using (author_id = auth.uid())
  with check (author_id = auth.uid());

drop policy if exists "author deletes own jobs" on firmware_jobs;
create policy "author deletes own jobs"
  on firmware_jobs for delete to authenticated
  using (author_id = auth.uid());

-- Enable Realtime on firmware_jobs via Dashboard → Database → Replication.
