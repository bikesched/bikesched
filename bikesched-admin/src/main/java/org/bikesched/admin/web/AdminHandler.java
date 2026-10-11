// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedException;
import org.bikesched.admin.Admin;
import org.bikesched.admin.EventQuery;
import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobQuery;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes the requests of a {@link BikeschedAdmin}: access check, the static files and the JSON API.
 * Stateless apart from the static file cache; thread-safe.
 */
final class AdminHandler {

  private static final Logger LOG = LoggerFactory.getLogger(AdminHandler.class);

  /** The header the page sets on POSTs; a cross-site form or image tag cannot send it. */
  static final String CSRF_HEADER = "X-Bikesched-Admin";

  static final String CANCEL_MESSAGE = "cancelled from the admin UI";

  private static final int DEFAULT_LIMIT = 50;

  /** Ancestors of a child job shown above its detail; deeper ones are not looked up. */
  private static final int MAX_ANCESTORS = 20;

  private static final String CSP =
      "default-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

  private static final Pattern JOB = Pattern.compile("/api/jobs/([^/]+)");

  private static final Pattern JOB_ACTION = Pattern.compile("/api/jobs/([^/]+)/(retry|cancel)");

  private static final Pattern EVENT = Pattern.compile("/api/events/([^/]+)");

  private final Bikesched bikesched;

  private final boolean readOnly;

  private final boolean showPayloads;

  private final AdminAccess access;

  private final StaticFiles files;

  AdminHandler(
      Bikesched bikesched,
      boolean readOnly,
      boolean showPayloads,
      AdminAccess access,
      StaticFiles files) {
    this.bikesched = bikesched;
    this.readOnly = readOnly;
    this.showPayloads = showPayloads;
    this.access = access;
    this.files = files;
  }

