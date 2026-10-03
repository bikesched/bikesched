package bikesched.core.internal;

import bikesched.core.job.JobInfo;
import bikesched.core.job.JobState;
import java.util.UUID;

/**
 * The {@link JobInfo} implementation.
 *
 * @param id the job id
 * @param type the job type
 * @param state the job's state when it was read
 */
record DefaultJobInfo(UUID id, String type, JobState state) implements JobInfo {}
