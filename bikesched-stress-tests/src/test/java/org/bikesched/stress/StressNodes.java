// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import java.time.Duration;
import javax.sql.DataSource;
import org.bikesched.BikeschedBuilder;
import org.bikesched.RetryBackoff;
import org.bikesched.test.BikeschedTest;

/** Node settings shared by the failure tests: short timeouts, so failures resolve in seconds. */
final class StressNodes {

  /** Heartbeat interval of the failure tests' nodes. */
  static final Duration HEARTBEAT_INTERVAL = Duration.ofMillis(500);

  /** Node timeout of the failure tests' nodes: other nodes release the jobs of a silent node. */
  static final Duration NODE_TIMEOUT = Duration.ofSeconds(3);

  private StressNodes() {}

  /**
   * A test node with a heartbeat every 500 ms, a node timeout of 3 seconds, a node clean-up every
   * second and a retry after one second, so attempts failed by an outage are retried soon.
   */
  static BikeschedBuilder node(DataSource dataSource, String name) {
    return BikeschedTest.testNode(dataSource, name)
        .heartbeatInterval(HEARTBEAT_INTERVAL)
        .nodeTimeout(NODE_TIMEOUT)
        .nodeCleanUpInterval(Duration.ofSeconds(1))
        .retryBackoff(RetryBackoff.fixed(Duration.ofSeconds(1)));
  }
}
