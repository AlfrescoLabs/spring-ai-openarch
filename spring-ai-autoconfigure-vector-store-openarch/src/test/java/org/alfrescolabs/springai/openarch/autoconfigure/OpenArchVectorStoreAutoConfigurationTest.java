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

import org.alfrescolabs.springai.openarch.OpenArchVectorStore;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenArchVectorStoreAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenArchVectorStoreAutoConfiguration.class))
		.withUserConfiguration(EmbeddingConfig.class);

	@Test
	void createsStoreWithProperties() {
		this.runner
			.withPropertyValues("spring.ai.vectorstore.openarch.url=http://openarch:8080",
					"spring.ai.vectorstore.openarch.username=admin", "spring.ai.vectorstore.openarch.password=secret",
					"spring.ai.vectorstore.openarch.collection=books",
					"spring.ai.vectorstore.openarch.index-dimensions=768")
			.run(context -> {
				assertThat(context).hasSingleBean(VectorStore.class).hasSingleBean(BatchingStrategy.class);
				assertThat(context.getBean(VectorStore.class)).isInstanceOf(OpenArchVectorStore.class);
				OpenArchVectorStoreProperties properties = context.getBean(OpenArchVectorStoreProperties.class);
				assertThat(properties.getCollection()).isEqualTo("books");
				assertThat(properties.getIndexDimensions()).isEqualTo(768);
			});
	}

	@Test
	void backsOffForAnotherStoreType() {
		this.runner.withPropertyValues("spring.ai.vectorstore.type=pgvector")
			.run(context -> assertThat(context).doesNotHaveBean(VectorStore.class));
	}

	@Test
	void backsOffWhenUserDefinesStore() {
		this.runner.withUserConfiguration(UserStoreConfig.class)
			.run(context -> assertThat(context).getBean(OpenArchVectorStore.class)
				.extracting(store -> store.getNativeClient().isPresent())
				.isEqualTo(true));
	}

	@Configuration(proxyBeanMethods = false)
	static class EmbeddingConfig {

		@Bean
		EmbeddingModel embeddingModel() {
			return new EmbeddingModel() {

				@Override
				public EmbeddingResponse call(EmbeddingRequest request) {
					return new EmbeddingResponse(java.util.List.of(new Embedding(new float[] { 1f }, 0)));
				}

				@Override
				public float[] embed(Document document) {
					return new float[] { 1f };
				}

				@Override
				public int dimensions() {
					return 1;
				}
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UserStoreConfig {

		@Bean
		OpenArchVectorStore userStore(EmbeddingModel embeddingModel) {
			return OpenArchVectorStore
				.builder(OpenArchVectorStore.restClient("http://other", "u", "p", "default"), embeddingModel)
				.build();
		}

	}

}
