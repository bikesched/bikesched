// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the log events of one logger for assertions (test-only; public for the tests in
 * sub-packages). Capturing stops on {@link #close()}; the captured events stay available
 * afterwards:
 *
 * <pre>{@code
 * var logs = LogCapture.of(DefaultBikesched.class);
 * try (logs; Bikesched bikesched = ...) {
 *     ...
 * }
 * assertThat(logs.messages()).contains(...);
 * }</pre>
 */
public final class LogCapture implements AutoCloseable {

  private final List<Logger> loggers;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private LogCapture(List<Logger> loggers) {
    this.loggers = loggers;
    appender.start();
    loggers.forEach(logger -> logger.addAppender(appender));
  }

  /**
   * Starts capturing the events of the loggers named after the given classes, interleaved in
   * logging order.
   */
  public static LogCapture of(Class<?>... types) {
    return new LogCapture(
        java.util.Arrays.stream(types)
            .map(type -> (Logger) LoggerFactory.getLogger(type))
            .toList());
  }

  /** The captured events, in logging order. */
  public List<ILoggingEvent> events() {
    return appender.list;
  }

  /** The formatted messages of the captured events, in logging order. */
  public List<String> messages() {
    return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Discards the events captured so far. */
  public void clear() {
    appender.list.clear();
  }

  /** Stops capturing; idempotent. */
  @Override
  public void close() {
    loggers.forEach(logger -> logger.detachAppender(appender));
    appender.stop();
  }
}
