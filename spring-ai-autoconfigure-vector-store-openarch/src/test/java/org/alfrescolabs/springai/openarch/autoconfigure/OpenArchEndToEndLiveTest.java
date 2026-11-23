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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot application with the OpenAI embedding starter pointed at Docker Model Runner and
 * the Hyland OpenArch store from auto-configuration. Set {@code OPENARCH_URL} and
 * {@code MODEL_RUNNER_URL} (for example {@code http://localhost:12434/engines/v1});
 * optionally {@code EMBEDDING_MODEL} (default {@code ai/mxbai-embed-large}).
 */
@EnabledIfEnvironmentVariable(named = "OPENARCH_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MODEL_RUNNER_URL", matches = ".+")
class OpenArchEndToEndLiveTest {

	@Test
	void semanticSearchWithLocalEmbeddingModel() {
		String collection = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
			.web(WebApplicationType.NONE)
			.properties(Map.of("spring.ai.openai.base-url", System.getenv("MODEL_RUNNER_URL"),
					"spring.ai.openai.api-key", "not-needed", "spring.ai.openai.embedding.model",
					env("EMBEDDING_MODEL", "ai/mxbai-embed-large"), "spring.ai.model.chat", "none",
					"spring.ai.model.image", "none", "spring.ai.model.audio.speech", "none",
					"spring.ai.model.audio.transcription", "none", "spring.ai.model.moderation", "none"))
			.properties(Map.of("spring.ai.vectorstore.openarch.url", System.getenv("OPENARCH_URL"),
					"spring.ai.vectorstore.openarch.username", env("OPENARCH_USER", "admin"),
					"spring.ai.vectorstore.openarch.password", env("OPENARCH_PASSWORD", "password"),
					"spring.ai.vectorstore.openarch.collection", collection,
					"spring.ai.vectorstore.openarch.embedding-type", "mxbai-embed-large",
					"spring.ai.vectorstore.openarch.initialize-schema", "true"))
			.run()) {

			VectorStore store = context.getBean(VectorStore.class);
			List<Document> documents = List.of(
					doc("Knead the dough for ten minutes, let it rise for an hour, then bake at 220 C.", "cooking"),
					doc("The invoice is due within 30 days. Late payments add a 2 percent fee.", "finance"),
					doc("Replace the bicycle chain when it stretches more than half a percent.", "repair"),
					doc("A sourdough starter needs flour and water every day to stay active.", "cooking"));
			store.add(documents);

			List<Document> bread = store
				.similaritySearch(SearchRequest.builder().query("How do I make bread at home?").topK(2).build());
			bread.forEach(d -> System.out.printf("bread hit score=%.3f %s%n", d.getScore(), d.getText()));
			assertThat(bread).extracting(d -> d.getMetadata().get("topic")).containsOnly("cooking");

			List<Document> money = store.similaritySearch(SearchRequest.builder()
				.query("When do I have to pay?")
				.topK(1)
				.filterExpression("topic != 'cooking'")
				.build());
			money.forEach(d -> System.out.printf("money hit score=%.3f %s%n", d.getScore(), d.getText()));
			assertThat(money).singleElement().satisfies(d -> assertThat(d.getMetadata()).containsEntry("topic", "finance"));

			store.delete(documents.stream().map(Document::getId).toList());
		}
	}

	private static Document doc(String text, String topic) {
		return Document.builder().text(text).metadata("topic", topic).build();
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return (value == null || value.isBlank()) ? fallback : value;
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

	}

}
