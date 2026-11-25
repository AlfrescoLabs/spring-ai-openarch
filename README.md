# Spring AI Hyland OpenArch

A [Spring AI](https://docs.spring.io/spring-ai/reference/) `VectorStore` backed by [Hyland OpenArch](https://github.com/Hyland/OpenArch).

Hyland OpenArch is an open source content repository. It stores documents with metadata and permissions, and it indexes vectors for semantic search. With this project, a Spring AI application can use it as a vector store for RAG, with the standard `VectorStore` API.

> This is an AlfrescoLabs project. It is not an official Hyland product, and Hyland does not officially support it.

**Status:** proof of concept. No release is published yet. Tested with Spring AI 2.0.0, Spring Boot 4.0.7 and Hyland OpenArch 1.0.90.

## Modules

| Module | What it contains |
| --- | --- |
| `spring-ai-openarch-store` | `OpenArchVectorStore` and the filter converter |
| `spring-ai-autoconfigure-vector-store-openarch` | Spring Boot auto-configuration |
| `spring-ai-starter-vector-store-openarch` | Starter: store plus auto-configuration |

## Requirements

- Java 17 or later.
- A running Hyland OpenArch engine. To start one for tests, see [Build](#build).
- An `EmbeddingModel`. The engine indexes vectors of 1024 dimensions. Models with fewer dimensions also work, because the store pads vectors with zeros (see [Vector size](#vector-size)).

## Spring Boot

Build the project with `mvn install`, then add the starter:

```xml
<dependency>
    <groupId>org.alfrescolabs.springai</groupId>
    <artifactId>spring-ai-starter-vector-store-openarch</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure the engine:

```yaml
spring:
  ai:
    vectorstore:
      openarch:
        url: http://localhost:8080
        username: admin
        password: password
        collection: books
        embedding-type: mxbai-embed-large
        initialize-schema: true
```

Then use the store:

```java
@Service
class Library {

    private final VectorStore vectorStore;

    Library(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    List<Document> findRecipes() {
        vectorStore.add(List.of(
                new Document("Knead the dough for ten minutes, then bake at 220 C.", Map.of("topic", "cooking")),
                new Document("The invoice is due within 30 days.", Map.of("topic", "finance"))));

        return vectorStore.similaritySearch(SearchRequest.builder()
                .query("How do I make bread at home?")
                .filterExpression("topic == 'cooking'")
                .topK(3)
                .build());
    }
}
```

### Properties

All properties start with `spring.ai.vectorstore.openarch`.

| Property | Default | Description |
| --- | --- | --- |
| `url` | `http://localhost:8080` | Engine URL |
| `username`, `password` | none | HTTP Basic credentials |
| `repository-id` | `default` | Repository, sent in the `HXCS-REPOSITORY` header |
| `base-path` | `/spring-ai` | Repository folder that holds the documents |
| `collection` | `default` | Logical collection. Several stores can share one base path. |
| `embedding-type` | `spring-ai` | Label of the embedding model. Use one label per model. |
| `index-dimensions` | `1024` | Vector size of the engine index |
| `initialize-schema` | `false` | Create the base path folders on startup |
| `wait-for-indexing` | `true` | Wait for the search index after each write |

The store is active when `spring.ai.vectorstore.type` is `openarch` or is not set.

## Without Spring Boot

```java
RestClient restClient = OpenArchVectorStore.restClient("http://localhost:8080", "admin", "password", "default");

OpenArchVectorStore store = OpenArchVectorStore.builder(restClient, embeddingModel)
        .collection("books")
        .embeddingType("mxbai-embed-large")
        .initializeSchema(true)
        .build();
store.afterPropertiesSet();
```

## How documents are stored

Each Spring AI `Document` becomes one `SysFile` document under the base path, named after the document id:

- `cin_id` holds the document id.
- `cin_sourceId` holds `spring-ai:<collection>`. Every query is limited to this value.
- `cin_ingestProperties` holds the metadata.

The vector and the text go into a child document of type `SysEmbeddings`, named `_e_<embedding-type>`. Its content is a Parquet file with one row. Hyland OpenArch indexes that row for vector search.

Adding a document with an existing id replaces it. Deleting a document also deletes its `SysEmbeddings` child.

## Filters

Filter expressions are translated to HXQL, the query language of Hyland OpenArch, on the metadata fields. Supported: `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `nin`, `&&`, `||`, `NOT`, `isNull`, `isNotNull` and groups. `in` is expanded to `OR`. Null checks become `IS NULL` and `IS NOT NULL`.

Keys must be plain identifiers: letters, digits and underscores, with dots between parts. Other keys are rejected, because a key is written into the query text. String values are escaped. Numbers and booleans are written as they are, and any other value is written as an escaped string.

## Scores

The engine ranks by Euclidean distance. It returns `score = 1 / (1 + d2)`, where `d2` is the squared distance. The store sets this value as the document score, and `d2` as the `distance` metadata.

This scale is different from cosine similarity. An identical vector scores 1.0. Two orthogonal unit vectors score 0.33. With `ai/mxbai-embed-large`, good matches score about 0.55 to 0.6. Choose `similarityThreshold` on this scale: a value near 0.5 is a good start, while 0.7 or 0.8 can return nothing.

## Vector size

The engine index takes vectors of exactly 1024 dimensions. The store pads shorter vectors with zeros. Zero padding does not change distances, so the ranking stays the same. Vectors longer than `index-dimensions` are rejected.

Do not mix embedding models in one collection: vectors from different models cannot be compared. Use a different `embedding-type` and `collection` for each model.

## Limitations

- Each new document costs five HTTP calls: a lookup for an existing id, the document, two calls to upload the Parquet file, and the child. A replace costs one more (the delete). Each `add` or `delete` call also waits once for the index when `wait-for-indexing` is on. Bulk loads are slow: on a laptop, 1000 documents in 10 `add` calls took about 5 minutes (about 3 documents per second), with `wait-for-indexing` on or off.
- Writes are not atomic. A replace deletes the old document first, so a failed call can lose the old version or leave a document with no vector.
- Writing Parquet needs `hadoop-common`, which is a large dependency.
- The search does not use the permissions of Hyland OpenArch yet: every document of the collection is visible to the configured user.
- The examples use `admin`, the default user of the engine image. Admin users skip the permission filter of the engine. Outside tests, use a non-admin account with rights on the base path (see [Non-admin user](#non-admin-user)).

## Build

You need Java 17 or later, Maven and Docker.

```bash
# Unit tests
mvn verify

# Integration tests against a real engine
docker compose -f docker/compose.yaml up -d --wait
OPENARCH_URL=http://localhost:8080 mvn verify
docker compose -f docker/compose.yaml down
```

`docker/compose.yaml` starts Hyland OpenArch with MongoDB and OpenSearch, for tests only. Its users come from `docker/users.yml`: `admin` / `password` (administrator) and `springai` / `springai` (non-admin). Set `OPENARCH_PORT` if port 8080 is in use. The end-to-end test also needs an embedding model; see [CONTRIBUTING.md](CONTRIBUTING.md).

### Non-admin user

A non-admin user has no rights on the root folder, so an admin creates the base path and gives the user access to it. With the test stack:

```bash
curl -u admin:password -H 'HXCS-REPOSITORY: default' -H 'Content-Type: application/json' \
  -X POST http://localhost:8080/api/documents/path/ \
  -d '{"sys_primaryType":"SysFolder","sys_name":"spring-ai","sys_acl":[{"user":{"id":"315efe3b-8001-49b0-b653-6492b3bdfd13"},"permission":"ReadWrite","granted":true}]}'

OPENARCH_URL=http://localhost:8080 OPENARCH_USER=springai OPENARCH_PASSWORD=springai mvn verify
```

The id is the id of `springai` in `docker/users.yml`. With `initialize-schema`, the store only reads folders that exist and creates the missing ones.

## License

[Apache License 2.0](LICENSE).
