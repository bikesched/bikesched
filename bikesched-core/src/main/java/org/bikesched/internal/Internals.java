package org.bikesched.internal;

import java.util.Objects;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.JsonMapper;
import org.bikesched.internal.database.Database;

/**
 * The only way from the API packages into the implementation: the API creates instances through
 * these factories (and passes the validated {@link Settings}), so it depends on no other
 * implementation class. Enforced by {@code ArchitectureTest}.
 */
public final class Internals {

  private Internals() {}

  /**
   * Creates a bikesched node, see {@link DefaultBikesched#create}.
   *
   * @param settings the node's settings
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results
   * @return the node; already polling if {@link Settings#autoStartPolling()} is set
   */
  public static Bikesched create(Settings settings, DataSource dataSource, JsonMapper jsonMapper) {
    Objects.requireNonNull(settings, "settings");
    return DefaultBikesched.create(new Database(dataSource, jsonMapper), settings);
  }

  /**
   * The JSON mapper that passes JSON text through unchanged, see {@link JsonMapper#rawJson()}.
   *
   * @return the shared instance
   */
  public static JsonMapper rawJsonMapper() {
    return RawJsonMapper.INSTANCE;
  }
}
