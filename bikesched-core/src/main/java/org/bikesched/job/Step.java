package org.bikesched.job;

import java.util.UUID;

/**
 * A named step of a job, passed to the {@link StepHandler} executing it via {@link Job#step(String,
 * Class, StepHandler)} and to the {@link CompensationHandler} undoing it.
 *
 * @param jobId the id of the job the step belongs to
 * @param name the step name, unique within the job
 * @param attempts number of attempts of this step including the current one; when compensating, the
 *     number of attempts the step took
 */
public record Step(UUID jobId, String name, int attempts) {}
