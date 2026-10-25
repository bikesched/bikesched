// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.jspecify.annotations.Nullable;

/**
 * The {@link JobInfo} implementation.
 *
 * @param id the job id
 * @param name the job name
 * @param type how the job was started
 * @param state the job's state when it was read
 * @param resultJson the job's result as JSON text, {@code null} if none
 * @param parentJobId the parent job's id, {@code null} if the job is not a child
 * @param reference the job's reference, {@code null} if none
 * @param jsonMapper reads the result
 */
public record DefaultJobInfo(
    UUID id,
    String name,
    JobType type,
    JobState state,
    @Nullable String resultJson,
    @Nullable UUID parentJobId,
    @Nullable String reference,
    JsonMapper jsonMapper)
    implements JobInfo {

  public DefaultJobInfo {}

  @Override
  public <T extends @Nullable Object> @Nullable T result(Class<T> type) {
    Objects.requireNonNull(type, "type");
    String json = resultJson;
    return json == null || state != JobState.SUCCEEDED ? null : jsonMapper.fromJson(json, type);
  }

  @Override
  public String toString() {
    return "JobInfo[id="
        + id
        + ", name="
        + name
        + ", type="
        + type
        + ", state="
        + state
        + (parentJobId == null ? "" : ", parentJobId=" + parentJobId)
        + (reference == null ? "" : ", reference=" + reference)
        + "]";
  }
}
