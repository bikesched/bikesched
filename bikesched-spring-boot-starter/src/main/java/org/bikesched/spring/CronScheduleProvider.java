// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.time.ZoneOffset;
import org.bikesched.annotation.ScheduleProvider;
import org.bikesched.job.Recurrence;
import org.springframework.scheduling.support.CronExpression;

/** Schedule provider for Spring's six-field cron expressions. */
final class CronScheduleProvider implements ScheduleProvider {

  @Override
  public boolean supports(String schedule) {
    return CronExpression.isValidExpression(schedule);
  }

  @Override
  public Recurrence parse(String schedule) {
    CronExpression expression = CronExpression.parse(schedule);
    return Recurrence.of(
        "cron " + schedule,
        previous -> expression.next(previous.atZone(ZoneOffset.UTC)).toInstant());
  }
}
