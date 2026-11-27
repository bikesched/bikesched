// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Objects;
import java.util.UUID;

/**
 * An item of a job batch, as its compensation receives it (see {@link
 * JobBatchSettings#compensateWith(CompensationHandler)}).
 *
 * @param key the item's key in its batch
 * @param jobId the item's child job
 */
public record JobBatchItem(String key, UUID jobId) {

  /**
   * Creates an item.
   *
   * @param key the item's key in its batch
   * @param jobId the item's child job
   */
  public JobBatchItem {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(jobId, "jobId");
  }
}
