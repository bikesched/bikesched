// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.bikesched.Bikesched;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TypedJob;

/**
 * A job handler found on a {@link JobHandler @JobHandler} method, or a failure handler found on a
 * {@link JobFailureHandler @JobFailureHandler} method (see {@link #isFailureHandler()}), see {@link
 * JobHandlers#scan(Class)}; {@link #bindTo(Object)} binds it to an instance and {@link
 * #registerOn(Bikesched)} registers it.
 *
 * <p>Binding resolves the method on the target's own class first, so the handler of a proxied
 * object (e.g. a Spring AOP proxy) is invoked through the proxy and its advice applies.
 */
public final class AnnotatedJobHandler {

  private final String name;
  private final Class<?> payloadType;
  private final String schedule; // null for a one-off handler or a fixed delay
  private final Duration fixedDelay; // null unless a fixed delay
  private final Duration initialDelay; // null for the recurrence's next run from now
  private final Method method;
  private final int parameterCount;
  private final int jobIndex;
  private final int payloadIndex;
  private final boolean failureHandler;
  private final Object target; // null while unbound
  private final MethodHandle
      invoker; // bound to target and adapted to (Job or FailedJob, Object); null while unbound

  AnnotatedJobHandler(
      String name,
      Class<?> payloadType,
      String schedule,
      Duration fixedDelay,
      Duration initialDelay,
      Method method,
      int parameterCount,
      int jobIndex,
      int payloadIndex,
      boolean failureHandler,
      Object target,
      MethodHandle invoker) {
    this.name = Objects.requireNonNull(name, "name");
    this.payloadType = payloadType;
    this.schedule = schedule;
    this.fixedDelay = fixedDelay;
    this.initialDelay = initialDelay;
    this.method = Objects.requireNonNull(method, "method");
    this.parameterCount = parameterCount;
    this.jobIndex = jobIndex;
    this.payloadIndex = payloadIndex;
    this.failureHandler = failureHandler;
    this.target = target;
    this.invoker = invoker;
  }

  /**
   * Whether the method is a failure handler ({@link JobFailureHandler @JobFailureHandler}) rather
   * than a job handler ({@link JobHandler @JobHandler}).
   *
   * @return {@code true} for a failure handler
   */
  public boolean isFailureHandler() {
    return failureHandler;
  }

  /**
   * The job name the method handles.
   *
   * @return the job name
   */
  public String name() {
    return name;
  }

  /**
   * The class of the method's payload parameter.
   *
   * @return the payload class, empty if the method has no payload parameter
   */
  public Optional<Class<?>> payloadType() {
    return Optional.ofNullable(payloadType);
  }

  /**
   * The recurrence expression declared by the annotation, parsed by the node's {@code
   * RecurrenceParser}s when the handler is registered.
   *
   * @return the expression, empty for a one-off handler
   */
  public Optional<String> schedule() {
    return Optional.ofNullable(schedule);
  }

  /**
   * The fixed delay declared by the annotation: the recurring job runs this long after each
   * occurrence ended ({@link Recurrence#everyAfterEnd(Duration)}).
   *
   * @return the delay, empty unless the annotation declares one
   */
  public Optional<Duration> fixedDelay() {
    return Optional.ofNullable(fixedDelay);
  }

  /**
   * The delay of a new first occurrence declared by the annotation, see {@link
   * JobHandler#initialDelay()}.
   *
   * @return the delay, empty for the recurrence's next run from now
   */
  public Optional<Duration> initialDelay() {
    return Optional.ofNullable(initialDelay);
  }

  /**
   * The annotated method.
   *
   * @return the method
   */
  public Method method() {
    return method;
  }

  /**
   * Whether this handler is bound to an instance.
   *
   * @return whether {@link #bindTo(Object)} was called
   */
  public boolean isBound() {
    return target != null;
  }

  /**
   * Binds this handler to the instance the methods are invoked on — the bean itself for a proxied
   * bean, so its advice (e.g. {@code @Transactional}) applies. The method is resolved on the
   * instance's own class first, falling back to the scanned method.
   *
   * @param target the instance, e.g. the bean that declares the methods
   * @return the bound handler; this one is unchanged
   * @throws IllegalArgumentException if the method is not accessible on the target
   */
  public AnnotatedJobHandler bindTo(Object target) {
    Objects.requireNonNull(target, "target");
    Method invocable = invocableMethod(target.getClass());
    if (!invocable.trySetAccessible()) {
      throw new IllegalArgumentException(
          "Job handler method "
              + invocable
              + " is not accessible; open its package to module org.bikesched.annotation"
              + " (or make the class public in an exported package)");
    }
    MethodHandle bound;
    try {
      bound = MethodHandles.lookup().unreflect(invocable).bindTo(target);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException(
          "Job handler method " + invocable + " is not accessible", e);
    }
    return new AnnotatedJobHandler(
        name,
        payloadType,
        schedule,
        fixedDelay,
        initialDelay,
        method,
        parameterCount,
        jobIndex,
        payloadIndex,
        failureHandler,
        target,
        adapt(bound, parameterCount, jobIndex, payloadIndex));
  }

