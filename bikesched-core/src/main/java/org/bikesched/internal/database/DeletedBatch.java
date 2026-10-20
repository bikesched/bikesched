// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Instant;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One batch of a clean-up that deletes rows oldest first by a timestamp column (ended jobs, job
 * history entries, expired events), each batch in its own short transaction.
 *
 * <p>The next batch starts at the timestamp the previous one ended at instead of at the oldest
 * index entry: deleted rows stay in the index as dead entries until vacuum removes them, so
 * starting over would visit every row the earlier batches deleted again.
 *
 * @param count the number of deleted rows
 * @param last the timestamp of the newest deleted row, {@code null} if none was deleted
 */
public record DeletedBatch(int count, @Nullable Instant last) {

  static final Sql.RowMapper<DeletedBatch> MAPPER =
      row -> new DeletedBatch(row.integer(1), row.instant(2));

  /**
   * Deletes batches until a batch is not full or the thread is interrupted.
   *
   * @param batchSize the rows deleted per batch, the limit passed to {@code deleteBatch}
   * @param deleteBatch deletes one batch of rows at or after the given timestamp ({@code null}:
   *     from the oldest)
   * @return the number of deleted rows
   */
  public static int deleteAll(
      int batchSize, Function<@Nullable Instant, DeletedBatch> deleteBatch) {
    int deleted = 0;
    @Nullable Instant after = null;
    while (!Thread.currentThread().isInterrupted()) {
      DeletedBatch batch = deleteBatch.apply(after);
      deleted += batch.count();
      if (batch.count() < batchSize) {
        break;
      }
      after = batch.last();
    }
    return deleted;
  }
}
