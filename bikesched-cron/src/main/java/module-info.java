// SPDX-License-Identifier: Apache-2.0
/**
 * Cron expressions for bikesched: {@link org.bikesched.cron.CronRecurrence} runs a recurring job on
 * a cron schedule, {@link org.bikesched.cron.CronRecurrenceParser} parses cron expressions given as
 * strings ({@code schedule(String)}, {@code @JobHandler(schedule = …)}). Parsing comes from
 * cron-utils.
 */
// cron-utils has no module descriptor, only an Automatic-Module-Name.
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"})
module org.bikesched.cron {
  requires transitive org.bikesched;
  requires transitive com.cronutils;

  exports org.bikesched.cron;
}
