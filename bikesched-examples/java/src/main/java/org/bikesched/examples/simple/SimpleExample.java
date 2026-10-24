// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.simple;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.TypedJob;

/**
 * The smallest bikesched setup: registers one job handler and schedules one job, against the
 * Postgres from {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runSimple}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class SimpleExample {

  /** The typed job: its name and payload class. */
  public static final TypedJob<JobPayload> JOB_NAME = TypedJob.of("simple-job", JobPayload.class);

  /** The job payload: a simple record with a UUID. */
  public record JobPayload(UUID id) {}

  private final Bikesched bikesched;

  SimpleExample(Bikesched bikesched) {
    this.bikesched = bikesched;

    // Register a job handler.
    bikesched.registerJobHandler(
        JOB_NAME, (job, payload) -> System.out.println("Job " + job.id() + ": " + payload));
  }

  public UUID scheduleJob() {
    return bikesched.newJob(JOB_NAME).payload(new JobPayload(UUID.randomUUID())).schedule();
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("simple-example")) {

      // Schedule a job.
      UUID jobId = new SimpleExample(bikesched).scheduleJob();

      // Wait for the job to end.
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
    }
  }
}
