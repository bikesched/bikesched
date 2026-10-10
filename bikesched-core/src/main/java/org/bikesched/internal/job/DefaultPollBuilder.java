// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.RetryBackoff;
import org.bikesched.job.PollBuilder;
import org.bikesched.job.PollHandler;
import org.bikesched.job.StepFailureHandler;

/** The {@link PollBuilder} implementation, running the poll on its {@link DefaultJob}. */
final class DefaultPollBuilder<T> implements PollBuilder<T> {

  private final DefaultJob job;
  private final String name;
  private final Class<T> resultType;
  private final RetryBackoff interval;
  private Duration timeout;
  private StepFailureHandler<T> failureHandler;

  DefaultPollBuilder(DefaultJob job, String name, Class<T> resultType, RetryBackoff interval) {
    this.job = job;
    this.name = name;
    this.resultType = resultType;
    this.interval = interval;
  }

  @Override
  public PollBuilder<T> timeout(Duration timeout) {
    this.timeout = requirePositive(timeout);
    return this;
  }

  @Override
  public PollBuilder<T> onFailure(StepFailureHandler<T> handler) {
    this.failureHandler = Objects.requireNonNull(handler, "handler");
    return this;
  }

  private static Duration requirePositive(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive: " + timeout);
    }
    return timeout;
  }

  @Override
  public T run(PollHandler<T> handler) throws Exception {
    Objects.requireNonNull(handler, "handler");
    return job.poll(name, resultType, interval, timeout, failureHandler, handler);
  }
}
