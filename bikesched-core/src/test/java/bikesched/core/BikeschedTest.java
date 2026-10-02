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
            UUID jobId = bikesched.schedule("print", "{\"greeting\": \"hello\"}");

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
            bikesched.schedule("ping", null);

            assertThat(ran.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void schedulingUnknownJobTypeFails() {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-unknown").build()) {
            bikesched.registerHandler("known", () -> {});

            assertThatThrownBy(() -> bikesched.schedule("unknown", null))
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
            UUID jobId = bikesched.schedule("inline", "{\"x\": 1}", received::complete);

            assertThat(received.get(10, java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(jobId);
            // The handler is registered under the job type now: scheduling by name works as well.
            assertThat(bikesched.schedule("inline", null)).isNotNull();
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

            UUID jobId = bikesched.schedule("keep", null, job -> {
                throw new AssertionError("second handler must not be used");
            });

            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(jobId);
        }
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
            UUID jobId = bikesched.schedule("explode", null);

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
            UUID jobId = bikesched.schedule("print-later", null, inOneHour);
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
            assertThatThrownBy(() -> bikesched.schedule("print", "not json"))
                    .isInstanceOf(BikeschedException.class)
                    .hasMessageContaining("Scheduling bikesched job of type 'print' failed");
            assertThatThrownBy(() -> bikesched.schedule(" ", null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void scheduleAfterCloseFails() {
        Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-closed").build();
        bikesched.close();

        assertThatThrownBy(() -> bikesched.schedule("print", null)).isInstanceOf(IllegalStateException.class);
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
            for (int i = 0; i < count; i++) {
                bikesched.schedule(jobType, null);
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
