package bikesched.core;

/**
 * Maps job payloads and step results between Java objects and the JSON text bikesched stores in
 * {@code jsonb} columns. Configured via {@link BikeschedBuilder#jsonMapper(JsonMapper)}; the
 * default {@link JsonStringMapper} only passes JSON strings through. Plug in a JSON library, e.g.
 * Jackson:
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
 * {@code null}. Implementations must be thread-safe and report failures with unchecked
 * exceptions.
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
