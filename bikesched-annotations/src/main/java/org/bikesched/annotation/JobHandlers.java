// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bikesched.Bikesched;
import org.bikesched.job.Job;

/**
 * Finds the {@link JobHandler @JobHandler} methods of an object — or of a class, for objects whose
 * runtime class is a proxy — and registers them as job handlers:
 *
 * <pre>{@code
 * JobHandlers.register(bikesched, new MailJobs(mailServer));          // a plain object
 * JobHandlers.register(bikesched, bean, targetClass);                 // a proxied bean
 * }</pre>
 *
 * <p>All annotated methods are checked when scanning, so a wrong signature fails right away, not
 * when a job runs.
 */
public final class JobHandlers {

  private JobHandlers() {}

  /**
   * Registers the {@link JobHandler @JobHandler} methods of the object on the node.
   *
   * @param bikesched the node
   * @param target the object declaring the methods
   * @return the registered handlers, ordered by job type
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler}),
   *     two methods handle the same type, or the node already has a handler for one of the types
   */
  public static List<AnnotatedJobHandler> register(Bikesched bikesched, Object target) {
    Objects.requireNonNull(bikesched, "bikesched");
    List<AnnotatedJobHandler> handlers = scan(target);
    handlers.forEach(handler -> handler.registerOn(bikesched));
    return handlers;
  }

  /**
   * Registers the {@link JobHandler @JobHandler} methods of {@code targetClass}, invoked through
   * {@code bean} — the same object for a plain bean, the proxy for a proxied one (so the advice
   * applies).
   *
   * @param bikesched the node
   * @param bean the instance the methods are invoked on
   * @param targetClass the class declaring the annotated methods, e.g. the target class of a proxy
   * @return the registered handlers, ordered by job type
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler}),
   *     two methods handle the same type, or the node already has a handler for one of the types
   */
  public static List<AnnotatedJobHandler> register(
      Bikesched bikesched, Object bean, Class<?> targetClass) {
    Objects.requireNonNull(bikesched, "bikesched");
    List<AnnotatedJobHandler> handlers =
        scan(targetClass).stream().map(handler -> handler.bindTo(bean)).toList();
    handlers.forEach(handler -> handler.registerOn(bikesched));
    return handlers;
  }

  /**
   * Finds the {@link JobHandler @JobHandler} methods of the object (declared by its class or a
   * superclass) without registering them.
   *
   * @param target the object declaring the methods
   * @return its handlers, ordered by job type; empty if it has no annotated methods
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler}) or
   *     two methods handle the same type
   */
  public static List<AnnotatedJobHandler> scan(Object target) {
    Objects.requireNonNull(target, "target");
    return scan(target.getClass()).stream().map(handler -> handler.bindTo(target)).toList();
  }

  /**
   * Finds the {@link JobHandler @JobHandler} methods of a class (or of a superclass) without
   * registering or binding them; bind them with {@link AnnotatedJobHandler#bindTo(Object)}. Use
   * this for an object whose runtime class is a proxy: pass the class that declares the
   * annotations, not the proxy class.
   *
   * @param targetClass the class declaring the methods
   * @return its handlers, ordered by job type; empty if it has no annotated methods
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler}) or
   *     two methods handle the same type
   */
  public static List<AnnotatedJobHandler> scan(Class<?> targetClass) {
    Objects.requireNonNull(targetClass, "targetClass");
    Map<String, AnnotatedJobHandler> byType = new HashMap<>();
    for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        JobHandler annotation = method.getAnnotation(JobHandler.class);
        if (annotation == null || method.isBridge()) {
          continue;
        }
        AnnotatedJobHandler handler = handler(method, annotation.type());
        AnnotatedJobHandler other = byType.putIfAbsent(handler.type(), handler);
        if (other != null && !overrides(other.method(), method)) {
          throw new IllegalArgumentException(
              "Job type '"
                  + handler.type()
                  + "' is handled by two methods: "
                  + other.method()
                  + " and "
                  + method);
        }
      }
    }
    var handlers = new ArrayList<>(byType.values());
    handlers.sort(Comparator.comparing(AnnotatedJobHandler::type));
    return List.copyOf(handlers);
  }

  /**
   * Whether the class (or a superclass) declares {@link JobHandler @JobHandler} methods; a cheap
   * check for scanning many classes, without validating the methods.
   *
   * @param targetClass the class to check
   * @return whether it has annotated methods
   */
  public static boolean hasAnnotatedMethods(Class<?> targetClass) {
    Objects.requireNonNull(targetClass, "targetClass");
    for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        if (!method.isBridge() && method.isAnnotationPresent(JobHandler.class)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether {@code sub} (found first, in a subclass) overrides {@code sup}. */
  private static boolean overrides(Method sub, Method sup) {
    return sub.getName().equals(sup.getName())
        && Arrays.equals(sub.getParameterTypes(), sup.getParameterTypes());
  }

  /** Validates an annotated method and describes it; the handler is not bound to an instance. */
  private static AnnotatedJobHandler handler(Method method, String type) {
    if (type == null || type.isBlank()) {
      throw invalid(method, "has no job type");
    }
    int modifiers = method.getModifiers();
    if (!Modifier.isPublic(modifiers)) {
      throw invalid(method, "is not public");
    }
    if (Modifier.isStatic(modifiers)) {
      throw invalid(method, "is static");
    }
    if (method.getReturnType() != void.class) {
      throw invalid(method, "does not return void");
    }
    Class<?>[] parameters = method.getParameterTypes();
    Type[] genericParameters = method.getGenericParameterTypes();
    if (parameters.length > 2) {
      throw invalid(method, "has more than two parameters (at most the Job and the payload)");
    }
    int jobIndex = -1;
    int payloadIndex = -1;
    for (int i = 0; i < parameters.length; i++) {
      if (parameters[i] == Job.class) {
        if (jobIndex >= 0) {
          throw invalid(method, "has two Job parameters");
        }
        jobIndex = i;
      } else {
        if (payloadIndex >= 0) {
          throw invalid(method, "has two payload parameters (at most the Job and the payload)");
        }
        if (parameters[i].isPrimitive()) {
          throw invalid(method, "has a primitive payload parameter; use an object, e.g. a record");
        }
        if (!(genericParameters[i] instanceof Class<?>)) {
          throw invalid(
              method,
              "has a generic payload parameter ("
                  + genericParameters[i].getTypeName()
                  + "); use a non-generic class, e.g. a record");
        }
        payloadIndex = i;
      }
    }
    return new AnnotatedJobHandler(
        type,
        payloadIndex < 0 ? null : parameters[payloadIndex],
        method,
        parameters.length,
        jobIndex,
        payloadIndex,
        null,
        null);
  }

  private static IllegalArgumentException invalid(Method method, String problem) {
    return new IllegalArgumentException("@JobHandler method " + method + " " + problem);
  }
}
