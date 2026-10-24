// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.BikeschedException;
import org.junit.jupiter.api.Test;

/** Connection and transaction handling of {@link DatabaseAccess}. */
class DatabaseAccessTest extends AbstractStoreTest {

  private final DatabaseAccess access = new DatabaseAccess(dataSource);

  @Test
  void exceptionHandlerIsOnlyCalledWhenAStatementFails() {
    var calls = new AtomicInteger();
    int one =
        access.withConnection(
            db -> db.one("select 1 as one", row -> row.integer("one")).orElseThrow(),
            e -> {
              calls.incrementAndGet();
              return new BikeschedException("unexpected", e);
            });
    assertThat(one).isEqualTo(1);
    assertThat(calls).as("not called on success").hasValue(0);

    assertThatThrownBy(
            () ->
                access.withConnection(
                    db -> db.update("delete from no_such_table"),
                    e -> {
                      calls.incrementAndGet();
                      return new IllegalStateException("Deleting failed", e);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Deleting failed")
        .hasCauseInstanceOf(SQLException.class)
        .hasStackTraceContaining("no_such_table");
    assertThat(calls).hasValue(1);
  }

  @Test
  void transactionIsRolledBackAndRuntimeExceptionsPassThrough() throws SQLException {
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                access.inTransaction(
                    db -> {
                      db.update(
                          "insert into bikesched_nodes (id, name) values (gen_random_uuid(), ?)",
                          "rolled-back");
                      throw new IllegalStateException("work failed");
                    },
                    e -> {
                      calls.incrementAndGet();
                      return new BikeschedException("unexpected", e);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("work failed");

    assertThat(calls).as("only SQLExceptions reach the handler").hasValue(0);
    assertThat(countRows("bikesched_nodes", "name = 'rolled-back'")).isZero();
  }

  @Test
  void readOnlyTransactionRejectsWrites() {
    assertThatThrownBy(
            () ->
                access.inReadOnlyTransaction(
                    db -> db.update("delete from bikesched_jobs"),
                    e -> new BikeschedException("Writing in a read-only transaction failed", e)))
        .isInstanceOf(BikeschedException.class)
        .hasMessage("Writing in a read-only transaction failed")
        .hasStackTraceContaining("cannot execute DELETE in a read-only transaction");

    int jobs =
        access.inReadOnlyTransaction(
            db ->
                db.one("select count(*) as jobs from bikesched_jobs", row -> row.integer("jobs"))
                    .orElseThrow(),
            e -> new BikeschedException("Reading in a read-only transaction failed", e));
    assertThat(jobs).isNotNegative();
  }

  @Test
  void rowsAreReadByUniqueColumnLabels() {
    String read =
        access.withConnection(
            db ->
                db.one(
                        "select 'a' as name, 2 as count",
                        row -> row.string("name") + row.integer("count"))
                    .orElseThrow(),
            e -> new BikeschedException("Reading failed", e));
    assertThat(read).isEqualTo("a2");

    assertThatThrownBy(
            () ->
                access.withConnection(
                    db -> db.one("select 1 as one", row -> row.integer("two")),
                    e -> new BikeschedException("Reading failed", e)))
        .hasRootCauseMessage("No column two in the result, only [one]");

    assertThatThrownBy(
            () ->
                access.withConnection(
                    db -> db.one("select 1 as id, 2 as id", row -> row.integer("id")),
                    e -> new BikeschedException("Reading failed", e)))
        .hasRootCauseMessage("Several columns are labelled id: alias them");
  }
}
