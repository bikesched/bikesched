// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
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
            .history(AdminExample.HISTORY)
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

        JsonObject failed =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(
                    () ->
                        get(client, base + "api/jobs?name=send-invoice&state=FAILED")
                            .getArray("jobs")
                            .stream()
                            .map(JsonObject.class::cast)
                            .filter(
                                job ->
                                    job.getString("lastError", "")
                                        .contains("SMTP timeout while sending invoice 4"))
                            .findFirst(),
                    Optional::isPresent)
                .orElseThrow();

        // The node records the history, so a failed invoice's detail has its entries.
        String id = failed.getString("id");
        assertThat(get(client, base + "api/jobs/" + id).getArray("history")).isNotEmpty();

        // The inventory sync's first run is now and completes both steps.
        JsonArray synced =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(
                    () ->
                        get(
                            client,
                            base
                                + "api/jobs?name="
                                + AdminExample.SYNC_INVENTORY
                                + "&state=SUCCEEDED"),
                    body -> !body.getArray("jobs").isEmpty())
                .getArray("jobs");
        String syncId = synced.getObject(0).getString("id");
        assertThat(get(client, base + "api/jobs/" + syncId).getArray("steps"))
            .extracting(step -> ((JsonObject) step).getString("name"))
            .contains("count-stock", "update-shop");
      } finally {
        server.stop(0);
        bikesched.removeRecurringJob(AdminExample.SYNC_INVENTORY);
      }
    }
  }

  private static JsonObject get(HttpClient client, String uri) throws Exception {
    return JsonParser.object()
        .from(
            client
                .send(
                    HttpRequest.newBuilder(URI.create(uri)).build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
  }
}
