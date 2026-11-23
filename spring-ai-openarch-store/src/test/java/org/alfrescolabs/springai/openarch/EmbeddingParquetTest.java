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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingParquetTest {

	@Test
	@SuppressWarnings("unchecked")
	void writesReadableRows(@TempDir Path dir) throws Exception {
		byte[] bytes = EmbeddingParquet.write(
				List.of(new EmbeddingParquet.Row("doc-1", "hello", new float[] { 0.5f, -1f })), "spring-ai");
		Path file = Files.write(dir.resolve("e.parquet"), bytes);

		try (ParquetReader<GenericRecord> reader = AvroParquetReader
			.<GenericRecord>builder(new LocalInputFile(file))
			.build()) {
			GenericRecord row = reader.read();
			assertThat(row.get("id").toString()).isEqualTo("doc-1");
			assertThat(row.get("type").toString()).isEqualTo("spring-ai");
			assertThat(row.get("text").toString()).isEqualTo("hello");
			assertThat((List<Object>) row.get("vector")).containsExactly(0.5, -1.0);
			assertThat(reader.read()).isNull();
		}
	}

}
