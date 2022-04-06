/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.r2dbc;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import io.r2dbc.h2.H2ConnectionFactory;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.PoolMetrics;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryProvider;
import io.r2dbc.spi.Option;
import io.r2dbc.spi.Wrapped;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.InstanceOfAssertFactory;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.SimpleConnectionFactoryProvider.SimpleTestConnectionFactory;
import org.springframework.boot.r2dbc.EmbeddedDatabaseConnection;
import org.springframework.boot.r2dbc.OptionsCapableConnectionFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link R2dbcAutoConfiguration}.
 *
 * @author Mark Paluch
 * @author Stephane Nicoll
 */
class R2dbcAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class));

	@Test
	void configureWithUrlCreateConnectionPoolByDefault() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:h2:mem:///" + randomDatabaseName())
				.run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(ConnectionPool.class);
					assertThat(context.getBean(ConnectionPool.class)).extracting(ConnectionPool::unwrap)
							.satisfies((connectionFactory) -> assertThat(connectionFactory)
									.asInstanceOf(type(OptionsCapableConnectionFactory.class))
									.extracting(Wrapped<ConnectionFactory>::unwrap)
									.isExactlyInstanceOf(H2ConnectionFactory.class));
				});
	}

	@Test
	void configureWithUrlAndPoolPropertiesApplyProperties() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:h2:mem:///" + randomDatabaseName(),
				"spring.r2dbc.pool.max-size=15", "spring.r2dbc.pool.max-acquire-time=3m").run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(ConnectionPool.class)
							.hasSingleBean(R2dbcProperties.class);
					ConnectionPool connectionPool = context.getBean(ConnectionPool.class);
					PoolMetrics poolMetrics = connectionPool.getMetrics().get();
					assertThat(poolMetrics.getMaxAllocatedSize()).isEqualTo(15);
					assertThat(connectionPool).hasFieldOrPropertyWithValue("maxAcquireTime", Duration.ofMinutes(3));
				});
	}

	@Test
	void configureWithUrlAndDefaultDoNotOverrideDefaultTimeouts() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:h2:mem:///" + randomDatabaseName())
				.run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(ConnectionPool.class)
							.hasSingleBean(R2dbcProperties.class);
					ConnectionPool connectionPool = context.getBean(ConnectionPool.class);
					assertThat(connectionPool).hasFieldOrPropertyWithValue("maxAcquireTime", Duration.ZERO);
				});
	}

	@Test
	void configureWithUrlPoolAndPoolPropertiesFails() {
		this.contextRunner
				.withPropertyValues("spring.r2dbc.url:r2dbc:pool:h2:mem:///" + randomDatabaseName() + "?maxSize=12",
						"spring.r2dbc.pool.max-size=15")
				.run((context) -> assertThat(context).getFailure().getRootCause()
						.isInstanceOf(MultipleConnectionPoolConfigurationsException.class));
	}

	@Test
	void configureWithUrlPoolAndPropertyBasedPoolingDisabledFails() {
		this.contextRunner
				.withPropertyValues("spring.r2dbc.url:r2dbc:pool:h2:mem:///" + randomDatabaseName() + "?maxSize=12",
						"spring.r2dbc.pool.enabled=false")
				.run((context) -> assertThat(context).getFailure().getRootCause()
						.isInstanceOf(MultipleConnectionPoolConfigurationsException.class));
	}

	@Test
	void configureWithUrlPoolAndNoPoolPropertiesCreatesPool() {
		this.contextRunner
				.withPropertyValues("spring.r2dbc.url:r2dbc:pool:h2:mem:///" + randomDatabaseName() + "?maxSize=12")
				.run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(ConnectionPool.class);
					ConnectionPool connectionPool = context.getBean(ConnectionPool.class);
					assertThat(connectionPool.getMetrics().get().getMaxAllocatedSize()).isEqualTo(12);
				});
	}

	@Test
	void configureWithPoolEnabledCreateConnectionPool() {
		this.contextRunner
				.withPropertyValues("spring.r2dbc.pool.enabled=true",
						"spring.r2dbc.url:r2dbc:h2:mem:///" + randomDatabaseName()
								+ "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
				.run((context) -> assertThat(context).hasSingleBean(ConnectionFactory.class)
						.hasSingleBean(ConnectionPool.class));
	}

	@Test
	void configureWithPoolDisabledCreateGenericConnectionFactory() {
		this.contextRunner.withPropertyValues("spring.r2dbc.pool.enabled=false", "spring.r2dbc.url:r2dbc:h2:mem:///"
				+ randomDatabaseName() + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE").run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).doesNotHaveBean(ConnectionPool.class);
					assertThat(context.getBean(ConnectionFactory.class))
							.asInstanceOf(type(OptionsCapableConnectionFactory.class))
							.extracting(Wrapped<ConnectionFactory>::unwrap)
							.isExactlyInstanceOf(H2ConnectionFactory.class);
				});
	}

	@Test
	void configureWithoutPoolInvokeOptionCustomizer() {
		this.contextRunner
				.withPropertyValues("spring.r2dbc.pool.enabled=false", "spring.r2dbc.url:r2dbc:simple://host/database")
				.withUserConfiguration(CustomizerConfiguration.class).run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).doesNotHaveBean(ConnectionPool.class);
					ConnectionFactory connectionFactory = context.getBean(ConnectionFactory.class);
					assertThat(connectionFactory).asInstanceOf(type(OptionsCapableConnectionFactory.class))
							.extracting(OptionsCapableConnectionFactory::getOptions).satisfies((options) -> assertThat(
									options.getRequiredValue(Option.<Boolean>valueOf("customized"))).isTrue());
				});
	}

	@Test
	void configureWithPoolInvokeOptionCustomizer() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:simple://host/database")
				.withUserConfiguration(CustomizerConfiguration.class).run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(ConnectionPool.class);
					ConnectionFactory pool = context.getBean(ConnectionFactory.class);
					ConnectionFactory connectionFactory = ((ConnectionPool) pool).unwrap();
					assertThat(connectionFactory).asInstanceOf(type(OptionsCapableConnectionFactory.class))
							.extracting(OptionsCapableConnectionFactory::getOptions).satisfies((options) -> assertThat(
									options.getRequiredValue(Option.<Boolean>valueOf("customized"))).isTrue());
				});
	}

	@Test
	void configureWithInvalidUrlThrowsAppropriateException() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:not-going-to-work")
				.run((context) -> assertThat(context).getFailure().isInstanceOf(BeanCreationException.class));
	}

	@Test
	void configureWithoutSpringJdbcCreateConnectionFactory() {
		this.contextRunner.withPropertyValues("spring.r2dbc.pool.enabled=false", "spring.r2dbc.url:r2dbc:simple://foo")
				.withClassLoader(new FilteredClassLoader("org.springframework.jdbc")).run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class);
					assertThat(context.getBean(ConnectionFactory.class))
							.asInstanceOf(type(OptionsCapableConnectionFactory.class))
							.extracting(Wrapped<ConnectionFactory>::unwrap)
							.isExactlyInstanceOf(SimpleTestConnectionFactory.class);
				});
	}

	@Test
	void configureWithoutPoolShouldApplyAdditionalProperties() {
		this.contextRunner.withPropertyValues("spring.r2dbc.pool.enabled=false", "spring.r2dbc.url:r2dbc:simple://foo",
				"spring.r2dbc.properties.test=value", "spring.r2dbc.properties.another=2").run((context) -> {
					ConnectionFactory connectionFactory = context.getBean(ConnectionFactory.class);
					assertThat(connectionFactory).asInstanceOf(type(OptionsCapableConnectionFactory.class))
							.extracting(OptionsCapableConnectionFactory::getOptions).satisfies((options) -> {
								assertThat(options.getRequiredValue(Option.<String>valueOf("test"))).isEqualTo("value");
								assertThat(options.getRequiredValue(Option.<String>valueOf("another"))).isEqualTo("2");
							});
				});
	}

	@Test
	void configureWithPoolShouldApplyAdditionalProperties() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:simple://foo",
				"spring.r2dbc.properties.test=value", "spring.r2dbc.properties.another=2").run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(ConnectionPool.class);
					ConnectionFactory connectionFactory = context.getBean(ConnectionPool.class).unwrap();
					assertThat(connectionFactory).asInstanceOf(type(OptionsCapableConnectionFactory.class))
							.extracting(OptionsCapableConnectionFactory::getOptions).satisfies((options) -> {
								assertThat(options.getRequiredValue(Option.<String>valueOf("test"))).isEqualTo("value");
								assertThat(options.getRequiredValue(Option.<String>valueOf("another"))).isEqualTo("2");
							});
				});
	}

	@Test
	void configureWithoutUrlShouldCreateEmbeddedConnectionPoolByDefault() {
		this.contextRunner.run((context) -> assertThat(context).hasSingleBean(ConnectionFactory.class)
				.hasSingleBean(ConnectionPool.class));
	}

	@Test
	void configureWithoutUrlAndPollPoolDisabledCreateGenericConnectionFactory() {
		this.contextRunner.withPropertyValues("spring.r2dbc.pool.enabled=false").run((context) -> {
			assertThat(context).hasSingleBean(ConnectionFactory.class).doesNotHaveBean(ConnectionPool.class);
			assertThat(context.getBean(ConnectionFactory.class))
					.asInstanceOf(type(OptionsCapableConnectionFactory.class))
					.extracting(Wrapped<ConnectionFactory>::unwrap).isExactlyInstanceOf(H2ConnectionFactory.class);
		});
	}

	@Test
	void configureWithoutUrlAndSprigJdbcCreateEmbeddedConnectionFactory() {
		this.contextRunner.withClassLoader(new FilteredClassLoader("org.springframework.jdbc"))
				.run((context) -> assertThat(context).hasSingleBean(ConnectionFactory.class)
						.hasSingleBean(ConnectionPool.class));
	}

	@Test
	void configureWithoutUrlAndEmbeddedCandidateFails() {
		this.contextRunner.withClassLoader(new DisableEmbeddedDatabaseClassLoader()).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context).getFailure().isInstanceOf(BeanCreationException.class)
					.hasMessageContaining("Failed to determine a suitable R2DBC Connection URL");
		});
	}

	@Test
	void configureWithoutUrlAndNoConnectionFactoryProviderBacksOff() {
		this.contextRunner
				.withClassLoader(new FilteredClassLoader(
						("META-INF/services/" + ConnectionFactoryProvider.class.getName())::equals))
				.run((context) -> assertThat(context).doesNotHaveBean(R2dbcAutoConfiguration.class));
	}

	@Test
	void configureWithDataSourceAutoConfigurationDoesNotCreateDataSource() {
		this.contextRunner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
				.run((context) -> assertThat(context).hasSingleBean(ConnectionFactory.class)
						.doesNotHaveBean(DataSource.class));
	}

	@Test
	void databaseClientIsConfigured() {
		this.contextRunner.withPropertyValues("spring.r2dbc.url:r2dbc:h2:mem:///" + randomDatabaseName())
				.run((context) -> {
					assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(DatabaseClient.class);
					assertThat(context.getBean(DatabaseClient.class).getConnectionFactory())
							.isSameAs(context.getBean(ConnectionFactory.class));
				});
	}

	@Test
	void databaseClientBacksOffIfSpringR2dbcIsNotAvailable() {
		this.contextRunner.withClassLoader(new FilteredClassLoader("org.springframework.r2dbc"))
				.withPropertyValues("spring.r2dbc.url:r2dbc:h2:mem:///" + randomDatabaseName())
				.run((context) -> assertThat(context).hasSingleBean(ConnectionFactory.class)
						.doesNotHaveBean(DatabaseClient.class));
	}

	private <T> InstanceOfAssertFactory<T, ObjectAssert<T>> type(Class<T> type) {
		return InstanceOfAssertFactories.type(type);
	}

	private String randomDatabaseName() {
		return "testdb-" + UUID.randomUUID();
	}

	private static class DisableEmbeddedDatabaseClassLoader extends URLClassLoader {

		DisableEmbeddedDatabaseClassLoader() {
			super(new URL[0], DisableEmbeddedDatabaseClassLoader.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			for (EmbeddedDatabaseConnection candidate : EmbeddedDatabaseConnection.values()) {
				if (name.equals(candidate.getDriverClassName())) {
					throw new ClassNotFoundException();
				}
			}
			return super.loadClass(name, resolve);
		}

	}

	@Configuration(proxyBeanMethods = false)
	private static class CustomizerConfiguration {

		@Bean
		ConnectionFactoryOptionsBuilderCustomizer customizer() {
			return (builder) -> builder.option(Option.valueOf("customized"), true);
		}

	}

}
