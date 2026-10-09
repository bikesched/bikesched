// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedBuilder;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;
import org.bikesched.RetryBackoff;
import org.bikesched.event.EventPublisher;
import org.bikesched.event.EventType;
import org.bikesched.event.EventWait;
import org.bikesched.event.StepEventBuilder;
import org.bikesched.event.TypedEventWait;

/**
 * A job being executed, as seen by its {@link JobHandler}.
 *
 * <p>A handler can {@link #cancel() cancel} the job it executes: once the handler returns, the job
 * ends in state {@code CANCELLED} and is not retried, regardless of the remaining attempts.
 * Cancelling does not stop the handler, so return right after it:
 *
 * <pre>{@code
 * bikesched.registerJobHandler("send-mail", job -> {
 *     if (customerDeleted(job)) {
 *         job.cancel("customer deleted");
 *         return;
 *     }
 *     mailer.send(job.payload());
 * });
 * }</pre>
 *
 * <p>A handler can split its work into durable {@link #step(String, Class, StepHandler) steps}: a
 * step that completed is not executed again when the job is retried, its stored result is returned
 * instead. Steps built with {@link #newStep(String, Class)} can have a compensation that undoes
 * them if the job is cancelled or fails permanently.
 */
public interface Job extends EventPublisher {

  /**
   * Starts building an event that is published atomically with a durable step of this job.
   *
   * @param eventName event name
   * @param eventKey event key
   * @return a durable event builder; its step name defaults to {@code publish <eventName>}
   */
  @Override
  StepEventBuilder<Object> newEvent(String eventName, String eventKey);

  /**
   * Starts building a typed event that is published atomically with a durable step.
   *
   * @param eventType event name and payload type
   * @param eventKey event key
   * @param <T> payload type
   * @return a durable event builder
   */
  @Override
  <T> StepEventBuilder<T> newEvent(EventType<T> eventType, String eventKey);

  /**
   * Returns the job id.
   *
   * @return the job id
   */
  UUID id();

  /**
   * Returns the job type, which identifies the handler that executes the job.
   *
   * @return the job type
   */
  String type();

  /**
   * Returns the number of attempts including the current one.
   *
   * @return the attempt number, starting at 1
   */
  int attempts();

  /**
   * Returns the maximum number of attempts.
   *
   * @return the maximum number of attempts
   */
  int maxAttempts();

  /**
   * Returns the raw job payload.
   *
   * @return the payload as JSON text, may be {@code null}
   * @see #payload(Class)
   */
  String payload();

  /**
   * Returns the job payload converted by the configured {@link JsonMapper}:
   *
   * <pre>{@code
   * Order order = job.payload(Order.class);
   * }</pre>
   *
   * @param type the type to convert the payload to
   * @param <T> the type to convert the payload to
   * @return the payload, or {@code null} if the job has none
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default mapper (no {@code jsonMapper} configured) for types other than {@link
   *     String}
   */
  <T> T payload(Class<T> type);

