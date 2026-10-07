-- bikesched schema v1: nodes, jobs, steps, events and the job history.
-- States are text + CHECK constraints (not Postgres enums) so adding states later is a cheap migration.
-- Names (node names, job types, step and event names, recurrence descriptions) have at most 200
-- characters, event keys at most 500: most are indexed, and B-tree entries are limited to about
-- 2.7 kB. The library checks the same limits (ValidationUtil) before writing. Messages (last_error,
-- cancel_message) are not indexed and have no constraint: the library truncates them to 4000
-- characters, so recording a failure never fails on its length.

-- Every application instance (node) registers itself here and heartbeats periodically.
-- Nodes whose heartbeat is too old are considered dead; their RUNNING jobs can be rescheduled.
-- The name (configured or the host name) identifies a node: only one node per name.
create table bikesched_nodes (
    id                uuid        primary key,
    name              text        not null,
    started_at        timestamptz not null default now(),
    last_heartbeat_at timestamptz not null default now(),
    constraint bikesched_nodes_name_key unique (name),
    constraint bikesched_nodes_name_check check (char_length(name) between 1 and 200)
);

create index bikesched_nodes_last_heartbeat_at_idx on bikesched_nodes (last_heartbeat_at);

-- Jobs are claimed by nodes with:  ... where state in ('SCHEDULED', 'WAITING', 'COMPENSATING')
--                                  and scheduled_at <= now()
--                                  order by priority desc, scheduled_at for update skip locked
-- A WAITING job waits for an event (see bikesched_events), for a child job or sleeps; its
-- scheduled_at is the deadline of the wait ('infinity' if it waits without one) or the wake-up time
-- of the sleep.
-- A job that is cancelled or fails permanently after completing steps with a compensation is
-- COMPENSATING (compensated_state: the state it ends in) until its compensations ran, or
-- COMPENSATION_FAILED once they failed in max_attempts compensation attempts.
-- A child job (parent_job_id set) was started by a step of its parent (kind CHILD_JOB); ending it
-- completes that step with its outcome. parent_job_id only references the parent (no FK): the
-- parent may be deleted after its retention while the child still runs.
create table bikesched_jobs (
    id           uuid        primary key,
    type         text        not null,          -- identifies the handler that executes the job
    state        text        not null default 'SCHEDULED',
    payload      jsonb,
    priority     integer     not null default 0,
    scheduled_at timestamptz not null default now(),
    attempts     integer     not null default 0,
    max_attempts integer     not null default 3,
    timeout_millis bigint,                      -- max. duration of an attempt; null: node default / none
    recurrence   text,                          -- occurrence of a recurring job: its recurrence's description
    fixed_rate_at timestamptz,                  -- its planned run (only for fixed-rate recurrences);
                                                -- unlike scheduled_at not moved by retries or event waits
    compensated_state     text,                 -- CANCELLED or FAILED: the state a compensating job ends in
    compensation_attempts integer not null default 0,
    cancel_requested_at   timestamptz,          -- Bikesched.cancel(jobId): cancelled on its next claim
    cancel_message        text,
    result       jsonb,                         -- Job.complete(result); only set when SUCCEEDED
    parent_job_id uuid,                         -- the job that started this one as a child
    locked_by    uuid        references bikesched_nodes (id),
    lock_token   uuid,                          -- new for every claim: fences the claim's writes
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
        check ((state = 'RUNNING') = (locked_by is not null)
               and (locked_by is null) = (lock_token is null)),
    constraint bikesched_jobs_type_check
        check (char_length(type) between 1 and 200),
    constraint bikesched_jobs_recurrence_check
        check (char_length(recurrence) between 1 and 200),
    constraint bikesched_jobs_result_check
        check (result is null or state = 'SUCCEEDED'),
    -- Children are never recurring.
    constraint bikesched_jobs_parent_check
        check (parent_job_id is null or recurrence is null)
);

