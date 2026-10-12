// SPDX-License-Identifier: Apache-2.0
/**
 * Admin UI and JSON API for bikesched: {@link org.bikesched.admin.web.BikeschedAdmin} serves a
 * small page showing jobs, steps, events and nodes, with retry and cancel, through the JDK's {@code
 * com.sun.net.httpserver} or any framework.
 */
module org.bikesched.admin.web {
  requires transitive org.bikesched;
  requires transitive jdk.httpserver;
  requires org.slf4j;

  exports org.bikesched.admin.web;
  exports org.bikesched.admin.web.access;
}