  /** The method to invoke on the target: its own class's, or the scanned one as a fallback. */
  private Method invocableMethod(Class<?> targetClass) {
    try {
      return targetClass.getMethod(method.getName(), method.getParameterTypes());
    } catch (NoSuchMethodException e) {
      return method;
    }
  }

  /**
   * Registers this handler on the node: with a payload parameter as the handler of {@code
   * TypedJob.of(name, payloadType)}, so the node rejects the name with another payload class;
   * otherwise as the handler of the job name. A failure handler is registered as the name's failure
   * handler the same way. With a schedule, it also registers the recurring job, parsing the
   * schedule with the node's {@code RecurrenceParser}s.
   *
   * @param bikesched the node
   * @throws IllegalStateException if the handler is not bound, see {@link #bindTo(Object)}
   * @throws IllegalArgumentException if the node already has a handler for the name, or the
   *     schedule is not supported by exactly one of the node's recurrence parsers
   */
  public void registerOn(Bikesched bikesched) {
    Objects.requireNonNull(bikesched, "bikesched");
    if (invoker == null) {
      throw new IllegalStateException(
          "Job handler " + this + " is not bound to a target; call bindTo(Object) first");
    }
    if (failureHandler) {
      if (payloadType == null) {
        bikesched.registerJobFailureHandler(name, failed -> invoke(failed, null));
      } else {
        registerTypedFailureHandler(bikesched, TypedJob.of(name, payloadType));
      }
      return;
    }
    if (payloadType == null) {
      bikesched.registerJobHandler(name, job -> invoke(job, null));
    } else {
      registerTyped(bikesched, TypedJob.of(name, payloadType));
    }
    if (schedule != null || fixedDelay != null) {
      scheduleOn(
          payloadType == null
              ? bikesched.newJob(name)
              : bikesched.newJob(TypedJob.of(name, payloadType)));
    }
  }

  /**
   * Registers this handler on the node like {@link #registerOn(Bikesched)}, naming {@code source}
   * (e.g. the bean declaring the method) when the node already has a handler for the name.
   *
   * @param bikesched the node
   * @param source what declares the method, for the error message, e.g. {@code "bean mailJobs"}
   * @throws IllegalStateException if the handler is not bound, see {@link #bindTo(Object)}
   * @throws IllegalArgumentException if the node already has a handler for the name
   */
  public void registerOn(Bikesched bikesched, String source) {
    Objects.requireNonNull(source, "source");
    try {
      registerOn(bikesched);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(e.getMessage() + " (declared by " + source + ")", e);
    }
  }

  private <T> void registerTyped(Bikesched bikesched, TypedJob<T> typedJob) {
    bikesched.registerJobHandler(typedJob, this::invoke);
  }

  private <T> void registerTypedFailureHandler(Bikesched bikesched, TypedJob<T> typedJob) {
    bikesched.registerJobFailureHandler(typedJob, this::invoke);
  }

  /** Registers the recurring job declared by the annotation. */
  private void scheduleOn(JobBuilder<?> builder) {
    if (fixedDelay != null) {
      Recurrence recurrence = Recurrence.everyAfterEnd(fixedDelay);
      if (initialDelay == null) {
        builder.schedule(recurrence);
      } else {
        builder.schedule(recurrence, initialDelay);
      }
    } else if (initialDelay == null) {
      builder.schedule(schedule);
    } else {
      builder.schedule(schedule, initialDelay);
    }
  }

  /**
   * Calls the bound method with the {@code Job} (or {@code FailedJob}) and the payload; its
   * exceptions are passed on unchanged.
   */
  private void invoke(Object job, Object payload) throws Exception {
    try {
      invoker.invoke(job, payload);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("Handler method " + method + " failed", e);
    }
  }

  /** Adapts the bound method to {@code (Job, Object payload)}, dropping what it does not take. */
  private static MethodHandle adapt(
      MethodHandle invoker, int parameterCount, int jobIndex, int payloadIndex) {
    MethodHandle generic =
        invoker.asType(MethodType.genericMethodType(parameterCount).changeReturnType(void.class));
    // Argument positions of (Job, Object) for each of the method's parameters.
    int[] reorder = new int[parameterCount];
    if (jobIndex >= 0) {
      reorder[jobIndex] = 0;
    }
    if (payloadIndex >= 0) {
      reorder[payloadIndex] = 1;
    }
    return MethodHandles.permuteArguments(
        generic, MethodType.methodType(void.class, Object.class, Object.class), reorder);
  }

  @Override
  public String toString() {
    return "AnnotatedJobHandler[name="
        + name
        + (failureHandler ? ", failure handler" : "")
        + ", method="
        + method
        + ", bound="
        + isBound()
        + "]";
  }
}
