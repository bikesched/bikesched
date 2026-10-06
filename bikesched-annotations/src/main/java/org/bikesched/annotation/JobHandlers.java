package org.bikesched.annotation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bikesched.Bikesched;
import org.bikesched.job.Job;

/**
 * Finds the {@link JobHandler @JobHandler} methods of an object and registers them as job handlers:
 *
 * <pre>{@code
 * JobHandlers.register(bikesched, new MailJobs(mailServer));
 * }</pre>
 *
 * <p>All annotated methods are checked when scanning, so a wrong signature fails right away, not
 * when a job runs.
 */
public final class JobHandlers {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

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
    Map<String, AnnotatedJobHandler> byType = new HashMap<>();
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        JobHandler annotation = method.getAnnotation(JobHandler.class);
        if (annotation == null || method.isBridge()) {
          continue;
        }
        AnnotatedJobHandler handler = handler(target, method, annotation.type());
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

  /** Whether {@code sub} (found first, in a subclass) overrides {@code sup}. */
  private static boolean overrides(Method sub, Method sup) {
    return sub.getName().equals(sup.getName())
        && java.util.Arrays.equals(sub.getParameterTypes(), sup.getParameterTypes());
  }

  private static AnnotatedJobHandler handler(Object target, Method method, String type) {
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
    if (!method.trySetAccessible()) {
      throw invalid(
          method,
          "is not accessible; open its package to module org.bikesched.annotation"
              + " (or make the class public in an exported package)");
    }
    MethodHandle invoker;
    try {
      invoker = LOOKUP.unreflect(method).bindTo(target);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException("Job handler method " + method + " is not accessible", e);
    }
    return new AnnotatedJobHandler(
        type,
        payloadIndex < 0 ? null : parameters[payloadIndex],
        method,
        adapt(invoker, parameters.length, jobIndex, payloadIndex));
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

  private static IllegalArgumentException invalid(Method method, String problem) {
    return new IllegalArgumentException("@JobHandler method " + method + " " + problem);
  }
}
