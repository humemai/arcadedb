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

/**
 * Lexer of the postgresql language: PostgreSQL-style SQL over a relational view of the schema. It follows the scanner of
 * PostgreSQL (src/backend/parser/scan.l) for the subset the language reads, and it is written for this language: it shares
 * nothing with the lexer of the sql language, so the words join, left and inner stay words of the language that needs them.
 *
 * What it does as PostgreSQL does:
 *  - Keywords are case insensitive. The keyword list is src/include/parser/kwlist.h of PostgreSQL 18; the class of each
 *    keyword (reserved, unreserved, type or function name, column name) is applied by the parser grammar.
 *  - Any run of operator characters is one generic OP token, with the rule of PostgreSQL that trailing plus and minus
 *    signs are not part of an operator that has no ~ ! @ # % ^ & | ` ? in it, so a=-1 reads as a, =, -, 1. The parser maps
 *    the text of an OP (||, <->, <=>, <#>), so a new operator needs no lexer change.
 *  - Strings: standard strings with doubled quotes and no backslash escapes, E'...' strings with backslash escapes, and the
 *    joining of strings that are separated by a newline. Dollar-quoted strings are read in nextToken (see the members).
 *  - Quoted identifiers with doubled quotes, nested block comments, line comments, and numeric constants with an exponent,
 *    underscores and the 0x, 0o and 0b forms. A number glued to a word (123abc) is a token of its own so the parser can
 *    refuse it, as PostgreSQL does.
 *
 * A character the language does not know is the token UNRECOGNIZED and the parser reports it, as PostgreSQL does.
 */
lexer grammar PostgreSQLLexer;

options {
  caseInsensitive = true;
}

tokens {
  DOLLAR_STRING
}

@header {
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Token;
}

@members {
  /**
   * Dollar-quoted strings ($$text$$ and $tag$text$tag$) end at a tag equal to the opening tag, which a lexer rule cannot say,
   * so they are read here at the start of a token. Whitespace and comments go to the hidden channel and not to skip, so the
   * lexer returns to this method after each of them.
   */
  @Override
  public Token nextToken() {
    if (_input.LA(1) == '$') {
      final Token dollarQuoted = readDollarQuoted();
      if (dollarQuoted != null)
        return dollarQuoted;
    }
    return super.nextToken();
  }

  private Token readDollarQuoted() {
    // the opening tag is $, an optional identifier without a dollar sign, and $
    int length = 1;
    int c = _input.LA(length + 1);
    if (c != '$') {
      if (!isTagStart(c))
        return null;
      do {
        length++;
        c = _input.LA(length + 1);
      } while (isTagPart(c));
      if (c != '$')
        return null;
    }
    final int tagLength = length + 1;

    // the closing tag is the same characters
    int end = -1;
    for (int j = tagLength + 1; end < 0; j++) {
      final int d = _input.LA(j);
      if (d == IntStream.EOF)
        return null;
      if (d == '$') {
        boolean same = true;
        for (int k = 0; k < tagLength && same; k++)
          same = _input.LA(j + k) == _input.LA(k + 1);
        if (same)
          end = j + tagLength - 1;
      }
    }

    final int start = _input.index();
    final int tokenLine = getLine();
    final int tokenColumn = getCharPositionInLine();
    final CommonToken token = new CommonToken(_tokenFactorySourcePair, DOLLAR_STRING, Token.DEFAULT_CHANNEL, start,
        start + end - 1);
    token.setLine(tokenLine);
    token.setCharPositionInLine(tokenColumn);
    for (int i = 0; i < end; i++)
      getInterpreter().consume(_input);
    return token;
  }

  private static boolean isTagStart(final int c) {
    return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 0x80;
  }

  private static boolean isTagPart(final int c) {
    return isTagStart(c) || (c >= '0' && c <= '9');
  }
}

// ============================================================================
// PUNCTUATION AND OPERATORS
// ============================================================================

