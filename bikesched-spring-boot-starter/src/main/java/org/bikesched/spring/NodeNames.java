// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.core.env.Environment;

/**
 * Resolves the name of the node the starter creates. Names need not be unique, but they tell nodes
 * apart in logs and the admin UI, so the {@code spring.application.name} alone is not the default
 * (several replicas share it): the default is {@code <spring.application.name>-<host name>}, and
 * {@code bikesched.node-name} overrides it.
 */
final class NodeNames {

  private NodeNames() {}

  /**
   * Resolves the node name.
   *
   * @param nodeName the configured {@code bikesched.node-name}, may be {@code null}
   * @param environment the environment, for {@code spring.application.name}
   * @return the node name
   */
  static String resolve(String nodeName, Environment environment) {
    if (nodeName != null && !nodeName.isBlank()) {
      return nodeName;
    }
    String applicationName = environment.getProperty("spring.application.name");
    String hostName = hostName();
    return applicationName == null || applicationName.isBlank()
        ? hostName
        : applicationName + "-" + hostName;
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      String fromEnv = System.getenv("HOSTNAME");
      return fromEnv == null ? "bikesched" : fromEnv;
    }
  }
}
