// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.Objects;
import org.bikesched.BikeschedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes the database of a bikesched instance lazily, on first use: logs it (at debug level)
 * and migrates its schema or validates its migration history. This way an instance can be created
 * while the database is not available yet. A failed initialization is retried on the next call.
 * Thread-safe.
 *
 * <p>Public only so that tests can capture its log; not part of the API.
 */
public final class DatabaseInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseInitializer.class);

  private final Database database;
  private final boolean migrateSchema;
  private final Object lock = new Object();
  private volatile boolean initialized;

  public DatabaseInitializer(Database database, boolean migrateSchema) {
    this.database = Objects.requireNonNull(database, "database");
    this.migrateSchema = migrateSchema;
  }

  private void logDatabase() {
    if (LOG.isDebugEnabled()) {
      // "Database: <jdbc url>" format, e.g. picked up by IntelliJ's Testcontainers Port Updater.
      LOG.debug("Database: {}", database.describe());
    }
  }

  /** Explicitly migrates, even after successful initialization or with automatic migration off. */
  public void migrate() {
    synchronized (lock) {
      if (!initialized) {
        logDatabase();
      }
      database.migrate();
      initialized = true;
    }
  }

  /**
   * Initializes the database unless that already succeeded. Concurrent callers wait for the one
   * initializing.
   *
   * @throws BikeschedException if the database cannot be initialized, e.g. it is not available
   */
  public void ensureInitialized() {
    if (initialized) {
      return;
    }
    synchronized (lock) {
      if (initialized) {
        return;
      }
      logDatabase();
      if (migrateSchema) {
        database.migrate();
      } else {
        database.validateSchema();
      }
      initialized = true;
    }
  }
}
