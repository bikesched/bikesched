// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.junit.jupiter.api.Test;

/** Serves the admin next to the demo jobs: the page and the job list answer. */
class AdminExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void servesThePageAndTheDemoJobs() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("admin-" + UUID.randomUUID())
            .pollInterval(Duration.ofMillis(50))
            .jsonMapper(new JacksonJsonMapper())
            .build()) {
      var example = new AdminExample(bikesched);
      example.seed();
      var server = example.serve(0);
      try (HttpClient client = HttpClient.newHttpClient()) {
        String base = "http://localhost:" + server.getAddress().getPort() + "/bikesched/";
        HttpResponse<String> page =
            client.send(
                HttpRequest.newBuilder(URI.create(base)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("bikesched admin");

        await()
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(
                () ->
                    assertThat(
                            client
                                .send(
                                    HttpRequest.newBuilder(
                                            URI.create(
                                                base + "api/jobs?type=send-invoice&state=FAILED"))
                                        .build(),
                                    HttpResponse.BodyHandlers.ofString())
                                .body())
                        .contains("SMTP timeout while sending invoice 4"));
      } finally {
        server.stop(0);
      }
    }
  }
}
