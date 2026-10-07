// SPDX-License-Identifier: Apache-2.0
package org.bikesched.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cronutils.model.CronType;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CronRecurrenceParserTest {

  @Test
  void supportsValidExpressionsOfItsDialect() {
    var unix = new CronRecurrenceParser();
    var quartz = new CronRecurrenceParser(CronType.QUARTZ, ZoneId.of("Europe/Berlin"));

    assertThat(unix.supports("0 3 * * MON-FRI")).isTrue();
    assertThat(unix.supports("0 0 3 ? * MON-FRI")).isFalse();
    assertThat(unix.supports("PT1H")).isFalse();
    assertThat(quartz.supports("0 0 3 ? * MON-FRI")).isTrue();
    assertThat(quartz.supports("0 3 * * MON-FRI")).isFalse();
  }

  @Test
  void parsesIntoACronRecurrenceInItsZone() {
    var parser = new CronRecurrenceParser(CronType.UNIX, ZoneId.of("Europe/Berlin"));

    assertThat(parser.parse("0 3 * * MON-FRI"))
        .isInstanceOf(CronRecurrence.class)
        .hasToString("cron unix 0 3 * * 1-5 Europe/Berlin")
        .satisfies(
            recurrence ->
                assertThat(recurrence.next(Instant.parse("2026-10-03T00:00:00Z")))
                    .isEqualTo(Instant.parse("2026-10-05T01:00:00Z")));
  }

  @Test
  void rejectsSupportedExpressionsWithoutAFutureExecution() {
    var parser = new CronRecurrenceParser();

    assertThat(parser.supports("0 0 30 2 *")).isTrue();
    assertThatThrownBy(() -> parser.parse("0 0 30 2 *"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unix cron expression '0 0 30 2 *' has no future execution");
  }
}
