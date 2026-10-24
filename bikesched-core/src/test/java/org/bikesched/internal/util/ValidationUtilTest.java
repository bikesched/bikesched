// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ValidationUtilTest {

  @Test
  void namesUpToTheLimitAreAccepted() {
    String name = "n".repeat(ValidationUtil.MAX_NAME_LENGTH);
    assertThat(ValidationUtil.requireName(name, "job name")).isSameAs(name);
    String key = "k".repeat(ValidationUtil.MAX_KEY_LENGTH);
    assertThat(ValidationUtil.requireKey(key, "event key")).isSameAs(key);
  }

  @Test
  void nullBlankAndTooLongValuesAreRejected() {
    assertThatThrownBy(() -> ValidationUtil.requireName(null, "job name"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("job name");
    assertThatThrownBy(() -> ValidationUtil.requireName(" \t", "job name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("job name must not be blank");
    assertThatThrownBy(() -> ValidationUtil.requireName("n".repeat(201), "step name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("step name must have at most 200 characters, but has 201");
    assertThatThrownBy(() -> ValidationUtil.requireKey("k".repeat(501), "event key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("event key must have at most 500 characters, but has 501");
  }

  @Test
  void longMessagesAreTruncated() {
    assertThat(ValidationUtil.truncateMessage((String) null)).isNull();
    String limit = "m".repeat(ValidationUtil.MAX_MESSAGE_LENGTH);
    assertThat(ValidationUtil.truncateMessage(limit)).isSameAs(limit);

    String truncated = ValidationUtil.truncateMessage(limit + "m");
    assertThat(truncated).hasSize(ValidationUtil.MAX_MESSAGE_LENGTH).endsWith("… (truncated)");
    // A surrogate pair at the cut is kept whole: 4000 code points, no dangling half.
    String bikes = ValidationUtil.truncateMessage("\uD83D\uDEB2".repeat(5000));
    assertThat(bikes.codePointCount(0, bikes.length()))
        .isEqualTo(ValidationUtil.MAX_MESSAGE_LENGTH);
    assertThat(bikes).startsWith("\uD83D\uDEB2").endsWith("\uD83D\uDEB2… (truncated)");
  }

  @Test
  void charactersOutsideTheBmpCountOnce() {
    String bikes = "\uD83D\uDEB2".repeat(150);
    assertThat(ValidationUtil.requireName(bikes, "job name")).isSameAs(bikes);
    assertThatThrownBy(() -> ValidationUtil.requireName(bikes.repeat(2), "job name"))
        .hasMessage("job name must have at most 200 characters, but has 300");
  }

  @Test
  void textOfTheBmpCanUseTheFullLimit() {
    // Three bytes each in UTF-8, the most a character of the Basic Multilingual Plane takes.
    String name = "\u81EA".repeat(ValidationUtil.MAX_NAME_LENGTH);
    assertThat(ValidationUtil.requireName(name, "job name")).isSameAs(name);
    String key = "\u81EA".repeat(ValidationUtil.MAX_KEY_LENGTH);
    assertThat(ValidationUtil.requireKey(key, "event key")).isSameAs(key);
  }

  @Test
  void valuesOverTheByteLimitAreRejected() {
    // Four bytes each: 151 of them exceed the 600 bytes of a name, 376 the 1500 of a key.
    assertThatThrownBy(() -> ValidationUtil.requireName("\uD83D\uDEB2".repeat(151), "job name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("job name must have at most 600 bytes in UTF-8, but has 604");
    assertThatThrownBy(() -> ValidationUtil.requireKey("\uD83D\uDEB2".repeat(376), "event key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("event key must have at most 1500 bytes in UTF-8, but has 1504");
  }
}
