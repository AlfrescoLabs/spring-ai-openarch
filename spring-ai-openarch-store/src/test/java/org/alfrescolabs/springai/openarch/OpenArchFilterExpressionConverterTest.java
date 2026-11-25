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

import org.junit.jupiter.api.Test;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenArchFilterExpressionConverterTest {

	private final OpenArchFilterExpressionConverter converter = new OpenArchFilterExpressionConverter();

	private final FilterExpressionBuilder b = new FilterExpressionBuilder();

	@Test
	void comparisonAndLogic() {
		Filter.Expression expression = b.and(b.eq("country", "BG"), b.gte("year", 2020)).build();
		assertThat(this.converter.convertExpression(expression))
			.isEqualTo("cin_ingestProperties.country = 'BG' AND cin_ingestProperties.year >= 2020");
	}

	@Test
	void groupsAndNotEqual() {
		Filter.Expression expression = b
			.and(b.group(b.or(b.eq("a", "x"), b.ne("b", "y"))), b.lt("c", 3))
			.build();
		assertThat(this.converter.convertExpression(expression)).isEqualTo(
				"(cin_ingestProperties.a = 'x' OR cin_ingestProperties.b != 'y') AND cin_ingestProperties.c < 3");
	}

	@Test
	void inAndNinExpandToOrChains() {
		assertThat(this.converter.convertExpression(b.in("genre", "drama", "comedy").build()))
			.isEqualTo("(cin_ingestProperties.genre = 'drama' OR cin_ingestProperties.genre = 'comedy')");
		assertThat(this.converter.convertExpression(b.nin("genre", "drama").build()))
			.isEqualTo("NOT ((cin_ingestProperties.genre = 'drama'))");
	}

	@Test
	void textParserInput() {
		Filter.Expression expression = new FilterExpressionTextParser().parse("author == 'john' && rating > 4");
		assertThat(this.converter.convertExpression(expression))
			.isEqualTo("cin_ingestProperties.author = 'john' AND cin_ingestProperties.rating > 4");
	}

	@Test
	void notWrapsTheExpression() {
		Filter.Expression expression = new FilterExpressionTextParser().parse("NOT (genre == 'drama')");
		assertThat(this.converter.convertExpression(expression))
			.isEqualTo("NOT ((cin_ingestProperties.genre = 'drama'))");
	}

	@Test
	void literalsUseBackslashEscapes() {
		assertThat(this.converter.convertExpression(b.eq("name", "o'brien\\x").build()))
			.isEqualTo("cin_ingestProperties.name = 'o\\'brien\\\\x'");
	}

	@Test
	void nullChecks() {
		assertThat(this.converter.convertExpression(b.isNull("a").build()))
			.isEqualTo("cin_ingestProperties.a IS NULL");
		assertThat(this.converter.convertExpression(b.isNotNull("a").build()))
			.isEqualTo("cin_ingestProperties.a IS NOT NULL");
	}

	@Test
	void quotedAndDottedKeys() {
		assertThat(this.converter.convertExpression(b.eq("'genre'", "drama").build()))
			.isEqualTo("cin_ingestProperties.genre = 'drama'");
		assertThat(this.converter.convertExpression(b.eq("book.year", 2020).build()))
			.isEqualTo("cin_ingestProperties.book.year = 2020");
	}

	@Test
	void keysThatAreNotIdentifiersAreRejected() {
		for (String key : new String[] { "x = 1) OR (y", "a'b", "a b", "1a", "a-b", "a.", "\"x\" OR 1" }) {
			assertThatThrownBy(() -> this.converter.convertExpression(b.eq(key, "v").build()))
				.as(key)
				.isInstanceOf(IllegalArgumentException.class);
		}
		Filter.Expression quoted = new FilterExpressionTextParser().parse("'x = 1) OR (y' == 'v'");
		assertThatThrownBy(() -> this.converter.convertExpression(quoted))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void valuesThatAreNotNumbersOrBooleansAreQuoted() {
		Object value = new Object() {
			@Override
			public String toString() {
				return "1 OR x = 'y'";
			}
		};
		assertThat(this.converter.convertExpression(
				new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("a"), new Filter.Value(value))))
			.isEqualTo("cin_ingestProperties.a = '1 OR x = \\'y\\''");
		assertThat(this.converter.convertExpression(b.eq("flag", true).build()))
			.isEqualTo("cin_ingestProperties.flag = true");
	}

}
