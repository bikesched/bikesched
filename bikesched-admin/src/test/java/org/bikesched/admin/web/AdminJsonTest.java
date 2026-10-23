// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.grack.nanojson.JsonParser;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/** Exact wire contracts, including explicit nulls and names that differ from record accessors. */
class AdminJsonTest {

  private static final UUID ID = UUID.fromString("7f0c2a1e-0000-4000-8000-000000000001");
  private static final Instant AT = Instant.parse("2026-10-11T12:00:00.123456Z");

  @Test
  void jobHasOnlySummaryFields() throws Exception {
    var job =
        new JobSummary(
            ID,
            "mail",
            JobState.WAITING,
            2,
            5,
            7,
            null,
            AT,
            AT,
            null,
            "failed",
            ID,
            "parent",
            "every PT1H",
            "dedup",
            ID,
            "worker");
    assertContract(
        AdminJson.job(job),
        """
        {"id":"7f0c2a1e-0000-4000-8000-000000000001","type":"mail","state":"WAITING",
         "attempts":2,"maxAttempts":5,"priority":7,"scheduledAt":null,
         "createdAt":"2026-10-11T12:00:00.123456Z","updatedAt":"2026-10-11T12:00:00.123456Z",
         "finishedAt":null,"lastError":"failed","parentJobId":"7f0c2a1e-0000-4000-8000-000000000001",
         "parentJobType":"parent","recurrence":"every PT1H","idempotencyKey":"dedup",
         "nodeId":"7f0c2a1e-0000-4000-8000-000000000001","nodeName":"worker"}
        """);
  }

  @Test
  void stepHasChildStateByEnumNameAndNullableTimestamps() throws Exception {
    var step =
        new StepInfo(
            ID,
            ID,
            "child",
            "CHILD_JOB",
            "WAITING",
            3,
            0,
            true,
            null,
            AT,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            ID,
            "mail",
            JobState.RUNNING,
            true);
    assertContract(
        AdminJson.step(step),
        """
        {"id":"7f0c2a1e-0000-4000-8000-000000000001","jobId":"7f0c2a1e-0000-4000-8000-000000000001",
         "name":"child","kind":"CHILD_JOB","state":"WAITING","attempts":3,"failureAttempts":0,
         "compensable":true,"lastError":null,"startedAt":"2026-10-11T12:00:00.123456Z",
         "finishedAt":null,"waitUntil":null,"deadline":null,"nodeId":null,"eventName":null,
         "eventKey":null,"eventId":null,"childJobId":"7f0c2a1e-0000-4000-8000-000000000001",
         "childJobType":"mail","childJobState":"RUNNING","childJobExists":true}
        """);
  }

  @Test
  void historyRenamesCreatedAtToAtWithoutDerivedFields() throws Exception {
    assertContract(
        AdminJson.history(new JobHistoryEntry(AT, null, "FAILED", 2, ID, "error")),
        """
        {"at":"2026-10-11T12:00:00.123456Z","stepName":null,"state":"FAILED","attempt":2,
         "nodeId":"7f0c2a1e-0000-4000-8000-000000000001","detail":"error"}
        """);
  }

  @Test
  void eventHasOnlySummaryFields() throws Exception {
    assertContract(
        AdminJson.event(new EventSummary(ID, "paid", "order", null, AT, AT, 4, true)),
        """
        {"id":"7f0c2a1e-0000-4000-8000-000000000001","name":"paid","key":"order",
         "idempotencyKey":null,"publishedAt":"2026-10-11T12:00:00.123456Z",
         "expiresAt":"2026-10-11T12:00:00.123456Z","receivedBy":4,"latest":true}
        """);
  }

  @Test
  void eventStepRenamesPublisherAndIncludesJobState() throws Exception {
    assertContract(
        AdminJson.eventStep(
            new EventStep(ID, ID, "mail", JobState.SUCCEEDED, "publish", "EVENT_PUBLISH")),
        """
        {"stepId":"7f0c2a1e-0000-4000-8000-000000000001","jobId":"7f0c2a1e-0000-4000-8000-000000000001","jobType":"mail","jobState":"SUCCEEDED",
         "stepName":"publish","kind":"EVENT_PUBLISH","publisher":true}
        """);
    assertContract(
        AdminJson.eventStep(new EventStep(ID, ID, "mail", JobState.WAITING, "await", "EVENT_WAIT")),
        """
        {"stepId":"7f0c2a1e-0000-4000-8000-000000000001","jobId":"7f0c2a1e-0000-4000-8000-000000000001","jobType":"mail","jobState":"WAITING",
         "stepName":"await","kind":"EVENT_WAIT","publisher":false}
        """);
  }

  @Test
  void waitingStepKeepsNullTimeout() throws Exception {
    assertContract(
        AdminJson.waitingStep(new WaitingStep(ID, ID, "mail", "await", "paid", "order", AT, null)),
        """
        {"stepId":"7f0c2a1e-0000-4000-8000-000000000001","jobId":"7f0c2a1e-0000-4000-8000-000000000001","jobType":"mail","stepName":"await",
         "eventName":"paid","eventKey":"order","since":"2026-10-11T12:00:00.123456Z","timeoutAt":null}
        """);
  }

  @Test
  void nodeIncludesHeartbeatAliveFlagAndRunningJobs() throws Exception {
    assertContract(
        AdminJson.node(new NodeInfo(ID, "worker", AT, AT, false, 2)),
        """
        {"id":"7f0c2a1e-0000-4000-8000-000000000001","name":"worker",
         "startedAt":"2026-10-11T12:00:00.123456Z","lastHeartbeatAt":"2026-10-11T12:00:00.123456Z","alive":false,
         "runningJobs":2}
        """);
  }

  private static void assertContract(Json.Obj actual, String expected) throws Exception {
    assertThat(JsonParser.object().from(Json.write(actual)))
        .containsExactlyInAnyOrderEntriesOf(JsonParser.object().from(expected));
  }
}
