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
import java.util.regex.Pattern;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.converter.AbstractFilterExpressionConverter;

/**
 * Converts Spring AI filter expressions into HXQL predicates over the document metadata.
 *
 * <p>Metadata lives in {@code cin_ingestProperties}, so the key {@code author} becomes
 * {@code cin_ingestProperties.author}. HXQL escapes string literals with a backslash: quote
 * doubling is rejected with HTTP 400. {@code IN} and {@code NIN} are expanded into {@code OR}
 * chains. {@code ISNULL} and {@code ISNOTNULL} become {@code IS NULL} and {@code IS NOT NULL}.</p>
 *
 * <p>Keys are written into the query text, so they must be plain identifiers (letters, digits and
 * underscores, with dots between parts). Any other key is rejected: a key such as
 * {@code "x = 1) OR (y"} would otherwise change the query and could leave the collection scope.
 * Numbers and booleans are written as they are; every other value is written as an escaped string
 * literal.</p>
 */
public class OpenArchFilterExpressionConverter extends AbstractFilterExpressionConverter {

	static final String METADATA_PREFIX = "cin_ingestProperties.";

	private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

	@Override
	protected void doExpression(Expression expression, StringBuilder context) {
		ExpressionType type = expression.type();
		switch (type) {
			case AND, OR -> {
				convertOperand(expression.left(), context);
				context.append(' ').append(type.name()).append(' ');
				convertOperand(expression.right(), context);
			}
			case IN -> doIn(expression, context);
			case NIN -> {
				context.append("NOT (");
				doIn(expression, context);
				context.append(')');
			}
			case ISNULL -> {
				convertOperand(expression.left(), context);
				context.append(" IS NULL");
			}
			case ISNOTNULL -> {
				convertOperand(expression.left(), context);
				context.append(" IS NOT NULL");
			}
			default -> {
				convertOperand(expression.left(), context);
				context.append(' ').append(operator(type)).append(' ');
				convertOperand(expression.right(), context);
			}
		}
	}

	private void doIn(Expression expression, StringBuilder context) {
		Filter.Key key = (Filter.Key) expression.left();
		Object value = ((Filter.Value) expression.right()).value();
		List<?> values = (value instanceof List<?> list) ? list : List.of(value);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("IN needs at least one value");
		}
		context.append('(');
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				context.append(" OR ");
			}
			doKey(key, context);
			context.append(" = ");
			doSingleValue(normalizeDateString(values.get(i)), context);
		}
		context.append(')');
	}

	@Override
	protected void doNot(Expression expression, StringBuilder context) {
		context.append("NOT (");
		convertOperand(expression.left(), context);
		context.append(')');
	}

	@Override
	protected void doKey(Filter.Key key, StringBuilder context) {
		context.append(METADATA_PREFIX).append(validKey(key.key()));
	}

	@Override
	protected void doSingleValue(Object value, StringBuilder context) {
		if (value instanceof Number || value instanceof Boolean) {
			context.append(value);
		}
		else {
			context.append('\'').append(escapeLiteral(String.valueOf(value))).append('\'');
		}
	}

	@Override
	protected void doStartGroup(Filter.Group group, StringBuilder context) {
		context.append('(');
	}

	@Override
	protected void doEndGroup(Filter.Group group, StringBuilder context) {
		context.append(')');
	}

	/** Returns the key without outer quotes, or throws if it is not a plain identifier. */
	private String validKey(String key) {
		String name = hasOuterQuotes(key) ? removeOuterQuotes(key) : key;
		if (name == null || !KEY.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid filter key: " + key);
		}
		return name;
	}

	/** Escapes an HXQL string literal: the backslash first, then the quote. */
	static String escapeLiteral(String value) {
		return value.replace("\\", "\\\\").replace("'", "\\'");
	}

	private static String operator(ExpressionType type) {
		return switch (type) {
			case EQ -> "=";
			case NE -> "!=";
			case GT -> ">";
			case GTE -> ">=";
			case LT -> "<";
			case LTE -> "<=";
			default -> throw new UnsupportedOperationException("Unsupported filter operator: " + type);
		};
	}

}
