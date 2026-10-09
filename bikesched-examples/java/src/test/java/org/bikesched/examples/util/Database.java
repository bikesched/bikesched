// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.util;

import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database of the example tests: one PostgreSQL 18 container (Testcontainers), started on first
 * use and shared by all tests of the JVM.
 */
public final class Database {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  private Database() {}

  /**
   * Returns the data source of the shared test database.
   *
   * @return the data source
   */
  public static DataSource dataSource() {
    return DATA_SOURCE;
  }
}
