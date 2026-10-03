package bikesched.core;

import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Builder for {@link Bikesched} instances, obtained via {@link Bikesched#builder()}. Not
 * thread-safe.
 */
public final class BikeschedBuilder {

    private DataSource dataSource;
    private String nodeName;
    private int maxConcurrentJobs = DefaultBikesched.DEFAULT_MAX_CONCURRENT_JOBS;
    private Duration heartbeatInterval = DefaultBikesched.DEFAULT_HEARTBEAT_INTERVAL;
    private Duration nodeTimeout = DefaultBikesched.DEFAULT_NODE_TIMEOUT;
    private Duration pollInterval = DefaultBikesched.DEFAULT_POLL_INTERVAL;
    private boolean autoStartPolling = true;
    private JsonMapper jsonMapper = new JsonStringMapper();

    BikeschedBuilder() {}

    /**
     * Sets the PostgreSQL data source bikesched stores its tables in (required).
     *
     * @param dataSource the data source
     * @return this builder
     */
    public BikeschedBuilder dataSource(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        return this;
    }

    /**
     * Sets the unique name this application instance registers as (optional, defaults to the
     * host name). Only one live node per name is allowed; the name of a dead node (no heartbeat
     * for 30 seconds) is taken over.
     *
     * @param name the node name
     * @return this builder
     */
    public BikeschedBuilder node(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("node name must not be blank");
        }
        this.nodeName = name;
        return this;
    }

    /**
     * Sets the maximum number of jobs this node executes at the same time (optional, default 10).
     * The node only claims jobs from the
     * database while it has free capacity, so jobs it cannot start right away stay available to
     * other nodes.
     *
     * @param maxConcurrentJobs the limit, at least 1
     * @return this builder
     */
    public BikeschedBuilder maxConcurrentJobs(int maxConcurrentJobs) {
        if (maxConcurrentJobs < 1) {
            throw new IllegalArgumentException("maxConcurrentJobs must be >= 1: " + maxConcurrentJobs);
        }
        this.maxConcurrentJobs = maxConcurrentJobs;
        return this;
    }

    /**
     * Sets whether the node starts polling for jobs as soon as it is built (optional, default
     * {@code true}). With {@code false} the node is registered and heartbeats, and jobs can be
     * scheduled, but it executes no jobs until {@link Bikesched#startPolling()} is called, e.g.
     * once all job handlers are registered, or never for a node that only schedules jobs.
     *
     * @param autoStartPolling whether to start polling on {@link #build()}
     * @return this builder
     */
    public BikeschedBuilder autoStartPolling(boolean autoStartPolling) {
        this.autoStartPolling = autoStartPolling;
        return this;
    }

    /**
     * Sets the mapper converting job payloads and step results to and from JSON (optional,
     * default {@link JsonStringMapper}, which only supports strings already containing JSON).
     *
     * @param jsonMapper the mapper
     * @return this builder
     * @see JobBuilder#payload(Object)
     * @see Job#payload(Class)
     * @see Job#step(String, Class, StepHandler)
     */
    public BikeschedBuilder jsonMapper(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        return this;
    }

    /** Heartbeat interval; package-private so tests can speed it up. */
    BikeschedBuilder heartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        return this;
    }

    /**
     * Time without heartbeat after which a node is considered dead; package-private so tests can
     * speed it up.
     */
    BikeschedBuilder nodeTimeout(Duration nodeTimeout) {
        this.nodeTimeout = requirePositive(nodeTimeout, "nodeTimeout");
        return this;
    }

    /** Job poll interval; package-private so tests can speed it up. */
    BikeschedBuilder pollInterval(Duration pollInterval) {
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        return this;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    /**
     * Creates the {@link Bikesched} instance: applies pending schema migrations, registers the
     * node and starts its heartbeat and, unless disabled via {@link #autoStartPolling(boolean)},
     * its job poller.
     *
     * @return the new instance
     * @throws IllegalStateException if no data source was set
     * @throws BikeschedException if the migration or the node registration fails, e.g. because
     *     a live node with the same name is already registered
     */
    public Bikesched build() {
        if (dataSource == null) {
            throw new IllegalStateException("dataSource is required");
        }
        String name = nodeName != null ? nodeName : DefaultBikesched.localHostName();
        var settings = new Settings(
                name, heartbeatInterval, nodeTimeout, pollInterval, maxConcurrentJobs, autoStartPolling);
        return DefaultBikesched.create(new Database(dataSource, jsonMapper), settings);
    }
}
