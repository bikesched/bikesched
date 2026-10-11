// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.chat.ChatExample.ChatMessage;
import org.bikesched.examples.chat.ChatExample.Delivery;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs the chat example's job against a real PostgreSQL. */
class ChatExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  @ParameterizedTest
  @EnumSource(Delivery.class)
  void answersEachMessageOnce(Delivery delivery) throws SQLException {
    try (Bikesched bikesched = node()) {
      var example =
          new ChatExample(bikesched, new PrintStream(output, true, StandardCharsets.UTF_8));
      ChatMessage message = ChatExample.message("alice", "hi");

      List<UUID> jobIds = example.deliver(message, delivery);

      assertThat(jobIds.stream().distinct()).as("every delivery returns the same job").hasSize(1);
      UUID jobId = jobIds.getFirst();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      assertThat(bikesched.findJob(jobId).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
      assertThat(example.platform().replies(message.messageId())).isEqualTo(1);
      assertThat(jobsWithKey(message.messageId())).isEqualTo(1);
    }
  }

  private static int jobsWithKey(String key) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement =
            connection.prepareStatement(
                "select count(*) from bikesched_jobs"
                    + " where type = 'answer-chat-message' and idempotency_key = ?")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static Bikesched node() {
    return Bikesched.builder()
        .dataSource(DATA_SOURCE)
        .nodeName("chat-" + UUID.randomUUID())
        .pollInterval(Duration.ofMillis(50))
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
