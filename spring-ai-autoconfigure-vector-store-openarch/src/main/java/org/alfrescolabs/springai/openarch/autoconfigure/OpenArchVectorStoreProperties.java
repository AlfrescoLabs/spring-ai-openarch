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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the Hyland OpenArch vector store.
 */
@ConfigurationProperties(OpenArchVectorStoreProperties.PREFIX)
public class OpenArchVectorStoreProperties {

	public static final String PREFIX = "spring.ai.vectorstore.openarch";

	/** Engine URL. */
	private String url = "http://localhost:8080";

	/** Engine user (HTTP Basic). */
	private String username;

	/** Engine password (HTTP Basic). */
	private String password;

	/** Repository, sent in the HXCS-REPOSITORY header. */
	private String repositoryId = "default";

	/** Repository folder that holds the documents of this store. */
	private String basePath = OpenArchVectorStore.DEFAULT_BASE_PATH;

	/** Logical collection inside the base path. */
	private String collection = OpenArchVectorStore.DEFAULT_COLLECTION;

	/** Embedding type label: use one label per embedding model. */
	private String embeddingType = OpenArchVectorStore.DEFAULT_EMBEDDING_TYPE;

	/** Vector length of the engine index. Shorter vectors are padded with zeros. */
	private int indexDimensions = OpenArchVectorStore.DEFAULT_INDEX_DIMENSIONS;

	/** Create the base path folders on startup. */
	private boolean initializeSchema = false;

	/** Wait for the query index after each write. */
	private boolean waitForIndexing = true;

	public String getUrl() {
		return this.url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getUsername() {
		return this.username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return this.password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getRepositoryId() {
		return this.repositoryId;
	}

	public void setRepositoryId(String repositoryId) {
		this.repositoryId = repositoryId;
	}

	public String getBasePath() {
		return this.basePath;
	}

	public void setBasePath(String basePath) {
		this.basePath = basePath;
	}

	public String getCollection() {
		return this.collection;
	}

	public void setCollection(String collection) {
		this.collection = collection;
	}

	public String getEmbeddingType() {
		return this.embeddingType;
	}

	public void setEmbeddingType(String embeddingType) {
		this.embeddingType = embeddingType;
	}

	public int getIndexDimensions() {
		return this.indexDimensions;
	}

	public void setIndexDimensions(int indexDimensions) {
		this.indexDimensions = indexDimensions;
	}

	public boolean isInitializeSchema() {
		return this.initializeSchema;
	}

	public void setInitializeSchema(boolean initializeSchema) {
		this.initializeSchema = initializeSchema;
	}

	public boolean isWaitForIndexing() {
		return this.waitForIndexing;
	}

	public void setWaitForIndexing(boolean waitForIndexing) {
		this.waitForIndexing = waitForIndexing;
	}

}
