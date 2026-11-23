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
 * chains. {@code ISNULL} and {@code ISNOTNULL} are not supported.</p>
 */
public class OpenArchFilterExpressionConverter extends AbstractFilterExpressionConverter {

	static final String METADATA_PREFIX = "cin_ingestProperties.";

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
			case ISNULL, ISNOTNULL -> throw new UnsupportedOperationException(
					"Hyland OpenArch filters do not support " + type);
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
		String name = hasOuterQuotes(key.key()) ? removeOuterQuotes(key.key()) : key.key();
		context.append(METADATA_PREFIX).append(name);
	}

	@Override
	protected void doSingleValue(Object value, StringBuilder context) {
		if (value instanceof String text) {
			context.append('\'').append(escapeLiteral(text)).append('\'');
		}
		else {
			context.append(value);
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
