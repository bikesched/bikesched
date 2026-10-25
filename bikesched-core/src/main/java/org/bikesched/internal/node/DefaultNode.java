// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.node;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bikesched.Node;
import org.jspecify.annotations.Nullable;

/**
 * A node's identity and lifecycle state: its name, its id once registered (polling started) and
 * whether it was closed. The owner coordinates registering and closing (both happen once).
 */
public final class DefaultNode implements Node {

  private final String name;

  /** Set (once) when the node registers; {@code null} until then. */
  private volatile @Nullable UUID id;

  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * @param name the node's name
   */
  public DefaultNode(String name) {
    this.name = name;
  }

  /**
   * Records the id the node registered with.
   *
   * @param id the id
   * @throws IllegalStateException if the node already registered
   */
  public void registered(UUID id) {
    if (this.id != null) {
      throw new IllegalStateException("bikesched node " + name + " is already registered");
    }
    this.id = id;
  }

  /**
   * Marks the node closed.
   *
   * @return {@code true} for the first call only
   */
  public boolean close() {
    return closed.compareAndSet(false, true);
  }

  /**
   * Checks that the node is open.
   *
   * @throws IllegalStateException if the node is closed
   */
  public void requireOpen() {
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + name + " is closed");
    }
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public @Nullable UUID id() {
    return id;
  }

  @Override
  public boolean isPolling() {
    return id != null && !closed.get();
  }

  @Override
  public String toString() {
    return "Node[name=" + name + ", id=" + id + "]";
  }
}
