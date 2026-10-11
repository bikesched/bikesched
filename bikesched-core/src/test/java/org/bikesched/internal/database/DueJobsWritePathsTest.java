// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard of the due-job reports (see {@link DueJobs}): every store statement that inserts or updates
 * jobs must be classified here as reporting jobs due now (which notifies the other nodes) or as not
 * notifying, with the reason. A new statement fails this test until it is classified, so that a
 * write path making jobs due now can't silently leave them to the next interval poll. Whether a
 * notifying statement really reports its types is tested in {@link DueJobsStoreTest} and the node
 * tests. SQL built at runtime or outside bikesched is not covered.
 */
class DueJobsWritePathsTest {

  private static final Pattern JOB_WRITE =
      Pattern.compile("(insert\\s+into|update)\\s+bikesched_jobs\\b", Pattern.CASE_INSENSITIVE);

  /** Statements making jobs due now: their store reports the types ({@code Sql.dueNow/due}). */
  private static final Set<String> NOTIFYING =
      Set.of(
          "JobStore.INSERT_JOB_SQL",
          "JobStore.INSERT_KEYED_JOB_SQL",
          "JobStore.INSERT_CHILD_JOB_SQL",
          "JobStore.INSERT_OCCURRENCE_SQL",
          "JobStore.RESUME_PARENT_SQL",
          "JobStore.RETRY_JOB_SQL",
          "JobStore.REQUEST_CANCEL_SQL",
          "JobStore.RELEASE_JOBS_SQL",
          "JobStore.RELEASE_INACTIVE_JOBS_SQL",
          "JobStore.RELEASE_JOBS_OF_DEAD_NODES_SQL",
          "EventStore.RESUME_WAITING_STEPS_SQL");

  /** Statements that make no job due now for other nodes, and why. */
  private static final Map<String, String> SILENT =
      Map.of(
          "JobStore.CLAIM_JOBS_SQL",
          "the job runs",
          "JobStore.COMPLETE_JOB_SQL",
          "the job ends",
          "JobStore.COMPLETE_COMPENSATION_SQL",
          "the job ends, or its node wakes up for the failure handler (dueAt)",
          "JobStore.END_FAILING_SQL",
          "the job ends",
          "JobStore.RETRY_FAILURE_HANDLER_SQL",
          "the attempt's own node wakes up for the failure handler's retry (dueAt)",
          "JobStore.FAIL_JOB_SQL",
          "the attempt's own node wakes up for the retry or the failure handler (dueAt)",
          "JobStore.CANCEL_JOB_SQL",
          "the job ends, or its node wakes up to compensate (dueAt)",
          "JobStore.FAIL_COMPENSATION_SQL",
          "the attempt's own node wakes up for the retry (dueAt)",
          "StepStore.SUSPEND_JOB_SQL",
          "the attempt's own node wakes up at the due time (dueAt)",
          "JobStore.RESCHEDULE_OCCURRENCE_SQL",
          "moves a pending occurrence (dueAt)");

  @Test
  void everyStatementWritingJobsIsClassified() throws IllegalAccessException {
    Map<String, String> writes = new TreeMap<>();
    for (Class<?> store :
        new Class<?>[] {
          JobStore.class, StepStore.class, EventStore.class, NodeStore.class, HistoryStore.class
        }) {
      for (Field field : store.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())
            && field.getType() == String.class
            && field.getName().endsWith("_SQL")) {
          field.setAccessible(true);
          String sql = (String) field.get(null);
          if (JOB_WRITE.matcher(sql).find()) {
            writes.put(store.getSimpleName() + "." + field.getName(), sql);
          }
        }
      }
    }

    Set<String> classified = new TreeSet<>(NOTIFYING);
    classified.addAll(SILENT.keySet());
    for (String name : writes.keySet()) {
      assertThat(classified)
          .as("classify %s as notifying or silent, see DueJobs", name)
          .contains(name);
    }
    for (String name : classified) {
      assertThat(writes)
          .as("%s is classified but no longer writes jobs (or was renamed)", name)
          .containsKey(name);
    }
    assertThat(writes).hasSize(21);
  }
}
