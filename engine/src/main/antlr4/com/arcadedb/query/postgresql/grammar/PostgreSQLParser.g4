/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use file except in compliance with the License.
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

/**
 * Parser of the postgresql language, written from the structure of the grammar of PostgreSQL (src/backend/parser/gram.y) for
 * the subset the language reads: SELECT with joins, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT and OFFSET, and EXPLAIN of one.
 *
 * How it follows gram.y:
 *  - The rules keep the names and the shape of gram.y: select_clause, target_el, table_ref, joined_table, a_expr, b_expr,
 *    c_expr, func_application, case_expr, Typename and the others. A_expr is one left recursive rule, listed from the
 *    tightest operator to the loosest as the precedence table of PostgreSQL (section 4.1.6 of the manual): the order of its
 *    alternatives is that table.
 *  - The four keyword classes of PostgreSQL decide where a keyword may be a name: an unreserved keyword is a name anywhere,
 *    a column name keyword is a column name but not a function or type name, a type or function name keyword is a function or
 *    type name but not a column name, and a reserved keyword is a name only after a dot or AS. The lists at the end of this
 *    file are generated from src/include/parser/kwlist.h of PostgreSQL 18, with the bare label list for aliases without AS.
 *
 * What it does with PostgreSQL syntax the subset does not cover: a rule whose name starts with "unsupported" matches the
 * first words of such a construct (a subquery, WITH, RIGHT JOIN, a window function, any statement that is not a SELECT, and so
 * on). It does not parse the rest. The Java side records the first such rule and reports it as SQLSTATE 0A000 with its line
 * and column, unless a real syntax error comes before it, which is SQLSTATE 42601. A construct that is not PostgreSQL syntax is
 * never matched by such a rule, so it is a syntax error, as in PostgreSQL.
 */
parser grammar PostgreSQLParser;

options {
  tokenVocab = PostgreSQLLexer;
}

// ============================================================================
// STATEMENTS
// ============================================================================

root
  : stmt EOF
  ;

stmt
  : selectStmt SEMI?       # selectCommand
  | explainStmt SEMI?      # explainCommand
  | unsupportedStatement   # unsupportedCommand
  | SEMI?                  # emptyCommand
  ;

explainStmt
  : EXPLAIN selectStmt
  | EXPLAIN unsupportedExplainOption
  | EXPLAIN unsupportedStatement
  ;

unsupportedExplainOption
  : ANALYZE
  | ANALYSE
  | VERBOSE
  | LPAREN
  ;

// the first words of every other statement of PostgreSQL, WITH, VALUES, TABLE and a parenthesised query
unsupportedStatement
  : ABORT | ALTER | ANALYSE | ANALYZE | BEGIN | CALL | CHECKPOINT | CLOSE | CLUSTER | COMMENT | COMMIT | COPY | CREATE
  | DEALLOCATE | DECLARE | DELETE | DISCARD | DO | DROP | END | EXECUTE | FETCH | GRANT | IMPORT | INSERT | LISTEN | LOAD
  | LOCK | MERGE | MOVE | NOTIFY | PREPARE | REASSIGN | REFRESH | REINDEX | RELEASE | RESET | REVOKE | ROLLBACK | SAVEPOINT
  | SECURITY | SET | SHOW | START | TRUNCATE | UNLISTEN | UPDATE | VACUUM | VALUES | WITH | TABLE
  | LPAREN+ (SELECT | WITH | VALUES | TABLE)
  ;

// ============================================================================
// SELECT (select_no_parens and simple_select of gram.y)
// ============================================================================

selectStmt
  : SELECT selectQuantifier? targetList? unsupportedInto? fromClause? whereClause? groupClause? havingClause?
    unsupportedWindowClause? unsupportedSetOperation? sortClause? unsupportedLockingClause? selectLimit?
    unsupportedLockingClause?
  ;

selectQuantifier
  : ALL
  | DISTINCT unsupportedDistinctOn?
  ;

unsupportedDistinctOn
  : ON
  ;

unsupportedInto
  : INTO
  ;

unsupportedWindowClause
  : WINDOW
  ;

unsupportedSetOperation
  : UNION
  | INTERSECT
  | EXCEPT
  ;

unsupportedLockingClause
  : FOR
  ;

// ---------------------------------------------------------------- select list

targetList
  : items+=targetEl (COMMA items+=targetEl)*
  ;

targetEl
  : STAR                                 # targetStar
  | qualifiedStar                        # targetQualifiedStar
  | aExpr (AS colLabel | bareColLabel)?  # targetExpression
  ;

qualifiedStar
  : first=colId (DOT rest+=colLabel)* DOT STAR
  ;

