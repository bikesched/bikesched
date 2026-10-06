// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Resolves the {@code DataSource} bean the node uses: the one named by {@code
 * bikesched.data-source}, or the only (or {@code @Primary}) {@code DataSource} bean.
 */
final class DataSourceSelector {

  private static final Logger LOG = LoggerFactory.getLogger(DataSourceSelector.class);

  private final ConfigurableListableBeanFactory beanFactory;
  private final ObjectProvider<DataSource> candidates;
  private final String configuredName;

  DataSourceSelector(
      ConfigurableListableBeanFactory beanFactory,
      ObjectProvider<DataSource> candidates,
      BikeschedProperties properties) {
    this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory");
    this.candidates = Objects.requireNonNull(candidates, "candidates");
    this.configuredName = properties.dataSource();
  }

  /**
   * Returns the {@code DataSource} the node uses.
   *
   * @return the data source
   * @throws DataSourceSelectionException if it cannot be determined
   */
  DataSource select() {
    if (configuredName != null && !configuredName.isBlank()) {
      return named();
    }
    DataSource unique = candidates.getIfUnique();
    if (unique == null) {
      throw new DataSourceSelectionException(
          "bikesched found "
              + candidateNames().size()
              + " DataSource beans ("
              + String.join(", ", candidateNames())
              + ") and none is @Primary; set bikesched.data-source to the name of the one to use");
    }
    return unique;
  }

  /** The bean named by {@code bikesched.data-source}. */
  private DataSource named() {
    if (!beanFactory.containsBean(configuredName)) {
      throw new DataSourceSelectionException(
          "bikesched.data-source is '"
              + configuredName
              + "', but the context has no bean with that name; DataSource beans: "
              + String.join(", ", candidateNames()));
    }
    Object bean = beanFactory.getBean(configuredName);
    if (!(bean instanceof DataSource dataSource)) {
      throw new DataSourceSelectionException(
          "bikesched.data-source is '"
              + configuredName
              + "', but that bean is a "
              + bean.getClass().getName()
              + ", not a DataSource");
    }
    if (beanFactory.containsBeanDefinition(configuredName)
        && !beanFactory.getBeanDefinition(configuredName).isPrimary()) {
      LOG.info(
          "bikesched uses the DataSource bean '{}', which is not the @Primary one; DataSource beans: {}",
          configuredName,
          String.join(", ", candidateNames()));
    }
    return dataSource;
  }

  private List<String> candidateNames() {
    return Arrays.stream(beanFactory.getBeanNamesForType(DataSource.class, true, false))
        .sorted()
        .toList();
  }
}