  /**
   * Executes a durable step of this job, like a durable function: if the step already completed in
   * an earlier attempt of the job, its stored result is returned without executing the handler
   * again. Otherwise the handler is executed, and its result is stored in {@code bikesched_steps}
   * (committed right away) and returned. Results are converted to and from JSON by the configured
   * {@link JsonMapper}.
   *
   * <pre>{@code
   * bikesched.registerJobHandler("order", job -> {
   *     Order order = job.payload(Order.class);
   *     Payment payment = job.step("charge-card", Payment.class, step -> payments.charge(order));
   *     job.step("send-confirmation", Void.class, step -> {
   *         mailer.send(order, payment);
   *         return null;
   *     });
   * });
   * }</pre>
   *
   * <p>If the handler throws, the step is marked {@code FAILED} and the exception is rethrown,
   * which fails the job's attempt: on the next attempt, completed steps are skipped and the failed
   * step is executed again. Step names identify steps across attempts, so they must be unique
   * within the job and the handler must call its steps in a deterministic way. A step may still run
   * more than once (e.g. if the node dies before its result is stored), so steps should be
   * idempotent. Steps run on the calling thread, one at a time, and cannot be nested: a step
   * handler (or compensation) must not run steps or wait for events itself, so that every step
   * completes or fails as a whole.
   *
   * @param name the step name, unique within the job, at most 200 characters
   * @param resultType the type of the step result, used to read a stored result back
   * @param handler executes the step and returns its result (may be {@code null})
   * @param <T> the type of the step result
   * @return the step result, may be {@code null}
   * @throws Exception the exception thrown by the handler
   * @throws BikeschedException if the step cannot be read or stored, e.g. because the mapped result
   *     is not valid JSON
   * @throws IllegalArgumentException if the name is blank
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  <T> T step(String name, Class<T> resultType, StepHandler<T> handler) throws Exception;

  /**
   * Executes a durable step of this job with no result, like {@link #step(String, Class,
   * StepHandler)} with {@code Void.class}, running a plain {@link Runnable} that does not need the
   * {@link Step} itself:
   *
   * <pre>{@code
   * job.step("send-confirmation", () -> mailer.send(order, payment));
   * }</pre>
   *
   * @param name the step name, unique within the job, at most 200 characters
   * @param runnable executes the step
   * @throws BikeschedException if the step cannot be stored
   * @throws IllegalArgumentException if the name is blank
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  default void step(String name, Runnable runnable) {
    try {
      step(
          name,
          Void.class,
          _ -> {
            runnable.run();
            return null;
          });
    } catch (Exception e) {
      // StepHandler declares a checked Exception for other steps; this one only ever wraps a
      // plain Runnable, which cannot throw a checked exception.
      throw (RuntimeException) e;
    }
  }

  /**
   * Builds a durable step that can have a compensation (a saga): if the job is cancelled, or fails
   * permanently, after the step completed, the compensation undoes it before the job ends.
   *
   * <pre>{@code
   * bikesched.registerJobHandler("order", job -> {
   *     Order order = job.payload(Order.class);
   *     PaymentReservation reservation =
   *         job.newStep("reserve-payment", PaymentReservation.class)
   *             .compensateWith((step, r) -> payments.release(r))
   *             .run(step -> payments.reserve(order));
   *     job.newStep("ship", Void.class).run(step -> {   // no compensation: same as job.step(...)
   *         shipping.ship(order, reservation);
   *         return null;
   *     });
   * });
   * }</pre>
   *
   * <p>Compensation is optional: a job whose completed steps have no compensation is cancelled or
   * failed right away, as without this method. Otherwise the job becomes {@code COMPENSATING}: a
   * node runs its handler again in compensation mode, where completed steps return their stored
   * results (registering their compensations) and the replay ends at the first step, event wait or
   * sleep that has not completed, or when the handler returns or throws. Then the compensations run
   * in reverse order, each recorded once it succeeded (steps become {@code COMPENSATED}). A failed
   * compensation is retried in further compensation attempts (up to the job's maximum number of
   * attempts, with the node's retry backoff), afterwards the job is {@code COMPENSATION_FAILED}.
   * Once all compensations ran, the job ends {@code CANCELLED} or {@code FAILED}.
   *
   * @param name the step name, unique within the job, at most 200 characters
   * @param resultType the type of the step result, used to read a stored result back and passed to
   *     the compensation
   * @param <T> the type of the step result
   * @return the step builder
   * @throws IllegalArgumentException if the name is blank
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  <T> StepBuilder<T> newStep(String name, Class<T> resultType);

  /**
   * Returns whether the handler runs to compensate the job (see {@link #newStep(String, Class)}):
   * steps then only return their stored results and are not executed. Code outside of steps runs
   * again, so a handler may check this to skip side effects there.
   *
   * @return {@code true} while the job is compensated
   */
  boolean isCompensating();

