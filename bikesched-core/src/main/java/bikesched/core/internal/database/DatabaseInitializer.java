package bikesched.core.internal.database;

import bikesched.core.BikeschedException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes the database of a bikesched instance lazily, on first use: logs it (at debug level)
 * and migrates its schema. This way an instance can be created while the database is not available
 * yet. A failed initialization is retried on the next call. Thread-safe.
 *
 * <p>Public only so that tests can capture its log; not part of the API.
 */
public final class DatabaseInitializer {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseInitializer.class);

  private final Database database;
  private final Object lock = new Object();
  private volatile boolean initialized;

  public DatabaseInitializer(Database database) {
    this.database = Objects.requireNonNull(database, "database");
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
      if (LOG.isDebugEnabled()) {
        // "Database: <jdbc url>" format, e.g. picked up by IntelliJ's Testcontainers Port Updater.
        LOG.debug("Database: {}", database.describe());
      }
      database.migrate();
      initialized = true;
    }
  }
}
