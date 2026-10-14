// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bikesched.Bikesched;
import org.bikesched.admin.EventQuery;
import org.bikesched.job.JobState;
import org.bikesched.job.Recurrence;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The page, the JSON API, retry and cancel, served through the JDK {@code HttpServer}. */
class BikeschedAdminTest {

  private final String prefix = "admin-web-" + UUID.randomUUID().toString().substring(0, 8) + "-";

  private Bikesched bikesched;

  @BeforeEach
  void startNode() {
    bikesched = AdminServer.node(prefix);
  }

  /** Deletes the jobs and events left behind. */
  @AfterEach
  void deleteRows() throws Exception {
    bikesched.close();
    try (var connection = AdminServer.DATA_SOURCE.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("delete from bikesched_jobs where type like '" + prefix + "%'");
      statement.execute("delete from bikesched_events where name like '" + prefix + "%'");
    }
  }

  @Test
  void servesThePageAndEveryModuleItImports() {
    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      HttpResponse<String> redirect = server.get("");
      assertThat(redirect.statusCode()).isEqualTo(308);
      assertThat(redirect.headers().firstValue("Location")).contains("/bikesched/");

      HttpResponse<String> page = server.get("/");
      assertThat(page.statusCode()).isEqualTo(200);
      assertThat(page.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
      assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow())
          .startsWith("default-src 'self'");
      assertThat(page.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
      assertThat(page.body()).contains("href=\"admin.css\"", "src=\"app.js\"");
      assertThat(server.get("/admin.css").headers().firstValue("Content-Type"))
          .contains("text/css; charset=utf-8");

      // Follows every relative import from app.js: each one is served as JavaScript.
      Pattern imports = Pattern.compile("(?:from|import)\\s*['\"](\\./[^'\"]+)['\"]");
      Set<String> seen = new HashSet<>();
      var queue = new ArrayDeque<>(List.of("app.js"));
      while (!queue.isEmpty()) {
        String file = queue.poll();
        if (!seen.add(file)) {
          continue;
        }
        HttpResponse<String> module = server.get("/" + file);
        assertThat(module.statusCode()).as(file).isEqualTo(200);
        assertThat(module.headers().firstValue("Content-Type"))
            .contains("text/javascript; charset=utf-8");
        assertThat(module.body())
            .as(file + " imports no bare specifiers")
            .doesNotContain("from\"preact\"");
        String directory = file.contains("/") ? file.substring(0, file.lastIndexOf('/') + 1) : "";
        Matcher matcher = imports.matcher(module.body());
        while (matcher.find()) {
          queue.add(directory + matcher.group(1).substring(2));
        }
      }
      assertThat(seen)
          .contains(
              "jobs.js",
              "events.js",
              "vendor/preact.module.js",
              "vendor/hooks.module.js",
              "vendor/htm.module.js");
      assertThat(server.get("/vendor/preact-license.txt").statusCode()).isEqualTo(200);
      assertThat(server.get("/logo.svg").body()).contains("<title>bikesched</title>");
      assertThat(server.get("/missing.js").statusCode()).isEqualTo(404);
      assertThat(server.get("/../BikeschedAdmin.class").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void listsJobsByTypeAndStateInPages() {
    String type = prefix + "list";
    bikesched.registerJobHandler(type, job -> job.fail("failed on purpose"));
    UUID failed = bikesched.newJob(type).maxAttempts(1).schedule();
    UUID later1 = bikesched.newJob(type).schedule(Duration.ofHours(1));
    UUID later2 = bikesched.newJob(type).schedule(Duration.ofHours(1));
    awaitState(failed, JobState.FAILED);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      JsonObject failedOnly =
          AdminServer.json(server.get("/api/jobs?type=" + type + "&state=FAILED"));
      assertThat(failedOnly.getArray("jobs")).hasSize(1);
      JsonObject job = failedOnly.getArray("jobs").getObject(0);
      assertThat(job.getString("id")).isEqualTo(failed.toString());
      assertThat(job.getString("state")).isEqualTo("FAILED");
      assertThat(job.getString("lastError")).contains("failed on purpose");
      assertThat(failedOnly.isNull("next")).isTrue();

      JsonObject first = AdminServer.json(server.get("/api/jobs?type=" + type + "&limit=2"));
      assertThat(ids(first.getArray("jobs"))).containsExactly(later2.toString(), later1.toString());
      String next = first.getString("next");
      JsonObject second =
          AdminServer.json(
              server.get("/api/jobs?type=" + type + "&limit=2&before=" + encode(next)));
      assertThat(ids(second.getArray("jobs"))).containsExactly(failed.toString());

      JsonObject types = AdminServer.json(server.get("/api/types"));
      assertThat(types.getArray("types").stream().map(JsonObject.class::cast))
          .anySatisfy(
              t -> {
                assertThat(t.getString("type")).isEqualTo(type);
                assertThat(t.getInt("total")).isEqualTo(3);
                assertThat(t.getObject("counts").getInt("FAILED")).isOne();
                assertThat(t.getObject("counts").getInt("SCHEDULED")).isEqualTo(2);
              });

      assertThat(server.get("/api/jobs?state=BROKEN").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/jobs?limit=0").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/jobs?limit=x").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/jobs?before=nope").statusCode()).isEqualTo(400);
    }
  }

  @Test
  void findsJobsAndEventsByTheFirstDigitsOfTheirId() {
    String type = prefix + "by-id";
    bikesched.registerJobHandler(type, _ -> {});
    UUID jobId = bikesched.newJob(type).schedule(Duration.ofHours(1));
    UUID eventId = bikesched.newEvent(prefix + "by-id", "k").publish();

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      JsonObject jobs =
          AdminServer.json(server.get("/api/jobs?id=" + jobId.toString().substring(0, 8)));
      assertThat(ids(jobs.getArray("jobs"))).containsExactly(jobId.toString());
      assertThat(jobs.isNull("next")).isTrue();
      JsonObject events = AdminServer.json(server.get("/api/events?id=" + eventId));
      assertThat(events.getArray("events").getObject(0).getString("id"))
          .isEqualTo(eventId.toString());
      assertThat(AdminServer.json(server.get("/api/jobs?id=" + eventId)).getArray("jobs"))
          .as("the job search finds only jobs")
          .isEmpty();

      assertThat(server.get("/api/jobs?id=3f2a").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/events?id=not-an-id").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/jobs?id=" + jobId + "&type=" + type).statusCode()).isEqualTo(400);
      assertThat(server.get("/api/events?id=" + eventId + "&key=k").statusCode()).isEqualTo(400);
    }
  }

