/*
 * Copyright 2026 the original author or authors.
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

package org.alfrescolabs.springai.openarch.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.alfrescolabs.springai.openarch.OpenArchVectorStore;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.SpringAIVectorStoreTypes;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration of {@link OpenArchVectorStore}.
 *
 * <p>Active when {@code spring.ai.vectorstore.type} is {@code openarch} or not set. Needs an
 * {@link EmbeddingModel} bean. When the context has a {@link RestClient.Builder}, the store
 * uses a copy of it, so its customizers (timeouts, tracing) apply.</p>
 */
@AutoConfiguration
@ConditionalOnClass({ OpenArchVectorStore.class, EmbeddingModel.class, RestClient.class })
@EnableConfigurationProperties(OpenArchVectorStoreProperties.class)
@ConditionalOnProperty(name = SpringAIVectorStoreTypes.TYPE, havingValue = OpenArchVectorStoreAutoConfiguration.TYPE,
		matchIfMissing = true)
public class OpenArchVectorStoreAutoConfiguration {

	/** Value of {@code spring.ai.vectorstore.type} that selects this store. */
	public static final String TYPE = "openarch";

	@Bean
	@ConditionalOnMissingBean(BatchingStrategy.class)
	BatchingStrategy openArchBatchingStrategy() {
		return new TokenCountBatchingStrategy();
	}

	@Bean
	@ConditionalOnMissingBean
	OpenArchVectorStore openArchVectorStore(OpenArchVectorStoreProperties properties, EmbeddingModel embeddingModel,
			BatchingStrategy batchingStrategy, ObjectProvider<RestClient.Builder> restClientBuilder,
			ObjectProvider<ObservationRegistry> observationRegistry,
			ObjectProvider<VectorStoreObservationConvention> customObservationConvention) {

		RestClient.Builder builder = restClientBuilder.getIfAvailable(RestClient::builder).clone();
		builder.baseUrl(properties.getUrl()).defaultHeaders(headers -> {
			if (StringUtils.hasText(properties.getUsername())) {
				headers.setBasicAuth(properties.getUsername(),
						(properties.getPassword() != null) ? properties.getPassword() : "");
			}
			headers.set("HXCS-REPOSITORY", properties.getRepositoryId());
		});

		return OpenArchVectorStore.builder(builder.build(), embeddingModel)
			.basePath(properties.getBasePath())
			.collection(properties.getCollection())
			.embeddingType(properties.getEmbeddingType())
			.indexDimensions(properties.getIndexDimensions())
			.initializeSchema(properties.isInitializeSchema())
			.waitForIndexing(properties.isWaitForIndexing())
			.batchingStrategy(batchingStrategy)
			.observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
			.customObservationConvention(customObservationConvention.getIfAvailable(() -> null))
			.build();
	}

}
