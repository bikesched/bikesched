package org.bikesched;

import org.bikesched.internal.Internals;

/**
 * Maps job payloads and step results between Java objects and the JSON text bikesched stores in
 * {@code jsonb} columns. Configured via {@link BikeschedBuilder#jsonMapper(JsonMapper)}; the
 * default {@link JsonMapper#rawJson()} only passes JSON strings through. Plug in a JSON library,
 * e.g. Jackson:
 *
 * <pre>{@code
 * ObjectMapper objectMapper = new ObjectMapper();
 * JsonMapper jackson = new JsonMapper() {
 *     public String toJson(Object value) {
 *         try {
 *             return objectMapper.writeValueAsString(value);
 *         } catch (JsonProcessingException e) {
 *             throw new IllegalArgumentException(e);
 *         }
 *     }
 *
 *     public <T> T fromJson(String json, Class<T> type) {
 *         try {
 *             return objectMapper.readValue(json, type);
 *         } catch (JsonProcessingException e) {
 *             throw new IllegalArgumentException(e);
 *         }
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

  /**
   * Returns the default mapper: it supports only strings that already contain JSON text, which it
   * passes through unchanged ({@code toJson("{\"a\": 1}")} returns the string as is, {@code
   * fromJson(json, String.class)} returns the JSON text). Other types are rejected with an {@link
   * IllegalArgumentException}; configure a mapper backed by a JSON library for them. The JSON is
   * not validated here; PostgreSQL rejects invalid JSON when it is stored.
   *
   * @return the raw JSON mapper
   */
  static JsonMapper rawJson() {
    return Internals.rawJsonMapper();
  }
}
