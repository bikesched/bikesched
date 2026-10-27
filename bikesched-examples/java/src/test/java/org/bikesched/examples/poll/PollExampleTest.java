// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.poll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.poll.PollExample.Webhook;
import org.bikesched.examples.util.Database;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.StepEntry;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the poll example's job against a real PostgreSQL. */
class PollExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  @Test
  void aDeliveredWebhookNeedsNoPolling() throws SQLException {
    try (Bikesched bikesched = node()) {
      var example =
          new PollExample(bikesched, new PrintStream(output, true, StandardCharsets.UTF_8));

      UUID jobId = example.pay("order-webhook", Duration.ofSeconds(1), Webhook.DELIVERED);

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      String paymentId = paymentId(jobId);
      assertThat(example.provider().statusCalls(paymentId)).isZero();
      assertThat(pollChecks(jobId)).as("no poll step").isZero();
      assertThat(output.toString(StandardCharsets.UTF_8))
          .contains("[shop]     order order-webhook paid (" + paymentId + ", webhook)");
    }
  }

  @Test
  void aWebhookDeliveredTwiceIsPublishedOnce() throws SQLException {
    try (Bikesched bikesched = node()) {
      var example =
          new PollExample(bikesched, new PrintStream(output, true, StandardCharsets.UTF_8));

      UUID jobId =
          example.pay("order-webhook-twice", Duration.ofSeconds(1), Webhook.DELIVERED_TWICE);

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      String paymentId = paymentId(jobId);
      Pattern published = Pattern.compile("webhook (evt_\\w+) published as event ([\\w-]+)");
      await()
          .untilAsserted(
              () -> assertThat(published.matcher(output()).results().count()).isEqualTo(2));
      assertThat(published.matcher(output()).results().map(match -> match.group(2)).distinct())
          .as("both deliveries return the same event")
          .hasSize(1);
      assertThat(
              query(
                  "select count(*) from bikesched_events where name = 'payment-settled'"
                      + " and key = '"
                      + paymentId
                      + "'"))
          .isEqualTo("1");
      assertThat(output())
          .contains("[shop]     order order-webhook-twice paid (" + paymentId + ", webhook)");
    }
  }

  @Test
  void aLostWebhookIsMadeUpForByPolling() throws SQLException {
    try (Bikesched bikesched = node()) {
      var example =
          new PollExample(bikesched, new PrintStream(output, true, StandardCharsets.UTF_8));

      UUID jobId = example.pay("order-polled", Duration.ofSeconds(4), Webhook.LOST);

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      String paymentId = paymentId(jobId);
      int statusCalls = example.provider().statusCalls(paymentId);
      assertThat(statusCalls).as("pending at least once, then settled").isGreaterThanOrEqualTo(2);
      assertThat(pollChecks(jobId)).as("one check per status call").isEqualTo(statusCalls);
      List<HistoryEntry> poll =
          bikesched.findJobHistory(jobId).stream()
              .filter(entry -> entry instanceof StepEntry)
              .filter(
                  entry -> "poll-payment".equals(data(entry).getObject("step").getString("name")))
              .toList();
      assertThat(poll)
          .as("the first wait and the outcome")
          .extracting(entry -> entry.action().name())
          .containsExactly("WAITING", "SUCCEEDED");
      assertThat(data(poll.getLast()).getInt("checks")).isEqualTo(statusCalls);
      assertThat(output.toString(StandardCharsets.UTF_8))
          .contains("[provider] settled " + paymentId + ", webhook lost")
          .contains("[shop]     order order-polled paid (" + paymentId + ", polled)");
    }
  }

  private String output() {
    return output.toString(StandardCharsets.UTF_8);
  }

  /** The payment id, as stored by the job's first step. */
  private static String paymentId(UUID jobId) throws SQLException {
    return query(
        "select result #>> '{}' from bikesched_steps where job_id = '"
            + jobId
            + "' and name = 'create-payment'");
  }

  /** The number of checks of the job's poll step, 0 without one. */
  private static int pollChecks(UUID jobId) throws SQLException {
    String checks =
        query(
            "select coalesce(max(attempts), 0) from bikesched_steps where job_id = '"
                + jobId
                + "' and name = 'poll-payment'");
    return Integer.parseInt(checks);
  }

  private static String query(String sql) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static Bikesched node() {
    return BikeschedTest.testNode(DATA_SOURCE, "poll")
        .history(Duration.ofHours(1))
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }

  private static JsonObject data(HistoryEntry entry) {
    try {
      return JsonParser.object().from(entry.data());
    } catch (JsonParserException e) {
      throw new AssertionError("Not a JSON object: " + entry.data(), e);
    }
  }
}
