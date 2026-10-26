// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/** What happened to an event, see {@link EventEntry}. */
public enum EventAction implements HistoryAction {

  /** Published (not recorded for a publish that returned an earlier event by its key). */
  PUBLISHED
}
