package bikesched.core;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;

/**
 * A {@link JsonMapper} for tests backed by nanojson. nanojson has no data binding, so the {@link
 * Payment} fields are mapped explicitly; strings are written and read as JSON strings.
 */
final class PaymentJsonMapper implements JsonMapper {

  record Payment(String id, int amount) {}

  @Override
  public String toJson(Object value) {
    return switch (value) {
      case Payment(String id, int amount) ->
          JsonWriter.string().object().value("id", id).value("amount", amount).end().done();
      case String text -> JsonWriter.string(text);
      default -> throw new IllegalArgumentException("unsupported: " + value.getClass());
    };
  }

  @Override
  public <T> T fromJson(String json, Class<T> type) {
    try {
      if (type == Payment.class) {
        JsonObject object = JsonParser.object().from(json);
        return type.cast(new Payment(object.getString("id"), object.getInt("amount")));
      }
      if (type == String.class) {
        return type.cast(JsonParser.any().from(json));
      }
    } catch (JsonParserException e) {
      throw new IllegalArgumentException("Invalid JSON: " + json, e);
    }
    throw new IllegalArgumentException("unsupported: " + type);
  }
}
