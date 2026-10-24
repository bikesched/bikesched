-- bikesched schema v1: nodes, jobs, steps, events and the job history.
-- States are text + CHECK constraints (not Postgres enums) so adding states later is a cheap migration.
-- Names (node, job, step and event names, recurrence descriptions) have at most 200
-- characters, event keys and idempotency keys at most 500: most are indexed, and B-tree entries are
-- limited to 2704 bytes. Since a character takes up to 4 bytes, they also have at most 3 bytes per
-- character in UTF-8 (600 and 1500), so an index on a name and a key stays at most 2100 bytes. The
-- library checks the same limits (ValidationUtil) before writing.
-- The identifying columns and the state come first (a job's id, name, type and state; a step's id,
-- job, name, type and state); the other columns are ordered by alignment: uuids, then timestamps and
-- bigints, then integers and booleans, then text and jsonb, so rows have little padding.
-- Ids are time-ordered UUIDs (version 7, created by the library), so new rows land at the end of
-- the primary keys and of the indexes led by a job id.
-- Messages (last_error, cancel_message) are not indexed and have no constraint: the library
-- truncates them to 4000 characters, so recording a failure never fails on its length.

-- Every application instance (node) registers itself here and heartbeats periodically.
-- Nodes whose heartbeat is too old are considered dead; their RUNNING jobs can be rescheduled.
-- The name (configured or the host name) is for people only: it need not be unique, so an instance
-- restarted after a crash registers next to the stale row of its previous run. The id identifies it.
-- The heartbeat is the table's only frequent write; last_heartbeat_at has no index, so heartbeats
-- are HOT updates. The table has a few rows, read by sequential scans.
create table bikesched_nodes (
    id                uuid        not null,
    started_at        timestamptz not null default now(),
    last_heartbeat_at timestamptz not null default now(),
    name              text        not null,
    constraint bikesched_nodes_pkey primary key (id),
    constraint bikesched_nodes_name_check
        check (char_length(name) between 1 and 200 and octet_length(name) <= 600)
);

