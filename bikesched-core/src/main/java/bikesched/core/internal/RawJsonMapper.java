package bikesched.core.internal;

import bikesched.core.JsonMapper;
import java.util.Objects;

/**
 * The default {@link JsonMapper}, created by {@link JsonMapper#rawJson()}: supports only strings
 * that already contain JSON text, which it passes through unchanged. Configure another mapper via
 * {@code BikeschedBuilder.jsonMapper(JsonMapper)} to use other types.
 *
 * <p>The JSON is not validated here; PostgreSQL rejects invalid JSON when it is stored.
 */
public final class RawJsonMapper implements JsonMapper {

  /** The only instance; stateless. */
  public static final RawJsonMapper INSTANCE = new RawJsonMapper();

  private RawJsonMapper() {}

  /**
   * Returns the JSON string unchanged.
   *
   * @param value a {@link String} containing JSON text
   * @return the value
   * @throws IllegalArgumentException if the value is not a {@link String}
   */
  @Override
  public String toJson(Object value) {
    Objects.requireNonNull(value, "value");
    if (value instanceof String json) {
      return json;
    }
    throw new IllegalArgumentException(unsupported(value.getClass()));
  }

  /**
   * Returns the JSON text unchanged.
   *
   * @param json the JSON text
   * @param type {@link String} (or {@link Object}, {@link CharSequence})
   * @return the JSON text
   * @throws IllegalArgumentException if the type cannot hold a {@link String}
   */
  @Override
  public <T> T fromJson(String json, Class<T> type) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
    if (type.isAssignableFrom(String.class)) {
      return type.cast(json);
    }
    throw new IllegalArgumentException(unsupported(type));
  }

  private static String unsupported(Class<?> type) {
    return "JsonMapper.rawJson() only supports JSON strings, not "
        + type.getName()
        + "; configure a JsonMapper for other types via Bikesched.builder().jsonMapper(...)";
  }
}
