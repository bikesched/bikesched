package org.bikesched.jackson;

import java.util.Objects;
import org.bikesched.JsonMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@link JsonMapper} backed by Jackson 3, so payloads, step results and event payloads can be
 * plain records and classes:
 *
 * <pre>{@code
 * Bikesched bikesched = Bikesched.builder()
 *     .dataSource(dataSource)
 *     .jsonMapper(new JacksonJsonMapper())
 *     .build();
 * }</pre>
 *
 * <p>Pass a configured {@link ObjectMapper} to add modules (e.g. Jackson's Kotlin module) or change
 * features. Mapping failures are reported with Jackson's unchecked {@code JacksonException}.
 * Thread-safe, like the {@link ObjectMapper} it uses.
 */
public final class JacksonJsonMapper implements JsonMapper {

  private final ObjectMapper mapper;

  /** Creates the mapper with Jackson's defaults (records and {@code java.time} types included). */
  public JacksonJsonMapper() {
    this(tools.jackson.databind.json.JsonMapper.builder().build());
  }

  /**
   * Creates the mapper with a configured Jackson mapper.
   *
   * @param mapper the Jackson mapper
   */
  public JacksonJsonMapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public String toJson(Object value) {
    return mapper.writeValueAsString(value);
  }

  @Override
  public <T> T fromJson(String json, Class<T> type) {
    return mapper.readValue(json, type);
  }
}
