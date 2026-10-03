package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class BikeschedTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static PGSimpleDataSource dataSource;

    @BeforeAll
    static void createDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
    }

    @Test
    void buildMigratesSchemaAndRegistersNode() throws SQLException {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-a").build()) {
            assertThat(bikesched.nodeName()).isEqualTo("node-a");
            assertThat(nodeName(bikesched.nodeId())).isEqualTo("node-a");
        }
    }

    @Test
    void nodeNameDefaultsToHostName() throws Exception {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).build()) {
            String hostName = InetAddress.getLocalHost().getHostName();
            assertThat(bikesched.nodeName()).isEqualTo(hostName);
            assertThat(nodeName(bikesched.nodeId())).isEqualTo(hostName);
        }
    }

    @Test
    void heartbeatUpdatesNode() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-heartbeat")
                .heartbeatInterval(Duration.ofMillis(100))
                .build()) {
            OffsetDateTime registered = lastHeartbeat(bikesched.nodeId());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!lastHeartbeat(bikesched.nodeId()).isAfter(registered) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(lastHeartbeat(bikesched.nodeId())).isAfter(registered);
        }
    }

    @Test
    void closeStopsHeartbeatAndDeregistersNode() throws Exception {
        Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-closing")
                .heartbeatInterval(Duration.ofMillis(50))
                .build();
        bikesched.close();
        bikesched.close(); // idempotent

        assertThat(nodeName(bikesched.nodeId())).isNull();
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(t -> (t.getName().equals("bikesched-heartbeat") || t.getName().equals("bikesched-poller"))
                        && t.isAlive());
    }

    @Test
    void rejectsSecondLiveNodeWithSameName() {
        try (Bikesched first = Bikesched.builder().dataSource(dataSource).node("node-dup").build()) {
            assertThatThrownBy(() -> Bikesched.builder().dataSource(dataSource).node("node-dup").build())
                    .isInstanceOf(BikeschedException.class)
                    .hasMessageContaining("live bikesched node named 'node-dup' is already registered");
            assertThat(first.nodeName()).isEqualTo("node-dup");
        }
    }

    @Test
    void takesOverStaleNodeWithSameName() throws SQLException {
        Bikesched.builder().dataSource(dataSource).node("bootstrap").build().close(); // ensure schema
        UUID staleId = UUID.randomUUID();
        try (var connection = dataSource.getConnection();
                var insert = connection.prepareStatement("""
                        insert into bikesched_nodes (id, name, last_heartbeat_at)
                        values (?, 'node-restarted', now() - interval '1 hour')""")) {
            insert.setObject(1, staleId);
            insert.executeUpdate();
        }
        // A job the crashed previous instance was still running (scheduled in the future so the
        // new instance's poller doesn't immediately execute it after the release).
        execute("insert into bikesched_jobs (id, job_type, state, locked_by, scheduled_at) values "
                + "(gen_random_uuid(), 'orphan-restart', 'RUNNING', '" + staleId + "', now() + interval '1 hour')");

        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-restarted").build()) {
            assertThat(bikesched.nodeId()).isEqualTo(staleId);
            assertThat(count("job_type = 'orphan-restart' and state = 'SCHEDULED' and locked_by is null"))
                    .as("jobs left running by the previous instance are released on startup")
                    .isEqualTo(1);
        }
        execute("delete from bikesched_jobs where job_type = 'orphan-restart'");
    }

    @Test
    void heartbeatReleasesJobsOfDeadNodesAndThenRemovesThem() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        UUID deadNode = UUID.randomUUID();
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-reaper")
                .heartbeatInterval(Duration.ofMillis(50))
                .nodeTimeout(Duration.ofSeconds(1))
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            bikesched.registerHandler("orphan", () -> {});
            execute("insert into bikesched_nodes (id, name, last_heartbeat_at) "
                    + "values ('" + deadNode + "', 'node-dead', now() - interval '1 minute')");
            execute("insert into bikesched_jobs (id, job_type, state, locked_by) select gen_random_uuid(), "
                    + "'orphan', 'RUNNING', '" + deadNode + "' from generate_series(1, 2)");

            // Released by the heartbeat, then claimed and executed by this node's poller; the dead
            // node itself is removed once its jobs no longer reference it.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while ((count("job_type = 'orphan' and state = 'SUCCEEDED'") < 2 || nodeName(deadNode) != null)
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("job_type = 'orphan' and state = 'SUCCEEDED'")).isEqualTo(2);
            assertThat(nodeName(deadNode)).isNull();
        } finally {
            logger.detachAppender(logs);
        }

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        int released = indexOfMatch(messages,
                "Released 2 running job\\(s\\) of dead bikesched node node-dead \\(.*\\): no heartbeat for PT1S");
        int removed = indexOfMatch(messages, "Removed 1 dead bikesched node\\(s\\) without heartbeat for more than PT2S: "
                + "node-dead \\(" + deadNode + ", last heartbeat .*\\)");
        assertThat(released).as("jobs released").isNotNegative();
        assertThat(removed).as("node removed after its jobs were released").isGreaterThan(released);
    }

    private static int indexOfMatch(List<String> messages, String regex) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).matches(regex)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void nodeTimeoutMustCoverAtLeastTwoHeartbeats() {
        assertThatThrownBy(() -> Bikesched.builder()
                        .dataSource(dataSource)
                        .heartbeatInterval(Duration.ofSeconds(5))
                        .nodeTimeout(Duration.ofSeconds(9))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeTimeout");
    }

    @Test
    void pollerClaimsJobsInBatchesAndRunsHandlersOnVirtualThreads() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var handled = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-worker")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            bikesched.registerHandler("print-batch", job -> handled.add(
                    (Thread.currentThread().isVirtual() ? "virtual " : "platform ")
                            + Thread.currentThread().getName() + " " + job.type() + " " + job.payload()));
            execute("insert into bikesched_jobs (id, job_type, payload) "
                    + "select gen_random_uuid(), 'print-batch', jsonb_build_object('n', n) from generate_series(1, 25) n");

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (count("job_type = 'print-batch' and state = 'SUCCEEDED'") < 25 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("job_type = 'print-batch' and state = 'SUCCEEDED'")).isEqualTo(25);
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(handled).hasSize(25).allSatisfy(entry -> assertThat(entry)
                .matches("virtual bikesched-job-\\d+ print-batch \\{\"n\": \\d+}"));
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                        && e.getFormattedMessage().matches("Executing job .* of type print-batch \\(attempt 1 of 3\\) "
                                + "on bikesched node node-worker \\(.*\\)"))
                .as("debug log before each job execution, on the job's virtual thread")
                .hasSize(25)
                .allSatisfy(e -> assertThat(e.getThreadName()).startsWith("bikesched-job-"));
        var claimed = java.util.regex.Pattern.compile(
                "bikesched node node-worker \\([0-9a-f-]{36}\\) claimed (\\d+) job\\(s\\)");
        assertThat(logs.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .map(claimed::matcher)
                        .filter(java.util.regex.Matcher::matches)
                        .map(m -> Integer.parseInt(m.group(1))))
                .as("poll batch sizes")
                .allSatisfy(size -> assertThat(size).isBetween(1, 10))
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void closeReleasesJobsThatAreStillRunning() throws Exception {
        Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-releasing").build();
        // Simulate a job claimed by this node whose processing never finished.
        execute("insert into bikesched_jobs (id, job_type, state, locked_by) "
                + "values (gen_random_uuid(), 'stuck', 'RUNNING', '" + bikesched.nodeId() + "')");

        bikesched.close();

        assertThat(count("job_type = 'stuck' and state = 'SCHEDULED' and locked_by is null")).isEqualTo(1);
        assertThat(nodeName(bikesched.nodeId())).isNull();
        execute("delete from bikesched_jobs where job_type = 'stuck'");
    }

    @Test
    void scheduledJobIsProcessed() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-scheduling")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var received = new java.util.concurrent.CompletableFuture<Job>();
            bikesched.registerHandler("print", received::complete);
            UUID jobId = bikesched.newJob("print").payload("{\"greeting\": \"hello\"}").schedule();

            Job job = received.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(job.id()).isEqualTo(jobId);
            assertThat(job.payload()).isEqualTo("{\"greeting\": \"hello\"}");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (count("id = '" + jobId + "' and state = 'SUCCEEDED'") == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("id = '" + jobId + "' and state = 'SUCCEEDED' and job_type = 'print' "
                    + "and payload = '{\"greeting\": \"hello\"}'::jsonb")).isEqualTo(1);
        }
    }

    @Test
    void runnableHandlerIsSupported() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-runnable")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var ran = new java.util.concurrent.CountDownLatch(1);
            bikesched.registerHandler("ping", ran::countDown);
            bikesched.newJob("ping").schedule();

            assertThat(ran.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void schedulingUnknownJobTypeFails() {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-unknown").build()) {
            bikesched.registerHandler("known", () -> {});

            assertThatThrownBy(() -> bikesched.newJob("unknown").schedule())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown job type 'unknown': no handler registered (known types: [known])");
        }
    }

    @Test
    void handlerCanOnlyBeRegisteredOncePerType() {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-duplicate").build()) {
            bikesched.registerHandler("once", () -> {});

            assertThatThrownBy(() -> bikesched.registerHandler("once", () -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already registered");
            assertThatThrownBy(() -> bikesched.registerHandler(" ", () -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void jobWithoutHandlerOnClaimingNodeLogsErrorAndFails() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-missing-handler")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            bikesched.registerHandler("handled-here", () -> {});
            // Scheduled by some other node that has a handler this node lacks; single attempt.
            execute("insert into bikesched_jobs (id, job_type, max_attempts) "
                    + "values (gen_random_uuid(), 'handled-elsewhere', 1)");

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (count("job_type = 'handled-elsewhere' and state = 'FAILED'") == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("job_type = 'handled-elsewhere' and state = 'FAILED' and last_error like "
                    + "'%No handler registered for job type ''handled-elsewhere''%'")).isEqualTo(1);
            execute("delete from bikesched_jobs where job_type = 'handled-elsewhere'");
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches("No handler registered for job type handled-elsewhere on bikesched node "
                        + "node-missing-handler \\(.*\\), cannot run job .*"));
    }

    @Test
    void scheduleWithHandlerRegistersItAndRunsTheJob() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-inline-handler")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var received = new java.util.concurrent.CompletableFuture<Job>();
            UUID jobId = bikesched.newJob("inline").payload("{\"x\": 1}").handler(received::complete).schedule();

            assertThat(received.get(10, java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(jobId);
            // The handler is registered under the job type now: scheduling by name works as well.
            assertThat(bikesched.newJob("inline").schedule()).isNotNull();
            assertThatThrownBy(() -> bikesched.registerHandler("inline", () -> {}))
                    .hasMessageContaining("already registered");
        }
    }

    @Test
    void scheduleWithHandlerKeepsAlreadyRegisteredHandler() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-keep-handler")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var first = new java.util.concurrent.CompletableFuture<UUID>();
            bikesched.registerHandler("keep", job -> first.complete(job.id()));

            UUID jobId = bikesched.newJob("keep").handler(job -> {
                throw new AssertionError("second handler must not be used");
            }).schedule();

            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(jobId);
        }
    }

    @Test
    void pollingCanBeStartedLater() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-manual-polling")
                .pollInterval(Duration.ofMillis(50))
                .autoStartPolling(false)
                .build()) {
            var ran = new java.util.concurrent.CountDownLatch(1);
            UUID jobId = bikesched.newJob("manual").handler(job -> ran.countDown()).schedule();
            Thread.sleep(300); // several poll intervals

            assertThat(bikesched.isPolling()).isFalse();
            assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED' and attempts = 0"))
                    .as("not claimed while polling is off")
                    .isEqualTo(1);

            bikesched.startPolling();
            bikesched.startPolling(); // no effect while already polling

            assertThat(bikesched.isPolling()).isTrue();
            assertThat(ran.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void startPollingAfterCloseFails() {
        Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-closed-polling")
                .autoStartPolling(false)
                .build();
        bikesched.close();

        assertThat(bikesched.isPolling()).isFalse();
        assertThatThrownBy(bikesched::startPolling).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newJobBuilderSchedulesJobWithHandlerPriorityAndMaxAttempts() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-job-builder")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var received = new java.util.concurrent.CompletableFuture<Job>();

            UUID jobId = bikesched.newJob("built")
                    .payload("{\"x\": 1}")
                    .handler(received::complete)
                    .priority(7)
                    .maxAttempts(5)
                    .schedule();

            Job job = received.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(job.id()).isEqualTo(jobId);
            assertThat(job.type()).isEqualTo("built");
            assertThat(job.payload()).isEqualTo("{\"x\": 1}");
            assertThat(job.priority()).isEqualTo(7);
            assertThat(job.maxAttempts()).isEqualTo(5);
        }
    }

    @Test
    void newJobBuilderSchedulesAtInstantOrAfterDelay() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-job-builder-later")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            Instant inOneHour = Instant.now().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            JobBuilder later = bikesched.newJob("later").handler(() -> {});

            UUID atInstant = later.schedule(inOneHour);
            UUID afterDelay = later.schedule(Duration.ofHours(2));
            Thread.sleep(300); // several poll cycles

            assertThat(atInstant).isNotEqualTo(afterDelay);
            assertThat(count("id = '" + atInstant + "' and state = 'SCHEDULED' "
                    + "and scheduled_at = '" + inOneHour + "'::timestamptz")).isEqualTo(1);
            assertThat(count("id = '" + afterDelay + "' and state = 'SCHEDULED' "
                    + "and scheduled_at between now() + interval '119 minutes' and now() + interval '121 minutes'"))
                    .isEqualTo(1);
            execute("delete from bikesched_jobs where job_type = 'later'");
        }
    }

    @Test
    void newJobBuilderValidatesInput() {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-job-builder-invalid").build()) {
            assertThatThrownBy(() -> bikesched.newJob(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> bikesched.newJob("x").maxAttempts(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> bikesched.newJob("x").handler(() -> {}).schedule(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("delay must not be negative");
            assertThatThrownBy(() -> bikesched.newJob("no-handler").schedule())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Unknown job type 'no-handler'");
        }
    }

    @Test
    void logContextContainsNodeIdAndJobId() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        UUID nodeId;
        UUID jobId;
        var seenByHandler = new java.util.concurrent.CompletableFuture<java.util.Map<String, String>>();
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-mdc")
                .heartbeatInterval(Duration.ofMillis(50))
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            nodeId = bikesched.nodeId();
            jobId = bikesched.newJob("mdc")
                    .handler(() -> seenByHandler.complete(org.slf4j.MDC.getCopyOfContextMap()))
                    .schedule();

            assertThat(seenByHandler.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("visible inside the job handler")
                    .containsEntry(BikeschedLogContext.NODE_ID, nodeId.toString())
                    .containsEntry(BikeschedLogContext.JOB_ID, jobId.toString())
                    .containsEntry(BikeschedLogContext.JOB_TYPE, "mdc");
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list)
                .filteredOn(e -> e.getFormattedMessage().startsWith("Executing job " + jobId))
                .singleElement()
                .satisfies(e -> assertThat(e.getMDCPropertyMap())
                        .containsEntry(BikeschedLogContext.NODE_ID, nodeId.toString())
                        .containsEntry(BikeschedLogContext.JOB_ID, jobId.toString())
                        .containsEntry(BikeschedLogContext.JOB_TYPE, "mdc"));
        assertThat(logs.list)
                .filteredOn(e -> e.getThreadName().equals("bikesched-poller"))
                .as("poller logs carry the node id but no job")
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getMDCPropertyMap())
                        .containsEntry(BikeschedLogContext.NODE_ID, nodeId.toString())
                        .doesNotContainKeys(BikeschedLogContext.JOB_ID, BikeschedLogContext.JOB_TYPE));
    }

    @Test
    void handlerCanCancelJobWithMessageAndItIsNotRetried() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-cancel")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var continuedAfterCancel = new java.util.concurrent.atomic.AtomicBoolean();
            UUID jobId = bikesched.newJob("cancel-me").maxAttempts(5).handler(job -> {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("first attempt fails");
                }
                job.cancel("customer deleted");
                assertThat(job.isCancelled()).isTrue();
                assertThat(job.cancelMessage()).isEqualTo("customer deleted");
                continuedAfterCancel.set(true); // cancel() does not stop the handler
            }).schedule();

            awaitState(jobId, "CANCELLED");
            Thread.sleep(300); // several poll cycles: a cancelled job is not picked up again

            assertThat(calls).hasValue(2);
            assertThat(continuedAfterCancel).isTrue();
            assertThat(count("id = '" + jobId + "' and state = 'CANCELLED' and attempts = 2 and locked_by is null "
                    + "and locked_at is null and finished_at is not null and last_error = 'customer deleted'"))
                    .isEqualTo(1);
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                        && e.getFormattedMessage().matches("Job .* of type cancel-me cancelled by its handler "
                                + "\\(attempt 2 of 5\\): customer deleted"))
                .hasSize(1);
    }

    @Test
    void handlerCanCancelJobWithoutMessage() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-cancel-silent")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            UUID jobId = bikesched.newJob("cancel-silent").handler(job -> {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("first attempt fails");
                }
                job.cancel();
            }).schedule();

            awaitState(jobId, "CANCELLED");

            assertThat(count("id = '" + jobId + "' and state = 'CANCELLED' and last_error is null"))
                    .as("the earlier failure is cleared")
                    .isEqualTo(1);
        }
    }

    @Test
    void cancelledJobStaysCancelledWhenHandlerThrowsAfterwards() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        UUID jobId;
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-cancel-throw")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            jobId = bikesched.newJob("cancel-throw").handler(job -> {
                job.cancel("nope");
                throw new java.io.IOException("cleanup failed");
            }).schedule();

            awaitState(jobId, "CANCELLED");

            assertThat(count("id = '" + jobId + "' and attempts = 1 and last_error = 'nope'")).isEqualTo(1);
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().equals("Job " + jobId + " of type cancel-throw was cancelled, "
                                + "ignoring the exception thrown by its handler afterwards"))
                .singleElement()
                .satisfies(e -> assertThat(e.getThrowableProxy().getMessage()).isEqualTo("cleanup failed"));
    }

    private void awaitState(UUID jobId, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (count("id = '" + jobId + "' and state = '" + state + "'") == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(count("id = '" + jobId + "' and state = '" + state + "'")).as("job in state " + state).isEqualTo(1);
    }

    @Test
    void failingHandlerIsRetriedUntilMaxAttemptsThenMarkedFailed() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-failing")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            bikesched.registerHandler("explode", job -> {
                calls.incrementAndGet();
                throw new java.io.IOException("attempt " + job.attempts() + " exploded");
            });
            UUID jobId = bikesched.newJob("explode").schedule();

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (count("id = '" + jobId + "' and state = 'FAILED'") == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("id = '" + jobId + "' and state = 'FAILED' and attempts = 3 and locked_by is null "
                    + "and last_error = 'java.io.IOException: attempt 3 exploded'")).isEqualTo(1);
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void jobScheduledInTheFutureIsNotProcessedEarly() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-future")
                .pollInterval(Duration.ofMillis(50))
                .build()) {
            Instant inOneHour = Instant.now().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            bikesched.registerHandler("print-later", () -> {});
            UUID jobId = bikesched.newJob("print-later").schedule(inOneHour);
            Thread.sleep(300); // several poll cycles

            assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED' and payload is null "
                    + "and scheduled_at = '" + inOneHour + "'::timestamptz")).isEqualTo(1);
            execute("delete from bikesched_jobs where id = '" + jobId + "'");
        }
    }

    @Test
    void scheduleRejectsInvalidInput() {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-invalid").build()) {
            bikesched.registerHandler("print", () -> {});
            assertThatThrownBy(() -> bikesched.newJob("print").payload("not json").schedule())
                    .isInstanceOf(BikeschedException.class)
                    .hasMessageContaining("Scheduling bikesched job of type 'print' failed");
            assertThatThrownBy(() -> bikesched.newJob(" ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void scheduleAfterCloseFails() {
        Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-closed").build();
        bikesched.close();

        assertThatThrownBy(() -> bikesched.newJob("print").schedule()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverRunsMoreThanMaxConcurrentJobs() throws Exception {
        assertThat(runSlowJobs("slow-default", 30, null)).isEqualTo(DefaultBikesched.DEFAULT_MAX_CONCURRENT_JOBS);
    }

    @Test
    void claimsOnlyAsManyJobsAsThereAreFreeSlots() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultBikesched.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThat(runSlowJobs("slow-limited", 12, 3)).isEqualTo(3);
        } finally {
            logger.detachAppender(logs);
        }
        var claimed = java.util.regex.Pattern.compile("bikesched node node-slow-limited \\(.*\\) claimed (\\d+) job\\(s\\)");
        assertThat(logs.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .map(claimed::matcher)
                        .filter(java.util.regex.Matcher::matches)
                        .map(m -> Integer.parseInt(m.group(1))))
                .as("claim sizes never exceed the free slots")
                .isNotEmpty()
                .allSatisfy(size -> assertThat(size).isBetween(1, 3));
        var polling = java.util.regex.Pattern.compile(
                "bikesched node node-slow-limited \\(.*\\) polling for up to (\\d+) job\\(s\\), (\\d+) free slot\\(s\\)");
        assertThat(logs.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .map(polling::matcher)
                        .filter(java.util.regex.Matcher::matches))
                .as("polls try to claim min(free slots, batch size)")
                .isNotEmpty()
                .allSatisfy(m -> {
                    assertThat(Integer.parseInt(m.group(1))).isBetween(1, 3);
                    assertThat(m.group(1)).isEqualTo(m.group(2));
                });
        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("polls are skipped while all slots are busy")
                .anyMatch(m -> m.matches("bikesched node node-slow-limited \\(.*\\) is at capacity, skipping poll"));
    }

    @Test
    void maxConcurrentJobsMustBePositive() {
        assertThatThrownBy(() -> Bikesched.builder().maxConcurrentJobs(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Runs {@code count} jobs that each take 200 ms on a node with the given limit (default if
     * null), waits until all succeeded and returns the peak number of jobs running at once.
     */
    private static int runSlowJobs(String jobType, int count, Integer maxConcurrentJobs) throws Exception {
        var running = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var builder = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-" + jobType)
                .pollInterval(Duration.ofMillis(20));
        if (maxConcurrentJobs != null) {
            builder.maxConcurrentJobs(maxConcurrentJobs);
        }
        try (Bikesched bikesched = builder.build()) {
            bikesched.registerHandler(jobType, () -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            });
            JobBuilder slowJob = bikesched.newJob(jobType);
            for (int i = 0; i < count; i++) {
                slowJob.schedule();
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (count("job_type = '" + jobType + "' and state = 'SUCCEEDED'") < count && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(count("job_type = '" + jobType + "' and state = 'SUCCEEDED'")).isEqualTo(count);
        }
        return peak.get();
    }

    @Test
    void buildRequiresDataSource() {
        assertThatThrownBy(() -> Bikesched.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dataSource is required");
    }

    @Test
    void nodeNameMustNotBeBlank() {
        assertThatThrownBy(() -> Bikesched.builder().node(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String where) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery("select count(*) from bikesched_jobs where " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String nodeName(UUID nodeId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var select = connection.prepareStatement("select name from bikesched_nodes where id = ?")) {
            select.setObject(1, nodeId);
            try (var rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static OffsetDateTime lastHeartbeat(UUID nodeId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var select = connection.prepareStatement("select last_heartbeat_at from bikesched_nodes where id = ?")) {
            select.setObject(1, nodeId);
            try (var rs = select.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class);
            }
        }
    }
}
