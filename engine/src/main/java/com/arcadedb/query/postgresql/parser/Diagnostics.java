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
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.grammar.PostgreSQLParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The positions and the messages of the two refusals of the parser: a syntax error ({@code 42601}) and a construct of
 * PostgreSQL that the subset does not cover ({@code 0A000}). Both name the line and the column, counted from 1.
 */
final class Diagnostics {
  /** What the construct matched by a rule of the parser grammar whose name starts with {@code unsupported} is called. */
  private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
      Map.entry("unsupportedExplainOption", "EXPLAIN with an option"),
      Map.entry("unsupportedDistinctOn", "DISTINCT ON"),
      Map.entry("unsupportedInto", "SELECT INTO"),
      Map.entry("unsupportedWindowClause", "A WINDOW clause"),
      Map.entry("unsupportedLockingClause", "A locking clause (FOR UPDATE and the others)"),
      Map.entry("unsupportedGroupByQuantifier", "GROUP BY ALL and GROUP BY DISTINCT"),
      Map.entry("unsupportedGroupingSet", "ROLLUP, CUBE, GROUPING SETS and the empty grouping set"),
      Map.entry("unsupportedSortUsing", "ORDER BY with USING"),
      Map.entry("unsupportedLimitComma", "LIMIT with two values"),
      Map.entry("unsupportedFetchClause", "FETCH FIRST"),
      Map.entry("unsupportedJoinUsingAlias", "A join alias (USING ... AS name)"),
      Map.entry("unsupportedJoinAlias", "An alias on a parenthesised join"),
      Map.entry("unsupportedTablesample", "TABLESAMPLE"),
      Map.entry("unsupportedColumnAliases", "A column alias list"),
      Map.entry("unsupportedTableRef", "A subquery, a table function or LATERAL in FROM"),
      Map.entry("unsupportedRecursive", "WITH RECURSIVE"),
      Map.entry("unsupportedWithWrite", "A WITH clause on INSERT, UPDATE, DELETE or MERGE"),
      Map.entry("unsupportedCteBody", "A data-modifying statement in WITH"),
      Map.entry("unsupportedCteClause", "SEARCH and CYCLE in WITH"),
      Map.entry("unsupportedInSubquery", "A subquery"),
      Map.entry("unsupportedPrefixOperator", "A prefix operator"),
      Map.entry("unsupportedSymmetric", "BETWEEN SYMMETRIC"),
      Map.entry("unsupportedQuantifiedComparison", "A comparison with ANY, SOME or ALL"),
      Map.entry("unsupportedIndirection", "A field selection or a subscript"),
      Map.entry("unsupportedRowComma", "A row constructor"),
      Map.entry("unsupportedSubscript", "A subscript"),
      Map.entry("unsupportedAggregateOrder", "ORDER BY inside a function call"),
      Map.entry("unsupportedNamedArg", "A named argument"),
      Map.entry("unsupportedVariadic", "VARIADIC"),
      Map.entry("unsupportedSimilarEscape", "SUBSTRING with SIMILAR"),
      Map.entry("unsupportedBitString", "A bit string constant"),
      Map.entry("unsupportedUnicodeString", "A Unicode escape string"),
      Map.entry("unsupportedSetof", "SETOF"),
      Map.entry("unsupportedCollate", "COLLATE"),
      Map.entry("unsupportedAtTimeZone", "AT TIME ZONE"),
      Map.entry("unsupportedStarArg", "A whole-row reference such as t.*"),
      Map.entry("unsupportedUnicodeIdent", "A Unicode escape identifier"),
      Map.entry("unsupportedTableAlias", "An alias after a subquery or a function in FROM"),
      Map.entry("unsupportedMultiAssign", "A multiple-column assignment"),
      Map.entry("unsupportedUpdateFrom", "UPDATE with FROM"),
      Map.entry("unsupportedDeleteUsing", "DELETE with USING"),
      Map.entry("unsupportedCurrentOf", "WHERE CURRENT OF"),
      Map.entry("unsupportedTransactionMode", "A transaction mode (ISOLATION LEVEL, READ ONLY, DEFERRABLE)"),
      Map.entry("unsupportedTransactionChain", "AND CHAIN"),
      Map.entry("unsupportedTableKind", "A temporary or unlogged table"),
      Map.entry("unsupportedExplainedCreate", "CREATE"),
      Map.entry("unsupportedTableForm", "This form of CREATE TABLE (OF, PARTITION OF, AS or a table option)"),
      Map.entry("unsupportedTableOption",
          "A table option (INHERITS, PARTITION BY, USING, WITH, ON COMMIT or TABLESPACE)"),
      Map.entry("unsupportedTableElement", "LIKE in CREATE TABLE"),
      Map.entry("unsupportedColumnConstraint",
          "This column option (CHECK, GENERATED, COLLATE, storage, a deferrable or unenforced constraint and the like)"),
      Map.entry("unsupportedReferenceOption", "MATCH in a foreign key"),
      Map.entry("unsupportedTableConstraint", "This table constraint (CHECK, EXCLUDE, NOT NULL, NULLS NOT DISTINCT or USING INDEX)"),
      Map.entry("unsupportedConstraintOption", "An option of a table constraint (INCLUDE, WITH, DEFERRABLE and the like)"),
      Map.entry("unsupportedConcurrently", "CREATE INDEX CONCURRENTLY"),
      Map.entry("unsupportedIndexExpression", "An index on an expression"),
      Map.entry("unsupportedIndexElementOption",
          "This option of an index column (COLLATE, DESC, NULLS, a qualified operator class or its parameters)"),
      Map.entry("unsupportedIndexParameter", "An index parameter that is not a whole number"),
      Map.entry("unsupportedIndexOption", "This clause of CREATE INDEX (INCLUDE, NULLS NOT DISTINCT, TABLESPACE or WHERE)"));

  private static final Set<Integer> NOT_WRITES = Set.of(PostgreSQLParser.WITH, PostgreSQLParser.VALUES, PostgreSQLParser.TABLE,
      PostgreSQLParser.LPAREN);

  private Diagnostics() {
  }

  static SourcePosition position(final Token token) {
    return new SourcePosition(token.getLine(), token.getCharPositionInLine() + 1, token.getStartIndex());
  }

  /** The error PostgreSQL reports for a token that cannot continue the statement. */
  static PostgreSQLSyntaxException syntaxError(final Token token) {
    final String what = token.getType() == Token.EOF ? "syntax error at end of input"
        : "syntax error at or near \"" + token.getText() + "\"";
    return new PostgreSQLSyntaxException(what + " (" + position(token) + ")");
  }

  /** A syntax error with a message of its own, such as PostgreSQL's zero-length delimited identifier. */
  static PostgreSQLSyntaxException syntaxError(final String message, final Token token) {
    return new PostgreSQLSyntaxException(message + " (" + position(token) + ")");
  }

  static PostgreSQLFeatureNotSupportedException notSupported(final String what, final Token token) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language (" + position(token) + ")");
  }

  /**
   * The refusal for the construct that a rule named {@code unsupported...} matched. A statement that the language does not run
   * (DDL, a session or a utility statement) says how to run it.
   */
  static PostgreSQLFeatureNotSupportedException refusal(final String ruleName, final ParserRuleContext marker) {
    final Token start = marker.getStart();
    final String what = DESCRIPTIONS.containsKey(ruleName) ? DESCRIPTIONS.get(ruleName) : words(ruleName, marker);
    String message = what + " is not supported by the postgresql language (" + position(start) + ")";
    if ("unsupportedStatement".equals(ruleName) && !NOT_WRITES.contains(start.getType()))
      message += ": the language runs SELECT, INSERT, UPDATE, DELETE, CREATE TABLE, CREATE INDEX and the transaction statements,"
          + " use the sql language for it (the {sql} marker on the wire)";
    return new PostgreSQLFeatureNotSupportedException(message);
  }

  /** The words of the construct as written, in upper case: {@code RIGHT OUTER JOIN}, {@code IS NOT DISTINCT FROM}. */
  private static String words(final String ruleName, final ParserRuleContext marker) {
    final List<String> words = new ArrayList<>();
    if ("unsupportedPrimary".equals(ruleName) && marker.getStart().getType() == PostgreSQLParser.LPAREN)
      return "A subquery";
    if (("unsupportedStatement".equals(ruleName) || "unsupportedInsertSource".equals(ruleName))
        && marker.getStart().getType() == PostgreSQLParser.LPAREN)
      return "A parenthesised query";
    collect(marker, words);
    if (words.isEmpty())
      return "A construct of PostgreSQL";
    final String joined = String.join(" ", words);
    return "unsupportedJoinKind".equals(ruleName) ? joined + " JOIN" : joined;
  }

  /** Adds the words from the start of the construct up to the first token that is not a word; @return false once it stopped. */
  private static boolean collect(final ParseTree tree, final List<String> words) {
    if (tree instanceof TerminalNode terminal) {
      final String text = terminal.getText();
      if (text.isEmpty() || !Character.isLetter(text.charAt(0)))
        return false;
      words.add(text.toUpperCase(Locale.ROOT));
      return true;
    }
    for (int i = 0; i < tree.getChildCount(); i++)
      if (!collect(tree.getChild(i), words))
        return false;
    return true;
  }
}