// ---------------------------------------------------------------- FROM

fromClause
  : FROM items+=tableRef (COMMA items+=tableRef)*
  ;

tableRef
  : relationExpr aliasClause? unsupportedTablesample?     # tableNameRef
  | tableRef joinKind? JOIN tableRef joinQual             # joinRef
  | tableRef unsupportedJoinKind JOIN tableRef joinQual?  # unsupportedJoinRef
  | LPAREN tableRef RPAREN                                # parenRef
  | LPAREN tableRef RPAREN unsupportedJoinAlias           # unsupportedParenAliasRef
  | unsupportedTableRef                                   # unsupportedRef
  ;

joinKind
  : INNER
  | LEFT OUTER?
  ;

joinQual
  : ON aExpr
  | unsupportedJoinUsing
  ;

unsupportedJoinUsing
  : USING
  ;

unsupportedJoinKind
  : CROSS
  | NATURAL (INNER | LEFT | RIGHT | FULL)? OUTER?
  | RIGHT OUTER?
  | FULL OUTER?
  ;

unsupportedJoinAlias
  : AS colId
  | colId
  ;

unsupportedTablesample
  : TABLESAMPLE
  ;

unsupportedTableRef
  : LATERAL
  | ROWS FROM LPAREN
  | XMLTABLE
  | JSON_TABLE
  | funcName LPAREN
  | LPAREN+ (SELECT | WITH | VALUES | TABLE)
  ;

relationExpr
  : qualifiedName STAR?
  | ONLY qualifiedName
  | ONLY LPAREN qualifiedName RPAREN
  ;

qualifiedName
  : first=colId (DOT rest+=attrName)*
  ;

attrName
  : colLabel
  ;

aliasClause
  : AS alias=colId unsupportedColumnAliases?
  | alias=colId unsupportedColumnAliases?
  ;

unsupportedColumnAliases
  : LPAREN
  ;

// ---------------------------------------------------------------- WHERE, GROUP BY, HAVING

whereClause
  : WHERE aExpr
  ;

groupClause
  : GROUP BY unsupportedGroupByQuantifier? items+=groupByItem (COMMA items+=groupByItem)*
  ;

groupByItem
  : unsupportedGroupingSet
  | aExpr
  ;

unsupportedGroupByQuantifier
  : ALL
  | DISTINCT
  ;

unsupportedGroupingSet
  : (ROLLUP | CUBE) LPAREN
  | GROUPING SETS
  | LPAREN RPAREN
  ;

havingClause
  : HAVING aExpr
  ;

// ---------------------------------------------------------------- ORDER BY, LIMIT, OFFSET

sortClause
  : ORDER BY items+=sortBy (COMMA items+=sortBy)*
  ;

sortBy
  : aExpr unsupportedSortUsing                 # sortUsing
  | aExpr dir=(ASC | DESC)? nullsOrder?        # sortKey
  ;

unsupportedSortUsing
  : USING
  ;

nullsOrder
  : NULLS (FIRST | LAST)
  ;

selectLimit
  : limitClause offsetClause?
  | offsetClause limitClause?
  ;

limitClause
  : LIMIT (aExpr | ALL) unsupportedLimitComma?
  | unsupportedFetchClause
  ;

unsupportedLimitComma
  : COMMA
  ;

unsupportedFetchClause
  : FETCH
  ;

offsetClause
  : OFFSET aExpr
  | OFFSET fetchFirstValue (ROW | ROWS)
  ;

fetchFirstValue
  : cExpr
  | PLUS (ICONST | FCONST)
  | MINUS (ICONST | FCONST)
  ;

// ============================================================================
// EXPRESSIONS (a_expr, b_expr and c_expr of gram.y)
// ============================================================================

