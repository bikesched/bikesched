// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * The {@link JobInfo} implementation.
 *
 * @param id the job id
 * @param type the job type
 * @param state the job's state when it was read
 * @param resultJson the job's result as JSON text, {@code null} if none
 * @param parent the parent job's id, {@code null} if the job is not a child
 * @param jsonMapper reads the result
 */
public record DefaultJobInfo(
    UUID id,
    String type,
    JobState state,
    @Nullable String resultJson,
    @Nullable UUID parent,
    JsonMapper jsonMapper)
    implements JobInfo {

  public DefaultJobInfo {}

  @Override
  public <T> Optional<T> result(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return resultJson == null || state != JobState.SUCCEEDED
        ? Optional.empty()
        : Optional.ofNullable(jsonMapper.fromJson(resultJson, type));
  }

  @Override
  public Optional<UUID> parentJobId() {
    return Optional.ofNullable(parent);
  }

  @Override
  public String toString() {
    return "JobInfo[id="
        + id
        + ", type="
        + type
        + ", state="
        + state
        + (parent == null ? "" : ", parentJobId=" + parent)
        + "]";
  }
}
