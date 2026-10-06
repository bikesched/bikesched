// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseTest extends AbstractStoreTest {

  @Test
  void migrateIsIdempotent() {
    assertThat(database.migrate()).isEmpty();
  }

  @Test
  void redactUrlRemovesCredentials() {
    assertThat(Database.redactUrl("jdbc:postgresql://localhost:5432/bikesched"))
        .isEqualTo("jdbc:postgresql://localhost:5432/bikesched");
    assertThat(
            Database.redactUrl(
                "jdbc:postgresql://localhost:5432/bikesched?user=bob&password=secret&ssl=true"))
        .isEqualTo("jdbc:postgresql://localhost:5432/bikesched");
    assertThat(
            Database.redactUrl(
                "jdbc:postgresql://bob:secret@db.example.com:5432/bikesched?sslmode=require"))
        .isEqualTo("jdbc:postgresql://db.example.com:5432/bikesched");
    assertThat(
            Database.redactUrl("jdbc:postgresql://host1:5432,host2:5433/bikesched?password=secret"))
        .isEqualTo("jdbc:postgresql://host1:5432,host2:5433/bikesched");
    assertThat(Database.redactUrl("jdbc:sqlserver://localhost:1433;user=bob;password=secret"))
        .isEqualTo("jdbc:sqlserver://localhost:1433");
    assertThat(Database.redactUrl(null)).isEqualTo("<unknown>");
  }

  @Test
  void describeReturnsRedactedUrlAndVersion() {
    assertThat(database.describe())
        .matches("jdbc:postgresql://[^/@?]+:\\d+/\\w+ \\(PostgreSQL 18\\..*\\)")
        .doesNotContain("password");
  }
}
