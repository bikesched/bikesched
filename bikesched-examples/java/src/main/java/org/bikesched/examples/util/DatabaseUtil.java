// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.util;

import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Database access shared by the examples' main classes. */
public final class DatabaseUtil {

  private DatabaseUtil() {}

  /**
   * Returns a data source for the PostgreSQL from the repository's {@code compose.yaml} ({@code
   * docker compose up -d}), on the port in {@code POSTGRES_PORT} like the compose file (default
   * 5432).
   *
   * @return a data source for the {@code bikesched} database
   */
  public static DataSource composeDatabase() {
    var dataSource = new PGSimpleDataSource();
    dataSource.setServerNames(new String[] {"localhost"});
    dataSource.setPortNumbers(
        new int[] {Integer.parseInt(System.getenv().getOrDefault("POSTGRES_PORT", "5432"))});
    dataSource.setDatabaseName("bikesched");
    dataSource.setUser("bikesched");
    dataSource.setPassword("bikesched");
    return dataSource;
  }
}
