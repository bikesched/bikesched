package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Scheduling jobs via {@link Bikesched#newJob(String)}, handler registration and transactional scheduling. */
class JobSchedulingTest extends AbstractBikeschedTest {

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
            assertThat(((DefaultJob) job).priority()).as("not exposed to handlers").isEqualTo(7);
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
    void jobScheduledWithCallerConnectionIsPartOfCallersTransaction() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-tx")
                .pollInterval(Duration.ofMillis(50))
                .build();
                java.sql.Connection connection = dataSource.getConnection()) {
            var ran = new java.util.concurrent.CompletableFuture<UUID>();
            bikesched.registerHandler("tx", job -> ran.complete(job.id()));
            connection.setAutoCommit(false);
            UUID otherNodeId = UUID.randomUUID();

            // The caller's own change and the job in one transaction.
            try (var insert = connection.prepareStatement("insert into bikesched_nodes (id, name) values (?, ?)")) {
                insert.setObject(1, otherNodeId);
                insert.setString(2, "node-tx-other");
                insert.executeUpdate();
            }
            UUID jobId = bikesched.newJob("tx").payload("{\"order\": 1}").connection(connection).schedule();
            Thread.sleep(300); // several poll cycles

            assertThat(countRows("bikesched_nodes", "id = '" + otherNodeId + "'")).as("node invisible before commit")
                    .isZero();
            assertThat(count("id = '" + jobId + "'")).as("job invisible before commit").isZero();
            assertThat(ran).isNotDone();
            assertThat(connection.isClosed()).isFalse();
            assertThat(connection.getAutoCommit()).as("auto-commit left untouched").isFalse();

            connection.commit();

            assertThat(countRows("bikesched_nodes", "id = '" + otherNodeId + "' and name = 'node-tx-other'"))
                    .as("node committed")
                    .isEqualTo(1);
            assertThat(count("id = '" + jobId + "' and job_type = 'tx'")).as("job committed").isEqualTo(1);
            assertThat(ran.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(jobId);
            execute("delete from bikesched_nodes where id = '" + otherNodeId + "'");
        }
    }

    @Test
    void jobScheduledWithCallerConnectionIsDiscardedOnRollback() throws Exception {
        try (Bikesched bikesched = Bikesched.builder()
                .dataSource(dataSource)
                .node("node-tx-rollback")
                .pollInterval(Duration.ofMillis(50))
                .build();
                java.sql.Connection connection = dataSource.getConnection()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            bikesched.registerHandler("tx-rollback", calls::incrementAndGet);
            connection.setAutoCommit(false);

            UUID jobId = bikesched.newJob("tx-rollback").connection(connection).schedule(Duration.ZERO);
            try (var statement = connection.createStatement();
                    var rs = statement.executeQuery("select count(*) from bikesched_jobs where id = '" + jobId + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("visible inside the caller's transaction").isEqualTo(1);
            }
            connection.rollback();
            Thread.sleep(300); // several poll cycles

            assertThat(count("id = '" + jobId + "'")).isZero();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void failingInsertWithCallerConnectionLeavesTransactionToCaller() throws Exception {
        try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).node("node-tx-invalid").build();
                java.sql.Connection connection = dataSource.getConnection()) {
            bikesched.registerHandler("tx-invalid", () -> {});
            connection.setAutoCommit(false);

            assertThatThrownBy(() -> bikesched.newJob("tx-invalid").payload("not json").connection(connection).schedule())
                    .isInstanceOf(BikeschedException.class)
                    .hasMessage("Scheduling bikesched job of type 'tx-invalid' failed");

            assertThat(connection.isClosed()).isFalse();
            connection.rollback(); // the caller decides; the connection is usable again
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("select 1")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }
}
