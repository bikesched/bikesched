// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One batch of a clean-up that deletes rows oldest first by a timestamp column (ended jobs, job
 * history entries, expired events), each batch in its own short transaction.
 *
 * <p>The next batch starts at the timestamp the previous one ended at instead of at the oldest
 * index entry: deleted rows stay in the index as dead entries until vacuum removes them, so
 * starting over would visit every row the earlier batches deleted again.
 *
 * <p>All batches of a run delete up to the cutoff the first batch computed from the database's
 * clock, so a run ends once the backlog it found is deleted, even while new rows become due as fast
 * as it deletes them (it shares the clean-up thread with the release of dead nodes' jobs).
 *
 * @param count the number of deleted rows
 * @param last the timestamp of the newest deleted row, {@code null} if none was deleted
 * @param cutoff the rows deleted are before this timestamp (expired events: at or before it)
 */
public record DeletedBatch(int count, @Nullable Instant last, Instant cutoff) {

  static final Sql.RowMapper<DeletedBatch> MAPPER =
      row -> new DeletedBatch(row.integer(1), row.instant(2), row.instant(3));

  /** Deletes one batch. */
  @FunctionalInterface
  public interface BatchDelete {

    /**
     * Deletes up to the batch size of rows, oldest first.
     *
     * @param after only rows at or after this timestamp, {@code null} for all
     * @param cutoff only rows before this timestamp, {@code null} to compute it from the retention
     * @return the deleted batch
     */
    DeletedBatch delete(@Nullable Instant after, @Nullable Instant cutoff);
  }

  /**
   * Deletes batches until a batch is not full or the thread is interrupted.
   *
   * @param batchSize the rows deleted per batch, the limit passed to {@code deleteBatch}
   * @param deleteBatch deletes one batch
   * @return the number of deleted rows
   */
  public static int deleteAll(int batchSize, BatchDelete deleteBatch) {
    int deleted = 0;
    @Nullable Instant after = null;
    @Nullable Instant cutoff = null;
    while (!Thread.currentThread().isInterrupted()) {
      DeletedBatch batch = deleteBatch.delete(after, cutoff);
      deleted += batch.count();
      if (batch.count() < batchSize) {
        break;
      }
      after = batch.last();
      cutoff = batch.cutoff();
    }
    return deleted;
  }
}
