// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import org.bikesched.Bikesched;
import org.bikesched.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the node with the application and stops it with the context: {@link #start()} migrates the
 * schema (unless {@code bikesched.migrate-on-startup=false}) and starts polling (unless {@code
 * bikesched.auto-start.enabled=false}), {@link #stop()} only marks the node as no longer running —
 * closing it is the {@code Bikesched} bean's destroy method, which makes Spring close the node
 * before the {@code DataSource} it was built from.
 *
 * <p>The phase is {@link Integer#MAX_VALUE}: polling starts after every other lifecycle bean (a web
 * server, a migration tool, the application's own executors) is up, and stops first.
 */
final class BikeschedLifecycle implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(BikeschedLifecycle.class);

  private final Bikesched bikesched;
  private final BikeschedProperties properties;
  private final JobHandlerBeans jobHandlerBeans;
  private volatile boolean running;

  BikeschedLifecycle(
      Bikesched bikesched, BikeschedProperties properties, JobHandlerBeans jobHandlerBeans) {
    this.bikesched = bikesched;
    this.properties = properties;
    this.jobHandlerBeans = jobHandlerBeans;
  }

  @Override
  public void start() {
    if (properties.migrateOnStartupEnabled()) {
      bikesched.migrate();
      LOG.debug("bikesched migrated and validated the schema of node {}", bikesched.node().name());
    }
    if (!properties.autoStartEnabled()) {
      LOG.info(
          "bikesched polls with no node: bikesched.auto-start.enabled is false, so node {} is not"
              + " registered, executes no jobs and sends no heartbeats; call start() to"
              + " start it",
          bikesched.node().name());
      return;
    }
    Node node = bikesched.start();
    running = true;
    LOG.info("bikesched node {} ({}) polls for jobs", node.name(), node.id().orElse(null));
  }

  @Override
  public void stop() {
    jobHandlerBeans.nodeClosed();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
