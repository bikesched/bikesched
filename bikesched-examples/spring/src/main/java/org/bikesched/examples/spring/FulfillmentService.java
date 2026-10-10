// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.event.EventType;
import org.bikesched.spring.BikeschedTransactions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fulfills an order on another thread: it stores the {@link Fulfillment} and publishes the {@link
 * #FULFILLMENT_FINISHED} event on the same transaction connection, so both are committed together.
 * Items out of stock are rejected right away.
 */
@Service
public class FulfillmentService {

  public static final EventType<FulfillmentFinished> FULFILLMENT_FINISHED =
      EventType.of("fulfillment-finished", FulfillmentFinished.class);

  private final FulfillmentRepository fulfillments;
  private final Bikesched bikesched;
  private final DataSource dataSource;
  private final TransactionTemplate transactions;

  public FulfillmentService(
      FulfillmentRepository fulfillments,
      Bikesched bikesched,
      DataSource dataSource,
      TransactionTemplate transactions) {
    this.fulfillments = fulfillments;
    this.bikesched = bikesched;
    this.dataSource = dataSource;
    this.transactions = transactions;
  }

  /** Items that can't be fulfilled. */
  static final Set<String> OUT_OF_STOCK = Set.of("Unicorn");

  /**
   * Starts the fulfillment and returns the id the job waits for.
   *
   * @throws IllegalStateException if the item is out of stock
   */
  public UUID fulfill(UUID orderId, String item) {
    if (OUT_OF_STOCK.contains(item)) {
      throw new IllegalStateException(item + " is out of stock");
    }
    UUID fulfillmentId = UUID.randomUUID();
    Thread.ofVirtual()
        .name("fulfillment")
        .start(
            () -> {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                return;
              }
              transactions.executeWithoutResult(_ -> finish(orderId, fulfillmentId));
            });
    return fulfillmentId;
  }

  private void finish(UUID orderId, UUID fulfillmentId) {
    fulfillments.save(new Fulfillment(fulfillmentId, orderId));
    bikesched
        .newEvent(FULFILLMENT_FINISHED, fulfillmentId.toString())
        .payload(new FulfillmentFinished(fulfillmentId, orderId))
        .connection(BikeschedTransactions.transactionalConnection(dataSource))
        .publish();
  }

  public record FulfillmentFinished(UUID fulfillmentId, UUID orderId) {}
}
