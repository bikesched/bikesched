// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered node, see {@link Admin#findNodes()}.
 *
 * @param id the node id
 * @param name the node name
 * @param startedAt when it registered
 * @param lastHeartbeatAt its last heartbeat
 * @param alive whether its last heartbeat is within the node timeout of the node reading it; other
 *     nodes release the jobs of a dead node
 */
public record NodeInfo(
    UUID id, String name, Instant startedAt, Instant lastHeartbeatAt, boolean alive) {}
