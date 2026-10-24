// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/** How a job was started, see {@link JobInfo#type()}. */
public enum JobType {

  /** Scheduled on its own (by the application or the admin UI): runs once. */
  SINGLE,

  /** An occurrence of a recurring job: ending it schedules the next occurrence. */
  RECURRING,

  /** Started by a step of its parent job, whose step receives its outcome. */
  CHILD
}