LPAREN: '(';
RPAREN: ')';
LBRACKET: '[';
RBRACKET: ']';
COMMA: ',';
SEMI: ';';
DOT: '.';
COLON: ':';
TYPECAST: '::';
COLON_EQUALS: ':=';
EQUALS_GREATER: '=>';

STAR: '*';
PLUS: '+';
MINUS: '-';
SLASH: '/';
PERCENT: '%';
CARET: '^';
LT: '<';
GT: '>';
EQ: '=';
LE: '<=';
GE: '>=';
NE: '<>' | '!=';

// Any other run of operator characters. The first alternative ends in a character that is not a plus or a minus sign. The
// second has a character of the special set in it, and may end in a plus or a minus sign. A run that has neither is cut
// before its trailing plus and minus signs, which the single character tokens above then read. A slash that starts a block
// comment is not part of an operator.
OP
  : OP_ANY* OP_NO_SIGN
  | OP_ANY* OP_SPECIAL OP_ANY*
  ;

fragment OP_PLAIN: [~!@#^&|`?*%<>=];
fragment OP_SPECIAL: [~!@#%^&|`?];
fragment OP_SLASH: '/' {_input.LA(1) != '*'}?;
fragment OP_NO_SIGN: OP_PLAIN | OP_SLASH;
fragment OP_ANY: OP_PLAIN | OP_SLASH | '+' | '-';

// ============================================================================
// CONSTANTS
// ============================================================================

PARAM: '$' DEC_DIGIT+;

ICONST
  : DEC_INT
  | '0' 'x' HEX_DIGIT ('_'? HEX_DIGIT)*
  | '0' 'o' OCT_DIGIT ('_'? OCT_DIGIT)*
  | '0' 'b' BIN_DIGIT ('_'? BIN_DIGIT)*
  ;

FCONST
  : DEC_INT '.' DEC_INT? EXPONENT?
  | '.' DEC_INT EXPONENT?
  | DEC_INT EXPONENT
  ;

// a number directly followed by a word: PostgreSQL refuses it as trailing junk
NUMERIC_JUNK: (DEC_INT ('.' DEC_INT?)? | '.' DEC_INT) IDENT_START IDENT_CONT*;

fragment DEC_DIGIT: [0-9];
fragment HEX_DIGIT: [0-9a-f];
fragment OCT_DIGIT: [0-7];
fragment BIN_DIGIT: [01];
fragment DEC_INT: DEC_DIGIT ('_'? DEC_DIGIT)*;
fragment EXPONENT: 'e' [+-]? DEC_DIGIT+;

// 'text' with '' for a quote; strings separated by a newline are one string
SCONST: STRING_BODY (STRING_SEPARATOR STRING_BODY)*;

// E'text' with backslash escapes, which the parser decodes
ESTRING: 'e' ESTRING_BODY (STRING_SEPARATOR ESTRING_BODY)*;

BCONST: 'b' '\'' ~'\''* '\'';
XCONST: 'x' '\'' ~'\''* '\'';
USCONST: 'u' '&' '\'' ('\'\'' | ~'\'')* '\'';
UIDENT: 'u' '&' '"' ('""' | ~'"')* '"';

fragment STRING_BODY: '\'' ('\'\'' | ~'\'')* '\'';
fragment ESTRING_BODY: '\'' ('\'\'' | '\\' . | ~['\\])* '\'';
fragment STRING_SEPARATOR
  : [ \t\f\u000B]* ('--' ~[\r\n]*)? [\r\n] ([ \t\r\n\f\u000B] | '--' ~[\r\n]* [\r\n])*
  ;

// ============================================================================
// KEYWORDS (src/include/parser/kwlist.h of PostgreSQL 18, 494 words)
// ============================================================================

ABORT: 'abort';
ABSENT: 'absent';
ABSOLUTE: 'absolute';
ACCESS: 'access';
ACTION: 'action';
ADD: 'add';
ADMIN: 'admin';
AFTER: 'after';
AGGREGATE: 'aggregate';
ALL: 'all';
ALSO: 'also';
ALTER: 'alter';
ALWAYS: 'always';
ANALYSE: 'analyse';
ANALYZE: 'analyze';
AND: 'and';
ANY: 'any';
ARRAY: 'array';
AS: 'as';
ASC: 'asc';
ASENSITIVE: 'asensitive';
ASSERTION: 'assertion';
ASSIGNMENT: 'assignment';
ASYMMETRIC: 'asymmetric';
AT: 'at';
ATOMIC: 'atomic';
ATTACH: 'attach';
ATTRIBUTE: 'attribute';
AUTHORIZATION: 'authorization';
BACKWARD: 'backward';
BEFORE: 'before';
BEGIN: 'begin';
BETWEEN: 'between';
BIGINT: 'bigint';
BINARY: 'binary';
BIT: 'bit';
BOOLEAN: 'boolean';
BOTH: 'both';
BREADTH: 'breadth';
BY: 'by';
CACHE: 'cache';
CALL: 'call';
CALLED: 'called';
CASCADE: 'cascade';
CASCADED: 'cascaded';
CASE: 'case';
CAST: 'cast';
CATALOG: 'catalog';
CHAIN: 'chain';
CHAR: 'char';
CHARACTER: 'character';
CHARACTERISTICS: 'characteristics';
CHECK: 'check';
CHECKPOINT: 'checkpoint';
CLASS: 'class';
CLOSE: 'close';
CLUSTER: 'cluster';
COALESCE: 'coalesce';
COLLATE: 'collate';
COLLATION: 'collation';
COLUMN: 'column';
COLUMNS: 'columns';
COMMENT: 'comment';
COMMENTS: 'comments';
COMMIT: 'commit';
COMMITTED: 'committed';
COMPRESSION: 'compression';
CONCURRENTLY: 'concurrently';
CONDITIONAL: 'conditional';
CONFIGURATION: 'configuration';
CONFLICT: 'conflict';
CONNECTION: 'connection';
CONSTRAINT: 'constraint';
CONSTRAINTS: 'constraints';
CONTENT: 'content';
CONTINUE: 'continue';
CONVERSION: 'conversion';
COPY: 'copy';
COST: 'cost';
CREATE: 'create';
CROSS: 'cross';
CSV: 'csv';
CUBE: 'cube';
CURRENT: 'current';
CURRENT_CATALOG: 'current_catalog';
CURRENT_DATE: 'current_date';
CURRENT_ROLE: 'current_role';
CURRENT_SCHEMA: 'current_schema';
CURRENT_TIME: 'current_time';
CURRENT_TIMESTAMP: 'current_timestamp';
CURRENT_USER: 'current_user';
CURSOR: 'cursor';
CYCLE: 'cycle';
DATA: 'data';
DATABASE: 'database';
DAY: 'day';
DEALLOCATE: 'deallocate';
DEC: 'dec';
DECIMAL: 'decimal';
DECLARE: 'declare';
DEFAULT: 'default';
DEFAULTS: 'defaults';
DEFERRABLE: 'deferrable';
DEFERRED: 'deferred';
DEFINER: 'definer';
DELETE: 'delete';
DELIMITER: 'delimiter';
DELIMITERS: 'delimiters';
DEPENDS: 'depends';
DEPTH: 'depth';
DESC: 'desc';
DETACH: 'detach';
DICTIONARY: 'dictionary';
DISABLE: 'disable';
DISCARD: 'discard';
DISTINCT: 'distinct';
DO: 'do';
DOCUMENT: 'document';
DOMAIN: 'domain';
DOUBLE: 'double';
DROP: 'drop';
EACH: 'each';
ELSE: 'else';
EMPTY: 'empty';
ENABLE: 'enable';
ENCODING: 'encoding';
ENCRYPTED: 'encrypted';
END: 'end';
ENFORCED: 'enforced';
ENUM: 'enum';
ERROR: 'error';
ESCAPE: 'escape';
EVENT: 'event';
EXCEPT: 'except';
EXCLUDE: 'exclude';
EXCLUDING: 'excluding';
EXCLUSIVE: 'exclusive';
EXECUTE: 'execute';
EXISTS: 'exists';
EXPLAIN: 'explain';
EXPRESSION: 'expression';
EXTENSION: 'extension';
EXTERNAL: 'external';
EXTRACT: 'extract';
FALSE: 'false';
FAMILY: 'family';
FETCH: 'fetch';
FILTER: 'filter';
FINALIZE: 'finalize';
FIRST: 'first';
FLOAT: 'float';
FOLLOWING: 'following';
FOR: 'for';
FORCE: 'force';
FOREIGN: 'foreign';
FORMAT: 'format';
FORWARD: 'forward';
FREEZE: 'freeze';
FROM: 'from';
FULL: 'full';
FUNCTION: 'function';
FUNCTIONS: 'functions';
GENERATED: 'generated';
GLOBAL: 'global';
GRANT: 'grant';
GRANTED: 'granted';
GREATEST: 'greatest';
GROUP: 'group';
GROUPING: 'grouping';
GROUPS: 'groups';
HANDLER: 'handler';
HAVING: 'having';
HEADER: 'header';
HOLD: 'hold';
HOUR: 'hour';
IDENTITY: 'identity';
IF: 'if';
ILIKE: 'ilike';
IMMEDIATE: 'immediate';
IMMUTABLE: 'immutable';
IMPLICIT: 'implicit';
IMPORT: 'import';
IN: 'in';
INCLUDE: 'include';
INCLUDING: 'including';
INCREMENT: 'increment';
INDENT: 'indent';
INDEX: 'index';
INDEXES: 'indexes';
INHERIT: 'inherit';
INHERITS: 'inherits';
INITIALLY: 'initially';
INLINE: 'inline';
INNER: 'inner';
INOUT: 'inout';
INPUT: 'input';
INSENSITIVE: 'insensitive';
INSERT: 'insert';
INSTEAD: 'instead';
INT: 'int';
INTEGER: 'integer';
INTERSECT: 'intersect';
INTERVAL: 'interval';
INTO: 'into';
INVOKER: 'invoker';
IS: 'is';
ISNULL: 'isnull';
ISOLATION: 'isolation';
JOIN: 'join';
JSON: 'json';
JSON_ARRAY: 'json_array';
JSON_ARRAYAGG: 'json_arrayagg';
JSON_EXISTS: 'json_exists';
JSON_OBJECT: 'json_object';
JSON_OBJECTAGG: 'json_objectagg';
JSON_QUERY: 'json_query';
JSON_SCALAR: 'json_scalar';
JSON_SERIALIZE: 'json_serialize';
JSON_TABLE: 'json_table';
JSON_VALUE: 'json_value';
KEEP: 'keep';
KEY: 'key';
KEYS: 'keys';
LABEL: 'label';
LANGUAGE: 'language';
LARGE: 'large';
LAST: 'last';
LATERAL: 'lateral';
LEADING: 'leading';
LEAKPROOF: 'leakproof';
LEAST: 'least';
LEFT: 'left';
LEVEL: 'level';
LIKE: 'like';
LIMIT: 'limit';
LISTEN: 'listen';
LOAD: 'load';
LOCAL: 'local';
LOCALTIME: 'localtime';
LOCALTIMESTAMP: 'localtimestamp';
LOCATION: 'location';
LOCK: 'lock';
LOCKED: 'locked';
LOGGED: 'logged';
MAPPING: 'mapping';
MATCH: 'match';
MATCHED: 'matched';
MATERIALIZED: 'materialized';
MAXVALUE: 'maxvalue';
MERGE: 'merge';
MERGE_ACTION: 'merge_action';
METHOD: 'method';
MINUTE: 'minute';
MINVALUE: 'minvalue';
MODE: 'mode';
MONTH: 'month';
MOVE: 'move';
NAME: 'name';
NAMES: 'names';
NATIONAL: 'national';
NATURAL: 'natural';
NCHAR: 'nchar';
NESTED: 'nested';
NEW: 'new';
NEXT: 'next';
NFC: 'nfc';
NFD: 'nfd';
NFKC: 'nfkc';
NFKD: 'nfkd';
NO: 'no';
NONE: 'none';
NORMALIZE: 'normalize';
NORMALIZED: 'normalized';
NOT: 'not';
NOTHING: 'nothing';
NOTIFY: 'notify';
NOTNULL: 'notnull';
NOWAIT: 'nowait';
NULL: 'null';
NULLIF: 'nullif';
NULLS: 'nulls';
NUMERIC: 'numeric';
OBJECT: 'object';
OBJECTS: 'objects';
OF: 'of';
OFF: 'off';
OFFSET: 'offset';
OIDS: 'oids';
OLD: 'old';
OMIT: 'omit';
ON: 'on';
ONLY: 'only';
OPERATOR: 'operator';
OPTION: 'option';
OPTIONS: 'options';
OR: 'or';
ORDER: 'order';
ORDINALITY: 'ordinality';
OTHERS: 'others';
OUT: 'out';
OUTER: 'outer';
OVER: 'over';
OVERLAPS: 'overlaps';
OVERLAY: 'overlay';
OVERRIDING: 'overriding';
OWNED: 'owned';
OWNER: 'owner';
PARALLEL: 'parallel';
PARAMETER: 'parameter';
PARSER: 'parser';
PARTIAL: 'partial';
PARTITION: 'partition';
PASSING: 'passing';
PASSWORD: 'password';
PATH: 'path';
PERIOD: 'period';
PLACING: 'placing';
PLAN: 'plan';
PLANS: 'plans';
POLICY: 'policy';
POSITION: 'position';
PRECEDING: 'preceding';
PRECISION: 'precision';
PREPARE: 'prepare';
PREPARED: 'prepared';
PRESERVE: 'preserve';
PRIMARY: 'primary';
PRIOR: 'prior';
PRIVILEGES: 'privileges';
PROCEDURAL: 'procedural';
PROCEDURE: 'procedure';
PROCEDURES: 'procedures';
PROGRAM: 'program';
PUBLICATION: 'publication';
QUOTE: 'quote';
QUOTES: 'quotes';
RANGE: 'range';
READ: 'read';
REAL: 'real';
REASSIGN: 'reassign';
RECURSIVE: 'recursive';
REF: 'ref';
REFERENCES: 'references';
REFERENCING: 'referencing';
REFRESH: 'refresh';
REINDEX: 'reindex';
RELATIVE: 'relative';
RELEASE: 'release';
RENAME: 'rename';
REPEATABLE: 'repeatable';
REPLACE: 'replace';
REPLICA: 'replica';
RESET: 'reset';
RESTART: 'restart';
RESTRICT: 'restrict';
RETURN: 'return';
RETURNING: 'returning';
RETURNS: 'returns';
REVOKE: 'revoke';
RIGHT: 'right';
ROLE: 'role';
ROLLBACK: 'rollback';
ROLLUP: 'rollup';
ROUTINE: 'routine';
ROUTINES: 'routines';
ROW: 'row';
ROWS: 'rows';
RULE: 'rule';
SAVEPOINT: 'savepoint';
SCALAR: 'scalar';
SCHEMA: 'schema';
SCHEMAS: 'schemas';
SCROLL: 'scroll';
SEARCH: 'search';
SECOND: 'second';
SECURITY: 'security';
SELECT: 'select';
SEQUENCE: 'sequence';
SEQUENCES: 'sequences';
SERIALIZABLE: 'serializable';
SERVER: 'server';
SESSION: 'session';
SESSION_USER: 'session_user';
SET: 'set';
SETOF: 'setof';
SETS: 'sets';
SHARE: 'share';
SHOW: 'show';
SIMILAR: 'similar';
SIMPLE: 'simple';
SKIP_KW: 'skip';
SMALLINT: 'smallint';
SNAPSHOT: 'snapshot';
SOME: 'some';
SOURCE: 'source';
SQL: 'sql';
STABLE: 'stable';
STANDALONE: 'standalone';
START: 'start';
STATEMENT: 'statement';
STATISTICS: 'statistics';
STDIN: 'stdin';
STDOUT: 'stdout';
STORAGE: 'storage';
STORED: 'stored';
STRICT: 'strict';
STRING: 'string';
STRIP: 'strip';
SUBSCRIPTION: 'subscription';
SUBSTRING: 'substring';
SUPPORT: 'support';
SYMMETRIC: 'symmetric';
SYSID: 'sysid';
SYSTEM: 'system';
SYSTEM_USER: 'system_user';
TABLE: 'table';
TABLES: 'tables';
TABLESAMPLE: 'tablesample';
TABLESPACE: 'tablespace';
TARGET: 'target';
TEMP: 'temp';
TEMPLATE: 'template';
TEMPORARY: 'temporary';
TEXT: 'text';
THEN: 'then';
TIES: 'ties';
TIME: 'time';
TIMESTAMP: 'timestamp';
TO: 'to';
TRAILING: 'trailing';
TRANSACTION: 'transaction';
TRANSFORM: 'transform';
TREAT: 'treat';
TRIGGER: 'trigger';
TRIM: 'trim';
TRUE: 'true';
TRUNCATE: 'truncate';
TRUSTED: 'trusted';
TYPE: 'type';
TYPES: 'types';
UESCAPE: 'uescape';
UNBOUNDED: 'unbounded';
UNCOMMITTED: 'uncommitted';
UNCONDITIONAL: 'unconditional';
UNENCRYPTED: 'unencrypted';
UNION: 'union';
UNIQUE: 'unique';
UNKNOWN: 'unknown';
UNLISTEN: 'unlisten';
UNLOGGED: 'unlogged';
UNTIL: 'until';
UPDATE: 'update';
USER: 'user';
USING: 'using';
VACUUM: 'vacuum';
VALID: 'valid';
VALIDATE: 'validate';
VALIDATOR: 'validator';
VALUE: 'value';
VALUES: 'values';
VARCHAR: 'varchar';
VARIADIC: 'variadic';
VARYING: 'varying';
VERBOSE: 'verbose';
VERSION: 'version';
VIEW: 'view';
VIEWS: 'views';
VIRTUAL: 'virtual';
VOLATILE: 'volatile';
WHEN: 'when';
WHERE: 'where';
WHITESPACE: 'whitespace';
WINDOW: 'window';
WITH: 'with';
WITHIN: 'within';
WITHOUT: 'without';
WORK: 'work';
WRAPPER: 'wrapper';
WRITE: 'write';
XML: 'xml';
XMLATTRIBUTES: 'xmlattributes';
XMLCONCAT: 'xmlconcat';
XMLELEMENT: 'xmlelement';
XMLEXISTS: 'xmlexists';
XMLFOREST: 'xmlforest';
XMLNAMESPACES: 'xmlnamespaces';
XMLPARSE: 'xmlparse';
XMLPI: 'xmlpi';
XMLROOT: 'xmlroot';
XMLSERIALIZE: 'xmlserialize';
XMLTABLE: 'xmltable';
YEAR: 'year';
YES: 'yes';
ZONE: 'zone';

// ============================================================================
// IDENTIFIERS, WHITESPACE AND COMMENTS
// ============================================================================

IDENT: IDENT_START IDENT_CONT*;

QUOTED_IDENT: '"' ('""' | ~'"')* '"';

fragment IDENT_START: [a-z_] | ~[\u0000-\u007F];
fragment IDENT_CONT: [a-z_0-9$] | ~[\u0000-\u007F];

WS: [ \t\r\n\f\u000B]+ -> channel(HIDDEN);
LINE_COMMENT: '--' ~[\r\n]* -> channel(HIDDEN);
BLOCK_COMMENT: '/*' (BLOCK_COMMENT | .)*? '*/' -> channel(HIDDEN);

// anything else: the parser reports it, as PostgreSQL does for a character it does not know
UNRECOGNIZED: .;