  /** Thrown for malformed parameters and ids; answered with 400. */
  private static final class BadRequest extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadRequest(String message) {
      super(message, null, false, false);
    }
  }

  AdminResponse handle(AdminRequest request) {
    AccessGrant grant = access.check(request);
    AccessLevel level = grant.level();
    if (readOnly && level == AccessLevel.OPERATE) {
      level = AccessLevel.VIEW;
    }
    if (level == AccessLevel.NONE) {
      String challenge = access.challenge();
      if (challenge != null) {
        return error(401, "Authentication required", Map.of("WWW-Authenticate", challenge));
      }
      return error(403, "Access denied", Map.of());
    }
    try {
      return switch (request.method()) {
        case "GET" -> get(request, grant.user(), level);
        case "POST" -> post(request, grant.user(), level);
        default -> error(405, "Method not allowed", Map.of("Allow", "GET, POST"));
      };
    } catch (BadRequest | IllegalArgumentException e) {
      return error(400, e.getMessage(), Map.of());
    } catch (BikeschedException | IllegalStateException e) {
      // No stack trace: an open page polls, so an unreachable database would flood the log.
      LOG.warn("Admin request {} {} failed: {}", request.method(), request.path(), e.toString());
      return error(503, e.getMessage(), Map.of());
    }
  }

  private AdminResponse get(AdminRequest request, String user, AccessLevel level) {
    String path = request.path();
    if (!path.startsWith("/api/")) {
      return staticFile(request, path.equals("/") ? "index.html" : path.substring(1));
    }
    Map<String, String> params = parseQuery(request.query());
    Admin admin = bikesched.admin();
    Object body =
        switch (path) {
          case "/api/me" ->
              Json.object()
                  .put("user", user)
                  .put("level", level)
                  .put("readOnly", readOnly)
                  .put("payloadsShown", showPayloads);
          case "/api/jobs" -> jobs(admin, params);
          case "/api/types" -> Json.object().put("types", types(admin));
          case "/api/events" -> events(admin, params);
          case "/api/event-names" ->
              Json.object()
                  .put(
                      "names",
                      admin.findEventNames().stream()
                          .map(n -> Json.object().put("name", n.name()).put("count", n.count()))
                          .toList());
          case "/api/waiting" -> waiting(admin, params);
          case "/api/nodes" ->
              Json.object()
                  .put("nodes", admin.findNodes().stream().map(AdminHandler::node).toList());
          default -> null;
        };
    if (body == null) {
      Matcher job = JOB.matcher(path);
      Matcher event = EVENT.matcher(path);
      if (job.matches()) {
        body = job(admin, id(job.group(1))).orElse(null);
      } else if (event.matches()) {
        body = event(admin, id(event.group(1))).orElse(null);
      } else if (JOB_ACTION.matcher(path).matches()) {
        return error(405, "Method not allowed", Map.of("Allow", "POST"));
      }
      if (body == null) {
        return error(404, "Not found: " + path, Map.of());
      }
    }
    return json(request, 200, body);
  }

  private AdminResponse post(AdminRequest request, String user, AccessLevel level) {
    Matcher action = JOB_ACTION.matcher(request.path());
    if (!action.matches()) {
      boolean known = request.path().startsWith("/api/") || request.path().equals("/");
      return known
          ? error(405, "Method not allowed", Map.of("Allow", "GET"))
          : error(404, "Not found: " + request.path(), Map.of());
    }
    if (!"1".equals(request.header(CSRF_HEADER))) {
      return error(403, "Missing header " + CSRF_HEADER + ": 1", Map.of());
    }
    if (!level.allows(AccessLevel.OPERATE)) {
      return error(
          403, readOnly ? "The admin is read-only" : "Viewers cannot change jobs", Map.of());
    }
    UUID jobId = id(action.group(1));
    String by = user == null ? "" : " by " + user;
    if (action.group(2).equals("retry")) {
      boolean retried = bikesched.retry(jobId);
      if (retried) {
        LOG.info("Job {} retried from the admin UI{}", jobId, by);
      }
      return json(request, 200, Json.object().put("retried", retried));
    }
    boolean cancelled = bikesched.cancel(jobId, CANCEL_MESSAGE + by);
    if (cancelled) {
      LOG.info("Job {} cancelled from the admin UI{}", jobId, by);
    }
    return json(request, 200, Json.object().put("cancelled", cancelled));
  }

  private Json.Obj jobs(Admin admin, Map<String, String> params) {
    int limit = limit(params);
    JobQuery query = JobQuery.newest(limit).withType(blankToNull(params.get("type")));
    String states = params.get("state");
    if (states != null && !states.isBlank()) {
      Set<JobState> set = EnumSet.noneOf(JobState.class);
      for (String state : states.split(",")) {
        try {
          set.add(JobState.valueOf(state.trim()));
        } catch (IllegalArgumentException e) {
          throw new BadRequest("Unknown job state: " + state);
        }
      }
      query = query.withStates(set);
    }
    Cursor before = Cursor.parse(params.get("before"));
    if (before != null) {
      query = new JobQuery(query.type(), query.states(), before.time(), before.id(), limit);
    }
    List<JobSummary> jobs = admin.findJobs(query);
    String next = null;
    if (jobs.size() == limit) {
      JobSummary last = jobs.getLast();
      next = new Cursor(last.createdAt(), last.id()).toString();
    }
    return Json.object()
        .put("jobs", jobs.stream().map(AdminHandler::job).toList())
        .put("next", next);
  }

  private static List<Json.Obj> types(Admin admin) {
    return admin.findJobTypes().stream()
        .map(
            type -> {
              Map<String, Integer> counts = new LinkedHashMap<>();
              type.counts().forEach((state, count) -> counts.put(state.name(), count));
              return Json.object()
                  .put("type", type.type())
                  .put("total", type.total())
                  .put("counts", counts)
                  .put("recurrence", type.recurrence());
            })
        .toList();
  }

  private Optional<Json.Obj> job(Admin admin, UUID jobId) {
    Optional<JobSummary> found = admin.findJob(jobId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    JobSummary summary = found.get();
    Json.Obj body =
        Json.object()
            .put("job", job(summary))
            .put("ancestors", ancestors(admin, summary))
            .put("steps", admin.findSteps(jobId).stream().map(AdminHandler::step).toList())
            .put(
                "history",
                bikesched.findJobHistory(jobId).stream().map(AdminHandler::history).toList())
            .put("payloadsShown", showPayloads);
    if (showPayloads) {
      // Only read with showPayloads(true): without it, payloads never leave the database.
      admin
          .findJobData(jobId)
          .ifPresent(
              data ->
                  body.put("payload", new Json.Raw(data.payload()))
                      .put("result", new Json.Raw(data.result())));
    }
    return Optional.of(body);
  }

  /**
   * The parents of a child job, root first, each with the step that started the next level; a
   * deleted parent ends the path with {@code exists: false}.
   */
  private static List<Json.Obj> ancestors(Admin admin, JobSummary job) {
    List<Json.Obj> path = new ArrayList<>();
    UUID child = job.id();
    UUID parentId = job.parentJobId();
    while (parentId != null && path.size() < MAX_ANCESTORS) {
      Optional<JobSummary> parent = admin.findJob(parentId);
      if (parent.isEmpty()) {
        path.addFirst(Json.object().put("id", parentId).put("exists", false));
        break;
      }
      UUID childId = child;
      String stepName =
          admin.findSteps(parentId).stream()
              .filter(step -> childId.equals(step.childJobId()))
              .map(StepInfo::name)
              .findFirst()
              .orElse(null);
      path.addFirst(
          Json.object()
              .put("id", parentId)
              .put("type", parent.get().type())
              .put("state", parent.get().state())
              .put("exists", true)
              .put("stepName", stepName));
      child = parentId;
      parentId = parent.get().parentJobId();
    }
    return path;
  }

  private Json.Obj events(Admin admin, Map<String, String> params) {
    int limit = limit(params);
    EventQuery query =
        EventQuery.newest(limit)
            .withName(blankToNull(params.get("name")))
            .withKeyPrefix(blankToNull(params.get("key")));
    Cursor before = Cursor.parse(params.get("before"));
    if (before != null) {
      query = new EventQuery(query.name(), query.keyPrefix(), before.time(), before.id(), limit);
    }
    List<EventSummary> events = admin.findEvents(query);
    String next = null;
    if (events.size() == limit) {
      EventSummary last = events.getLast();
      next = new Cursor(last.publishedAt(), last.id()).toString();
    }
    return Json.object()
        .put("events", events.stream().map(AdminHandler::event).toList())
        .put("next", next);
  }

  private Optional<Json.Obj> event(Admin admin, UUID eventId) {
    Optional<EventSummary> found = admin.findEvent(eventId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Json.Obj body =
        Json.object()
            .put("event", event(found.get()))
            .put(
                "steps",
                admin.findEventSteps(eventId).stream().map(AdminHandler::eventStep).toList())
            .put("payloadsShown", showPayloads);
    if (showPayloads) {
      admin
          .findEventPayload(eventId)
          .ifPresent(payload -> body.put("payload", new Json.Raw(payload)));
    }
    return Optional.of(body);
  }

  private static Json.Obj waiting(Admin admin, Map<String, String> params) {
    String name = blankToNull(params.get("name"));
    if (name == null) {
      throw new BadRequest("Parameter name is required");
    }
    List<WaitingStep> steps =
        admin.findWaitingSteps(name, blankToNull(params.get("key")), limit(params));
    return Json.object().put("steps", steps.stream().map(AdminHandler::waitingStep).toList());
  }

  private static Json.Obj job(JobSummary job) {
    return Json.object()
        .put("id", job.id())
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
        .put("parentJobType", job.parentJobType())
        .put("recurrence", job.recurrence())
        .put("idempotencyKey", job.idempotencyKey())
        .put("nodeName", job.nodeName());
  }

  private static Json.Obj step(StepInfo step) {
    return Json.object()
        .put("name", step.name())
        .put("kind", step.kind())
        .put("state", step.state())
        .put("attempts", step.attempts())
        .put("lastError", step.lastError())
        .put("startedAt", step.startedAt())
        .put("finishedAt", step.finishedAt())
        .put("waitUntil", step.waitUntil())
        .put("eventName", step.eventName())
        .put("eventKey", step.eventKey())
        .put("childJobId", step.childJobId())
        .put("childJobType", step.childJobType())
        .put("childJobState", step.childJobState())
        .put("childJobExists", step.childJobExists());
  }

  private static Json.Obj history(JobHistoryEntry entry) {
    return Json.object()
        .put("at", entry.createdAt())
        .put("stepName", entry.stepName())
        .put("state", entry.state())
        .put("attempt", entry.attempt())
        .put("nodeId", entry.nodeId())
        .put("detail", entry.detail());
  }

  private static Json.Obj event(EventSummary event) {
    return Json.object()
        .put("id", event.id())
        .put("name", event.name())
        .put("key", event.key())
        .put("idempotencyKey", event.idempotencyKey())
        .put("publishedAt", event.publishedAt())
        .put("expiresAt", event.expiresAt())
        .put("receivedBy", event.receivedBy())
        .put("latest", event.latest());
  }

  private static Json.Obj eventStep(EventStep step) {
    return Json.object()
        .put("jobId", step.jobId())
        .put("jobType", step.jobType())
        .put("jobState", step.jobState())
        .put("stepName", step.stepName())
        .put("kind", step.kind())
        .put("publisher", step.isPublisher());
  }

  private static Json.Obj waitingStep(WaitingStep step) {
    return Json.object()
        .put("jobId", step.jobId())
        .put("jobType", step.jobType())
        .put("stepName", step.stepName())
        .put("eventName", step.eventName())
        .put("eventKey", step.eventKey())
        .put("since", step.since())
        .put("timeoutAt", step.timeoutAt());
  }

  private static Json.Obj node(NodeInfo node) {
    return Json.object()
        .put("id", node.id())
        .put("name", node.name())
        .put("startedAt", node.startedAt())
        .put("lastHeartbeatAt", node.lastHeartbeatAt())
        .put("alive", node.alive());
  }

  private AdminResponse staticFile(AdminRequest request, String name) {
    Optional<StaticFiles.File> file = files.find(name);
    if (file.isEmpty()) {
      return error(404, "Not found: /" + name, Map.of());
    }
    // Revalidated on every load (ETag), so a new version of the module is picked up at once.
    return withETag(
        request,
        200,
        Map.of("Content-Type", file.get().contentType(), "Cache-Control", "no-cache"),
        file.get().content());
  }

  private static AdminResponse json(AdminRequest request, int status, Object body) {
    return withETag(
        request,
        status,
        Map.of("Content-Type", "application/json; charset=utf-8", "Cache-Control", "no-store"),
        Json.write(body).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Adds an {@code ETag} (a hash of the body) and the security headers; answers {@code 304} without
   * a body if the request's {@code If-None-Match} names the same tag.
   */
  private static AdminResponse withETag(
      AdminRequest request, int status, Map<String, String> headers, byte[] body) {
    String etag = etag(body);
    Map<String, String> all = new LinkedHashMap<>(headers);
    all.put("ETag", etag);
    String ifNoneMatch = request.header("If-None-Match");
    if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
      return response(304, all, new byte[0]);
    }
    return response(status, all, body);
  }

  private static AdminResponse error(int status, String message, Map<String, String> headers) {
    Map<String, String> all = new LinkedHashMap<>(headers);
    all.put("Content-Type", "application/json; charset=utf-8");
    all.put("Cache-Control", "no-store");
    return response(
        status,
        all,
        Json.write(Json.object().put("error", message)).getBytes(StandardCharsets.UTF_8));
  }

  private static AdminResponse response(int status, Map<String, String> headers, byte[] body) {
    Map<String, String> all = new LinkedHashMap<>(headers);
    all.put("Content-Security-Policy", CSP);
    all.put("X-Content-Type-Options", "nosniff");
    all.put("Referrer-Policy", "no-referrer");
    return new AdminResponse(status, all, body);
  }

  private static String etag(byte[] body) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
      return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> params = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) {
      return params;
    }
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      String name = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      try {
        params.putIfAbsent(
            URLDecoder.decode(name, StandardCharsets.UTF_8),
            URLDecoder.decode(value, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        throw new BadRequest("Malformed query parameter: " + name);
      }
    }
    return params;
  }

  private static int limit(Map<String, String> params) {
    String limit = params.get("limit");
    if (limit == null || limit.isBlank()) {
      return DEFAULT_LIMIT;
    }
    try {
      int value = Integer.parseInt(limit.trim());
      if (value < 1 || value > JobQuery.MAX_LIMIT) {
        throw new BadRequest("limit must be between 1 and " + JobQuery.MAX_LIMIT);
      }
      return value;
    } catch (NumberFormatException e) {
      throw new BadRequest("limit must be a number: " + limit);
    }
  }

  private static UUID id(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new BadRequest("Malformed id: " + value);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** The position after the last row of a page: its time and id, as {@code time,id}. */
  private record Cursor(Instant time, UUID id) {

    static Cursor parse(String value) {
      if (value == null || value.isBlank()) {
        return null;
      }
      int comma = value.indexOf(',');
      try {
        if (comma > 0) {
          return new Cursor(
              Instant.parse(value.substring(0, comma)),
              UUID.fromString(value.substring(comma + 1)));
        }
      } catch (DateTimeParseException | IllegalArgumentException e) {
        // reported below
      }
      throw new BadRequest("Malformed cursor: " + value);
    }

    @Override
    public String toString() {
      return time + "," + id;
    }
  }
}
