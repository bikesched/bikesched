// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.util.List;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.JsonMapper;
import org.bikesched.annotation.ScheduleProvider;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobTypeHandler;
import org.bikesched.job.listener.JobListener;
import org.bikesched.micrometer.MicrometerJobListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Creates the {@link Bikesched} node of a Spring Boot application from its {@code DataSource} and
 * the {@code bikesched.*} properties, registers the {@code @JobHandler} methods of the context's
 * beans and starts polling with the application.
 *
 * <pre>{@code
 * @Component
 * class InvoiceJobs {
 *   @JobHandler(type = "send-invoice")
 *   public void sendInvoice(Job job, Invoice invoice) { invoiceService.send(invoice); }
 * }
 * }</pre>
 *
 * <p>Every bean is conditional: the node is only created when a {@code DataSource} bean exists, it
 * backs off for an application-defined {@link Bikesched} bean, and the optional integrations
 * (bikesched-jackson, bikesched-micrometer) only take part when they are on the classpath.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(Bikesched.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(BikeschedProperties.class)
public class BikeschedAutoConfiguration {

  /** Creates the auto-configuration; Spring instantiates it. */
  public BikeschedAutoConfiguration() {}

  private static final Logger LOG = LoggerFactory.getLogger(BikeschedAutoConfiguration.class);

  /**
   * The beans with {@code @JobHandler} methods, collected by the post-processor below and
   * registered by {@link #bikesched}. Static, so it exists before the regular beans — the
   * post-processor and the node's factory method must see the same instance.
   *
   * @param warnings the warnings to apply to discovered handler methods
   * @return the holder
   */
  @Bean
  static JobHandlerBeans jobHandlerBeans(
      ObjectProvider<HandlerWarning> warnings, ObjectProvider<ScheduleProvider> scheduleProviders) {
    return new JobHandlerBeans(warnings, scheduleProviders);
  }

  /**
   * Supports Spring's six-field cron syntax on {@code @JobHandler} schedules.
   *
   * @return the cron schedule provider
   */
  @Bean
  ScheduleProvider cronScheduleProvider() {
    return new CronScheduleProvider();
  }

  /**
   * Collects the beans that declare {@code @JobHandler} methods. Static, so Spring registers it
   * early enough to see every other bean.
   *
   * @param jobHandlerBeans the holder to collect into
   * @return the post-processor
   */
  @Bean
  static JobHandlerBeanPostProcessor jobHandlerBeanPostProcessor(JobHandlerBeans jobHandlerBeans) {
    return new JobHandlerBeanPostProcessor(jobHandlerBeans);
  }

  /**
   * Resolves the {@code DataSource} the node uses, see {@link DataSourceSelector}.
   *
   * @param beanFactory the bean factory, to look beans up by name
   * @param candidates the {@code DataSource} beans of the context
   * @param properties the properties, for {@code bikesched.data-source}
   * @return the selector
   */
  @Bean
  DataSourceSelector dataSourceSelector(
      ConfigurableListableBeanFactory beanFactory,
      ObjectProvider<DataSource> candidates,
      BikeschedProperties properties) {
    return new DataSourceSelector(beanFactory, candidates, properties);
  }

  /**
   * The node: built with {@code autoStartPolling(false)} (the {@link BikeschedLifecycle} starts it
   * with the application), with the properties and then the customizers applied, the registered
   * handler beans and the composed listeners. Spring closes it when the context closes, before the
   * {@code DataSource} it was built from.
   *
   * @param dataSourceSelector resolves the {@code DataSource} to use
   * @param properties the {@code bikesched.*} properties
   * @param environment the environment, for the default node name
   * @param jobHandlerBeans the collected beans with {@code @JobHandler} methods
   * @param customizers customizers, applied after the properties in {@code @Order}
   * @param listeners the {@link JobListener} beans, composed into one
   * @param jobTypeHandlers the {@link JobTypeHandler} beans, registered as handlers
   * @param jsonMapper the JSON mapper to use, if the application or bikesched-jackson provides one
   * @return the node
   */
  @Bean
  @ConditionalOnMissingBean
  Bikesched bikesched(
      DataSourceSelector dataSourceSelector,
      BikeschedProperties properties,
      Environment environment,
      JobHandlerBeans jobHandlerBeans,
      ObjectProvider<BikeschedCustomizer> customizers,
      ObjectProvider<JobListener> listeners,
      ObjectProvider<JobTypeHandler<?>> jobTypeHandlers,
      ObjectProvider<JsonMapper> jsonMapper) {
    var builder =
        Bikesched.builder()
            .dataSource(dataSourceSelector.select())
            .nodeName(NodeNames.resolve(properties.nodeName(), environment))
            .autoStartPolling(false);
    properties.applyTo(builder);
    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
    JsonMapper mapper = jsonMapper.getIfAvailable();
    if (mapper != null) {
      builder.jsonMapper(mapper);
    }
    List<JobListener> listenerBeans = listeners.orderedStream().toList();
    if (!listenerBeans.isEmpty()) {
      builder.jobListener(
          listenerBeans.size() == 1
              ? listenerBeans.getFirst()
              : new CompositeJobListener(listenerBeans));
    }

    Bikesched node = builder.build();
    int handlers = jobHandlerBeans.registerAll(node);
    jobTypeHandlers.orderedStream().forEach(node::registerJobHandler);
    LOG.info(
        "bikesched node {} is ready: {} @JobHandler method(s), {} listener(s), {} (polling is"
            + " started with the application)",
        node.node().name(),
        handlers,
        listenerBeans.size(),
        mapper == null
            ? "the default JSON mapper (JSON strings only)"
            : mapper.getClass().getName());
    return node;
  }

  /**
   * Starts and stops the node with the context. Not conditional on {@code
   * bikesched.auto-start.enabled} on purpose: the schema is migrated and validated even when this
   * instance only schedules jobs.
   *
   * @param bikesched the node
   * @param properties the properties
   * @param jobHandlerBeans the holder, so beans created during shutdown are not registered
   * @return the lifecycle
   */
  @Bean
  @ConditionalOnBean(Bikesched.class)
  BikeschedLifecycle bikeschedLifecycle(
      Bikesched bikesched, BikeschedProperties properties, JobHandlerBeans jobHandlerBeans) {
    return new BikeschedLifecycle(bikesched, properties, jobHandlerBeans);
  }

  /** The Jackson {@code JsonMapper}, when {@code bikesched-jackson} is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(JacksonJsonMapper.class)
  static class JacksonConfiguration {

    /**
     * Maps payloads with the context's {@code ObjectMapper} if there is one, and with Jackson's
     * defaults otherwise.
     *
     * @param mapper the application's {@code ObjectMapper}, if it defines one
     * @return the mapper
     */
    @Bean
    @ConditionalOnMissingBean(JsonMapper.class)
    JsonMapper jsonMapper(ObjectProvider<tools.jackson.databind.ObjectMapper> mapper) {
      tools.jackson.databind.ObjectMapper objectMapper = mapper.getIfAvailable();
      return objectMapper == null ? new JacksonJsonMapper() : new JacksonJsonMapper(objectMapper);
    }
  }

  /**
   * The Micrometer listener, when {@code bikesched-micrometer} and a {@code MeterRegistry} exist.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MicrometerJobListener.class)
  static class MicrometerConfiguration {

    /**
     * Records the node's jobs in the context's registry, tagged with the node name (two nodes
     * sharing a registry would otherwise overwrite each other's gauges).
     *
     * @param registry the application's registry
     * @param properties the properties, for the node name
     * @param environment the environment, for the default node name
     * @return the listener
     */
    @Bean
    @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
    @ConditionalOnMissingBean(MicrometerJobListener.class)
    @ConditionalOnProperty(
        name = "bikesched.metrics.enabled",
        havingValue = "true",
        matchIfMissing = true)
    MicrometerJobListener micrometerJobListener(
        io.micrometer.core.instrument.MeterRegistry registry,
        BikeschedProperties properties,
        Environment environment) {
      return new MicrometerJobListener(
          registry,
          io.micrometer.core.instrument.Tags.of(
              "node", NodeNames.resolve(properties.nodeName(), environment)));
    }
  }

  /** The {@code @Transactional} propagation warning, when {@code spring-tx} is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(org.springframework.transaction.annotation.Transactional.class)
  static class TransactionWarningConfiguration {

    /**
     * @return the warning about handler methods using the default propagation
     */
    @Bean
    HandlerWarning transactionPropagationWarning() {
      return new TransactionPropagationWarning();
    }
  }
}
