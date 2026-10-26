// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobEntry;
import org.bikesched.history.StepEntry;

/** Reading history entries in tests: their actions in a compact form and their data. */
public final class HistoryTesting {

  private HistoryTesting() {}

  /**
   * The entries' actions: {@code ACTION} for job entries, {@code step ACTION} for step entries (the
   * step's name from the data), {@code subjectName ACTION} for other entries.
   */
  public static List<String> actions(List<? extends HistoryEntry> history) {
    return history.stream().map(HistoryTesting::action).toList();
  }

  /** One entry's action, see {@link #actions}. */
  public static String action(HistoryEntry entry) {
    return switch (entry) {
      case JobEntry job -> job.action().name();
      case StepEntry step -> data(step, "stepName") + " " + step.action();
      default -> data(entry, "subjectName") + " " + entry.action().name();
    };
  }

  /** The entries' actions with their states, {@code ACTION:STATE}, see {@link #actions}. */
  public static List<String> actionsAndStates(List<? extends HistoryEntry> history) {
    return history.stream()
        .map(
            entry ->
                switch (entry) {
                  case JobEntry job -> action(job) + ":" + job.state();
                  case StepEntry step -> action(step) + ":" + step.state();
                  default -> action(entry);
                })
        .toList();
  }

  /**
   * A value of the entry's data as text: a string without its quotes, a number as written; {@code
   * null} if the key is missing.
   */
  public static String data(HistoryEntry entry, String key) {
    String data = entry.data();
    if (data == null) {
      return null;
    }
    Matcher matcher =
        Pattern.compile("\"" + Pattern.quote(key) + "\": (\"((?:[^\"\\\\]++|\\\\.)*+)\"|[^,}]+)")
            .matcher(data);
    if (!matcher.find()) {
      return null;
    }
    return matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
  }

  /** The entry with this action (see {@link #actions}); fails if there is none. */
  public static HistoryEntry entry(List<? extends HistoryEntry> history, String action) {
    return history.stream()
        .filter(entry -> action(entry).equals(action))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No entry " + action + " in " + actions(history)));
  }
}
