package bikesched.examples;

import bikesched.core.JsonMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps payloads, events and step results with Jackson, so they can be plain records. Configure it
 * with {@code Bikesched.builder().jsonMapper(new JacksonJsonMapper())}.
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
    this.mapper = mapper;
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
