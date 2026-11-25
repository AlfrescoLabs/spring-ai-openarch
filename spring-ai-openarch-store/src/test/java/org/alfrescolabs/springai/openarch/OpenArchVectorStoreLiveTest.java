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

package org.alfrescolabs.springai.openarch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real Hyland OpenArch engine. Set {@code OPENARCH_URL}, and optionally
 * {@code OPENARCH_USER} and {@code OPENARCH_PASSWORD} (default {@code admin:password}).
 */
@EnabledIfEnvironmentVariable(named = "OPENARCH_URL", matches = ".+")
class OpenArchVectorStoreLiveTest {

	private static final int DIMENSIONS = 8;

	private RestClient restClient;

	private OpenArchVectorStore store;

	@BeforeEach
	void setUp() {
		this.restClient = OpenArchVectorStore.restClient(System.getenv("OPENARCH_URL"), env("OPENARCH_USER", "admin"),
				env("OPENARCH_PASSWORD", "password"), "default");
		this.store = OpenArchVectorStore.builder(this.restClient, new KeywordEmbeddingModel())
			.collection("it-" + UUID.randomUUID().toString().substring(0, 8))
			.initializeSchema(true)
			.build();
		this.store.afterPropertiesSet();
	}

	@Test
	void addSearchFilterReplaceDelete() {
		String a = UUID.randomUUID().toString();
		String b = UUID.randomUUID().toString();
		String c = UUID.randomUUID().toString();
		this.store.add(List.of(doc(a, "alpha", "drama", 2020), doc(b, "beta", "comedy", 2021),
				doc(c, "gamma", "drama", 2022)));

		List<Document> nearest = this.store.similaritySearch(SearchRequest.builder().query("alpha").topK(3).build());
		nearest.forEach(d -> System.out.printf("score text=%s score=%s distance=%s%n", d.getText(), d.getScore(),
				d.getMetadata().get("distance")));
		assertThat(nearest).isNotEmpty();
		assertThat(nearest.get(0).getId()).isEqualTo(a);
		assertThat(nearest.get(0).getMetadata()).containsEntry("genre", "drama");

		assertThat(ids(search("genre == 'drama'"))).containsExactlyInAnyOrder(a, c);
		assertThat(ids(search("genre != 'drama'"))).containsExactly(b);
		assertThat(ids(search("genre in ['comedy', 'drama'] && year >= 2021"))).containsExactlyInAnyOrder(b, c);

		// Null checks: a document without a year.
		String noYear = UUID.randomUUID().toString();
		this.store.add(
				List.of(Document.builder().id(noYear).text("epsilon").metadata(Map.of("genre", "horror")).build()));
		FilterExpressionBuilder fb = new FilterExpressionBuilder();
		assertThat(ids(search(fb.isNull("year").build()))).containsExactly(noYear);
		assertThat(ids(search(fb.isNotNull("year").build()))).containsExactlyInAnyOrder(a, b, c);
		this.store.delete(List.of(noYear));

		// Same id replaces the stored document.
		this.store.add(List.of(doc(a, "delta", "thriller", 2023)));
		assertThat(search("genre == 'thriller'")).singleElement()
			.satisfies(d -> assertThat(d.getText()).isEqualTo("delta"));
		assertThat(search("genre == 'drama'")).extracting(Document::getId).containsExactly(c);

		// Deleting the parent removes its SysEmbeddings child.
		String childPath = "/api/documents/path/spring-ai/" + b + "/_e_spring-ai";
		Map<?, ?> child = this.restClient.get().uri(childPath).retrieve().body(Map.class);
		System.out.println("child before delete: sys_primaryType=" + child.get("sys_primaryType"));
		this.store.delete(List.of(b));
		assertThatThrownBy(() -> this.restClient.get().uri(childPath).retrieve().body(Map.class))
			.isInstanceOf(HttpClientErrorException.NotFound.class);
		assertThat(ids(this.store.similaritySearch(SearchRequest.builder().query("beta").topK(5).build())))
			.doesNotContain(b);

		this.store.delete("genre == 'drama'");
		assertThat(ids(this.store.similaritySearch(SearchRequest.builder().query("gamma").topK(5).build())))
			.containsExactly(a);
		this.store.delete(List.of(a));
	}

	private List<Document> search(Filter.Expression filter) {
		return this.store.similaritySearch(SearchRequest.builder().query("alpha").topK(10).filterExpression(filter).build());
	}

	private List<Document> search(String filter) {
		return this.store.similaritySearch(SearchRequest.builder().query("alpha").topK(10).filterExpression(filter).build());
	}

	private static Document doc(String id, String text, String genre, int year) {
		return Document.builder().id(id).text(text).metadata(Map.of("genre", genre, "year", year)).build();
	}

	private static List<String> ids(List<Document> documents) {
		return documents.stream().map(Document::getId).toList();
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return (value == null || value.isBlank()) ? fallback : value;
	}

	/** Maps known words to fixed unit vectors, so similarity is predictable. */
	static final class KeywordEmbeddingModel implements EmbeddingModel {

		private static final List<String> WORDS = List.of("alpha", "beta", "gamma", "delta");

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> embeddings = new ArrayList<>();
			for (int i = 0; i < request.getInstructions().size(); i++) {
				embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
			}
			return new EmbeddingResponse(embeddings);
		}

		@Override
		public float[] embed(Document document) {
			return vector(document.getText());
		}

		@Override
		public int dimensions() {
			return DIMENSIONS;
		}

		private static float[] vector(String text) {
			float[] v = new float[DIMENSIONS];
			int index = WORDS.indexOf(text);
			v[index < 0 ? DIMENSIONS - 1 : index] = 1f;
			// A small shared component keeps every pair of vectors from being orthogonal.
			v[DIMENSIONS - 1] += 0.1f;
			return v;
		}

	}

}