  /**
   * Starts waiting for an event, as a durable step; {@link EventWait#receive(Class)} returns its
   * payload once the event published with the given name and key was received. If it has not been
   * published yet, the job's attempt ends in {@code receive} (it does not return) and the job is
   * suspended in state {@code WAITING}, using no thread and no concurrency slot. Once someone
   * {@link Bikesched#newEvent(String, String) publishes} the event, the job is scheduled again and
   * its handler runs again from the start: completed steps are skipped, and {@code receive} now
   * returns the payload, which was stored with the step when the event arrived.
   *
   * <pre>{@code
   * bikesched.registerJobHandler("order", job -> {
   *     Order order = job.payload(Order.class);
   *     job.step("create-invoice", Invoice.class, step -> billing.createInvoice(order));
   *     Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
   *             .timeout(Duration.ofDays(3), () -> Payment.missing())   // optional
   *             .receive(Payment.class);
   *     job.step("ship", Void.class, step -> {
   *         shipping.ship(order, payment);
   *         return null;
   *     });
   * });
   *
   * // elsewhere, e.g. in a payment webhook
   * bikesched.newEvent("payment-received", orderId).payload(payment).publish();
   * }</pre>
   *
   * <p>Events published before the job starts waiting are not lost: they are kept for their {@link
   * org.bikesched.event.EventBuilder#retention(Duration) retention} (default {@link
   * BikeschedBuilder#eventRetention(Duration)}), and the step receives the latest one right away.
   * An event is delivered to every step waiting for it: each step stores a copy of its payload, so
   * later runs return it even after the event was deleted. To derive something from the payload
   * durably (e.g. with side effects), do it in a {@link #step(String, Class, StepHandler) step}
   * after the wait; a pure conversion can simply run again on every run.
   *
   * <p>Because the job's handler runs again after the wait, code outside of steps must be cheap and
   * deterministic, and steps and waits must be called with the same names in the same order on
   * every run. Waiting does not use up an attempt. Do not catch the {@link Error} {@code receive}
   * throws to suspend the job; if the handler does anyway, the job is still suspended once the
   * handler returns ({@link #cancel() cancelling} or {@link #fail() failing} the attempt takes
   * precedence).
   *
   * @param stepName the step name, unique within the job, at most 200 characters
   * @param eventName the name of the event, e.g. {@code payment-received}, at most 200 characters
   * @param eventKey identifies the event instance waited for, e.g. an order id, at most 500
   *     characters
   * @return the wait, to set an optional timeout and receive the event
   * @throws IllegalArgumentException if the step or event name is blank
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  EventWait waitForEvent(String stepName, String eventName, String eventKey);

  /**
   * Waits for an event of an {@link EventType}, like {@link #waitForEvent(String, String, String)},
   * receiving its payload mapped to the type's payload class:
   *
   * <pre>{@code
   * static final EventType<Payment> PAYMENT_RECEIVED = EventType.of("payment-received", Payment.class);
   *
   * Payment payment = job.waitForEvent("await-payment", PAYMENT_RECEIVED, order.id())
   *         .timeout(Duration.ofDays(3), () -> null)   // optional
   *         .receive();
   * }</pre>
   *
   * <p>Without a timeout, {@link #awaitEvent(String, EventType, String)} is shorter.
   *
   * @param stepName the step name, unique within the job, at most 200 characters
   * @param eventType the event's name and payload class
   * @param eventKey identifies the event instance waited for, e.g. an order id, at most 500
   *     characters
   * @param <T> the payload type
   * @return the wait, to set an optional timeout and receive the payload
   * @throws IllegalArgumentException if the step name is blank
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  <T> TypedEventWait<T> waitForEvent(String stepName, EventType<T> eventType, String eventKey);

  /**
   * Waits for an event of an {@link EventType} and returns its payload, mapped to the type's
   * payload class; short for {@code waitForEvent(stepName, eventType, eventKey).receive()}:
   *
   * <pre>{@code
   * Payment payment = job.awaitEvent("await-payment", PAYMENT_RECEIVED, order.id());
   * }</pre>
   *
   * <p>The wait is a durable step like with {@link #waitForEvent(String, String, String)}: if the
   * event has not been published yet, the job's attempt ends here (the method does not return) and
   * the job is suspended until it is; later runs of the handler return the payload stored with the
   * step. The node's {@link BikeschedBuilder#defaultEventTimeout(Duration) default event timeout}
   * applies, if any; for a timeout of its own with a fallback result, use {@link
   * #waitForEvent(String, EventType, String)}.
   *
   * @param stepName the step name, unique within the job, at most 200 characters
   * @param eventType the event's name and payload class
   * @param eventKey identifies the event instance waited for, e.g. an order id, at most 500
   *     characters
   * @param <T> the payload type
   * @return the payload, {@code null} if the event has none
   * @throws org.bikesched.event.EventTimeoutException if the wait exceeded the node's default event
   *     timeout; the job fails
   * @throws IllegalStateException if the step name is used for another step of the job, or if
   *     called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   * @throws IllegalArgumentException if the step name is blank
   */
  <T> T awaitEvent(String stepName, EventType<T> eventType, String eventKey);

