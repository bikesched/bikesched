// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * A Spring Boot application: {@code bikesched-spring-boot-starter} wires the {@link Bikesched} node
 * from the {@code DataSource}, discovers the {@link OrderService} handlers and polls with the
 * application. The runner places two orders, one of them out of stock, and closes the context once
 * their jobs ended.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:spring:bootRun}; the {@code DataSource} comes
 * from {@code spring.datasource.*} in {@code application.properties}, which honors {@code
 * POSTGRES_PORT} like the compose file (default 5432).
 */
@SpringBootApplication
public class SpringExampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpringExampleApplication.class, args);
  }

  /**
   * Places an order that succeeds and one whose item is out of stock, waits for their jobs to end,
   * then closes the application.
   */
  @Bean
  ApplicationRunner run(
      OrderService orders, Bikesched bikesched, ConfigurableApplicationContext context) {
    return ignored -> {
      UUID bike = orders.placeOrder("alice@example.com", "Bike");
      UUID unicorn = orders.placeOrder("bob@example.com", "Unicorn");
      for (UUID jobId : List.of(bike, unicorn)) {
        JobState state =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
        System.out.println("Order job " + jobId + " " + state);
      }
      context.close();
    };
  }
}
