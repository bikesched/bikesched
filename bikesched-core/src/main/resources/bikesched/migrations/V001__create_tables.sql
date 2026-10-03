-- bikesched schema v1: nodes, jobs and steps.
-- States are text + CHECK constraints (not Postgres enums) so adding states later is a cheap migration.

-- Every application instance (node) registers itself here and heartbeats periodically.
-- Nodes whose heartbeat is too old are considered dead; their RUNNING jobs can be rescheduled.
-- The name (configured or the host name) identifies a node: only one node per name.
create table bikesched_nodes (
    id                uuid        primary key,
    name              text        not null,
    started_at        timestamptz not null default now(),
    last_heartbeat_at timestamptz not null default now(),
    constraint bikesched_nodes_name_key unique (name)
);

create index bikesched_nodes_last_heartbeat_at_idx on bikesched_nodes (last_heartbeat_at);

-- Jobs are claimed by nodes with:  ... where state = 'SCHEDULED' and scheduled_at <= now()
--                                  order by priority desc, scheduled_at for update skip locked
create table bikesched_jobs (
    id           uuid        primary key,
    job_type     text        not null,          -- identifies the handler that executes the job
    payload      jsonb,
    state        text        not null default 'SCHEDULED',
    priority     integer     not null default 0,
    scheduled_at timestamptz not null default now(),
    attempts     integer     not null default 0,
    max_attempts integer     not null default 3,
    locked_by    uuid        references bikesched_nodes (id),
    locked_at    timestamptz,
    last_error   text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    finished_at  timestamptz,
    constraint bikesched_jobs_state_check
        check (state in ('SCHEDULED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    constraint bikesched_jobs_attempts_check
        check (attempts >= 0 and max_attempts >= 1),
    constraint bikesched_jobs_lock_check
        check ((state = 'RUNNING') = (locked_by is not null))
);

-- Polling index: only due-able jobs, in claim order.
create index bikesched_jobs_poll_idx on bikesched_jobs (priority desc, scheduled_at)
    where state = 'SCHEDULED';

-- Finding/releasing jobs of a (dead) node; also backs the foreign key.
create index bikesched_jobs_locked_by_idx on bikesched_jobs (locked_by);

-- Steps of a job: each named step runs once per job, its result is persisted so a retried
-- job can skip steps that already succeeded.
create table bikesched_steps (
    job_id      uuid        not null references bikesched_jobs (id) on delete cascade,
    name        text        not null,
    state       text        not null default 'RUNNING',
    result      jsonb,
    attempts    integer     not null default 0,
    last_error  text,
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    primary key (job_id, name),
    constraint bikesched_steps_state_check
        check (state in ('RUNNING', 'SUCCEEDED', 'FAILED'))
);
