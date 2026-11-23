/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.query.postgresql.parser;

import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLSyntaxException;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.grammar.PostgreSQLLexer;
import com.arcadedb.query.postgresql.grammar.PostgreSQLParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * The entry point of the parser of the {@code postgresql} language: the text of one statement in, the syntax tree out.
 *
 * <p>Two refusals, in the order PostgreSQL's own parser would meet them. A text that is not PostgreSQL syntax is a
 * {@link PostgreSQLSyntaxException} (SQLSTATE {@code 42601}) at the first token that cannot continue the statement. A text that
 * is PostgreSQL syntax outside the subset is a {@link PostgreSQLFeatureNotSupportedException} ({@code 0A000}) at the first word
 * of the construct. When both are present the one that comes first in the text wins, because the grammar does not read past a
 * construct it does not support.
 *
 * <p>The parser takes exactly one statement, with or without the final semicolon, and the empty statement.
 */
public final class StatementParser {
  private StatementParser() {
  }

  /**
   * @param text the statement
   *
   * @return the syntax tree: a {@code SelectStatement}, an {@code ExplainStatement} or an {@code EmptyStatement}
   *
   * @throws PostgreSQLSyntaxException               when the text is not PostgreSQL syntax
   * @throws PostgreSQLFeatureNotSupportedException when the text is PostgreSQL syntax the subset does not cover
   */
  public static Statement parse(final String text) {
    final ThrowingListener errors = new ThrowingListener();

    final PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(text));
    lexer.removeErrorListeners();
    lexer.addErrorListener(errors);

    final CommonTokenStream stream = new CommonTokenStream(lexer);
    final PostgreSQLParser parser = new PostgreSQLParser(stream);
    parser.removeErrorListeners();
    final UnsupportedCollector collector = new UnsupportedCollector(parser.getRuleNames());
    errors.collector = collector;
    parser.addParseListener(collector);
    parser.addErrorListener(errors);

    final PostgreSQLParser.RootContext root = parser.root();

    // what PostgreSQL rejects after it has read the text is a syntax error too, and comes before a construct it does not support
    StatementChecks.run(root, stream);

    // the text is PostgreSQL syntax; if a construct outside the subset came in it, say so
    if (collector.first != null)
      throw Diagnostics.refusal(collector.firstName, collector.first);

    return new AstBuilder().build(root);
  }

  /**
   * Remembers the first construct that a rule named {@code unsupported...} matched, in the order of the text. A rule that ended
   * with an error does not count: a text that is not PostgreSQL syntax is not a construct of PostgreSQL.
   */
  private static final class UnsupportedCollector implements ParseTreeListener {
    private final String[]          ruleNames;
    private       ParserRuleContext first;
    private       String            firstName;

    private UnsupportedCollector(final String[] ruleNames) {
      this.ruleNames = ruleNames;
    }

    @Override
    public void exitEveryRule(final ParserRuleContext context) {
      if (context.exception != null)
        return;
      final String name = ruleNames[context.getRuleIndex()];
      if (!name.startsWith("unsupported"))
        return;
      if (first == null || context.getStart().getTokenIndex() < first.getStart().getTokenIndex()) {
        first = context;
        firstName = name;
      }
    }

    @Override
    public void enterEveryRule(final ParserRuleContext context) {
    }

    @Override
    public void visitTerminal(final TerminalNode node) {
    }

    @Override
    public void visitErrorNode(final ErrorNode node) {
    }
  }

  /** Turns the first error of the lexer or the parser into the exception of the language. */
  private static final class ThrowingListener extends BaseErrorListener {
    private UnsupportedCollector collector;

    @Override
    public void syntaxError(final Recognizer<?, ?> recognizer, final Object offendingSymbol, final int line,
        final int charPositionInLine, final String message, final RecognitionException exception) {
      // a construct outside the subset that was read before the error is what PostgreSQL would have accepted
      if (collector != null && collector.first != null)
        throw Diagnostics.refusal(collector.firstName, collector.first);

      if (offendingSymbol instanceof Token token)
        throw Diagnostics.syntaxError(token);
      throw new PostgreSQLSyntaxException(
          "syntax error (line " + line + ", column " + (charPositionInLine + 1) + "): " + message);
    }
  }
}
