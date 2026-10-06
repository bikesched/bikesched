// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import org.bikesched.Bikesched;
import org.bikesched.job.Job;
import org.bikesched.job.JobType;
import org.bikesched.job.Recurrence;

/**
 * A job handler found on a {@link JobHandler @JobHandler} method, see {@link
 * JobHandlers#scan(Class)}; {@link #bindTo(Object)} binds it to an instance and {@link
 * #registerOn(Bikesched)} registers it.
 *
 * <p>Binding resolves the method on the target's own class first, so the handler of a proxied
 * object (e.g. a Spring AOP proxy) is invoked through the proxy and its advice applies.
 */
public final class AnnotatedJobHandler {

  private final String type;
  private final Class<?> payloadType;
  private final Recurrence recurrence;
  private final Method method;
  private final int parameterCount;
  private final int jobIndex;
  private final int payloadIndex;
  private final Object target; // null while unbound
  private final MethodHandle
      invoker; // bound to target and adapted to (Job, Object); null while unbound

  AnnotatedJobHandler(
      String type,
      Class<?> payloadType,
      Recurrence recurrence,
      Method method,
      int parameterCount,
      int jobIndex,
      int payloadIndex,
      Object target,
      MethodHandle invoker) {
    this.type = Objects.requireNonNull(type, "type");
    this.payloadType = payloadType;
    this.recurrence = recurrence;
    this.method = Objects.requireNonNull(method, "method");
    this.parameterCount = parameterCount;
    this.jobIndex = jobIndex;
    this.payloadIndex = payloadIndex;
    this.target = target;
    this.invoker = invoker;
  }

  /**
   * The job type the method handles.
   *
   * @return the job type
   */
  public String type() {
    return type;
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
   * The recurring schedule declared by the annotation.
   *
   * @return the recurrence, empty for a one-off handler
   */
  public Optional<Recurrence> recurrence() {
    return Optional.ofNullable(recurrence);
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
        type,
        payloadType,
        recurrence,
        method,
        parameterCount,
        jobIndex,
        payloadIndex,
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
   * JobType.of(type, payloadType)}, so the node rejects the type with another payload class;
   * otherwise as the handler of the job type name.
   *
   * @param bikesched the node
   * @throws IllegalStateException if the handler is not bound, see {@link #bindTo(Object)}
   * @throws IllegalArgumentException if the node already has a handler for the type
   */
  public void registerOn(Bikesched bikesched) {
    Objects.requireNonNull(bikesched, "bikesched");
    if (invoker == null) {
      throw new IllegalStateException(
          "Job handler " + this + " is not bound to a target; call bindTo(Object) first");
    }
    if (payloadType == null) {
      bikesched.registerJobHandler(type, job -> invoke(job, null));
    } else {
      registerTyped(bikesched, JobType.of(type, payloadType));
    }
    if (recurrence != null) {
      if (payloadType == null) {
        bikesched.newJob(type).schedule(recurrence);
      } else {
        scheduleTyped(bikesched, JobType.of(type, payloadType));
      }
    }
  }

  /**
   * Registers this handler on the node like {@link #registerOn(Bikesched)}, naming {@code source}
   * (e.g. the bean declaring the method) when the node already has a handler for the type.
   *
   * @param bikesched the node
   * @param source what declares the method, for the error message, e.g. {@code "bean mailJobs"}
   * @throws IllegalStateException if the handler is not bound, see {@link #bindTo(Object)}
   * @throws IllegalArgumentException if the node already has a handler for the type
   */
  public void registerOn(Bikesched bikesched, String source) {
    Objects.requireNonNull(source, "source");
    try {
      registerOn(bikesched);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(e.getMessage() + " (declared by " + source + ")", e);
    }
  }

  private <T> void registerTyped(Bikesched bikesched, JobType<T> jobType) {
    bikesched.registerJobHandler(jobType, this::invoke);
  }

  private <T> void scheduleTyped(Bikesched bikesched, JobType<T> jobType) {
    bikesched.newJob(jobType).schedule(recurrence);
  }

  /** Calls the bound method; its exceptions are passed on unchanged. */
  private void invoke(Job job, Object payload) throws Exception {
    try {
      invoker.invoke(job, payload);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("Job handler method " + method + " failed", e);
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
    return "AnnotatedJobHandler[type=" + type + ", method=" + method + ", bound=" + isBound() + "]";
  }
}
