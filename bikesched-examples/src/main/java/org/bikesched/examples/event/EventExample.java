package org.bikesched.examples.event;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.event.EventType;
import org.bikesched.examples.util.DatabaseUtil;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;

/**
 * One job with two steps: the first calls the {@link EventService}, which returns an id and
 * publishes an event keyed by it; the second waits for that event. Runs against the Postgres from
 * {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>{@link #scheduleJob(Duration)} decides per job: without a delay, the service publishes the
 * event right away and the wait receives it without suspending; with a delay, it publishes the
 * event that much later on another thread, and the job is suspended at the wait ({@code WAITING},
 * without a thread) until the event arrives.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:runEvent}; honors {@code POSTGRES_PORT} like the
 * compose file (default 5432).
 */
public final class EventExample {

  /** The job type: its name and payload class. */
  public static final JobType<JobPayload> JOB_TYPE = JobType.of("event-job", JobPayload.class);

  /** The event the service publishes: its name and payload class. */
  public static final EventType<EventPayload> EVENT_TYPE =
      EventType.of("event-processed", EventPayload.class);

  /**
   * The job payload.
   *
   * @param id the id sent to the service
   * @param delay how long the service waits before publishing the event; {@code null}: right away
   */
  public record JobPayload(UUID id, Duration delay) {}

  /**
   * The event payload.
   *
   * @param id the service's id of the processing, also the event's key
   * @param jobId the id from the job's payload
   */
  public record EventPayload(UUID id, UUID jobId) {}

  private final Bikesched bikesched;

  EventExample(Bikesched bikesched) {
    this.bikesched = bikesched;
    var service = new EventService(bikesched);

    // Register a job handler with two steps.
    bikesched.registerJobHandler(
        JOB_TYPE,
        (job, payload) -> {
          // Step 1: call the service, which publishes the event; the id it returns is stored as
          // the step's result, so later runs of the handler wait with the same id.
          UUID id =
              job.step(
                  "call-service", UUID.class, _ -> service.process(payload.id(), payload.delay()));

          // Step 2: wait for the event with that id. If it was already published, it is received
          // right away; otherwise (delay) the job is suspended until it is, and this handler runs
          // again from the start: step 1 returns its stored id without calling the service again.
          EventPayload event = job.waitForEvent("await-event", EVENT_TYPE, id.toString()).receive();
          System.out.println("Job " + job.id() + " received: " + event);
        });
  }

  /**
   * Schedules a job.
   *
   * @param delay how long the service waits before publishing the event; {@code null}: right away
   * @return the job id
   */
  public UUID scheduleJob(Duration delay) {
    return bikesched.newJob(JOB_TYPE).payload(new JobPayload(UUID.randomUUID(), delay)).schedule();
  }

  static void main() {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DatabaseUtil.composeDatabase())
            .jsonMapper(new JacksonJsonMapper())
            .build()) {

      var example = new EventExample(bikesched);

      // Schedule two jobs: the first one's event is published right away, the second one's after
      // 3 seconds, so that job waits for it.
      UUID immediateJobId = example.scheduleJob(null);
      UUID delayedJobId = example.scheduleJob(Duration.ofSeconds(3));

      // Wait for both jobs to end.
      for (UUID jobId : List.of(immediateJobId, delayedJobId)) {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      }
    }
  }
}
