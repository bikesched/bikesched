// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/** What a {@link HistoryEntry} is about, see {@link HistoryEntry#subjectId()}. */
public enum SubjectType {

  /** A job, or one of its steps: a {@link JobEntry} or a {@link StepEntry}. */
  JOB,

  /** An event: an {@link EventEntry}. */
  EVENT,

  /** A node: a {@link NodeEntry}. */
  NODE
}
