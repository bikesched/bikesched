// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.chat;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A chat platform the bot posts replies to, simulated in memory. */
final class SimulatedChatPlatform {

  private final PrintStream out;
  private final Map<String, AtomicInteger> replies = new ConcurrentHashMap<>();

  SimulatedChatPlatform(PrintStream out) {
    this.out = out;
  }

  /**
   * Posts a reply in a message's thread.
   *
   * @param channel the channel
   * @param messageId the message replied to
   * @param text the reply
   */
  void reply(String channel, String messageId, String text) {
    replies.computeIfAbsent(messageId, ignored -> new AtomicInteger()).incrementAndGet();
    out.printf(
        "%s [chat]     %s, reply to %s: %s%n", ChatExample.timestamp(), channel, messageId, text);
  }

  /** The number of replies to a message. */
  int replies(String messageId) {
    AtomicInteger count = replies.get(messageId);
    return count == null ? 0 : count.get();
  }

  /** The number of replies to all messages. */
  int replies() {
    return replies.values().stream().mapToInt(AtomicInteger::get).sum();
  }
}