// Alternatives from the tightest operator to the loosest: the precedence table of PostgreSQL.
aExpr
  : cExpr                                                                     # primaryExpr
  | aExpr TYPECAST typename                                                   # typecastExpr
  | op=(PLUS | MINUS) aExpr                                                   # unaryExpr
  | aExpr unsupportedPostfix                                                  # unsupportedPostfixExpr
  | l=aExpr CARET r=aExpr                                                     # powerExpr
  | l=aExpr op=(STAR | SLASH | PERCENT) r=aExpr                               # multiplicativeExpr
  | l=aExpr op=(PLUS | MINUS) r=aExpr                                         # additiveExpr
  | unsupportedPrefixOperator aExpr                                           # unsupportedPrefixExpr
  | l=aExpr OP r=aExpr                                                  # operatorExpr
  | aExpr NOT? IN LPAREN exprList RPAREN                                      # inListExpr
  | aExpr NOT? IN unsupportedInSubquery                                       # unsupportedInExpr
  | aExpr NOT? BETWEEN ASYMMETRIC? low=bExpr AND high=aExpr                   # betweenExpr
  | aExpr NOT? BETWEEN unsupportedSymmetric low=bExpr AND high=aExpr          # unsupportedBetweenExpr
  | aExpr NOT? LIKE pattern=aExpr ESCAPE escape=aExpr                         # likeEscapeExpr
  | aExpr NOT? LIKE pattern=aExpr                                             # likeExpr
  | l=aExpr unsupportedPatternOperator r=aExpr                                # unsupportedPatternExpr
  | aExpr unsupportedQuantifiedComparison                                     # unsupportedQuantifiedExpr
  | l=aExpr op=(LT | GT | EQ | LE | GE | NE) r=aExpr                          # comparisonExpr
  | aExpr IS NOT? NULL                                                        # isNullExpr
  | aExpr op=(ISNULL | NOTNULL)                                               # isNullPostfixExpr
  | aExpr unsupportedIsForm                                                   # unsupportedIsExpr
  | NOT aExpr                                                                 # notExpr
  | l=aExpr AND r=aExpr                                                       # andExpr
  | l=aExpr OR r=aExpr                                                        # orExpr
  ;

// b_expr: the operands of BETWEEN and POSITION, without the boolean and pattern operators
bExpr
  : cExpr                                                                     # bPrimaryExpr
  | bExpr TYPECAST typename                                                   # bTypecastExpr
  | op=(PLUS | MINUS) bExpr                                                   # bUnaryExpr
  | l=bExpr CARET r=bExpr                                                     # bPowerExpr
  | l=bExpr op=(STAR | SLASH | PERCENT) r=bExpr                               # bMultiplicativeExpr
  | l=bExpr op=(PLUS | MINUS) r=bExpr                                         # bAdditiveExpr
  | unsupportedPrefixOperator bExpr                                           # bUnsupportedPrefixExpr
  | l=bExpr OP r=bExpr                                                  # bOperatorExpr
  | l=bExpr op=(LT | GT | EQ | LE | GE | NE) r=bExpr                          # bComparisonExpr
  ;

unsupportedPostfix
  : COLLATE
  | AT (TIME ZONE | LOCAL)
  ;

unsupportedPrefixOperator
  : OP
  ;

unsupportedInSubquery
  : LPAREN+ (SELECT | WITH | VALUES | TABLE)
  ;

unsupportedSymmetric
  : SYMMETRIC
  ;

unsupportedPatternOperator
  : NOT? (ILIKE | SIMILAR TO)
  ;

unsupportedQuantifiedComparison
  : (EQ | LT | GT | LE | GE | NE | PLUS | MINUS | STAR | SLASH | PERCENT | CARET | OP) (ANY | SOME | ALL) LPAREN
  ;

unsupportedIsForm
  : IS NOT? (TRUE | FALSE | UNKNOWN | DISTINCT FROM | DOCUMENT | NORMALIZED | NFC | NFD | NFKC | NFKD | OF | JSON)
  ;

cExpr
  : columnRef                                                                  # columnRefExpr
  | aexprConst                                                                 # constExpr
  | PARAM                                                                      # paramExpr
  | LPAREN aExpr RPAREN                                                        # parenExpr
  | LPAREN aExpr RPAREN unsupportedIndirection                                 # unsupportedParenIndirectionExpr
  | LPAREN aExpr unsupportedRowComma                                           # unsupportedRowExpr
  | caseExpr                                                                   # caseExprAlt
  | CAST LPAREN aExpr AS typename RPAREN                                       # castExpr
  | funcApplication unsupportedFunctionSuffix?                                 # functionCallExpr
  | fn=(COALESCE | GREATEST | LEAST) LPAREN exprList RPAREN                    # variadicSpecialExpr
  | NULLIF LPAREN l=aExpr COMMA r=aExpr RPAREN                                 # nullIfExpr
  | CURRENT_DATE                                                               # currentDateExpr
  | fn=(CURRENT_TIME | CURRENT_TIMESTAMP | LOCALTIME | LOCALTIMESTAMP) (LPAREN ICONST RPAREN)?   # timeFunctionExpr
  | EXTRACT LPAREN extractArg FROM aExpr RPAREN                                # extractExpr
  | POSITION LPAREN l=bExpr IN r=bExpr RPAREN                                  # positionExpr
  | SUBSTRING LPAREN substrList RPAREN                                         # substringFromExpr
  | SUBSTRING LPAREN funcArgList? RPAREN                                       # substringCallExpr
  | TRIM LPAREN kind=(BOTH | LEADING | TRAILING)? trimList RPAREN              # trimExpr
  | unsupportedPrimary                                                         # unsupportedPrimaryExpr
  ;

