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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * Spring AI {@link org.springframework.ai.vectorstore.VectorStore} backed by Hyland OpenArch.
 *
 * <h2>Storage model</h2>
 * <p>Each Spring AI {@link Document} becomes one {@code SysFile} document under
 * {@code basePath}, named after the document id:</p>
 * <ul>
 * <li>{@code cin_id} holds the Spring AI document id.</li>
 * <li>{@code cin_sourceId} holds {@code spring-ai:<collection>}, which scopes every query to
 * this store.</li>
 * <li>{@code cin_ingestProperties} holds the metadata map. Filter expressions run against
 * it.</li>
 * </ul>
 * <p>The vector and the text go into a {@code SysEmbeddings} child named
 * {@code _e_<embeddingType>}. Its content is a one-row Parquet file, uploaded first through
 * {@code /api/upload}. Hyland OpenArch indexes that row and serves it from
 * {@code POST /api/query/embeddings}.</p>
 *
 * <h2>Scores</h2>
 * <p>The engine ranks by Euclidean distance and returns {@code score = 1 / (1 + d2)}, where
 * {@code d2} is the squared distance. The document score is that value, and the
 * {@code distance} metadata is {@code d2}. For unit vectors, {@code d2 = 2 - 2 * cosine}, so a
 * cosine of 0 gives a score of 1/3. Choose {@code similarityThreshold} on this scale.</p>
 *
 * <h2>Connection</h2>
 * <p>The {@link RestClient} must carry the engine base URL, the credentials and the
 * {@code HXCS-REPOSITORY} header. See {@link #restClient(String, String, String, String)}.</p>
 *
 * <h2>Consistency</h2>
 * <p>The Hyland OpenArch query index is eventually consistent. With
 * {@code waitForIndexing(true)} (the default), each write waits for the index before it
 * returns, so a search right after {@code add} sees the new documents.</p>
 */
public class OpenArchVectorStore extends AbstractObservationVectorStore implements InitializingBean {

	public static final String PROVIDER = "hyland_openarch";

	public static final String DEFAULT_BASE_PATH = "/spring-ai";

	public static final String DEFAULT_COLLECTION = "default";

	public static final String DEFAULT_EMBEDDING_TYPE = "spring-ai";

	/**
	 * Vector length of the Hyland OpenArch embeddings index. The index accepts only this length,
	 * so shorter vectors are padded with zeros. Zero padding keeps cosine, dot product and
	 * Euclidean results unchanged.
	 */
	public static final int DEFAULT_INDEX_DIMENSIONS = 1024;

	static final String SOURCE_PREFIX = "spring-ai:";

	private static final String MIXIN_CIN_REMOTE = "CinRemote";

	private static final String MIXIN_HAS_EMBEDDINGS = "SysHasEmbeddings";

	private static final int PAGE_SIZE = 100;

	private static final int INDEX_WAIT_SECONDS = 30;

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
	};

	private final RestClient restClient;

	private final String basePath;

	private final String collection;

	private final String embeddingType;

	private final boolean initializeSchema;

	private final boolean waitForIndexing;

	private final int indexDimensions;

	private final FilterExpressionConverter filterConverter = new OpenArchFilterExpressionConverter();

	protected OpenArchVectorStore(Builder builder) {
		super(builder);
		this.restClient = builder.restClient;
		this.basePath = normalizePath(builder.basePath);
		this.collection = builder.collection;
		this.embeddingType = builder.embeddingType;
		this.initializeSchema = builder.initializeSchema;
		this.waitForIndexing = builder.waitForIndexing;
		this.indexDimensions = builder.indexDimensions;
	}

	public static Builder builder(RestClient restClient, EmbeddingModel embeddingModel) {
		return new Builder(restClient, embeddingModel);
	}

	/**
	 * Builds a {@link RestClient} for a Hyland OpenArch engine with HTTP Basic credentials.
	 * @param baseUrl engine URL, for example {@code http://localhost:8080}
	 * @param username engine user
	 * @param password engine password
	 * @param repositoryId repository, usually {@code default}
	 * @return a configured client
	 */
	public static RestClient restClient(String baseUrl, String username, String password, String repositoryId) {
		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeaders(headers -> {
				headers.setBasicAuth(username, password);
				headers.set("HXCS-REPOSITORY", repositoryId);
			})
			.build();
	}

	/**
	 * Creates the missing folders of {@code basePath} when {@code initializeSchema} is set.
	 * Existing folders are only read, so a non-admin user works when an admin has created the
	 * base path and given that user access to it.
	 */
	@Override
	public void afterPropertiesSet() {
		if (!this.initializeSchema) {
			return;
		}
		String parent = "";
		for (String segment : this.basePath.substring(1).split("/")) {
			String path = parent + "/" + segment;
			if (!folderExists(path)) {
				try {
					this.restClient.post()
						.uri(pathUri(parent, "enforceSysName=true"))
						.contentType(MediaType.APPLICATION_JSON)
						.body(Map.of("sys_primaryType", "SysFolder", "sys_name", segment))
						.retrieve()
						.toBodilessEntity();
				}
				catch (HttpClientErrorException.Conflict ex) {
					// Created in the meantime.
				}
			}
			parent = path;
		}
	}

	private boolean folderExists(String path) {
		try {
			this.restClient.get().uri(pathUri(path, null)).retrieve().toBodilessEntity();
			return true;
		}
		catch (HttpClientErrorException.NotFound ex) {
			return false;
		}
	}

	@Override
	public void doAdd(List<Document> documents) {
		List<float[]> vectors = this.embeddingModel.embed(documents, EmbeddingOptions.builder().build(),
				this.batchingStrategy);
		for (int i = 0; i < documents.size(); i++) {
			Document document = documents.get(i);
			float[] vector = pad(vectors.get(i));
			// Same id means replace, as in the other Spring AI stores.
			deleteById(document.getId());
			String sysId = createParent(document);
			createEmbeddingsChild(sysId, document, vector);
		}
		awaitIndex();
	}

	@Override
	public void doDelete(List<String> idList) {
		idList.forEach(this::deleteById);
		awaitIndex();
	}

	@Override
	protected void doDelete(Filter.Expression filterExpression) {
		String hxql = scopeQuery() + " AND (" + this.filterConverter.convertExpression(filterExpression) + ")";
		List<String> sysIds = new ArrayList<>();
		int offset = 0;
		List<Map<String, Object>> page;
		do {
			page = query(hxql, PAGE_SIZE, offset);
			page.forEach(doc -> sysIds.add((String) doc.get("sys_id")));
			offset += page.size();
		}
		while (page.size() == PAGE_SIZE);
		sysIds.forEach(this::deleteBySysId);
		awaitIndex();
	}

	@Override
	public List<Document> doSimilaritySearch(SearchRequest request) {
		String hxql = scopeQuery();
		if (request.hasFilterExpression()) {
			hxql += " AND (" + this.filterConverter.convertExpression(request.getFilterExpression()) + ")";
		}

		Map<String, Object> vectorQuery = new LinkedHashMap<>();
		vectorQuery.put("vector", toDoubles(pad(this.embeddingModel.embed(request.getQuery()))));
		vectorQuery.put("embeddingType", this.embeddingType);
		vectorQuery.put("query", hxql);
		vectorQuery.put("limit", request.getTopK());
		vectorQuery.put("offset", 0);

		Map<String, Object> result = this.restClient.post()
			.uri("/api/query/embeddings")
			.contentType(MediaType.APPLICATION_JSON)
			.body(vectorQuery)
			.retrieve()
			.body(JSON_MAP);

		List<Map<String, Object>> hits = listOf(result, "embeddings").stream()
			.filter(hit -> score(hit) >= request.getSimilarityThreshold())
			.toList();
		if (hits.isEmpty()) {
			return List.of();
		}

		// The embeddings index returns the parent sys_id but not its metadata: read the parents in one query.
		Map<String, Map<String, Object>> parents = readParents(
				hits.stream().map(hit -> (String) hit.get("sysembed_docId")).distinct().toList());

		List<Document> documents = new ArrayList<>(hits.size());
		for (Map<String, Object> hit : hits) {
			Map<String, Object> parent = parents.get((String) hit.get("sysembed_docId"));
			if (parent == null) {
				continue;
			}
			double score = score(hit);
			Map<String, Object> metadata = new HashMap<>(mapOf(parent, "cin_ingestProperties"));
			metadata.put(DocumentMetadata.DISTANCE.value(), (score > 0) ? (1.0 / score) - 1.0 : Double.MAX_VALUE);
			documents.add(Document.builder()
				.id((String) parent.get("cin_id"))
				.text((String) hit.get("sysembed_text"))
				.metadata(metadata)
				.score(score)
				.build());
		}
		return documents;
	}

	@Override
	public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {
		return VectorStoreObservationContext.builder(PROVIDER, operationName)
			.collectionName(this.collection)
			.namespace(this.basePath)
			.dimensions(this.embeddingModel.dimensions());
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getNativeClient() {
		return Optional.of((T) this.restClient);
	}

	private String createParent(Document document) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sys_primaryType", "SysFile");
		body.put("sys_name", document.getId());
		body.put("sys_mixinTypes", List.of(MIXIN_CIN_REMOTE, MIXIN_HAS_EMBEDDINGS));
		body.put("cin_id", document.getId());
		body.put("cin_sourceId", sourceId());
		body.put("cin_ingestProperties", document.getMetadata());
		body.put("cin_ingestPropertyNames", List.copyOf(document.getMetadata().keySet()));

		Map<String, Object> created = this.restClient.post()
			.uri(pathUri(this.basePath, "enforceSysName=true"))
			.contentType(MediaType.APPLICATION_JSON)
			.body(body)
			.retrieve()
			.body(JSON_MAP);
		Assert.state(created != null && created.get("sys_id") != null, "Hyland OpenArch returned no sys_id");
		return (String) created.get("sys_id");
	}

	private void createEmbeddingsChild(String parentSysId, Document document, float[] vector) {
		byte[] parquet = EmbeddingParquet.write(
				List.of(new EmbeddingParquet.Row(document.getId(), document.getText(), vector)), this.embeddingType);
		String uploadId = upload(parquet);
		this.restClient.post()
			.uri("/api/documents/{id}?enforceSysName=true", parentSysId)
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("sys_primaryType", "SysEmbeddings", "sys_name", "_e_" + this.embeddingType, "sys_title",
					"Embeddings", "sysemb_embeddings", Map.of("uploadId", uploadId)))
			.retrieve()
			.toBodilessEntity();
	}

	private String upload(byte[] parquet) {
		Map<String, Object> slot = this.restClient.post().uri("/api/upload/create").retrieve().body(JSON_MAP);
		Assert.state(slot != null && slot.get("id") != null, "Hyland OpenArch returned no upload id");
		String uploadId = (String) slot.get("id");
		this.restClient.post()
			.uri("/api/upload?id={id}&fileName=embeddings.parquet&mimeType=application/x-parquet", uploadId)
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body(parquet)
			.retrieve()
			.toBodilessEntity();
		return uploadId;
	}

	/** Deletes the document stored under the given Spring AI id, if any. Its children go with it. */
	private void deleteById(String id) {
		try {
			Map<String, Object> existing = this.restClient.get()
				.uri(pathUri(this.basePath + "/" + id, null))
				.retrieve()
				.body(JSON_MAP);
			if (existing != null && existing.get("sys_id") != null) {
				deleteBySysId((String) existing.get("sys_id"));
			}
		}
		catch (HttpClientErrorException.NotFound ex) {
			// Nothing stored under this id.
		}
	}

	private void deleteBySysId(String sysId) {
		this.restClient.delete()
			.uri("/api/documents/{id}", sysId)
			.retrieve()
			.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
				if (response.getStatusCode().value() != 404) {
					throw new IllegalStateException("Delete of " + sysId + " failed: " + response.getStatusCode());
				}
			})
			.toBodilessEntity();
	}

	private Map<String, Map<String, Object>> readParents(List<String> sysIds) {
		String predicate = sysIds.stream()
			.map(sysId -> "sys_id = '" + OpenArchFilterExpressionConverter.escapeLiteral(sysId) + "'")
			.collect(Collectors.joining(" OR "));
		return query(scopeQuery() + " AND (" + predicate + ")", sysIds.size(), 0).stream()
			.collect(Collectors.toMap(doc -> (String) doc.get("sys_id"), doc -> doc, (a, b) -> a));
	}

	private List<Map<String, Object>> query(String hxql, int limit, int offset) {
		// limit must be positive: /api/query returns no rows for limit 0.
		Map<String, Object> result = this.restClient.post()
			.uri("/api/query")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of("query", hxql, "limit", Math.max(limit, 1), "offset", offset))
			.retrieve()
			.body(JSON_MAP);
		return listOf(result, "documents");
	}

	private void awaitIndex() {
		if (this.waitForIndexing) {
			this.restClient.get()
				.uri("/api/query/waitForFullTextSearchIndexing?refresh=true&timeout={t}", INDEX_WAIT_SECONDS)
				.retrieve()
				.toBodilessEntity();
		}
	}

	private String scopeQuery() {
		return "SELECT * FROM SysFile WHERE cin_sourceId = '"
				+ OpenArchFilterExpressionConverter.escapeLiteral(sourceId()) + "'";
	}

	private String sourceId() {
		return SOURCE_PREFIX + this.collection;
	}

	private static URI pathUri(String absolutePath, String query) {
		String encoded = absolutePath.isEmpty() ? ""
				: java.util.Arrays.stream(absolutePath.substring(1).split("/"))
					.map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
					.collect(Collectors.joining("/"));
		String uri = "/api/documents/path/" + encoded;
		return URI.create(query == null ? uri : uri + "?" + query);
	}

	private static String normalizePath(String path) {
		String trimmed = path.strip();
		if (!trimmed.startsWith("/")) {
			trimmed = "/" + trimmed;
		}
		while (trimmed.length() > 1 && trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		Assert.isTrue(trimmed.length() > 1, "basePath must not be the repository root");
		return trimmed;
	}

	private float[] pad(float[] vector) {
		Assert.isTrue(vector.length <= this.indexDimensions, () -> "The Hyland OpenArch index takes "
				+ this.indexDimensions + " dimensions, the embedding model returned " + vector.length);
		return (vector.length == this.indexDimensions) ? vector : java.util.Arrays.copyOf(vector, this.indexDimensions);
	}

	private static double score(Map<String, Object> hit) {
		Object value = hit.get("sysembed_score");
		return (value instanceof Number number) ? number.doubleValue() : 0.0;
	}

	private static List<Double> toDoubles(float[] vector) {
		List<Double> values = new ArrayList<>(vector.length);
		for (float value : vector) {
			values.add((double) value);
		}
		return values;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> listOf(Map<String, Object> source, String key) {
		Object value = (source == null) ? null : source.get(key);
		return (value instanceof List<?> list) ? (List<Map<String, Object>>) list : List.of();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapOf(Map<String, Object> source, String key) {
		Object value = source.get(key);
		return (value instanceof Map<?, ?> map) ? (Map<String, Object>) map : Map.of();
	}

	public static final class Builder extends AbstractVectorStoreBuilder<Builder> {

		private final RestClient restClient;

		private String basePath = DEFAULT_BASE_PATH;

		private String collection = DEFAULT_COLLECTION;

		private String embeddingType = DEFAULT_EMBEDDING_TYPE;

		private boolean initializeSchema = false;

		private boolean waitForIndexing = true;

		private int indexDimensions = DEFAULT_INDEX_DIMENSIONS;

		private Builder(RestClient restClient, EmbeddingModel embeddingModel) {
			super(embeddingModel);
			Assert.notNull(restClient, "restClient must not be null");
			this.restClient = restClient;
		}

		/** Repository folder that holds the documents of this store. */
		public Builder basePath(String basePath) {
			Assert.hasText(basePath, "basePath must not be empty");
			this.basePath = basePath;
			return this;
		}

		/** Logical collection; several stores can share one {@code basePath}. */
		public Builder collection(String collection) {
			Assert.hasText(collection, "collection must not be empty");
			this.collection = collection;
			return this;
		}

		/**
		 * Embedding type label. One label per embedding model: vectors of different models must
		 * not be compared.
		 */
		public Builder embeddingType(String embeddingType) {
			Assert.hasText(embeddingType, "embeddingType must not be empty");
			this.embeddingType = embeddingType;
			return this;
		}

		/** Creates the {@code basePath} folders on startup. */
		public Builder initializeSchema(boolean initializeSchema) {
			this.initializeSchema = initializeSchema;
			return this;
		}

		/** Waits for the query index after each write. */
		public Builder waitForIndexing(boolean waitForIndexing) {
			this.waitForIndexing = waitForIndexing;
			return this;
		}

		/** Vector length of the engine index. Shorter vectors are padded with zeros. */
		public Builder indexDimensions(int indexDimensions) {
			Assert.isTrue(indexDimensions > 0, "indexDimensions must be positive");
			this.indexDimensions = indexDimensions;
			return this;
		}

		@Override
		public OpenArchVectorStore build() {
			return new OpenArchVectorStore(this);
		}

	}

}
