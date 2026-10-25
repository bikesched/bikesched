// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.job.JobHistoryEntry;

/** Explicit wire projections, independent of routing and database lookups. */
final class AdminJson {

  private AdminJson() {}

  static Json.Obj job(JobSummary job) {
    return Json.object()
        .put("id", job.id())
        .put("name", job.name())
        .put("type", job.type())
        .put("state", job.state())
        .put("attempts", job.attempts())
        .put("maxAttempts", job.maxAttempts())
        .put("priority", job.priority())
        .put("scheduledAt", job.scheduledAt())
        .put("createdAt", job.createdAt())
        .put("updatedAt", job.updatedAt())
        .put("finishedAt", job.finishedAt())
        .put("lastError", job.lastError())
        .put("parentJobId", job.parentJobId())
        .put("parentJobName", job.parentJobName())
        .put("recurrence", job.recurrence())
        .put("idempotencyKey", job.idempotencyKey())
        .put("reference", job.reference())
        .put("nodeId", job.nodeId())
        .put("nodeName", job.nodeName());
  }

  static Json.Obj step(StepInfo step) {
    return Json.object()
        .put("id", step.id())
        .put("jobId", step.jobId())
        .put("name", step.name())
        .put("type", step.type())
        .put("state", step.state())
        .put("attempts", step.attempts())
        .put("failureAttempts", step.failureAttempts())
        .put("compensable", step.compensable())
        .put("lastError", step.lastError())
        .put("startedAt", step.startedAt())
        .put("finishedAt", step.finishedAt())
        .put("waitUntil", step.waitUntil())
        .put("deadline", step.deadline())
        .put("nodeId", step.nodeId())
        .put("eventName", step.eventName())
        .put("eventKey", step.eventKey())
        .put("eventId", step.eventId())
        .put("childJobId", step.childJobId())
        .put("childJobName", step.childJobName())
        .put("childJobState", step.childJobState())
        .put("childJobExists", step.childJobExists());
  }

  static Json.Obj history(JobHistoryEntry entry) {
    return Json.object()
        .put("at", entry.createdAt())
        .put("stepName", entry.stepName())
        .put("state", entry.state())
        .put("attempt", entry.attempt())
        .put("nodeId", entry.nodeId())
        .put("detail", entry.detail());
  }

  static Json.Obj event(EventSummary event) {
    return Json.object()
        .put("id", event.id())
        .put("name", event.name())
        .put("key", event.key())
        .put("idempotencyKey", event.idempotencyKey())
        .put("reference", event.reference())
        .put("publishedAt", event.publishedAt())
        .put("expiresAt", event.expiresAt())
        .put("receivedBy", event.receivedBy())
        .put("latest", event.latest());
  }

  static Json.Obj eventStep(EventStep step) {
    return Json.object()
        .put("stepId", step.stepId())
        .put("jobId", step.jobId())
        .put("jobName", step.jobName())
        .put("jobState", step.jobState())
        .put("stepName", step.stepName())
        .put("stepType", step.stepType())
        .put("publisher", step.isPublisher());
  }

  static Json.Obj waitingStep(WaitingStep step) {
    return Json.object()
        .put("stepId", step.stepId())
        .put("jobId", step.jobId())
        .put("jobName", step.jobName())
        .put("stepName", step.stepName())
        .put("eventName", step.eventName())
        .put("eventKey", step.eventKey())
        .put("since", step.since())
        .put("timeoutAt", step.timeoutAt());
  }

  static Json.Obj node(NodeInfo node) {
    return Json.object()
        .put("id", node.id())
        .put("name", node.name())
        .put("startedAt", node.startedAt())
        .put("lastHeartbeatAt", node.lastHeartbeatAt())
        .put("alive", node.alive())
        .put("runningJobs", node.runningJobs());
  }
}
