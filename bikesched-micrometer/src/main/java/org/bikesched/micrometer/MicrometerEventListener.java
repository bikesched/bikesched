// SPDX-License-Identifier: Apache-2.0
package org.bikesched.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.util.Locale;
import java.util.Objects;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;

/**
 * Records the events published through a node as Micrometer meters:
 *
 * <pre>{@code
 * Bikesched.builder(dataSource)
 *     .eventListener(new MicrometerEventListener(registry, Tags.of("node", "node-1")))
 *     .build();
 * }</pre>
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Name</th><th>Type</th><th>Tags</th><th>Measures</th></tr>
 *   <tr><td>{@code bikesched.events.published}</td><td>counter</td><td>{@code event.name},
 *       {@code source}</td><td>published events ({@code source}: {@code node} or {@code
 *       job})</td></tr>
 *   <tr><td>{@code bikesched.event.deliveries}</td><td>counter</td><td>{@code
 *       event.name}</td><td>waiting steps that received an event when it was published</td></tr>
 * </table>
 *
 * <p>All meters also carry the tags given to the constructor. Events published in the caller's
 * transaction are not reported (see {@link EventListener}), so they are not counted.
 */
public final class MicrometerEventListener implements EventListener {

  private final MeterRegistry registry;
  private final Tags tags;

  /**
   * Records the meters without additional tags.
   *
   * @param registry the registry
   */
  public MicrometerEventListener(MeterRegistry registry) {
    this(registry, Tags.empty());
  }

  /**
   * Records the meters with additional tags.
   *
   * @param registry the registry
   * @param tags tags added to every meter, e.g. the node name
   */
  public MicrometerEventListener(MeterRegistry registry, Iterable<Tag> tags) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.tags = Tags.of(Objects.requireNonNull(tags, "tags"));
  }

  @Override
  public void onEventPublished(EventPublication publication) {
    Counter.builder("bikesched.events.published")
        .description("Published events")
        .tags(tags)
        .tag("event.name", publication.eventName())
        .tag("source", publication.source().name().toLowerCase(Locale.ROOT))
        .register(registry)
        .increment();
    Counter.builder("bikesched.event.deliveries")
        .description("Waiting steps that received an event when it was published")
        .tags(tags)
        .tag("event.name", publication.eventName())
        .register(registry)
        .increment(publication.receivedSteps());
  }
}
