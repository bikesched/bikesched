package org.bikesched.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Mapping with {@link JacksonJsonMapper}, without a database. */
class JacksonJsonMapperTest {

  record Order(UUID id, String customer, List<String> items, Instant placedAt, Duration window) {}

  private final JacksonJsonMapper mapper = new JacksonJsonMapper();

  @Test
  void roundTripsRecordsWithJavaTimeTypes() {
    var order =
        new Order(
            UUID.randomUUID(),
            "alice",
            List.of("bike", "bell"),
            Instant.parse("2026-10-04T10:15:30Z"),
            Duration.ofMinutes(90));

    String json = mapper.toJson(order);

    assertThat(json).contains("\"customer\":\"alice\"").contains("\"items\":[\"bike\",\"bell\"]");
    assertThat(mapper.fromJson(json, Order.class)).isEqualTo(order);
  }

  @Test
  void mapsJsonTextOfPlainTypes() {
    assertThat(mapper.toJson("text")).isEqualTo("\"text\"");
    assertThat(mapper.fromJson("42", Integer.class)).isEqualTo(42);
  }

  @Test
  void usesTheConfiguredObjectMapper() {
    var snakeCase =
        new JacksonJsonMapper(
            JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build());
    record Ride(String pickupAddress) {}

    assertThat(snakeCase.toJson(new Ride("Main St 1")))
        .isEqualTo("{\"pickup_address\":\"Main St 1\"}");
  }

  @Test
  void reportsInvalidJsonWithAnUncheckedException() {
    assertThatThrownBy(() -> mapper.fromJson("{not json", Order.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void requiresAnObjectMapper() {
    assertThatThrownBy(() -> new JacksonJsonMapper(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("mapper");
  }
}
