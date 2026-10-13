// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the publish example's jobs against a real PostgreSQL. */
class PublishExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void shippingJobPublishesOnceAndTheNotificationJobReceivesIt() throws Exception {
    try (Bikesched bikesched = node()) {
      var example = new PublishExample(bikesched);
      String orderId = "order-" + UUID.randomUUID();

      var jobs = example.placeOrder(orderId, "alice@example.com");

      for (UUID jobId : new UUID[] {jobs.shipJobId(), jobs.notifyJobId()}) {
        BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      }
      // The handler ran at least twice (before and after the sleep); every run got the same id.
      var eventIds = example.publishedEventIds();
      assertThat(eventIds).hasSizeGreaterThanOrEqualTo(2).containsOnly(eventIds.getFirst());
      assertThat(publishedEvents(orderId)).isEqualTo(1);
    }
  }

  private static int publishedEvents(String orderId) throws Exception {
    try (Connection connection = DATA_SOURCE.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from bikesched_events where name = ? and key = ?")) {
      statement.setString(1, PublishExample.ORDER_SHIPPED.name());
      statement.setString(2, orderId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private static Bikesched node() {
    return BikeschedTest.testNode(DATA_SOURCE, "publish")
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
