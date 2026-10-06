// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal;

import java.util.Objects;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.JsonMapper;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.json.RawJsonMapper;

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
   * @param jsonMapper maps payloads and step results; {@code null} for the default, which only
   *     passes JSON strings through ({@link RawJsonMapper})
   * @return the node; already polling if {@link Settings#autoStartPolling()} is set
   */
  public static Bikesched create(Settings settings, DataSource dataSource, JsonMapper jsonMapper) {
    Objects.requireNonNull(settings, "settings");
    JsonMapper mapper = jsonMapper != null ? jsonMapper : RawJsonMapper.INSTANCE;
    var database =
        new Database(
            dataSource,
            mapper,
            settings.jobHistoryRetention() != null,
            settings.statementTimeout());
    return DefaultBikesched.create(database, settings);
  }
}
