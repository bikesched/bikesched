// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.examples.util.Database;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Runs the Spring Boot application against the shared Testcontainers database: the {@link
 * SpringExampleApplication} runner places an order, waits for its job to succeed and closes the
 * context.
 */
class SpringExampleTest {

  @Test
  void fulfillsAnOrderEndToEnd() throws Exception {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SpringExampleApplication.class)
            .sources(TestConfiguration.class)
            .properties(
                "spring.jpa.hibernate.ddl-auto=create",
                "bikesched.node-name=spring-test-" + UUID.randomUUID())
            .run()) {
      assertThat(context.isActive()).isFalse(); // the runner closed it once the job succeeded
    }
    // The order is marked fulfilled, and its fulfillment was stored.
    assertThat(count("orders", "where fulfilled = true")).isOne();
    assertThat(count("fulfillments", "")).isOne();
  }

  @Configuration
  static class TestConfiguration {
    /** The shared Testcontainers database, preferred over the application's compose one. */
    @Bean
    @Primary
    DataSource testDataSource() {
      return Database.dataSource();
    }
  }

  private static long count(String table, String where) throws Exception {
    try (var connection = Database.dataSource().getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
