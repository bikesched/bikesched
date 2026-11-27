// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import org.jspecify.annotations.Nullable;

/**
 * The progress of a job batch, on its {@code CHILD_JOB_BATCH} step, see {@link StepInfo#batch()}
 * and {@link org.bikesched.job.Job#newJobBatch(String, org.bikesched.job.ResultJob)}.
 *
 * @param items the number of items
 * @param ended the items that ended
 * @param active the items admitted and not ended, at most {@code parallelism}
 * @param parallelism how many items may be active at once; {@code null} for no limit
 * @param failFast whether the batch ends at the first item that does not succeed
 */
public record StepBatch(
    int items, int ended, int active, @Nullable Integer parallelism, boolean failFast) {}
