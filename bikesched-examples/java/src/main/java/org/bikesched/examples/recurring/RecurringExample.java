// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.recurring;

import static org.awaitility.Awaitility.await;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.Recurrence;

/**
 * Periodic work with a recurring job: every node registers the same recurring clean-up on startup,
 * and each run happens once across all nodes, on whichever node claims it. No leader election and
 * no extra lock: bikesched keeps exactly one occurrence pending. Runs against the Postgres from
 * {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runRecurring}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class RecurringExample {

  /** The recurring job's type. */
  public static final String CLEAN_UP_SESSIONS = "clean-up-sessions";

  /** How often the clean-up runs (hours or days in practice). */
  static final Recurrence EVERY_SECOND = Recurrence.every(Duration.ofSeconds(1));

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

  /**
   * The current time, formatted for the example's console output.
   *
   * @return the formatted time
   */
  static String timestamp() {
    return LocalTime.now().format(TIMESTAMP_FORMAT);
  }

  private final PrintStream out;
  private final AtomicInteger runs = new AtomicInteger();

  RecurringExample(PrintStream out) {
    this.out = out;
  }

  /**
   * Registers the recurring job on a node, as every node does on startup. Registering is
   * idempotent: it creates the first occurrence only if none is pending.
   *
   * @param bikesched the node
   */
  void registerOn(Bikesched bikesched) {
    String node = bikesched.node().name();
    bikesched
        .newJob(CLEAN_UP_SESSIONS)
        .handler(
            _ ->
                out.printf(
                    "%s [%s] run %d: removed expired sessions%n",
                    timestamp(), node, runs.incrementAndGet()))
        .schedule(EVERY_SECOND);
  }

  /** The number of runs on all nodes. */
  int runs() {
    return runs.get();
  }

  static void main() {
    var example = new RecurringExample(System.out);
    try (Bikesched first = BikeschedUtil.bikesched("recurring-example-1");
        Bikesched second = BikeschedUtil.bikesched("recurring-example-2")) {
      example.registerOn(first);
      example.registerOn(second);

      await().atMost(Duration.ofSeconds(30)).until(() -> example.runs() >= 5);
    }
    // The pending occurrence stays: the next start registers the job again and continues with it
    // (runs missed meanwhile are skipped).
  }
}
