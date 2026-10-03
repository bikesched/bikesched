package bikesched.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The running attempt of a job and its timeout timer, shared between the job's thread, the
 * timeout thread and the handler ({@link Job#updateTimeout(Duration)}). Synchronized so the timer
 * can never interrupt the job's thread after the handler returned, i.e. while it records the
 * outcome.
 */
final class JobAttempt {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultBikesched.class);

    private final Job job;
    private final Thread thread;
    private final ScheduledExecutorService timers;
    private final String nodeId;
    private Duration timeout;
    private ScheduledFuture<?> timer;
    private boolean finished;
    private boolean timedOut;

    JobAttempt(Job job, Thread thread, ScheduledExecutorService timers, String nodeId) {
        this.job = Objects.requireNonNull(job, "job");
        this.thread = Objects.requireNonNull(thread, "thread");
        this.timers = Objects.requireNonNull(timers, "timers");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    /**
     * (Re)starts the timer: the attempt times out once the given duration has passed from now,
     * replacing any earlier deadline.
     *
     * @throws IllegalStateException if the attempt already finished or timed out
     */
    synchronized void startTimeout(Duration timeout) {
        if (finished) {
            throw new IllegalStateException("Job " + job.id() + " is no longer running");
        }
        if (timedOut) {
            throw new IllegalStateException("Job " + job.id() + " already timed out after " + this.timeout);
        }
        if (timer != null) {
            timer.cancel(false);
        }
        this.timeout = timeout;
        try {
            timer = timers.schedule(this::timeOut, timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            timer = null; // closing: the job executor's shutdown interrupts long-running jobs instead
        }
    }

    /** The timeout currently enforced, or {@code null} if none. */
    synchronized Duration timeout() {
        return timeout;
    }

    /** Called by the timer: marks the attempt timed out and interrupts it, unless it finished. */
    private void timeOut() {
        Duration exceeded;
        synchronized (this) {
            if (finished || timedOut) {
                return;
            }
            timedOut = true;
            exceeded = timeout;
            thread.interrupt();
        }
        try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, nodeId);
                var _ = MDC.putCloseable(BikeschedLogContext.JOB_ID, job.id().toString());
                var _ = MDC.putCloseable(BikeschedLogContext.JOB_TYPE, job.type())) {
            LOG.warn("Job {} of type {} exceeded its timeout of {} (attempt {} of {}), interrupting it",
                    job.id(), job.type(), exceeded, job.attempts(), job.maxAttempts());
        }
    }

    /**
     * Called by the job's thread once the handler returned or threw: stops the timer.
     *
     * @return whether the attempt timed out
     */
    synchronized boolean finish() {
        finished = true;
        if (timer != null) {
            timer.cancel(false);
        }
        return timedOut;
    }
}
