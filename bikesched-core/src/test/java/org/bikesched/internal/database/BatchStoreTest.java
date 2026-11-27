// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The schema's constraints on batches: their items, batch steps and item steps. */
class BatchStoreTest extends AbstractStoreTest {

  private final UUID parent = UUID.randomUUID();
  private final UUID batchStep = UUID.randomUUID();

  @BeforeEach
  void insertParentAndBatchStep() throws SQLException {
    execute("insert into bikesched_jobs (id, name) values ('" + parent + "', 'parent')");
    execute(
        "insert into bikesched_steps (id, job_id, name, type, state, batch_item_count, batch_active_count,"
            + " batch_ended_count, batch_parallelism) values ('"
            + batchStep
            + "', '"
            + parent
            + "', 'items', 'CHILD_JOB_BATCH', 'WAITING', 2, 1, 0, 1)");
  }

  @Test
  void itemsKnowTheirBatchStepPositionAndKey() throws SQLException {
    insertItem("HELD", "1", "'b'");

    assertThat(count("batch_key = 'b' and state = 'HELD'")).isEqualTo(1);
    assertThatThrownBy(() -> insertItem("SCHEDULED", "null", "'c'"))
        .as("an item without position")
        .hasMessageContaining("bikesched_jobs_batch_check");
    assertThatThrownBy(() -> insertItem("SCHEDULED", "-1", "'c'"))
        .as("a negative position")
        .hasMessageContaining("bikesched_jobs_batch_check");
    assertThatThrownBy(() -> insertItem("SCHEDULED", "2", "''"))
        .as("an empty key")
        .hasMessageContaining("bikesched_jobs_batch_key_check");
    assertThatThrownBy(() -> insertItem("SCHEDULED", "2", "repeat('k', 201)"))
        .as("a key longer than names")
        .hasMessageContaining("bikesched_jobs_batch_key_check");
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_jobs (id, name, state) values (gen_random_uuid(),"
                        + " 'single', 'HELD')"))
        .as("only items are held")
        .hasMessageContaining("bikesched_jobs_batch_check");
  }

  @Test
  void onlyBatchStepsCountItemsAndFailFast() throws SQLException {
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_steps (id, job_id, name, type, state, batch_item_count)"
                        + " values (gen_random_uuid(), '"
                        + parent
                        + "', 'step', 'STEP', 'RUNNING', 2)"))
        .hasMessageContaining("bikesched_steps_batch_check");
    assertThatThrownBy(
            () ->
                execute(
                    "update bikesched_steps set state = 'FAILED', finished_at = now() where id = '"
                        + batchStep
                        + "'"))
        .as("only fail-fast batches fail")
        .hasMessageContaining("bikesched_steps_batch_check");
    assertThatThrownBy(
            () ->
                execute(
                    "update bikesched_steps set batch_ended_count = 3 where id = '"
                        + batchStep
                        + "'"))
        .as("no more items ended than the batch has")
        .hasMessageContaining("bikesched_steps_batch_check");
    assertThatThrownBy(
            () ->
                execute(
                    "update bikesched_steps set batch_parallelism = 0 where id = '"
                        + batchStep
                        + "'"))
        .hasMessageContaining("bikesched_steps_batch_check");
    execute(
        "update bikesched_steps set state = 'FAILED', finished_at = now(), batch_fail_fast = true"
            + " where id = '"
            + batchStep
            + "'");
  }

  @Test
  void itemStepsAreChildJobSteps() throws SQLException {
    execute(
        "insert into bikesched_steps (id, job_id, name, type, state, batch_step_id,"
            + " child_job_id, child_job_name) values (gen_random_uuid(), '"
            + parent
            + "', 'items/a', 'CHILD_JOB', 'WAITING', '"
            + batchStep
            + "', gen_random_uuid(), 'item')");
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_steps (id, job_id, name, type, state, batch_step_id)"
                        + " values (gen_random_uuid(), '"
                        + parent
                        + "', 'items/b', 'STEP', 'RUNNING', '"
                        + batchStep
                        + "')"))
        .hasMessageContaining("bikesched_steps_batch_check");
  }

  private void insertItem(String state, String index, String key) throws SQLException {
    execute(
        "insert into bikesched_jobs (id, name, type, state, parent_job_id, batch_step_id,"
            + " batch_index, batch_key) values (gen_random_uuid(), 'item', 'CHILD', '"
            + state
            + "', '"
            + parent
            + "', '"
            + batchStep
            + "', "
            + index
            + ", "
            + key
            + ")");
  }
}
