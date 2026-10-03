package bikesched.core.job;

/** The state of a job, see {@link JobInfo#state()}. */
public enum JobState {

  /** Waiting to run: due now or at its scheduled time, or to be retried. */
  SCHEDULED,

  /** Claimed and running on a node (also while it is being compensated). */
  RUNNING,

  /** Waiting for an event, see {@link Job#waitForEvent(String, String, String)}. */
  WAITING,

  /** Completed successfully. */
  SUCCEEDED,

  /** Failed permanently: its last attempt failed. */
  FAILED,

  /** Cancelled by its handler or via {@code Bikesched.cancel}. */
  CANCELLED,

  /**
   * Cancelled or failed permanently; its completed steps are compensated before it ends, see {@link
   * Job#newStep(String, Class)}.
   */
  COMPENSATING,

  /** Compensating its steps failed permanently; needs manual attention. */
  COMPENSATION_FAILED;

  /**
   * Returns whether a job in this state has ended, i.e. no longer runs.
   *
   * @return {@code true} for {@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELLED} and {@link
   *     #COMPENSATION_FAILED}
   */
  public boolean ended() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == COMPENSATION_FAILED;
  }
}