  @Test
  void showsTheRunsOfARecurringJob() {
    String type = prefix + "recurring";
    bikesched.registerJobHandler(type, _ -> {});
    bikesched.newJob(type).schedule(Recurrence.every(Duration.ofMillis(200)), Duration.ZERO);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      await()
          .untilAsserted(
              () ->
                  assertThat(
                          AdminServer.json(
                                  server.get("/api/recurring-runs?type=" + type + "&limit=2"))
                              .getArray("previous"))
                      .hasSize(2));
      JsonObject runs = AdminServer.json(server.get("/api/recurring-runs?type=" + type));
      assertThat(runs.getString("type")).isEqualTo(type);
      assertThat(runs.getObject("pending").getString("recurrence")).isEqualTo("every PT0.2S");
      assertThat(runs.getArray("previous").getObject(0).getString("state")).isEqualTo("SUCCEEDED");
      JsonObject listed = AdminServer.json(server.get("/api/jobs?type=" + type));
      assertThat(listed.getArray("jobs").getObject(0).getString("recurrence"))
          .isEqualTo("every PT0.2S");

      JsonObject none = AdminServer.json(server.get("/api/recurring-runs?type=" + prefix + "none"));
      assertThat(none.isNull("pending")).isTrue();
      assertThat(none.getArray("previous")).isEmpty();
      assertThat(server.get("/api/recurring-runs").statusCode()).isEqualTo(400);
    } finally {
      bikesched.removeRecurringJob(type);
    }
  }

  @Test
  void showsAJobWithStepsHistoryAndChildren() {
    String parentType = prefix + "parent";
    String childType = prefix + "child";
    bikesched.registerJobHandler(childType, job -> job.complete("\"child\""));
    bikesched.registerJobHandler(
        parentType,
        job -> {
          job.step("prepare", String.class, _ -> "\"prepared\"");
          UUID child = job.newJob(childType).stepName("run-child").schedule();
          job.waitForJob(child, String.class);
        });
    UUID parentId = bikesched.newJob(parentType).idempotencyKey("parent-1").schedule();
    awaitState(parentId, JobState.SUCCEEDED);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      JsonObject detail = AdminServer.json(server.get("/api/jobs/" + parentId));
      assertThat(detail.getObject("job").getString("idempotencyKey")).isEqualTo("parent-1");
      assertThat(detail.getArray("ancestors")).isEmpty();
      assertThat(detail.getBoolean("payloadsShown")).isFalse();
      JsonArray steps = detail.getArray("steps");
      assertThat(steps.stream().map(s -> ((JsonObject) s).getString("name")))
          .containsExactly("prepare", "run-child");
      JsonObject childStep = steps.getObject(1);
      assertThat(childStep.getString("kind")).isEqualTo("CHILD_JOB");
      assertThat(childStep.getString("childJobType")).isEqualTo(childType);
      assertThat(childStep.getString("childJobState")).isEqualTo("SUCCEEDED");
      assertThat(childStep.getBoolean("childJobExists")).isTrue();
      assertThat(detail.getArray("history")).as("history disabled").isEmpty();

      String childId = childStep.getString("childJobId");
      JsonObject child = AdminServer.json(server.get("/api/jobs/" + childId));
      assertThat(child.getObject("job").getString("parentJobType")).isEqualTo(parentType);
      assertThat(child.getArray("ancestors")).hasSize(1);
      JsonObject parent = child.getArray("ancestors").getObject(0);
      assertThat(parent.getString("id")).isEqualTo(parentId.toString());
      assertThat(parent.getString("stepName")).isEqualTo("run-child");
      assertThat(parent.getBoolean("exists")).isTrue();

      assertThat(server.get("/api/jobs/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
      assertThat(server.get("/api/jobs/not-a-uuid").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/unknown").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void showsEventsWithTheirReceiversAndWaitingJobs() {
    String event = prefix + "event";
    String type = prefix + "waiter";
    bikesched.registerJobHandler(
        type,
        job -> {
          String key = job.payload(String.class).replace("\"", ""); // the default mapper: JSON text
          job.waitForEvent("await", event, key, String.class).receive();
        });
    UUID received = bikesched.newJob(type).payload("\"key-1\"").schedule();
    UUID waiting = bikesched.newJob(type).payload("\"key-2\"").schedule();
    awaitState(received, JobState.WAITING);
    awaitState(waiting, JobState.WAITING);
    UUID eventId = bikesched.newEvent(event, "key-1").publish();
    awaitState(received, JobState.SUCCEEDED);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      JsonObject events = AdminServer.json(server.get("/api/events?name=" + event));
      assertThat(ids(events.getArray("events"))).containsExactly(eventId.toString());
      assertThat(events.getArray("events").getObject(0).getInt("receivedBy")).isOne();
      assertThat(
              AdminServer.json(server.get("/api/events?name=" + event + "&key=key-9"))
                  .getArray("events"))
          .isEmpty();

      JsonObject detail = AdminServer.json(server.get("/api/events/" + eventId));
      assertThat(detail.getObject("event").getBoolean("latest")).isTrue();
      JsonObject step = detail.getArray("steps").getObject(0);
      assertThat(step.getString("jobId")).isEqualTo(received.toString());
      assertThat(step.getBoolean("publisher")).isFalse();

      JsonObject names = AdminServer.json(server.get("/api/event-names"));
      assertThat(names.getArray("names").stream().map(JsonObject.class::cast))
          .anySatisfy(n -> assertThat(n.getString("name")).isEqualTo(event));

      JsonObject waits = AdminServer.json(server.get("/api/waiting?name=" + event));
      assertThat(waits.getArray("steps")).hasSize(1);
      assertThat(waits.getArray("steps").getObject(0).getString("jobId"))
          .isEqualTo(waiting.toString());
      assertThat(server.get("/api/waiting").statusCode()).isEqualTo(400);
      assertThat(server.get("/api/events/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }
  }

  @Test
  void showsANodeWithTheJobsItRuns() throws Exception {
    String type = prefix + "on-node";
    CountDownLatch release = new CountDownLatch(1);
    bikesched.registerJobHandler(type, _ -> release.await());
    try {
      UUID jobId = bikesched.newJob(type).schedule();
      awaitState(jobId, JobState.RUNNING);
      UUID nodeId = bikesched.node().id().orElseThrow();

      try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
        JsonObject node = AdminServer.json(server.get("/api/nodes/" + nodeId)).getObject("node");
        assertThat(node.getString("id")).isEqualTo(nodeId.toString());
        assertThat(node.getBoolean("alive")).isTrue();
        assertThat(node.getInt("runningJobs")).isEqualTo(1);

        JsonArray jobs = AdminServer.json(server.get("/api/jobs?node=" + nodeId)).getArray("jobs");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getObject(0).getString("id")).isEqualTo(jobId.toString());

        assertThat(server.get("/api/nodes/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(server.get("/api/nodes/nope").statusCode()).isEqualTo(400);
        assertThat(server.get("/api/jobs?node=nope").statusCode()).isEqualTo(400);
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void retriesAndCancelsJobs() {
    String type = prefix + "actions";
    var failOnce = new AtomicBoolean(true);
    bikesched.registerJobHandler(
        type,
        job -> {
          if (failOnce.getAndSet(false)) {
            job.fail("first run fails");
          }
        });
    UUID failed = bikesched.newJob(type).maxAttempts(1).schedule();
    UUID later = bikesched.newJob(type).schedule(Duration.ofHours(1));
    awaitState(failed, JobState.FAILED);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      String csrf = AdminHandler.CSRF_HEADER;
      HttpResponse<String> withoutHeader = server.post("/api/jobs/" + failed + "/retry");
      assertThat(withoutHeader.statusCode()).isEqualTo(403);
      assertThat(withoutHeader.body()).contains(csrf);

      HttpResponse<String> retried = server.post("/api/jobs/" + failed + "/retry", csrf, "1");
      assertThat(retried.statusCode()).isEqualTo(200);
      assertThat(AdminServer.json(retried).getBoolean("retried")).isTrue();
      awaitState(failed, JobState.SUCCEEDED);
      assertThat(
              AdminServer.json(server.post("/api/jobs/" + failed + "/retry", csrf, "1"))
                  .getBoolean("retried"))
          .as("a succeeded job is not retried")
          .isFalse();

      HttpResponse<String> cancelled = server.post("/api/jobs/" + later + "/cancel", csrf, "1");
      assertThat(AdminServer.json(cancelled).getBoolean("cancelled")).isTrue();
      awaitState(later, JobState.CANCELLED);

      assertThat(server.get("/api/jobs/" + later + "/cancel").statusCode()).isEqualTo(405);
      assertThat(server.post("/api/jobs", csrf, "1").statusCode()).isEqualTo(405);
      assertThat(
              server
                  .send(
                      HttpRequest.newBuilder(server.uri("/api/jobs"))
                          .method("PUT", HttpRequest.BodyPublishers.noBody()))
                  .statusCode())
          .isEqualTo(405);
    }
  }

  @Test
  void publishesEvents() {
    String type = prefix + "publish-waiter";
    String event = prefix + "published";
    bikesched.registerJobHandler(
        type, job -> job.complete(job.waitForEvent("await", event, "k-1", String.class).receive()));
    UUID waiting = bikesched.newJob(type).schedule();
    awaitState(waiting, JobState.WAITING);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      String csrf = AdminHandler.CSRF_HEADER;
      String form =
          "name="
              + encode(event)
              + "&key=k-1&payload="
              + encode("{\"amount\": 42}")
              + "&retention=PT1H&idempotencyKey=admin-1";
      assertThat(server.postForm("/api/events", form).statusCode())
          .as("without the CSRF header")
          .isEqualTo(403);

      HttpResponse<String> published = server.postForm("/api/events", form, csrf, "1");
      assertThat(published.statusCode()).isEqualTo(200);
      JsonObject body = AdminServer.json(published);
      assertThat(body.getInt("received")).isOne();
      UUID eventId = UUID.fromString(body.getString("id"));
      awaitState(waiting, JobState.SUCCEEDED);
      assertThat(bikesched.admin().findJobData(waiting).orElseThrow().result())
          .isEqualTo("{\"amount\": 42}");
      var summary = bikesched.admin().findEvent(eventId).orElseThrow();
      assertThat(Duration.between(summary.publishedAt(), summary.expiresAt()))
          .isEqualTo(Duration.ofHours(1));
      assertThat(summary.idempotencyKey()).isEqualTo("admin-1");
      assertThat(AdminServer.json(server.postForm("/api/events", form, csrf, "1")).getString("id"))
          .as("the idempotency key returns the first event")
          .isEqualTo(eventId.toString());

      assertThat(server.postForm("/api/events", "name=" + encode(event), csrf, "1").body())
          .contains("needs a name and a key");
      assertThat(
              server
                  .postForm("/api/events", "name=x&key=k&payload=" + encode("{\"a\": }"), csrf, "1")
                  .body())
          .contains("not valid JSON");
      HttpResponse<String> badRetention =
          server.postForm("/api/events", "name=x&key=k&retention=soon", csrf, "1");
      assertThat(badRetention.statusCode()).isEqualTo(400);
      assertThat(badRetention.body()).contains("Malformed retention");
      assertThat(
              server
                  .postForm(
                      "/api/events",
                      "name=x&key=k&payload=" + "1".repeat(ExchangeHandler.MAX_BODY_BYTES),
                      csrf,
                      "1")
                  .statusCode())
          .isEqualTo(413);
    }
    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched).readOnly(true))) {
      HttpResponse<String> response =
          server.postForm(
              "/api/events", "name=" + encode(event) + "&key=k-2", AdminHandler.CSRF_HEADER, "1");
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.body()).contains("read-only");
    }
    assertThat(bikesched.admin().findEvents(EventQuery.newest(10).withName(event)))
        .as("only the first form was published")
        .hasSize(1);
  }

  @Test
  void aReadOnlyAdminRejectsRetryAndCancel() {
    bikesched.registerJobHandler(prefix + "read-only", _ -> {});
    UUID later = bikesched.newJob(prefix + "read-only").schedule(Duration.ofHours(1));
    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched).readOnly(true))) {
      HttpResponse<String> response =
          server.post("/api/jobs/" + later + "/cancel", AdminHandler.CSRF_HEADER, "1");
      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.body()).contains("read-only");
      JsonObject me = AdminServer.json(server.get("/api/me"));
      assertThat(me.getString("level")).isEqualTo("VIEW");
      assertThat(me.getBoolean("readOnly")).isTrue();
    }
    assertThat(bikesched.findJob(later).orElseThrow().state()).isEqualTo(JobState.SCHEDULED);
  }

  @Test
  void payloadsAreOnlyReadWithShowPayloads() {
    String marker = "secret-" + UUID.randomUUID();
    String type = prefix + "payload";
    String event = prefix + "payload-event";
    bikesched.registerJobHandler(type, job -> job.complete("{\"result\": \"" + marker + "\"}"));
    UUID jobId = bikesched.newJob(type).payload("{\"card\": \"" + marker + "\"}").schedule();
    awaitState(jobId, JobState.SUCCEEDED);
    UUID eventId = bikesched.newEvent(event, "k").payload("\"" + marker + "\"").publish();
    List<String> paths =
        List.of(
            "/api/me",
            "/api/jobs?type=" + type,
            "/api/jobs/" + jobId,
            "/api/types",
            "/api/events?name=" + event,
            "/api/events/" + eventId,
            "/api/event-names",
            "/api/waiting?name=" + event,
            "/api/nodes");

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      for (String path : paths) {
        HttpResponse<String> response = server.get(path);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertThat(response.body()).as(path).doesNotContain(marker, "\"payload\":", "\"result\":");
      }
    }
    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched).showPayloads(true))) {
      JsonObject job = AdminServer.json(server.get("/api/jobs/" + jobId));
      assertThat(job.getBoolean("payloadsShown")).isTrue();
      assertThat(job.getObject("payload").getString("card")).isEqualTo(marker);
      assertThat(job.getObject("result").getString("result")).isEqualTo(marker);
      assertThat(AdminServer.json(server.get("/api/events/" + eventId)).getString("payload"))
          .isEqualTo(marker);
      assertThat(server.get("/api/jobs?type=" + type).body()).doesNotContain(marker);
    }
  }

  @Test
  void anUnchangedJobAnswersNotModified() {
    bikesched.registerJobHandler(prefix + "etag", _ -> {});
    UUID later = bikesched.newJob(prefix + "etag").schedule(Duration.ofHours(1));
    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      HttpResponse<String> first = server.get("/api/jobs/" + later);
      String etag = first.headers().firstValue("ETag").orElseThrow();
      assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");

      HttpResponse<String> unchanged = server.get("/api/jobs/" + later, "If-None-Match", etag);
      assertThat(unchanged.statusCode()).isEqualTo(304);
      assertThat(unchanged.body()).isEmpty();

      bikesched.cancel(later, "changed");
      awaitState(later, JobState.CANCELLED);
      HttpResponse<String> changed = server.get("/api/jobs/" + later, "If-None-Match", etag);
      assertThat(changed.statusCode()).isEqualTo(200);
      assertThat(changed.headers().firstValue("ETag")).isPresent().get().isNotEqualTo(etag);
    }
  }

  @Test
  void escapesErrorMessages() {
    String type = prefix + "escape";
    String message = "a \"quoted\"\nline with <script>alert(1)</script> & \\ backslash";
    bikesched.registerJobHandler(type, job -> job.fail(message));
    UUID jobId = bikesched.newJob(type).maxAttempts(1).schedule();
    awaitState(jobId, JobState.FAILED);

    try (var server = AdminServer.serve(BikeschedAdmin.of(bikesched))) {
      HttpResponse<String> response = server.get("/api/jobs/" + jobId);
      assertThat(response.body()).doesNotContain("</script>", "\n");
      assertThat(AdminServer.json(response).getObject("job").getString("lastError"))
          .contains(message);
    }
  }

  @Test
  void aClosedNodeAnswersServiceUnavailable() {
    Bikesched closed = AdminServer.node(prefix);
    closed.close();
    try (var server = AdminServer.serve(BikeschedAdmin.of(closed))) {
      assertThat(server.get("/api/jobs").statusCode()).isEqualTo(503);
      assertThat(server.get("/").statusCode()).as("the page is still served").isEqualTo(200);
    }
  }

  private void awaitState(UUID jobId, JobState state) {
    BikeschedTest.awaitJob(bikesched, jobId).timeout(Duration.ofSeconds(15)).untilState(state);
  }

  private static List<String> ids(JsonArray items) {
    return items.stream().map(item -> ((JsonObject) item).getString("id")).toList();
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