-- Jobs are claimed by nodes with:  ... where state in ('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING')
--                                  and scheduled_at <= now()
--                                  order by priority desc, scheduled_at for update skip locked
-- A WAITING job waits for an event (see bikesched_events), for a child job or sleeps; its
-- scheduled_at is the deadline of the wait ('infinity' if it waits without one) or the wake-up time
-- of the sleep.
-- A job that is cancelled or fails permanently after completing steps with a compensation is
-- COMPENSATING (compensation_end_state: the state it ends in) until its compensations ran, or
-- COMPENSATION_FAILED once they failed in max_attempts compensation attempts.
-- A job with a failure handler that would end FAILED or COMPENSATION_FAILED is FAILING
-- (failure_end_state: the state it ends in) until its failure handler ran (failure_attempts: its runs).
-- The type tells how a job was started:
-- - ONE_OFF: scheduled by the application (or the admin UI), runs once.
-- - RECURRING: an occurrence of a recurring job (recurrence and planned_run_at set); finishing it
--   inserts the next occurrence.
-- - CHILD: started by a step of its parent (parent_job_id set, step type CHILD_JOB); ending it
--   completes that step with its outcome. parent_job_id only references the parent (no FK): the
-- parent may be deleted after its retention while the child still runs.
create table bikesched_jobs (
    id                     uuid        not null,
    name                   text        not null,           -- identifies the handler that executes the job
    type                   text        not null default 'ONE_OFF',
    state                  text        not null default 'SCHEDULED',
    parent_job_id          uuid,                           -- the job that started this one as a child
    locked_by_node_id      uuid,
    lock_token             uuid,                           -- new for every claim: fences the claim's writes
    scheduled_at           timestamptz not null default now(),
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    locked_at              timestamptz,
    finished_at            timestamptz,
    cancel_requested_at    timestamptz,                    -- Bikesched.cancel(jobId): cancelled on its next claim
    planned_run_at         timestamptz,                    -- planned run of an occurrence; unlike scheduled_at
                                                           -- not moved by retries or event waits
    timeout_millis         bigint,                         -- max. duration of an attempt; null: node default / none
    priority               integer     not null default 0,
    attempts               integer     not null default 0,
    max_attempts           integer     not null default 3,
    compensation_attempts  integer     not null default 0,
    failure_attempts       integer     not null default 0, -- runs of its failure handler
    compensation_end_state text,                           -- CANCELLED or FAILED: the state a compensating job ends in
    failure_end_state      text,                           -- FAILED or COMPENSATION_FAILED: the state a FAILING job ends in
    recurrence             text,                           -- occurrence of a recurring job: its recurrence's description
    idempotency_key        text,                           -- dedups scheduling: unique per name while the job exists
    payload                jsonb,
    result                 jsonb,                          -- Job.complete(result); only set when SUCCEEDED
    last_error             text,
    cancel_message         text,
    constraint bikesched_jobs_pkey primary key (id),
    constraint bikesched_jobs_locked_by_node_id_fkey
        foreign key (locked_by_node_id) references bikesched_nodes (id),
    constraint bikesched_jobs_type_check
        check (type in ('ONE_OFF', 'RECURRING', 'CHILD')),
    constraint bikesched_jobs_state_check
        check (state in ('SCHEDULED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                         'COMPENSATING', 'COMPENSATION_FAILED', 'FAILING')),
    constraint bikesched_jobs_attempts_check
        check (attempts >= 0 and max_attempts >= 1 and compensation_attempts >= 0
               and failure_attempts >= 0),
    constraint bikesched_jobs_compensation_end_state_check
        check (compensation_end_state in ('CANCELLED', 'FAILED')
               and state in ('RUNNING', 'COMPENSATING', 'COMPENSATION_FAILED', 'FAILING', compensation_end_state)
               or compensation_end_state is null and state not in ('COMPENSATING', 'COMPENSATION_FAILED')),
    -- A FAILING job (RUNNING while its failure handler runs) knows the state it ends in.
    constraint bikesched_jobs_failure_end_state_check
        check (failure_end_state in ('FAILED', 'COMPENSATION_FAILED') and state in ('FAILING', 'RUNNING')
               or failure_end_state is null and state <> 'FAILING'),
    constraint bikesched_jobs_timeout_check
        check (timeout_millis > 0),
    constraint bikesched_jobs_lock_check
        check ((state = 'RUNNING') = (locked_by_node_id is not null)
               and (locked_by_node_id is null) = (lock_token is null)
               and (locked_by_node_id is null) = (locked_at is null)),
    -- Exactly the ended jobs have finished_at; the clean-up of ended jobs relies on it.
    constraint bikesched_jobs_finished_at_check
        check ((finished_at is not null)
               = (state in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'COMPENSATION_FAILED'))),
    constraint bikesched_jobs_planned_run_at_check
        check ((planned_run_at is null) = (recurrence is null)),
    constraint bikesched_jobs_cancel_message_check
        check (cancel_message is null or cancel_requested_at is not null),
    constraint bikesched_jobs_name_check
        check (char_length(name) between 1 and 200 and octet_length(name) <= 600),
    constraint bikesched_jobs_recurrence_check
        check (char_length(recurrence) between 1 and 200 and octet_length(recurrence) <= 600),
    constraint bikesched_jobs_idempotency_key_check
        check (char_length(idempotency_key) between 1 and 500 and octet_length(idempotency_key) <= 1500),
    constraint bikesched_jobs_result_check
        check (result is null or state = 'SUCCEEDED'),
    -- Exactly the occurrences of recurring jobs have a recurrence, exactly children a parent.
    constraint bikesched_jobs_type_columns_check
        check ((type = 'RECURRING') = (recurrence is not null)
               and (type = 'CHILD') = (parent_job_id is not null)
               and parent_job_id <> id)
);

-- Polling index: only due-able jobs, in claim order. The name lets the claim skip jobs the node has
-- no handlers for on index entries.
create index bikesched_jobs_poll_idx on bikesched_jobs (priority desc, scheduled_at, name)
    where state in ('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING');

-- Recurring jobs (identified by their job name): at most one pending occurrence each. Finishing an
-- occurrence inserts the next one in the same transaction; registering a recurring job and the
-- node's repair insert one with "on conflict do nothing" if it is missing.
create unique index bikesched_jobs_recurring_idx on bikesched_jobs (name)
    where type = 'RECURRING' and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING');

-- Idempotency keys: scheduling a job with the key of an existing job of the same name returns that
-- job ("on conflict do nothing"); the key is taken until the job is deleted after its retention.
-- Only jobs with a key have an entry.
create unique index bikesched_jobs_idempotency_key_idx on bikesched_jobs (name, idempotency_key)
    where idempotency_key is not null;

-- Deleting ended jobs after the job retention, oldest first. Only the states that are deleted:
-- COMPENSATION_FAILED jobs also have finished_at but are kept, so they would stay at the front of
-- the index forever and every clean-up would read past them.
create index bikesched_jobs_finished_at_idx on bikesched_jobs (finished_at)
    where state in ('SUCCEEDED', 'FAILED', 'CANCELLED');

-- Finding/releasing jobs of a (dead) node; also backs the foreign key. Only RUNNING jobs have a
-- node, so the index holds only them.
create index bikesched_jobs_locked_by_node_id_idx on bikesched_jobs (locked_by_node_id)
    where locked_by_node_id is not null;

-- Pages of the admin's job list, newest first: the ids are time-ordered, so the primary key serves
-- the list of all jobs, this index the list of one name.
create index bikesched_jobs_name_id_idx on bikesched_jobs (name, id);

-- The admin's search by short id, the last 8 hex digits of the id (the random part of a UUIDv7).
create index bikesched_jobs_short_id_idx on bikesched_jobs (right(id::text, 8));

-- Steps of a job: each named step runs once per job, its result is persisted so a retried
-- job can skip steps that already succeeded. The type tells what a step does:
-- - STEP: a regular step running user code; result = what it returned.
-- - SLEEP: WAITING until wake_at has passed and the job runs again (SUCCEEDED).
-- - EVENT_WAIT: waits for the event event_name/event_key: WAITING until it is published
--   (SUCCEEDED, event_id = the received event, result = a copy of its payload, so the step does
--   not depend on the event) or wake_at has passed (TIMED_OUT).
-- - EVENT_PUBLISH: published the event event_name/event_key (event_id) exactly once; SUCCEEDED
--   when created, in the transaction that published the event.
-- - CHILD_JOB: started the child job child_job_id named child_job_name exactly once; WAITING until the child ends
--   (SUCCEEDED, child_end_state = its terminal state, result = its result, last_error = its error or
--   cancel message) or the parent's wait for it times out at wake_at (TIMED_OUT).
-- - POLL: checks an external service until it is ready; one row for all checks (attempts = the
--   number of checks). RUNNING during a check, WAITING until the next check at wake_at (at most
--   deadline_at), SUCCEEDED once ready (result = the ready value), TIMED_OUT once a check after
--   deadline_at was not ready, FAILED after a check that threw.
-- event_id only references the event (no FK): events are deleted after their retention; the same
-- for child_job_id (children are deleted after the job retention).
-- A compensable step (declared with a compensation) is COMPENSATED once its compensation ran.
-- A step whose handler postponed the job (Job.postpone) is POSTPONED, its attempt given back; the
-- next attempt starts it again.
-- Cancelling a job marks its unfinished steps (WAITING, or RUNNING after a crash) CANCELLED.
-- A RUNNING step is locked by the node running it (locked_by_node_id). Every step write also requires the
-- job to be RUNNING with the lock_token of the attempt making the write, so an attempt whose job
-- was released (e.g. after missed heartbeats) can no longer change its steps, even if the same
-- node has claimed the job again since.
-- A step is identified by its id (a UUIDv7 created by the library when the step first starts, kept
-- across its attempts), which every write to a single step uses, and by its name within its job,
-- which the handler's replay looks it up by.
create table bikesched_steps (
    id                uuid        not null,
    job_id            uuid        not null,
    name              text        not null,
    type              text        not null default 'STEP',
    state             text        not null default 'RUNNING',
    locked_by_node_id uuid,                           -- the node running the step (no FK: may outlive a dead node)
    event_id          uuid,                           -- the event received or published (no FK: see above)
    child_job_id      uuid,                           -- the child job started (no FK: see above)
    started_at        timestamptz not null default now(),
    finished_at       timestamptz,
    wake_at           timestamptz,
    deadline_at       timestamptz,                    -- the deadline of a poll, none if null
    attempts          integer     not null default 0,
    failure_attempts  integer     not null default 0, -- runs of its failure handler while FAILING
    compensable       boolean     not null default false,
    child_end_state   text,                           -- the child's terminal state, once it ended
    child_job_name    text,                           -- its name, checked on replays even after it was deleted
    event_name        text,
    event_key         text,
    result            jsonb,
    last_error        text,
    constraint bikesched_steps_pkey primary key (id),
    constraint bikesched_steps_job_id_name_key unique (job_id, name),
    constraint bikesched_steps_job_id_fkey
        foreign key (job_id) references bikesched_jobs (id) on delete cascade,
    constraint bikesched_steps_attempts_check
        check (attempts >= 0 and failure_attempts >= 0),
    -- Unfinished steps have no finished_at, ended ones have it; a compensated step keeps what it
    -- had (a child job step compensated while waiting has none).
    constraint bikesched_steps_finished_at_check
        check (case
                   when state in ('RUNNING', 'WAITING', 'FAILING') then finished_at is null
                   when state = 'COMPENSATED' then true
                   else finished_at is not null
               end),
    constraint bikesched_steps_type_check
        check (type in ('STEP', 'SLEEP', 'EVENT_WAIT', 'EVENT_PUBLISH', 'CHILD_JOB', 'POLL')),
    constraint bikesched_steps_state_check
        check (state in ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILING', 'FAILED', 'TIMED_OUT',
                         'COMPENSATED', 'CANCELLED', 'POSTPONED')),
    -- Event waits and publishes name their event; an event wait references the event once it
    -- received it (not if its failure handler resolved it after a timeout: it keeps last_error), a
    -- publish always; only event waits, child jobs and polls time out.
    constraint bikesched_steps_event_check
        check ((event_name is not null) = (type in ('EVENT_WAIT', 'EVENT_PUBLISH'))
               and (event_name is null) = (event_key is null)
               and case type
                       when 'EVENT_WAIT' then (event_id is not null) = (state = 'SUCCEEDED' and last_error is null)
                       when 'EVENT_PUBLISH' then event_id is not null
                       else event_id is null
                   end
               and (type in ('EVENT_WAIT', 'CHILD_JOB', 'POLL') or state <> 'TIMED_OUT')),
    -- Child job steps reference their child; the child's state is set once it ended (kept when the
    -- step is compensated afterwards).
    constraint bikesched_steps_child_check
        check ((child_job_id is not null) = (type = 'CHILD_JOB')
               and (child_job_name is not null) = (type = 'CHILD_JOB')
               and (child_end_state is null or type = 'CHILD_JOB' and state in ('SUCCEEDED', 'COMPENSATED'))
               and (type <> 'CHILD_JOB' or state <> 'SUCCEEDED' or child_end_state is not null)
               and child_end_state in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'COMPENSATION_FAILED')),
    -- Only sleeps, event waits, child jobs and polls wait; a sleep and a waiting poll need their
    -- wake-up time. (An event wait or a wait for a child job without a timeout waits without a
    -- deadline.) Only polls have a deadline of their own.
    constraint bikesched_steps_wait_check
        check ((state <> 'WAITING' or type in ('SLEEP', 'EVENT_WAIT', 'CHILD_JOB', 'POLL'))
               and (wake_at is null or type in ('SLEEP', 'EVENT_WAIT', 'CHILD_JOB', 'POLL'))
               and (type <> 'SLEEP' or wake_at is not null)
               and (type <> 'POLL' or state <> 'WAITING' or wake_at is not null)
               and (deadline_at is null or type = 'POLL')),
    constraint bikesched_steps_lock_check
        check ((state = 'RUNNING') = (locked_by_node_id is not null)),
    -- Only regular steps run handlers that can postpone the job.
    constraint bikesched_steps_postponed_check
        check (state <> 'POSTPONED' or type = 'STEP'),
    constraint bikesched_steps_name_check
        check (char_length(name) between 1 and 200 and octet_length(name) <= 600),
    constraint bikesched_steps_event_name_check
        check (char_length(event_name) between 1 and 200 and octet_length(event_name) <= 600),
    constraint bikesched_steps_event_key_check
        check (char_length(event_key) between 1 and 500 and octet_length(event_key) <= 1500),
    constraint bikesched_steps_child_job_name_check
        check (char_length(child_job_name) between 1 and 200 and octet_length(child_job_name) <= 600)
);

