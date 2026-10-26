// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/**
 * What happened in a {@link HistoryEntry}: one enum per type of entry. Switch over it, or over the
 * entry, whose records narrow it to their enum.
 */
public sealed interface HistoryAction permits JobAction, StepAction, EventAction, NodeAction {

  /**
   * Returns the action's name, as stored.
   *
   * @return the enum constant's name, e.g. {@code CLAIMED}
   */
  String name();
}
