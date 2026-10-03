package bikesched.core.internal;

import bikesched.core.job.Compensation;
import bikesched.core.job.StepBuilder;
import bikesched.core.job.StepHandler;
import java.util.Objects;

/** The {@link StepBuilder} implementation, running the step on its {@link DefaultJob}. */
final class DefaultStepBuilder<T> implements StepBuilder<T> {

  private final DefaultJob job;
  private final String name;
  private final Class<T> resultType;
  private Compensation<? super T> compensation;

  DefaultStepBuilder(DefaultJob job, String name, Class<T> resultType) {
    this.job = job;
    this.name = name;
    this.resultType = resultType;
  }

  @Override
  public StepBuilder<T> compensateWith(Compensation<? super T> compensation) {
    this.compensation = Objects.requireNonNull(compensation, "compensation");
    return this;
  }

  @Override
  public T run(StepHandler<T> handler) throws Exception {
    return job.step(name, resultType, handler, compensation);
  }
}
