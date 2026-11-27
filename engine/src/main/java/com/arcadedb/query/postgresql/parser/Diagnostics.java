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

import com.arcadedb.exception.CommandParsingException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLHints;
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
      Map.entry("unsupportedQuantifiedComparison", "ANY, SOME or ALL with an operator that is not a comparison"),
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
      Map.entry("unsupportedParameterNamespace", "An index parameter name with a namespace"),
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
   * The refusal for the construct that a rule named {@code unsupported...} matched, with the hint of what to write instead (fork
   * issue #241). A form that PostgreSQL refuses too, {@code LIMIT 1, 2} and a name in backticks, is {@code 42601} as there.
   */
  static CommandParsingException refusal(final String ruleName, final ParserRuleContext marker) {
    final Token start = marker.getStart();
    if ("unsupportedLimitComma".equals(ruleName))
      return new PostgreSQLSyntaxException("LIMIT #,# syntax is not supported (" + position(start) + ")",
          "Use separate LIMIT and OFFSET clauses: LIMIT 1, 2 is LIMIT 2 OFFSET 1");
    if ("unsupportedPrefixOperator".equals(ruleName) && start.getText().contains("`"))
      return new PostgreSQLSyntaxException("syntax error at or near \"`\" (" + position(start) + ")",
          "PostgreSQL has no backticks: write a name in double quotes, as in \"name\"");
    final String what = DESCRIPTIONS.containsKey(ruleName) ? DESCRIPTIONS.get(ruleName) : words(ruleName, marker);
    final String message = what + " is not supported by the postgresql language (" + position(start) + ")";
    if ("unsupportedStatement".equals(ruleName)) {
      final List<String> statement = new ArrayList<>();
      tokens(marker.getParent() instanceof ParserRuleContext command ? command : marker, statement);
      return new PostgreSQLFeatureNotSupportedException(message, StatementHints.of(statement, StatementHints.AS_WRITTEN),
          statement);
    }
    return new PostgreSQLFeatureNotSupportedException(message, hint(ruleName, what, start.getText().toUpperCase(Locale.ROOT)));
  }

  private static void tokens(final ParseTree tree, final List<String> tokens) {
    if (tree instanceof TerminalNode terminal) {
      if (terminal.getSymbol().getType() != Token.EOF && !";".equals(terminal.getText()))
        tokens.add(terminal.getText());
      return;
    }
    for (int i = 0; i < tree.getChildCount(); i++)
      tokens(tree.getChild(i), tokens);
  }

  /**
   * @param what  the construct as the message names it
   * @param first the first word of the construct, in upper case
   */
  private static String hint(final String ruleName, final String what, final String first) {
    return switch (ruleName) {
      case "unsupportedColumnConstraint" -> "GENERATED".equals(first) ?
          "Leave GENERATED out. For generated keys declare no id column: the table then has a record id as id, which"
              + " INSERT ... RETURNING id returns" :
          "Leave " + first + " and what belongs to it out, and check in your program what it would have checked";
      case "unsupportedTableConstraint", "unsupportedConstraintOption" ->
          "Leave " + first + " and what belongs to it out; UNIQUE (columns) and PRIMARY KEY (columns) keep values unique";
      case "unsupportedExplainOption" -> "Write EXPLAIN SELECT ... with no option: it plans the statement and runs nothing";
      case "unsupportedDistinctOn" -> "Number the rows in a WITH query with row_number() OVER (PARTITION BY ... ORDER BY ...) and"
          + " keep the rows numbered 1";
      case "unsupportedInto" -> "Create the table with CREATE TABLE, then write INSERT INTO name SELECT ...";
      case "unsupportedWindowClause" -> "Write the window inside the OVER (...) of each function";
      case "unsupportedLockingClause" -> "Leave the clause out: no row is locked. Of two transactions that change one row the later"
          + " commit fails with SQLSTATE 40001, run it again";
      case "unsupportedGroupByQuantifier" -> "List the columns after GROUP BY";
      case "unsupportedGroupingSet" -> "Write one SELECT for each grouping and put them together with UNION ALL";
      case "unsupportedSortUsing" -> "Write ASC or DESC";
      case "unsupportedFetchClause" -> "Write LIMIT n, with OFFSET m to skip rows";
      case "unsupportedJoinUsingAlias", "unsupportedJoinAlias" -> "Leave the alias out and name the columns by their tables";
      case "unsupportedColumnAliases", "unsupportedTableAlias" -> "Name a query in a WITH clause, with its columns named by AS";
      case "unsupportedTableRef" -> "Name a subquery in a WITH clause and read it by that name: WITH s AS (SELECT ...) SELECT ..."
          + " FROM s. There are no table functions and no LATERAL yet";
      case "unsupportedRecursive" -> "Write paths of any length in openCypher, where a table is a vertex type and a link table is an"
          + " edge type, as in {opencypher}MATCH (a:person)-[:knows*]->(b:person) RETURN b.name";
      case "unsupportedWithWrite", "unsupportedCteBody" ->
          "Run the query and the write as two statements in one transaction, with RETURNING to read what a write changed";
      case "unsupportedInSubquery" -> "Name the query in a WITH clause, or run it first and send its values as parameters";
      case "unsupportedSymmetric" -> "Write x BETWEEN low AND high with the smaller bound first";
      case "unsupportedQuantifiedComparison" -> "Write IN with one parameter for each value, as in x IN ($1, $2), or IN (SELECT ...)."
          + " There is no way to search an array column yet";
      case "unsupportedIndirection", "unsupportedSubscript" -> "Select the whole value and read the field or element in your program";
      case "unsupportedRowComma" -> "Compare the columns one by one, as in a < $1 OR (a = $1 AND b < $2)";
      case "unsupportedAggregateOrder" -> PostgreSQLHints.NO_WAY + ": select the rows with ORDER BY and aggregate in your program";
      case "unsupportedNamedArg" -> "Pass the arguments by position; an interval is written interval '3 days'";
      case "unsupportedVariadic" -> "Pass the values one by one";
      case "unsupportedSimilarEscape" -> "Use substring(value FROM start FOR length), or match with LIKE";
      case "unsupportedUnicodeString", "unsupportedUnicodeIdent" -> "Write the characters themselves";
      case "unsupportedCollate" -> "Leave COLLATE out: text compares as in the C collation";
      case "unsupportedAtTimeZone" -> "Leave AT TIME ZONE out and convert the time in your program";
      case "unsupportedStarArg" -> "Name the columns one by one";
      case "unsupportedMultiAssign" -> "Assign one column at a time";
      case "unsupportedUpdateFrom", "unsupportedDeleteUsing" -> "Put the condition in WHERE with a subquery, as in"
          + " WHERE id IN (SELECT ...)";
      case "unsupportedCurrentOf" -> "Name the row in WHERE by its key";
      case "unsupportedTransactionMode" -> "Send BEGIN with no mode";
      case "unsupportedTransactionChain" -> "Send COMMIT, then BEGIN";
      case "unsupportedTableKind" -> "Create an ordinary table, or name the query in a WITH clause";
      case "unsupportedExplainedCreate" -> "Run the CREATE statement without EXPLAIN";
      case "unsupportedTableForm" -> "Write CREATE TABLE name (column type, ...) and fill it with INSERT INTO name SELECT ...";
      case "unsupportedTableOption", "unsupportedIndexOption", "unsupportedConcurrently", "unsupportedReferenceOption",
          "unsupportedParameterNamespace" -> "Leave the clause out";
      case "unsupportedTableElement" -> "Write the columns out";
      case "unsupportedIndexExpression" -> "Store the computed value in a column of its own and index that column";
      case "unsupportedIndexElementOption" -> "Name the column only, for a vector index with its operator class";
      case "unsupportedIndexParameter" -> "Give a whole number";
      case "unsupportedInsertSource" -> "Write INSERT INTO name (columns) VALUES (...) or INSERT INTO name (columns) SELECT ...";
      default -> hintOfWords(what);
    };
  }

  /** The hint of a construct that the message names by its own words. */
  private static String hintOfWords(final String what) {
    if (what.startsWith("NATURAL"))
      return "Write JOIN ... ON, or JOIN ... USING (columns)";
    if (what.endsWith("SIMILAR TO"))
      return "Use LIKE or ILIKE";
    if ("A subquery".equals(what) || "A parenthesised query".equals(what))
      return "Name the query in a WITH clause and read it by that name";
    return switch (what.split(" ")[0]) {
      case "FILTER" -> "Write the condition inside the aggregate, as in count(CASE WHEN condition THEN 1 END)";
      case "IS" -> "Write a comparison, as in x = true, or coalesce(x, false)";
      case "OVER" -> "Write the window inside OVER (...)";
      case "TABLE" -> "Write SELECT * FROM name";
      case "CURRENT_USER", "SESSION_USER", "USER" -> PostgreSQLHints.NO_WAY + ": your program knows the user it connected as";
      default -> PostgreSQLHints.NO_WAY;
    };
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
