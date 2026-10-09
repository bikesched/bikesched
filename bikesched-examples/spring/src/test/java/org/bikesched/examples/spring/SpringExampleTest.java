// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the Spring Boot application against a PostgreSQL 18 container (Testcontainers): the {@link
 * SpringExampleApplication} runner places an order, waits for its job to succeed and closes the
 * context.
 */
class SpringExampleTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  static {
    POSTGRES.start();
  }

  @Test
  void fulfillsAnOrderEndToEnd() throws Exception {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SpringExampleApplication.class)
            // Command-line arguments override application.properties (builder properties don't):
            // the container instead of the compose database.
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.jpa.hibernate.ddl-auto=create",
                "--bikesched.node-name=spring-test-" + UUID.randomUUID())) {
      assertThat(context.isActive()).isFalse(); // the runner closed it once the job succeeded
    }
    // The order is marked fulfilled, and its fulfillment was stored.
    assertThat(count("orders", "where fulfilled = true")).isOne();
    assertThat(count("fulfillments", "")).isOne();
  }

  private static long count(String table, String where) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