-- Polling index: only due-able jobs, in claim order. The type lets the claim skip jobs of types
-- the node has no handlers for on index entries.
create index bikesched_jobs_poll_idx on bikesched_jobs (priority desc, scheduled_at, type)
    where state in ('SCHEDULED', 'WAITING', 'COMPENSATING');

-- Recurring jobs (identified by their job type): at most one pending occurrence each. Finishing an
-- occurrence inserts the next one in the same transaction; registering a recurring job and the
-- node's repair insert one with "on conflict do nothing" if it is missing.
create unique index bikesched_jobs_recurring_idx on bikesched_jobs (type)
    where recurrence is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING');

-- Deleting finished jobs after the job retention (only ended jobs have finished_at).
create index bikesched_jobs_finished_at_idx on bikesched_jobs (finished_at)
    where finished_at is not null;

-- Finding/releasing jobs of a (dead) node; also backs the foreign key.
create index bikesched_jobs_locked_by_idx on bikesched_jobs (locked_by);

-- Steps of a job: each named step runs once per job, its result is persisted so a retried
-- job can skip steps that already succeeded. The kind tells what a step does:
-- - STEP: a regular step running user code; result = what it returned.
-- - SLEEP: WAITING until wait_until has passed and the job runs again (SUCCEEDED).
-- - EVENT_WAIT: waits for the event event_name/event_key: WAITING until it is published
--   (SUCCEEDED, event_id = the received event, result = a copy of its payload, so the step does
--   not depend on the event) or wait_until has passed (TIMED_OUT).
-- - EVENT_PUBLISH: published the event event_name/event_key (event_id) exactly once; SUCCEEDED
--   when created, in the transaction that published the event.
-- - CHILD_JOB: started the child job child_job_id of type child_job_type exactly once; WAITING until the child ends
--   (SUCCEEDED, child_state = its terminal state, result = its result, last_error = its error or
--   cancel message) or the parent's wait for it times out at wait_until (TIMED_OUT).
-- event_id only references the event (no FK): events are deleted after their retention; the same
-- for child_job_id (children are deleted after the job retention).
-- A compensable step (declared with a compensation) is COMPENSATED once its compensation ran.
-- Cancelling a job marks its unfinished steps (WAITING, or RUNNING after a crash) CANCELLED.
-- A RUNNING step is locked by the node running it (locked_by). Every step write also requires the
-- job to be RUNNING with the lock_token of the attempt making the write, so an attempt whose job
-- was released (e.g. after missed heartbeats) can no longer change its steps, even if the same
-- node has claimed the job again since.
create table bikesched_steps (
    job_id      uuid        not null references bikesched_jobs (id) on delete cascade,
    name        text        not null,
    kind        text        not null default 'STEP',
    state       text        not null default 'RUNNING',
    result      jsonb,
    attempts    integer     not null default 0,
    locked_by   uuid,                   -- the node running the step (no FK: may outlive a dead node)
    last_error  text,
    event_name  text,
    event_key   text,
    event_id    uuid,                   -- the event received or published (no FK: see above)
    wait_until  timestamptz,
    child_job_id uuid,                  -- the child job started (no FK: see above)
    child_job_type text,                -- its type, checked on replays even after it was deleted
    child_state text,                   -- the child's terminal state, once it ended
    compensable boolean     not null default false,
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    primary key (job_id, name),
    constraint bikesched_steps_kind_check
        check (kind in ('STEP', 'SLEEP', 'EVENT_WAIT', 'EVENT_PUBLISH', 'CHILD_JOB')),
    constraint bikesched_steps_state_check
        check (state in ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'COMPENSATED',
                         'CANCELLED')),
    -- Event waits and publishes name their event; an event wait references the event once it
    -- received it, a publish always; only event waits and child jobs time out.
    constraint bikesched_steps_event_check
        check ((event_name is not null) = (kind in ('EVENT_WAIT', 'EVENT_PUBLISH'))
               and (event_name is null) = (event_key is null)
               and case kind
                       when 'EVENT_WAIT' then (event_id is not null) = (state = 'SUCCEEDED')
                       when 'EVENT_PUBLISH' then event_id is not null
                       else event_id is null
                   end
               and (kind in ('EVENT_WAIT', 'CHILD_JOB') or state <> 'TIMED_OUT')),
    -- Child job steps reference their child; the child's state is set once it ended (kept when the
    -- step is compensated afterwards).
    constraint bikesched_steps_child_check
        check ((child_job_id is not null) = (kind = 'CHILD_JOB')
               and (child_job_type is not null) = (kind = 'CHILD_JOB')
               and (child_state is null or kind = 'CHILD_JOB' and state in ('SUCCEEDED', 'COMPENSATED'))
               and (kind <> 'CHILD_JOB' or state <> 'SUCCEEDED' or child_state is not null)
               and child_state in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'COMPENSATION_FAILED')),
    -- Only sleeps, event waits and child jobs wait; a sleep needs its wake-up time. (An event wait
    -- or a wait for a child job without a timeout waits without a deadline.)
    constraint bikesched_steps_wait_check
        check ((state <> 'WAITING' or kind in ('SLEEP', 'EVENT_WAIT', 'CHILD_JOB'))
               and (wait_until is null or kind in ('SLEEP', 'EVENT_WAIT', 'CHILD_JOB'))
               and (kind <> 'SLEEP' or wait_until is not null)),
    constraint bikesched_steps_lock_check
        check ((state = 'RUNNING') = (locked_by is not null)),
    constraint bikesched_steps_name_check
        check (char_length(name) between 1 and 200
               and char_length(event_name) between 1 and 200
               and char_length(event_key) between 1 and 500
               and char_length(child_job_type) between 1 and 200)
);

