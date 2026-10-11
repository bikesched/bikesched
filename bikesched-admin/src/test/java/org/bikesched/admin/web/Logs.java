// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the log events of one logger for assertions (test-only). Capturing stops on {@link
 * #close()}; the captured events stay available afterwards.
 */
final class Logs implements AutoCloseable {

  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private Logs(String name) {
    logger = (Logger) LoggerFactory.getLogger(name);
    appender.start();
    logger.addAppender(appender);
  }

  /** Starts capturing the events of the named logger. */
  static Logs of(String name) {
    return new Logs(name);
  }

  /** The formatted messages of the captured events, in logging order. */
  List<String> messages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
