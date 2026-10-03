package bikesched.core;

/**
 * Executes a step of a job, see {@link Job#step(String, Class, StepHandler)}.
 *
 * @param <T> the type of the step result
 */
@FunctionalInterface
public interface StepHandler<T> {

    /**
     * Executes the step. Only called if the step has not completed in an earlier attempt of the
     * job.
     *
     * @param step the step being executed
     * @return the step result, stored durably (as JSON) and returned on later attempts of the job
     *     instead of executing the step again; may be {@code null}
     * @throws Exception if the step failed; it is executed again in the job's next attempt
     */
    T run(Step step) throws Exception;
}
