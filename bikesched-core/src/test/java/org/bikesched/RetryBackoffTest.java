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
  void exponentialDoublesUpToTheMaximumWithJitter() {
    var backoff = RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(1));
    long[] maxSeconds = {5, 10, 20, 40, 60, 60, 60};
    for (int attempt = 1; attempt <= maxSeconds.length; attempt++) {
      Duration max = Duration.ofSeconds(maxSeconds[attempt - 1]);
      int failedAttempt = attempt;
      assertThat(IntStream.range(0, 100).mapToObj(_ -> backoff.delay(failedAttempt)))
          .allSatisfy(delay -> assertThat(delay).isBetween(max.dividedBy(2), max))
          .as("jittered")
          .doesNotHaveDuplicates();
    }
  }

  @Test
  void exponentialDoesNotOverflowForHighAttempts() {
    var backoff = RetryBackoff.exponential(Duration.ofMillis(1), Duration.ofHours(1));
    assertThat(backoff.delay(Integer.MAX_VALUE))
        .isBetween(Duration.ofMinutes(30), Duration.ofHours(1));
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
