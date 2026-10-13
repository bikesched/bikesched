// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapts API values for nanojson: ordered objects, lists, strings, integers, booleans, {@code
 * null}, {@link Instant}s, {@link UUID}s and {@link Duration}s as strings, enums by name and
 * embedded {@link Raw} JSON values. nanojson handles all JSON serialization and escaping;
 * unsupported value types are rejected.
 */
final class Json {

  /** JSON text interpreted as a value, e.g. a payload read from the database. */
  record Raw(String json) {}

  private Json() {}

  /** Starts an object; {@link Obj#put} keeps the insertion order. */
  static Obj object() {
    return new Obj();
  }

  /** An object under construction. */
  static final class Obj {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    Obj put(String name, Object value) {
      fields.put(name, value);
      return this;
    }

    @Override
    public String toString() {
      return write(this);
    }
  }

  static String write(Object value) {
    return JsonWriter.string(normalize(value));
  }

  private static Object normalize(Object value) {
    return switch (value) {
      case null -> null;
      case Obj obj -> normalize(obj.fields);
      case Map<?, ?> map -> {
        Map<String, Object> fields = new LinkedHashMap<>();
        map.forEach((key, item) -> fields.put(String.valueOf(key), normalize(item)));
        yield fields;
      }
      case List<?> list -> list.stream().map(Json::normalize).toList();
      case Raw raw -> parse(raw.json());
      case String string -> string;
      case Boolean bool -> bool;
      case Integer number -> number;
      case Long number -> number;
      case Enum<?> constant -> constant.name();
      case Instant instant -> instant.toString();
      case UUID uuid -> uuid.toString();
      case Duration duration -> duration.toString();
      default ->
          throw new IllegalArgumentException(
              "Unsupported JSON value type: " + value.getClass().getName());
    };
  }

  private static Object parse(String json) {
    if (json == null) {
      return null;
    }
    try {
      // Preserve decimal precision and exponent ranges instead of converting through double.
      return JsonParser.any().withLazyNumbers().from(json);
    } catch (JsonParserException e) {
      throw new IllegalStateException("Invalid JSON in stored admin data", e);
    }
  }
}
