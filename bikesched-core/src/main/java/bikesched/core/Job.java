package bikesched.core;

import java.time.Instant;
import java.util.UUID;

/**
 * A job claimed from {@code bikesched_jobs} for execution on this node.
 *
 * @param id the job id
 * @param type identifies the handler that executes the job
 * @param payload the job payload as JSON text, may be {@code null}
 * @param priority higher priorities are claimed first
 * @param scheduledAt the earliest time the job may run
 * @param attempts number of attempts including the current one
 * @param maxAttempts maximum number of attempts
 */
public record Job(
        UUID id, String type, String payload, int priority, Instant scheduledAt, int attempts, int maxAttempts) {}
