package bikesched.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the log events of one logger for assertions (test-only; public for the tests in
 * sub-packages). Capturing stops on {@link #close()}; the
 * captured events stay available afterwards:
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

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        appender.start();
        logger.addAppender(appender);
    }

    /** Starts capturing the events of the logger named after the given class. */
    public static LogCapture of(Class<?> type) {
        return new LogCapture((Logger) LoggerFactory.getLogger(type));
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
        logger.detachAppender(appender);
        appender.stop();
    }
}
