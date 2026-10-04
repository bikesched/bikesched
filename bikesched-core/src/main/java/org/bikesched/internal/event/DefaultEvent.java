package org.bikesched.internal.event;

import java.util.Objects;
import org.bikesched.JsonMapper;
import org.bikesched.event.Event;

/** The {@link Event} implementation: an event a waiting step received, with its stored payload. */
public final class DefaultEvent implements Event {

  private final String name;
  private final String key;
  private final String payload;
  private final JsonMapper jsonMapper;

  public DefaultEvent(String name, String key, String payload, JsonMapper jsonMapper) {
    this.name = Objects.requireNonNull(name, "name");
    this.key = Objects.requireNonNull(key, "key");
    this.payload = payload;
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public String payload() {
    return payload;
  }

  @Override
  public <T> T payload(Class<T> type) {
    Objects.requireNonNull(type, "type");
    return payload == null ? null : jsonMapper.fromJson(payload, type);
  }

  @Override
  public String toString() {
    return "Event[name=" + name + ", key=" + key + ", payload=" + payload + "]";
  }
}
