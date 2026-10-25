// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The cluster node of a {@link Bikesched} instance. A node joins the cluster (is registered in
 * {@code bikesched_nodes} and sends heartbeats) only once it polls for jobs: an instance that only
 * schedules jobs or publishes events never registers and has no id.
 */
public interface Node {

  /**
   * Returns the node's name. It is for logs and the admin UI and need not be unique; the {@link
   * #id() id} identifies the node.
   *
   * @return the node name
   */
  String name();

  /**
   * Returns the node's id once it polls for jobs, new for every instance.
   *
   * @return the node id, {@code null} until {@link Bikesched#start()} was called
   */
  @Nullable UUID id();

  /**
   * Returns whether this node polls for and executes jobs.
   *
   * @return {@code true} once polling was started and until the instance is closed
   */
  boolean isPolling();
}