  /**
   * Sleeps for the given duration, as a durable step. On the first run the step stores its wake-up
   * time ({@code duration} from now), the job's attempt ends here (the method does not return) and
   * the job is suspended in state {@code WAITING}, using no thread and no concurrency slot. Once
   * the wake-up time has passed, the job runs again from the start: completed steps are skipped,
   * and {@code sleep} now returns.
   *
   * <pre>{@code
   * bikesched.registerJobHandler("trial", job -> {
   *     Account account = job.payload(Account.class);
   *     job.step("send-welcome", Void.class, step -> { mail.welcome(account); return null; });
   *     job.sleep("trial-period", Duration.ofDays(14));
   *     job.step("send-trial-ended", Void.class, step -> { mail.trialEnded(account); return null; });
   * });
   * }</pre>
   *
   * <p>The wake-up time stored on the first run wins: later runs ignore the argument, so a changed
   * duration (e.g. after a deploy) does not move a sleep that already started. The node that
   * suspended the job wakes up at the wake-up time, other nodes find it with every {@link
   * BikeschedBuilder#pollInterval(Duration) interval poll}. Sleeping does not use up an attempt;
   * like waiting for an event, it reruns the code outside of steps, and the same rules apply (see
   * {@link #waitForEvent(String, String, String)}).
   *
   * @param stepName the step name, unique within the job, at most 200 characters
   * @param duration how long to sleep, positive
   * @throws IllegalArgumentException if the step name is blank or the duration is not positive
   * @throws IllegalStateException if the step name is used for another step of the job, or if
   *     called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   */
  void sleep(String stepName, Duration duration);

  /**
   * Sleeps until the given time, as a durable step, like {@link #sleep(String, Duration)}. Returns
   * right away if the time has passed (the step is stored as completed).
   *
   * <pre>{@code
   * job.sleepUntil("until-check-in", booking.checkIn().minus(Duration.ofDays(1)));
   * }</pre>
   *
   * <p>The wake-up time stored on the first run wins: later runs ignore the argument, so a time
   * computed from {@link Instant#now()} outside of a step does not move the sleep.
   *
   * @param stepName the step name, unique within the job, at most 200 characters
   * @param time when to wake up
   * @throws IllegalArgumentException if the step name is blank
   * @throws IllegalStateException if the step name is used for another step of the job, or if
   *     called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   */
  void sleepUntil(String stepName, Instant time);

  /**
   * Builds a poll: a durable step that checks, e.g. an external service, until a result is ready.
   * Each run of the handler checks once; if the result is not ready, the job's attempt ends at the
   * poll (it does not return) and the job is suspended in state {@code WAITING} until the next
   * check, using no thread and no concurrency slot, like {@link #sleep(String, Duration)}. Once a
   * check is ready, the poll returns the value, which is stored and returned on later runs without
   * checking again.
   *
   * <pre>{@code
   * Report report = job.newPoll("await-report", Report.class, Duration.ofSeconds(30))
   *     .timeout(Duration.ofHours(2))   // optional
   *     .run(step -> switch (reports.status(reportId)) {
   *       case Done d -> Poll.ready(d.report());
   *       case Pending _ -> Poll.notReady();
   *     });
   * }</pre>
   *
   * <p>A poll is one step for all its checks: checks that are not ready do not use up attempts, and
   * the step's {@link Step#attempts()} counts the checks. A check that throws fails the job's
   * attempt; the next attempt checks again right away. A due check runs when it is due, like a
   * sleep ends (see {@link #sleep(String, Duration)}). Like waiting for an event, polling reruns
   * the code outside of steps, and the same rules apply (see {@link #waitForEvent(String, String,
   * String)}).
   *
   * @param name the step name, unique within the job, at most 200 characters
   * @param resultType the type of the ready value, used to read the stored value back
   * @param interval the time between checks, positive; {@link Poll#notReady(Duration)} overrides it
   *     for one check
   * @param <T> the type of the ready value
   * @return the poll builder
   * @throws IllegalArgumentException if the name is blank or the interval is not positive
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  <T> PollBuilder<T> newPoll(String name, Class<T> resultType, Duration interval);

  /**
   * Builds a poll whose interval grows, like {@link #newPoll(String, Class, Duration)}:
   *
   * <pre>{@code
   * job.newPoll("await-report", Report.class,
   *         RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(5)))
   *     .run(step -> …);
   * }</pre>
   *
   * <p>After each check that is not ready, the interval is called with the number of that check (1,
   * 2, …, also counting checks that threw), and the next check runs after the returned delay. It is
   * not called for a ready check, nor for a check that threw (that is a failed attempt, delayed by
   * the node's {@link BikeschedBuilder#retryBackoff(RetryBackoff) retry backoff}). A delay of zero
   * checks again on the next poll of a node; an interval that throws or returns {@code null} or a
   * negative delay is logged, and the next check runs right away.
   *
   * @param name the step name, unique within the job, at most 200 characters
   * @param resultType the type of the ready value, used to read the stored value back
   * @param interval the time between checks, by the number of the check that was not ready
   * @param <T> the type of the ready value
   * @return the poll builder
   * @throws IllegalArgumentException if the name is blank
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  <T> PollBuilder<T> newPoll(String name, Class<T> resultType, RetryBackoff interval);

  /**
   * Starts building a child job, started exactly once as a durable step of this job: the first run
   * stores the child and the step in one transaction, later runs return the same child's id without
   * scheduling again. Wait for the child to end with {@link #waitForJob(UUID, Class)}:
   *
   * <pre>{@code
   * bikesched.registerJobHandler("fulfil-order", job -> {
   *     Order order = job.payload(Order.class);
   *     UUID charge = job.newJob("charge").payload(order.payment()).stepName("charge").schedule();
   *     UUID pack = job.newJob("pack").payload(order.items()).stepName("pack").schedule();
   *     // both children run in parallel; the parent waits for each without a thread
   *     Receipt receipt = job.waitForJob(charge, Receipt.class).orElseThrow();
   *     Parcel parcel = job.waitForJob(pack, Parcel.class).orElseThrow();
   *     job.complete(new Fulfilment(receipt, parcel));
   * });
   * }</pre>
   *
   * <p>The step's name defaults to {@code start <type>}. A parent may also end without waiting for
   * its children. Cancelling the parent does not touch its children; give the step a {@link
   * StepJobBuilder#compensateWith(CompensationHandler) compensation} to cancel them. There is no
   * limit on the nesting depth or the number of children: a runaway job tree is a handler bug.
   *
   * @param type the child's job type
   * @return a builder for the child
   * @throws IllegalArgumentException if the type is blank
   * @throws IllegalStateException if this job is an occurrence of a recurring job, or if called
   *     while a step handler or compensation of the job runs
   */
  StepJobBuilder<Object> newJob(String type);

