// SPDX-License-Identifier: Apache-2.0
package org.bikesched.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;

/**
 * Records the jobs a node runs as Micrometer meters:
 *
 * <pre>{@code
 * Bikesched.builder()
 *     .dataSource(dataSource)
 *     .jobListener(new MicrometerJobListener(registry, Tags.of("node", "node-1")))
 *     .build();
 * }</pre>
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Name</th><th>Type</th><th>Tags</th><th>Measures</th></tr>
 *   <tr><td>{@code bikesched.job.claim.delay}</td><td>timer</td><td>{@code job.name}</td>
 *       <td>how long after they were due jobs were claimed</td></tr>
 *   <tr><td>{@code bikesched.job.attempts}</td><td>timer</td><td>{@code job.name}, {@code
 *       outcome}</td><td>attempts by recorded outcome (lower case, e.g. {@code succeeded}, {@code
 *       retrying}, {@code failed}) and their duration</td></tr>
 *   <tr><td>{@code bikesched.job.attempts.abandoned}</td><td>counter</td><td>{@code
 *       job.name}</td><td>attempts abandoned by the hard timeout</td></tr>
 *   <tr><td>{@code bikesched.jobs.active}</td><td>gauge</td><td></td><td>attempts being
 *       processed</td></tr>
 *   <tr><td>{@code bikesched.jobs.abandoned}</td><td>gauge</td><td></td><td>abandoned
 *       attempts whose thread is still running</td></tr>
 * </table>
 *
 * <p>All meters also carry the tags given to the constructor. Use one listener per node; give nodes
 * sharing a registry a distinguishing tag (e.g. the node name), otherwise their gauges collide.
 */
public final class MicrometerJobListener implements JobListener {

  private final MeterRegistry registry;
  private final Tags tags;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger abandoned = new AtomicInteger();

  /**
   * Records the meters without additional tags.
   *
   * @param registry the registry
   */
  public MicrometerJobListener(MeterRegistry registry) {
    this(registry, Tags.empty());
  }

  /**
   * Records the meters with additional tags.
   *
   * @param registry the registry
   * @param tags tags added to every meter, e.g. the node name
   */
  public MicrometerJobListener(MeterRegistry registry, Iterable<Tag> tags) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.tags = Tags.of(Objects.requireNonNull(tags, "tags"));
    Gauge.builder("bikesched.jobs.active", active, AtomicInteger::get)
        .description("Job attempts being processed")
        .tags(this.tags)
        .register(registry);
    Gauge.builder("bikesched.jobs.abandoned", abandoned, AtomicInteger::get)
        .description("Job attempts abandoned by the hard timeout whose thread is still running")
        .tags(this.tags)
        .register(registry);
  }

  @Override
  public void onJobClaimed(JobClaim claim) {
    active.incrementAndGet();
    Timer.builder("bikesched.job.claim.delay")
        .description("How long after they were due jobs were claimed")
        .tags(tags)
        .tag("job.name", claim.jobName())
        .register(registry)
        .record(claim.delay());
  }

  @Override
  public void onAttemptEnded(JobAttemptResult result) {
    active.decrementAndGet();
    String name = result.claim().jobName();
    Timer.builder("bikesched.job.attempts")
        .description("Job attempts by recorded outcome")
        .tags(tags)
        .tag("job.name", name)
        .tag("outcome", result.outcome().name().toLowerCase(Locale.ROOT))
        .register(registry)
        .record(result.duration());
    if (result.isAbandoned()) {
      abandoned.incrementAndGet();
      Counter.builder("bikesched.job.attempts.abandoned")
          .description("Job attempts abandoned by the hard timeout")
          .tags(tags)
          .tag("job.name", name)
          .register(registry)
          .increment();
    }
  }

  @Override
  public void onAbandonedAttemptReturned(JobClaim claim) {
    abandoned.decrementAndGet();
  }
}
