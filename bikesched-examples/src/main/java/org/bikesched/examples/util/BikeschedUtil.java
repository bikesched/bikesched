// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.util;

import java.time.Duration;
import org.bikesched.Bikesched;
import org.bikesched.jackson.JacksonJsonMapper;

/** A configured {@link Bikesched} node shared by the examples' main classes. */
public final class BikeschedUtil {

  private BikeschedUtil() {}

  /**
   * Returns a node on the compose database (see {@link DatabaseUtil#composeDatabase()}) with a JSON
   * mapper and a poll interval of one second, so jobs scheduled "now" start promptly.
   *
   * @param nodeName the name of the node; unique per running example
   * @return the node
   */
  public static Bikesched bikesched(String nodeName) {
    return Bikesched.builder()
        .dataSource(DatabaseUtil.composeDatabase())
        .nodeName(nodeName)
        .pollInterval(Duration.ofSeconds(1))
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
