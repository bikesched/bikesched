// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import org.bikesched.Bikesched;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the {@code bikesched-test} tests: one PostgreSQL container shared by all test
 * classes of the JVM, like {@code AbstractBikeschedTest} in {@code bikesched-core}.
 */
abstract class AbstractTestModuleTest {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
  static final PGSimpleDataSource DATA_SOURCE;

  static {
    POSTGRES.start();
    DATA_SOURCE = new PGSimpleDataSource();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  /**
   * A job name unique to the calling test, to isolate it from the others on the shared database.
   */
  static String uniqueType(String prefix) {
    return prefix + "-" + java.util.UUID.randomUUID();
  }

  static Bikesched buildNode(String nodeNamePrefix) {
    return BikeschedTest.testNode(DATA_SOURCE, nodeNamePrefix).build();
  }
}
