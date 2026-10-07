// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.time.ZoneOffset;
import org.bikesched.job.Recurrence;
import org.bikesched.job.RecurrenceParser;
import org.springframework.scheduling.support.CronExpression;

/** Recurrence parser for Spring's six-field cron expressions, evaluated in UTC. */
final class SpringCronRecurrenceParser implements RecurrenceParser {

  @Override
  public boolean supports(String expression) {
    return CronExpression.isValidExpression(expression);
  }

  @Override
  public Recurrence parse(String expression) {
    CronExpression cron = CronExpression.parse(expression);
    return Recurrence.of(
        "cron " + expression, previous -> cron.next(previous.atZone(ZoneOffset.UTC)).toInstant());
  }

  @Override
  public String toString() {
    return "SpringCronRecurrenceParser";
  }
}
