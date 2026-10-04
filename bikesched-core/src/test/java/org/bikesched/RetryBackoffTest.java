package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

  @Test
  void noneRetriesImmediately() {
    assertThat(RetryBackoff.none().delay(1)).isZero();
    assertThat(RetryBackoff.none().delay(100)).isZero();
  }

  @Test
  void fixedAlwaysReturnsTheSameDelay() {
    var backoff = RetryBackoff.fixed(Duration.ofSeconds(30));
    assertThat(IntStream.rangeClosed(1, 5).mapToObj(backoff::delay))
        .containsOnly(Duration.ofSeconds(30));
  }

  @Test
  void exponentialDoublesUpToTheMaximum() {
    var backoff = RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(1));
    assertThat(IntStream.rangeClosed(1, 7).mapToObj(backoff::delay))
        .containsExactly(
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(40),
            Duration.ofMinutes(1),
            Duration.ofMinutes(1),
            Duration.ofMinutes(1));
  }

  @Test
  void exponentialDoesNotOverflowForHighAttempts() {
    var backoff = RetryBackoff.exponential(Duration.ofMillis(1), Duration.ofHours(1));
    assertThat(backoff.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void invalidArgumentsAreRejected() {
    assertThatThrownBy(() -> RetryBackoff.fixed(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("delay must not be negative: PT-1S");
    assertThatThrownBy(() -> RetryBackoff.exponential(Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("initialDelay must be positive");
    assertThatThrownBy(
            () -> RetryBackoff.exponential(Duration.ofSeconds(10), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxDelay (PT5S) must not be shorter than initialDelay (PT10S)");
  }
}
