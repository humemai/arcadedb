<!--
Written by .github/scripts/pg_regress_page.py from e2e/src/test/resources/pgregress/result. Do not edit by hand.

The quoted statements are from PostgreSQL's regression tests, under the PostgreSQL License.

SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
SPDX-FileCopyrightText: 1996-2026 PostgreSQL Global Development Group
SPDX-FileCopyrightText: 1994 The Regents of the University of California
SPDX-License-Identifier: Apache-2.0 AND PostgreSQL
-->

# PostgreSQL's regression files on the `postgresql` language

Each statement of PostgreSQL's regression files ran on PostgreSQL and on the `postgresql` language of this fork, and got one verdict by the [rules](postgresql-regression-rules.md). This page holds the counts of one run. Fork only (`humemai/arcadedb`, a prototype).

| | |
| --- | --- |
| PostgreSQL | PostgreSQL 18.7 (Debian 18.7-1.pgdg13+5), `C` collation |
| regression files | tag `REL_18_7`, commit `f5c41f6681`, 237 files |
| engine | [`d97de6b0e2`](https://github.com/humemai/arcadedb/commit/d97de6b0e2a77fa18a93fd74e80915bb6d9f13ad) |
| CI run | [45965628114](https://github.com/humemai/arcadedb/actions/runs/45965628114) |
| date | 2026-11-27 (UTC) |

## Counts

| verdict | statements | share of all statements |
| --- | ---: | ---: |
| passed | 6,070 | 11.8% |
| refused, `0A000` | 20,707 | 40.1% |
| refused, other code | 4,186 | 8.1% |
| wrong | 1,508 | 2.9% |
| blocked | 14,528 | 28.2% |
| out of scope | 4,599 | 8.9% |
| all | 51,598 | 100% |

| verdict | rule, in short |
| --- | --- |
| passed | the same result on both, or the same SQLSTATE on both |
| refused | an error where PostgreSQL succeeds, or `0A000` where PostgreSQL raises another error |
| wrong | another result or other table contents, success where PostgreSQL raises an error, another code than PostgreSQL (`0A000` aside), a crash, or a timeout |
| blocked | not judged: a table it uses differs between the two sides and cannot be copied |
| out of scope | one of the entries of a closed list |

32,471 statements were judged, and 4,664 of them on a table that the harness had copied from PostgreSQL into ArcadeDB through the engine's own API. 2,813 `psql` commands never reach a server and are not statements, and 477 statements that `psql` does not send are not counted either.

A check of the harness: in the same CI run, with a second PostgreSQL server as the side under test, all 237 files gave only passed (47,000 statements) and out of scope (4,598).

## Wrong statements, by cause

1,508 statements are wrong, in 32 groups of one cause each. Each group has the issue that tracks it, with an example. A statement is named by its file and the line of the file where it starts, and is quoted on one line, cut after 120 characters. The two codes are the SQLSTATE of PostgreSQL and the one of `postgresql`; `00000` is success.

| cause | issue | wrong statements |
| --- | --- | ---: |
| A function or operator that the language lacks answers 42883 where PostgreSQL raises another error | [#198](https://github.com/humemai/arcadedb/issues/198) | 763 |
| A relation that a refused statement would have made answers 42P01 where PostgreSQL raises another error | [#199](https://github.com/humemai/arcadedb/issues/199) | 229 |
| After a refused SET, a later statement answers as without the setting | [#181](https://github.com/humemai/arcadedb/issues/181) | 129 |
| Date, time, and interval text that is out of range answers 22007 | [#200](https://github.com/humemai/arcadedb/issues/200) | 93 |
| Text that the parser does not read answers 42601 instead of 0A000 | [#202](https://github.com/humemai/arcadedb/issues/202) | 56 |
| A statement that PostgreSQL refuses for a missing privilege succeeds | [#176](https://github.com/humemai/arcadedb/issues/176) | 45 |
| information_schema answers without the views and schemas that were refused | [#184](https://github.com/humemai/arcadedb/issues/184) | 31 |
| COMMIT succeeds where a check that PostgreSQL makes at COMMIT fails | [#179](https://github.com/humemai/arcadedb/issues/179) | 24 |
| A binary number that is out of range answers 22P02 instead of 22003 | [#201](https://github.com/humemai/arcadedb/issues/201) | 18 |
| The column of a cast of a cast is named after the inner type | [#185](https://github.com/humemai/arcadedb/issues/185) | 17 |
| An UPDATE or DELETE that a publication forbids succeeds | [#177](https://github.com/humemai/arcadedb/issues/177) | 17 |
| Values that PostgreSQL finds out of range are accepted | [#197](https://github.com/humemai/arcadedb/issues/197) | 12 |
| A write into a table with a rule succeeds without the rule | [#182](https://github.com/humemai/arcadedb/issues/182) | 12 |
| Too few parameters in a Bind answer 42P02 instead of 08P01 | [#204](https://github.com/humemai/arcadedb/issues/204) | 8 |
| sqrt of a numeric answers double precision | [#187](https://github.com/humemai/arcadedb/issues/187) | 8 |
| A cast that the language lacks answers 42846 | [#203](https://github.com/humemai/arcadedb/issues/203) | 7 |
| A date with a two-digit year is read as a year of the first century | [#193](https://github.com/humemai/arcadedb/issues/193) | 6 |
| A reference to a whole row answers 42703 instead of 0A000 | [#205](https://github.com/humemai/arcadedb/issues/205) | 6 |
| power(1, NaN) and power(-1, infinity) answer NaN instead of 1 | [#188](https://github.com/humemai/arcadedb/issues/188) | 5 |
| EXTRACT of a time zone from a date answers 22023 instead of 0A000 | [#206](https://github.com/humemai/arcadedb/issues/206) | 3 |
| information_schema does not report identity columns as PostgreSQL does | [#183](https://github.com/humemai/arcadedb/issues/183) | 3 |
| DISTINCT and GROUP BY keep two jsonb values that PostgreSQL treats as equal | [#189](https://github.com/humemai/arcadedb/issues/189) | 3 |
| A copied unique key loses NULLS NOT DISTINCT | [#180](https://github.com/humemai/arcadedb/issues/180) | 2 |
| After a refused CREATE FUNCTION the built-in function of the same name answers | [#191](https://github.com/humemai/arcadedb/issues/191) | 2 |
| A temporary table that PostgreSQL empties at COMMIT keeps its rows | [#178](https://github.com/humemai/arcadedb/issues/178) | 2 |
| A constant division by zero in a branch that never runs succeeds | [#196](https://github.com/humemai/arcadedb/issues/196) | 1 |
| CREATE TABLE accepts two constraints of one name | [#195](https://github.com/humemai/arcadedb/issues/195) | 1 |
| ON CONFLICT on a table named excluded answers 42702 | [#207](https://github.com/humemai/arcadedb/issues/207) | 1 |
| EXISTS over a table without columns is not named exists | [#186](https://github.com/humemai/arcadedb/issues/186) | 1 |
| LOCALTIMESTAMP is read from the clock for each call | [#190](https://github.com/humemai/arcadedb/issues/190) | 1 |
| An outer aggregate over a nested WITH succeeds where PostgreSQL refuses it | [#194](https://github.com/humemai/arcadedb/issues/194) | 1 |
| row_number over tied rows numbers them in another order | [#192](https://github.com/humemai/arcadedb/issues/192) | 1 |
| all | | 1,508 |

<details>
<summary>#198: 763 statements</summary>

A function or operator that the language lacks answers 42883 where PostgreSQL raises another error

| file and line | codes | statement |
| --- | --- | --- |
| [name:67](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L67) | 22023 / 42883 | `SELECT parse_ident('foo.boo[]');` |
| [name:71](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L71) | 22023 / 42883 | `SELECT parse_ident(' ');` |
| [name:72](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L72) | 22023 / 42883 | `SELECT parse_ident(' .aaa');` |
| [name:73](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L73) | 22023 / 42883 | `SELECT parse_ident(' aaa . ');` |
| [name:74](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L74) | 22023 / 42883 | `SELECT parse_ident('aaa.a%b');` |
| [name:75](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L75) | 22023 / 42883 | `SELECT parse_ident(E'X\rXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX');` |
| [name:82](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L82) | 22023 / 42883 | `SELECT parse_ident(E'"c".X XXXX\002XXXXXX');` |
| [name:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L83) | 22023 / 42883 | `SELECT parse_ident('1020');` |
| [name:84](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L84) | 22023 / 42883 | `SELECT parse_ident('10.20');` |
| [name:85](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L85) | 22023 / 42883 | `SELECT parse_ident('.');` |
| [name:86](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L86) | 22023 / 42883 | `SELECT parse_ident('.1020');` |
| [name:87](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/name.sql#L87) | 22023 / 42883 | `SELECT parse_ident('xxx.1020');` |
| [text:64](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L64) | 22023 / 42883 | `select format('Hello %s %s', 'World');` |
| [text:65](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L65) | 22023 / 42883 | `select format('Hello %s');` |
| [text:66](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L66) | 22023 / 42883 | `select format('Hello %x', 20);` |
| [text:73](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L73) | 22004 / 42883 | `select format('INSERT INTO %I VALUES(%L,%L)', NULL, 10, 'Hello');` |
| [text:78](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L78) | 22023 / 42883 | `select format('%1$s %4$s', 1, 2, 3);` |
| [text:79](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L79) | 22023 / 42883 | `select format('%1$s %13$s', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);` |
| [text:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L80) | 22023 / 42883 | `select format('%0$s', 'Hello');` |
| [text:81](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L81) | 22023 / 42883 | `select format('%*0$s', 'Hello');` |
| [text:82](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L82) | 22023 / 42883 | `select format('%1$', 1);` |
| [text:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/text.sql#L83) | 22023 / 42883 | `select format('%1$1', 1);` |
| [int4:131](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L131) | 22003 / 42883 | `SELECT to_char(3, '9V999999999');` |
| [int4:133](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L133) | 22003 / 42883 | `SELECT to_char(2147483647, '9V9');` |
| [int4:135](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L135) | 22003 / 42883 | `SELECT to_char((-2147483648)::int4, '9V9');` |
| [int4:136](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L136) | 22003 / 42883 | `SELECT to_char(1, '9V9999999999');` |
| [int4:168](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L168) | 22003 / 42883 | `SELECT gcd((-2147483648)::int4, 0::int4);` |
| [int4:169](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L169) | 22003 / 42883 | `SELECT gcd((-2147483648)::int4, (-2147483648)::int4);` |
| [int4:180](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L180) | 22003 / 42883 | `SELECT lcm((-2147483648)::int4, 1::int4);` |
| [int4:181](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L181) | 22003 / 42883 | `SELECT lcm(2147483647::int4, 2147483646::int4);` |
| [int8:249](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L249) | 22003 / 42883 | `SELECT gcd((-9223372036854775808)::int8, 0::int8);` |
| [int8:250](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L250) | 22003 / 42883 | `SELECT gcd((-9223372036854775808)::int8, (-9223372036854775808)::int8);` |
| [int8:261](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L261) | 22003 / 42883 | `SELECT lcm((-9223372036854775808)::int8, 1::int8);` |
| [int8:262](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L262) | 22003 / 42883 | `SELECT lcm(9223372036854775807::int8, 9223372036854775806::int8);` |
| [float8:192](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L192) | 2201E / 42883 | `SELECT ln(f.f1) from FLOAT8_TBL f where f.f1 = '0.0' ;` |
| [float8:194](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L194) | 2201E / 42883 | `SELECT ln(f.f1) from FLOAT8_TBL f where f.f1 < '0.0' ;` |
| [float8:196](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L196) | 22003 / 42883 | `SELECT exp(f.f1) from FLOAT8_TBL f;` |
| [float8:226](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L226) | 22003 / 42883 | `SELECT acosh(float8 '-infinity');` |
| [float8:228](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L228) | 22003 / 42883 | `SELECT atanh(float8 'infinity');` |
| [float8:229](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L229) | 22003 / 42883 | `SELECT atanh(float8 '-infinity');` |
| [float8:258](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L258) | 22003 / 42883 | `SELECT gamma(float8 '-infinity');` |
| [float8:260](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L260) | 22003 / 42883 | `SELECT gamma(float8 '-1000.5');` |
| [float8:262](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L262) | 22003 / 42883 | `SELECT gamma(float8 '-1');` |
| [float8:263](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L263) | 22003 / 42883 | `SELECT lgamma(float8 '-1');` |
| [float8:264](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L264) | 22003 / 42883 | `SELECT gamma(float8 '0');` |
| [float8:265](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L265) | 22003 / 42883 | `SELECT lgamma(float8 '0');` |
| [float8:266](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L266) | 22003 / 42883 | `SELECT gamma(float8 '1000');` |
| [float8:268](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L268) | 22003 / 42883 | `SELECT lgamma(float8 '1e308');` |
| [numeric:670](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L670) | 22012 / 42883 | `SELECT div('0'::numeric, '0');` |
| [numeric:706](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L706) | 2201E / 42883 | `SELECT ln('0'::numeric);` |
| [numeric:707](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L707) | 2201E / 42883 | `SELECT ln('-1'::numeric);` |
| [numeric:716](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L716) | 2201E / 42883 | `SELECT log('0'::numeric, '10');` |
| [numeric:717](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L717) | 2201E / 42883 | `SELECT log('10'::numeric, '0');` |
| [numeric:719](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L719) | 2201E / 42883 | `SELECT log('10'::numeric, '-inf');` |
| [numeric:730](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L730) | 2201F / 42883 | `SELECT power('0'::numeric, '-1');` |
| [numeric:731](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L731) | 2201F / 42883 | `SELECT power('0'::numeric, '-inf');` |
| [numeric:734](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L734) | 2201F / 42883 | `SELECT power('-2'::numeric, '3.3');` |
| [numeric:736](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L736) | 2201F / 42883 | `SELECT power('-2'::numeric, '-1.5');` |
| [numeric:865](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L865) | 2201G / 42883 | `SELECT width_bucket(5.0, 3.0, 4.0, 0);` |
| [numeric:866](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L866) | 2201G / 42883 | `SELECT width_bucket(5.0, 3.0, 4.0, -5);` |
| [numeric:867](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L867) | 2201G / 42883 | `SELECT width_bucket(3.5, 3.0, 3.0, 888);` |
| [numeric:868](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L868) | 2201G / 42883 | `SELECT width_bucket(5.0::float8, 3.0::float8, 4.0::float8, 0);` |
| [numeric:869](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L869) | 2201G / 42883 | `SELECT width_bucket(5.0::float8, 3.0::float8, 4.0::float8, -5);` |
| [numeric:870](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L870) | 2201G / 42883 | `SELECT width_bucket(3.5::float8, 3.0::float8, 3.0::float8, 888);` |
| [numeric:871](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L871) | 2201G / 42883 | `SELECT width_bucket('NaN', 3.0, 4.0, 888);` |
| [numeric:872](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L872) | 2201G / 42883 | `SELECT width_bucket(0::float8, 'NaN', 4.0::float8, 888);` |
| [numeric:873](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L873) | 2201G / 42883 | `SELECT width_bucket(2.0, 3.0, '-inf', 888);` |
| [numeric:874](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L874) | 2201G / 42883 | `SELECT width_bucket(0::float8, '-inf', 4.0::float8, 888);` |
| [numeric:923](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L923) | 2201G / 42883 | `SELECT width_bucket(0.0::float8, 'Infinity'::float8, 5, 10);` |
| [numeric:924](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L924) | 2201G / 42883 | `SELECT width_bucket(0.0::float8, 5, '-Infinity'::float8, 20);` |
| [numeric:962](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L962) | 22003 / 42883 | `SELECT width_bucket(1::float8, 0, 1, 2147483647);` |
| [numeric:963](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L963) | 22003 / 42883 | `SELECT width_bucket(0::float8, 1, 0, 2147483647);` |
| [numeric:1103](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1103) | 22P02 / 42883 | `SELECT to_number('viv', 'RN');` |
| [numeric:1104](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1104) | 22P02 / 42883 | `SELECT to_number('DCCCD', 'RN');` |
| [numeric:1105](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1105) | 22P02 / 42883 | `SELECT to_number('XIXL', 'RN');` |
| [numeric:1106](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1106) | 22P02 / 42883 | `SELECT to_number('MCCM', 'RN');` |
| [numeric:1107](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1107) | 22P02 / 42883 | `SELECT to_number('MMMM', 'RN');` |
| [numeric:1108](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1108) | 22P02 / 42883 | `SELECT to_number('VV', 'RN');` |
| [numeric:1109](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1109) | 22P02 / 42883 | `SELECT to_number('IL', 'RN');` |
| [numeric:1110](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1110) | 22P02 / 42883 | `SELECT to_number('VIX', 'RN');` |
| [numeric:1111](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1111) | 22P02 / 42883 | `SELECT to_number('LXC', 'RN');` |
| [numeric:1112](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1112) | 22P02 / 42883 | `SELECT to_number('DCM', 'RN');` |
| [numeric:1113](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1113) | 22P02 / 42883 | `SELECT to_number('MMMDCM', 'RN');` |
| [numeric:1114](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1114) | 22P02 / 42883 | `SELECT to_number('CLXC', 'RN');` |
| [numeric:1115](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1115) | 42601 / 42883 | `SELECT to_number('CM', 'MIRN');` |
| [numeric:1116](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1116) | 42601 / 42883 | `SELECT to_number('CM', 'RNRN');` |
| [numeric:1117](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1117) | 22P02 / 42883 | `SELECT to_number('qiv', 'RN');` |
| [numeric:1118](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1118) | 22P02 / 42883 | `SELECT to_number('', 'RN');` |
| [numeric:1119](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1119) | 22P02 / 42883 | `SELECT to_number(' ', 'RN');` |
| [numeric:1394](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1394) | 2201E / 42883 | `select ln(-12.34);` |
| [numeric:1395](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1395) | 2201E / 42883 | `select ln(0.0);` |
| [numeric:1412](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1412) | 2201E / 42883 | `select log(-12.34);` |
| [numeric:1413](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1413) | 2201E / 42883 | `select log(0.0);` |
| [numeric:1428](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1428) | 2201E / 42883 | `select log(-12.34, 56.78);` |
| [numeric:1429](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1429) | 2201E / 42883 | `select log(-12.34, -56.78);` |
| [numeric:1430](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1430) | 2201E / 42883 | `select log(12.34, -56.78);` |
| [numeric:1431](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1431) | 2201E / 42883 | `select log(0.0, 12.34);` |
| [numeric:1432](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1432) | 2201E / 42883 | `select log(12.34, 0.0);` |
| [numeric:1433](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1433) | 22012 / 42883 | `select log(1.0, 12.34);` |
| [numeric:1563](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1563) | 22003 / 42883 | `SELECT lcm(9999 * (10::numeric)^131068 + (10::numeric^131068 - 1), 2);` |
| [numeric:1570](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1570) | 22003 / 42883 | `SELECT factorial(100000);` |
| [numeric:1572](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1572) | 22003 / 42883 | `SELECT factorial(-4);` |
| [numeric:1580](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1580) | 22023 / 42883 | `SELECT pg_lsn(-1::numeric);` |
| [numeric:1581](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1581) | 22023 / 42883 | `SELECT pg_lsn(18446744073709551616::numeric);` |
| [uuid:139](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/uuid.sql#L139) | 22008 / 42883 | `SELECT uuidv7('-1000 years'::interval);` |
| [uuid:142](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/uuid.sql#L142) | 22008 / 42883 | `SELECT uuidv7('9000 years'::interval);` |
| [rangetypes:92](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangetypes.sql#L92) | 22000 / 42883 | `select numrange(2.0, 1.0);` |
| [rangetypes:122](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangetypes.sql#L122) | 22000 / 42883 | `select numrange(1.0, 2.0) + numrange(2.5, 3.0);` |
| [rangetypes:566](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangetypes.sql#L566) | 22000 / 42883 | `select arrayrange(ARRAY[2,1], ARRAY[1,2]);` |
| [regproc:66](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L66) | 42P01 / 42883 | `SELECT regclass('pg_classes');` |
| [regproc:67](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L67) | 42704 / 42883 | `SELECT regtype('int3');` |
| [regproc:75](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L75) | 42P01 / 42883 | `SELECT regclass('ng_catalog.pg_class');` |
| [regproc:76](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L76) | 3F000 / 42883 | `SELECT regtype('ng_catalog.int4');` |
| [regproc:78](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L78) | 42704 / 42883 | `SELECT regcollation('ng_catalog."POSIX"');` |
| [regproc:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L83) | 42704 / 42883 | `SELECT regrole('regress_regrole_test');` |
| [regproc:84](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L84) | 42704 / 42883 | `SELECT regrole('"regress_regrole_test"');` |
| [regproc:85](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L85) | 42704 / 42883 | `SELECT regrole('Nonexistent');` |
| [regproc:86](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L86) | 42704 / 42883 | `SELECT regrole('"Nonexistent"');` |
| [regproc:87](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L87) | 42602 / 42883 | `SELECT regrole('foo.bar');` |
| [regproc:88](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L88) | 3F000 / 42883 | `SELECT regnamespace('Nonexistent');` |
| [regproc:89](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L89) | 3F000 / 42883 | `SELECT regnamespace('"Nonexistent"');` |
| [regproc:90](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regproc.sql#L90) | 42602 / 42883 | `SELECT regnamespace('foo.bar');` |
| [strings:234](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L234) | 22023 / 42883 | `SELECT regexp_replace('AAA aaa', 'A+', 'Z', 'z');` |
| [strings:248](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L248) | 22023 / 42883 | `SELECT regexp_replace('A PostgreSQL function', 'a\|e\|i\|o\|u', 'X', -1, 0, 'i');` |
| [strings:249](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L249) | 22023 / 42883 | `SELECT regexp_replace('A PostgreSQL function', 'a\|e\|i\|o\|u', 'X', 1, -1, 'i');` |
| [strings:251](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L251) | 22023 / 42883 | `SELECT regexp_replace('A PostgreSQL function', 'a\|e\|i\|o\|u', 'X', '1');` |
| [strings:261](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L261) | 22023 / 42883 | `SELECT regexp_count('123123123123', '123', 0);` |
| [strings:262](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L262) | 22023 / 42883 | `SELECT regexp_count('123123123123', '123', -3);` |
| [strings:269](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L269) | 22023 / 42883 | `SELECT regexp_like('abc', 'a.c', 'g');` |
| [strings:294](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L294) | 22023 / 42883 | `SELECT regexp_instr('abcabcabc', 'a.c', 0, 1);` |
| [strings:295](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L295) | 22023 / 42883 | `SELECT regexp_instr('abcabcabc', 'a.c', 1, 0);` |
| [strings:296](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L296) | 22023 / 42883 | `SELECT regexp_instr('abcabcabc', 'a.c', 1, 1, -1);` |
| [strings:297](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L297) | 22023 / 42883 | `SELECT regexp_instr('abcabcabc', 'a.c', 1, 1, 2);` |
| [strings:298](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L298) | 22023 / 42883 | `SELECT regexp_instr('abcabcabc', 'a.c', 1, 1, 0, 'g');` |
| [strings:299](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L299) | 22023 / 42883 | `SELECT regexp_instr('abcabcabc', 'a.c', 1, 1, 0, '', -1);` |
| [strings:318](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L318) | 22023 / 42883 | `SELECT regexp_substr('abcabcabc', 'a.c', 0, 1);` |
| [strings:319](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L319) | 22023 / 42883 | `SELECT regexp_substr('abcabcabc', 'a.c', 1, 0);` |
| [strings:320](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L320) | 22023 / 42883 | `SELECT regexp_substr('abcabcabc', 'a.c', 1, 1, 'g');` |
| [strings:321](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L321) | 22023 / 42883 | `SELECT regexp_substr('abcabcabc', 'a.c', 1, 1, '', -1);` |
| [strings:353](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L353) | 22023 / 42883 | `SELECT regexp_matches('foobarbequebaz', $re$(bar)(beque)$re$, 'gz');` |
| [strings:354](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L354) | 2201B / 42883 | `SELECT regexp_matches('foobarbequebaz', $re$(barbeque$re$);` |
| [strings:355](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L355) | 2201B / 42883 | `SELECT regexp_matches('foobarbequebaz', $re$(bar)(beque){2,1}$re$);` |
| [strings:380](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L380) | 22023 / 42883 | `SELECT regexp_split_to_array('thE QUick bROWn FOx jUMPs ovEr The lazy dOG', 'e', 'iz');` |
| [strings:383](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L383) | 22023 / 42883 | `SELECT regexp_split_to_array('thE QUick bROWn FOx jUMPs ovEr The lazy dOG', 'e', 'g');` |
| [strings:629](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L629) | 22011 / 42883 | `SELECT substr(f1, 5, -1) from toasttest;` |
| [strings:698](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L698) | 22023 / 42883 | `select split_part('joeuser@mydatabase','@',0) AS "an error";` |
| [strings:784](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L784) | 2202E / 42883 | `SELECT get_bit('\x1234567890abcdef00'::bytea, 99);` |
| [strings:786](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L786) | 2202E / 42883 | `SELECT set_bit('\x1234567890abcdef00'::bytea, 99, 0);` |
| [strings:788](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L788) | 2202E / 42883 | `SELECT get_byte('\x1234567890abcdef00'::bytea, 99);` |
| [strings:790](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L790) | 2202E / 42883 | `SELECT set_byte('\x1234567890abcdef00'::bytea, 99, 11);` |
| [strings:883](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L883) | 54000 / 42883 | `SELECT chr(0);` |
| [strings:892](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L892) | 22011 / 42883 | `SELECT SUBSTRING('string'::bytea FROM -10 FOR -2147483646) AS "error";` |
| [strings:910](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L910) | 42601 / 42883 | `SELECT unistr('wrong: \db99');` |
| [strings:911](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L911) | 42601 / 42883 | `SELECT unistr('wrong: \db99\0061');` |
| [strings:912](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L912) | 42601 / 42883 | `SELECT unistr('wrong: \+00db99\+000061');` |
| [strings:913](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L913) | 22023 / 42883 | `SELECT unistr('wrong: \+2FFFFF');` |
| [strings:914](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L914) | 42601 / 42883 | `SELECT unistr('wrong: \udb99\u0061');` |
| [strings:915](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L915) | 42601 / 42883 | `SELECT unistr('wrong: \U0000db99\U00000061');` |
| [strings:916](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L916) | 22023 / 42883 | `SELECT unistr('wrong: \U002FFFFF');` |
| [strings:917](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L917) | 42601 / 42883 | `SELECT unistr('wrong: \xyz');` |
| [date:370](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L370) | 22008 / 42883 | `select make_date(0, 7, 15);` |
| [date:371](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L371) | 22008 / 42883 | `select make_date(2013, 2, 30);` |
| [date:372](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L372) | 22008 / 42883 | `select make_date(2013, 13, 1);` |
| [date:373](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L373) | 22008 / 42883 | `select make_date(2013, 11, -1);` |
| [date:374](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L374) | 22008 / 42883 | `SELECT make_date(-2147483648, 1, 1);` |
| [date:375](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L375) | 22008 / 42883 | `select make_time(10, 55, 100.1);` |
| [date:376](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L376) | 22008 / 42883 | `select make_time(24, 0, 2.1);` |
| [timestamp:179](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L179) | 0A000 / 42883 | `SELECT date_trunc( 'timezone', timestamp '2004-02-29 15:44:17.71393' ) AS notsupp_trunc;` |
| [timestamp:279](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L279) | 0A000 / 42883 | `SELECT date_bin('5 months'::interval, timestamp '2020-02-01 01:01:01', timestamp '2001-01-01');` |
| [timestamp:280](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L280) | 0A000 / 42883 | `SELECT date_bin('5 years'::interval, timestamp '2020-02-01 01:01:01', timestamp '2001-01-01');` |
| [timestamp:283](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L283) | 22008 / 42883 | `SELECT date_bin('0 days'::interval, timestamp '1970-01-01 01:00:00' , timestamp '1970-01-01 00:00:00');` |
| [timestamp:286](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L286) | 22008 / 42883 | `SELECT date_bin('-2 days'::interval, timestamp '1970-01-01 01:00:00' , timestamp '1970-01-01 00:00:00');` |
| [timestamp:290](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L290) | 22008 / 42883 | `select date_bin('200000000 days'::interval, '2024-02-01'::timestamp, '2024-01-01'::timestamp);` |
| [timestamp:397](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L397) | 22008 / 42883 | `select make_timestamp(0, 7, 15, 12, 30, 15);` |
| [timestamptz:459](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamptz.sql#L459) | 22023 / 42883 | `SELECT make_timestamptz(1973, 07, 15, 08, 15, 55.33, '2');` |
| [timestamptz:460](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamptz.sql#L460) | 22023 / 42883 | `SELECT make_timestamptz(2014, 12, 10, 10, 10, 10, '+16');` |
| [timestamptz:461](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamptz.sql#L461) | 22023 / 42883 | `SELECT make_timestamptz(2014, 12, 10, 10, 10, 10, '-16');` |
| [timestamptz:472](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamptz.sql#L472) | 22023 / 42883 | `SELECT make_timestamptz(1910, 12, 24, 0, 0, 0, 'Nehwon/Lankhmar');` |
| [timestamptz:603](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamptz.sql#L603) | 22008 / 42883 | `SELECT to_timestamp('NaN'::float);` |
| [interval:76](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L76) | 22008 / 42883 | `SELECT -('-2147483648 months'::interval);` |
| [interval:78](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L78) | 22008 / 42883 | `SELECT -('-2147483648 days'::interval);` |
| [interval:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L80) | 22008 / 42883 | `SELECT -('-9223372036854775808 us'::interval);` |
| [interval:82](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L82) | 22008 / 42883 | `SELECT -('-2147483647 months -2147483647 days -9223372036854775807 us'::interval);` |
| [interval:185](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L185) | 22008 / 42883 | `SELECT justify_hours(interval '2147483647 days 24 hrs');` |
| [interval:186](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L186) | 22008 / 42883 | `SELECT justify_days(interval '2147483647 months 30 days');` |
| [interval:194](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L194) | 22008 / 42883 | `SELECT justify_interval(interval '2147483647 months 30 days');` |
| [interval:195](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L195) | 22008 / 42883 | `SELECT justify_interval(interval '-2147483648 months -30 days');` |
| [interval:198](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L198) | 22008 / 42883 | `SELECT justify_interval(interval '2147483647 months -30 days 1440 hrs');` |
| [interval:199](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L199) | 22008 / 42883 | `SELECT justify_interval(interval '-2147483648 months 30 days -1440 hrs');` |
| [interval:622](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L622) | 22023 / 42883 | `SELECT EXTRACT(FORTNIGHT FROM INTERVAL '2 days');` |
| [interval:623](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L623) | 0A000 / 42883 | `SELECT EXTRACT(TIMEZONE FROM INTERVAL '2 days');` |
| [interval:753](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L753) | 22008 / 42883 | `SELECT -interval '-2147483647 months -2147483647 days -9223372036854775807 us';` |
| [interval:772](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L772) | 22008 / 42883 | `SELECT date_bin('infinity', timestamp '2001-02-16 20:38:40', timestamp '2001-02-16 20:05:00');` |
| [interval:773](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L773) | 22008 / 42883 | `SELECT date_bin('-infinity', timestamp '2001-02-16 20:38:40', timestamp '2001-02-16 20:05:00');` |
| [multirangetypes:137](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/multirangetypes.sql#L137) | 22000 / 42883 | `select nummultirange(numrange(2.0, 1.0));` |
| [multirangetypes:817](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/multirangetypes.sql#L817) | 22000 / 42883 | `select arraymultirange(arrayrange(ARRAY[2,1], ARRAY[1,2]));` |
| [horology:483](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L483) | 22007 / 42883 | `SELECT to_timestamp('97/Feb/16', 'YYMonDD');` |
| [horology:545](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L545) | 22007 / 42883 | `SELECT to_timestamp('2011-12-18 00:00 LMT', 'YYYY-MM-DD HH24:MI TZ');` |
| [horology:548](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L548) | 22007 / 42883 | `SELECT to_timestamp('2011-12-18 11:38 JUNK', 'YYYY-MM-DD HH12:MI TZ');` |
| [horology:549](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L549) | 22007 / 42883 | `SELECT to_timestamp('2011-12-18 11:38 ...', 'YYYY-MM-DD HH12:MI TZ');` |
| [horology:553](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L553) | 22007 / 42883 | `SELECT to_timestamp('2011-12-18 11:38 +xyz', 'YYYY-MM-DD HH12:MI OF');` |
| [horology:554](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L554) | 22007 / 42883 | `SELECT to_timestamp('2011-12-18 11:38 +01:xyz', 'YYYY-MM-DD HH12:MI OF');` |
| [horology:601](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L601) | 22007 / 42883 | `SELECT to_timestamp('2000 + + JUN', 'YYYY MON');` |
| [horology:616](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L616) | 22007 / 42883 | `SELECT to_date('2011 x12 x18', 'YYYYxMMxDD');` |
| [horology:623](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L623) | 22007 / 42883 | `SELECT to_timestamp('2005527', 'YYYYIWID');` |
| [horology:626](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L626) | 22007 / 42883 | `SELECT to_timestamp('19971', 'YYYYMMDD');` |
| [horology:629](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L629) | 22007 / 42883 | `SELECT to_timestamp('19971)24', 'YYYYMMDD');` |
| [horology:632](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L632) | 22007 / 42883 | `SELECT to_timestamp('Friday 1-January-1999', 'DY DD MON YYYY');` |
| [horology:633](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L633) | 22007 / 42883 | `SELECT to_timestamp('Fri 1-January-1999', 'DY DD MON YYYY');` |
| [horology:637](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L637) | 22007 / 42883 | `SELECT to_timestamp('1997-11-Jan-16', 'YYYY-MM-Mon-DD');` |
| [horology:640](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L640) | 22007 / 42883 | `SELECT to_timestamp('199711xy', 'YYYYMMDD');` |
| [horology:643](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L643) | 22008 / 42883 | `SELECT to_timestamp('10000000000', 'FMYYYY');` |
| [horology:646](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L646) | 22008 / 42883 | `SELECT to_timestamp('2016-06-13 25:00:00', 'YYYY-MM-DD HH24:MI:SS');` |
| [horology:647](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L647) | 22008 / 42883 | `SELECT to_timestamp('2016-06-13 15:60:00', 'YYYY-MM-DD HH24:MI:SS');` |
| [horology:648](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L648) | 22008 / 42883 | `SELECT to_timestamp('2016-06-13 15:50:60', 'YYYY-MM-DD HH24:MI:SS');` |
| [horology:650](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L650) | 22007 / 42883 | `SELECT to_timestamp('2016-06-13 15:50:55', 'YYYY-MM-DD HH:MI:SS');` |
| [horology:651](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L651) | 22008 / 42883 | `SELECT to_timestamp('2016-13-01 15:50:55', 'YYYY-MM-DD HH24:MI:SS');` |
| [horology:652](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L652) | 22008 / 42883 | `SELECT to_timestamp('2016-02-30 15:50:55', 'YYYY-MM-DD HH24:MI:SS');` |
| [horology:654](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L654) | 22008 / 42883 | `SELECT to_timestamp('2015-02-29 15:50:55', 'YYYY-MM-DD HH24:MI:SS');` |
| [horology:656](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L656) | 22008 / 42883 | `SELECT to_timestamp('2015-02-11 86400', 'YYYY-MM-DD SSSS');` |
| [horology:658](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L658) | 22008 / 42883 | `SELECT to_timestamp('2015-02-11 86400', 'YYYY-MM-DD SSSSS');` |
| [horology:659](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L659) | 22008 / 42883 | `SELECT to_timestamp('1000000000,999', 'Y,YYY');` |
| [horology:660](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L660) | 22008 / 42883 | `SELECT to_timestamp('0.-2147483648', 'SS.MS');` |
| [horology:661](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L661) | 22008 / 42883 | `SELECT to_timestamp('613566758', 'W');` |
| [horology:662](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L662) | 22008 / 42883 | `SELECT to_timestamp('2024 613566758 1', 'YYYY WW D');` |
| [horology:663](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L663) | 22008 / 42883 | `SELECT to_date('2016-13-10', 'YYYY-MM-DD');` |
| [horology:664](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L664) | 22008 / 42883 | `SELECT to_date('2016-02-30', 'YYYY-MM-DD');` |
| [horology:666](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L666) | 22008 / 42883 | `SELECT to_date('2015-02-29', 'YYYY-MM-DD');` |
| [horology:668](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L668) | 22008 / 42883 | `SELECT to_date('2015 366', 'YYYY DDD');` |
| [horology:671](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L671) | 22008 / 42883 | `SELECT to_date('2016 367', 'YYYY DDD');` |
| [horology:673](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L673) | 22008 / 42883 | `SELECT to_date('100000000', 'CC');` |
| [horology:674](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L674) | 22008 / 42883 | `SELECT to_date('-100000000', 'CC');` |
| [horology:675](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L675) | 22008 / 42883 | `SELECT to_date('-2147483648 01', 'CC YY');` |
| [horology:676](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L676) | 22008 / 42883 | `SELECT to_date('2147483647 01', 'CC YY');` |
| [tstypes:267](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tstypes.sql#L267) | 22004 / 42883 | `SELECT array_to_tsvector(ARRAY['base','hidden','rebel','spaceship', NULL]);` |
| [tstypes:268](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tstypes.sql#L268) | 2200F / 42883 | `SELECT array_to_tsvector(ARRAY['base','hidden','rebel','spaceship', '']);` |
| [regex:33](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/regex.sql#L33) | 22023 / 42883 | `select regexp_match('abc', 'Bd', 'ig');` |
| [unicode:20](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/unicode.sql#L20) | 22023 / 42883 | `SELECT "normalize"('abc', 'def');` |
| [unicode:38](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/unicode.sql#L38) | 22023 / 42883 | `SELECT is_normalized('abc', 'def');` |
| [stats_import:77](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/stats_import.sql#L77) | XX000 / 42883 | `SELECT pg_restore_relation_stats( 'schemaname', 'stats_import', 'relname', 'test', 'relallvisible');` |
| [stats_import:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/stats_import.sql#L83) | XX000 / 42883 | `SELECT pg_restore_relation_stats( 'schemaname', 'stats_import', 'relname', 'test', NULL, '17'::integer);` |
| [create_procedure:23](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_procedure.sql#L23) | 42809 / 42883 | `SELECT ptest1('x');` |
| [create_type:222](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_type.sql#L222) | 42704 / 42883 | `SELECT pg_input_is_valid('(1,2,3)', 'widget');` |
| [create_type:223](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_type.sql#L223) | 42704 / 42883 | `SELECT pg_input_is_valid('(1,2)', 'widget');` |
| [create_type:224](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_type.sql#L224) | 42704 / 42883 | `SELECT pg_input_is_valid('{"(1,2,3)"}', 'widget[]');` |
| [create_type:225](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_type.sql#L225) | 42704 / 42883 | `SELECT pg_input_is_valid('{"(1,2)"}', 'widget[]');` |
| [create_type:226](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_type.sql#L226) | 42704 / 42883 | `SELECT pg_input_is_valid('("(1,2,3)")', 'mytab');` |
| [create_type:227](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_type.sql#L227) | 42704 / 42883 | `SELECT pg_input_is_valid('("(1,2)")', 'mytab');` |
| [create_function_sql:504](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_function_sql.sql#L504) | 42P13 / 42883 | `SELECT test1(0);` |
| [infinite_recurse:27](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/infinite_recurse.sql#L27) | 54001 / 42883 | `select infinite_recurse();` |
| [random:106](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/random.sql#L106) | 22023 / 42883 | `SELECT random(1, 0);` |
| [random:107](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/random.sql#L107) | 22023 / 42883 | `SELECT random(1000000000001, 1000000000000);` |
| [random:108](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/random.sql#L108) | 22023 / 42883 | `SELECT random(-2.0, -3.0);` |
| [arrays:612](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L612) | 22004 / 42883 | `select array_fill(1, null, array[2,2]);` |
| [arrays:613](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L613) | 22004 / 42883 | `select array_fill(1, array[2,2], null);` |
| [arrays:614](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L614) | 2202E / 42883 | `select array_fill(1, array[2,2], '{}');` |
| [arrays:615](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L615) | 2202E / 42883 | `select array_fill(1, array[3,3], array[1,1,1]);` |
| [arrays:616](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L616) | 22004 / 42883 | `select array_fill(1, array[1,2,null]);` |
| [arrays:707](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L707) | 0A000 / 42883 | `select array_remove('{{1,2,2},{1,4,3}}', 2);` |
| [arrays:826](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L826) | 22004 / 42883 | `SELECT width_bucket(5, ARRAY[3, 4, NULL]);` |
| [arrays:839](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L839) | 2202E / 42883 | `SELECT trim_array(ARRAY[1, 2, 3], -1);` |
| [arrays:840](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L840) | 2202E / 42883 | `SELECT trim_array(ARRAY[1, 2, 3], 10);` |
| [arrays:841](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L841) | 2202E / 42883 | `SELECT trim_array(ARRAY[]::int[], 1);` |
| [arrays:854](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L854) | 22023 / 42883 | `SELECT array_sample('{1,2,3,4,5,6}'::int[], -1);` |
| [arrays:855](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/arrays.sql#L855) | 22023 / 42883 | `SELECT array_sample('{1,2,3,4,5,6}'::int[], 7);` |
| [brin:413](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin.sql#L413) | 42809 / 42883 | `SELECT brin_summarize_new_values('brintest');` |
| [brin:414](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin.sql#L414) | 42P01 / 42883 | `SELECT brin_summarize_new_values('tenk1_unique1');` |
| [brin:418](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin.sql#L418) | 22003 / 42883 | `SELECT brin_desummarize_range('brinidx', -1);` |
| [brin:448](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin.sql#L448) | 22003 / 42883 | `SELECT brin_summarize_range('brin_summarize_idx', -1);` |
| [brin:449](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin.sql#L449) | 22003 / 42883 | `SELECT brin_summarize_range('brin_summarize_idx', 4294967296);` |
| [privileges:463](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L463) | 42501 / 42883 | `select * from ((select a.q1 as x, random() from int8_tbl a where q1 > 0) union all (select b.q2 as x, random() from int8 ...` |
| [privileges:892](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L892) | 42501 / 42883 | `SELECT priv_testfunc1(5);` |
| [privileges:893](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L893) | 42501 / 42883 | `SELECT priv_testagg1(x) FROM (VALUES (1), (2), (3)) _(x);` |
| [privileges:1115](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1115) | 42P01 / 42883 | `select has_table_privilege('pg_shad','select');` |
| [privileges:1116](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1116) | 42704 / 42883 | `select has_table_privilege('nosuchuser','pg_authid','select');` |
| [privileges:1117](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1117) | 22023 / 42883 | `select has_table_privilege('pg_authid','sel');` |
| [privileges:1212](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1212) | 42703 / 42883 | `select has_column_privilege('pg_authid','nosuchcol','select');` |
| [privileges:1220](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1220) | 42703 / 42883 | `select has_column_privilege('mytable','f2','select');` |
| [privileges:1402](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1402) | 42809 / 42883 | `SELECT has_sequence_privilege('regress_priv_user1', 'atest1', 'SELECT');` |
| [privileges:1403](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1403) | 22023 / 42883 | `SELECT has_sequence_privilege('regress_priv_user1', 'x_seq', 'INSERT');` |
| [privileges:1454](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1454) | 42501 / 42883 | `SELECT lo_unlink(1001);` |
| [privileges:1517](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1517) | 42501 / 42883 | `SELECT lo_put(1002, 1, 'abcd');` |
| [privileges:1518](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1518) | 42501 / 42883 | `SELECT lo_unlink(1002);` |
| [privileges:1519](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1519) | 42501 / 42883 | `SELECT lo_export(1001, '/dev/null');` |
| [privileges:1520](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1520) | 42501 / 42883 | `SELECT lo_import('/dev/null');` |
| [privileges:1521](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1521) | 42501 / 42883 | `SELECT lo_import('/dev/null', 2003);` |
| [privileges:1534](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1534) | 42501 / 42883 | `SELECT lo_export(1001, '/dev/null');` |
| [object_address:62](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L62) | 22023 / 42883 | `SELECT pg_get_object_address('stone', '{}', '{}');` |
| [object_address:63](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L63) | 22023 / 42883 | `SELECT pg_get_object_address('table', '{}', '{}');` |
| [object_address:64](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L64) | 22023 / 42883 | `SELECT pg_get_object_address('table', '{NULL}', '{}');` |
| [object_address:124](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L124) | 42704 / 42883 | `SELECT pg_get_object_address('language', '{one}', '{}');` |
| [object_address:125](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L125) | 22023 / 42883 | `SELECT pg_get_object_address('language', '{one,two}', '{}');` |
| [object_address:126](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L126) | 42704 / 42883 | `SELECT pg_get_object_address('large object', '{123}', '{}');` |
| [object_address:127](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L127) | 22023 / 42883 | `SELECT pg_get_object_address('large object', '{123,456}', '{}');` |
| [object_address:128](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L128) | 22P02 / 42883 | `SELECT pg_get_object_address('large object', '{blargh}', '{}');` |
| [object_address:129](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L129) | 3F000 / 42883 | `SELECT pg_get_object_address('schema', '{one}', '{}');` |
| [object_address:130](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L130) | 22023 / 42883 | `SELECT pg_get_object_address('schema', '{one,two}', '{}');` |
| [object_address:131](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L131) | 42704 / 42883 | `SELECT pg_get_object_address('role', '{one}', '{}');` |
| [object_address:132](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L132) | 22023 / 42883 | `SELECT pg_get_object_address('role', '{one,two}', '{}');` |
| [object_address:133](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L133) | 3D000 / 42883 | `SELECT pg_get_object_address('database', '{one}', '{}');` |
| [object_address:134](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L134) | 22023 / 42883 | `SELECT pg_get_object_address('database', '{one,two}', '{}');` |
| [object_address:135](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L135) | 42704 / 42883 | `SELECT pg_get_object_address('tablespace', '{one}', '{}');` |
| [object_address:136](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L136) | 22023 / 42883 | `SELECT pg_get_object_address('tablespace', '{one,two}', '{}');` |
| [object_address:137](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L137) | 42704 / 42883 | `SELECT pg_get_object_address('foreign-data wrapper', '{one}', '{}');` |
| [object_address:138](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L138) | 22023 / 42883 | `SELECT pg_get_object_address('foreign-data wrapper', '{one,two}', '{}');` |
| [object_address:139](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L139) | 42704 / 42883 | `SELECT pg_get_object_address('server', '{one}', '{}');` |
| [object_address:140](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L140) | 22023 / 42883 | `SELECT pg_get_object_address('server', '{one,two}', '{}');` |
| [object_address:141](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L141) | 42704 / 42883 | `SELECT pg_get_object_address('extension', '{one}', '{}');` |
| [object_address:142](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L142) | 22023 / 42883 | `SELECT pg_get_object_address('extension', '{one,two}', '{}');` |
| [object_address:143](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L143) | 42704 / 42883 | `SELECT pg_get_object_address('event trigger', '{one}', '{}');` |
| [object_address:144](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L144) | 22023 / 42883 | `SELECT pg_get_object_address('event trigger', '{one,two}', '{}');` |
| [object_address:145](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L145) | 42704 / 42883 | `SELECT pg_get_object_address('access method', '{one}', '{}');` |
| [object_address:146](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L146) | 22023 / 42883 | `SELECT pg_get_object_address('access method', '{one,two}', '{}');` |
| [object_address:147](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L147) | 42704 / 42883 | `SELECT pg_get_object_address('publication', '{one}', '{}');` |
| [object_address:148](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L148) | 22023 / 42883 | `SELECT pg_get_object_address('publication', '{one,two}', '{}');` |
| [object_address:149](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L149) | 42704 / 42883 | `SELECT pg_get_object_address('subscription', '{one}', '{}');` |
| [object_address:150](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/object_address.sql#L150) | 22023 / 42883 | `SELECT pg_get_object_address('subscription', '{one,two}', '{}');` |
| [generated_stored:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L308) | 42501 / 42883 | `SELECT gf1(10);` |
| [brin_bloom:327](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_bloom.sql#L327) | 42809 / 42883 | `SELECT brin_summarize_new_values('brintest_bloom');` |
| [brin_bloom:328](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_bloom.sql#L328) | 42P01 / 42883 | `SELECT brin_summarize_new_values('tenk1_unique1');` |
| [brin_bloom:332](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_bloom.sql#L332) | 22003 / 42883 | `SELECT brin_desummarize_range('brinidx_bloom', -1);` |
| [brin_bloom:362](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_bloom.sql#L362) | 22003 / 42883 | `SELECT brin_summarize_range('brin_summarize_bloom_idx', -1);` |
| [brin_bloom:363](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_bloom.sql#L363) | 22003 / 42883 | `SELECT brin_summarize_range('brin_summarize_bloom_idx', 4294967296);` |
| [brin_multi:368](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_multi.sql#L368) | 42809 / 42883 | `SELECT brin_summarize_new_values('brintest_multi');` |
| [brin_multi:369](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_multi.sql#L369) | 42P01 / 42883 | `SELECT brin_summarize_new_values('tenk1_unique1');` |
| [brin_multi:373](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_multi.sql#L373) | 22003 / 42883 | `SELECT brin_desummarize_range('brinidx_multi', -1);` |
| [brin_multi:409](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_multi.sql#L409) | 22003 / 42883 | `SELECT brin_summarize_range('brin_summarize_multi_idx', -1);` |
| [brin_multi:410](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/brin_multi.sql#L410) | 22003 / 42883 | `SELECT brin_summarize_range('brin_summarize_multi_idx', 4294967296);` |
| [async:11](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/async.sql#L11) | 22023 / 42883 | `SELECT pg_notify('','sample message1');` |
| [async:12](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/async.sql#L12) | 22023 / 42883 | `SELECT pg_notify(NULL,'sample message1');` |
| [async:13](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/async.sql#L13) | 22023 / 42883 | `SELECT pg_notify('notify_async_channel_name_too_long______________________________','sample_message1');` |
| [dbsize:55](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L55) | 22023 / 42883 | `SELECT pg_size_bytes('1 AB');` |
| [dbsize:56](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L56) | 22023 / 42883 | `SELECT pg_size_bytes('1 AB A');` |
| [dbsize:57](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L57) | 22023 / 42883 | `SELECT pg_size_bytes('1 AB A ');` |
| [dbsize:58](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L58) | 22003 / 42883 | `SELECT pg_size_bytes('9223372036854775807.9');` |
| [dbsize:59](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L59) | 22003 / 42883 | `SELECT pg_size_bytes('1e100');` |
| [dbsize:60](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L60) | 22003 / 42883 | `SELECT pg_size_bytes('1e1000000000000000000');` |
| [dbsize:61](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L61) | 22023 / 42883 | `SELECT pg_size_bytes('1 byte');` |
| [dbsize:62](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L62) | 22023 / 42883 | `SELECT pg_size_bytes('');` |
| [dbsize:64](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L64) | 22023 / 42883 | `SELECT pg_size_bytes('kb');` |
| [dbsize:65](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L65) | 22023 / 42883 | `SELECT pg_size_bytes('..');` |
| [dbsize:66](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L66) | 22023 / 42883 | `SELECT pg_size_bytes('-.');` |
| [dbsize:67](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L67) | 22023 / 42883 | `SELECT pg_size_bytes('-.kb');` |
| [dbsize:68](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L68) | 22023 / 42883 | `SELECT pg_size_bytes('-. kb');` |
| [dbsize:70](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L70) | 22023 / 42883 | `SELECT pg_size_bytes('.+912');` |
| [dbsize:71](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L71) | 22023 / 42883 | `SELECT pg_size_bytes('+912+ kB');` |
| [dbsize:72](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/dbsize.sql#L72) | 22023 / 42883 | `SELECT pg_size_bytes('++123 kB');` |
| [misc_functions:172](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L172) | 58P01 / 42883 | `select pg_read_file('does not exist');` |
| [misc_functions:175](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L175) | 22023 / 42883 | `select pg_read_file('does not exist', 0, -1);` |
| [misc_functions:176](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L176) | 22023 / 42883 | `select pg_read_file('does not exist', 0, -1, true);` |
| [misc_functions:182](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L182) | 58P01 / 42883 | `select pg_read_binary_file('does not exist');` |
| [misc_functions:185](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L185) | 22023 / 42883 | `select pg_read_binary_file('does not exist', 0, -1);` |
| [misc_functions:186](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L186) | 22023 / 42883 | `select pg_read_binary_file('does not exist', 0, -1, true);` |
| [misc_functions:194](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/misc_functions.sql#L194) | 58P01 / 42883 | `select pg_ls_dir('does not exist', false, false);` |
| [tsrf:70](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L70) | 0A000 / 42883 | `SELECT q1, case when q1 > 0 then generate_series(1,3) else 0 end FROM int8_tbl;` |
| [tsrf:71](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L71) | 0A000 / 42883 | `SELECT q1, coalesce(generate_series(1,3), 0) FROM int8_tbl;` |
| [tsrf:74](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L74) | 0A000 / 42883 | `SELECT min(generate_series(1, 3)) FROM few;` |
| [tsrf:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L83) | 0A000 / 42883 | `SELECT min(generate_series(1, 3)) OVER() FROM few;` |
| [tsrf:114](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L114) | 0A000 / 42883 | `UPDATE fewmore SET data = generate_series(4,9);` |
| [tsrf:117](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L117) | 0A000 / 42883 | `INSERT INTO fewmore VALUES(1) RETURNING generate_series(1,3);` |
| [tsrf:120](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L120) | 0A000 / 42883 | `VALUES(1, generate_series(1,2));` |
| [tsrf:159](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsrf.sql#L159) | 0A000 / 42883 | `SELECT 1 LIMIT generate_series(1,3);` |
| [generated_virtual:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L308) | 42501 / 42883 | `SELECT gf1(10);` |
| [psql:1659](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L1659) | P0001 / 42883 | `SELECT psql_error('bad!');` |
| [psql:1664](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L1664) | P0001 / 42883 | `SELECT psql_error('bad!');` |
| [select_parallel:544](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/select_parallel.sql#L544) | 22012 / 42883 | `select set_role_and_error(0);` |
| [select_parallel:547](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/select_parallel.sql#L547) | 22012 / 42883 | `select set_role_and_error(0);` |
| [subscription:269](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/subscription.sql#L269) | 25001 / 42883 | `SELECT func();` |
| [portals_inflight:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/portals_inflight.sql#L80) | 0A000 / 42883 | `SELECT cl_ro_open();` |
| [portals_inflight:85](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/portals_inflight.sql#L85) | 0A000 / 42883 | `SELECT cl_ro_open();` |
| [guc:16](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L16) | 22023 / 42883 | `SELECT set_config('output_plugin_libraries', 'pgoutput,', true);` |
| [guc:289](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L289) | 22012 / 42883 | `select myfunc(0);` |
| [guc:295](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L295) | 42704 / 42883 | `select current_setting('nosuch.setting');` |
| [guc:296](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L296) | 42704 / 42883 | `select current_setting('nosuch.setting', false);` |
| [guc:320](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L320) | 22023 / 42883 | `select func_with_bad_set();` |
| [tsearch:651](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsearch.sql#L651) | 22023 / 42883 | `SELECT ts_headline('english', 'foo barbar', to_tsquery('english', 'foo'), 'StartSel=' \|\| repeat('x', 32768));` |
| [tsearch:653](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsearch.sql#L653) | 22023 / 42883 | `SELECT ts_headline('english', 'foo barbar', to_tsquery('english', 'foo'), 'StopSel=' \|\| repeat('x', 32768));` |
| [tsearch:655](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tsearch.sql#L655) | 22023 / 42883 | `SELECT ts_headline('english', 'foo barbar', to_tsquery('english', 'foo'), 'FragmentDelimiter=' \|\| repeat('x', 32768));` |
| [window:1119](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/window.sql#L1119) | 42809 / 42883 | `SELECT generate_series(1, 100) OVER () FROM empsalary;` |
| [json:330](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L330) | 22023 / 42883 | `SELECT json_array_length('{"f1":1,"f2":[5,6]}');` |
| [json:332](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L332) | 22023 / 42883 | `SELECT json_array_length('4');` |
| [json:672](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L672) | 22023 / 42883 | `SELECT json_build_object('{a,b,c}'::text[]);` |
| [json:673](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L673) | 22023 / 42883 | `SELECT json_build_object('{a,b,c}'::text[], '{d,e,f}'::text[]);` |
| [json:674](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L674) | 22023 / 42883 | `SELECT json_build_object('a', 'b', 'c');` |
| [json:675](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L675) | 22004 / 42883 | `SELECT json_build_object(NULL, 'a');` |
| [json:695](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L695) | 22004 / 42883 | `SELECT json_build_object(null,2);` |
| [json:701](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L701) | 22023 / 42883 | `SELECT json_build_object('{1,2,3}'::int[], 3);` |
| [jsonb:281](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L281) | 22023 / 42883 | `SELECT jsonb_array_length('{"f1":1,"f2":[5,6]}');` |
| [jsonb:282](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L282) | 22023 / 42883 | `SELECT jsonb_array_length('4');` |
| [jsonb:370](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L370) | 22023 / 42883 | `SELECT jsonb_build_object('{a,b,c}'::text[]);` |
| [jsonb:371](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L371) | 22023 / 42883 | `SELECT jsonb_build_object('{a,b,c}'::text[], '{d,e,f}'::text[]);` |
| [jsonb:372](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L372) | 22023 / 42883 | `SELECT jsonb_build_object('a', 'b', 'c');` |
| [jsonb:373](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L373) | 22023 / 42883 | `SELECT jsonb_build_object(NULL, 'a');` |
| [jsonb:393](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L393) | 22023 / 42883 | `SELECT jsonb_build_object(null,2);` |
| [jsonb:399](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L399) | 22023 / 42883 | `SELECT jsonb_build_object('{1,2,3}'::int[], 3);` |
| [jsonb:436](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L436) | 2202E / 42883 | `SELECT jsonb_object('{a,b,c}');` |
| [jsonb:439](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L439) | 2202E / 42883 | `SELECT jsonb_object('{{a},{b}}');` |
| [jsonb:442](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L442) | 2202E / 42883 | `SELECT jsonb_object('{{a,b,c},{b,c,d}}');` |
| [jsonb:445](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L445) | 2202E / 42883 | `SELECT jsonb_object('{{{a,b},{c,d}},{{b,c},{d,e}}}');` |
| [jsonb:452](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L452) | 2202E / 42883 | `SELECT jsonb_object('{{a,1},{b,2},{3,NULL},{"d e f","a b c"}}', '{{a,1},{b,2},{3,NULL},{"d e f","a b c"}}');` |
| [jsonb:456](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L456) | 2202E / 42883 | `select jsonb_object('{a,b,c,"d e f",g}','{1,2,3,"a b c"}');` |
| [jsonb:458](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L458) | 2202E / 42883 | `select jsonb_object('{a,b,c,"d e f"}','{1,2,3,"a b c",g}');` |
| [jsonb:462](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L462) | 22004 / 42883 | `select jsonb_object('{a,b,NULL,"d e f"}','{1,2,3,"a b c"}');` |
| [jsonb:1209](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1209) | 22004 / 42883 | `select jsonb_set('{"n":null, "a":1, "b":[1,2], "c":{"1":2}, "d":{"1":[2,3]}}'::jsonb, '{d,NULL,0}', '[1,2,3]');` |
| [jsonb:1214](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1214) | 22004 / 42883 | `select jsonb_set('{"n":null, "a":1, "b":[1,2], "c":{"1":2}, "d":{"1":[2,3]}}'::jsonb, '{d,NULL,0}', '{"1": 2}');` |
| [jsonb:1236](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1236) | 22023 / 42883 | `select '{}'::jsonb - 1;` |
| [jsonb:1241](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1241) | 22023 / 42883 | `select jsonb_set('"a"','{a}','"b"');` |
| [jsonb:1265](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1265) | 22P02 / 42883 | `select jsonb_set('{"a": [1, 2, 3]}', '{a, non_integer}', '"new_value"');` |
| [jsonb:1266](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1266) | 22P02 / 42883 | `select jsonb_set('{"a": {"b": [1, 2, 3]}}', '{a, b, non_integer}', '"new_value"');` |
| [jsonb:1267](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1267) | 22004 / 42883 | `select jsonb_set('{"a": {"b": [1, 2, 3]}}', '{a, b, NULL}', '"new_value"');` |
| [jsonb:1280](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1280) | 22023 / 42883 | `select jsonb_set_lax('{"a":1,"b":2}', '{b}', null, true, null);` |
| [jsonb:1281](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1281) | 22023 / 42883 | `select jsonb_set_lax('{"a":1,"b":2}', '{b}', null, true, 'no_such_treatment');` |
| [jsonb:1326](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1326) | 22023 / 42883 | `select jsonb_insert('{"a": {"b": "value"}}', '{a, b}', '"new_value"');` |
| [jsonb:1327](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1327) | 22023 / 42883 | `select jsonb_insert('{"a": {"b": "value"}}', '{a, b}', '"new_value"', true);` |
| [jsonb:1566](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1566) | 22023 / 42883 | `select jsonb_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::jsonb, ...` |
| [jsonb:1567](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1567) | 22023 / 42883 | `select jsonb_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::jsonb, ...` |
| [jsonb:1569](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1569) | 22023 / 42883 | `select jsonb_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::jsonb, ...` |
| [jsonb:1570](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1570) | 22023 / 42883 | `select jsonb_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::jsonb, ...` |
| [jsonb_jsonpath:21](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L21) | 22033 / 42883 | `select jsonb_path_query('[1]', 'strict $[1]');` |
| [jsonb_jsonpath:25](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L25) | 22033 / 42883 | `select jsonb_path_query('[1]', 'lax $[10000000000000000]');` |
| [jsonb_jsonpath:26](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L26) | 22033 / 42883 | `select jsonb_path_query('[1]', 'strict $[10000000000000000]');` |
| [jsonb_jsonpath:48](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L48) | 2203A / 42883 | `select jsonb_path_query('1', 'strict $.a');` |
| [jsonb_jsonpath:49](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L49) | 2203C / 42883 | `select jsonb_path_query('1', 'strict $.*');` |
| [jsonb_jsonpath:53](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L53) | 2203A / 42883 | `select jsonb_path_query('[]', 'strict $.a');` |
| [jsonb_jsonpath:56](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L56) | 2203A / 42883 | `select jsonb_path_query('{}', 'strict $.a');` |
| [jsonb_jsonpath:59](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L59) | 22039 / 42883 | `select jsonb_path_query('1', 'strict $[1]');` |
| [jsonb_jsonpath:60](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L60) | 22039 / 42883 | `select jsonb_path_query('1', 'strict $[*]');` |
| [jsonb_jsonpath:61](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L61) | 22033 / 42883 | `select jsonb_path_query('[]', 'strict $[1]');` |
| [jsonb_jsonpath:62](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L62) | 22033 / 42883 | `select jsonb_path_query('[]', 'strict $["a"]');` |
| [jsonb_jsonpath:79](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L79) | 22012 / 42883 | `select jsonb_path_query('[12, {"a": 13}, {"b": 14}]', 'lax $[0 to 10 / 0].a');` |
| [jsonb_jsonpath:86](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L86) | 2203A / 42883 | `select jsonb_path_query('[1,2,3]', 'strict $[*].a');` |
| [jsonb_jsonpath:90](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L90) | 22033 / 42883 | `select jsonb_path_query('[]', 'strict $[last]');` |
| [jsonb_jsonpath:96](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L96) | 22033 / 42883 | `select jsonb_path_query('[1,2,3]', '$[last ? (@.type() == "string")]');` |
| [jsonb_jsonpath:220](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L220) | 22012 / 42883 | `select jsonb_path_query('0', '1 / $');` |
| [jsonb_jsonpath:221](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L221) | 22012 / 42883 | `select jsonb_path_query('0', '1 / $ + 2');` |
| [jsonb_jsonpath:222](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L222) | 22012 / 42883 | `select jsonb_path_query('0', '-(3 + 1 % $)');` |
| [jsonb_jsonpath:223](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L223) | 22038 / 42883 | `select jsonb_path_query('1', '$ + "2"');` |
| [jsonb_jsonpath:224](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L224) | 22038 / 42883 | `select jsonb_path_query('[1, 2]', '3 * $');` |
| [jsonb_jsonpath:225](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L225) | 2203B / 42883 | `select jsonb_path_query('"a"', '-$');` |
| [jsonb_jsonpath:226](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L226) | 2203B / 42883 | `select jsonb_path_query('[1,"2",3]', '+$');` |
| [jsonb_jsonpath:241](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L241) | 22038 / 42883 | `select jsonb_path_query('{"a": [1, 2]}', 'lax $.a * 3');` |
| [jsonb_jsonpath:248](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L248) | 2203C / 42883 | `select jsonb_path_query('[1,2,3,{"b": [3,4,5]}]', 'strict $.*');` |
| [jsonb_jsonpath:293](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L293) | 22039 / 42883 | `select jsonb_path_query('[1,null,true,"11",[],[1],[1,2,3],{},{"a":1,"b":2}]', 'strict $[*].size()');` |
| [jsonb_jsonpath:303](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L303) | 2203C / 42883 | `select jsonb_path_query('[{},1]', '$[*].keyvalue()');` |
| [jsonb_jsonpath:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L308) | 2203C / 42883 | `select jsonb_path_query('[{"a": 1, "b": [1, 2]}, {"c": {"a": "bbb"}}]', 'strict $.keyvalue()');` |
| [jsonb_jsonpath:310](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L310) | 2203C / 42883 | `select jsonb_path_query('[{"a": 1, "b": [1, 2]}, {"c": {"a": "bbb"}}]', 'strict $.keyvalue().a');` |
| [jsonb_jsonpath:314](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L314) | 22036 / 42883 | `select jsonb_path_query('null', '$.double()');` |
| [jsonb_jsonpath:315](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L315) | 22036 / 42883 | `select jsonb_path_query('true', '$.double()');` |
| [jsonb_jsonpath:319](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L319) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.double()');` |
| [jsonb_jsonpath:320](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L320) | 22036 / 42883 | `select jsonb_path_query('{}', '$.double()');` |
| [jsonb_jsonpath:325](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L325) | 22036 / 42883 | `select jsonb_path_query('"1.23aaa"', '$.double()');` |
| [jsonb_jsonpath:326](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L326) | 22036 / 42883 | `select jsonb_path_query('1e1000', '$.double()');` |
| [jsonb_jsonpath:327](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L327) | 22036 / 42883 | `select jsonb_path_query('"nan"', '$.double()');` |
| [jsonb_jsonpath:328](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L328) | 22036 / 42883 | `select jsonb_path_query('"NaN"', '$.double()');` |
| [jsonb_jsonpath:329](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L329) | 22036 / 42883 | `select jsonb_path_query('"inf"', '$.double()');` |
| [jsonb_jsonpath:330](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L330) | 22036 / 42883 | `select jsonb_path_query('"-inf"', '$.double()');` |
| [jsonb_jsonpath:334](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L334) | 22036 / 42883 | `select jsonb_path_query('{}', '$.abs()');` |
| [jsonb_jsonpath:335](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L335) | 22036 / 42883 | `select jsonb_path_query('true', '$.floor()');` |
| [jsonb_jsonpath:336](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L336) | 22036 / 42883 | `select jsonb_path_query('"1.2"', '$.ceiling()');` |
| [jsonb_jsonpath:361](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L361) | 22031 / 42883 | `select jsonb_path_query('null', '$.datetime()');` |
| [jsonb_jsonpath:362](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L362) | 22031 / 42883 | `select jsonb_path_query('true', '$.datetime()');` |
| [jsonb_jsonpath:363](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L363) | 22031 / 42883 | `select jsonb_path_query('1', '$.datetime()');` |
| [jsonb_jsonpath:365](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L365) | 22031 / 42883 | `select jsonb_path_query('[]', 'strict $.datetime()');` |
| [jsonb_jsonpath:366](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L366) | 22031 / 42883 | `select jsonb_path_query('{}', '$.datetime()');` |
| [jsonb_jsonpath:367](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L367) | 22031 / 42883 | `select jsonb_path_query('"bogus"', '$.datetime()');` |
| [jsonb_jsonpath:368](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L368) | 22007 / 42883 | `select jsonb_path_query('"12:34"', '$.datetime("aaa")');` |
| [jsonb_jsonpath:369](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L369) | 22007 / 42883 | `select jsonb_path_query('"aaaa"', '$.datetime("HH24")');` |
| [jsonb_jsonpath:374](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L374) | 22007 / 42883 | `select jsonb_path_query('"10-03-2017 12:34"', '$.datetime("dd-mm-yyyy")');` |
| [jsonb_jsonpath:375](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L375) | 22007 / 42883 | `select jsonb_path_query('"10-03-2017 12:34"', '$.datetime("dd-mm-yyyy").type()');` |
| [jsonb_jsonpath:383](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L383) | 22007 / 42883 | `select jsonb_path_query('"10-03-2017t12:34:56"', '$.datetime("dd-mm-yyyy\"T\"HH24:MI:SS")');` |
| [jsonb_jsonpath:384](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L384) | 22007 / 42883 | `select jsonb_path_query('"10-03-2017 12:34:56"', '$.datetime("dd-mm-yyyy\"T\"HH24:MI:SS")');` |
| [jsonb_jsonpath:387](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L387) | 22036 / 42883 | `select jsonb_path_query('null', '$.bigint()');` |
| [jsonb_jsonpath:388](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L388) | 22036 / 42883 | `select jsonb_path_query('true', '$.bigint()');` |
| [jsonb_jsonpath:392](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L392) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.bigint()');` |
| [jsonb_jsonpath:393](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L393) | 22036 / 42883 | `select jsonb_path_query('{}', '$.bigint()');` |
| [jsonb_jsonpath:396](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L396) | 22036 / 42883 | `select jsonb_path_query('"1.23"', '$.bigint()');` |
| [jsonb_jsonpath:397](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L397) | 22036 / 42883 | `select jsonb_path_query('"1.23aaa"', '$.bigint()');` |
| [jsonb_jsonpath:398](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L398) | 22036 / 42883 | `select jsonb_path_query('1e1000', '$.bigint()');` |
| [jsonb_jsonpath:399](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L399) | 22036 / 42883 | `select jsonb_path_query('"nan"', '$.bigint()');` |
| [jsonb_jsonpath:400](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L400) | 22036 / 42883 | `select jsonb_path_query('"NaN"', '$.bigint()');` |
| [jsonb_jsonpath:401](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L401) | 22036 / 42883 | `select jsonb_path_query('"inf"', '$.bigint()');` |
| [jsonb_jsonpath:402](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L402) | 22036 / 42883 | `select jsonb_path_query('"-inf"', '$.bigint()');` |
| [jsonb_jsonpath:411](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L411) | 22036 / 42883 | `select jsonb_path_query('12345678901234567890', '$.bigint()');` |
| [jsonb_jsonpath:412](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L412) | 22036 / 42883 | `select jsonb_path_query('"12345678901234567890"', '$.bigint()');` |
| [jsonb_jsonpath:419](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L419) | 22036 / 42883 | `select jsonb_path_query('null', '$.boolean()');` |
| [jsonb_jsonpath:422](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L422) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.boolean()');` |
| [jsonb_jsonpath:423](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L423) | 22036 / 42883 | `select jsonb_path_query('{}', '$.boolean()');` |
| [jsonb_jsonpath:426](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L426) | 22036 / 42883 | `select jsonb_path_query('1.23', '$.boolean()');` |
| [jsonb_jsonpath:427](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L427) | 22036 / 42883 | `select jsonb_path_query('"1.23"', '$.boolean()');` |
| [jsonb_jsonpath:428](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L428) | 22036 / 42883 | `select jsonb_path_query('"1.23aaa"', '$.boolean()');` |
| [jsonb_jsonpath:429](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L429) | 22036 / 42883 | `select jsonb_path_query('1e1000', '$.boolean()');` |
| [jsonb_jsonpath:430](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L430) | 22036 / 42883 | `select jsonb_path_query('"nan"', '$.boolean()');` |
| [jsonb_jsonpath:431](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L431) | 22036 / 42883 | `select jsonb_path_query('"NaN"', '$.boolean()');` |
| [jsonb_jsonpath:432](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L432) | 22036 / 42883 | `select jsonb_path_query('"inf"', '$.boolean()');` |
| [jsonb_jsonpath:433](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L433) | 22036 / 42883 | `select jsonb_path_query('"-inf"', '$.boolean()');` |
| [jsonb_jsonpath:436](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L436) | 22036 / 42883 | `select jsonb_path_query('"100"', '$.boolean()');` |
| [jsonb_jsonpath:461](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L461) | 22031 / 42883 | `select jsonb_path_query('null', '$.date()');` |
| [jsonb_jsonpath:462](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L462) | 22031 / 42883 | `select jsonb_path_query('true', '$.date()');` |
| [jsonb_jsonpath:463](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L463) | 22031 / 42883 | `select jsonb_path_query('1', '$.date()');` |
| [jsonb_jsonpath:465](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L465) | 22031 / 42883 | `select jsonb_path_query('[]', 'strict $.date()');` |
| [jsonb_jsonpath:466](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L466) | 22031 / 42883 | `select jsonb_path_query('{}', '$.date()');` |
| [jsonb_jsonpath:467](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L467) | 22031 / 42883 | `select jsonb_path_query('"bogus"', '$.date()');` |
| [jsonb_jsonpath:473](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L473) | 22031 / 42883 | `select jsonb_path_query('"12:34:56"', '$.date()');` |
| [jsonb_jsonpath:474](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L474) | 22031 / 42883 | `select jsonb_path_query('"12:34:56 +05:30"', '$.date()');` |
| [jsonb_jsonpath:476](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L476) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.date()');` |
| [jsonb_jsonpath:479](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L479) | 42601 / 42883 | `select jsonb_path_query('"2023-08-15"', '$.date(2)');` |
| [jsonb_jsonpath:482](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L482) | 22036 / 42883 | `select jsonb_path_query('null', '$.decimal()');` |
| [jsonb_jsonpath:483](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L483) | 22036 / 42883 | `select jsonb_path_query('true', '$.decimal()');` |
| [jsonb_jsonpath:487](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L487) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.decimal()');` |
| [jsonb_jsonpath:488](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L488) | 22036 / 42883 | `select jsonb_path_query('{}', '$.decimal()');` |
| [jsonb_jsonpath:493](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L493) | 22036 / 42883 | `select jsonb_path_query('"1.23aaa"', '$.decimal()');` |
| [jsonb_jsonpath:495](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L495) | 22036 / 42883 | `select jsonb_path_query('"nan"', '$.decimal()');` |
| [jsonb_jsonpath:496](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L496) | 22036 / 42883 | `select jsonb_path_query('"NaN"', '$.decimal()');` |
| [jsonb_jsonpath:497](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L497) | 22036 / 42883 | `select jsonb_path_query('"inf"', '$.decimal()');` |
| [jsonb_jsonpath:498](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L498) | 22036 / 42883 | `select jsonb_path_query('"-inf"', '$.decimal()');` |
| [jsonb_jsonpath:510](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L510) | 22036 / 42883 | `select jsonb_path_query('12345.678', '$.decimal(6, 2)');` |
| [jsonb_jsonpath:512](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L512) | 22036 / 42883 | `select jsonb_path_query('12345.678', '$.decimal(4, 6)');` |
| [jsonb_jsonpath:513](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L513) | 22023 / 42883 | `select jsonb_path_query('12345.678', '$.decimal(0, 6)');` |
| [jsonb_jsonpath:514](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L514) | 22023 / 42883 | `select jsonb_path_query('12345.678', '$.decimal(1001, 6)');` |
| [jsonb_jsonpath:517](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L517) | 22023 / 42883 | `select jsonb_path_query('1234.5678', '$.decimal(-6, +2)');` |
| [jsonb_jsonpath:518](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L518) | 22023 / 42883 | `select jsonb_path_query('1234.5678', '$.decimal(6, -1001)');` |
| [jsonb_jsonpath:519](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L519) | 22023 / 42883 | `select jsonb_path_query('1234.5678', '$.decimal(6, 1001)');` |
| [jsonb_jsonpath:524](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L524) | 22036 / 42883 | `select jsonb_path_query('12.3', '$.decimal(12345678901,1)');` |
| [jsonb_jsonpath:525](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L525) | 22036 / 42883 | `select jsonb_path_query('12.3', '$.decimal(1,12345678901)');` |
| [jsonb_jsonpath:535](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L535) | 22036 / 42883 | `select jsonb_path_query('null', '$.integer()');` |
| [jsonb_jsonpath:536](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L536) | 22036 / 42883 | `select jsonb_path_query('true', '$.integer()');` |
| [jsonb_jsonpath:540](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L540) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.integer()');` |
| [jsonb_jsonpath:541](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L541) | 22036 / 42883 | `select jsonb_path_query('{}', '$.integer()');` |
| [jsonb_jsonpath:544](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L544) | 22036 / 42883 | `select jsonb_path_query('"1.23"', '$.integer()');` |
| [jsonb_jsonpath:545](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L545) | 22036 / 42883 | `select jsonb_path_query('"1.23aaa"', '$.integer()');` |
| [jsonb_jsonpath:546](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L546) | 22036 / 42883 | `select jsonb_path_query('1e1000', '$.integer()');` |
| [jsonb_jsonpath:547](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L547) | 22036 / 42883 | `select jsonb_path_query('"nan"', '$.integer()');` |
| [jsonb_jsonpath:548](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L548) | 22036 / 42883 | `select jsonb_path_query('"NaN"', '$.integer()');` |
| [jsonb_jsonpath:549](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L549) | 22036 / 42883 | `select jsonb_path_query('"inf"', '$.integer()');` |
| [jsonb_jsonpath:550](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L550) | 22036 / 42883 | `select jsonb_path_query('"-inf"', '$.integer()');` |
| [jsonb_jsonpath:557](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L557) | 22036 / 42883 | `select jsonb_path_query('12345678901', '$.integer()');` |
| [jsonb_jsonpath:558](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L558) | 22036 / 42883 | `select jsonb_path_query('"12345678901"', '$.integer()');` |
| [jsonb_jsonpath:565](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L565) | 22036 / 42883 | `select jsonb_path_query('null', '$.number()');` |
| [jsonb_jsonpath:566](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L566) | 22036 / 42883 | `select jsonb_path_query('true', '$.number()');` |
| [jsonb_jsonpath:570](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L570) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.number()');` |
| [jsonb_jsonpath:571](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L571) | 22036 / 42883 | `select jsonb_path_query('{}', '$.number()');` |
| [jsonb_jsonpath:576](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L576) | 22036 / 42883 | `select jsonb_path_query('"1.23aaa"', '$.number()');` |
| [jsonb_jsonpath:578](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L578) | 22036 / 42883 | `select jsonb_path_query('"nan"', '$.number()');` |
| [jsonb_jsonpath:579](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L579) | 22036 / 42883 | `select jsonb_path_query('"NaN"', '$.number()');` |
| [jsonb_jsonpath:580](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L580) | 22036 / 42883 | `select jsonb_path_query('"inf"', '$.number()');` |
| [jsonb_jsonpath:581](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L581) | 22036 / 42883 | `select jsonb_path_query('"-inf"', '$.number()');` |
| [jsonb_jsonpath:594](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L594) | 22036 / 42883 | `select jsonb_path_query('null', '$.string()');` |
| [jsonb_jsonpath:597](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L597) | 22036 / 42883 | `select jsonb_path_query('[]', 'strict $.string()');` |
| [jsonb_jsonpath:598](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L598) | 22036 / 42883 | `select jsonb_path_query('{}', '$.string()');` |
| [jsonb_jsonpath:610](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L610) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +5:30"', '$.timestamp().string()');` |
| [jsonb_jsonpath:612](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L612) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56"', '$.timestamp_tz().string()');` |
| [jsonb_jsonpath:634](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L634) | 22031 / 42883 | `select jsonb_path_query('null', '$.time()');` |
| [jsonb_jsonpath:635](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L635) | 22031 / 42883 | `select jsonb_path_query('true', '$.time()');` |
| [jsonb_jsonpath:636](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L636) | 22031 / 42883 | `select jsonb_path_query('1', '$.time()');` |
| [jsonb_jsonpath:638](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L638) | 22031 / 42883 | `select jsonb_path_query('[]', 'strict $.time()');` |
| [jsonb_jsonpath:639](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L639) | 22031 / 42883 | `select jsonb_path_query('{}', '$.time()');` |
| [jsonb_jsonpath:640](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L640) | 22031 / 42883 | `select jsonb_path_query('"bogus"', '$.time()');` |
| [jsonb_jsonpath:646](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L646) | 22031 / 42883 | `select jsonb_path_query('"2023-08-15"', '$.time()');` |
| [jsonb_jsonpath:647](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L647) | 0A000 / 42883 | `select jsonb_path_query('"12:34:56 +05:30"', '$.time()');` |
| [jsonb_jsonpath:651](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L651) | 42601 / 42883 | `select jsonb_path_query('"12:34:56.789"', '$.time(-1)');` |
| [jsonb_jsonpath:652](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L652) | 42601 / 42883 | `select jsonb_path_query('"12:34:56.789"', '$.time(2.0)');` |
| [jsonb_jsonpath:653](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L653) | 22031 / 42883 | `select jsonb_path_query('"12:34:56.789"', '$.time(12345678901)');` |
| [jsonb_jsonpath:661](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L661) | 22031 / 42883 | `select jsonb_path_query('null', '$.time_tz()');` |
| [jsonb_jsonpath:662](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L662) | 22031 / 42883 | `select jsonb_path_query('true', '$.time_tz()');` |
| [jsonb_jsonpath:663](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L663) | 22031 / 42883 | `select jsonb_path_query('1', '$.time_tz()');` |
| [jsonb_jsonpath:665](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L665) | 22031 / 42883 | `select jsonb_path_query('[]', 'strict $.time_tz()');` |
| [jsonb_jsonpath:666](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L666) | 22031 / 42883 | `select jsonb_path_query('{}', '$.time_tz()');` |
| [jsonb_jsonpath:667](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L667) | 22031 / 42883 | `select jsonb_path_query('"bogus"', '$.time_tz()');` |
| [jsonb_jsonpath:673](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L673) | 22031 / 42883 | `select jsonb_path_query('"2023-08-15"', '$.time_tz()');` |
| [jsonb_jsonpath:674](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L674) | 22031 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56"', '$.time_tz()');` |
| [jsonb_jsonpath:676](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L676) | 42601 / 42883 | `select jsonb_path_query('"12:34:56.789 +05:30"', '$.time_tz(-1)');` |
| [jsonb_jsonpath:677](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L677) | 42601 / 42883 | `select jsonb_path_query('"12:34:56.789 +05:30"', '$.time_tz(2.0)');` |
| [jsonb_jsonpath:678](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L678) | 22031 / 42883 | `select jsonb_path_query('"12:34:56.789 +05:30"', '$.time_tz(12345678901)');` |
| [jsonb_jsonpath:686](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L686) | 22031 / 42883 | `select jsonb_path_query('null', '$.timestamp()');` |
| [jsonb_jsonpath:687](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L687) | 22031 / 42883 | `select jsonb_path_query('true', '$.timestamp()');` |
| [jsonb_jsonpath:688](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L688) | 22031 / 42883 | `select jsonb_path_query('1', '$.timestamp()');` |
| [jsonb_jsonpath:690](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L690) | 22031 / 42883 | `select jsonb_path_query('[]', 'strict $.timestamp()');` |
| [jsonb_jsonpath:691](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L691) | 22031 / 42883 | `select jsonb_path_query('{}', '$.timestamp()');` |
| [jsonb_jsonpath:692](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L692) | 22031 / 42883 | `select jsonb_path_query('"bogus"', '$.timestamp()');` |
| [jsonb_jsonpath:699](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L699) | 22031 / 42883 | `select jsonb_path_query('"12:34:56"', '$.timestamp()');` |
| [jsonb_jsonpath:700](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L700) | 22031 / 42883 | `select jsonb_path_query('"12:34:56 +05:30"', '$.timestamp()');` |
| [jsonb_jsonpath:702](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L702) | 42601 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56.789"', '$.timestamp(-1)');` |
| [jsonb_jsonpath:703](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L703) | 42601 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56.789"', '$.timestamp(2.0)');` |
| [jsonb_jsonpath:704](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L704) | 22031 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56.789"', '$.timestamp(12345678901)');` |
| [jsonb_jsonpath:712](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L712) | 22031 / 42883 | `select jsonb_path_query('null', '$.timestamp_tz()');` |
| [jsonb_jsonpath:713](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L713) | 22031 / 42883 | `select jsonb_path_query('true', '$.timestamp_tz()');` |
| [jsonb_jsonpath:714](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L714) | 22031 / 42883 | `select jsonb_path_query('1', '$.timestamp_tz()');` |
| [jsonb_jsonpath:716](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L716) | 22031 / 42883 | `select jsonb_path_query('[]', 'strict $.timestamp_tz()');` |
| [jsonb_jsonpath:717](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L717) | 22031 / 42883 | `select jsonb_path_query('{}', '$.timestamp_tz()');` |
| [jsonb_jsonpath:718](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L718) | 22031 / 42883 | `select jsonb_path_query('"bogus"', '$.timestamp_tz()');` |
| [jsonb_jsonpath:724](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L724) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15"', '$.timestamp_tz()');` |
| [jsonb_jsonpath:726](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L726) | 22031 / 42883 | `select jsonb_path_query('"12:34:56"', '$.timestamp_tz()');` |
| [jsonb_jsonpath:727](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L727) | 22031 / 42883 | `select jsonb_path_query('"12:34:56 +05:30"', '$.timestamp_tz()');` |
| [jsonb_jsonpath:729](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L729) | 42601 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56.789 +05:30"', '$.timestamp_tz(-1)');` |
| [jsonb_jsonpath:730](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L730) | 42601 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56.789 +05:30"', '$.timestamp_tz(2.0)');` |
| [jsonb_jsonpath:731](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L731) | 22031 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56.789 +05:30"', '$.timestamp_tz(12345678901)');` |
| [jsonb_jsonpath:741](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L741) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.time()');` |
| [jsonb_jsonpath:744](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L744) | 0A000 / 42883 | `select jsonb_path_query('"12:34:56"', '$.time_tz()');` |
| [jsonb_jsonpath:746](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L746) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.timestamp()');` |
| [jsonb_jsonpath:748](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L748) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56"', '$.timestamp_tz()');` |
| [jsonb_jsonpath:752](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L752) | 22007 / 42883 | `select jsonb_path_query('"10-03-2017 12:34"', '$.datetime("dd-mm-yyyy HH24:MI TZH")');` |
| [jsonb_jsonpath:758](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L758) | 22007 / 42883 | `select jsonb_path_query('"12:34"', '$.datetime("HH24:MI TZH")');` |
| [jsonb_jsonpath:766](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L766) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.time()');` |
| [jsonb_jsonpath:769](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L769) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.timestamp()');` |
| [jsonb_jsonpath:771](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L771) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56"', '$.timestamp_tz()');` |
| [jsonb_jsonpath:776](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L776) | 22007 / 42883 | `select jsonb_path_query('"10-03-2017 12:34"', '$.datetime("dd-mm-yyyy HH24:MI TZH")');` |
| [jsonb_jsonpath:782](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L782) | 22007 / 42883 | `select jsonb_path_query('"12:34"', '$.datetime("HH24:MI TZH")');` |
| [jsonb_jsonpath:790](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L790) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.time()');` |
| [jsonb_jsonpath:793](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L793) | 0A000 / 42883 | `select jsonb_path_query('"2023-08-15 12:34:56 +05:30"', '$.timestamp()');` |
| [jsonb_jsonpath:806](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L806) | 22031 / 42883 | `select jsonb_path_query('"2017-03-10t12:34:56+3:10"', '$.datetime()');` |
| [jsonb_jsonpath:809](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L809) | 22031 / 42883 | `select jsonb_path_query('"2017-03-10t12:34:56.789+3:10"', '$.datetime()');` |
| [jsonb_jsonpath:822](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L822) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10", "2017-03-11", "2017-03-09", "12:34:56", "01:02:03+04", "2017-03-10 00:00:00", " ...` |
| [jsonb_jsonpath:825](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L825) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10", "2017-03-11", "2017-03-09", "12:34:56", "01:02:03+04", "2017-03-10 00:00:00", " ...` |
| [jsonb_jsonpath:828](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L828) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10", "2017-03-11", "2017-03-09", "12:34:56", "01:02:03+04", "2017-03-10 00:00:00", " ...` |
| [jsonb_jsonpath:850](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L850) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10", "2017-03-11", "2017-03-09", "2017-03-10 00:00:00", "2017-03-10 12:34:56", "2017 ...` |
| [jsonb_jsonpath:853](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L853) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10", "2017-03-11", "2017-03-09", "2017-03-10 00:00:00", "2017-03-10 12:34:56", "2017 ...` |
| [jsonb_jsonpath:856](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L856) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10", "2017-03-11", "2017-03-09", "2017-03-10 00:00:00", "2017-03-10 12:34:56", "2017 ...` |
| [jsonb_jsonpath:870](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L870) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00", "12:35:00", "12:36:00", "12:35:00+00", "12:35:00+01", "13:35:00+01", "2017-03-10" ...` |
| [jsonb_jsonpath:873](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L873) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00", "12:35:00", "12:36:00", "12:35:00+00", "12:35:00+01", "13:35:00+01", "2017-03-10" ...` |
| [jsonb_jsonpath:876](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L876) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00", "12:35:00", "12:36:00", "12:35:00+00", "12:35:00+01", "13:35:00+01", "2017-03-10" ...` |
| [jsonb_jsonpath:898](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L898) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00", "12:35:00", "12:36:00", "12:35:00+00", "12:35:00+01", "13:35:00+01", "2017-03-10 ...` |
| [jsonb_jsonpath:901](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L901) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00", "12:35:00", "12:36:00", "12:35:00+00", "12:35:00+01", "13:35:00+01", "2017-03-10 ...` |
| [jsonb_jsonpath:904](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L904) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00", "12:35:00", "12:36:00", "12:35:00+00", "12:35:00+01", "13:35:00+01", "2017-03-10 ...` |
| [jsonb_jsonpath:907](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L907) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00.123", "12:35:00.123", "12:36:00.1123", "12:35:00.1123+00", "12:35:00.123+01", "13:3 ...` |
| [jsonb_jsonpath:925](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L925) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00+01", "12:35:00+01", "12:36:00+01", "12:35:00+02", "12:35:00-02", "10:35:00", "11:35 ...` |
| [jsonb_jsonpath:928](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L928) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00+01", "12:35:00+01", "12:36:00+01", "12:35:00+02", "12:35:00-02", "10:35:00", "11:35 ...` |
| [jsonb_jsonpath:931](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L931) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00+01", "12:35:00+01", "12:36:00+01", "12:35:00+02", "12:35:00-02", "10:35:00", "11:35 ...` |
| [jsonb_jsonpath:953](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L953) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00+01", "12:35:00+01", "12:36:00+01", "12:35:00+02", "12:35:00-02", "10:35:00", "11:35 ...` |
| [jsonb_jsonpath:956](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L956) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00+01", "12:35:00+01", "12:36:00+01", "12:35:00+02", "12:35:00-02", "10:35:00", "11:35 ...` |
| [jsonb_jsonpath:959](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L959) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00+01", "12:35:00+01", "12:36:00+01", "12:35:00+02", "12:35:00-02", "10:35:00", "11:35 ...` |
| [jsonb_jsonpath:962](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L962) | 0A000 / 42883 | `select jsonb_path_query( '["12:34:00.123+01", "12:35:00.123+01", "12:36:00.1123+01", "12:35:00.1123+02", "12:35:00.123-0 ...` |
| [jsonb_jsonpath:979](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L979) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00", "2017-03-10 12:35:00", "2017-03-10 12:36:00", "2017-03-10 12:35:00+01" ...` |
| [jsonb_jsonpath:982](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L982) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00", "2017-03-10 12:35:00", "2017-03-10 12:36:00", "2017-03-10 12:35:00+01" ...` |
| [jsonb_jsonpath:985](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L985) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00", "2017-03-10 12:35:00", "2017-03-10 12:36:00", "2017-03-10 12:35:00+01" ...` |
| [jsonb_jsonpath:1007](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1007) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00", "2017-03-10 12:35:00", "2017-03-10 12:36:00", "2017-03-10 12:35:00+01" ...` |
| [jsonb_jsonpath:1010](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1010) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00", "2017-03-10 12:35:00", "2017-03-10 12:36:00", "2017-03-10 12:35:00+01" ...` |
| [jsonb_jsonpath:1013](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1013) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00", "2017-03-10 12:35:00", "2017-03-10 12:36:00", "2017-03-10 12:35:00+01" ...` |
| [jsonb_jsonpath:1016](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1016) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00.123", "2017-03-10 12:35:00.123", "2017-03-10 12:36:00.1123", "2017-03-10 ...` |
| [jsonb_jsonpath:1033](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1033) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00+01", "2017-03-10 12:35:00+01", "2017-03-10 12:36:00+01", "2017-03-10 12: ...` |
| [jsonb_jsonpath:1036](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1036) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00+01", "2017-03-10 12:35:00+01", "2017-03-10 12:36:00+01", "2017-03-10 12: ...` |
| [jsonb_jsonpath:1039](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1039) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00+01", "2017-03-10 12:35:00+01", "2017-03-10 12:36:00+01", "2017-03-10 12: ...` |
| [jsonb_jsonpath:1061](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1061) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00+01", "2017-03-10 12:35:00+01", "2017-03-10 12:36:00+01", "2017-03-10 12: ...` |
| [jsonb_jsonpath:1064](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1064) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00+01", "2017-03-10 12:35:00+01", "2017-03-10 12:36:00+01", "2017-03-10 12: ...` |
| [jsonb_jsonpath:1067](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1067) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00+01", "2017-03-10 12:35:00+01", "2017-03-10 12:36:00+01", "2017-03-10 12: ...` |
| [jsonb_jsonpath:1070](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1070) | 0A000 / 42883 | `select jsonb_path_query( '["2017-03-10 12:34:00.123+01", "2017-03-10 12:35:00.123+01", "2017-03-10 12:36:00.1123+01", "2 ...` |
| [jsonb_jsonpath:1096](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1096) | 42704 / 42883 | `SELECT jsonb_path_query('[{"a": 1}]', '$undefined_var');` |
| [jsonb_jsonpath:1099](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1099) | 2203A / 42883 | `SELECT jsonb_path_query_array('[{"a": 1}, {"a": 2}, {}]', 'strict $[*].a');` |
| [jsonb_jsonpath:1106](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1106) | 2203A / 42883 | `SELECT jsonb_path_query_first('[{"a": 1}, {"a": 2}, {}]', 'strict $[*].a');` |
| [jsonb_jsonpath:1113](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1113) | 42704 / 42883 | `SELECT jsonb_path_query_first('[{"a": 1}]', '$undefined_var');` |
| [jsonb_jsonpath:1121](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1121) | 42704 / 42883 | `SELECT jsonb_path_exists('[{"a": 1}]', '$undefined_var');` |
| [jsonb_jsonpath:1139](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb_jsonpath.sql#L1139) | 42704 / 42883 | `SELECT jsonb_path_match('[{"a": 1}]', '$undefined_var');` |
| [plpgsql:1946](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L1946) | 22005 / 42883 | `select constant_refcursor();` |
| [plpgsql:2048](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2048) | 22012 / 42883 | `select namedparmcursor_test7();` |
| [plpgsql:2181](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2181) | 2F005 / 42883 | `select missing_return_expr();` |
| [plpgsql:2228](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2228) | 42703 / 42883 | `select excpt_test1();` |
| [plpgsql:2239](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2239) | 42703 / 42883 | `select excpt_test2();` |
| [plpgsql:2331](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2331) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2373](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2373) | P0002 / 42883 | `select stricttest();` |
| [plpgsql:2383](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2383) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2403](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2403) | P0002 / 42883 | `select stricttest();` |
| [plpgsql:2413](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2413) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2432](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2432) | P0002 / 42883 | `select stricttest();` |
| [plpgsql:2445](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2445) | P0002 / 42883 | `select stricttest();` |
| [plpgsql:2458](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2458) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2468](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2468) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2478](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2478) | P0002 / 42883 | `select stricttest();` |
| [plpgsql:2488](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2488) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2498](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2498) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2513](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2513) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:2530](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L2530) | P0003 / 42883 | `select stricttest();` |
| [plpgsql:3127](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3127) | 42804 / 42883 | `select compos();` |
| [plpgsql:3186](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3186) | 42804 / 42883 | `select compos();` |
| [plpgsql:3210](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3210) | 22P02 / 42883 | `select compos();` |
| [plpgsql:3219](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3219) | 22P02 / 42883 | `select compos();` |
| [plpgsql:3237](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3237) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3253](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3253) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3266](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3266) | 1234F / 42883 | `select raise_test();` |
| [plpgsql:3280](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3280) | 1234F / 42883 | `select raise_test();` |
| [plpgsql:3292](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3292) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3300](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3300) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3308) | 1234F / 42883 | `select raise_test();` |
| [plpgsql:3316](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3316) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3324](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3324) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3333](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3333) | 42601 / 42883 | `select raise_test();` |
| [plpgsql:3342](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3342) | 42601 / 42883 | `select raise_test();` |
| [plpgsql:3351](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3351) | 0Z002 / 42883 | `select raise_test();` |
| [plpgsql:3418](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3418) | 0Z002 / 42883 | `select stacked_diagnostics_test();` |
| [plpgsql:3455](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3455) | 22012 / 42883 | `select raise_test();` |
| [plpgsql:3714](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3714) | 3F000 / 42883 | `select error2('nonexistent.stuffs');` |
| [plpgsql:3743](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3743) | 22007 / 42883 | `select cast_invoker(-1);` |
| [plpgsql:3769](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3769) | 22012 / 42883 | `select fail();` |
| [plpgsql:3770](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L3770) | 22012 / 42883 | `select fail();` |
| [plpgsql:4013](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L4013) | 42804 / 42883 | `select foreach_test(ARRAY[1,2,3,4]);` |
| [plpgsql:4043](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L4043) | 2202E / 42883 | `select foreach_test(ARRAY[1,2,3,4]);` |
| [plpgsql:4132](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L4132) | 23514 / 42883 | `select testoa(2,1,3);` |
| [plpgsql:4133](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/plpgsql.sql#L4133) | 23514 / 42883 | `select testoa(1,2,1);` |
| [temp:357](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/temp.sql#L357) | 53000 / 42883 | `SELECT test_temp_pin(10, 105);` |
| [temp:366](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/temp.sql#L366) | 53000 / 42883 | `SELECT test_temp_pin(10, 105);` |
| [domain:194](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L194) | 23514 / 42883 | `select makedcomp(2,1);` |
| [domain:631](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L631) | 23502 / 42883 | `select doubledecrement(3);` |
| [domain:639](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L639) | 23514 / 42883 | `select doubledecrement(3);` |
| [domain:648](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L648) | 23502 / 42883 | `select doubledecrement(null);` |
| [domain:649](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L649) | 23514 / 42883 | `select doubledecrement(0);` |
| [domain:650](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L650) | 23514 / 42883 | `select doubledecrement(1);` |
| [domain:651](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L651) | 23514 / 42883 | `select doubledecrement(2);` |
| [domain:722](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L722) | 22003 / 42883 | `select array_elem_check(121.00);` |
| [domain:735](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L735) | 22003 / 42883 | `select array_elem_check(121.00);` |
| [domain:748](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L748) | 22003 / 42883 | `select array_elem_check(121.00);` |
| [domain:779](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L779) | 23514 / 42883 | `select array_elem_check(-1);` |
| [domain:801](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L801) | 23514 / 42883 | `select dom_check(0);` |
| [domain:821](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L821) | 23514 / 42883 | `select dom_check(0);` |
| [rangefuncs:389](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangefuncs.sql#L389) | 42804 / 42883 | `SELECT dup('xyz');` |
| [truncate:235](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/truncate.sql#L235) | 42P01 / 42883 | `SELECT nextval('truncate_a_id1');` |
| [sequence:205](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L205) | 2200H / 42883 | `SELECT nextval('sequence_test2');` |
| [sequence:221](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L221) | 2200H / 42883 | `SELECT nextval('sequence_test2');` |
| [sequence:227](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L227) | 22003 / 42883 | `SELECT setval('sequence_test2', -100);` |
| [sequence:228](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L228) | 22003 / 42883 | `SELECT setval('sequence_test2', 100);` |
| [sequence:264](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L264) | 55000 / 42883 | `SELECT lastval();` |
| [sequence:272](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L272) | 55000 / 42883 | `SELECT lastval();` |
| [sequence:287](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L287) | 25006 / 42883 | `SELECT nextval('sequence_test2');` |
| [sequence:291](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L291) | 25006 / 42883 | `SELECT setval('sequence_test2', 1);` |
| [sequence:304](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L304) | 42501 / 42883 | `SELECT nextval('seq3');` |
| [sequence:339](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L339) | 42501 / 42883 | `SELECT currval('seq3');` |
| [sequence:367](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L367) | 42501 / 42883 | `SELECT lastval();` |
| [sequence:385](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/sequence.sql#L385) | 42501 / 42883 | `SELECT setval('seq3', 5);` |
| [polymorphism:133](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L133) | 42804 / 42883 | `select polyf(null);` |
| [polymorphism:141](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L141) | 42804 / 42883 | `select polyf(null);` |
| [polymorphism:149](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L149) | 42804 / 42883 | `select polyf(null);` |
| [polymorphism:157](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L157) | 42804 / 42883 | `select polyf(null);` |
| [polymorphism:165](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L165) | 42804 / 42883 | `select polyf(null);` |
| [polymorphism:173](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L173) | 42804 / 42883 | `select polyf(null);` |
| [polymorphism:756](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L756) | 42725 / 42883 | `select dfunc();` |
| [polymorphism:776](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L776) | 42725 / 42883 | `select dfunc();` |
| [polymorphism:777](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L777) | 42725 / 42883 | `select dfunc(1);` |
| [polymorphism:778](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L778) | 42725 / 42883 | `select dfunc(1, 2);` |
| [polymorphism:841](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L841) | 42725 / 42883 | `select dfunc(1);` |
| [largeobject:289](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L289) | 25006 / 42883 | `SELECT lo_create(42);` |
| [largeobject:293](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L293) | 25006 / 42883 | `SELECT lo_creat(42);` |
| [largeobject:297](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L297) | 25006 / 42883 | `SELECT lo_unlink(42);` |
| [largeobject:301](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L301) | 25006 / 42883 | `SELECT lowrite(42, 'x');` |
| [largeobject:305](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L305) | 25006 / 42883 | `SELECT lo_import('/tmp/regress_out/results/lotest.txt');` |
| [largeobject:309](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L309) | 25006 / 42883 | `SELECT lo_truncate(42, 0);` |
| [largeobject:313](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L313) | 25006 / 42883 | `SELECT lo_truncate64(42, 0);` |
| [largeobject:317](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L317) | 25006 / 42883 | `SELECT lo_from_bytea(0, 'x');` |
| [largeobject:321](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/largeobject.sql#L321) | 25006 / 42883 | `SELECT lo_put(42, 0, 'x');` |
| [xml:29](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/xml.sql#L29) | 2200S / 42883 | `SELECT xmlcomment('test-');` |
| [xml:30](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/xml.sql#L30) | 2200S / 42883 | `SELECT xmlcomment('--test');` |
| [xml:239](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/xml.sql#L239) | 10608 / 42883 | `SELECT xpath('', '<!-- error -->');` |
| [xml:358](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/xml.sql#L358) | 2200M / 42883 | `SELECT xpath('/*', '<invalidns xmlns=''&lt;''/>');` |
| [xml:362](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/xml.sql#L362) | 2200M / 42883 | `SELECT xpath('/*', '<nosuchprefix:tag/>');` |
| [rowtypes_alter:249](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L249) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:295](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L295) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:390](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L390) | 22P07 / 42883 | `SELECT r, record_send(r) FROM (SELECT rta_func() AS r OFFSET 0) s;` |
| [rowtypes_alter:427](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L427) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:1078](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L1078) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:1126](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L1126) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:1328](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L1328) | 22P07 / 42883 | `SELECT split_part(r::text, ',', 2), rta_exec('ALTER TYPE rta_type ALTER ATTRIBUTE b TYPE text'), split_part(r::text, ',' ...` |
| [hash_part:15](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/hash_part.sql#L15) | XX000 / 42883 | `SELECT satisfies_hash_partition(0, 4, 0, NULL);` |
| [explain:100](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/explain.sql#L100) | 22023 / 42883 | `select explain_filter('explain (analyze, generic_plan) select unique1 from tenk1 where thousand = $1');` |
| [stats:513](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/stats.sql#L513) | 22023 / 42883 | `SELECT pg_stat_reset_shared('unknown');` |
| [stats:565](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/stats.sql#L565) | 22023 / 42883 | `SELECT pg_stat_have_stats('zaphod', 0, 0);` |
| [event_trigger:14](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/event_trigger.sql#L14) | 0A000 / 42883 | `SELECT test_event_trigger();` |
| [event_trigger:401](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/event_trigger.sql#L401) | 39P03 / 42883 | `select pg_event_trigger_table_rewrite_oid();` |

</details>

<details>
<summary>#199: 229 statements</summary>

A relation that a refused statement would have made answers 42P01 where PostgreSQL raises another error

| file and line | codes | statement |
| --- | --- | --- |
| [create_view:620](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L620) | 42703 / 42P01 | `select * from tt14v;` |
| [create_view:646](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L646) | 42804 / 42P01 | `select * from tt14v;` |
| [create_view:850](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L850) | 55000 / 42P01 | `select a from tt27v where a > 0;` |
| [create_view:851](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L851) | 55000 / 42P01 | `insert into tt27v values (1);` |
| [updatable_views:52](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L52) | 55000 / 42P01 | `DELETE FROM ro_view1;` |
| [updatable_views:53](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L53) | 55000 / 42P01 | `DELETE FROM ro_view2;` |
| [updatable_views:54](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L54) | 55000 / 42P01 | `DELETE FROM ro_view3;` |
| [updatable_views:55](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L55) | 55000 / 42P01 | `DELETE FROM ro_view4;` |
| [updatable_views:56](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L56) | 55000 / 42P01 | `DELETE FROM ro_view5;` |
| [updatable_views:57](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L57) | 55000 / 42P01 | `DELETE FROM ro_view6;` |
| [updatable_views:58](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L58) | 55000 / 42P01 | `UPDATE ro_view7 SET a=a+1;` |
| [updatable_views:59](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L59) | 55000 / 42P01 | `UPDATE ro_view8 SET a=a+1;` |
| [updatable_views:60](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L60) | 55000 / 42P01 | `UPDATE ro_view9 SET a=a+1;` |
| [updatable_views:61](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L61) | 55000 / 42P01 | `UPDATE ro_view10 SET a=a+1;` |
| [updatable_views:62](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L62) | 55000 / 42P01 | `UPDATE ro_view11 SET a=a+1;` |
| [updatable_views:63](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L63) | 55000 / 42P01 | `UPDATE ro_view12 SET a=a+1;` |
| [updatable_views:64](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L64) | 55000 / 42P01 | `INSERT INTO ro_view13 VALUES (3, 'Row 3');` |
| [updatable_views:78](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L78) | 0A000 / 42P01 | `INSERT INTO rw_view14 VALUES (null, 3, 'Row 3');` |
| [updatable_views:99](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L99) | 0A000 / 42P01 | `INSERT INTO rw_view15 VALUES (3, 'ROW 3');` |
| [updatable_views:107](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L107) | 0A000 / 42P01 | `INSERT INTO rw_view15 (a) VALUES (3) ON CONFLICT (a) DO UPDATE set upper = 'blarg';` |
| [updatable_views:111](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L111) | 0A000 / 42P01 | `INSERT INTO rw_view15 (a) VALUES (4);` |
| [updatable_views:112](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L112) | 0A000 / 42P01 | `UPDATE rw_view15 SET upper='ROW 3' WHERE a=3;` |
| [updatable_views:113](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L113) | 0A000 / 42P01 | `UPDATE rw_view15 SET upper=DEFAULT WHERE a=3;` |
| [updatable_views:118](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L118) | 42601 / 42P01 | `INSERT INTO rw_view16 VALUES (3, 'Row 3', 3);` |
| [updatable_views:120](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L120) | 42601 / 42P01 | `UPDATE rw_view16 SET a=3, aa=-3 WHERE a=3;` |
| [updatable_views:125](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L125) | 55000 / 42P01 | `INSERT INTO ro_view17 VALUES (3, 'ROW 3');` |
| [updatable_views:126](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L126) | 55000 / 42P01 | `DELETE FROM ro_view18;` |
| [updatable_views:129](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L129) | 55000 / 42P01 | `UPDATE ro_view19 SET last_value=1000;` |
| [updatable_views:130](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L130) | 55000 / 42P01 | `UPDATE ro_view20 SET b=upper(b);` |
| [updatable_views:142](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L142) | 55000 / 42P01 | `INSERT INTO rw_view16 (a, b) VALUES (3, 'Row 3');` |
| [updatable_views:143](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L143) | 55000 / 42P01 | `UPDATE rw_view16 SET b='ROW 2' WHERE a=2;` |
| [updatable_views:144](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L144) | 55000 / 42P01 | `DELETE FROM rw_view16 WHERE a=2;` |
| [updatable_views:611](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L611) | 42501 / 42P01 | `INSERT INTO rw_view1 VALUES ('Row 3', 3.0, 3);` |
| [updatable_views:612](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L612) | 42501 / 42P01 | `INSERT INTO rw_view2 VALUES ('Row 3', 3.0, 3);` |
| [updatable_views:624](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L624) | 42501 / 42P01 | `UPDATE rw_view1 SET aa=aa;` |
| [updatable_views:626](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L626) | 42501 / 42P01 | `UPDATE rw_view2 SET bb=bb;` |
| [updatable_views:638](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L638) | 42501 / 42P01 | `DELETE FROM rw_view1;` |
| [updatable_views:639](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L639) | 42501 / 42P01 | `DELETE FROM rw_view2;` |
| [updatable_views:648](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L648) | 42501 / 42P01 | `INSERT INTO rw_view1 VALUES ('Row 4', 4.0, 4);` |
| [updatable_views:651](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L651) | 42501 / 42P01 | `DELETE FROM rw_view1 WHERE aa=2;` |
| [updatable_views:668](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L668) | 42501 / 42P01 | `INSERT INTO rw_view2 VALUES ('Row 6', 6.0, 6);` |
| [updatable_views:671](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L671) | 42501 / 42P01 | `DELETE FROM rw_view2 WHERE aa=4;` |
| [updatable_views:688](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L688) | 42501 / 42P01 | `SELECT * FROM rw_view1;` |
| [updatable_views:690](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L690) | 42501 / 42P01 | `UPDATE rw_view1 SET b = 'foo' WHERE a = 1;` |
| [updatable_views:696](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L696) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:698](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L698) | 42501 / 42P01 | `UPDATE rw_view2 SET b = 'bar' WHERE a = 1;` |
| [updatable_views:708](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L708) | 42501 / 42P01 | `UPDATE rw_view1 SET b = 'foo' WHERE a = 1;` |
| [updatable_views:713](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L713) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:715](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L715) | 42501 / 42P01 | `UPDATE rw_view2 SET b = 'bar' WHERE a = 1;` |
| [updatable_views:725](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L725) | 42501 / 42P01 | `UPDATE rw_view2 SET b = 'bar' WHERE a = 1;` |
| [updatable_views:742](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L742) | 42501 / 42P01 | `UPDATE rw_view2 SET b = 'bar' WHERE a = 1;` |
| [updatable_views:762](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L762) | 42501 / 42P01 | `UPDATE rw_view1 SET b = 'foo' WHERE a = 1;` |
| [updatable_views:769](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L769) | 42501 / 42P01 | `UPDATE rw_view2 SET b = 'bar' WHERE a = 1;` |
| [updatable_views:790](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L790) | 42501 / 42P01 | `SELECT * FROM rw_view1;` |
| [updatable_views:792](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L792) | 42501 / 42P01 | `INSERT INTO rw_view1 VALUES ('Row 3', 3.0, 3);` |
| [updatable_views:794](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L794) | 42501 / 42P01 | `UPDATE rw_view1 SET bb=bb, cc=cc;` |
| [updatable_views:798](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L798) | 42501 / 42P01 | `DELETE FROM rw_view1;` |
| [updatable_views:814](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L814) | 42501 / 42P01 | `UPDATE rw_view1 SET aa=aa;` |
| [updatable_views:815](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L815) | 42501 / 42P01 | `UPDATE rw_view1 SET bb=bb;` |
| [updatable_views:826](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L826) | 42501 / 42P01 | `INSERT INTO rw_view1 VALUES ('Row 4', 4.0, 4);` |
| [updatable_views:828](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L828) | 42501 / 42P01 | `DELETE FROM rw_view1 WHERE aa=2;` |
| [updatable_views:837](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L837) | 42501 / 42P01 | `INSERT INTO rw_view1 VALUES ('Row 4', 4.0, 4);` |
| [updatable_views:838](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L838) | 42501 / 42P01 | `DELETE FROM rw_view1 WHERE aa=2;` |
| [updatable_views:864](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L864) | 42501 / 42P01 | `SELECT * FROM rw_view1;` |
| [updatable_views:865](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L865) | 42501 / 42P01 | `UPDATE rw_view1 SET aa=aa;` |
| [updatable_views:872](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L872) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:873](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L873) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:885](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L885) | 42501 / 42P01 | `UPDATE rw_view1 SET cc=cc;` |
| [updatable_views:892](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L892) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:893](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L893) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:898](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L898) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:899](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L899) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:908](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L908) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:909](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L909) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:914](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L914) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:915](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L915) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:926](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L926) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:927](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L927) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:937](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L937) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:938](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L938) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:939](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L939) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:940](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L940) | 42501 / 42P01 | `UPDATE rw_view2 SET ccc=ccc;` |
| [updatable_views:955](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L955) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:956](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L956) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:970](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L970) | 42501 / 42P01 | `SELECT * FROM rw_view1;` |
| [updatable_views:971](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L971) | 42501 / 42P01 | `UPDATE rw_view1 SET aa=aa;` |
| [updatable_views:977](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L977) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:978](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L978) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:989](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L989) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:990](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L990) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:1004](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1004) | 42501 / 42P01 | `SELECT * FROM rw_view2;` |
| [updatable_views:1005](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1005) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:1006](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1006) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:1007](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1007) | 42501 / 42P01 | `UPDATE rw_view2 SET ccc=ccc;` |
| [updatable_views:1017](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1017) | 42501 / 42P01 | `UPDATE rw_view2 SET aaa=aaa;` |
| [updatable_views:1018](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1018) | 42501 / 42P01 | `UPDATE rw_view2 SET bbb=bbb;` |
| [updatable_views:1128](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1128) | 0A000 / 42P01 | `INSERT INTO rw_view1 VALUES (null, null, 1.1, null);` |
| [updatable_views:1129](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1129) | 0A000 / 42P01 | `INSERT INTO rw_view1 (s, c, a) VALUES (null, null, 1.1);` |
| [updatable_views:1130](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1130) | 0A000 / 42P01 | `INSERT INTO rw_view1 (s, c, a) VALUES (default, default, 1.1);` |
| [updatable_views:1132](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1132) | 0A000 / 42P01 | `UPDATE rw_view1 SET s = s WHERE a = 1.1;` |
| [updatable_views:1140](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1140) | 0A000 / 42P01 | `INSERT INTO rw_view2 VALUES (null, null, null, 1.1, null);` |
| [updatable_views:1141](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1141) | 0A000 / 42P01 | `INSERT INTO rw_view2(s, c, base_a) VALUES (null, null, 1.1);` |
| [updatable_views:1143](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1143) | 0A000 / 42P01 | `UPDATE rw_view2 SET s = s WHERE base_a = 1.1;` |
| [updatable_views:1144](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1144) | 0A000 / 42P01 | `UPDATE rw_view2 SET t = t WHERE base_a = 1.1;` |
| [updatable_views:1152](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1152) | 0A000 / 42P01 | `INSERT INTO rw_view3 VALUES (null, null, null, null);` |
| [updatable_views:1153](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1153) | 0A000 / 42P01 | `INSERT INTO rw_view3(s) VALUES (null);` |
| [updatable_views:1154](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1154) | 0A000 / 42P01 | `UPDATE rw_view3 SET s = s;` |
| [updatable_views:1190](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1190) | 428C9 / 42P01 | `INSERT INTO rw_view1 (id, idplus1) VALUES (6, 7);` |
| [updatable_views:1269](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1269) | 44000 / 42P01 | `INSERT INTO rw_view1 VALUES(4,3);` |
| [updatable_views:1270](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1270) | 44000 / 42P01 | `INSERT INTO rw_view1 VALUES(5,null);` |
| [updatable_views:1272](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1272) | 44000 / 42P01 | `UPDATE rw_view1 SET b = -5 WHERE a = 3;` |
| [updatable_views:1274](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1274) | 44000 / 42P01 | `INSERT INTO rw_view1(a) VALUES (10);` |
| [updatable_views:1299](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1299) | 44000 / 42P01 | `INSERT INTO rw_view2 VALUES (-5);` |
| [updatable_views:1301](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1301) | 44000 / 42P01 | `INSERT INTO rw_view2 VALUES (15);` |
| [updatable_views:1304](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1304) | 44000 / 42P01 | `UPDATE rw_view2 SET a = a - 10;` |
| [updatable_views:1305](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1305) | 44000 / 42P01 | `UPDATE rw_view2 SET a = a + 10;` |
| [updatable_views:1313](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1313) | 44000 / 42P01 | `INSERT INTO rw_view2 VALUES (20);` |
| [updatable_views:1319](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1319) | 44000 / 42P01 | `INSERT INTO rw_view2 VALUES (-20);` |
| [updatable_views:1320](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1320) | 44000 / 42P01 | `INSERT INTO rw_view2 VALUES (30);` |
| [updatable_views:1343](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1343) | 44000 / 42P01 | `INSERT INTO rw_view3 VALUES (-3);` |
| [updatable_views:1355](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1355) | 44000 / 42P01 | `INSERT INTO rw_view1 VALUES (10, ARRAY[4,5]);` |
| [updatable_views:1379](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1379) | 44000 / 42P01 | `INSERT INTO rw_view1 VALUES (15);` |
| [updatable_views:1382](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1382) | 44000 / 42P01 | `UPDATE rw_view1 SET a = a + 5;` |
| [updatable_views:1446](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1446) | 44000 / 42P01 | `INSERT INTO rw_view2 VALUES (-5);` |
| [updatable_views:1455](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1455) | 44000 / 42P01 | `UPDATE rw_view2 SET a = a - 10;` |
| [updatable_views:1606](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1606) | 55000 / 42P01 | `INSERT INTO uv_upsert_leak_outer VALUES (1, 0, true) ON CONFLICT (id) DO UPDATE SET secret = 0 RETURNING *;` |
| [updatable_views:1608](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1608) | P0001 / 42P01 | `INSERT INTO uv_upsert_leak_outer VALUES (2, 0, true) ON CONFLICT (id) DO UPDATE SET secret = 0 RETURNING *;` |
| [updatable_views:1615](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1615) | 55000 / 42P01 | `INSERT INTO uv_upsert_leak_plain_outer VALUES (1, 0, true) ON CONFLICT (id) DO UPDATE SET secret = 0 RETURNING *;` |
| [updatable_views:1617](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1617) | P0001 / 42P01 | `INSERT INTO uv_upsert_leak_plain_outer VALUES (2, 0, true) ON CONFLICT (id) DO UPDATE SET secret = 0 RETURNING *;` |
| [updatable_views:1770](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1770) | 44000 / 42P01 | `INSERT INTO v2 VALUES (-2, 'minus two', 20);` |
| [updatable_views:1771](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1771) | 44000 / 42P01 | `INSERT INTO v2 VALUES (3, 'three', 30);` |
| [updatable_views:1774](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1774) | 44000 / 42P01 | `UPDATE v2 SET a = -1 WHERE a = 1;` |
| [updatable_views:1775](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1775) | 44000 / 42P01 | `UPDATE v2 SET c = 30 WHERE a = 1;` |
| [updatable_views:1811](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1811) | 44000 / 42P01 | `INSERT INTO v1 VALUES (-1, 'invalid');` |
| [updatable_views:1926](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1926) | 55000 / 42P01 | `insert into uv_upsert_barrier values (1, 0, true) on conflict (id) do update set secret = 0 where uv_upsert_hide_leak(uv ...` |
| [updatable_views:1931](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1931) | 55000 / 42P01 | `insert into uv_upsert_barrier values (1, 0, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:1953](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1953) | 55000 / 42P01 | `insert into uv_upsert_plain values (1, 0, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:1961](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1961) | 55000 / 42P01 | `insert into uv_upsert_checkopt values (1, 0, true) on conflict (id) do update set vis = true returning *;` |
| [updatable_views:1973](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1973) | 55000 / 42P01 | `insert into uv_upsert_sublink values (1, 0, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:1994](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1994) | 55000 / 42P01 | `insert into uv_upsert_plain_over_barrier values (1, 1, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:2001](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2001) | 55000 / 42P01 | `insert into uv_upsert_barrier_over_plain values (1, 1, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:2011](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2011) | 55000 / 42P01 | `insert into uv_upsert_barrier_over_hides values (1, 1, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:2018](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2018) | 55000 / 42P01 | `insert into uv_upsert_barrier_noquals_over_hides values (1, 1, true) on conflict (id) do update set secret = 0 returning ...` |
| [updatable_views:2023](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2023) | 55000 / 42P01 | `insert into uv_upsert_wco_over_hides values (1, 1, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:2030](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2030) | 55000 / 42P01 | `insert into uv_upsert_barrier_over_mid values (1, 1, true) on conflict (id) do update set secret = 0 returning *;` |
| [updatable_views:2055](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2055) | 44000 / 42P01 | `insert into uv_upsert_casc_outer values (1, 5, 5) on conflict (id) do update set a = -1;` |
| [updatable_views:2061](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2061) | 55000 / 42P01 | `insert into uv_upsert_casc_outer values (3, 1, 1) on conflict (id) do update set a = 1 returning *;` |
| [updatable_views:2123](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2123) | 0A000 / 42P01 | `insert into uv_iocu_view (aa) values (1) on conflict (aa) do update set cc = 'XXX';` |
| [updatable_views:2143](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2143) | 42501 / 42P01 | `insert into rw_view1 values ('yyy',2.0,1) on conflict (aa) do update set bb = excluded.cc;` |
| [updatable_views:2145](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2145) | 42501 / 42P01 | `insert into rw_view1 values ('yyy',2.0,1) on conflict (aa) do update set bb = rw_view1.cc;` |
| [updatable_views:2151](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2151) | 42501 / 42P01 | `insert into rw_view1 values ('zzz',2.0,1) on conflict (aa) do update set cc = 3.0;` |
| [updatable_views:2163](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2163) | 42501 / 42P01 | `insert into rw_view2 (aa,bb) values (1,'xxx') on conflict (aa) do update set bb = excluded.bb;` |
| [updatable_views:2173](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L2173) | 42501 / 42P01 | `insert into rw_view4 (aa,bb) values (1,'yyy') on conflict (aa) do update set bb = excluded.bb;` |
| [privileges:283](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L283) | 42501 / 3F000 | `UPDATE pg_toast.pg_toast_1213 SET chunk_id = 1;` |
| [privileges:444](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L444) | 42501 / 42P01 | `SELECT * FROM atestv2;` |
| [privileges:451](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L451) | 42501 / 42P01 | `SELECT * FROM atestv2;` |
| [privileges:453](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L453) | 42501 / 42P01 | `SELECT * FROM atestv0;` |
| [privileges:478](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L478) | 42501 / 42P01 | `SELECT * FROM atestv3;` |
| [privileges:482](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L482) | 42501 / 42P01 | `SELECT * FROM atestv2;` |
| [collate:144](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.sql#L144) | 42P21 / 42P01 | `SELECT a, b FROM collate_test1 UNION SELECT a, b FROM collate_test2 ORDER BY 2;` |
| [collate:146](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.sql#L146) | 42P21 / 42P01 | `SELECT a, b FROM collate_test1 INTERSECT SELECT a, b FROM collate_test2 ORDER BY 2;` |
| [collate:147](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.sql#L147) | 42P21 / 42P01 | `SELECT a, b FROM collate_test1 EXCEPT SELECT a, b FROM collate_test2 ORDER BY 2;` |
| [collate:154](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.sql#L154) | 42P21 / 42P01 | `select x, y from collate_test10 order by x \|\| y;` |
| [matview:19](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/matview.sql#L19) | 55000 / 42P01 | `SELECT * FROM mvtest_tm ORDER BY type;` |
| [matview:264](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/matview.sql#L264) | 55000 / 42P01 | `SELECT * FROM mvtest2;` |
| [rowsecurity:454](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L454) | 42501 / 42P01 | `SELECT * FROM part_document ORDER by did;` |
| [rowsecurity:455](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L455) | 42501 / 42P01 | `SELECT * FROM part_document_satire ORDER by did;` |
| [rowsecurity:493](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L493) | 42P17 / 42P01 | `SELECT * FROM rec1;` |
| [rowsecurity:505](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L505) | 42P17 / 42P01 | `SELECT * FROM rec1;` |
| [rowsecurity:518](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L518) | 42P17 / 42P01 | `SELECT * FROM rec1;` |
| [rowsecurity:534](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L534) | 42P17 / 42P01 | `SELECT * FROM rec1;` |
| [rowsecurity:557](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L557) | 42P17 / 42P01 | `SELECT * FROM s1 WHERE f_leak(b);` |
| [rowsecurity:559](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L559) | 42P17 / 42P01 | `INSERT INTO s1 VALUES (1, 'foo');` |
| [rowsecurity:581](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L581) | 42P17 / 42P01 | `SELECT * FROM s1 WHERE f_leak(b);` |
| [rowsecurity:1151](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1151) | 42501 / 42P01 | `SELECT * FROM rls_view;` |
| [rowsecurity:1170](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1170) | 42501 / 42P01 | `SELECT * FROM rls_view;` |
| [rowsecurity:1175](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1175) | 42501 / 42P01 | `SELECT * FROM rls_view;` |
| [rowsecurity:1253](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1253) | 42501 / 42P01 | `SELECT * FROM rls_view;` |
| [rowsecurity:1258](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1258) | 42501 / 42P01 | `SELECT * FROM rls_view;` |
| [rowsecurity:1271](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1271) | 42501 / 42P01 | `SELECT * FROM rls_view;` |
| [rowsecurity:1936](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1936) | 42501 / 42P01 | `UPDATE r1 SET a = 1;` |
| [rowsecurity:1937](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1937) | 42501 / 42P01 | `DELETE FROM r1;` |
| [rowsecurity:2226](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2226) | 42501 / 42P01 | `SELECT * FROM ref_tbl;` |
| [rowsecurity:2227](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2227) | 42501 / 42P01 | `SELECT * FROM rls_tbl;` |
| [tablesample:109](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/tablesample.sql#L109) | 55000 / 42P01 | `INSERT INTO test_tablesample_v1 VALUES(1);` |
| [identity:184](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/identity.sql#L184) | 428C9 / 42P01 | `INSERT INTO itestv11 VALUES (10, 'xyz');` |
| [generated_stored:55](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L55) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, 33);` |
| [generated_stored:56](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L56) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, 33), (4, 44);` |
| [generated_stored:57](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L57) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, DEFAULT), (4, 44);` |
| [generated_stored:58](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L58) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, 33), (4, DEFAULT);` |
| [generated_stored:67](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L67) | 428C9 / 42P01 | `UPDATE gtest1 SET b = 11 WHERE a = 1;` |
| [generated_stored:119](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L119) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (4, 8);` |
| [generated_stored:121](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L121) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (6, 66), (7, 77);` |
| [generated_stored:122](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L122) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (6, DEFAULT), (7, 77);` |
| [generated_stored:123](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L123) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (6, 66), (7, DEFAULT);` |
| [generated_stored:127](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L127) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (8, DEFAULT);` |
| [generated_stored:128](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L128) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (8, DEFAULT), (9, DEFAULT);` |
| [generated_stored:306](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L306) | 42501 / 42P01 | `SELECT a, b FROM gtest11;` |
| [collate.icu.utf8:242](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L242) | 42P21 / 42P01 | `SELECT a, b FROM collate_test1 UNION SELECT a, b FROM collate_test3 ORDER BY 2;` |
| [collate.icu.utf8:244](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L244) | 42P21 / 42P01 | `SELECT a, b FROM collate_test1 INTERSECT SELECT a, b FROM collate_test3 ORDER BY 2;` |
| [collate.icu.utf8:245](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L245) | 42P21 / 42P01 | `SELECT a, b FROM collate_test1 EXCEPT SELECT a, b FROM collate_test3 ORDER BY 2;` |
| [collate.icu.utf8:252](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L252) | 42P21 / 42P01 | `select x, y from collate_test10 order by x \|\| y;` |
| [collate.icu.utf8:723](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L723) | 0A000 / 42P01 | `CREATE INDEX ON test3ci (x text_pattern_ops);` |
| [collate.icu.utf8:746](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L746) | 23505 / 42P01 | `CREATE UNIQUE INDEX ON test3ci (x);` |
| [collate.icu.utf8:1022](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L1022) | 0A000 / 42P01 | `CREATE INDEX ON test3bpci (x bpchar_pattern_ops);` |
| [collate.icu.utf8:1045](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L1045) | 23505 / 42P01 | `CREATE UNIQUE INDEX ON test3bpci (x);` |
| [generated_virtual:55](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L55) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, 33);` |
| [generated_virtual:56](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L56) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, 33), (4, 44);` |
| [generated_virtual:57](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L57) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, DEFAULT), (4, 44);` |
| [generated_virtual:58](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L58) | 428C9 / 42P01 | `INSERT INTO gtest1 VALUES (3, 33), (4, DEFAULT);` |
| [generated_virtual:67](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L67) | 428C9 / 42P01 | `UPDATE gtest1 SET b = 11 WHERE a = 1;` |
| [generated_virtual:119](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L119) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (4, 8);` |
| [generated_virtual:121](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L121) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (6, 66), (7, 77);` |
| [generated_virtual:122](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L122) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (6, DEFAULT), (7, 77);` |
| [generated_virtual:123](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L123) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (6, 66), (7, DEFAULT);` |
| [generated_virtual:127](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L127) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (8, DEFAULT);` |
| [generated_virtual:128](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L128) | 428C9 / 42P01 | `INSERT INTO gtest1v VALUES (8, DEFAULT), (9, DEFAULT);` |
| [generated_virtual:306](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L306) | 42501 / 42P01 | `SELECT a, b FROM gtest11;` |
| [generated_virtual:649](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L649) | 428C9 / 42P01 | `INSERT INTO gtest29 (a, b) VALUES (6, 66);` |
| [rules:684](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L684) | 0A000 / 42P01 | `insert into shoelace values ('sl10', 1000, 'magenta', 40.0, 'inch', 0.0) on conflict do nothing;` |
| [rules:861](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L861) | 0A000 / 42P01 | `insert into shoelace values ('sl9', 0, 'pink', 35.0, 'inch', 0.0) on conflict (sl_name) do update set sl_avail = exclude ...` |
| [foreign_data:332](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_data.sql#L332) | 42809 / 42P01 | `CREATE INDEX id_ft1_c2 ON ft1 (c2);` |
| [foreign_data:333](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_data.sql#L333) | 55000 / 42P01 | `SELECT * FROM ft1;` |
| [domain:549](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L549) | 23502 / 42P01 | `select * from domview;` |
| [domain:555](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/domain.sql#L555) | 23514 / 42P01 | `select * from domview;` |
| [rangefuncs:704](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangefuncs.sql#L704) | 42703 / 42P01 | `select * from usersview;` |
| [rangefuncs:722](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangefuncs.sql#L722) | 42804 / 42P01 | `select * from usersview;` |
| [returning:98](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/returning.sql#L98) | 0A000 / 42P01 | `INSERT INTO voo VALUES(12,'zoo') RETURNING *, f1*2;` |
| [returning:314](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/returning.sql#L314) | 42702 / 42P01 | `UPDATE joinview SET f3 = f3 + 1, f4 = 7 WHERE f3 = 58 RETURNING old.*, new.*, *, new.f3 - old.f3 AS delta_f3;` |

</details>

<details>
<summary>#181: 129 statements</summary>

After a refused SET, a later statement answers as without the setting

| file and line | codes | statement |
| --- | --- | --- |
| [create_view:197](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L197) | 00000 / 00000 | `CREATE TABLE t1 (num int, name text);` |
| [create_view:198](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L198) | 00000 / 00000 | `CREATE TABLE t2 (num2 int, value text);` |
| [create_view:219](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L219) | 00000 / 00000 | `CREATE TABLE tbl1 ( a int, b int);` |
| [create_view:220](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L220) | 00000 / 00000 | `CREATE TABLE tbl2 (c int, d int);` |
| [create_view:221](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L221) | 00000 / 00000 | `CREATE TABLE tbl3 (e int, f int);` |
| [create_view:222](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L222) | 00000 / 00000 | `CREATE TABLE tbl4 (g int, h int);` |
| [create_view:316](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L316) | 00000 / 00000 | `CREATE TABLE tt1 (f1 int, f2 int, f3 text);` |
| [create_view:317](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L317) | 00000 / 00000 | `CREATE TABLE tx1 (x1 int, x2 int, x3 text);` |
| [create_view:401](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L401) | 00000 / 00000 | `create table tbl1a (a int, c int);` |
| [create_view:414](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L414) | 00000 / 00000 | `create table tt2 (a int, b int, c int);` |
| [create_view:415](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L415) | 00000 / 00000 | `create table tt3 (ax int8, b int2, c numeric);` |
| [create_view:416](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L416) | 00000 / 00000 | `create table tt4 (ay int, b int, q int);` |
| [create_view:464](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L464) | 00000 / 00000 | `create table tt5 (a int, b int);` |
| [create_view:465](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L465) | 00000 / 00000 | `create table tt6 (c int, d int);` |
| [create_view:483](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L483) | 00000 / 00000 | `create table tt7 (x int, xx int, y int);` |
| [create_view:485](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L485) | 00000 / 00000 | `create table tt8 (x int, z int);` |
| [create_view:523](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L523) | 00000 / 00000 | `create table tt7a (x date, xx int, y int);` |
| [create_view:538](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L538) | 00000 / 00000 | `create table tt9 (x int, xx int, y int);` |
| [create_view:539](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L539) | 00000 / 00000 | `create table tt10 (x int, z int);` |
| [create_view:554](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L554) | 00000 / 00000 | `create table tt11 (x int, y int);` |
| [create_view:555](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L555) | 00000 / 00000 | `create table tt12 (x int, z int);` |
| [create_view:556](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L556) | 00000 / 00000 | `create table tt13 (z int, q int);` |
| [create_view:571](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L571) | 00000 / 00000 | `create table tt14t (f1 text, f2 text, f3 text, f4 text);` |
| [create_view:847](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_view.sql#L847) | 00000 / 00000 | `create table tt27v_tbl (a int);` |
| [create_function_sql:212](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_function_sql.sql#L212) | 00000 / 00000 | `CREATE TABLE functest1 (i int);` |
| [create_function_sql:261](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_function_sql.sql#L261) | 00000 / 00000 | `CREATE TABLE functest3 (a int);` |
| [create_function_sql:313](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_function_sql.sql#L313) | 00000 / 00000 | `CREATE TABLE functest2 (a int, b int);` |
| [create_function_sql:370](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_function_sql.sql#L370) | 00000 / 00000 | `CREATE TABLE functest3 (a int);` |
| [aggregates:42](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/aggregates.sql#L42) | 00000 / 00000 | `SELECT sum(b) AS avg_431_773 FROM aggtest;` |
| [transactions:107](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/transactions.sql#L107) | 25006 / 00000 | `INSERT INTO writetest VALUES (1);` |
| [namespace:85](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/namespace.sql#L85) | 00000 / 00000 | `CREATE TABLE test_maint(i INT);` |
| [lock:14](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/lock.sql#L14) | 00000 / 00000 | `CREATE TABLE lock_tbl1 (a BIGINT);` |
| [lock:15](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/lock.sql#L15) | 00000 / 00000 | `CREATE TABLE lock_tbl1a (a BIGINT);` |
| [rowsecurity:58](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L58) | 00000 / 00000 | `CREATE TABLE category ( cid int primary key, cname text );` |
| [rowsecurity:239](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L239) | 00000 / 00000 | `CREATE TABLE t1 (id int not null primary key, a int, junk1 text, b text);` |
| [rowsecurity:260](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L260) | 00000 / 00000 | `CREATE TABLE t3 (id int not null primary key, c text, b text, a int);` |
| [rowsecurity:470](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L470) | 00000 / 00000 | `CREATE TABLE dependee (x integer, y integer);` |
| [rowsecurity:472](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L472) | 00000 / 00000 | `CREATE TABLE dependent (x integer, y integer);` |
| [rowsecurity:489](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L489) | 00000 / 00000 | `CREATE TABLE rec1 (x integer, y integer);` |
| [rowsecurity:499](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L499) | 00000 / 00000 | `CREATE TABLE rec2 (a integer, b integer);` |
| [rowsecurity:540](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L540) | 00000 / 00000 | `CREATE TABLE s1 (a int, b text);` |
| [rowsecurity:543](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L543) | 00000 / 00000 | `CREATE TABLE s2 (x int, y text);` |
| [rowsecurity:675](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L675) | 00000 / 00000 | `CREATE TABLE b1 (a int, b text);` |
| [rowsecurity:1059](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1059) | 00000 / 00000 | `CREATE TABLE z1 (a int, b text);` |
| [rowsecurity:1060](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1060) | 00000 / 00000 | `CREATE TABLE z2 (a int, b text);` |
| [rowsecurity:1164](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1164) | 00000 / 00000 | `CREATE TABLE z1_blacklist (a int);` |
| [rowsecurity:1290](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1290) | 00000 / 00000 | `CREATE TABLE x1 (a int, b text, c text);` |
| [rowsecurity:1324](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1324) | 00000 / 00000 | `CREATE TABLE y1 (a int, b text);` |
| [rowsecurity:1325](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1325) | 00000 / 00000 | `CREATE TABLE y2 (a int, b text);` |
| [rowsecurity:1372](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1372) | 00000 / 00000 | `CREATE TABLE test_qual_pushdown ( abc text );` |
| [rowsecurity:1393](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1393) | 00000 / 00000 | `CREATE TABLE t1 (a integer);` |
| [rowsecurity:1423](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1423) | 00000 / 00000 | `CREATE TABLE t1 (a integer, b text);` |
| [rowsecurity:1466](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1466) | 00000 / 00000 | `CREATE TABLE t2 (a integer, b text);` |
| [rowsecurity:1480](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1480) | 00000 / 00000 | `CREATE TABLE blog (id integer, author text, post text);` |
| [rowsecurity:1481](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1481) | 00000 / 00000 | `CREATE TABLE comment (blog_id integer, message text);` |
| [rowsecurity:1555](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1555) | 00000 / 00000 | `CREATE TABLE copy_t (a integer, b text);` |
| [rowsecurity:1595](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1595) | 00000 / 00000 | `CREATE TABLE copy_rel_to (a integer, b text);` |
| [rowsecurity:1718](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1718) | 00000 / 00000 | `CREATE TABLE current_check (currentid int, payload text, rlsuser text);` |
| [rowsecurity:1871](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1871) | 00000 / 00000 | `CREATE TABLE r1 (a int);` |
| [rowsecurity:1872](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1872) | 00000 / 00000 | `CREATE TABLE r2 (a int);` |
| [rowsecurity:1912](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1912) | 00000 / 00000 | `CREATE TABLE r1 (a int);` |
| [rowsecurity:1946](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1946) | 00000 / 00000 | `CREATE TABLE r1 (a int PRIMARY KEY);` |
| [rowsecurity:1984](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L1984) | 00000 / 00000 | `CREATE TABLE r1 (a int PRIMARY KEY);` |
| [rowsecurity:2010](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2010) | 00000 / 00000 | `CREATE TABLE r1 (a int PRIMARY KEY);` |
| [rowsecurity:2041](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2041) | 00000 / 00000 | `CREATE TABLE r1 (a int);` |
| [rowsecurity:2071](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2071) | 00000 / 00000 | `CREATE TABLE r1 (a int PRIMARY KEY);` |
| [rowsecurity:2139](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2139) | 00000 / 00000 | `CREATE TABLE dep1 (c1 int);` |
| [rowsecurity:2140](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2140) | 00000 / 00000 | `CREATE TABLE dep2 (c1 int);` |
| [rowsecurity:2173](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2173) | 00000 / 00000 | `CREATE TABLE dob_t1 (c1 int);` |
| [rowsecurity:2209](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2209) | 00000 / 00000 | `CREATE TABLE ref_tbl (a int);` |
| [rowsecurity:2212](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2212) | 00000 / 00000 | `CREATE TABLE rls_tbl (a int PRIMARY KEY);` |
| [rowsecurity:2250](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2250) | 00000 / 00000 | `CREATE TABLE rls_tbl (a int);` |
| [rowsecurity:2321](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2321) | 00000 / 00000 | `CREATE TABLE rls_tbl (a int, b int, c int);` |
| [rowsecurity:2339](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2339) | 00000 / 00000 | `create table rls_t (c text);` |
| [rowsecurity:2417](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2417) | 00000 / 00000 | `create table rls_t (c text);` |
| [rowsecurity:2418](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2418) | 00000 / 00000 | `create table test_t (c text);` |
| [rowsecurity:2478](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2478) | 00000 / 00000 | `CREATE TABLE rls_tbl (c1 int);` |
| [rowsecurity:2485](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowsecurity.sql#L2485) | 00000 / 00000 | `CREATE TABLE rls_tbl_force (c1 int);` |
| [generated_stored:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L80) | 00000 / 00000 | `CREATE TABLE gtestx (x int, y int);` |
| [generated_stored:146](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L146) | 00000 / 00000 | `CREATE TABLE gtest_normal (a int, b int);` |
| [generated_stored:162](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L162) | 00000 / 00000 | `CREATE TABLE gtestxx_1 (a int NOT NULL, b int);` |
| [generated_stored:170](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L170) | 00000 / 00000 | `CREATE TABLE gtesty (x int, b int DEFAULT 55);` |
| [generated_stored:174](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L174) | 00000 / 00000 | `CREATE TABLE gtesty (x int, b int);` |
| [generated_stored:184](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L184) | 00000 / 00000 | `CREATE TABLE gtestp (f1 int);` |
| [generated_stored:396](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L396) | 00000 / 00000 | `CREATE TABLE gtest23a (x int PRIMARY KEY, y int);` |
| [generated_stored:464](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L464) | 00000 / 00000 | `CREATE TABLE gtest_child3 (f1 date NOT NULL, f2 bigint, f3 bigint);` |
| [generated_stored:467](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L467) | 00000 / 00000 | `CREATE TABLE gtest_child3 (f1 date NOT NULL, f2 bigint, f3 bigint DEFAULT 42);` |
| [generated_stored:517](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L517) | 00000 / 00000 | `CREATE TABLE gtest25 (a int PRIMARY KEY);` |
| [generated_stored:783](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L783) | 00000 / 00000 | `CREATE TABLE gtest_rule_log (op text, old_b int, new_b int);` |
| [alter_generic:466](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/alter_generic.sql#L466) | 00000 / 00000 | `CREATE TABLE alt_regress_1 (a INTEGER, b INTEGER);` |
| [alter_generic:477](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/alter_generic.sql#L477) | 00000 / 00000 | `CREATE TABLE alt_regress_2 (a INTEGER, b INTEGER);` |
| [collate.icu.utf8:440](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L440) | 00000 / 00000 | `CREATE TABLE collate_dep_test4t (a int, b text);` |
| [collate.icu.utf8:507](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L507) | 00000 / 00000 | `CREATE TABLE test7 (a text);` |
| [collate.icu.utf8:528](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L528) | 00000 / 00000 | `CREATE TABLE test6 (a int, b text);` |
| [collate.icu.utf8:554](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L554) | 00000 / 00000 | `CREATE TABLE test6a (a int, b text[]);` |
| [collate.icu.utf8:1095](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L1095) | 00000 / 00000 | `CREATE TABLE test4 (a int, b text);` |
| [collate.icu.utf8:1102](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/collate.icu.utf8.sql#L1102) | 00000 / 00000 | `CREATE TABLE test4nfd (a int, b text);` |
| [generated_virtual:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L80) | 00000 / 00000 | `CREATE TABLE gtestx (x int, y int);` |
| [generated_virtual:146](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L146) | 00000 / 00000 | `CREATE TABLE gtest_normal (a int, b int);` |
| [generated_virtual:162](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L162) | 00000 / 00000 | `CREATE TABLE gtestxx_1 (a int NOT NULL, b int);` |
| [generated_virtual:170](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L170) | 00000 / 00000 | `CREATE TABLE gtesty (x int, b int DEFAULT 55);` |
| [generated_virtual:174](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L174) | 00000 / 00000 | `CREATE TABLE gtesty (x int, b int);` |
| [generated_virtual:184](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L184) | 00000 / 00000 | `CREATE TABLE gtestp (f1 int);` |
| [generated_virtual:382](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L382) | 00000 / 00000 | `CREATE TABLE gtest21d (a int NOT NULL);` |
| [generated_virtual:452](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L452) | 00000 / 00000 | `CREATE TABLE gtest23a (x int PRIMARY KEY, y int);` |
| [generated_virtual:486](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L486) | 00000 / 00000 | `CREATE TABLE gtest24at (a int PRIMARY KEY);` |
| [generated_virtual:528](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L528) | 00000 / 00000 | `CREATE TABLE gtest_child3 (f1 date NOT NULL, f2 bigint, f3 bigint);` |
| [generated_virtual:531](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L531) | 00000 / 00000 | `CREATE TABLE gtest_child3 (f1 date NOT NULL, f2 bigint, f3 bigint DEFAULT 42);` |
| [generated_virtual:581](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L581) | 00000 / 00000 | `CREATE TABLE gtest25 (a int PRIMARY KEY);` |
| [generated_virtual:840](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L840) | 00000 / 00000 | `CREATE TABLE gtest_rule_log (op text, old_b int, new_b int);` |
| [psql:1269](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L1269) | 00000 / 00000 | `create table testtable_apple(logdate date);` |
| [psql:1270](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L1270) | 00000 / 00000 | `create table testtable_orange(logdate date);` |
| [foreign_key:1907](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1907) | 00000 / 00000 | `CREATE TABLE pk2 (b int, a int);` |
| [foreign_key:1915](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1915) | 00000 / 00000 | `CREATE TABLE fk2 (b int, a int) ;` |
| [foreign_key:2026](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2026) | 00000 / 00000 | `CREATE TABLE pk32 (b int, a int NOT NULL);` |
| [foreign_key:2031](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2031) | 00000 / 00000 | `CREATE TABLE fk3 (b int, a int);` |
| [foreign_key:2069](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2069) | 00000 / 00000 | `CREATE TABLE pt(f1 int, f2 int, f3 int, PRIMARY KEY(f1,f2));` |
| [foreign_key:2085](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2085) | 00000 / 00000 | `CREATE TABLE pt(f1 int, f2 int, f3 int, PRIMARY KEY(f1,f2));` |
| [foreign_key:2110](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2110) | 00000 / 00000 | `CREATE TABLE ref(f1 int, f2 int, f3 int);` |
| [foreign_key:2127](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2127) | 00000 / 00000 | `CREATE TABLE ref(f1 int, f2 int, f3 int);` |
| [foreign_key:2461](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2461) | 00000 / 00000 | `CREATE TABLE fkpart13_t1 (a int PRIMARY KEY);` |
| [indexing:822](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/indexing.sql#L822) | 00000 / 00000 | `create table pk2 (b int, a int);` |
| [fast_default:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L83) | 00000 / 00000 | `CREATE TABLE T(pk INT NOT NULL PRIMARY KEY, c_int INT DEFAULT 1);` |
| [fast_default:298](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L298) | 00000 / 00000 | `CREATE TABLE T(pk INT NOT NULL PRIMARY KEY);` |
| [fast_default:328](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L328) | 00000 / 00000 | `CREATE TABLE T (pk INT NOT NULL PRIMARY KEY);` |
| [fast_default:388](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L388) | 00000 / 00000 | `CREATE TABLE T(pk INT NOT NULL PRIMARY KEY);` |
| [fast_default:543](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L543) | 00000 / 00000 | `CREATE TABLE leader (a int PRIMARY KEY, b int);` |
| [fast_default:552](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L552) | 00000 / 00000 | `CREATE TABLE vtype( a integer);` |
| [fast_default:564](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/fast_default.sql#L564) | 00000 / 00000 | `CREATE TABLE vtype2 (a int);` |

</details>

<details>
<summary>#200: 93 statements</summary>

Date, time, and interval text that is out of range answers 22007

| file and line | codes | statement |
| --- | --- | --- |
| [uuid:135](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/uuid.sql#L135) | 22008 / 22007 | `SELECT uuidv7('infinity'::interval);` |
| [uuid:136](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/uuid.sql#L136) | 22008 / 22007 | `SELECT uuidv7('-infinity'::interval);` |
| [date:42](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L42) | 22008 / 22007 | `SELECT date '1/8/1999';` |
| [date:43](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L43) | 22008 / 22007 | `SELECT date '1/18/1999';` |
| [date:44](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L44) | 22008 / 22007 | `SELECT date '18/1/1999';` |
| [date:50](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L50) | 22008 / 22007 | `SELECT date 'January 8, 99 BC';` |
| [date:54](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L54) | 22008 / 22007 | `SELECT date '08-Jan-99';` |
| [date:56](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L56) | 22008 / 22007 | `SELECT date 'Jan-08-99';` |
| [date:63](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L63) | 22008 / 22007 | `SELECT date '08 Jan 99';` |
| [date:65](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L65) | 22008 / 22007 | `SELECT date 'Jan 08 99';` |
| [date:73](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L73) | 22008 / 22007 | `SELECT date '08-01-1999';` |
| [date:75](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L75) | 22008 / 22007 | `SELECT date '01-08-1999';` |
| [date:81](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L81) | 22008 / 22007 | `SELECT date '08 01 99';` |
| [date:82](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L82) | 22008 / 22007 | `SELECT date '08 01 1999';` |
| [date:83](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L83) | 22008 / 22007 | `SELECT date '01 08 99';` |
| [date:84](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L84) | 22008 / 22007 | `SELECT date '01 08 1999';` |
| [date:94](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L94) | 22008 / 22007 | `SELECT date '1/18/1999';` |
| [date:103](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L103) | 22008 / 22007 | `SELECT date '99-Jan-08';` |
| [date:112](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L112) | 22008 / 22007 | `SELECT date '99 Jan 08';` |
| [date:130](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L130) | 22008 / 22007 | `SELECT date '99 01 08';` |
| [date:136](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L136) | 22008 / 22007 | `SELECT date '99 08 01';` |
| [date:146](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L146) | 22008 / 22007 | `SELECT date '18/1/1999';` |
| [date:154](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L154) | 22008 / 22007 | `SELECT date '99-Jan-08';` |
| [date:181](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L181) | 22008 / 22007 | `SELECT date '99 01 08';` |
| [date:187](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L187) | 22008 / 22007 | `SELECT date '99 08 01';` |
| [date:192](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L192) | 22008 / 22007 | `SELECT date '4714-11-23 BC';` |
| [date:194](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L194) | 22008 / 22007 | `SELECT date '5874898-01-01';` |
| [date:363](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L363) | 22023 / 22007 | `SELECT EXTRACT(MICROSEC FROM DATE 'infinity');` |
| [timestamp:151](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L151) | 22008 / 22007 | `SELECT '4714-11-23 23:59:59 BC'::timestamp;` |
| [timestamp:153](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L153) | 22008 / 22007 | `SELECT '294277-01-01 00:00:00'::timestamp;` |
| [timestamp:180](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L180) | 0A000 / 22007 | `SELECT date_trunc( 'timezone', timestamp 'infinity' ) AS notsupp_inf_trunc;` |
| [timestamp:181](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L181) | 22023 / 22007 | `SELECT date_trunc( 'ago', timestamp 'infinity' ) AS invalid_trunc;` |
| [timestamp:289](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L289) | 22008 / 22007 | `select date_bin('15 minutes'::interval, timestamp '294276-12-30', timestamp '4000-12-20 BC');` |
| [timestamp:291](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L291) | 22008 / 22007 | `select date_bin('365000 days'::interval, '4400-01-01 BC'::timestamp, '4000-01-01 BC'::timestamp);` |
| [timestamp:343](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L343) | 22008 / 22007 | `SELECT timestamp '294276-12-31 23:59:59' - timestamp '1999-12-23 19:59:04.224192' AS overflows;` |
| [timestamp:412](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L412) | 22023 / 22007 | `select generate_series(timestamp '1995-08-06 12:12:12', timestamp '1996-08-06 12:12:12', interval 'infinity');` |
| [timestamp:413](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L413) | 22023 / 22007 | `select generate_series(timestamp '1995-08-06 12:12:12', timestamp '1996-08-06 12:12:12', interval '-infinity');` |
| [timestamp:417](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L417) | 22008 / 22007 | `select timestamp 'infinity' - timestamp 'infinity';` |
| [timestamp:420](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L420) | 22008 / 22007 | `select timestamp '-infinity' - timestamp '-infinity';` |
| [timestamp:427](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L427) | 22008 / 22007 | `select age(timestamp 'infinity', timestamp 'infinity');` |
| [timestamp:430](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/timestamp.sql#L430) | 22008 / 22007 | `select age(timestamp '-infinity', timestamp '-infinity');` |
| [interval:415](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L415) | 22015 / 22007 | `select interval '2147483647 decades';` |
| [interval:416](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L416) | 22015 / 22007 | `select interval '-2147483648 decades';` |
| [interval:417](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L417) | 22015 / 22007 | `select interval '2147483647 centuries';` |
| [interval:418](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L418) | 22015 / 22007 | `select interval '-2147483648 centuries';` |
| [interval:419](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L419) | 22015 / 22007 | `select interval '2147483647 millennium';` |
| [interval:420](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L420) | 22015 / 22007 | `select interval '-2147483648 millennium';` |
| [interval:432](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L432) | 22015 / 22007 | `select interval '1 decade 2147483647 years';` |
| [interval:433](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L433) | 22015 / 22007 | `select interval '1 century 2147483647 years';` |
| [interval:434](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L434) | 22015 / 22007 | `select interval '1 millennium 2147483647 years';` |
| [interval:435](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L435) | 22015 / 22007 | `select interval '-1 decade -2147483648 years';` |
| [interval:436](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L436) | 22015 / 22007 | `select interval '-1 century -2147483648 years';` |
| [interval:437](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L437) | 22015 / 22007 | `select interval '-1 millennium -2147483648 years';` |
| [interval:439](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L439) | 22015 / 22007 | `select interval '2147483647 years 1 decade';` |
| [interval:440](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L440) | 22015 / 22007 | `select interval '2147483647 years 1 century';` |
| [interval:441](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L441) | 22015 / 22007 | `select interval '2147483647 years 1 millennium';` |
| [interval:442](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L442) | 22015 / 22007 | `select interval '-2147483648 years -1 decade';` |
| [interval:443](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L443) | 22015 / 22007 | `select interval '-2147483648 years -1 century';` |
| [interval:444](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L444) | 22015 / 22007 | `select interval '-2147483648 years -1 millennium';` |
| [interval:447](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L447) | 22015 / 22007 | `select interval '0.1 millennium 2147483647 months';` |
| [interval:448](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L448) | 22015 / 22007 | `select interval '0.1 centuries 2147483647 months';` |
| [interval:449](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L449) | 22015 / 22007 | `select interval '0.1 decades 2147483647 months';` |
| [interval:451](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L451) | 22015 / 22007 | `select interval '-0.1 millennium -2147483648 months';` |
| [interval:452](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L452) | 22015 / 22007 | `select interval '-0.1 centuries -2147483648 months';` |
| [interval:453](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L453) | 22015 / 22007 | `select interval '-0.1 decades -2147483648 months';` |
| [interval:456](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L456) | 22015 / 22007 | `select interval '2147483647 months 0.1 millennium';` |
| [interval:457](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L457) | 22015 / 22007 | `select interval '2147483647 months 0.1 centuries';` |
| [interval:458](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L458) | 22015 / 22007 | `select interval '2147483647 months 0.1 decades';` |
| [interval:460](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L460) | 22015 / 22007 | `select interval '-2147483648 months -0.1 millennium';` |
| [interval:461](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L461) | 22015 / 22007 | `select interval '-2147483648 months -0.1 centuries';` |
| [interval:462](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L462) | 22015 / 22007 | `select interval '-2147483648 months -0.1 decades';` |
| [interval:530](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L530) | 22015 / 22007 | `select interval '2562047788.1:0:54.775807';` |
| [interval:531](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L531) | 22015 / 22007 | `select interval '2562047788.1:0:54.775808 ago';` |
| [interval:754](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L754) | 22008 / 22007 | `SELECT interval 'infinity' * 'nan';` |
| [interval:755](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L755) | 22008 / 22007 | `SELECT interval '-infinity' * 'nan';` |
| [interval:757](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L757) | 22008 / 22007 | `SELECT interval 'infinity' * 0;` |
| [interval:758](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L758) | 22008 / 22007 | `SELECT interval '-infinity' * 0;` |
| [interval:764](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L764) | 22008 / 22007 | `SELECT interval 'infinity' / 'infinity';` |
| [interval:765](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L765) | 22008 / 22007 | `SELECT interval 'infinity' / '-infinity';` |
| [interval:766](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L766) | 22008 / 22007 | `SELECT interval 'infinity' / 'nan';` |
| [interval:767](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L767) | 22008 / 22007 | `SELECT interval '-infinity' / 'infinity';` |
| [interval:768](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L768) | 22008 / 22007 | `SELECT interval '-infinity' / '-infinity';` |
| [interval:769](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L769) | 22008 / 22007 | `SELECT interval '-infinity' / 'nan';` |
| [interval:791](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L791) | 22023 / 22007 | `SELECT timezone('infinity'::interval, '1995-08-06 12:12:12'::timestamp);` |
| [interval:792](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L792) | 22023 / 22007 | `SELECT timezone('-infinity'::interval, '1995-08-06 12:12:12'::timestamp);` |
| [interval:793](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L793) | 22023 / 22007 | `SELECT timezone('infinity'::interval, '1995-08-06 12:12:12'::timestamptz);` |
| [interval:794](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L794) | 22023 / 22007 | `SELECT timezone('-infinity'::interval, '1995-08-06 12:12:12'::timestamptz);` |
| [interval:795](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L795) | 22023 / 22007 | `SELECT timezone('infinity'::interval, '12:12:12'::time);` |
| [interval:796](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L796) | 22023 / 22007 | `SELECT timezone('-infinity'::interval, '12:12:12'::time);` |
| [interval:797](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L797) | 22023 / 22007 | `SELECT timezone('infinity'::interval, '12:12:12'::timetz);` |
| [interval:798](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/interval.sql#L798) | 22023 / 22007 | `SELECT timezone('-infinity'::interval, '12:12:12'::timetz);` |
| [horology:157](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L157) | 22008 / 22007 | `SELECT timestamp without time zone '294276-12-31 23:59:59' + interval '9223372036854775807 microseconds' AS "out of rang ...` |
| [horology:362](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L362) | 22008 / 22007 | `SELECT '2202020-10-05'::date::timestamp;` |

</details>

<details>
<summary>#202: 56 statements</summary>

Text that the parser does not read answers 42601 instead of 0A000

| file and line | codes | statement |
| --- | --- | --- |
| [strings:61](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L61) | 0A000 / 42601 | `SELECT U&'wrong: \061';` |
| [strings:62](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L62) | 0A000 / 42601 | `SELECT U&'wrong: \+0061';` |
| [strings:63](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L63) | 0A000 / 42601 | `SELECT U&'wrong: +0061' UESCAPE '+';` |
| [update:47](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/update.sql#L47) | 42804 / 42601 | `UPDATE update_test SET a = v.* FROM (VALUES(100, 20)) AS v(i, j) WHERE update_test.b = v.j;` |
| [privileges:512](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L512) | 42501 / 42601 | `SELECT (j.*) IS NULL FROM (atest5 a JOIN atest5 b USING (one)) j;` |
| [privileges:521](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L521) | 42501 / 42601 | `SELECT (a.*) IS NULL FROM (atest5 a JOIN atest5 b USING (one));` |
| [without_overlaps:22](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L22) | 42703 / 42601 | `CREATE TABLE temporal_rng ( id INTEGER, CONSTRAINT temporal_rng_pk PRIMARY KEY (id, valid_at WITHOUT OVERLAPS) );` |
| [without_overlaps:29](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L29) | 42804 / 42601 | `CREATE TABLE temporal_rng ( id int4range, valid_at TEXT, CONSTRAINT temporal_rng_pk PRIMARY KEY (id, valid_at WITHOUT OV ...` |
| [without_overlaps:135](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L135) | 42703 / 42601 | `CREATE TABLE temporal_rng3 ( id INTEGER, CONSTRAINT temporal_rng3_uq UNIQUE (id, valid_at WITHOUT OVERLAPS) );` |
| [without_overlaps:142](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L142) | 42804 / 42601 | `CREATE TABLE temporal_rng3 ( id int4range, valid_at TEXT, CONSTRAINT temporal_rng3_uq UNIQUE (id, valid_at WITHOUT OVERL ...` |
| [json:3](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L3) | 22P02 / 42601 | `SELECT $$''$$::json;` |
| [json:5](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L5) | 22P02 / 42601 | `SELECT '"abc'::json;` |
| [json:6](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L6) | 22P02 / 42601 | `SELECT '"abc def"'::json;` |
| [json:9](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L9) | 22P02 / 42601 | `SELECT '"\v"'::json;` |
| [json:27](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L27) | 22P02 / 42601 | `SELECT '01'::json;` |
| [json:32](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L32) | 22P02 / 42601 | `SELECT '1f2'::json;` |
| [json:33](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L33) | 22P02 / 42601 | `SELECT '0.x1'::json;` |
| [json:34](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L34) | 22P02 / 42601 | `SELECT '1.3ex100'::json;` |
| [json:40](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L40) | 22P02 / 42601 | `SELECT '[1,2,]'::json;` |
| [json:41](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L41) | 22P02 / 42601 | `SELECT '[1,2'::json;` |
| [json:42](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L42) | 22P02 / 42601 | `SELECT '[1,[2]'::json;` |
| [json:46](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L46) | 22P02 / 42601 | `SELECT '{"abc"}'::json;` |
| [json:48](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L48) | 22P02 / 42601 | `SELECT '{1:"abc"}'::json;` |
| [json:49](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L49) | 22P02 / 42601 | `SELECT '{"abc",1}'::json;` |
| [json:50](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L50) | 22P02 / 42601 | `SELECT '{"abc"=1}'::json;` |
| [json:51](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L51) | 22P02 / 42601 | `SELECT '{"abc"::1}'::json;` |
| [json:53](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L53) | 22P02 / 42601 | `SELECT '{"abc":1:2}'::json;` |
| [json:54](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L54) | 22P02 / 42601 | `SELECT '{"abc":1,3}'::json;` |
| [json:58](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L58) | 54001 / 42601 | `SELECT repeat('[', 10000)::json;` |
| [json:59](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L59) | 54001 / 42601 | `SELECT repeat('{"a":', 10000)::json;` |
| [json:67](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L67) | 22P02 / 42601 | `SELECT 'true false'::json;` |
| [json:68](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L68) | 22P02 / 42601 | `SELECT 'true, false'::json;` |
| [json:69](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L69) | 22P02 / 42601 | `SELECT 'truf'::json;` |
| [json:70](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L70) | 22P02 / 42601 | `SELECT 'trues'::json;` |
| [json:71](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L71) | 22P02 / 42601 | `SELECT ''::json;` |
| [json:72](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L72) | 22P02 / 42601 | `SELECT ' '::json;` |
| [json:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L80) | 22P02 / 42601 | `SELECT '{ "one": 1, "two":,"two", -- ERROR extraneous comma before field "two" "three": true}'::json;` |
| [json:85](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L85) | 22P02 / 42601 | `SELECT '{ "one": 1, "two":"two", "averyveryveryveryveryveryveryveryveryverylongfieldname":}'::json;` |
| [json:699](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L699) | 22023 / 42601 | `SELECT json_build_object(json '{"a":1,"b":2}', 3);` |
| [json:880](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L880) | 22023 / 42601 | `select json_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::json, '" ...` |
| [json:881](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L881) | 22023 / 42601 | `select json_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::json, '{ ...` |
| [json:883](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L883) | 22023 / 42601 | `select json_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::json, 'n ...` |
| [json:884](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L884) | 22023 / 42601 | `select json_to_tsvector('english', '{"a": "aaa in bbb", "b": 123, "c": 456, "d": true, "f": false, "g": null}'::json, '[ ...` |
| [jsonb:397](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L397) | 22023 / 42601 | `SELECT jsonb_build_object(json '{"a":1,"b":2}', 3);` |
| [json_encoding:18](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L18) | 22P02 / 42601 | `SELECT '"\u"'::json;` |
| [json_encoding:19](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L19) | 22P02 / 42601 | `SELECT '"\u00"'::json;` |
| [json_encoding:20](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L20) | 22P02 / 42601 | `SELECT '"\u000g"'::json;` |
| [json_encoding:27](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L27) | 22P02 / 42601 | `select json '{ "a": "\ud83d\ud83d" }' -> 'a';` |
| [json_encoding:28](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L28) | 22P02 / 42601 | `select json '{ "a": "\ude04\ud83d" }' -> 'a';` |
| [json_encoding:29](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L29) | 22P02 / 42601 | `select json '{ "a": "\ud83dX" }' -> 'a';` |
| [json_encoding:30](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L30) | 22P02 / 42601 | `select json '{ "a": "\ude04X" }' -> 'a';` |
| [json_encoding:43](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json_encoding.sql#L43) | 22P05 / 42601 | `select json '{ "a": "null \u0000 escape" }' ->> 'a' as fails;` |
| [temp:123](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/temp.sql#L123) | 42P16 / 42601 | `CREATE TABLE temptest(col) ON COMMIT DELETE ROWS AS SELECT 1;` |
| [rangefuncs:827](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangefuncs.sql#L827) | 42804 / 42601 | `select * from int8_tbl, coalesce(row(1)) as (a int, b int);` |
| [reloptions:125](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/reloptions.sql#L125) | 22023 / 42601 | `CREATE INDEX reloptions_test_idx ON reloptions_test (s) WITH (not_existing_ns.fillfactor=2);` |
| [hash_part:42](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/hash_part.sql#L42) | 22023 / 42601 | `SELECT satisfies_hash_partition('mchash'::regclass, 4, 0, NULL::int, NULL::text, NULL::json);` |

</details>

<details>
<summary>#176: 45 statements</summary>

A statement that PostgreSQL refuses for a missing privilege succeeds

| file and line | codes | statement |
| --- | --- | --- |
| [updatable_views:610](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L610) | 42501 / 00000 | `INSERT INTO base_tbl VALUES (3, 'Row 3', 3.0);` |
| [updatable_views:622](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L622) | 42501 / 00000 | `UPDATE base_tbl SET b=b;` |
| [updatable_views:637](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L637) | 42501 / 00000 | `DELETE FROM base_tbl;` |
| [updatable_views:666](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L666) | 42501 / 00000 | `INSERT INTO base_tbl VALUES (5, 'Row 5', 5.0);` |
| [updatable_views:669](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L669) | 42501 / 00000 | `DELETE FROM base_tbl WHERE a=3;` |
| [updatable_views:789](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L789) | 42501 / 00000 | `SELECT * FROM base_tbl;` |
| [updatable_views:791](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L791) | 42501 / 00000 | `INSERT INTO base_tbl VALUES (3, 'Row 3', 3.0);` |
| [updatable_views:793](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L793) | 42501 / 00000 | `UPDATE base_tbl SET a=a;` |
| [updatable_views:797](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L797) | 42501 / 00000 | `DELETE FROM base_tbl;` |
| [updatable_views:810](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L810) | 42501 / 00000 | `UPDATE base_tbl SET b=b;` |
| [privileges:274](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L274) | 42501 / 00000 | `SELECT * FROM atest1;` |
| [privileges:290](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L290) | 42501 / 00000 | `INSERT INTO atest1 VALUES (2, 'two');` |
| [privileges:292](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L292) | 42501 / 00000 | `INSERT INTO atest1 SELECT 1, b FROM atest1;` |
| [privileges:293](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L293) | 42501 / 00000 | `UPDATE atest1 SET a = 1 WHERE a = 2;` |
| [privileges:411](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L411) | 42501 / 00000 | `SELECT * FROM atest3;` |
| [privileges:424](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L424) | 42501 / 00000 | `DELETE FROM atest3;` |
| [privileges:429](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L429) | 42501 / 00000 | `DELETE FROM atest3;` |
| [privileges:456](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L456) | 42501 / 00000 | `select * from ((select a.q1 as x from int8_tbl a offset 0) union all (select b.q2 as x from int8_tbl b offset 0)) ss whe ...` |
| [privileges:499](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L499) | 42501 / 00000 | `SELECT * FROM atest5;` |
| [privileges:502](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L502) | 42501 / 00000 | `SELECT two FROM atest5;` |
| [privileges:508](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L508) | 42501 / 00000 | `SELECT 1 FROM atest5 a JOIN atest5 b USING (two);` |
| [privileges:519](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L519) | 42501 / 00000 | `SELECT * FROM (atest5 a JOIN atest5 b USING (one));` |
| [privileges:520](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L520) | 42501 / 00000 | `SELECT a.* FROM (atest5 a JOIN atest5 b USING (one));` |
| [privileges:530](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L530) | 42501 / 00000 | `SELECT 1 FROM atest5 WHERE two = 2;` |
| [privileges:531](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L531) | 42501 / 00000 | `SELECT * FROM atest1, atest5;` |
| [privileges:534](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L534) | 42501 / 00000 | `SELECT atest1.*,atest5.one FROM atest1 JOIN atest5 ON (atest1.a = atest5.two);` |
| [privileges:536](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L536) | 42501 / 00000 | `SELECT one, two FROM atest5;` |
| [privileges:557](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L557) | 42501 / 00000 | `INSERT INTO atest5 (three) VALUES (4);` |
| [privileges:558](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L558) | 42501 / 00000 | `INSERT INTO atest5 VALUES (5,5,5);` |
| [privileges:560](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L560) | 42501 / 00000 | `UPDATE atest5 SET one = 8;` |
| [privileges:561](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L561) | 42501 / 00000 | `UPDATE atest5 SET three = 5, one = 2;` |
| [privileges:566](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L566) | 42501 / 00000 | `INSERT INTO atest5(two) VALUES (6) ON CONFLICT (two) DO UPDATE set three = 10 RETURNING atest5.three;` |
| [privileges:573](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L573) | 42501 / 00000 | `INSERT INTO atest5(two) VALUES (6) ON CONFLICT (two) DO UPDATE set three = EXCLUDED.three;` |
| [privileges:574](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L574) | 42501 / 00000 | `INSERT INTO atest5(two) VALUES (6) ON CONFLICT (two) DO UPDATE set one = 8;` |
| [privileges:575](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L575) | 42501 / 00000 | `INSERT INTO atest5(three) VALUES (4) ON CONFLICT (two) DO UPDATE set three = 10;` |
| [privileges:578](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L578) | 42501 / 00000 | `INSERT INTO atest5(four) VALUES (4);` |
| [privileges:584](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L584) | 42501 / 00000 | `INSERT INTO atest5(four) VALUES (4) ON CONFLICT (four) DO UPDATE set three = 3;` |
| [privileges:600](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L600) | 42501 / 00000 | `SELECT one FROM atest5;` |
| [privileges:601](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L601) | 42501 / 00000 | `UPDATE atest5 SET one = 1;` |
| [privileges:800](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L800) | 42501 / 00000 | `SELECT * FROM atest6;` |
| [privileges:801](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L801) | 42501 / 00000 | `SELECT 1 FROM atest6;` |
| [privileges:804](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L804) | 42501 / 00000 | `DELETE FROM atest5 WHERE one = 1;` |
| [privileges:919](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L919) | 42501 / 00000 | `SELECT '{1}'::int4[]::int8[];` |
| [privileges:1583](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1583) | 42501 / 00000 | `DELETE FROM datdba_only;` |
| [privileges:1586](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L1586) | 42501 / 00000 | `INSERT INTO datdba_only DEFAULT VALUES;` |

</details>

<details>
<summary>#184: 31 statements</summary>

information_schema answers without the views and schemas that were refused

| file and line | codes | statement |
| --- | --- | --- |
| [updatable_views:36](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L36) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE E'r_\\_view%' ORDER BY table_ ...` |
| [updatable_views:46](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L46) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE E'r_\\_view%' ORDER B ...` |
| [updatable_views:160](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L160) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name = 'rw_view1';` |
| [updatable_views:168](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L168) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name = 'rw_view1' ORDER BY ordi ...` |
| [updatable_views:254](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L254) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name = 'rw_view2';` |
| [updatable_views:262](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L262) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name = 'rw_view2' ORDER BY ordi ...` |
| [updatable_views:304](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L304) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:314](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L314) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:322](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L322) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:332](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L332) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:340](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L340) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:350](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L350) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:358](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L358) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:368](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L368) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:401](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L401) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:413](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L413) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:441](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L441) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:453](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L453) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:461](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L461) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:473](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L473) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:481](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L481) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE 'rw_view%' ORDER BY table_nam ...` |
| [updatable_views:493](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L493) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE 'rw_view%' ORDER BY t ...` |
| [updatable_views:1158](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1158) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name LIKE E'r_\\_view%' ORDER BY table_ ...` |
| [updatable_views:1168](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1168) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name LIKE E'r_\\_view%' ORDER B ...` |
| [updatable_views:1529](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1529) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name = 'rw_view1';` |
| [updatable_views:1537](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1537) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name = 'rw_view1' ORDER BY ordi ...` |
| [updatable_views:1562](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1562) | 00000 / 00000 | `SELECT table_name, is_insertable_into FROM information_schema.tables WHERE table_name = 'rw_view2';` |
| [updatable_views:1570](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1570) | 00000 / 00000 | `SELECT table_name, column_name, is_updatable FROM information_schema.columns WHERE table_name = 'rw_view2' ORDER BY ordi ...` |
| [updatable_views:1836](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/updatable_views.sql#L1836) | 00000 / 00000 | `select table_name, column_name, is_updatable from information_schema.columns where table_name = 'uv_ptv' order by column ...` |
| [generated_stored:11](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L11) | 00000 / 00000 | `SELECT table_name, column_name, column_default, is_nullable, is_generated, generation_expression FROM information_schema ...` |
| [generated_virtual:11](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L11) | 00000 / 00000 | `SELECT table_name, column_name, column_default, is_nullable, is_generated, generation_expression FROM information_schema ...` |

</details>

<details>
<summary>#179: 24 statements</summary>

COMMIT succeeds where a check that PostgreSQL makes at COMMIT fails

| file and line | codes | statement |
| --- | --- | --- |
| [constraints:409](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L409) | 23505 / 00000 | `COMMIT;` |
| [constraints:444](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L444) | 23505 / 00000 | `COMMIT;` |
| [constraints:515](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L515) | 23505 / 00000 | `COMMIT;` |
| [constraints:599](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L599) | 23P01 / 00000 | `COMMIT;` |
| [constraints:603](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L603) | 23P01 / 00000 | `COMMIT;` |
| [triggers:2863](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/triggers.sql#L2863) | 22012 / 00000 | `commit;` |
| [without_overlaps:1247](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L1247) | 23503 / 00000 | `COMMIT;` |
| [without_overlaps:1321](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L1321) | 23503 / 00000 | `COMMIT;` |
| [without_overlaps:1371](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L1371) | 23503 / 00000 | `COMMIT;` |
| [without_overlaps:1674](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L1674) | 23503 / 00000 | `COMMIT;` |
| [without_overlaps:1748](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L1748) | 23503 / 00000 | `COMMIT;` |
| [without_overlaps:1793](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/without_overlaps.sql#L1793) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:849](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L849) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:900](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L900) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:1007](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1007) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:1023](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1023) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:1039](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1039) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:1056](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1056) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:1258](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L1258) | 23503 / 00000 | `commit;` |
| [foreign_key:2055](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2055) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:2271](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2271) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:2278](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2278) | 23503 / 00000 | `COMMIT;` |
| [foreign_key:2302](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/foreign_key.sql#L2302) | 23503 / 00000 | `COMMIT;` |
| [temp:139](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/temp.sql#L139) | 0A000 / 00000 | `COMMIT;` |

</details>

<details>
<summary>#201: 18 statements</summary>

A binary number that is out of range answers 22P02 instead of 22003

| file and line | codes | statement |
| --- | --- | --- |
| [int2:132](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int2.sql#L132) | 22003 / 22P02 | `SELECT int2 '0b1000000000000000';` |
| [int2:134](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int2.sql#L134) | 22003 / 22P02 | `SELECT int2 '0o100000';` |
| [int2:136](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int2.sql#L136) | 22003 / 22P02 | `SELECT int2 '0x8000';` |
| [int2:139](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int2.sql#L139) | 22003 / 22P02 | `SELECT int2 '-0b1000000000000001';` |
| [int2:141](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int2.sql#L141) | 22003 / 22P02 | `SELECT int2 '-0o100001';` |
| [int2:143](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int2.sql#L143) | 22003 / 22P02 | `SELECT int2 '-0x8001';` |
| [int4:196](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L196) | 22003 / 22P02 | `SELECT int4 '0b10000000000000000000000000000000';` |
| [int4:198](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L198) | 22003 / 22P02 | `SELECT int4 '0o20000000000';` |
| [int4:200](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L200) | 22003 / 22P02 | `SELECT int4 '0x80000000';` |
| [int4:203](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L203) | 22003 / 22P02 | `SELECT int4 '-0b10000000000000000000000000000001';` |
| [int4:205](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L205) | 22003 / 22P02 | `SELECT int4 '-0o20000000001';` |
| [int4:207](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int4.sql#L207) | 22003 / 22P02 | `SELECT int4 '-0x80000001';` |
| [int8:277](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L277) | 22003 / 22P02 | `SELECT int8 '0b1000000000000000000000000000000000000000000000000000000000000000';` |
| [int8:279](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L279) | 22003 / 22P02 | `SELECT int8 '0o1000000000000000000000';` |
| [int8:281](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L281) | 22003 / 22P02 | `SELECT int8 '0x8000000000000000';` |
| [int8:284](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L284) | 22003 / 22P02 | `SELECT int8 '-0b1000000000000000000000000000000000000000000000000000000000000001';` |
| [int8:286](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L286) | 22003 / 22P02 | `SELECT int8 '-0o1000000000000000000001';` |
| [int8:288](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L288) | 22003 / 22P02 | `SELECT int8 '-0x8000000000000001';` |

</details>

<details>
<summary>#185: 17 statements</summary>

The column of a cast of a cast is named after the inner type

| file and line | codes | statement |
| --- | --- | --- |
| [int8:182](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L182) | 00000 / 00000 | `SELECT CAST('42'::int2 AS int8), CAST('-37'::int2 AS int8);` |
| [int8:185](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/int8.sql#L185) | 00000 / 00000 | `SELECT CAST('36854775807.0'::float4 AS int8);` |
| [float4:104](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L104) | 00000 / 00000 | `SELECT '32767.4'::float4::int2;` |
| [float4:106](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L106) | 00000 / 00000 | `SELECT '-32768.4'::float4::int2;` |
| [float4:108](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L108) | 00000 / 00000 | `SELECT '2147483520'::float4::int4;` |
| [float4:110](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L110) | 00000 / 00000 | `SELECT '-2147483648.5'::float4::int4;` |
| [float4:112](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L112) | 00000 / 00000 | `SELECT '9223369837831520256'::float4::int8;` |
| [float4:114](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L114) | 00000 / 00000 | `SELECT '-9223372036854775808.5'::float4::int8;` |
| [float8:287](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L287) | 00000 / 00000 | `SELECT '32767.4'::float8::int2;` |
| [float8:289](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L289) | 00000 / 00000 | `SELECT '-32768.4'::float8::int2;` |
| [float8:291](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L291) | 00000 / 00000 | `SELECT '2147483647.4'::float8::int4;` |
| [float8:293](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L293) | 00000 / 00000 | `SELECT '-2147483648.4'::float8::int4;` |
| [float8:295](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L295) | 00000 / 00000 | `SELECT '9223372036854773760'::float8::int8;` |
| [float8:297](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L297) | 00000 / 00000 | `SELECT '-9223372036854775808.5'::float8::int8;` |
| [numeric:803](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L803) | 00000 / 00000 | `SELECT '42'::int2::numeric;` |
| [privileges:915](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L915) | 00000 / 00000 | `SELECT '{1}'::int4[]::int8[];` |
| [privileges:917](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L917) | 00000 / 00000 | `SELECT '{1}'::int4[]::int8[];` |

</details>

<details>
<summary>#177: 17 statements</summary>

An UPDATE or DELETE that a publication forbids succeeds

| file and line | codes | statement |
| --- | --- | --- |
| [publication:317](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L317) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:320](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L320) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:324](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L324) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_nopk SET a = 1;` |
| [publication:341](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L341) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:344](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L344) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:347](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L347) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_nopk SET a = 1;` |
| [publication:537](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L537) | 42P10 / 00000 | `UPDATE testpub_tbl6 SET a = 1;` |
| [publication:689](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L689) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:692](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L692) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:698](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L698) | 55000 / 00000 | `UPDATE rf_tbl_abcd_nopk SET a = 1;` |
| [publication:705](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L705) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:708](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L708) | 42P10 / 00000 | `UPDATE rf_tbl_abcd_nopk SET a = 1;` |
| [publication:716](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L716) | 55000 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:720](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L720) | 55000 / 00000 | `UPDATE rf_tbl_abcd_pk SET a = 1;` |
| [publication:724](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L724) | 55000 / 00000 | `UPDATE rf_tbl_abcd_nopk SET a = 1;` |
| [publication:800](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L800) | 55000 / 00000 | `UPDATE testpub_tbl4 set a = 3;` |
| [publication:1238](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/publication.sql#L1238) | 55000 / 00000 | `INSERT INTO testpub_insert_onconfl_no_ri VALUES (1, 1) ON CONFLICT (a) DO UPDATE SET b = 2;` |

</details>

<details>
<summary>#197: 12 statements</summary>

Values that PostgreSQL finds out of range are accepted

| file and line | codes | statement |
| --- | --- | --- |
| [float4:16](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L16) | 22003 / 00000 | `INSERT INTO FLOAT4_TBL(f1) VALUES ('10e-70');` |
| [float4:17](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L17) | 22003 / 00000 | `INSERT INTO FLOAT4_TBL(f1) VALUES ('-10e-70');` |
| [float4:21](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L21) | 22003 / 00000 | `INSERT INTO FLOAT4_TBL(f1) VALUES ('10e-70'::float8);` |
| [float4:22](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L22) | 22003 / 00000 | `INSERT INTO FLOAT4_TBL(f1) VALUES ('-10e-70'::float8);` |
| [float4:26](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L26) | 22003 / 00000 | `INSERT INTO FLOAT4_TBL(f1) VALUES ('10e-400');` |
| [float4:27](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float4.sql#L27) | 22003 / 00000 | `INSERT INTO FLOAT4_TBL(f1) VALUES ('-10e-400');` |
| [float8:21](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L21) | 22003 / 00000 | `SELECT '10e-400'::float8;` |
| [float8:22](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L22) | 22003 / 00000 | `SELECT '-10e-400'::float8;` |
| [float8:276](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L276) | 22003 / 00000 | `INSERT INTO FLOAT8_TBL(f1) VALUES ('10e-400');` |
| [float8:278](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L278) | 22003 / 00000 | `INSERT INTO FLOAT8_TBL(f1) VALUES ('-10e-400');` |
| [numeric:840](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L840) | 22003 / 00000 | `SELECT round(5.5e131071, -131072);` |
| [horology:156](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/horology.sql#L156) | 22008 / 00000 | `SELECT timestamp without time zone '2000-01-01' - interval '2483590 days' AS "out of range";` |

</details>

<details>
<summary>#182: 12 statements</summary>

A write into a table with a rule succeeds without the rule

| file and line | codes | statement |
| --- | --- | --- |
| [rules:359](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L359) | 00000 / 00000 | `insert into rtest_order1 values (1);` |
| [rules:379](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L379) | 00000 / 00000 | `insert into rtest_nothn2 values (10, 'too small');` |
| [rules:380](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L380) | 00000 / 00000 | `insert into rtest_nothn2 values (50, 'too small');` |
| [rules:381](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L381) | 00000 / 00000 | `insert into rtest_nothn2 values (100, 'OK');` |
| [rules:382](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L382) | 00000 / 00000 | `insert into rtest_nothn2 values (200, 'OK');` |
| [rules:414](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L414) | 00000 / 00000 | `insert into rtest_nothn2 select * from rtest_nothn4;` |
| [rules:799](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L799) | 00000 / 00000 | `INSERT INTO ruletest_tbl VALUES (99, 99);` |
| [rules:804](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L804) | 00000 / 00000 | `INSERT INTO ruletest_tbl VALUES (99, 99);` |
| [rules:982](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L982) | 00000 / 00000 | `update rules_base set f2 = f2 + 1;` |
| [rules:985](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L985) | 00000 / 00000 | `update rules_base set f2 = f2 + 1;` |
| [rules:1377](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L1377) | 00000 / 00000 | `INSERT INTO ruletest1 VALUES (1);` |
| [rules:1384](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rules.sql#L1384) | 00000 / 00000 | `INSERT INTO ruletest1 VALUES (4);` |

</details>

<details>
<summary>#204: 8 statements</summary>

Too few parameters in a Bind answer 42P02 instead of 08P01

| file and line | codes | statement |
| --- | --- | --- |
| [psql:94](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L94) | 08P01 / 42P02 | `SELECT $1, $2` |
| [psql_pipeline:213](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L213) | 08P01 / 42P02 | `SELECT $1` |
| [psql_pipeline:276](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L276) | 08P01 / 42P02 | `SELECT $1` |
| [psql_pipeline:305](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L305) | 08P01 / 42P02 | `SELECT $1` |
| [psql_pipeline:330](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L330) | 08P01 / 42P02 | `SELECT $1` |
| [psql_pipeline:345](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L345) | 08P01 / 42P02 | `SELECT $1` |
| [psql_pipeline:348](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L348) | 08P01 / 42P02 | `SELECT $1` |
| [psql_pipeline:493](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql_pipeline.sql#L493) | 08P01 / 42P02 | `SELECT $1` |

</details>

<details>
<summary>#187: 8 statements</summary>

sqrt of a numeric answers double precision

| file and line | codes | statement |
| --- | --- | --- |
| [numeric:1269](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1269) | 00000 / 00000 | `select sqrt(1.000000000000003::numeric);` |
| [numeric:1270](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1270) | 00000 / 00000 | `select sqrt(1.000000000000004::numeric);` |
| [numeric:1271](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1271) | 00000 / 00000 | `select sqrt(96627521408608.56340355805::numeric);` |
| [numeric:1272](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1272) | 00000 / 00000 | `select sqrt(96627521408608.56340355806::numeric);` |
| [numeric:1273](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1273) | 00000 / 00000 | `select sqrt(515549506212297735.073688290367::numeric);` |
| [numeric:1274](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1274) | 00000 / 00000 | `select sqrt(515549506212297735.073688290368::numeric);` |
| [numeric:1275](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1275) | 00000 / 00000 | `select sqrt(8015491789940783531003294973900306::numeric);` |
| [numeric:1276](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/numeric.sql#L1276) | 00000 / 00000 | `select sqrt(8015491789940783531003294973900307::numeric);` |

</details>

<details>
<summary>#203: 7 statements</summary>

A cast that the language lacks answers 42846

| file and line | codes | statement |
| --- | --- | --- |
| [strings:803](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L803) | 22003 / 42846 | `SELECT '\x123456'::bytea::int2;` |
| [strings:808](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L808) | 22003 / 42846 | `SELECT '\x123456789A'::bytea::int4;` |
| [strings:813](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L813) | 22003 / 42846 | `SELECT '\x112233445566778899'::bytea::int8;` |
| [jsonb:1586](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1586) | 22023 / 42846 | `select '[]'::jsonb::bool;` |
| [jsonb:1589](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1589) | 22023 / 42846 | `select '[1.0]'::jsonb::float;` |
| [jsonb:1592](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1592) | 22023 / 42846 | `select '[1.0]'::jsonb::float4;` |
| [jsonb:1604](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L1604) | 22023 / 42846 | `select '{}'::jsonb::numeric;` |

</details>

<details>
<summary>#193: 6 statements</summary>

A date with a two-digit year is read as a year of the first century

| file and line | codes | statement |
| --- | --- | --- |
| [date:70](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L70) | 00000 / 00000 | `SELECT date '99-01-08';` |
| [date:76](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L76) | 00000 / 00000 | `SELECT date '99-08-01';` |
| [date:121](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L121) | 22008 / 00000 | `SELECT date '99-01-08';` |
| [date:127](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L127) | 22008 / 00000 | `SELECT date '99-08-01';` |
| [date:172](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L172) | 22008 / 00000 | `SELECT date '99-01-08';` |
| [date:178](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L178) | 22008 / 00000 | `SELECT date '99-08-01';` |

</details>

<details>
<summary>#205: 6 statements</summary>

A reference to a whole row answers 42703 instead of 0A000

| file and line | codes | statement |
| --- | --- | --- |
| [privileges:504](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L504) | 42501 / 42703 | `SELECT atest5 FROM atest5;` |
| [privileges:785](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L785) | 42501 / 42703 | `SELECT atest6 FROM atest6;` |
| [json:697](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/json.sql#L697) | 22023 / 42703 | `SELECT json_build_object(r,2) FROM (SELECT 1 AS a, 2 AS b) r;` |
| [jsonb:395](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L395) | 22023 / 42703 | `SELECT jsonb_build_object(r,2) FROM (SELECT 1 AS a, 2 AS b) r;` |
| [rowtypes:398](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes.sql#L398) | 42883 / 42703 | `select text(fullname) from fullname;` |
| [rowtypes:551](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes.sql#L551) | 42P01 / 42703 | `SELECT d.a FROM (SELECT compositetable AS d FROM compositetable) s;` |

</details>

<details>
<summary>#188: 5 statements</summary>

power(1, NaN) and power(-1, infinity) answer NaN instead of 1

| file and line | codes | statement |
| --- | --- | --- |
| [float8:134](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L134) | 00000 / 00000 | `SELECT power(float8 '1', float8 'NaN');` |
| [float8:140](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L140) | 00000 / 00000 | `SELECT power(float8 '1', float8 'inf');` |
| [float8:141](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L141) | 00000 / 00000 | `SELECT power(float8 '1', float8 '-inf');` |
| [float8:142](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L142) | 00000 / 00000 | `SELECT power(float8 '-1', float8 'inf');` |
| [float8:143](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/float8.sql#L143) | 00000 / 00000 | `SELECT power(float8 '-1', float8 '-inf');` |

</details>

<details>
<summary>#206: 3 statements</summary>

EXTRACT of a time zone from a date answers 22023 instead of 0A000

| file and line | codes | statement |
| --- | --- | --- |
| [date:307](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L307) | 0A000 / 22023 | `SELECT EXTRACT(TIMEZONE FROM DATE '2020-08-11');` |
| [date:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L308) | 0A000 / 22023 | `SELECT EXTRACT(TIMEZONE_M FROM DATE '2020-08-11');` |
| [date:309](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L309) | 0A000 / 22023 | `SELECT EXTRACT(TIMEZONE_H FROM DATE '2020-08-11');` |

</details>

<details>
<summary>#183: 3 statements</summary>

information_schema does not report identity columns as PostgreSQL does

| file and line | codes | statement |
| --- | --- | --- |
| [identity:10](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/identity.sql#L10) | 00000 / 00000 | `SELECT table_name, column_name, column_default, is_nullable, is_identity, identity_generation, identity_start, identity_ ...` |
| [identity:253](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/identity.sql#L253) | 00000 / 00000 | `SELECT table_name, column_name, is_identity, identity_generation FROM information_schema.columns WHERE table_name = 'ite ...` |
| [identity:283](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/identity.sql#L283) | 00000 / 00000 | `SELECT table_name, column_name, is_nullable, is_identity, identity_generation FROM information_schema.columns WHERE tabl ...` |

</details>

<details>
<summary>#189: 3 statements</summary>

DISTINCT and GROUP BY keep two jsonb values that PostgreSQL treats as equal

| file and line | codes | statement |
| --- | --- | --- |
| [jsonb:931](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L931) | 00000 / 00000 | `SELECT count(distinct j) FROM testjsonb;` |
| [jsonb:933](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L933) | 00000 / 00000 | `SELECT count(*) FROM (SELECT j FROM (SELECT * FROM testjsonb UNION ALL SELECT * FROM testjsonb) js GROUP BY j) js2;` |
| [jsonb:936](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/jsonb.sql#L936) | 00000 / 00000 | `SELECT count(*) FROM (SELECT j FROM (SELECT * FROM testjsonb UNION ALL SELECT * FROM testjsonb) js GROUP BY j) js2;` |

</details>

<details>
<summary>#180: 2 statements</summary>

A copied unique key loses NULLS NOT DISTINCT

| file and line | codes | statement |
| --- | --- | --- |
| [constraints:331](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L331) | 23505 / 00000 | `INSERT INTO UNIQUE_TBL (t) VALUES ('seven');` |
| [constraints:332](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L332) | 00000 / 00000 | `INSERT INTO UNIQUE_TBL (t) VALUES ('eight') ON CONFLICT DO NOTHING;` |

</details>

<details>
<summary>#191: 2 statements</summary>

After a refused CREATE FUNCTION the built-in function of the same name answers

| file and line | codes | statement |
| --- | --- | --- |
| [polymorphism:682](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L682) | 00000 / 00000 | `select concat('%', 1, 2, 3, 4, 5);` |
| [polymorphism:683](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L683) | 00000 / 00000 | `select concat('\|', 'a'::text, 'b', 'c');` |

</details>

<details>
<summary>#178: 2 statements</summary>

A temporary table that PostgreSQL empties at COMMIT keeps its rows

| file and line | codes | statement |
| --- | --- | --- |
| [create_index:562](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_index.sql#L562) | 00000 / 00000 | `INSERT INTO concur_temp VALUES (1, 'foo'), (2, 'bar');` |
| [temp:66](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/temp.sql#L66) | 00000 / 00000 | `COMMIT;` |

</details>

<details>
<summary>#196: 1 statements</summary>

A constant division by zero in a branch that never runs succeeds

| file and line | codes | statement |
| --- | --- | --- |
| [case:72](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/case.sql#L72) | 22012 / 00000 | `SELECT CASE WHEN i > 100 THEN 1/0 ELSE 0 END FROM case_tbl;` |

</details>

<details>
<summary>#195: 1 statements</summary>

CREATE TABLE accepts two constraints of one name

| file and line | codes | statement |
| --- | --- | --- |
| [constraints:723](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/constraints.sql#L723) | 42710 / 00000 | `CREATE TABLE notnull_tbl2 (a INTEGER CONSTRAINT blah NOT NULL, b INTEGER CONSTRAINT blah NOT NULL);` |

</details>

<details>
<summary>#207: 1 statements</summary>

ON CONFLICT on a table named excluded answers 42702

| file and line | codes | statement |
| --- | --- | --- |
| [insert_conflict:372](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/insert_conflict.sql#L372) | 42P09 / 42702 | `insert into excluded values(1, '2') on conflict (key) do update set data = excluded.data RETURNING *;` |

</details>

<details>
<summary>#186: 1 statements</summary>

EXISTS over a table without columns is not named exists

| file and line | codes | statement |
| --- | --- | --- |
| [subselect:774](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/subselect.sql#L774) | 00000 / 00000 | `select exists(select * from nocolumns);` |

</details>

<details>
<summary>#190: 1 statements</summary>

LOCALTIMESTAMP is read from the clock for each call

| file and line | codes | statement |
| --- | --- | --- |
| [expressions:30](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/expressions.sql#L30) | 00000 / 00000 | `SELECT localtimestamp = localtimestamp(7);` |

</details>

<details>
<summary>#194: 1 statements</summary>

An outer aggregate over a nested WITH succeeds where PostgreSQL refuses it

| file and line | codes | statement |
| --- | --- | --- |
| [with:1107](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/with.sql#L1107) | 0A000 / 00000 | `select f1, (with cte1(x,y) as (select 1,2) select count((select i4.f1 from cte1))) as ss from int4_tbl i4;` |

</details>

<details>
<summary>#192: 1 statements</summary>

row_number over tied rows numbers them in another order

| file and line | codes | statement |
| --- | --- | --- |
| [window:1652](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/window.sql#L1652) | 00000 / 00000 | `SELECT * FROM (SELECT depname, empno, salary, enroll_date, row_number() OVER (PARTITION BY depname ORDER BY enroll_date) ...` |

</details>

## Why statements are refused

The messages are the language's own, with names, values, and numbers taken out.

### With `0A000`

| message | statements |
| --- | ---: |
| `DROP is not supported by the postgresql language` | 2,210 |
| `SET is not supported by the postgresql language` | 1,779 |
| `A subquery, a table function or LATERAL in FROM is not supported by the postgresql language` | 1,620 |
| `ALTER is not supported by the postgresql language` | 1,513 |
| `CREATE FUNCTION is not supported by the postgresql language` | 726 |
| `RESET is not supported by the postgresql language` | 667 |
| `A table option (INHERITS, PARTITION BY, USING, WITH, ON COMMIT or TABLESPACE) is not supported by the postgresql language` | 641 |
| `A temporary or unlogged table is not supported by the postgresql language` | 418 |
| `CREATE VIEW is not supported by the postgresql language` | 332 |
| `This column option (CHECK, GENERATED, COLLATE, storage, a deferrable or unenforced constraint and the like) is not supported by the postgresql language` | 325 |
| `GRANT is not supported by the postgresql language` | 322 |
| `CREATE OR is not supported by the postgresql language` | 274 |
| `FETCH is not supported by the postgresql language` | 266 |
| `CREATE TYPE is not supported by the postgresql language` | 264 |
| `The type jsonpath is not supported by the postgresql language` | 253 |
| `EXECUTE is not supported by the postgresql language` | 230 |
| `The type timestamptz is not supported by the postgresql language` | 213 |
| `This form of CREATE TABLE (OF, PARTITION OF, AS or a table option) is not supported by the postgresql language` | 194 |
| `COPY is not supported by the postgresql language` | 191 |
| `CREATE DOMAIN is not supported by the postgresql language` | 169 |
| `CREATE ROLE is not supported by the postgresql language` | 168 |
| `MERGE is not supported by the postgresql language` | 163 |
| `ROW is not supported by the postgresql language` | 146 |
| `ANALYZE is not supported by the postgresql language` | 145 |
| `The operator @@ is not supported by the postgresql language` | 145 |
| every other one | 7,333 |
| all | 20,707 |

### With another code

| message | statements |
| --- | ---: |
| `function does not exist` | 3,110 |
| `relation "..." does not exist` | 454 |
| `syntax error at or near "..."` | 180 |
| `invalid input syntax for type date: "..."` | 138 |
| `invalid input syntax for type timestamp: "..."` | 49 |
| `operator does not exist` | 46 |
| `schema "..." does not exist` | 41 |
| `column "..." does not exist` | 37 |
| `column "..." must appear in the GROUP BY clause or be used in an aggregate function` | 26 |
| `invalid input syntax for type integer: "..."` | 20 |
| `invalid input syntax for type bigint: "..."` | 14 |
| `invalid input syntax for type smallint: "..."` | 14 |
| `invalid input syntax for type interval: "..."` | 8 |
| `date/time field value out of range: "..."` | 5 |
| `cannot cast type bytea to bigint` | 4 |
| `cannot cast type bytea to integer` | 4 |
| `cannot cast type bytea to smallint` | 4 |
| `syntax error at or near "..."x"..."y"..."` | 4 |
| `value out of range: underflow` | 3 |
| `division by zero` | 2 |
| every other one | 23 |
| all | 4,186 |

The functions that are missing most often, among the statements refused with another code:

| function | statements |
| --- | ---: |
| `jsonb_path_query` | 300 |
| `pg_input_is_valid` | 128 |
| `pg_get_viewdef` | 81 |
| `to_timestamp` | 81 |
| `to_tsquery` | 76 |
| `ts_lexize` | 73 |
| `websearch_to_tsquery` | 67 |
| `jsonb_path_query_tz` | 64 |
| `to_char` | 59 |
| `numrange` | 54 |
| `nextval` | 49 |
| `generate_series` | 40 |
| `has_table_privilege` | 39 |
| `regexp_matches` | 37 |
| `has_largeobject_privilege` | 35 |
| `int4range` | 31 |
| `format` | 30 |
| `explain_filter` | 29 |
| `to_number` | 28 |
| `setval` | 27 |
| `to_tsvector` | 27 |
| `dfunc` | 26 |
| `has_function_privilege` | 26 |
| `pg_stat_force_next_flush` | 22 |
| `to_date` | 22 |
| every other one | 1,659 |
| all | 3,110 |

## Why statements are blocked

A statement that uses several tables of this kind is counted under the first one. 660 statements are blocked first by a column of type `name`, and 457 by a column of type `char(n)`; whether the language gets the two types is [open](https://github.com/humemai/arcadedb/issues/119).

| reason | statements |
| --- | ---: |
| `inheritance and partitions are not copied` | 4,694 |
| `only tables of public and temporary tables are copied` | 1,934 |
| `the copy differs from the table in information_schema` | 1,408 |
| `a column of type name cannot be held` | 660 |
| `for a write: the copy does not carry a default, identity or generated column` | 649 |
| `a column of type int4range cannot be held` | 605 |
| `a column of type character(n) cannot be held` | 457 |
| `a value of type numeric cannot be held` | 344 |
| `a column of type point cannot be held` | 234 |
| `a column of type box cannot be held` | 224 |
| `for a write: the copy does not carry a trigger` | 201 |
| `for a write: the copy does not carry a unique index` | 186 |
| `a column of type tsvector cannot be held` | 183 |
| `for a write: the copy does not carry a foreign key that refers to it` | 173 |
| `for a write: the copy does not carry a check constraint` | 125 |
| `a column of type timestamp(n) with time zone cannot be held` | 112 |
| `a value of type timestamp cannot be held` | 107 |
| `a column of type oid cannot be held` | 106 |
| `a value of type _int4 cannot be held` | 101 |
| `a column of type int4multirange cannot be held` | 83 |
| every other one | 1,942 |
| all | 14,528 |

## Out of scope

The [list](../e2e/src/test/resources/pgregress/out-of-scope.tsv) is closed, and a change to it goes through a pull request.

| reason | statements |
| --- | ---: |
| PostgreSQL answers differently in two runs | 171 |
| PostgreSQL internals | 3,750 |
| server administration | 678 |
| all | 4,599 |

| entry of the list | reason | statements |
| --- | --- | ---: |
| `explain` | PostgreSQL internals | 2,202 |
| `catalog-relation` | PostgreSQL internals | 1,194 |
| `publication` | server administration | 434 |
| `system-column` | PostgreSQL internals | 253 |
| `two-runs` | PostgreSQL answers differently in two runs | 162 |
| `c-function-call` | server administration | 113 |
| `catalog-qualified` | PostgreSQL internals | 101 |
| `c-function` | server administration | 83 |
| `tablespace` | server administration | 16 |
| `language-handler` | server administration | 14 |
| `access-method` | server administration | 13 |
| `tied-order` | PostgreSQL answers differently in two runs | 9 |
| `extension` | server administration | 2 |
| `load` | server administration | 2 |
| `replication-function` | server administration | 1 |
| all | | 4,599 |

## What the missing C library changes

`pg_regress` builds a library of C functions, `regress.so`, for the suite. The harness does not. A statement that defines a function of that code, or calls one, is out of scope. What else changes is that PostgreSQL itself fails where the suite expects success, when a type or another object needs such a function; both sides are then compared with that error. The table has every file that defines or calls such a function. Compare the two last columns: they are not the same measure, because the suite runs all files in one database with its own settings.

| file | statements | defines C code | calls C code | wrong | errors of PostgreSQL | `ERROR` lines the suite expects |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `test_setup` | 71 | 4 | 0 | 0 | 4 | 0 |
| `float4` | 100 | 2 | 0 | 12 | 30 | 30 |
| `float8` | 184 | 2 | 0 | 29 | 45 | 45 |
| `type_sanity` | 63 | 1 | 1 | 0 | 2 | 0 |
| `opr_sanity` | 131 | 0 | 14 | 0 | 14 | 0 |
| `expressions` | 79 | 3 | 1 | 1 | 1 | 1 |
| `encoding` | 75 | 6 | 17 | 0 | 32 | 18 |
| `create_function_c` | 5 | 3 | 0 | 0 | 5 | 3 |
| `create_type` | 95 | 24 | 3 | 6 | 46 | 27 |
| `create_view` | 310 | 1 | 1 | 28 | 21 | 19 |
| `create_cast` | 24 | 2 | 0 | 0 | 3 | 3 |
| `triggers` | 1,073 | 1 | 2 | 1 | 75 | 72 |
| `privileges` | 1,402 | 1 | 1 | 64 | 244 | 244 |
| `lock` | 131 | 1 | 1 | 2 | 6 | 4 |
| `alter_generic` | 333 | 1 | 2 | 2 | 111 | 108 |
| `alter_operator` | 70 | 1 | 1 | 0 | 12 | 12 |
| `misc` | 61 | 2 | 3 | 0 | 5 | 0 |
| `misc_functions` | 150 | 6 | 32 | 7 | 47 | 12 |
| `psql` | 480 | 1 | 0 | 5 | 54 | 31 |
| `foreign_data` | 539 | 1 | 1 | 2 | 131 | 126 |
| `window` | 400 | 1 | 2 | 2 | 40 | 40 |
| `indirect_toast` | 28 | 1 | 1 | 0 | 7 | 0 |
| `equivclass` | 96 | 11 | 2 | 0 | 0 | 0 |
| `sqljson` | 233 | 2 | 0 | 0 | 55 | 55 |
| `conversion` | 65 | 2 | 1 | 0 | 38 | 3 |
| `rowtypes` | 294 | 0 | 5 | 2 | 37 | 32 |
| `rowtypes_alter` | 495 | 0 | 2 | 7 | 84 | 83 |
| `attribute_version` | 107 | 1 | 6 | 0 | 10 | 11 |
| `compression_pglz` | 18 | 2 | 14 | 0 | 18 | 11 |
| these files | 7,112 | 83 | 113 | 170 | 1,177 | 990 |

What differs in four of them, read from the statements on which PostgreSQL fails:

- `create_type`: the input and output functions of its new types are C code, so the types are not completed, and statements about them fail on PostgreSQL, many with `42704` (undefined object).
- `conversion`: `test_enc_setup` and `test_enc_conversion` are C code, and the calls of `test_conv`, which the file tests the conversions with, fail on PostgreSQL with `42883` (undefined function).
- `misc_functions`: the functions of the library are not made, so calls of them fail with `42883`.
- `psql`: it defines one such function. Part of its difference has another cause: it reads back files that `psql` would have written, and the harness writes none (`58P01`).

## By file

| file | statements | passed | refused, `0A000` | refused, other code | wrong | blocked | out of scope |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `test_setup` | 71 | 10 | 24 | 0 | 0 | 32 | 5 |
| `boolean` | 98 | 80 | 14 | 4 | 0 | 0 | 0 |
| `char` | 32 | 0 | 11 | 2 | 0 | 19 | 0 |
| `name` | 40 | 0 | 7 | 2 | 12 | 19 | 0 |
| `varchar` | 22 | 0 | 1 | 2 | 0 | 19 | 0 |
| `text` | 74 | 14 | 15 | 35 | 10 | 0 | 0 |
| `int2` | 76 | 45 | 7 | 18 | 6 | 0 | 0 |
| `int4` | 101 | 62 | 3 | 22 | 14 | 0 | 0 |
| `int8` | 174 | 107 | 9 | 45 | 12 | 0 | 1 |
| `oid` | 37 | 0 | 5 | 6 | 0 | 26 | 0 |
| `float4` | 100 | 53 | 12 | 21 | 12 | 0 | 2 |
| `float8` | 184 | 84 | 25 | 43 | 29 | 1 | 2 |
| `bit` | 132 | 0 | 76 | 5 | 0 | 51 | 0 |
| `numeric` | 1,057 | 108 | 132 | 145 | 66 | 606 | 0 |
| `txid` | 47 | 10 | 12 | 8 | 0 | 8 | 9 |
| `uuid` | 68 | 0 | 5 | 11 | 4 | 28 | 20 |
| `enum` | 170 | 10 | 112 | 3 | 0 | 35 | 10 |
| `money` | 113 | 0 | 56 | 2 | 0 | 55 | 0 |
| `rangetypes` | 419 | 6 | 162 | 48 | 3 | 184 | 16 |
| `pg_lsn` | 31 | 2 | 18 | 1 | 0 | 9 | 1 |
| `regproc` | 105 | 9 | 18 | 65 | 13 | 0 | 0 |
| `strings` | 505 | 148 | 85 | 209 | 44 | 8 | 11 |
| `md5` | 14 | 0 | 0 | 14 | 0 | 0 | 0 |
| `numerology` | 89 | 72 | 8 | 9 | 0 | 0 | 0 |
| `point` | 43 | 0 | 12 | 1 | 0 | 30 | 0 |
| `lseg` | 16 | 0 | 2 | 1 | 0 | 13 | 0 |
| `line` | 35 | 0 | 7 | 5 | 0 | 23 | 0 |
| `box` | 101 | 2 | 19 | 2 | 0 | 64 | 14 |
| `path` | 23 | 0 | 3 | 2 | 0 | 18 | 0 |
| `polygon` | 62 | 1 | 15 | 2 | 0 | 31 | 13 |
| `circle` | 22 | 0 | 2 | 0 | 0 | 20 | 0 |
| `date` | 271 | 75 | 7 | 141 | 42 | 6 | 0 |
| `time` | 44 | 2 | 22 | 3 | 0 | 17 | 0 |
| `timetz` | 57 | 3 | 26 | 4 | 0 | 24 | 0 |
| `timestamp` | 177 | 7 | 9 | 45 | 20 | 93 | 3 |
| `timestamptz` | 404 | 11 | 220 | 44 | 5 | 120 | 4 |
| `interval` | 446 | 42 | 252 | 32 | 65 | 54 | 1 |
| `inet` | 116 | 0 | 29 | 3 | 0 | 78 | 6 |
| `macaddr` | 35 | 0 | 3 | 2 | 0 | 30 | 0 |
| `macaddr8` | 71 | 0 | 24 | 2 | 0 | 45 | 0 |
| `multirangetypes` | 622 | 5 | 388 | 58 | 2 | 169 | 0 |
| `geometry` | 162 | 0 | 3 | 2 | 0 | 155 | 2 |
| `horology` | 399 | 20 | 185 | 122 | 41 | 27 | 4 |
| `tstypes` | 238 | 1 | 227 | 8 | 2 | 0 | 0 |
| `regex` | 105 | 0 | 52 | 43 | 1 | 0 | 9 |
| `type_sanity` | 63 | 0 | 0 | 0 | 0 | 0 | 63 |
| `opr_sanity` | 131 | 0 | 0 | 0 | 0 | 0 | 131 |
| `misc_sanity` | 5 | 0 | 0 | 0 | 0 | 0 | 5 |
| `comments` | 6 | 6 | 0 | 0 | 0 | 0 | 0 |
| `expressions` | 79 | 6 | 32 | 17 | 1 | 15 | 8 |
| `unicode` | 29 | 0 | 25 | 2 | 2 | 0 | 0 |
| `xid` | 84 | 10 | 39 | 13 | 0 | 13 | 9 |
| `mvcc` | 11 | 3 | 6 | 2 | 0 | 0 | 0 |
| `database` | 27 | 2 | 23 | 0 | 0 | 0 | 2 |
| `stats_import` | 179 | 4 | 18 | 17 | 2 | 16 | 122 |
| `encoding` | 75 | 29 | 17 | 6 | 0 | 0 | 23 |
| `euc_kr` | 2 | 0 | 0 | 2 | 0 | 0 | 0 |
| `copy` | 107 | 19 | 50 | 0 | 0 | 34 | 4 |
| `copyselect` | 40 | 8 | 20 | 0 | 0 | 12 | 0 |
| `copydml` | 59 | 16 | 3 | 0 | 0 | 40 | 0 |
| `copyencoding` | 17 | 1 | 15 | 1 | 0 | 0 | 0 |
| `insert` | 390 | 27 | 67 | 0 | 0 | 281 | 15 |
| `insert_conflict` | 261 | 38 | 39 | 2 | 1 | 155 | 26 |
| `create_function_c` | 5 | 1 | 0 | 0 | 0 | 0 | 4 |
| `create_misc` | 88 | 0 | 1 | 0 | 0 | 86 | 1 |
| `create_operator` | 115 | 40 | 72 | 1 | 0 | 0 | 2 |
| `create_procedure` | 107 | 9 | 97 | 0 | 1 | 0 | 0 |
| `create_table` | 335 | 23 | 116 | 1 | 0 | 179 | 16 |
| `create_type` | 95 | 2 | 52 | 0 | 6 | 3 | 32 |
| `create_schema` | 27 | 0 | 24 | 0 | 0 | 3 | 0 |
| `create_index` | 696 | 105 | 230 | 2 | 1 | 253 | 105 |
| `create_index_spgist` | 202 | 29 | 31 | 2 | 0 | 60 | 80 |
| `create_view` | 310 | 5 | 126 | 78 | 28 | 54 | 19 |
| `index_including` | 135 | 5 | 29 | 0 | 0 | 72 | 29 |
| `index_including_gist` | 50 | 0 | 12 | 0 | 0 | 29 | 9 |
| `create_aggregate` | 59 | 0 | 56 | 0 | 0 | 0 | 3 |
| `create_function_sql` | 181 | 0 | 129 | 20 | 5 | 14 | 13 |
| `create_cast` | 24 | 2 | 18 | 1 | 0 | 0 | 3 |
| `constraints` | 568 | 134 | 175 | 3 | 8 | 236 | 12 |
| `triggers` | 1,073 | 112 | 265 | 7 | 1 | 655 | 33 |
| `select` | 87 | 24 | 20 | 5 | 0 | 27 | 11 |
| `inherit` | 884 | 60 | 171 | 0 | 0 | 502 | 151 |
| `typed_table` | 31 | 3 | 26 | 1 | 0 | 1 | 0 |
| `vacuum` | 328 | 65 | 115 | 14 | 0 | 109 | 25 |
| `drop_if_exists` | 161 | 2 | 151 | 0 | 0 | 2 | 6 |
| `updatable_views` | 1,185 | 147 | 475 | 187 | 188 | 143 | 45 |
| `roleattributes` | 80 | 0 | 48 | 0 | 0 | 0 | 32 |
| `create_am` | 142 | 19 | 59 | 1 | 0 | 26 | 37 |
| `hash_func` | 43 | 0 | 37 | 5 | 0 | 0 | 1 |
| `errors` | 87 | 27 | 47 | 0 | 0 | 5 | 8 |
| `infinite_recurse` | 3 | 0 | 2 | 0 | 1 | 0 | 0 |
| `sanity_check` | 3 | 0 | 1 | 0 | 0 | 0 | 2 |
| `select_into` | 67 | 9 | 41 | 1 | 0 | 6 | 10 |
| `select_distinct` | 101 | 24 | 52 | 0 | 0 | 10 | 15 |
| `select_distinct_on` | 23 | 2 | 10 | 0 | 0 | 4 | 7 |
| `select_implicit` | 44 | 2 | 3 | 0 | 0 | 39 | 0 |
| `select_having` | 23 | 0 | 1 | 0 | 0 | 22 | 0 |
| `subselect` | 361 | 102 | 89 | 17 | 1 | 49 | 103 |
| `union` | 198 | 64 | 71 | 3 | 0 | 16 | 44 |
| `case` | 64 | 42 | 13 | 5 | 1 | 0 | 3 |
| `join` | 1,022 | 251 | 325 | 4 | 0 | 107 | 335 |
| `aggregates` | 559 | 41 | 285 | 59 | 1 | 108 | 65 |
| `transactions` | 490 | 235 | 243 | 5 | 1 | 2 | 4 |
| `random` | 48 | 0 | 36 | 9 | 3 | 0 | 0 |
| `portals` | 354 | 100 | 167 | 1 | 0 | 69 | 17 |
| `arrays` | 515 | 50 | 198 | 109 | 12 | 144 | 2 |
| `btree_index` | 129 | 24 | 50 | 0 | 0 | 19 | 36 |
| `hash_index` | 100 | 34 | 52 | 0 | 0 | 13 | 1 |
| `update` | 286 | 32 | 53 | 0 | 1 | 168 | 32 |
| `delete` | 10 | 2 | 1 | 0 | 0 | 7 | 0 |
| `namespace` | 42 | 5 | 18 | 0 | 1 | 14 | 4 |
| `prepared_xacts` | 1 | 0 | 0 | 1 | 0 | 0 | 0 |
| `brin` | 77 | 10 | 32 | 11 | 5 | 16 | 3 |
| `gin` | 59 | 2 | 47 | 3 | 0 | 0 | 7 |
| `gist` | 84 | 2 | 23 | 0 | 0 | 46 | 13 |
| `spgist` | 41 | 1 | 13 | 0 | 0 | 26 | 1 |
| `privileges` | 1,402 | 133 | 863 | 158 | 64 | 122 | 62 |
| `init_privs` | 4 | 0 | 0 | 0 | 0 | 0 | 4 |
| `security_label` | 28 | 2 | 26 | 0 | 0 | 0 | 0 |
| `collate` | 146 | 1 | 64 | 2 | 4 | 70 | 5 |
| `matview` | 179 | 16 | 104 | 34 | 2 | 4 | 19 |
| `lock` | 131 | 38 | 76 | 0 | 2 | 5 | 10 |
| `replica_identity` | 76 | 9 | 18 | 0 | 0 | 37 | 12 |
| `rowsecurity` | 1,242 | 11 | 683 | 5 | 64 | 354 | 125 |
| `object_address` | 82 | 0 | 40 | 0 | 30 | 6 | 6 |
| `tablesample` | 58 | 5 | 36 | 0 | 1 | 10 | 6 |
| `groupingsets` | 217 | 6 | 131 | 0 | 0 | 28 | 52 |
| `drop_operator` | 12 | 0 | 8 | 0 | 0 | 0 | 4 |
| `password` | 53 | 0 | 47 | 0 | 0 | 0 | 6 |
| `identity` | 271 | 33 | 69 | 6 | 4 | 146 | 13 |
| `generated_stored` | 440 | 16 | 156 | 2 | 25 | 223 | 18 |
| `join_hash` | 279 | 44 | 204 | 0 | 0 | 1 | 30 |
| `brin_bloom` | 40 | 1 | 12 | 9 | 5 | 11 | 2 |
| `brin_multi` | 179 | 48 | 50 | 11 | 5 | 54 | 11 |
| `create_table_like` | 183 | 33 | 96 | 0 | 0 | 43 | 11 |
| `alter_generic` | 333 | 14 | 288 | 0 | 2 | 1 | 28 |
| `alter_operator` | 70 | 0 | 54 | 0 | 0 | 0 | 16 |
| `misc` | 61 | 7 | 14 | 11 | 0 | 24 | 5 |
| `async` | 11 | 0 | 4 | 4 | 3 | 0 | 0 |
| `dbsize` | 25 | 0 | 0 | 9 | 16 | 0 | 0 |
| `merge` | 572 | 251 | 163 | 9 | 0 | 132 | 17 |
| `misc_functions` | 150 | 3 | 49 | 45 | 7 | 0 | 46 |
| `sysviews` | 29 | 2 | 4 | 0 | 0 | 0 | 23 |
| `tsrf` | 74 | 6 | 23 | 30 | 8 | 0 | 7 |
| `tid` | 41 | 8 | 26 | 2 | 0 | 1 | 4 |
| `tidscan` | 49 | 8 | 13 | 0 | 0 | 0 | 28 |
| `tidrangescan` | 55 | 5 | 17 | 0 | 0 | 0 | 33 |
| `collate.utf8` | 58 | 0 | 55 | 3 | 0 | 0 | 0 |
| `collate.icu.utf8` | 703 | 14 | 291 | 5 | 14 | 313 | 66 |
| `incremental_sort` | 120 | 29 | 47 | 6 | 0 | 1 | 37 |
| `create_role` | 146 | 4 | 141 | 0 | 0 | 0 | 1 |
| `without_overlaps` | 658 | 24 | 21 | 25 | 10 | 564 | 14 |
| `generated_virtual` | 466 | 28 | 159 | 1 | 28 | 231 | 19 |
| `rules` | 619 | 169 | 133 | 73 | 14 | 221 | 9 |
| `psql` | 480 | 184 | 251 | 9 | 5 | 26 | 5 |
| `psql_crosstab` | 30 | 19 | 9 | 2 | 0 | 0 | 0 |
| `psql_pipeline` | 154 | 116 | 19 | 0 | 7 | 12 | 0 |
| `amutils` | 10 | 0 | 7 | 0 | 0 | 0 | 3 |
| `stats_ext` | 876 | 26 | 694 | 4 | 0 | 119 | 33 |
| `collate.linux.utf8` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `collate.windows.win1252` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `select_parallel` | 250 | 8 | 123 | 9 | 2 | 58 | 50 |
| `write_parallel` | 22 | 2 | 12 | 0 | 0 | 4 | 4 |
| `vacuum_parallel` | 14 | 3 | 9 | 0 | 0 | 0 | 2 |
| `maintain_every` | 16 | 0 | 11 | 0 | 0 | 3 | 2 |
| `publication` | 710 | 38 | 200 | 8 | 17 | 125 | 322 |
| `subscription` | 172 | 14 | 37 | 0 | 1 | 0 | 120 |
| `select_views` | 52 | 1 | 25 | 4 | 0 | 14 | 8 |
| `portals_p2` | 41 | 2 | 26 | 0 | 0 | 13 | 0 |
| `foreign_key` | 1,313 | 145 | 263 | 2 | 20 | 838 | 45 |
| `portals_inflight` | 35 | 12 | 13 | 0 | 2 | 6 | 2 |
| `cluster` | 218 | 61 | 56 | 0 | 0 | 84 | 17 |
| `dependency` | 62 | 2 | 55 | 0 | 0 | 3 | 2 |
| `guc` | 223 | 17 | 171 | 19 | 5 | 0 | 11 |
| `bitmapops` | 12 | 5 | 7 | 0 | 0 | 0 | 0 |
| `combocid` | 62 | 39 | 10 | 0 | 0 | 0 | 13 |
| `tsearch` | 446 | 1 | 57 | 168 | 3 | 202 | 15 |
| `tsdicts` | 131 | 0 | 36 | 92 | 0 | 0 | 3 |
| `foreign_data` | 539 | 8 | 414 | 20 | 2 | 76 | 19 |
| `window` | 400 | 24 | 151 | 13 | 2 | 158 | 52 |
| `xmlmap` | 40 | 0 | 10 | 25 | 0 | 5 | 0 |
| `functional_deps` | 40 | 8 | 25 | 5 | 0 | 2 | 0 |
| `advisory_lock` | 38 | 8 | 0 | 14 | 0 | 0 | 16 |
| `indirect_toast` | 28 | 5 | 7 | 8 | 0 | 6 | 2 |
| `equivclass` | 96 | 3 | 46 | 0 | 0 | 10 | 37 |
| `alter_column_type` | 215 | 52 | 131 | 4 | 0 | 24 | 4 |
| `json` | 470 | 10 | 213 | 178 | 42 | 25 | 2 |
| `jsonb` | 1,098 | 90 | 684 | 217 | 40 | 62 | 5 |
| `json_encoding` | 44 | 9 | 13 | 14 | 8 | 0 | 0 |
| `jsonpath` | 224 | 1 | 223 | 0 | 0 | 0 | 0 |
| `jsonpath_encoding` | 32 | 0 | 30 | 2 | 0 | 0 | 0 |
| `jsonb_jsonpath` | 836 | 4 | 218 | 381 | 233 | 0 | 0 |
| `sqljson` | 233 | 8 | 197 | 1 | 0 | 0 | 27 |
| `sqljson_queryfuncs` | 356 | 5 | 339 | 2 | 0 | 9 | 1 |
| `sqljson_jsontable` | 133 | 4 | 120 | 1 | 0 | 1 | 7 |
| `plancache` | 97 | 4 | 58 | 8 | 0 | 17 | 10 |
| `limit` | 80 | 2 | 39 | 5 | 0 | 26 | 8 |
| `plpgsql` | 941 | 33 | 475 | 99 | 45 | 283 | 6 |
| `copy2` | 284 | 80 | 120 | 1 | 0 | 83 | 0 |
| `temp` | 207 | 102 | 63 | 10 | 5 | 22 | 5 |
| `domain` | 482 | 15 | 243 | 20 | 15 | 183 | 6 |
| `rangefuncs` | 428 | 24 | 294 | 72 | 4 | 10 | 24 |
| `prepare` | 33 | 0 | 18 | 0 | 0 | 7 | 8 |
| `conversion` | 65 | 16 | 46 | 0 | 0 | 0 | 3 |
| `truncate` | 193 | 39 | 23 | 0 | 1 | 130 | 0 |
| `alter_table` | 1,778 | 311 | 607 | 33 | 0 | 757 | 70 |
| `sequence` | 261 | 28 | 138 | 64 | 12 | 17 | 2 |
| `polymorphism` | 423 | 17 | 303 | 86 | 13 | 0 | 4 |
| `rowtypes` | 294 | 22 | 172 | 17 | 2 | 61 | 20 |
| `returning` | 149 | 21 | 29 | 7 | 2 | 66 | 24 |
| `largeobject` | 127 | 31 | 24 | 2 | 9 | 52 | 9 |
| `with` | 307 | 66 | 162 | 15 | 1 | 47 | 16 |
| `xml` | 272 | 3 | 158 | 45 | 5 | 53 | 8 |
| `rowtypes_alter` | 495 | 58 | 386 | 3 | 7 | 35 | 6 |
| `partition_join` | 640 | 0 | 61 | 0 | 0 | 441 | 138 |
| `partition_prune` | 853 | 28 | 149 | 12 | 0 | 361 | 303 |
| `reloptions` | 66 | 3 | 41 | 2 | 1 | 0 | 19 |
| `hash_part` | 29 | 0 | 21 | 0 | 2 | 6 | 0 |
| `indexing` | 615 | 41 | 175 | 0 | 1 | 342 | 56 |
| `partition_aggregate` | 137 | 1 | 23 | 0 | 0 | 72 | 41 |
| `partition_info` | 73 | 1 | 34 | 15 | 0 | 15 | 8 |
| `tuplesort` | 108 | 20 | 45 | 0 | 0 | 27 | 16 |
| `explain` | 60 | 4 | 21 | 29 | 1 | 5 | 0 |
| `memoize` | 87 | 6 | 35 | 13 | 0 | 26 | 7 |
| `stats` | 475 | 92 | 125 | 70 | 2 | 34 | 152 |
| `predicate` | 42 | 10 | 6 | 0 | 0 | 4 | 22 |
| `numa` | 2 | 0 | 0 | 1 | 0 | 0 | 1 |
| `attribute_version` | 107 | 8 | 64 | 2 | 0 | 25 | 8 |
| `compression` | 87 | 20 | 34 | 13 | 0 | 20 | 0 |
| `compression_pglz` | 18 | 0 | 2 | 0 | 0 | 0 | 16 |
| `oidjoins` | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| `event_trigger` | 230 | 23 | 158 | 9 | 2 | 35 | 3 |
| `event_trigger_login` | 11 | 0 | 7 | 0 | 0 | 1 | 3 |
| `fast_default` | 275 | 4 | 56 | 7 | 7 | 173 | 28 |
| `tablespace` | 204 | 15 | 68 | 3 | 0 | 76 | 42 |
| `numeric_big` | 552 | 12 | 27 | 11 | 0 | 502 | 0 |
| `reindex_catalog` | 20 | 2 | 13 | 0 | 0 | 0 | 5 |
| all | 51,598 | 6,070 | 20,707 | 4,186 | 1,508 | 14,528 | 4,599 |

## Write this page again

```sh
.github/scripts/pg_regress.sh --subject postgresql all
.github/scripts/pg_regress.sh --skip-build all
uv run --no-project python .github/scripts/pg_regress_page.py collect e2e/target/pgregress
```

CI fails when this page and the [result](../e2e/src/test/resources/pgregress/result) disagree.

## Licence of the quoted statements

The statements quoted above are from `src/test/regress/sql` of PostgreSQL at tag `REL_18_7`, under the PostgreSQL License. The notice is in [`ATTRIBUTIONS.md`](../ATTRIBUTIONS.md) and in [`LICENSES/PostgreSQL.txt`](../LICENSES/PostgreSQL.txt).
