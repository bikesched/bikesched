// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bikesched.Bikesched;
import org.bikesched.job.FailedJob;
import org.bikesched.job.Job;

/**
 * Finds the {@link JobHandler @JobHandler} and {@link JobFailureHandler @JobFailureHandler} methods
 * of an object — or of a class, for objects whose runtime class is a proxy — and registers them as
 * job handlers and failure handlers:
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
   * Registers the {@link JobHandler @JobHandler} and {@link JobFailureHandler @JobFailureHandler}
   * methods of the object on the node.
   *
   * @param bikesched the node
   * @param target the object declaring the methods
   * @return the registered handlers: the job handlers, then the failure handlers, each ordered by
   *     job name
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler} and
   *     {@link JobFailureHandler}), two methods handle the same name, the node already has a
   *     handler (or failure handler) for one of the names, or a schedule is not supported by
   *     exactly one of the node's {@code RecurrenceParser}s
   */
  public static List<AnnotatedJobHandler> register(Bikesched bikesched, Object target) {
    Objects.requireNonNull(bikesched, "bikesched");
    List<AnnotatedJobHandler> handlers = scan(target);
    handlers.forEach(handler -> handler.registerOn(bikesched));
    return handlers;
  }

  /**
   * Registers the {@link JobHandler @JobHandler} and {@link JobFailureHandler @JobFailureHandler}
   * methods of {@code targetClass}, invoked through {@code bean} — the same object for a plain
   * bean, the proxy for a proxied one (so the advice applies).
   *
   * @param bikesched the node
   * @param bean the instance the methods are invoked on
   * @param targetClass the class declaring the annotated methods, e.g. the target class of a proxy
   * @return the registered handlers: the job handlers, then the failure handlers, each ordered by
   *     job name
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler} and
   *     {@link JobFailureHandler}), two methods handle the same name, the node already has a
   *     handler (or failure handler) for one of the names, or a schedule is not supported by
   *     exactly one of the node's {@code RecurrenceParser}s
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
   * Finds the {@link JobHandler @JobHandler} and {@link JobFailureHandler @JobFailureHandler}
   * methods of the object (declared by its class or a superclass) without registering them.
   *
   * @param target the object declaring the methods
   * @return its job handlers, then its failure handlers, each ordered by job name; empty if it has
   *     no annotated methods
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler} and
   *     {@link JobFailureHandler}) or two methods handle the same name
   */
  public static List<AnnotatedJobHandler> scan(Object target) {
    Objects.requireNonNull(target, "target");
    return scan(target.getClass()).stream().map(handler -> handler.bindTo(target)).toList();
  }

  /**
   * Finds the {@link JobHandler @JobHandler} and {@link JobFailureHandler @JobFailureHandler}
   * methods of a class (or of a superclass) without registering or binding them; bind them with
   * {@link AnnotatedJobHandler#bindTo(Object)}. Use this for an object whose runtime class is a
   * proxy: pass the class that declares the annotations, not the proxy class.
   *
   * @param targetClass the class declaring the methods
   * @return its job handlers, then its failure handlers, each ordered by job name; empty if it has
   *     no annotated methods
   * @throws IllegalArgumentException if an annotated method is invalid (see {@link JobHandler} and
   *     {@link JobFailureHandler}) or two methods handle the same name
   */
  public static List<AnnotatedJobHandler> scan(Class<?> targetClass) {
    Objects.requireNonNull(targetClass, "targetClass");
    Map<String, AnnotatedJobHandler> jobHandlers = new HashMap<>();
    Map<String, AnnotatedJobHandler> failureHandlers = new HashMap<>();
    for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        if (method.isBridge()) {
          continue;
        }
        JobHandler annotation = method.getAnnotation(JobHandler.class);
        JobFailureHandler failureAnnotation = method.getAnnotation(JobFailureHandler.class);
        if (annotation != null && failureAnnotation != null) {
          throw new IllegalArgumentException(
              "Method "
                  + method
                  + " is annotated with both @JobHandler and @JobFailureHandler; use two methods");
        }
        if (annotation != null) {
          add(
              jobHandlers,
              handler(
                  method,
                  annotation.name(),
                  annotation.schedule(),
                  annotation.fixedDelay(),
                  annotation.initialDelay(),
                  false),
              "Job name '%s' is handled by two methods: %s and %s");
        } else if (failureAnnotation != null) {
          add(
              failureHandlers,
              handler(method, failureAnnotation.name(), "", "", "", true),
              "Failed jobs named '%s' are handled by two methods: %s and %s");
        }
      }
    }
    var handlers = new ArrayList<AnnotatedJobHandler>();
    handlers.addAll(byName(jobHandlers));
    handlers.addAll(byName(failureHandlers));
    return List.copyOf(handlers);
  }

  /**
   * Adds a handler found while walking up the class hierarchy, unless a subclass method found
   * earlier overrides it; two methods for the same name fail with the formatted {@code duplicate}
   * message (name, first method, second method).
   */
  private static void add(
      Map<String, AnnotatedJobHandler> handlers, AnnotatedJobHandler handler, String duplicate) {
    AnnotatedJobHandler other = handlers.putIfAbsent(handler.name(), handler);
    if (other != null && !overrides(other.method(), handler.method())) {
      throw new IllegalArgumentException(
          duplicate.formatted(handler.name(), other.method(), handler.method()));
    }
  }

  private static List<AnnotatedJobHandler> byName(Map<String, AnnotatedJobHandler> handlers) {
    return handlers.values().stream()
        .sorted(Comparator.comparing(AnnotatedJobHandler::name))
        .toList();
  }

  /**
   * Whether the class (or a superclass) declares {@link JobHandler @JobHandler} or {@link
   * JobFailureHandler @JobFailureHandler} methods; a cheap check for scanning many classes, without
   * validating the methods.
   *
   * @param targetClass the class to check
   * @return whether it has annotated methods
   */
  public static boolean hasAnnotatedMethods(Class<?> targetClass) {
    Objects.requireNonNull(targetClass, "targetClass");
    for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        if (!method.isBridge()
            && (method.isAnnotationPresent(JobHandler.class)
                || method.isAnnotationPresent(JobFailureHandler.class))) {
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
  private static AnnotatedJobHandler handler(
      Method method,
      String name,
      String schedule,
      String fixedDelay,
      String initialDelay,
      boolean failureHandler) {
    String annotation = failureHandler ? "@JobFailureHandler" : "@JobHandler";
    Class<?> contextType = failureHandler ? FailedJob.class : Job.class;
    if (name == null || name.isBlank()) {
      throw invalid(annotation, method, "has no job name");
    }
    int modifiers = method.getModifiers();
    if (!Modifier.isPublic(modifiers)) {
      throw invalid(annotation, method, "is not public");
    }
    if (Modifier.isStatic(modifiers)) {
      throw invalid(annotation, method, "is static");
    }
    if (method.getReturnType() != void.class) {
      throw invalid(annotation, method, "does not return void");
    }
    Class<?>[] parameters = method.getParameterTypes();
    Type[] genericParameters = method.getGenericParameterTypes();
    if (parameters.length > 2) {
      throw invalid(
          annotation, method, "has more than two parameters (at most the Job and the payload)");
    }
    int jobIndex = -1;
    int payloadIndex = -1;
    for (int i = 0; i < parameters.length; i++) {
      if (parameters[i] == contextType) {
        if (jobIndex >= 0) {
          throw invalid(
              annotation, method, "has two " + contextType.getSimpleName() + " parameters");
        }
        jobIndex = i;
      } else if (parameters[i] == Job.class || parameters[i] == FailedJob.class) {
        throw invalid(
            annotation,
            method,
            "has a "
                + parameters[i].getSimpleName()
                + " parameter; it gets the "
                + contextType.getSimpleName());
      } else {
        if (payloadIndex >= 0) {
          throw invalid(
              annotation, method, "has two payload parameters (at most the Job and the payload)");
        }
        if (parameters[i].isPrimitive()) {
          throw invalid(
              annotation,
              method,
              "has a primitive payload parameter; use an object, e.g. a record");
        }
        if (!(genericParameters[i] instanceof Class<?>)) {
          throw invalid(
              annotation,
              method,
              "has a generic payload parameter ("
                  + genericParameters[i].getTypeName()
                  + "); use a non-generic class, e.g. a record");
        }
        payloadIndex = i;
      }
    }
    if (schedule == null) {
      throw invalid(annotation, method, "has no schedule expression");
    }
    Duration delay = duration(annotation, method, "fixedDelay", fixedDelay);
    Duration firstDelay = duration(annotation, method, "initialDelay", initialDelay);
    if (delay != null && !schedule.isBlank()) {
      throw invalid(annotation, method, "has both a schedule and a fixedDelay; use one of them");
    }
    if (delay != null && (delay.isZero() || delay.isNegative())) {
      throw invalid(annotation, method, "has a fixedDelay that is not positive: " + delay);
    }
    if (firstDelay != null && firstDelay.isNegative()) {
      throw invalid(annotation, method, "has a negative initialDelay: " + firstDelay);
    }
    if (firstDelay != null && delay == null && schedule.isBlank()) {
      throw invalid(annotation, method, "has an initialDelay but no schedule or fixedDelay");
    }
    return new AnnotatedJobHandler(
        name,
        payloadIndex < 0 ? null : parameters[payloadIndex],
        schedule.isBlank() ? null : schedule,
        delay,
        firstDelay,
        method,
        parameters.length,
        jobIndex,
        payloadIndex,
        failureHandler,
        null,
        null);
  }

  /** Parses an optional ISO-8601 duration attribute; {@code null} if it is empty. */
  private static Duration duration(
      String annotation, Method method, String attribute, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Duration.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          annotation
              + " method "
              + method
              + " has an invalid "
              + attribute
              + " '"
              + value
              + "'; use an ISO-8601 duration, e.g. PT15M",
          e);
    }
  }

  private static IllegalArgumentException invalid(
      String annotation, Method method, String problem) {
    return new IllegalArgumentException(annotation + " method " + method + " " + problem);
  }
}
