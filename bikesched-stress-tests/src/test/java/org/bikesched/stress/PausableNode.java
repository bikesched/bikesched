// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.job.JobHandler;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A node in a JVM of its own, so {@link NodePauseTest} can pause it like a long garbage collection
 * or a frozen VM ({@code kill -STOP}). Runs until its standard input closes.
 *
 * <p>Arguments: JDBC URL, user, password, node name prefix, the job type to run with {@link
 * #handler(DataSource, String, Duration)}, and a second job type only this node handles.
 */
final class PausableNode {

  /** The table the step bodies record their runs in. */
  static final String EFFECTS_TABLE =
      "create table if not exists stress_effects ("
          + " job_id uuid not null, step text not null, node text not null,"
          + " at timestamptz not null default clock_timestamp())";

  private PausableNode() {}

  public static void main(String[] args) throws Exception {
    var dataSource = new PGSimpleDataSource();
    dataSource.setUrl(args[0]);
    dataSource.setUser(args[1]);
    dataSource.setPassword(args[2]);
    try (Bikesched node = StressNodes.node(dataSource, args[3]).autoStartPolling(false).build()) {
      node.registerJobHandler(args[4], handler(dataSource, "paused", Duration.ofSeconds(4)));
      node.registerJobHandler(args[5], _ -> {});
      node.start();
      System.out.println("started " + node.node().id().orElseThrow());
      while (System.in.read() >= 0) {
        // until the test closes the pipe
      }
    }
  }

  /**
   * A job with two steps: {@code one} records its run, {@code two} records its start, takes the
   * given time and records its end. {@code node} names the node in the records.
   */
  static JobHandler handler(DataSource effects, String node, Duration stepTwo) {
    return job -> {
      job.step("one", () -> record(effects, job.id(), "one", node));
      job.step(
          "two",
          () -> {
            record(effects, job.id(), "two-started", node);
            try {
              Thread.sleep(stepTwo.toMillis());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            record(effects, job.id(), "two-ended", node);
          });
    };
  }

  private static void record(DataSource effects, UUID jobId, String step, String node) {
    try (var connection = effects.getConnection();
        var insert =
            connection.prepareStatement(
                "insert into stress_effects (job_id, step, node) values (?, ?, ?)")) {
      insert.setObject(1, jobId);
      insert.setString(2, step);
      insert.setString(3, node);
      insert.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
