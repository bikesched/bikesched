// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/**
 * Who made a change recorded in a {@link HistoryEntry}. The node that wrote the entry is {@link
 * HistoryEntry#nodeId()}, whoever the actor was.
 */
public enum ActorType {

  /** A node by itself: claims, timeouts, wake-ups, releases and the outcome of an attempt. */
  NODE,

  /** The application, through the {@link org.bikesched.Bikesched} API. */
  APPLICATION,

  /** A job's handler: its steps, the child jobs it starts and the events it publishes. */
  JOB,

  /** A named user, e.g. in the admin UI; {@link HistoryEntry#actorName()} is the user name. */
  USER
}
