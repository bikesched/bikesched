package bikesched.core;

import java.util.UUID;

/**
 * A named step of a job, passed to the {@link StepHandler} executing it via {@link Job#step(String,
 * Class, StepHandler)}.
 *
 * @param jobId the id of the job the step belongs to
 * @param name the step name, unique within the job
 * @param attempts number of attempts of this step including the current one
 */
public record Step(UUID jobId, String name, int attempts) {}
