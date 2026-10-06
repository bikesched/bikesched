// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.core.env.Environment;

/**
 * Resolves the name of the node the starter creates. It must be unique per running instance, so the
 * {@code spring.application.name} alone is not enough (several replicas share it, and the second
 * instance of a live name fails to start): the default is {@code <spring.application.name>-<host
 * name>}, which is unique per host or pod, and {@code bikesched.node-name} overrides it.
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
