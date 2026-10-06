// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;
import org.bikesched.micrometer.MicrometerJobListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Composition of {@link JobListener} beans and the automatic {@link MicrometerJobListener}. */
class ListenersAndMetricsTest extends AbstractSpringTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void composesMultipleJobListeners() {
    CountingListener first = new CountingListener();
    CountingListener second = new CountingListener();
    String type = "listener-job-" + UUID.randomUUID();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("first", JobListener.class, () -> first)
        .withBean("second", JobListener.class, () -> second)
        .withPropertyValues("bikesched.node-name=" + unique("listener-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              node.registerJobHandler(type, _ -> {});
              UUID jobId = node.newJob(type).schedule();
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
              assertThat(first.claimed.get()).isOne();
              assertThat(first.ended.get()).isOne();
              assertThat(second.claimed.get()).isOne();
              assertThat(second.ended.get()).isOne();
            });
  }

  @Test
  void createsAMicrometerListenerFromTheRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    String nodeName = unique("metrics-node");
    String type = "metrics-job-" + UUID.randomUUID();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean(MeterRegistry.class, () -> registry)
        .withPropertyValues("bikesched.node-name=" + nodeName)
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              node.registerJobHandler(type, _ -> {});
              UUID jobId = node.newJob(type).schedule();
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
              assertThat(registry.find("bikesched.job.attempts").tags("node", nodeName).timer())
                  .isNotNull();
              assertThat(
                      registry
                          .find("bikesched.job.attempts")
                          .tags("node", nodeName)
                          .timer()
                          .count())
                  .isOne();
            });
  }

  @Test
  void canDisableTheMicrometerListener() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    String type = "metrics-off-job-" + UUID.randomUUID();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean(MeterRegistry.class, () -> registry)
        .withPropertyValues(
            "bikesched.metrics.enabled=false", "bikesched.node-name=" + unique("metrics-off-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              node.registerJobHandler(type, _ -> {});
              UUID jobId = node.newJob(type).schedule();
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
              assertThat(registry.getMeters()).isEmpty();
            });
  }

  @Test
  void noMeterRegistryMeansNoMicrometerListener() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.node-name=" + unique("no-metrics-node"))
        .run(context -> assertThat(context).doesNotHaveBean(MicrometerJobListener.class));
  }

  @Test
  void aUserDefinedMicrometerListenerWins() {
    MicrometerJobListener user = new MicrometerJobListener(new SimpleMeterRegistry());
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean("micrometerJobListener", MicrometerJobListener.class, () -> user)
        .withPropertyValues("bikesched.node-name=" + unique("user-metrics-node"))
        .run(context -> assertThat(context.getBean(MicrometerJobListener.class)).isSameAs(user));
  }

  private static final class CountingListener implements JobListener {

    final AtomicInteger claimed = new AtomicInteger();
    final AtomicInteger ended = new AtomicInteger();

    @Override
    public void onJobClaimed(JobClaim claim) {
      claimed.incrementAndGet();
    }

    @Override
    public void onAttemptEnded(JobAttemptResult result) {
      ended.incrementAndGet();
    }
  }
}
