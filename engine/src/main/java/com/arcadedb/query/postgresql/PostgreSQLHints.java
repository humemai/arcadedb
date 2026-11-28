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
package com.arcadedb.query.postgresql;

import java.util.ArrayList;
import java.util.List;

/**
 * The hint of a refusal ({@code 0A000}) that the planner or the evaluator raises (fork issue #241): what to write instead, or,
 * starting with {@link #NO_WAY}, that there is no way yet. The parser knows the construct it refuses and gives the hint itself.
 * <p>
 * A hint is found by a part of the message, the first rule that matches wins. A hint that names a statement of another language
 * ends with it, from its {@code {sql}} or {@code {opencypher}} marker on.
 */
public final class PostgreSQLHints {
  /** How the hint of a refusal with no way out starts. */
  public static final String NO_WAY = "There is no way to write this in the postgresql language yet";

  private static final String IN_THE_PROGRAM = NO_WAY + ": select the values and compute the result in your program";
  private static final String JSON_KEY       = "Write the function: jsonb_exists(value, 'a'), jsonb_exists_any(value,"
      + " ARRAY['a', 'b']) or jsonb_exists_all(value, ARRAY['a', 'b'])";
  private static final String SQL_WRITES     = "Write this value with an INSERT or UPDATE in the sql language";

  private record Rule(String part, String hint) {
  }

  private static final List<Rule> RULES = new ArrayList<>();

  private static void rule(final String part, final String hint) {
    RULES.add(new Rule(part, hint));
  }

