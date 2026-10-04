package org.bikesched;

/**
 * Maps job payloads and step results between Java objects and the JSON text bikesched stores in
 * {@code jsonb} columns. Configured via {@link BikeschedBuilder#jsonMapper(JsonMapper)}; without
 * one, bikesched only accepts strings that already contain JSON text and passes them through
 * unchanged (other types are rejected with an {@link IllegalArgumentException}). For Jackson, use
 * {@code org.bikesched.jackson.JacksonJsonMapper} from the {@code bikesched-jackson} module; for
 * another JSON library, implement the two methods:
 *
 * <pre>{@code
 * Gson gson = new Gson();
 * JsonMapper mapper = new JsonMapper() {
 *     public String toJson(Object value) {
 *         return gson.toJson(value);
 *     }
 *
 *     public <T> T fromJson(String json, Class<T> type) {
 *         return gson.fromJson(json, type);
 *     }
 * };
 * }</pre>
 *
 * <p>{@code null} never reaches the mapper: it is stored as SQL {@code NULL} and read back as
 * {@code null}. Implementations must be thread-safe and report failures with unchecked exceptions.
 */
public interface JsonMapper {

  /**
   * Converts a value to JSON text.
   *
   * @param value the value, never {@code null}
   * @return the value as JSON text
   */
  String toJson(Object value);

  /**
   * Converts JSON text to a value of the given type.
   *
   * @param json the JSON text, never {@code null}
   * @param type the type to convert to
   * @param <T> the type to convert to
   * @return the value
   */
  <T> T fromJson(String json, Class<T> type);
}
