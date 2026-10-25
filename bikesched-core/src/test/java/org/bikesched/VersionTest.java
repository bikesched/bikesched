// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The library version exposed to applications embedding bikesched. */
class VersionTest extends AbstractBikeschedTest {

  @Test
  void versionIsFilledInByTheBuild() {
    assertThat(Bikesched.version())
        .as("the build fills the project version into version.properties")
        .matches("\\d+\\.\\d+\\.\\d+(-.+)?");
  }

  @Test
  void startLogsTheVersion() {
    var logs = LogCapture.of(org.bikesched.internal.DefaultBikesched.class);
    try (logs) {
      Bikesched.builder(dataSource).nodeName("node-version").build().close();
    }
    assertThat(logs.messages())
        .anyMatch(m -> m.endsWith("(bikesched " + Bikesched.version() + ")"));
  }
}