  /**
   * Starts building a child job of a {@link JobType}, like {@link #newJob(String)}, with its
   * payload of the type's payload class.
   *
   * @param type the child's job type and payload class
   * @param <T> the payload type
   * @return a builder for the child
   * @throws IllegalArgumentException if the type's name is already used with another payload class
   * @throws IllegalStateException if this job is an occurrence of a recurring job, or if called
   *     while a step handler or compensation of the job runs
   */
  <T> StepJobBuilder<T> newJob(JobType<T> type);

  /**
   * Waits for a child job started with {@link #newJob(String)} to end and returns its outcome, with
   * its result (see {@link #complete(Object)}) mapped to {@code resultType}. If the child has not
   * ended yet, the job's attempt ends here (the method does not return) and the job is suspended in
   * state {@code WAITING}, using no thread and no concurrency slot, until the child ends; then its
   * handler runs again from the start, and this method returns the outcome stored with the child's
   * step.
   *
   * <pre>{@code
   * UUID charge = job.newJob("charge").payload(payment).schedule();
   * switch (job.waitForJob(charge, Receipt.class)) {
   *     case JobResult.Succeeded<Receipt>(var id, Receipt receipt) -> ship(receipt);
   *     case JobResult.Failed<Receipt> f -> job.cancel("payment failed: " + f.error());
   *     default -> job.fail("payment not charged");
   * }
   * }</pre>
   *
   * <p>A failed child does not fail its parent: the parent decides ({@link JobResult#orElseThrow()}
   * turns any other outcome into an exception). To wait for several children, start them all, then
   * wait for one after the other: children that ended already return right away. Waiting does not
   * use up an attempt; like waiting for an event, it reruns the code outside of steps, and the same
   * rules apply (see {@link #waitForEvent(String, String, String)}). The child's outcome stays with
   * the step after the child was deleted (job retention).
   *
   * @param childJobId the id {@link StepJobBuilder#schedule()} returned
   * @param resultType the type of the child's result
   * @param <T> the type of the child's result
   * @return the child's outcome; never {@link JobResult.TimedOut}
   * @throws IllegalArgumentException if the id is not one of this job's children
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   */
  <T> JobResult<T> waitForJob(UUID childJobId, Class<T> resultType);

