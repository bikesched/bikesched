// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.ratelimit;

import static org.awaitility.Awaitility.await;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.examples.util.DatabaseUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;

/**
 * A provider's rate limit with <a href="https://github.com/bucket4j/bucket4j">Bucket4j</a> and
 * {@code job.postpone}: the SMS provider accepts at most two messages per second, so the job's step
 * takes a token from a bucket before each call. Without a token, it postpones the job until the
 * bucket has one again; the job does not use up an attempt and records no failure. The bucket lives
 * in PostgreSQL, so the limit holds across nodes: {@link #main} starts two nodes and schedules ten
 * messages at once. Runs against the Postgres from {@code compose.yaml} ({@code docker compose up
 * -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runRateLimit}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class RateLimitExample {

  /** Sends one SMS. */
  public static final JobType<Sms> SEND_SMS = JobType.of("send-sms", Sms.class);

  /**
   * The job payload.
   *
   * @param to the recipient
   * @param text the message
   */
  public record Sms(String to, String text) {}

  /**
   * The bucket: one message every 600 ms, a little under the provider's two per second, so the time
   * between taking a token and calling the provider never pushes a third call into a second.
   */
  static final BucketConfiguration RATE_LIMIT =
      BucketConfiguration.builder()
          .addLimit(limit -> limit.capacity(1).refillGreedy(1, Duration.ofMillis(600)))
          .build();

  /** The table Bucket4j stores the bucket's state in (in the application's database). */
  static final String BUCKET_TABLE = "ratelimit_buckets";

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

  private final Bucket bucket;
  private final SimulatedSmsProvider provider;
  private final PrintStream out;

  RateLimitExample(DataSource dataSource, PrintStream out) {
    this.out = out;
    this.provider = new SimulatedSmsProvider(out);
    createBucketTable(dataSource);
    // One bucket for the provider, shared by every node through the database. Bucket4j locks its
    // row with a PostgreSQL advisory lock while it takes a token.
    this.bucket =
        Bucket4jPostgreSQL.advisoryLockBasedBuilder(dataSource)
            .primaryKeyMapper(PrimaryKeyMapper.STRING)
            .table(BUCKET_TABLE)
            .build()
            .builder()
            .build("sms-provider", () -> RATE_LIMIT);
  }

  /**
   * Registers the job handler on a node; every node sending messages shares the bucket.
   *
   * @param bikesched the node
   */
  void registerOn(Bikesched bikesched) {
    String node = bikesched.node().name();
    bikesched.registerJobHandler(
        SEND_SMS,
        (job, sms) ->
            job.step(
                "send",
                String.class,
                ignored -> {
                  // The check sits in the step, next to the call it guards: once the step
                  // succeeded, a later replay of the job neither takes a token nor sends again.
                  ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
                  if (!probe.isConsumed()) {
                    Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
                    out.printf(
                        "%s [%s] %s to %s postponed by %d ms%n",
                        timestamp(), node, sms.text(), sms.to(), wait.toMillis());
                    job.postpone(wait);
                  }
                  return provider.send(sms.to(), sms.text());
                }));
  }

  /**
   * Schedules sending a message.
   *
   * @param bikesched the node scheduling it
   * @param sms the message
   * @return the job id
   */
  static UUID send(Bikesched bikesched, Sms sms) {
    return bikesched.newJob(SEND_SMS).payload(sms).schedule();
  }

  /** The provider, e.g. to check that no call was rejected. */
  SimulatedSmsProvider provider() {
    return provider;
  }

  private static void createBucketTable(DataSource dataSource) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "create table if not exists " + BUCKET_TABLE + " (id text primary key, state bytea)");
    } catch (SQLException e) {
      throw new IllegalStateException("Creating table " + BUCKET_TABLE + " failed", e);
    }
  }

  static void main() {
    var example = new RateLimitExample(DatabaseUtil.composeDatabase(), System.out);
    try (Bikesched first = BikeschedUtil.bikesched("ratelimit-example-1");
        Bikesched second = BikeschedUtil.bikesched("ratelimit-example-2")) {
      example.registerOn(first);
      example.registerOn(second);

      List<UUID> jobIds =
          IntStream.rangeClosed(1, 10)
              .mapToObj(i -> send(first, new Sms("+49 151 0000000" + (i % 10), "sms-" + i)))
              .toList();

      for (UUID jobId : jobIds) {
        await()
            .atMost(Duration.ofSeconds(60))
            .until(() -> first.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      }
      System.out.printf(
          "%s [app]      %d messages sent in %d provider calls, %d rejected%n",
          timestamp(), jobIds.size(), example.provider().calls(), example.provider().rejected());
    }
  }
}
