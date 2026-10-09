// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.cinema;

import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;

/**
 * Orders cinema tickets against the Postgres from {@code compose.yaml} ({@code docker compose up
 * -d}): registers the {@link TicketOrderHandler} on its own, orders tickets for two customers and
 * waits until both orders are done.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runCinema}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class CinemaExample {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private CinemaExample() {}

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("cinema-example")) {

      // The handler carries its job type: no separate type argument.
      bikesched.registerJobHandler(new TicketOrderHandler());

      // Ordering uses the shared job type constant, not the handler class.
      UUID alice =
          bikesched
              .newJob(CinemaJobs.TICKET_ORDER)
              .payload(
                  order("alice@example.com", "Metropolis", "2026-10-09 20:15", List.of("F7", "F8")))
              .schedule();
      UUID bob =
          bikesched
              .newJob(CinemaJobs.TICKET_ORDER)
              .payload(order("bob@example.com", "Casablanca", "2026-10-10 18:00", List.of("C3")))
              .schedule();
      System.out.println("[app]     ordered tickets: jobs " + alice + " and " + bob);

      awaitEnded(bikesched, alice);
      awaitEnded(bikesched, bob);
    }
  }

  private static TicketOrder order(
      String email, String movie, String showtime, List<String> seats) {
    return new TicketOrder(
        UUID.randomUUID().toString(), email, movie, showtime, seats, new BigDecimal("12.50"));
  }

  /** Waits until the job has ended (polling {@link Bikesched#findJob}), then prints its state. */
  private static void awaitEnded(Bikesched bikesched, UUID jobId) {
    JobState state =
        await("job " + jobId + " ends")
            .atMost(TIMEOUT)
            .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
    System.out.println("[app]     job " + jobId + " ended " + state);
  }
}