  /**
   * Waits for a child job like {@link #waitForJob(UUID, Class)}, at most for {@code timeout}
   * counted from the first wait: the deadline stored on the first wait wins, later runs ignore the
   * argument. Once it has passed with the child still running, the wait returns {@link
   * JobResult.TimedOut}, also on every later run; the child keeps running (cancel it with {@code
   * Bikesched.cancel} if needed) and its end no longer changes the outcome.
   *
   * @param childJobId the id {@link StepJobBuilder#schedule()} returned
   * @param resultType the type of the child's result
   * @param timeout how long to wait at most, positive
   * @param <T> the type of the child's result
   * @return the child's outcome, or {@link JobResult.TimedOut}
   * @throws IllegalArgumentException if the id is not one of this job's children or the timeout is
   *     not positive
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   */
  <T> JobResult<T> waitForJob(UUID childJobId, Class<T> resultType, Duration timeout);

  /**
   * Sets the job's result, mapped to JSON by the configured {@link JsonMapper} right away. Once the
   * handler returns and the job ends {@code SUCCEEDED}, the result is stored with it: a parent
   * waiting for the job receives it ({@link #waitForJob(UUID, Class)}), and {@link
   * JobInfo#result(Class)} returns it.
   *
   * <pre>{@code
   * Receipt receipt = job.step("charge", Receipt.class, step -> payments.charge(order));
   * job.complete(receipt);
   * }</pre>
   *
   * <p>Like {@link #cancel()} and {@link #fail()} it is a flag checked after the handler returned:
   * if called repeatedly, the last result wins. A cancelled or failed attempt (also by an exception
   * thrown afterwards) discards it, the next attempt sets it again; a job ending after its
   * compensations has no result.
   *
   * @param result the result, may be {@code null}
   * @throws RuntimeException any exception of the mapper
   */
  void complete(Object result);

  /**
   * Marks this job as cancelled. Once the handler returns, bikesched sets the job to {@code
   * CANCELLED} (it is not retried) and clears {@code last_error}; if completed steps have a {@link
   * StepBuilder#compensateWith(CompensationHandler) compensation}, the job is compensated first.
   * The handler keeps running until it returns; a cancelled job stays cancelled even if the handler
   * then throws or its attempt times out. Ignored while {@link #isCompensating() compensating}.
   */
  default void cancel() {
    cancel(null);
  }

  /**
   * Marks this job as cancelled, storing the message as the job's {@code last_error}. If called
   * repeatedly, the last message wins.
   *
   * @param message why the job was cancelled, may be {@code null}
   * @see #cancel()
   */
  void cancel(String message);

  /**
   * Marks the current attempt as failed, like throwing from the handler but without an exception
   * and with control over the retry. Once the handler returns, the job is retried while attempts
   * remain (by default as soon as possible), otherwise it is marked {@code FAILED}. Like {@link
   * #cancel()}, it does not stop the handler, so return right after it.
   *
   * @return the failure, to configure the retry
   * @see #fail(String)
   */
  default JobFailure fail() {
    return fail(null);
  }

  /**
   * Marks the current attempt as failed with a message, stored as the job's {@code last_error}. The
   * returned failure sets how many more attempts the job gets and when the next one runs:
   *
   * <pre>{@code
   * if (provider.isBusy()) {
   *     job.fail("provider busy").remainingAttempts(5).retryAfter(Duration.ofMinutes(1));
   *     return;
   * }
   * }</pre>
   *
   * <p>An explicit failure wins over an exception thrown by the handler afterwards and over a
   * timeout; {@link #cancel(String) cancelling} the job wins over failing it. If called repeatedly,
   * the last call wins.
   *
   * @param message why the attempt failed, may be {@code null}
   * @return the failure, to configure the retry
   */
  JobFailure fail(String message);

  /**
   * Sets a new timeout for the running attempt, counted from now, replacing the current deadline
   * (or starting one if the attempt had none). Useful when the work turns out to take longer than
   * expected, e.g. for a large video to encode or while polling an external system for a result:
   *
   * <pre>{@code
   * while (!encoder.isDone(task)) {
   *     job.updateTimeout(Duration.ofMinutes(2)); // still making progress: two more minutes
   *     Thread.sleep(Duration.ofSeconds(10));
   * }
   * }</pre>
   *
   * <p>Applies to the current attempt only; a retry starts with the job's original timeout (see
   * {@link JobBuilder#timeout(Duration)}). May be called from other threads than the handler's.
   *
   * @param timeout the new timeout from now, positive
   * @throws IllegalStateException if the attempt is not running (anymore) or already timed out
   */
  void updateTimeout(Duration timeout);
}