-- Matching published events to the steps waiting for them.
create index bikesched_steps_waiting_idx on bikesched_steps (event_name, event_key)
    where type = 'EVENT_WAIT' and state = 'WAITING';

-- Finding the step of a child job that ended.
create index bikesched_steps_child_job_idx on bikesched_steps (child_job_id)
    where type = 'CHILD_JOB' and state = 'WAITING';

-- Finding the steps that published or received an event (admin lookups).
create index bikesched_steps_event_id_idx on bikesched_steps (event_id)
    where event_id is not null;

-- Published events, kept until expires_at (published_at + the event's retention): a step that starts
-- waiting for an event that was already published receives the latest unexpired one right away, so
-- events published before anyone waits for them are not lost. With a retention of 0, an event only
-- reaches the steps waiting when it is published.
-- An idempotency key dedups publishing: while an event with the same name, key and idempotency key
-- is unexpired, publishing again returns it (checked under the event's advisory lock, see
-- EventStore; a partial index can't use now()).
create table bikesched_events (
    id              uuid        not null,
    published_at    timestamptz not null default now(),
    expires_at      timestamptz not null,
    name            text        not null,
    key             text        not null,
    idempotency_key text,
    payload         jsonb,
    constraint bikesched_events_pkey primary key (id),
    constraint bikesched_events_name_key_check
        check (char_length(name) between 1 and 200 and octet_length(name) <= 600
               and char_length(key) between 1 and 500 and octet_length(key) <= 1500),
    constraint bikesched_events_idempotency_key_check
        check (char_length(idempotency_key) between 1 and 500 and octet_length(idempotency_key) <= 1500),
    constraint bikesched_events_expires_at_check
        check (expires_at >= published_at)
);

create index bikesched_events_name_key_idx on bikesched_events (name, key, published_at desc);

-- Deleting expired events.
create index bikesched_events_expires_at_idx on bikesched_events (expires_at);

-- The admin's search by short id, the last 8 hex digits of the id (the random part of a UUIDv7).
create index bikesched_events_short_id_idx on bikesched_events (right(id::text, 8));

-- Job history (only written by nodes with the history enabled): one row per change of a job
-- (step_name null) or of one of its steps, in the transaction of the change. attempt is the job's
-- attempt count after the change; node_id the node that made it (null: the application, e.g.
-- scheduling, cancel requests, publishing). No foreign keys: entries are kept for their own
-- retention period, also after their job was deleted.
create table bikesched_job_history (
    job_id     uuid        not null,
    node_id    uuid,
    seq        bigint      generated always as identity,
    created_at timestamptz not null default now(),
    attempt    integer     not null,
    state      text        not null,
    step_name  text,
    detail     text,                   -- error, cancel message, deadline, ...; truncated to 4000 characters
    constraint bikesched_job_history_pkey primary key (job_id, seq),
    constraint bikesched_job_history_attempt_check
        check (attempt >= 0),
    constraint bikesched_job_history_state_check
        check (step_name is null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED',
                                               'CANCELLED', 'COMPENSATING', 'COMPENSATION_FAILED',
                                               'FAILING')
               or step_name is not null and state in ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILING',
                                                      'FAILED', 'TIMED_OUT', 'COMPENSATED',
                                                      'CANCELLED', 'POSTPONED')),
    constraint bikesched_job_history_step_name_check
        check (char_length(step_name) between 1 and 200 and octet_length(step_name) <= 600)
);

-- Deleting entries after their retention period.
create index bikesched_job_history_created_at_idx on bikesched_job_history (created_at);
