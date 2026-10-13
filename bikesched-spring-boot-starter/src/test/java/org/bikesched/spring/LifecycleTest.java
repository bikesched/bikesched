// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The lifecycle of the node: startup migration, polling and shutdown. */
class LifecycleTest extends AbstractSpringTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void closingTheContextDeregistersTheNode() {
    String nodeName = unique("lifecycle-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.node-name=" + nodeName)
        .run(
            context -> {
              assertThat(context.getBean(Bikesched.class).node().isPolling()).isTrue();
              assertThat(countRows("bikesched_nodes", "name = '" + nodeName + "'")).isOne();
            });
    // The context is closed: the node is deregistered, its row removed.
    await()
        .untilAsserted(
            () -> assertThat(countRows("bikesched_nodes", "name = '" + nodeName + "'")).isZero());
  }
}
