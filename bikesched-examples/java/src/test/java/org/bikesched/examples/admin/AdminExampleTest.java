// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Serves the admin next to the demo jobs: the page and the job list answer. */
class AdminExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void servesThePageAndTheDemoJobs() throws Exception {
    try (Bikesched bikesched =
        BikeschedTest.testNode(DATA_SOURCE, "admin")
            .jsonMapper(new JacksonJsonMapper())
            .jobHistory(AdminExample.JOB_HISTORY)
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

        String failed =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(
                    () -> get(client, base + "api/jobs?type=send-invoice&state=FAILED"),
                    body -> body.contains("SMTP timeout while sending invoice 4"));

        // The node records the job history, so a failed invoice's detail has its entries.
        Matcher id = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(failed);
        assertThat(id.find()).isTrue();
        assertThat(get(client, base + "api/jobs/" + id.group(1))).contains("\"history\":[{");

        // The inventory sync's first run is now and completes both steps.
        String synced =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(
                    () ->
                        get(
                            client,
                            base
                                + "api/jobs?type="
                                + AdminExample.SYNC_INVENTORY
                                + "&state=SUCCEEDED"),
                    body -> body.contains("\"state\":\"SUCCEEDED\""));
        Matcher syncId = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(synced);
        assertThat(syncId.find()).isTrue();
        assertThat(get(client, base + "api/jobs/" + syncId.group(1)))
            .contains("\"name\":\"count-stock\"", "\"name\":\"update-shop\"");
      } finally {
        server.stop(0);
        bikesched.removeRecurringJob(AdminExample.SYNC_INVENTORY);
      }
    }
  }

  private static String get(HttpClient client, String uri) throws Exception {
    return client
        .send(HttpRequest.newBuilder(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofString())
        .body();
  }
}
