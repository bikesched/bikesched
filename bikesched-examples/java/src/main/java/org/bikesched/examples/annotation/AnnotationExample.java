// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.annotation;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.annotation.JobHandlers;
import org.bikesched.examples.annotation.MailJobs.Mail;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.TypedJob;

/**
 * Job handlers declared with {@code @JobHandler} (module {@code bikesched-annotations}): {@link
 * JobHandlers#register(Bikesched, Object)} registers the annotated methods of {@link MailJobs}.
 * Runs against the Postgres from {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runAnnotation}; honors {@code
 * POSTGRES_PORT} like the compose file (default 5432).
 */
public final class AnnotationExample {

  private final Bikesched bikesched;

  AnnotationExample(Bikesched bikesched) {
    this.bikesched = bikesched;

    // Register the annotated methods as job handlers.
    JobHandlers.register(bikesched, new MailJobs());
  }

  /** Schedules one job for each handler. */
  public List<UUID> scheduleJobs() {
    var mail = new Mail("alice@example.com", "Welcome");
    return List.of(
        bikesched.newJob(TypedJob.of(MailJobs.SEND_MAIL, Mail.class)).payload(mail).schedule(),
        bikesched.newJob(TypedJob.of(MailJobs.ARCHIVE_MAIL, Mail.class)).payload(mail).schedule(),
        bikesched.newJob(MailJobs.CHECK_MAILBOX).schedule());
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("annotation-example")) {

      // Schedule the jobs.
      List<UUID> jobIds = new AnnotationExample(bikesched).scheduleJobs();

      // Wait for the jobs to end.
      for (UUID jobId : jobIds) {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      }
    }
  }
}
