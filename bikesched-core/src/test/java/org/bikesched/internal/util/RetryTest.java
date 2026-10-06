// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The retries of a failed call: a few attempts with a short delay, then the last failure. */
class RetryTest {

  private static final Retry RETRY = Retry.of(3, Duration.ofMillis(1));

  @Test
  void returnsTheFirstSuccessfulResult() {
    var calls = new AtomicInteger();
    assertThat(RETRY.call("Testing failed", () -> calls.incrementAndGet()))
        .as("no retry once the call succeeded")
        .isEqualTo(1);
    assertThat(calls).hasValue(1);
  }

  @Test
  void retriesUntilTheCallSucceeds() {
    var calls = new AtomicInteger();
    String result =
        RETRY.call(
            "Testing failed",
            () -> {
              if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("blip " + calls.get());
              }
              return "recorded";
            });

    assertThat(result).isEqualTo("recorded");
    assertThat(calls).hasValue(3);
  }

  @Test
  void throwsTheLastFailureOnceTheAttemptsAreUsed() {
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                RETRY.call(
                    "Testing failed",
                    () -> {
                      throw new IllegalStateException("blip " + calls.incrementAndGet());
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("blip 3");
    assertThat(calls).hasValue(3);
  }

  @Test
  void doesNotRetryOnceTheCallingThreadIsInterrupted() throws Exception {
    var calls = new AtomicInteger();
    var interrupted = new boolean[2];
    Thread thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  Thread.currentThread().interrupt(); // e.g. the node is closing
                  try {
                    RETRY.call(
                        "Testing failed",
                        () -> {
                          throw new IllegalStateException("blip" + calls.incrementAndGet());
                        });
                  } catch (IllegalStateException e) {
                    interrupted[0] = true;
                    interrupted[1] = Thread.currentThread().isInterrupted();
                  }
                });
    thread.join();

    assertThat(interrupted[0]).as("the original failure is thrown").isTrue();
    assertThat(interrupted[1]).as("the interrupt flag stays set").isTrue();
    assertThat(calls).as("no second attempt").hasValue(1);
  }

  @Test
  void rejectsInvalidSettings() {
    assertThatThrownBy(() -> Retry.of(0, Duration.ofMillis(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 1");
    assertThatThrownBy(() -> Retry.of(3, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be negative");
  }
}
