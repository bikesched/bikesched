// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import org.bikesched.Bikesched;
import org.bikesched.job.Job;
import org.bikesched.job.JobType;

/**
 * A job handler found on a {@link JobHandler @JobHandler} method, see {@link
 * JobHandlers#scan(Object)}; {@link #registerOn(Bikesched)} registers it.
 */
public final class AnnotatedJobHandler {

  private final String type;
  private final Class<?> payloadType;
  private final Method method;
  private final MethodHandle invoker;

  /**
   * @param invoker the method bound to its object, taking {@code (Job, Object payload)}
   */
  AnnotatedJobHandler(String type, Class<?> payloadType, Method method, MethodHandle invoker) {
    this.type = Objects.requireNonNull(type, "type");
    this.payloadType = payloadType;
    this.method = Objects.requireNonNull(method, "method");
    this.invoker = Objects.requireNonNull(invoker, "invoker");
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
   * The annotated method.
   *
   * @return the method
   */
  public Method method() {
    return method;
  }

  /**
   * Registers this handler on the node: with a payload parameter as the handler of {@code
   * JobType.of(type, payloadType)}, so the node rejects the type with another payload class;
   * otherwise as the handler of the job type name.
   *
   * @param bikesched the node
   * @throws IllegalArgumentException if the node already has a handler for the type
   */
  public void registerOn(Bikesched bikesched) {
    Objects.requireNonNull(bikesched, "bikesched");
    if (payloadType == null) {
      bikesched.registerJobHandler(type, job -> invoke(job, null));
    } else {
      registerTyped(bikesched, JobType.of(type, payloadType));
    }
  }

  private <T> void registerTyped(Bikesched bikesched, JobType<T> jobType) {
    bikesched.registerJobHandler(jobType, this::invoke);
  }

  /** Calls the method; its exceptions are passed on unchanged. */
  private void invoke(Job job, Object payload) throws Exception {
    try {
      invoker.invoke(job, payload);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("Job handler method " + method + " failed", e);
    }
  }

  @Override
  public String toString() {
    return "AnnotatedJobHandler[type=" + type + ", method=" + method + "]";
  }
}
