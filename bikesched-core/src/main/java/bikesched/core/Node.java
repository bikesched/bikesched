package bikesched.core;

import java.util.Optional;
import java.util.UUID;

/**
 * The cluster node of a {@link Bikesched} instance. A node joins the cluster (is registered in
 * {@code bikesched_nodes} and sends heartbeats) only once it polls for jobs: an instance that only
 * schedules jobs or publishes events never registers and has no id.
 */
public interface Node {

  /**
   * Returns the node's name, unique among the live nodes once it polls.
   *
   * @return the node name
   */
  String name();

  /**
   * Returns the node's id once it polls for jobs. A node restarted under the name of a dead node
   * takes over that node's id.
   *
   * @return the node id, empty until {@link Bikesched#startPolling()} was called
   */
  Optional<UUID> id();

  /**
   * Returns whether this node polls for and executes jobs.
   *
   * @return {@code true} once polling was started and until the instance is closed
   */
  boolean isPolling();
}
