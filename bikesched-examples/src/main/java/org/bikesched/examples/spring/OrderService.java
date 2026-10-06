// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.annotation.JobHandler;
import org.bikesched.job.Job;
import org.bikesched.job.JobType;
import org.bikesched.spring.BikeschedTransactions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order side of the example. {@link #placeOrder(String, String)} stores an {@link Order} and
 * schedules the job that fulfills it in the same transaction. The {@code fulfill-order} handler,
 * discovered by the starter, is the saga: it asks the {@link FulfillmentService} — in a real
 * deployment another microservice — to fulfill the order as a step, waits for its event and then
 * marks the order fulfilled. The services share no data: {@link Fulfillment} references the order
 * by UUID only, without a foreign key, and the {@link Order} only knows its own {@code fulfilled}
 * state.
 */
@Service
public class OrderService {

  public static final JobType<FulfillOrder> FULFILL_ORDER =
      JobType.of("fulfill-order", FulfillOrder.class);

  private final OrderRepository orders;
  private final FulfillmentService fulfillment;
  private final Bikesched bikesched;
  private final DataSource dataSource;

  public OrderService(
      OrderRepository orders,
      FulfillmentService fulfillment,
      Bikesched bikesched,
      DataSource dataSource) {
    this.orders = orders;
    this.fulfillment = fulfillment;
    this.bikesched = bikesched;
    this.dataSource = dataSource;
  }

  @Transactional
  public UUID placeOrder(String customer, String item) {
    Order order = orders.save(new Order(customer, item));
    return bikesched
        .newJob(FULFILL_ORDER)
        .payload(new FulfillOrder(order.getId()))
        .connection(BikeschedTransactions.transactionalConnection(dataSource))
        .schedule();
  }

  @JobHandler(type = "fulfill-order")
  public void fulfill(Job job, FulfillOrder command) throws Exception {
    Order order = orders.findById(command.orderId()).orElseThrow();
    UUID fulfillmentId = job.step("fulfill", UUID.class, _ -> fulfillment.fulfill(order.getId()));
    FulfillmentService.FulfillmentFinished finished =
        job.awaitEvent(
            "await-fulfillment", FulfillmentService.FULFILLMENT_FINISHED, fulfillmentId.toString());
    order.markFulfilled();
    orders.save(order);
    System.out.println("Order " + order.getId() + " fulfilled (" + finished.fulfillmentId() + ")");
  }

  public record FulfillOrder(UUID orderId) {}
}
