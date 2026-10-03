package bikesched.core.job;

/**
 * Undoes a completed step when its job is cancelled or fails permanently, see {@link
 * StepBuilder#compensateWith(Compensation)}.
 *
 * @param <T> the type of the step result
 */
@FunctionalInterface
public interface Compensation<T> {

  /**
   * Undoes the step, e.g. releases a reserved payment. Runs at least once per completed step, so it
   * must be idempotent: if the node dies before the compensation is recorded, it runs again.
   *
   * @param result the step's stored result, may be {@code null}
   * @throws Exception if the compensation failed; it runs again in the job's next compensation
   *     attempt
   */
  void compensate(T result) throws Exception;
}
