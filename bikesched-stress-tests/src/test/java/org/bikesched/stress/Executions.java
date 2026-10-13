// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts how often the body of each step ran, in this JVM: steps run at least once. */
final class Executions {

  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

  /** Records one run of the step's body. */
  void record(UUID jobId, String step) {
    counts.computeIfAbsent(jobId + "/" + step, _ -> new AtomicInteger()).incrementAndGet();
  }

  /** How often the step's body ran. */
  int of(UUID jobId, String step) {
    AtomicInteger count = counts.get(jobId + "/" + step);
    return count == null ? 0 : count.get();
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
