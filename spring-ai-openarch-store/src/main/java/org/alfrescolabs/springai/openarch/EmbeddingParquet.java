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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;

/**
 * Writes embedding rows in the Parquet format that Hyland OpenArch expects for the content of a
 * {@code SysEmbeddings} document.
 */
final class EmbeddingParquet {

	/** Avro schema of an embedding row ({@code com.hyland.hxpr.embeddings.avro.Embedding}). */
	static final Schema SCHEMA = new Schema.Parser().parse("""
			{
			  "type": "record",
			  "name": "Embedding",
			  "namespace": "com.hyland.hxpr.embeddings.avro",
			  "fields": [
			    {"name": "id", "type": "string"},
			    {"name": "type", "type": ["null", "string"], "default": null},
			    {"name": "vector", "type": {"type": "array", "items": "double"}},
			    {"name": "text", "type": ["null", "string"], "default": null},
			    {"name": "blobref", "type": ["null", "string"], "default": null},
			    {"name": "location", "type": ["null", "string"], "default": null}
			  ]
			}
			""");

	record Row(String id, String text, float[] vector) {
	}

	private EmbeddingParquet() {
	}

	/**
	 * Serializes the rows into an in-memory Parquet file.
	 * @param rows rows to write, not empty
	 * @param embeddingType embedding type written in every row; it must match the
	 * {@code _e_<type>} name of the {@code SysEmbeddings} document, because searches that name a
	 * type match on this value
	 * @return the Parquet bytes
	 */
	static byte[] write(List<Row> rows, String embeddingType) {
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("rows must not be empty");
		}
		InMemoryOutputFile file = new InMemoryOutputFile();
		try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(file)
			.withSchema(SCHEMA)
			.withCompressionCodec(CompressionCodecName.SNAPPY)
			.build()) {
			for (Row row : rows) {
				GenericRecord record = new GenericData.Record(SCHEMA);
				record.put("id", row.id());
				record.put("type", embeddingType);
				record.put("vector", toDoubles(row.vector()));
				record.put("text", row.text());
				writer.write(record);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to write embeddings as Parquet", ex);
		}
		return file.toByteArray();
	}

	private static List<Double> toDoubles(float[] vector) {
		List<Double> values = new ArrayList<>(vector.length);
		for (float value : vector) {
			values.add((double) value);
		}
		return values;
	}

	/** Parquet {@link OutputFile} that keeps the file in memory, so no temporary file is needed. */
	static final class InMemoryOutputFile implements OutputFile {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		@Override
		public PositionOutputStream create(long blockSizeHint) {
			return createOrOverwrite(blockSizeHint);
		}

		@Override
		public PositionOutputStream createOrOverwrite(long blockSizeHint) {
			this.bytes.reset();
			return new PositionOutputStream() {

				@Override
				public long getPos() {
					return InMemoryOutputFile.this.bytes.size();
				}

				@Override
				public void write(int b) {
					InMemoryOutputFile.this.bytes.write(b);
				}

				@Override
				public void write(byte[] b, int off, int len) {
					InMemoryOutputFile.this.bytes.write(b, off, len);
				}
			};
		}

		@Override
		public boolean supportsBlockSize() {
			return false;
		}

		@Override
		public long defaultBlockSize() {
			return 0;
		}

		byte[] toByteArray() {
			return this.bytes.toByteArray();
		}

	}

}