unsupportedIndirection
  : DOT
  | LBRACKET
  ;

unsupportedRowComma
  : COMMA
  ;

unsupportedFunctionSuffix
  : WITHIN GROUP
  | FILTER LPAREN
  | OVER (LPAREN | colId)
  ;

// a subquery, EXISTS, an array, a row, GROUPING, and the keyword functions the subset does not have
unsupportedPrimary
  : LPAREN+ (SELECT | WITH | VALUES | TABLE)
  | EXISTS LPAREN
  | ARRAY (LBRACKET | LPAREN)
  | ROW LPAREN
  | GROUPING LPAREN
  | CURRENT_ROLE | CURRENT_USER | SESSION_USER | SYSTEM_USER | USER | CURRENT_CATALOG | CURRENT_SCHEMA
  | COLLATION FOR
  | (OVERLAY | NORMALIZE | TREAT | XMLCONCAT | XMLELEMENT | XMLEXISTS | XMLFOREST | XMLPARSE | XMLPI | XMLROOT | XMLSERIALIZE
    | JSON | JSON_ARRAY | JSON_ARRAYAGG | JSON_EXISTS | JSON_OBJECT | JSON_OBJECTAGG | JSON_QUERY | JSON_SCALAR
    | JSON_SERIALIZE | JSON_VALUE | MERGE_ACTION) LPAREN
  ;

columnRef
  : first=colId (DOT rest+=attrName)* unsupportedSubscript?
  ;

unsupportedSubscript
  : LBRACKET
  ;

caseExpr
  : CASE operand=aExpr? whens+=whenClause+ (ELSE otherwise=aExpr)? END
  ;

whenClause
  : WHEN cond=aExpr THEN res=aExpr
  ;

exprList
  : items+=aExpr (COMMA items+=aExpr)*
  ;

// ---------------------------------------------------------------- function calls

funcApplication
  : name=funcName LPAREN RPAREN                                  # funcNoArgs
  | name=funcName LPAREN STAR RPAREN                             # funcStar
  | name=funcName LPAREN q=(ALL | DISTINCT)? args=funcArgList RPAREN   # funcWithArgs
  ;

funcName
  : typeFunctionName                    # simpleFuncName
  | head=colId (DOT tail+=colLabel)+    # qualifiedFuncName
  ;

funcArgList
  : items+=funcArgExpr (COMMA items+=funcArgExpr)* unsupportedAggregateOrder?
  ;

funcArgExpr
  : unsupportedNamedArg
  | unsupportedVariadic
  | aExpr
  ;

unsupportedNamedArg
  : paramName (COLON_EQUALS | EQUALS_GREATER)
  ;

paramName
  : typeFunctionName
  ;

unsupportedVariadic
  : VARIADIC
  ;

unsupportedAggregateOrder
  : ORDER BY
  ;

extractArg
  : identTok
  | YEAR
  | MONTH
  | DAY
  | HOUR
  | MINUTE
  | SECOND
  | sconst
  ;

substrList
  : a=aExpr FROM b=aExpr FOR c=aExpr     # substrFromFor
  | a=aExpr FOR c=aExpr FROM b=aExpr     # substrForFrom
  | a=aExpr FROM b=aExpr                 # substrFrom
  | a=aExpr FOR c=aExpr                  # substrFor
  | aExpr unsupportedSimilar             # substrSimilar
  ;

unsupportedSimilar
  : SIMILAR
  ;

trimList
  : (chars=aExpr FROM | FROM)? src=exprList
  ;

// ---------------------------------------------------------------- constants (AexprConst)

aexprConst
  : ICONST                                  # integerConst
  | FCONST                                  # decimalConst
  | sconst                                  # stringConst
  | unsupportedBitString                    # unsupportedBitConst
  | TRUE                                    # trueConst
  | FALSE                                   # falseConst
  | NULL                                    # nullConst
  | funcName sconst                         # genericTypedConst
  | constTypename sconst                    # typedConst
  | INTERVAL sconst intervalFields?         # intervalConst
  | INTERVAL LPAREN ICONST RPAREN sconst    # intervalPrecisionConst
  ;

sconst
  : SCONST
  | ESTRING
  | DOLLAR_STRING
  | unsupportedUnicodeString
  ;

unsupportedUnicodeString
  : USCONST
  ;

unsupportedBitString
  : BCONST
  | XCONST
  ;

constTypename
  : numericType
  | characterType
  | datetimeType
  ;

// ============================================================================
// TYPE NAMES (Typename, SimpleTypename and their rules in gram.y)
// ============================================================================

typename
  : simpleTypename arrayBounds?
  | unsupportedSetof
  ;

