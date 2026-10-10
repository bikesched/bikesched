// SPDX-License-Identifier: Apache-2.0
package org.bikesched.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Records as payloads, step results and event payloads on a node using {@link JacksonJsonMapper},
 * stored as JSON in PostgreSQL.
 */
class JacksonJobTest {

  record Order(String id, int amount) {}

  record Payment(String orderId, int amount, String reference) {}

  record Receipt(String orderId, String reference) {}

  /** One container for this test class; Ryuk removes it when the JVM ends. */
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
    Awaitility.setDefaultTimeout(Duration.ofSeconds(15));
    Awaitility.setDefaultPollInterval(Duration.ofMillis(100));
  }

  @Test
  void recordsTravelThroughPayloadsStepsAndEvents() throws Exception {
    var receipt = new CompletableFuture<Receipt>();
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("jackson-" + UUID.randomUUID())
            .pollInterval(Duration.ofMillis(50))
            .jsonMapper(new JacksonJsonMapper())
            .build()) {
      bikesched.registerJobHandler(
          "order",
          job -> {
            Order order = job.payload(Order.class);
            Payment payment =
                job.waitForEvent("await-payment", "payment-received", order.id(), Payment.class)
                    .receive();
            Receipt stored =
                job.step(
                    "create-receipt",
                    Receipt.class,
                    _ -> new Receipt(order.id(), payment.reference()));
            receipt.complete(stored);
          });

      UUID jobId = bikesched.newJob("order").payload(new Order("o-1", 100)).schedule();
      await().untilAsserted(() -> assertThat(jobState(jobId)).isEqualTo("WAITING"));

      bikesched
          .newEvent("payment-received", "o-1")
          .payload(new Payment("o-1", 100, "pay-7"))
          .publish();

      assertThat(receipt.get(15, TimeUnit.SECONDS)).isEqualTo(new Receipt("o-1", "pay-7"));
      await().untilAsserted(() -> assertThat(jobState(jobId)).isEqualTo("SUCCEEDED"));
    }

    // Stored as jsonb (which orders keys by length): the records' components as JSON properties.
    assertThat(query("select payload::text from bikesched_jobs where type = 'order'"))
        .isEqualTo("{\"id\": \"o-1\", \"amount\": 100}");
    assertThat(query("select result::text from bikesched_steps where name = 'create-receipt'"))
        .isEqualTo("{\"orderId\": \"o-1\", \"reference\": \"pay-7\"}");
  }

  private static String jobState(UUID jobId) throws Exception {
    return query("select state from bikesched_jobs where id = '" + jobId + "'");
  }

  private static String query(String sql) throws Exception {
    try (Connection connection = DATA_SOURCE.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }
}
