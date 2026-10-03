-- bikesched schema v1: nodes, jobs, steps and events.
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

-- Jobs are claimed by nodes with:  ... where state in ('SCHEDULED', 'WAITING', 'COMPENSATING')
--                                  and scheduled_at <= now()
--                                  order by priority desc, scheduled_at for update skip locked
-- A WAITING job waits for an event (see bikesched_events); its scheduled_at is the deadline of the
-- wait, or 'infinity' if it waits without one.
-- A job that is cancelled or fails permanently after completing steps with a compensation is
-- COMPENSATING (compensated_state: the state it ends in) until its compensations ran, or
-- COMPENSATION_FAILED once they failed in max_attempts compensation attempts.
create table bikesched_jobs (
    id           uuid        primary key,
    job_type     text        not null,          -- identifies the handler that executes the job
    payload      jsonb,
    state        text        not null default 'SCHEDULED',
    priority     integer     not null default 0,
    scheduled_at timestamptz not null default now(),
    attempts     integer     not null default 0,
    max_attempts integer     not null default 3,
    timeout_millis bigint,                      -- max. duration of an attempt; null: node default / none
    schedule     text,                          -- occurrence of a recurring job: its schedule's description
    planned_at   timestamptz,                   -- its planned run (only if the schedule uses planned times);
                                                -- unlike scheduled_at not moved by retries or event waits
    compensated_state     text,                 -- CANCELLED or FAILED: the state a compensating job ends in
    compensation_attempts integer not null default 0,
    cancel_requested_at   timestamptz,          -- Bikesched.cancel(jobId): cancelled on its next claim
    cancel_message        text,
    locked_by    uuid        references bikesched_nodes (id),
    locked_at    timestamptz,
    last_error   text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    finished_at  timestamptz,
    constraint bikesched_jobs_state_check
        check (state in ('SCHEDULED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                         'COMPENSATING', 'COMPENSATION_FAILED')),
    constraint bikesched_jobs_attempts_check
        check (attempts >= 0 and max_attempts >= 1 and compensation_attempts >= 0),
    constraint bikesched_jobs_compensated_state_check
        check (compensated_state in ('CANCELLED', 'FAILED')
               and state in ('RUNNING', 'COMPENSATING', 'COMPENSATION_FAILED', compensated_state)
               or compensated_state is null and state not in ('COMPENSATING', 'COMPENSATION_FAILED')),
    constraint bikesched_jobs_timeout_check
        check (timeout_millis > 0),
    constraint bikesched_jobs_lock_check
        check ((state = 'RUNNING') = (locked_by is not null))
);

-- Polling index: only due-able jobs, in claim order.
create index bikesched_jobs_poll_idx on bikesched_jobs (priority desc, scheduled_at)
    where state in ('SCHEDULED', 'WAITING', 'COMPENSATING');

-- Recurring jobs (identified by their job type): at most one pending occurrence each. Finishing an
-- occurrence inserts the next one in the same transaction; registering a recurring job and the
-- heartbeat's repair insert one with "on conflict do nothing" if it is missing.
create unique index bikesched_jobs_recurring_idx on bikesched_jobs (job_type)
    where schedule is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING');

-- Finding/releasing jobs of a (dead) node; also backs the foreign key.
create index bikesched_jobs_locked_by_idx on bikesched_jobs (locked_by);

-- Steps of a job: each named step runs once per job, its result is persisted so a retried
-- job can skip steps that already succeeded. Event steps (event_name/event_key set) wait for an
-- event instead: WAITING until it is published (result = its payload) or wait_until has passed.
-- A compensable step (declared with a compensation) is COMPENSATED once its compensation ran.
-- Cancelling a job marks its unfinished steps (WAITING, or RUNNING after a crash) CANCELLED.
create table bikesched_steps (
    job_id      uuid        not null references bikesched_jobs (id) on delete cascade,
    name        text        not null,
    state       text        not null default 'RUNNING',
    result      jsonb,
    attempts    integer     not null default 0,
    last_error  text,
    event_name  text,
    event_key   text,
    wait_until  timestamptz,
    compensable boolean     not null default false,
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    primary key (job_id, name),
    constraint bikesched_steps_state_check
        check (state in ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'COMPENSATED',
                         'CANCELLED')),
    constraint bikesched_steps_event_check
        check ((event_name is null) = (event_key is null)
               and (event_name is not null or (wait_until is null and state not in ('WAITING', 'TIMED_OUT'))))
);

-- Matching published events to the steps waiting for them.
create index bikesched_steps_waiting_idx on bikesched_steps (event_name, event_key)
    where state = 'WAITING';

-- Published events, kept for a retention period: a step that starts waiting for an event that was
-- already published receives the latest one right away, so events published before anyone waits
-- for them are not lost.
create table bikesched_events (
    id           uuid        primary key,
    event_name   text        not null,
    event_key    text        not null,
    payload      jsonb,
    published_at timestamptz not null default now()
);

create index bikesched_events_name_key_idx on bikesched_events (event_name, event_key, published_at desc);

-- Deleting events after their retention period.
create index bikesched_events_published_at_idx on bikesched_events (published_at);
