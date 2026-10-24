// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.annotation.JobFailureHandler;
import org.bikesched.annotation.JobHandler;
import org.bikesched.job.FailedJob;
import org.bikesched.job.Job;
import org.bikesched.job.TypedJob;
import org.bikesched.spring.BikeschedTransactions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order side of the example. {@link #placeOrder(String, String)} stores an {@link Order} and
 * schedules the job that fulfills it in the same transaction. The {@code fulfill-order} handler,
 * discovered by the starter, is the saga: it asks the {@link FulfillmentService} — in a real
 * deployment another microservice — to fulfill the order as a step, waits for its event and then
 * marks the order fulfilled; if the job fails for good, its {@code @JobFailureHandler} marks the
 * order failed. The services share no data: {@link Fulfillment} references the order by UUID only,
 * without a foreign key, and the {@link Order} only knows its own {@code fulfilled} state.
 */
@Service
public class OrderService {

  public static final TypedJob<FulfillOrder> FULFILL_ORDER =
      TypedJob.of("fulfill-order", FulfillOrder.class);

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

  @JobHandler(name = "fulfill-order")
  public void fulfill(Job job, FulfillOrder command) throws Exception {
    Order order = orders.findById(command.orderId()).orElseThrow();
    UUID fulfillmentId =
        job.step(
            "fulfill", UUID.class, ignored -> fulfillment.fulfill(order.getId(), order.getItem()));
    FulfillmentService.FulfillmentFinished finished =
        job.awaitEvent(
            "await-fulfillment", FulfillmentService.FULFILLMENT_FINISHED, fulfillmentId.toString());
    order.markFulfilled();
    orders.save(order);
    System.out.println("Order " + order.getId() + " fulfilled (" + finished.fulfillmentId() + ")");
  }

  @JobFailureHandler(name = "fulfill-order")
  public void fulfillmentFailed(FailedJob failed, FulfillOrder command) {
    Order order = orders.findById(command.orderId()).orElseThrow();
    order.markFailed();
    orders.save(order);
    System.out.println("Order " + order.getId() + " failed: " + failed.error());
  }

  public record FulfillOrder(UUID orderId) {}
}
