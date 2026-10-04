package org.bikesched.internal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ValidationUtilTest {

  @Test
  void namesUpToTheLimitAreAccepted() {
    String name = "n".repeat(ValidationUtil.MAX_NAME_LENGTH);
    assertThat(ValidationUtil.requireName(name, "job type")).isSameAs(name);
    String key = "k".repeat(ValidationUtil.MAX_KEY_LENGTH);
    assertThat(ValidationUtil.requireKey(key, "event key")).isSameAs(key);
  }

  @Test
  void nullBlankAndTooLongValuesAreRejected() {
    assertThatThrownBy(() -> ValidationUtil.requireName(null, "job type"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("job type");
    assertThatThrownBy(() -> ValidationUtil.requireName(" \t", "job type"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("job type must not be blank");
    assertThatThrownBy(() -> ValidationUtil.requireName("n".repeat(201), "step name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("step name must have at most 200 characters, but has 201");
    assertThatThrownBy(() -> ValidationUtil.requireKey("k".repeat(501), "event key"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("event key must have at most 500 characters, but has 501");
  }

  @Test
  void longMessagesAreTruncated() {
    assertThat(ValidationUtil.truncateMessage(null)).isNull();
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
    String bikes = "\uD83D\uDEB2".repeat(200);
    assertThat(ValidationUtil.requireName(bikes, "job type")).isSameAs(bikes);
    assertThatThrownBy(() -> ValidationUtil.requireName(bikes + "x", "job type"))
        .hasMessage("job type must have at most 200 characters, but has 201");
  }
}
