// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bikesched.job.Recurrence;

/** Resolves annotation schedule expressions through the built-in and supplied providers. */
final class ScheduleProviders {

  private static final ScheduleProvider ISO_DURATION =
      new ScheduleProvider() {
        @Override
        public boolean supports(String schedule) {
          return schedule.startsWith("P");
        }

        @Override
        public Recurrence parse(String schedule) {
          return Recurrence.every(Duration.parse(schedule));
        }
      };

  private ScheduleProviders() {}

  static Recurrence parse(String schedule, ScheduleProvider... addedProviders) {
    List<ScheduleProvider> providers = new ArrayList<>();
    providers.add(ISO_DURATION);
    for (ScheduleProvider provider : addedProviders) {
      providers.add(Objects.requireNonNull(provider, "schedule provider"));
    }

    List<ScheduleProvider> matching =
        providers.stream().filter(provider -> provider.supports(schedule)).toList();
    if (matching.isEmpty()) {
      throw new IllegalArgumentException(
          "No ScheduleProvider supports schedule '" + schedule + "'");
    }
    if (matching.size() > 1) {
      throw new IllegalArgumentException(
          "Multiple ScheduleProviders support schedule '" + schedule + "'");
    }
    return matching.getFirst().parse(schedule);
  }
}
