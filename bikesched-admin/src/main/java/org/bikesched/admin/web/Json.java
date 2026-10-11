// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A minimal JSON writer for the API's responses: maps (keeping their order), lists, strings,
 * numbers, booleans, {@code null}, {@link Instant}s and {@link UUID}s as strings, enums by name and
 * {@link Raw} JSON text (payloads) as is. Strings escape {@code <}, {@code >} and {@code &} too, so
 * no response can be read as markup.
 */
final class Json {

  /** JSON text written unchanged, e.g. a payload read from the database. */
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
    var out = new StringBuilder(256);
    write(out, value);
    return out.toString();
  }

  private static void write(StringBuilder out, Object value) {
    switch (value) {
      case null -> out.append("null");
      case Obj obj -> write(out, obj.fields);
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (var entry : map.entrySet()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          string(out, String.valueOf(entry.getKey()));
          out.append(':');
          write(out, entry.getValue());
        }
        out.append('}');
      }
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          write(out, list.get(i));
        }
        out.append(']');
      }
      case Raw raw -> out.append(raw.json() == null ? "null" : raw.json());
      case Boolean bool -> out.append(bool);
      case Integer number -> out.append(number);
      case Long number -> out.append(number);
      case Enum<?> constant -> string(out, constant.name());
      default -> string(out, value.toString()); // String, Instant, UUID
    }
  }

  private static void string(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
