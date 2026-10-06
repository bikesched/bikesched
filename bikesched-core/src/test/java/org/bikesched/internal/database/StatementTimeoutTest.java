// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.bikesched.BikeschedException;
import org.junit.jupiter.api.Test;

class StatementTimeoutTest extends AbstractStoreTest {

  @Test
  void statementRunningLongerThanTheTimeoutIsCancelled() {
    var access = new DatabaseAccess(dataSource, Duration.ofMillis(500));
    long start = System.nanoTime();

    assertThatThrownBy(() -> sleep(access, 10))
        .isInstanceOf(BikeschedException.class)
        .hasMessage("sleeping failed")
        .rootCause()
        .hasMessageContaining("canceling statement due to user request");
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("rounded up to 1 second, not 10")
        .isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void statementsWithinTheTimeoutRun() {
    var access = new DatabaseAccess(dataSource, Duration.ofSeconds(5));

    assertThat(sleep(access, 0.1)).contains(1);
  }

  @Test
  void withoutTimeoutTheDataSourceDecides() {
    var access = new DatabaseAccess(dataSource);

    assertThat(sleep(access, 1.5)).contains(1);
  }

  private static Optional<Integer> sleep(DatabaseAccess access, double seconds) {
    return access.withConnection(
        db -> db.one("select 1 from pg_sleep(?)", _ -> 1, seconds),
        e -> new BikeschedException("sleeping failed", e));
  }
}
