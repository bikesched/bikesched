// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Records the runs of step bodies in this JVM: how often and in which order per job. */
final class Executions {

  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
  private final Map<UUID, List<String>> order = new ConcurrentHashMap<>();

  /** Records one run of the step's body. */
  void record(UUID jobId, String step) {
    counts.computeIfAbsent(jobId + "/" + step, _ -> new AtomicInteger()).incrementAndGet();
    order.computeIfAbsent(jobId, _ -> new CopyOnWriteArrayList<>()).add(step);
  }

  /** The recorded runs of the job's steps, in order. */
  List<String> of(UUID jobId) {
    return List.copyOf(order.getOrDefault(jobId, List.of()));
  }

  /** How often the step's body ran. */
  int of(UUID jobId, String step) {
    AtomicInteger count = counts.get(jobId + "/" + step);
    return count == null ? 0 : count.get();
  }

  /** The number of jobs whose step (or attempt, as recorded) ran at least once. */
  long jobsWith(String step) {
    return counts.keySet().stream().filter(key -> key.endsWith("/" + step)).count();
  }

  /** The number of step bodies that ran more than once. */
  long repeated() {
    return counts.values().stream().filter(count -> count.get() > 1).count();
  }

  /** The number of step bodies that ran at least once. */
  int distinct() {
    return counts.size();
  }
}
