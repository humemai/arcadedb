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

import com.arcadedb.query.postgresql.PostgreSQLErrors;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLSyntaxException;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.grammar.PostgreSQLLexer;
import com.arcadedb.query.postgresql.grammar.PostgreSQLParser;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
    final Statement fast = parseFast(text);
    return fast != null ? fast : parseFull(text);
  }

  /**
   * The first of two stages (fork issue #92, the strategy the ANTLR documentation gives for a grammar with a costly prediction):
   * the parser predicts with SLL, which does not make the full-context closure of LL, and stops at the first error with no message.
   * A text that passes this stage gives the tree that {@link #parseFull} gives (SLL differs from LL only where it reports a conflict
   * that LL resolves, and then it ends in an error here). Every other text, a text that is not PostgreSQL, one outside the subset,
   * and one that a later check refuses, answers {@code null}: {@link #parseFull} reads it again and says what it says.
   */
  static Statement parseFast(final String text) {
    try {
      final PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(text));
      lexer.removeErrorListeners();
      lexer.addErrorListener(new BailListener());
      final CommonTokenStream stream = new CommonTokenStream(lexer);
      final PostgreSQLParser parser = new PostgreSQLParser(stream);
      parser.removeErrorListeners();
      parser.setErrorHandler(new BailErrorStrategy());
      parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
      final UnsupportedCollector collector = new UnsupportedCollector(parser.getRuleNames());
      parser.addParseListener(collector);
      final PostgreSQLParser.RootContext root = parser.root();
      if (collector.first != null)
        return null;
      StatementChecks.run(root, stream);
      return new AstBuilder().build(root);
    } catch (final RuntimeException e) {
      return null;
    }
  }

  /** Any error of the lexer or the parser ends the first stage. */
  private static final class BailListener extends BaseErrorListener {
    @Override
    public void syntaxError(final Recognizer<?, ?> recognizer, final Object offendingSymbol, final int line, final int charPositionInLine,
        final String message, final RecognitionException exception) {
      throw new ParseCancellationException(message);
    }
  }

  /** The full parse, with the messages and the positions of the errors: what {@link #parse} did before the first stage existed. */
  static Statement parseFull(final String text) {
    try {
      return readFull(text);
    } catch (final PostgreSQLSyntaxException | PostgreSQLFeatureNotSupportedException e) {
      throw namingTheParameters(e, text);
    }
  }

  private static Statement readFull(final String text) {
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

  /** What stands before a place where an expression begins: a {@code ?} there is meant as a parameter. */
  private static final Set<String> BEFORE_AN_EXPRESSION = Set.of("(", ",", "=", "<", ">", "<=", ">=", "<>", "!=", "+", "-", "*",
      "/", "%", "^", "||", "SELECT", "DISTINCT", "WHERE", "AND", "OR", "NOT", "ON", "HAVING", "BY", "LIKE", "ILIKE", "BETWEEN", "LIMIT",
      "OFFSET", "CASE", "WHEN", "THEN", "ELSE", "RETURNING");

  /** A {@code ?} written against the operator before it, which the scanner of PostgreSQL reads as one operator: {@code pid=?}. */
  private static final Pattern GLUED_QUESTION_MARK = Pattern.compile("(=|<>|!=|<=|>=|<|>|\\+|-|\\*|/|%|\\|\\|)\\?");

  /**
   * The error of a statement that failed at or after a {@code ?} written where a parameter stands, as in JDBC and in the {@code sql}
   * language, says how this language writes parameters (fork issue #98). The statement is refused either way, with the same
   * SQLSTATE. Any other error, one that comes before such a {@code ?} included, is returned as it is.
   */
  private static RuntimeException namingTheParameters(final RuntimeException error, final String text) {
    final Token mark = questionMarkParameter(text);
    if (mark == null)
      return error;
    final int position = PostgreSQLErrors.position(error, text);
    // the scanner and PostgreSQL both count characters: the position from 1, the index of the token from 0
    if (position != 0 && position <= mark.getStartIndex())
      return error;
    final String message = "A ? parameter is not supported by the postgresql language: write the parameters as $1, $2 and so on ("
        + Diagnostics.position(mark) + ")";
    return error instanceof PostgreSQLSyntaxException ? new PostgreSQLSyntaxException(message)
        : new PostgreSQLFeatureNotSupportedException(message);
  }

  /**
   * The first {@code ?} of the statement that stands where an expression begins, {@code null} when there is none. After an
   * expression {@code ?} is an operator of PostgreSQL ({@code address ? 'city'}).
   */
  private static Token questionMarkParameter(final String text) {
    if (text.indexOf('?') < 0)
      return null;
    final PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(text));
    // the error of the statement is reported by the caller
    lexer.removeErrorListeners();
    String previous = null;
    for (final Token token : lexer.getAllTokens()) {
      if (token.getChannel() != Token.DEFAULT_CHANNEL)
        continue;
      final String word = token.getText();
      if ("?".equals(word) && (previous == null || BEFORE_AN_EXPRESSION.contains(previous))
          || GLUED_QUESTION_MARK.matcher(word).matches())
        return token;
      previous = word.toUpperCase(Locale.ROOT);
    }
    return null;
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
      // UESCAPE has to be followed by a string, whatever the construct around it
      if (offendingSymbol instanceof Token offending && recognizer instanceof PostgreSQLParser parser) {
        int index = offending.getTokenIndex() - 1;
        while (index > 0 && parser.getTokenStream().get(index).getChannel() != Token.DEFAULT_CHANNEL)
          --index;
        if (index >= 0 && parser.getTokenStream().get(index).getType() == PostgreSQLLexer.UESCAPE)
          throw Diagnostics.syntaxError("UESCAPE must be followed by a simple string literal", offending);
      }

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
