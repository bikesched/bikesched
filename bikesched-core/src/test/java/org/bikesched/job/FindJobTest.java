package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;

/** Looking up jobs via {@link Bikesched#findJob(UUID)}. */
class FindJobTest extends AbstractBikeschedTest {

  @Test
  void findsAJobWithItsCurrentState() throws Exception {
    try (Bikesched bikesched = testNode("node-find-job").build()) {
      UUID later = bikesched.newJob("find-later").handler(() -> {}).schedule(Duration.ofHours(1));
      UUID now = bikesched.newJob("find-now").handler(() -> {}).schedule();

      assertThat(bikesched.findJob(later))
          .hasValueSatisfying(
              job -> {
                assertThat(job.id()).isEqualTo(later);
                assertThat(job.type()).isEqualTo("find-later");
                assertThat(job.state()).isEqualTo(JobState.SCHEDULED);
                assertThat(job.state().isEnded()).isFalse();
              });
      await()
          .untilAsserted(
              () ->
                  assertThat(bikesched.findJob(now))
                      .map(JobInfo::state)
                      .contains(JobState.SUCCEEDED));

      bikesched.cancel(later);
      await()
          .untilAsserted(
              () ->
                  assertThat(bikesched.findJob(later))
                      .map(JobInfo::state)
                      .contains(JobState.CANCELLED));
      assertThat(JobState.CANCELLED.isEnded()).isTrue();
    }
  }

  @Test
  void unknownJobIsEmpty() {
    try (Bikesched bikesched = testNode("node-find-unknown").build()) {
      assertThat(bikesched.findJob(UUID.randomUUID())).isEmpty();
    }
  }
}
