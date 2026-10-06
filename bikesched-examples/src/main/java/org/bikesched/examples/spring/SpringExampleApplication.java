// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.DatabaseUtil;
import org.bikesched.job.JobState;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * A Spring Boot application: {@code bikesched-spring-boot-starter} wires the {@link Bikesched} node
 * from the {@code DataSource}, discovers the {@link OrderService} handler and polls with the
 * application. The runner places one order and closes the context once its job ended.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:runSpring}; honors {@code POSTGRES_PORT} like
 * the compose file (default 5432).
 */
@SpringBootApplication
public class SpringExampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpringExampleApplication.class, args);
  }

  /**
   * The compose database, like every other example (see {@link DatabaseUtil#composeDatabase()}).
   */
  @Bean
  DataSource dataSource() {
    return DatabaseUtil.composeDatabase();
  }

  /** Places one order, waits for its job to succeed, then closes the application. */
  @Bean
  ApplicationRunner run(
      OrderService orders, Bikesched bikesched, ConfigurableApplicationContext context) {
    return _ -> {
      UUID jobId = orders.placeOrder("alice@example.com", "Bike");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> bikesched.findJob(jobId).orElseThrow().state(),
              state -> state == JobState.SUCCEEDED);
      System.out.println("Order job " + jobId + " " + JobState.SUCCEEDED);
      context.close();
    };
  }
}
