// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bikesched.JsonMapper;
import org.junit.jupiter.api.Test;

/** The mapper used when none is configured. */
class RawJsonMapperTest {

  record Payment(String orderId, int amount) {}

  private final JsonMapper mapper = RawJsonMapper.INSTANCE;

  @Test
  void passesJsonStringsThrough() {
    assertThat(mapper.toJson("{\"a\": 1}")).isEqualTo("{\"a\": 1}");
    assertThat(mapper.fromJson("{\"a\": 1}", String.class)).isEqualTo("{\"a\": 1}");
    assertThat(mapper.fromJson("[1, 2]", CharSequence.class)).isEqualTo("[1, 2]");
    assertThat(mapper.fromJson("42", Object.class)).isEqualTo("42");
  }

  @Test
  void rejectsOtherTypes() {
    assertThatThrownBy(() -> mapper.toJson(42))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Without a configured JsonMapper, only JSON strings are supported, not"
                + " java.lang.Integer; configure one via BikeschedBuilder.jsonMapper(...), e.g."
                + " bikesched-jackson's JacksonJsonMapper");
    assertThatThrownBy(() -> mapper.fromJson("{}", Payment.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not org.bikesched.internal.json.RawJsonMapperTest$Payment");
  }
}