unsupportedSetof
  : SETOF
  ;

arrayBounds
  : (LBRACKET ICONST? RBRACKET)+
  | ARRAY (LBRACKET ICONST RBRACKET)?
  ;

simpleTypename
  : genericType          # genericTypeName
  | numericType          # numericTypeName
  | characterType        # characterTypeName
  | datetimeType         # datetimeTypeName
  | intervalType         # intervalTypeName
  | unsupportedBitType   # bitTypeName
  ;

genericType
  : first=typeFunctionName (DOT rest+=attrName)* typeModifiers?
  ;

typeModifiers
  : LPAREN exprList RPAREN
  ;

numericType
  : INT
  | INTEGER
  | SMALLINT
  | BIGINT
  | REAL
  | BOOLEAN
  | FLOAT (LPAREN ICONST RPAREN)?
  | DOUBLE PRECISION
  | (DECIMAL | DEC | NUMERIC) typeModifiers?
  ;

characterType
  : (CHARACTER | CHAR) VARYING? (LPAREN ICONST RPAREN)?
  | VARCHAR (LPAREN ICONST RPAREN)?
  ;

datetimeType
  : (TIMESTAMP | TIME) (LPAREN ICONST RPAREN)? ((WITH | WITHOUT) TIME ZONE)?
  ;

intervalType
  : INTERVAL intervalFields?
  | INTERVAL LPAREN ICONST RPAREN
  ;

intervalFields
  : YEAR (TO MONTH)?
  | MONTH
  | DAY (TO (HOUR | MINUTE | intervalSecond))?
  | HOUR (TO (MINUTE | intervalSecond))?
  | MINUTE (TO intervalSecond)?
  | intervalSecond
  ;

intervalSecond
  : SECOND (LPAREN ICONST RPAREN)?
  ;

unsupportedBitType
  : BIT VARYING?
  | NATIONAL
  | NCHAR
  ;

// ============================================================================
// NAMES AND KEYWORD CLASSES (ColId, type_function_name, ColLabel, BareColLabel of gram.y)
// ============================================================================

identTok
  : IDENT
  | QUOTED_IDENT
  | UIDENT
  ;

colId
  : identTok
  | unreservedKeyword
  | colNameKeyword
  ;

typeFunctionName
  : identTok
  | unreservedKeyword
  | typeFuncNameKeyword
  ;

colLabel
  : identTok
  | unreservedKeyword
  | colNameKeyword
  | typeFuncNameKeyword
  | reservedKeyword
  ;

bareColLabel
  : identTok
  | bareLabelKeyword
  ;

