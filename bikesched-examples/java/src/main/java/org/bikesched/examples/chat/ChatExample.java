// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.chat;

import static org.awaitility.Awaitility.await;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.TypedJob;

/**
 * Deduplicating incoming messages with idempotency keys: a chat platform delivers each message to
 * the application's webhook at least once, and the webhook schedules a job answering it with the
 * message id as idempotency key. A redelivered message, also one arriving concurrently, returns the
 * job scheduled first, so the bot answers once. Runs against the Postgres from {@code compose.yaml}
 * ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runChat}; honors {@code POSTGRES_PORT} like
 * the compose file (default 5432).
 */
public final class ChatExample {

  /** Answers one chat message. */
  public static final TypedJob<ChatMessage> ANSWER_MESSAGE =
      TypedJob.of("answer-chat-message", ChatMessage.class);

  /**
   * A message posted in a chat channel, as the platform's webhook sends it.
   *
   * @param messageId the platform's id of the message, the same in every delivery
   * @param channel the channel
   * @param user who posted it
   * @param text the message
   */
  public record ChatMessage(String messageId, String channel, String user, String text) {}

  /** How the platform delivers a message (simulated). */
  public enum Delivery {
    /** Delivered once. */
    ONCE,
    /** Delivered again later, as platforms do when the first delivery's response was slow. */
    REDELIVERED,
    /** Delivered twice at the same time. */
    CONCURRENTLY
  }

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

  private final Bikesched bikesched;
  private final SimulatedChatPlatform platform;
  private final PrintStream out;

  ChatExample(Bikesched bikesched, PrintStream out) {
    this.bikesched = bikesched;
    this.out = out;
    this.platform = new SimulatedChatPlatform(out);

    bikesched.registerJobHandler(
        ANSWER_MESSAGE,
        (job, message) ->
            job.step(
                "reply",
                () ->
                    platform.reply(
                        message.channel(),
                        message.messageId(),
                        "@" + message.user() + " thanks, a human will get back to you")));
  }

  /**
   * The application's webhook endpoint, called by the platform once per delivery.
   *
   * @param message the delivered message
   * @return the id of the job answering it, the same for every delivery of the message
   */
  public UUID receive(ChatMessage message) {
    UUID jobId =
        bikesched
            .newJob(ANSWER_MESSAGE)
            .payload(message)
            .idempotencyKey(message.messageId()) // a redelivery returns the first job's id
            .schedule();
    out.printf(
        "%s [webhook]  message %s scheduled as job %s%n", timestamp(), message.messageId(), jobId);
    return jobId;
  }

  /**
   * Delivers a message the way the platform does (simulated).
   *
   * @param message the message
   * @param delivery how it is delivered
   * @return the job id returned for each delivery
   */
  public List<UUID> deliver(ChatMessage message, Delivery delivery) {
    return switch (delivery) {
      case ONCE -> List.of(receive(message));
      case REDELIVERED -> {
        UUID first = receive(message);
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> bikesched.findJob(first).orElseThrow().state(), JobState::isEnded);
        yield List.of(first, receive(message)); // after the job has answered
      }
      case CONCURRENTLY -> {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          var first = CompletableFuture.supplyAsync(() -> receive(message), executor);
          var second = CompletableFuture.supplyAsync(() -> receive(message), executor);
          yield List.of(first.join(), second.join());
        }
      }
    };
  }

  /** The platform, e.g. to count the replies to a message. */
  SimulatedChatPlatform platform() {
    return platform;
  }

  /**
   * A new message in {@code #support}. Its id is new on every run: a key stays taken while its job
   * exists (the job retention), so a fixed id would return the jobs of an earlier run.
   *
   * @param user who posts it
   * @param text the message
   * @return the message
   */
  static ChatMessage message(String user, String text) {
    return new ChatMessage("msg-" + UUID.randomUUID(), "#support", user, text);
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("chat-example")) {
      var example = new ChatExample(bikesched, System.out);

      List<UUID> jobIds =
          List.of(
                  example.deliver(message("alice", "hi"), Delivery.ONCE),
                  example.deliver(message("bob", "help"), Delivery.REDELIVERED),
                  example.deliver(message("carol", "hello?"), Delivery.CONCURRENTLY))
              .stream()
              .flatMap(List::stream)
              .distinct()
              .toList();

      for (UUID jobId : jobIds) {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      }
      System.out.printf(
          "%s [app]      5 deliveries, %d jobs, %d replies%n",
          timestamp(), jobIds.size(), example.platform().replies());
    }
  }
}
