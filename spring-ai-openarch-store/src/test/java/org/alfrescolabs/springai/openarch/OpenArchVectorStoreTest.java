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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenArchVectorStoreTest {

	private static final String URL = "http://openarch";

	private MockRestServiceServer server;

	private OpenArchVectorStore store;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.store = OpenArchVectorStore.builder(builder.build(), new FixedEmbeddingModel())
			.collection("books")
			.build();
	}

	@Test
	void addCreatesParentThenEmbeddingsChild() {
		this.server.expect(requestTo(URL + "/api/documents/path/spring-ai/doc-1"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withStatus(HttpStatus.NOT_FOUND));
		this.server.expect(requestTo(URL + "/api/documents/path/spring-ai?enforceSysName=true"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.sys_name").value("doc-1"))
			.andExpect(jsonPath("$.sys_mixinTypes[0]").value("CinRemote"))
			.andExpect(jsonPath("$.cin_sourceId").value("spring-ai:books"))
			.andExpect(jsonPath("$.cin_ingestProperties.author").value("ana"))
			.andRespond(withSuccess("{\"sys_id\":\"p1\"}", MediaType.APPLICATION_JSON));
		this.server.expect(requestTo(URL + "/api/upload/create"))
			.andRespond(withSuccess("{\"id\":\"u1\"}", MediaType.APPLICATION_JSON));
		this.server
			.expect(requestTo(URL + "/api/upload?id=u1&fileName=embeddings.parquet&mimeType=application/x-parquet"))
			.andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
			.andRespond(withSuccess());
		this.server.expect(requestTo(URL + "/api/documents/p1?enforceSysName=true"))
			.andExpect(jsonPath("$.sys_primaryType").value("SysEmbeddings"))
			.andExpect(jsonPath("$.sys_name").value("_e_spring-ai"))
			.andExpect(jsonPath("$.sysemb_embeddings.uploadId").value("u1"))
			.andRespond(withSuccess());
		this.server.expect(requestTo(URL + "/api/query/waitForFullTextSearchIndexing?refresh=true&timeout=30"))
			.andRespond(withSuccess());

		this.store.add(List.of(Document.builder().id("doc-1").text("hello").metadata("author", "ana").build()));

		this.server.verify();
	}

	@Test
	void similaritySearchJoinsHitsWithParentMetadata() {
		this.server.expect(requestTo(URL + "/api/query/embeddings"))
			.andExpect(jsonPath("$.embeddingType").value("spring-ai"))
			.andExpect(jsonPath("$.limit").value(2))
			.andExpect(jsonPath("$.query").value("SELECT * FROM SysFile WHERE cin_sourceId = 'spring-ai:books'"
					+ " AND (cin_ingestProperties.author = 'ana')"))
			.andRespond(withSuccess("""
					{"embeddings": [
					  {"sysembed_docId": "p1", "sysembed_text": "hello", "sysembed_score": 0.9},
					  {"sysembed_docId": "p2", "sysembed_text": "low", "sysembed_score": 0.1}
					]}
					""", MediaType.APPLICATION_JSON));
		this.server.expect(requestTo(URL + "/api/query"))
			.andExpect(jsonPath("$.query").value("SELECT * FROM SysFile WHERE cin_sourceId = 'spring-ai:books'"
					+ " AND (sys_id = 'p1')"))
			.andRespond(withSuccess("""
					{"documents": [
					  {"sys_id": "p1", "cin_id": "doc-1", "cin_ingestProperties": {"author": "ana"}}
					]}
					""", MediaType.APPLICATION_JSON));

		List<Document> results = this.store.similaritySearch(SearchRequest.builder()
			.query("hi")
			.topK(2)
			.similarityThreshold(0.5)
			.filterExpression("author == 'ana'")
			.build());

		assertThat(results).singleElement().satisfies(doc -> {
			assertThat(doc.getId()).isEqualTo("doc-1");
			assertThat(doc.getText()).isEqualTo("hello");
			assertThat(doc.getScore()).isEqualTo(0.9);
			assertThat(doc.getMetadata()).containsEntry("author", "ana");
		});
		this.server.verify();
	}

	/** Returns the same two-dimension vector for every input. */
	static final class FixedEmbeddingModel implements EmbeddingModel {

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> embeddings = new java.util.ArrayList<>();
			for (int i = 0; i < request.getInstructions().size(); i++) {
				embeddings.add(new Embedding(new float[] { 0.1f, 0.2f }, i));
			}
			return new EmbeddingResponse(embeddings);
		}

		@Override
		public float[] embed(Document document) {
			return new float[] { 0.1f, 0.2f };
		}

		@Override
		public int dimensions() {
			return 2;
		}

	}

}
