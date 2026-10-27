// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import java.util.List;
import org.bikesched.history.EventEntry;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobEntry;
import org.bikesched.history.NodeEntry;
import org.bikesched.history.StepEntry;

/** Reading history entries in tests: their actions in a compact form and their data. */
public final class HistoryTesting {

  private HistoryTesting() {}

  /**
   * The entries' actions: {@code ACTION} for job entries, {@code step ACTION} for step entries (the
   * step's name from the data), {@code name ACTION} with the event's or node's name for other
   * entries.
   */
  public static List<String> actions(List<? extends HistoryEntry> history) {
    return history.stream().map(HistoryTesting::action).toList();
  }

  /** One entry's action, see {@link #actions}. */
  public static String action(HistoryEntry entry) {
    return switch (entry) {
      case JobEntry job -> job.action().name();
      case StepEntry step -> data(step, "step.name") + " " + step.action();
      case EventEntry event -> data(event, "event.name") + " " + event.action();
      case NodeEntry node -> data(node, "node.name") + " " + node.action();
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
   * A value of the entry's data as text: a string as is, a number as written; {@code null} if the
   * key is missing. A key of a nested object is named by its path, e.g. {@code job.name}.
   */
  public static String data(HistoryEntry entry, String path) {
    Object value = data(entry);
    for (String key : path.split("\\.")) {
      if (!(value instanceof JsonObject object) || !object.containsKey(key)) {
        return null;
      }
      value = object.get(key);
    }
    return value == null ? null : value.toString();
  }

  /** The entry's data as a JSON object; empty if it has none. */
  public static JsonObject data(HistoryEntry entry) {
    String data = entry.data();
    if (data == null) {
      return new JsonObject();
    }
    try {
      return JsonParser.object().from(data);
    } catch (JsonParserException e) {
      throw new AssertionError("Not a JSON object: " + data, e);
    }
  }

  /** The entry with this action (see {@link #actions}); fails if there is none. */
  public static HistoryEntry entry(List<? extends HistoryEntry> history, String action) {
    return history.stream()
        .filter(entry -> action(entry).equals(action))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No entry " + action + " in " + actions(history)));
  }
}
