// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.UUID;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobState;

/**
 * The {@link JobInfo} implementation.
 *
 * @param id the job id
 * @param type the job type
 * @param state the job's state when it was read
 */
public record DefaultJobInfo(UUID id, String type, JobState state) implements JobInfo {}
