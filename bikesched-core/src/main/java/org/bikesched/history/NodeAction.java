// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/** What happened to a node, see {@link NodeEntry}. */
public enum NodeAction implements HistoryAction {

  /** Registered when it started polling (again, after another node removed it). */
  REGISTERED,

  /** Stopped polling and deregistered when it was closed. */
  STOPPED,

  /** Removed by another node's clean-up after it sent no heartbeat for the node timeout. */
  REMOVED
}