unreservedKeyword
  : ABORT | ABSENT | ABSOLUTE | ACCESS | ACTION | ADD
  | ADMIN | AFTER | AGGREGATE | ALSO | ALTER | ALWAYS
  | ASENSITIVE | ASSERTION | ASSIGNMENT | AT | ATOMIC | ATTACH
  | ATTRIBUTE | BACKWARD | BEFORE | BEGIN | BREADTH | BY
  | CACHE | CALL | CALLED | CASCADE | CASCADED | CATALOG
  | CHAIN | CHARACTERISTICS | CHECKPOINT | CLASS | CLOSE | CLUSTER
  | COLUMNS | COMMENT | COMMENTS | COMMIT | COMMITTED | COMPRESSION
  | CONDITIONAL | CONFIGURATION | CONFLICT | CONNECTION | CONSTRAINTS | CONTENT
  | CONTINUE | CONVERSION | COPY | COST | CSV | CUBE
  | CURRENT | CURSOR | CYCLE | DATA | DATABASE | DAY
  | DEALLOCATE | DECLARE | DEFAULTS | DEFERRED | DEFINER | DELETE
  | DELIMITER | DELIMITERS | DEPENDS | DEPTH | DETACH | DICTIONARY
  | DISABLE | DISCARD | DOCUMENT | DOMAIN | DOUBLE | DROP
  | EACH | EMPTY | ENABLE | ENCODING | ENCRYPTED | ENFORCED
  | ENUM | ERROR | ESCAPE | EVENT | EXCLUDE | EXCLUDING
  | EXCLUSIVE | EXECUTE | EXPLAIN | EXPRESSION | EXTENSION | EXTERNAL
  | FAMILY | FILTER | FINALIZE | FIRST | FOLLOWING | FORCE
  | FORMAT | FORWARD | FUNCTION | FUNCTIONS | GENERATED | GLOBAL
  | GRANTED | GROUPS | HANDLER | HEADER | HOLD | HOUR
  | IDENTITY | IF | IMMEDIATE | IMMUTABLE | IMPLICIT | IMPORT
  | INCLUDE | INCLUDING | INCREMENT | INDENT | INDEX | INDEXES
  | INHERIT | INHERITS | INLINE | INPUT | INSENSITIVE | INSERT
  | INSTEAD | INVOKER | ISOLATION | KEEP | KEY | KEYS
  | LABEL | LANGUAGE | LARGE | LAST | LEAKPROOF | LEVEL
  | LISTEN | LOAD | LOCAL | LOCATION | LOCK | LOCKED
  | LOGGED | MAPPING | MATCH | MATCHED | MATERIALIZED | MAXVALUE
  | MERGE | METHOD | MINUTE | MINVALUE | MODE | MONTH
  | MOVE | NAME | NAMES | NESTED | NEW | NEXT
  | NFC | NFD | NFKC | NFKD | NO | NORMALIZED
  | NOTHING | NOTIFY | NOWAIT | NULLS | OBJECT | OBJECTS
  | OF | OFF | OIDS | OLD | OMIT | OPERATOR
  | OPTION | OPTIONS | ORDINALITY | OTHERS | OVER | OVERRIDING
  | OWNED | OWNER | PARALLEL | PARAMETER | PARSER | PARTIAL
  | PARTITION | PASSING | PASSWORD | PATH | PERIOD | PLAN
  | PLANS | POLICY | PRECEDING | PREPARE | PREPARED | PRESERVE
  | PRIOR | PRIVILEGES | PROCEDURAL | PROCEDURE | PROCEDURES | PROGRAM
  | PUBLICATION | QUOTE | QUOTES | RANGE | READ | REASSIGN
  | RECURSIVE | REF | REFERENCING | REFRESH | REINDEX | RELATIVE
  | RELEASE | RENAME | REPEATABLE | REPLACE | REPLICA | RESET
  | RESTART | RESTRICT | RETURN | RETURNS | REVOKE | ROLE
  | ROLLBACK | ROLLUP | ROUTINE | ROUTINES | ROWS | RULE
  | SAVEPOINT | SCALAR | SCHEMA | SCHEMAS | SCROLL | SEARCH
  | SECOND | SECURITY | SEQUENCE | SEQUENCES | SERIALIZABLE | SERVER
  | SESSION | SET | SETS | SHARE | SHOW | SIMPLE
  | SKIP_KW | SNAPSHOT | SOURCE | SQL | STABLE | STANDALONE
  | START | STATEMENT | STATISTICS | STDIN | STDOUT | STORAGE
  | STORED | STRICT | STRING | STRIP | SUBSCRIPTION | SUPPORT
  | SYSID | SYSTEM | TABLES | TABLESPACE | TARGET | TEMP
  | TEMPLATE | TEMPORARY | TEXT | TIES | TRANSACTION | TRANSFORM
  | TRIGGER | TRUNCATE | TRUSTED | TYPE | TYPES | UESCAPE
  | UNBOUNDED | UNCOMMITTED | UNCONDITIONAL | UNENCRYPTED | UNKNOWN | UNLISTEN
  | UNLOGGED | UNTIL | UPDATE | VACUUM | VALID | VALIDATE
  | VALIDATOR | VALUE | VARYING | VERSION | VIEW | VIEWS
  | VIRTUAL | VOLATILE | WHITESPACE | WITHIN | WITHOUT | WORK
  | WRAPPER | WRITE | XML | YEAR | YES | ZONE
  ;

colNameKeyword
  : BETWEEN | BIGINT | BIT | BOOLEAN | CHAR | CHARACTER
  | COALESCE | DEC | DECIMAL | EXISTS | EXTRACT | FLOAT
  | GREATEST | GROUPING | INOUT | INT | INTEGER | INTERVAL
  | JSON | JSON_ARRAY | JSON_ARRAYAGG | JSON_EXISTS | JSON_OBJECT | JSON_OBJECTAGG
  | JSON_QUERY | JSON_SCALAR | JSON_SERIALIZE | JSON_TABLE | JSON_VALUE | LEAST
  | MERGE_ACTION | NATIONAL | NCHAR | NONE | NORMALIZE | NULLIF
  | NUMERIC | OUT | OVERLAY | POSITION | PRECISION | REAL
  | ROW | SETOF | SMALLINT | SUBSTRING | TIME | TIMESTAMP
  | TREAT | TRIM | VALUES | VARCHAR | XMLATTRIBUTES | XMLCONCAT
  | XMLELEMENT | XMLEXISTS | XMLFOREST | XMLNAMESPACES | XMLPARSE | XMLPI
  | XMLROOT | XMLSERIALIZE | XMLTABLE
  ;

