package org.bikesched.examples.event;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.event.EventExample.EventPayload;

/**
 * A service that does some work and reports its completion as an event, like a payment provider
 * calling a webhook. Here it publishes the event itself: right away, or after a delay on another
 * thread.
 */
public final class EventService {

  private final Bikesched bikesched;

  EventService(Bikesched bikesched) {
    this.bikesched = bikesched;
  }

  /**
   * Processes a request and publishes {@link EventExample#EVENT_TYPE}, keyed by the id it returns.
   *
   * @param jobId the id from the job's payload, included in the event's payload
   * @param delay how long to wait before publishing the event on another thread; {@code null} to
   *     publish it right away
   * @return the id of the processing, the key of the event
   */
  public UUID process(UUID jobId, Duration delay) {
    UUID id = UUID.randomUUID();
    System.out.println(
        "Service: processing job: "
            + jobId
            + " as event: "
            + id
            + (delay == null ? "" : ", delay " + delay));
    Runnable publish =
        () -> {
          bikesched
              .newEvent(EventExample.EVENT_TYPE, id.toString())
              .payload(new EventPayload(id, jobId))
              .publish();
          System.out.println("Service: published the event: " + id);
        };
    if (delay == null) {
      publish.run();
    } else {
      Thread.ofVirtual()
          .name("event-service")
          .start(
              () -> {
                try {
                  Thread.sleep(delay);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                publish.run();
              });
    }
    return id;
  }
}