  static {
    // statements and clauses
    rule("inside a transaction block", "Send ROLLBACK, then run it outside a transaction block, in autocommit: psycopg"
        + " autocommit=True, SQLAlchemy isolation_level=\"AUTOCOMMIT\"");
    rule("An empty statement", "Leave it out");
    rule("A ? parameter", "Write $1, $2 and so on");
    rule("A set operation or a WITH in the source", "Run the query first and insert its rows with INSERT ... VALUES");
    rule("ON CONFLICT ON CONSTRAINT", "Name the columns of the key instead, as in ON CONFLICT (column)");
    rule("An index predicate in the target", "Leave the WHERE of the ON CONFLICT target out");
    rule("An expression in the target", "Name the columns of a unique index");
    rule("A multiple-column assignment", "Assign one column at a time");
    rule("A cross-database", "Leave the database name out: a connection reads one database");
    rule("A VALUES list of more than", "Send the rows in several statements");
    rule("A select list with no item", "Name a column, or write SELECT 1");
    rule("A row constructor", "Compare two rows with =, <>, <, <=, >, >= or IN with a list of rows, or write the columns one by one");
    rule("A row comparison with", "Run the subquery or the function first and send its value as a parameter");
    rule("rows after IN", "Send the rows in several statements");
    rule("A Unicode escape", "Write the characters themselves");
    rule("A subquery is not supported in this clause", "Run the subquery first and send its values as parameters, or join its table");
    rule("A correlated subquery", "Join the table instead, or run the inner query first and send its values as parameters");
    rule("A correlated EXISTS with an aggregate",
        "Compute the aggregate in a WITH query that groups by the compared column, and join it");
    rule("A correlated EXISTS with GROUP BY", "Leave GROUP BY, HAVING, LIMIT and OFFSET out of the EXISTS");
    rule("of the query around an EXISTS", "Join the table instead of reading its column from inside the EXISTS");
    rule("An aggregate over columns of the outer query", "Compute the aggregate in the outer query and compare with its result");
    rule("A result column of type interval", "Add the interval to a date or a timestamp and select that");
    rule("ORDER BY clause: only result column names", "Order by a result column name, or select the expression as a column");
    rule("ORDER BY a value of type jsonb", "Order by a value taken from it, as in ORDER BY (value->>'a')::numeric");
    rule("ORDER BY a value of type", NO_WAY + ": sort the rows in your program");
    rule("which EXISTS can express", "Write it with NOT EXISTS: for a < ALL (SELECT b FROM t) write NOT EXISTS"
        + " (SELECT 1 FROM t WHERE b <= a), when b holds no NULL");
    rule("has no type for the value of parameter", "Send the value as text");
    rule("GROUP BY the position of a * item", "Name the columns after GROUP BY");
    rule("A window function in a query with GROUP BY", "Aggregate in a WITH query and apply the window function to its rows");
    rule("DISTINCT is not implemented for window functions", "Count the distinct values in a WITH query with GROUP BY and join it");
    rule("The window function", IN_THE_PROGRAM);
    rule("FULL JOIN", "Join on an equality of two columns of one type");
    // writes
    rule("ON CONFLICT on the edge table", "Select the row first, then UPDATE it or INSERT a new one");
    rule("of the edge table", "Delete the row and insert it again with the new ends");
    rule("UPDATE ... FROM on the edge table", "Delete the rows and insert them again");
    rule("RETURNING with UPDATE ... FROM", "Leave RETURNING out and SELECT the rows after the UPDATE");
    rule("UPDATE ... FROM needs the record id column", "Run one UPDATE for each row, with the values as parameters");
    rule("is lightweight: its edges have no record", "Write it in the sql or openCypher language");
    rule("This value cannot be written to the column", SQL_WRITES);
    // CREATE TABLE
    rule("A table with no column", "Declare at least one column");
    rule("A key over an end of an edge", "Declare the key over both ends, (source_id, target_id), or leave it out");
    rule("A key over a column of type", "Declare the key over a column of a type such as text, bigint or date");
    rule("A table in the schema", "Leave the schema out, or write public");
    rule("An index on a table of the schema", "Leave the schema out, or write public");
    rule("A column name that starts with @", "Choose a name that does not start with @");
    rule("A foreign key other than the two ends",
        "Refer to a primary key or a unique column of the table, which is checked, or declare a link table with the columns"
            + " source_id and target_id, each NOT NULL with the type of the key it refers to, such as bigint NOT NULL REFERENCES"
            + " table (id), or text NOT NULL REFERENCES table (rid) ON DELETE CASCADE");
    rule("A foreign key between columns of the types", "Give the referring column the type of the column it refers to");
    rule("A foreign key over several columns", "Declare one foreign key for source_id and one for target_id");
    rule("A foreign key to a column of type", "Refer to a unique column of a type such as bigint, text or date");
    rule("The referential action SET DEFAULT", "Write ON DELETE SET NULL or CASCADE");
    rule("A column list after SET NULL", "Leave the column list out: SET NULL sets every referring column");
    rule("A type modifier on a column", "Leave the modifier out: declare text or numeric, whose length and precision are not checked");
    rule("A column of type timestamptz[]", "Declare timestamp[] and keep its times in UTC");
    rule("A column of type uuid", "Declare the column text and send the UUID as text");
    rule("A table named", "Choose another name");
    rule("A column of type", "Declare one of boolean, smallint, integer, bigint, serial, bigserial, real, double precision,"
        + " numeric, text, bytea, date, timestamp, timestamptz, jsonb or vector(n)");
    rule("DEFAULT", "Write a constant as the DEFAULT, or leave it out and give the value in each INSERT");
    // CREATE INDEX
    rule("The index method", "Leave USING out for an ordinary index, or write USING hnsw for a vector column");
    rule("An index that names a column twice", "Name each column once");
    rule("A parameter of a tree index", "Leave the WITH clause out");
    rule("An operator class of a tree index", "Leave the operator class out");
    rule("A unique index over an end of an edge", "Declare UNIQUE (source_id, target_id) in CREATE TABLE, or leave UNIQUE out");
    rule("An index over a column of type", NO_WAY + ": index a column of another type");
    rule("A unique index over columns that have an index", NO_WAY + ": leave UNIQUE out, or index other columns");
    rule("A unique hnsw index", "Leave UNIQUE out");
    rule("An hnsw index over several columns", "Create one index for each vector column");
    rule("An hnsw index over a column that is not a vector", "Index a column of type vector(n), or leave USING hnsw out");
    rule("An hnsw index over a column without", "Declare the column vector(n) in CREATE TABLE");
    rule("A second index over the column", "Keep the index that the column has: one column has one index here");
    rule("The operator class", "Write vector_l2_ops, vector_cosine_ops or vector_ip_ops");
    rule("The index parameter", "Leave the parameter out");
    // time
    rule("before the year 1", "Use a year from 1 to 9999");
    rule("unit \"", "A date has no time of day: read that field from a timestamp");
    rule("interval is added to or subtracted from", "Add the interval to a date or a timestamp, or subtract it");
    rule("An infinite interval", NO_WAY + ": use a finite interval");
    rule("A fractional number of", "Write whole numbers, with a smaller unit for the fraction");
    rule("form of an interval", "Write the interval as text such as '1 year 2 months 3 days 04:05:06'");
    rule("The type interval", "Name one field, as in interval '1 day'");
    rule("This form of a", "Write the value in ISO 8601 form, such as 2026-11-27 or 2026-11-27 12:00:00");
    // operators, functions and types
    for (final String exists : new String[] { "?", "?|", "?&" })
      rule("The operator " + exists + " is not supported", JSON_KEY);
    rule("jsonb ?", JSON_KEY);
    rule("not supported for type jsonb", "Compare a value taken from it, as in (value->>'a')::numeric > 5");
    for (final String match : new String[] { "~", "~*", "!~", "!~*" })
      rule("The operator " + match + " is not supported", "Match with LIKE or ILIKE");
    rule("The operator", IN_THE_PROGRAM);
    rule("The aggregate function", NO_WAY + ": select the rows and aggregate them in your program");
    rule("The function", IN_THE_PROGRAM);
    rule("Comparing values of type", NO_WAY + ": compare the values in your program");
    rule("The text form of a value of type", IN_THE_PROGRAM);
    rule("cannot be written as a literal", "Send the value as a parameter");
    rule("NaN and Infinity", "Use double precision for NaN and Infinity");
    rule("numeric ^ numeric", "Cast to double precision, as in x::double precision ^ y");
    rule("A type modifier has to be", "Write the modifier as a number");
    rule("An array of more than one dimension", "Use one dimension, or store the value as jsonb");
    rule("An array literal with explicit bounds", "Write the elements only, as in '{1,2}'");
    rule("An array of", "Use an array of booleans, numbers, text, bytea, dates or timestamps, or store the value as jsonb");
    rule("The type uuid", "Use text for a UUID");
    rule("The type", "Use one of boolean, smallint, integer, bigint, real, double precision, numeric, text, bytea, date,"
        + " timestamp, timestamptz, jsonb or vector");
    rule("A JSON scalar is not supported", "Give an object or an array, such as {\"value\": 1}");
    rule("vector", "Give the vector as text of numbers, such as '[1,2,3]'");
  }

  private PostgreSQLHints() {
  }

  /** @return the hint of the refusal worded {@code message}, never {@code null} */
  public static String of(final String message) {
    if (message != null)
      for (final Rule rule : RULES)
        if (message.contains(rule.part()))
          return rule.hint();
    return NO_WAY;
  }
}