typeFuncNameKeyword
  : AUTHORIZATION | BINARY | COLLATION | CONCURRENTLY | CROSS | CURRENT_SCHEMA
  | FREEZE | FULL | ILIKE | INNER | IS | ISNULL
  | JOIN | LEFT | LIKE | NATURAL | NOTNULL | OUTER
  | OVERLAPS | RIGHT | SIMILAR | TABLESAMPLE | VERBOSE
  ;

reservedKeyword
  : ALL | ANALYSE | ANALYZE | AND | ANY | ARRAY
  | AS | ASC | ASYMMETRIC | BOTH | CASE | CAST
  | CHECK | COLLATE | COLUMN | CONSTRAINT | CREATE | CURRENT_CATALOG
  | CURRENT_DATE | CURRENT_ROLE | CURRENT_TIME | CURRENT_TIMESTAMP | CURRENT_USER | DEFAULT
  | DEFERRABLE | DESC | DISTINCT | DO | ELSE | END
  | EXCEPT | FALSE | FETCH | FOR | FOREIGN | FROM
  | GRANT | GROUP | HAVING | IN | INITIALLY | INTERSECT
  | INTO | LATERAL | LEADING | LIMIT | LOCALTIME | LOCALTIMESTAMP
  | NOT | NULL | OFFSET | ON | ONLY | OR
  | ORDER | PLACING | PRIMARY | REFERENCES | RETURNING | SELECT
  | SESSION_USER | SOME | SYMMETRIC | SYSTEM_USER | TABLE | THEN
  | TO | TRAILING | TRUE | UNION | UNIQUE | USER
  | USING | VARIADIC | WHEN | WHERE | WINDOW | WITH
  ;