-- Matching published events to the steps waiting for them.
create index bikesched_steps_waiting_idx on bikesched_steps (event_name, event_key)
    where kind = 'EVENT_WAIT' and state = 'WAITING';

-- Finding the step of a child job that ended.
create index bikesched_steps_child_job_idx on bikesched_steps (child_job_id)
    where kind = 'CHILD_JOB' and state = 'WAITING';

-- Published events, kept until expires_at (published_at + the event's retention): a step that starts
-- waiting for an event that was already published receives the latest unexpired one right away, so
-- events published before anyone waits for them are not lost. With a retention of 0, an event only
-- reaches the steps waiting when it is published.
create table bikesched_events (
    id           uuid        primary key,
    name         text        not null,
    key          text        not null,
    payload      jsonb,
    published_at timestamptz not null default now(),
    expires_at   timestamptz not null,
    constraint bikesched_events_name_key_check
        check (char_length(name) between 1 and 200 and char_length(key) between 1 and 500),
    constraint bikesched_events_expires_at_check
        check (expires_at >= published_at)
);

create index bikesched_events_name_key_idx on bikesched_events (name, key, published_at desc);

-- Deleting expired events.
create index bikesched_events_expires_at_idx on bikesched_events (expires_at);

-- Job history (only written by nodes with the history enabled): one row per change of a job
-- (step_name null) or of one of its steps, in the transaction of the change. attempt is the job's
-- attempt count after the change; node_id the node that made it (null: the application, e.g.
-- scheduling, cancel requests, publishing). No foreign keys: entries are kept for their own
-- retention period, also after their job was deleted.
create table bikesched_job_history (
    job_id     uuid        not null,
    seq        bigint      generated always as identity,
    created_at timestamptz not null default now(),
    step_name  text,
    state      text        not null,
    attempt    integer     not null,
    node_id    uuid,
    detail     text,                   -- error, cancel message, deadline, ...; truncated to 4000 characters
    primary key (job_id, seq),
    constraint bikesched_job_history_state_check
        check (step_name is null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED',
                                               'CANCELLED', 'COMPENSATING', 'COMPENSATION_FAILED')
               or step_name is not null and state in ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED',
                                                      'TIMED_OUT', 'COMPENSATED', 'CANCELLED')),
    constraint bikesched_job_history_step_name_check
        check (char_length(step_name) between 1 and 200)
);

-- Deleting entries after their retention period.
create index bikesched_job_history_created_at_idx on bikesched_job_history (created_at);