bareLabelKeyword
  : ABORT | ABSENT | ABSOLUTE | ACCESS | ACTION | ADD
  | ADMIN | AFTER | AGGREGATE | ALL | ALSO | ALTER
  | ALWAYS | ANALYSE | ANALYZE | AND | ANY | ASC
  | ASENSITIVE | ASSERTION | ASSIGNMENT | ASYMMETRIC | AT | ATOMIC
  | ATTACH | ATTRIBUTE | AUTHORIZATION | BACKWARD | BEFORE | BEGIN
  | BETWEEN | BIGINT | BINARY | BIT | BOOLEAN | BOTH
  | BREADTH | BY | CACHE | CALL | CALLED | CASCADE
  | CASCADED | CASE | CAST | CATALOG | CHAIN | CHARACTERISTICS
  | CHECK | CHECKPOINT | CLASS | CLOSE | CLUSTER | COALESCE
  | COLLATE | COLLATION | COLUMN | COLUMNS | COMMENT | COMMENTS
  | COMMIT | COMMITTED | COMPRESSION | CONCURRENTLY | CONDITIONAL | CONFIGURATION
  | CONFLICT | CONNECTION | CONSTRAINT | CONSTRAINTS | CONTENT | CONTINUE
  | CONVERSION | COPY | COST | CROSS | CSV | CUBE
  | CURRENT | CURRENT_CATALOG | CURRENT_DATE | CURRENT_ROLE | CURRENT_SCHEMA | CURRENT_TIME
  | CURRENT_TIMESTAMP | CURRENT_USER | CURSOR | CYCLE | DATA | DATABASE
  | DEALLOCATE | DEC | DECIMAL | DECLARE | DEFAULT | DEFAULTS
  | DEFERRABLE | DEFERRED | DEFINER | DELETE | DELIMITER | DELIMITERS
  | DEPENDS | DEPTH | DESC | DETACH | DICTIONARY | DISABLE
  | DISCARD | DISTINCT | DO | DOCUMENT | DOMAIN | DOUBLE
  | DROP | EACH | ELSE | EMPTY | ENABLE | ENCODING
  | ENCRYPTED | END | ENFORCED | ENUM | ERROR | ESCAPE
  | EVENT | EXCLUDE | EXCLUDING | EXCLUSIVE | EXECUTE | EXISTS
  | EXPLAIN | EXPRESSION | EXTENSION | EXTERNAL | EXTRACT | FALSE
  | FAMILY | FINALIZE | FIRST | FLOAT | FOLLOWING | FORCE
  | FOREIGN | FORMAT | FORWARD | FREEZE | FULL | FUNCTION
  | FUNCTIONS | GENERATED | GLOBAL | GRANTED | GREATEST | GROUPING
  | GROUPS | HANDLER | HEADER | HOLD | IDENTITY | IF
  | ILIKE | IMMEDIATE | IMMUTABLE | IMPLICIT | IMPORT | IN
  | INCLUDE | INCLUDING | INCREMENT | INDENT | INDEX | INDEXES
  | INHERIT | INHERITS | INITIALLY | INLINE | INNER | INOUT
  | INPUT | INSENSITIVE | INSERT | INSTEAD | INT | INTEGER
  | INTERVAL | INVOKER | IS | ISOLATION | JOIN | JSON
  | JSON_ARRAY | JSON_ARRAYAGG | JSON_EXISTS | JSON_OBJECT | JSON_OBJECTAGG | JSON_QUERY
  | JSON_SCALAR | JSON_SERIALIZE | JSON_TABLE | JSON_VALUE | KEEP | KEY
  | KEYS | LABEL | LANGUAGE | LARGE | LAST | LATERAL
  | LEADING | LEAKPROOF | LEAST | LEFT | LEVEL | LIKE
  | LISTEN | LOAD | LOCAL | LOCALTIME | LOCALTIMESTAMP | LOCATION
  | LOCK | LOCKED | LOGGED | MAPPING | MATCH | MATCHED
  | MATERIALIZED | MAXVALUE | MERGE | MERGE_ACTION | METHOD | MINVALUE
  | MODE | MOVE | NAME | NAMES | NATIONAL | NATURAL
  | NCHAR | NESTED | NEW | NEXT | NFC | NFD
  | NFKC | NFKD | NO | NONE | NORMALIZE | NORMALIZED
  | NOT | NOTHING | NOTIFY | NOWAIT | NULL | NULLIF
  | NULLS | NUMERIC | OBJECT | OBJECTS | OF | OFF
  | OIDS | OLD | OMIT | ONLY | OPERATOR | OPTION
  | OPTIONS | OR | ORDINALITY | OTHERS | OUT | OUTER
  | OVERLAY | OVERRIDING | OWNED | OWNER | PARALLEL | PARAMETER
  | PARSER | PARTIAL | PARTITION | PASSING | PASSWORD | PATH
  | PERIOD | PLACING | PLAN | PLANS | POLICY | POSITION
  | PRECEDING | PREPARE | PREPARED | PRESERVE | PRIMARY | PRIOR
  | PRIVILEGES | PROCEDURAL | PROCEDURE | PROCEDURES | PROGRAM | PUBLICATION
  | QUOTE | QUOTES | RANGE | READ | REAL | REASSIGN
  | RECURSIVE | REF | REFERENCES | REFERENCING | REFRESH | REINDEX
  | RELATIVE | RELEASE | RENAME | REPEATABLE | REPLACE | REPLICA
  | RESET | RESTART | RESTRICT | RETURN | RETURNS | REVOKE
  | RIGHT | ROLE | ROLLBACK | ROLLUP | ROUTINE | ROUTINES
  | ROW | ROWS | RULE | SAVEPOINT | SCALAR | SCHEMA
  | SCHEMAS | SCROLL | SEARCH | SECURITY | SELECT | SEQUENCE
  | SEQUENCES | SERIALIZABLE | SERVER | SESSION | SESSION_USER | SET
  | SETOF | SETS | SHARE | SHOW | SIMILAR | SIMPLE
  | SKIP_KW | SMALLINT | SNAPSHOT | SOME | SOURCE | SQL
  | STABLE | STANDALONE | START | STATEMENT | STATISTICS | STDIN
  | STDOUT | STORAGE | STORED | STRICT | STRING | STRIP
  | SUBSCRIPTION | SUBSTRING | SUPPORT | SYMMETRIC | SYSID | SYSTEM
  | SYSTEM_USER | TABLE | TABLES | TABLESAMPLE | TABLESPACE | TARGET
  | TEMP | TEMPLATE | TEMPORARY | TEXT | THEN | TIES
  | TIME | TIMESTAMP | TRAILING | TRANSACTION | TRANSFORM | TREAT
  | TRIGGER | TRIM | TRUE | TRUNCATE | TRUSTED | TYPE
  | TYPES | UESCAPE | UNBOUNDED | UNCOMMITTED | UNCONDITIONAL | UNENCRYPTED
  | UNIQUE | UNKNOWN | UNLISTEN | UNLOGGED | UNTIL | UPDATE
  | USER | USING | VACUUM | VALID | VALIDATE | VALIDATOR
  | VALUE | VALUES | VARCHAR | VARIADIC | VERBOSE | VERSION
  | VIEW | VIEWS | VIRTUAL | VOLATILE | WHEN | WHITESPACE
  | WORK | WRAPPER | WRITE | XML | XMLATTRIBUTES | XMLCONCAT
  | XMLELEMENT | XMLEXISTS | XMLFOREST | XMLNAMESPACES | XMLPARSE | XMLPI
  | XMLROOT | XMLSERIALIZE | XMLTABLE | YES | ZONE
  ;

