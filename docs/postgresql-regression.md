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
| engine | [`d88fa5c59b`](https://github.com/humemai/arcadedb/commit/d88fa5c59b11e22451554f9031fad9932d102c39) |
| CI run | [45994969289](https://github.com/humemai/arcadedb/actions/runs/45994969289) |
| date | 2026-11-27 (UTC) |

## Counts

| verdict | statements | share of all statements |
| --- | ---: | ---: |
| passed | 6,083 | 11.8% |
| refused, `0A000` | 22,861 | 44.3% |
| refused, other code | 600 | 1.2% |
| wrong | 207 | 0.4% |
| blocked | 17,245 | 33.4% |
| out of scope | 4,602 | 8.9% |
| all | 51,598 | 100% |

| verdict | rule, in short |
| --- | --- |
| passed | the same result on both, or the same SQLSTATE on both |
| refused | an error where PostgreSQL succeeds, or `0A000` where PostgreSQL raises another error |
| wrong | another result or other table contents, success where PostgreSQL raises an error, another code than PostgreSQL (`0A000` aside), a crash, or a timeout |
| blocked | not judged: the two sides cannot be made to agree on what the statement uses or depends on |
| out of scope | one of the entries of a closed list |

29,751 statements were judged, and 4,281 of them on a table that the harness had copied from PostgreSQL into ArcadeDB through the engine's own API. 2,813 `psql` commands never reach a server and are not statements, and 477 statements that `psql` does not send are not counted either.

A check of the harness: in the same CI run, with a second PostgreSQL server as the side under test, all 237 files gave only passed (46,995 statements) and out of scope (4,603).

## Wrong statements, by cause

207 statements are wrong, in 16 groups of one cause each. Each group has the issue that tracks it, with an example. A statement is named by its file and the line of the file where it starts, and is quoted on one line, cut after 120 characters. The two codes are the SQLSTATE of PostgreSQL and the one of `postgresql`; `00000` is success.

| cause | issue | wrong statements |
| --- | --- | ---: |
| A call of a function that a test file creates answers 42883 where PostgreSQL raises another error | [#260](https://github.com/humemai/arcadedb/issues/260) | 99 |
| A statement that PostgreSQL refuses for a missing privilege succeeds (kept as a [listed difference](postgresql-differences.md#kept-differences)) | [#176](https://github.com/humemai/arcadedb/issues/176) | 45 |
| An UPDATE or DELETE that a publication forbids succeeds | [#177](https://github.com/humemai/arcadedb/issues/177) | 17 |
| Too few parameters in a Bind answer 42P02 instead of 08P01 | [#204](https://github.com/humemai/arcadedb/issues/204) | 8 |
| A cast that the language lacks answers 42846 | [#203](https://github.com/humemai/arcadedb/issues/203) | 7 |
| After a refused SET of a setting that changes answers, a statement answers as without it | [#261](https://github.com/humemai/arcadedb/issues/261) | 6 |
| A date with a two-digit year is read as a year of the first century | [#193](https://github.com/humemai/arcadedb/issues/193) | 6 |
| A reference to a whole row answers 42703 instead of 0A000 | [#205](https://github.com/humemai/arcadedb/issues/205) | 6 |
| EXTRACT of a time zone from a date answers 22023 instead of 0A000 | [#206](https://github.com/humemai/arcadedb/issues/206) | 3 |
| DISTINCT and GROUP BY keep two jsonb values that PostgreSQL treats as equal | [#189](https://github.com/humemai/arcadedb/issues/189) | 3 |
| After a refused CREATE FUNCTION the built-in function of the same name answers | [#191](https://github.com/humemai/arcadedb/issues/191) | 2 |
| A constant division by zero in a branch that never runs succeeds | [#196](https://github.com/humemai/arcadedb/issues/196) | 1 |
| CREATE TABLE accepts two constraints of one name | [#195](https://github.com/humemai/arcadedb/issues/195) | 1 |
| ON CONFLICT on a table named excluded answers 42702 | [#207](https://github.com/humemai/arcadedb/issues/207) | 1 |
| LOCALTIMESTAMP is read from the clock for each call | [#190](https://github.com/humemai/arcadedb/issues/190) | 1 |
| An outer aggregate over a nested WITH succeeds where PostgreSQL refuses it | [#194](https://github.com/humemai/arcadedb/issues/194) | 1 |
| all | | 207 |

<details>
<summary>#260: 99 statements</summary>

A call of a function that a test file creates answers 42883 where PostgreSQL raises another error

| file and line | codes | statement |
| --- | --- | --- |
| [rangetypes:566](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rangetypes.sql#L566) | 22000 / 42883 | `select arrayrange(ARRAY[2,1], ARRAY[1,2]);` |
| [multirangetypes:817](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/multirangetypes.sql#L817) | 22000 / 42883 | `select arraymultirange(arrayrange(ARRAY[2,1], ARRAY[1,2]));` |
| [create_procedure:23](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_procedure.sql#L23) | 42809 / 42883 | `SELECT ptest1('x');` |
| [create_function_sql:504](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/create_function_sql.sql#L504) | 42P13 / 42883 | `SELECT test1(0);` |
| [infinite_recurse:27](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/infinite_recurse.sql#L27) | 54001 / 42883 | `select infinite_recurse();` |
| [privileges:892](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L892) | 42501 / 42883 | `SELECT priv_testfunc1(5);` |
| [privileges:893](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/privileges.sql#L893) | 42501 / 42883 | `SELECT priv_testagg1(x) FROM (VALUES (1), (2), (3)) _(x);` |
| [generated_stored:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_stored.sql#L308) | 42501 / 42883 | `SELECT gf1(10);` |
| [generated_virtual:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/generated_virtual.sql#L308) | 42501 / 42883 | `SELECT gf1(10);` |
| [psql:1659](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L1659) | P0001 / 42883 | `SELECT psql_error('bad!');` |
| [psql:1664](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/psql.sql#L1664) | P0001 / 42883 | `SELECT psql_error('bad!');` |
| [select_parallel:544](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/select_parallel.sql#L544) | 22012 / 42883 | `select set_role_and_error(0);` |
| [select_parallel:547](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/select_parallel.sql#L547) | 22012 / 42883 | `select set_role_and_error(0);` |
| [subscription:269](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/subscription.sql#L269) | 25001 / 42883 | `SELECT func();` |
| [portals_inflight:80](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/portals_inflight.sql#L80) | 0A000 / 42883 | `SELECT cl_ro_open();` |
| [portals_inflight:85](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/portals_inflight.sql#L85) | 0A000 / 42883 | `SELECT cl_ro_open();` |
| [guc:289](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L289) | 22012 / 42883 | `select myfunc(0);` |
| [guc:320](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/guc.sql#L320) | 22023 / 42883 | `select func_with_bad_set();` |
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
| [rowtypes_alter:249](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L249) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:295](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L295) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:390](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L390) | 22P07 / 42883 | `SELECT r, record_send(r) FROM (SELECT rta_func() AS r OFFSET 0) s;` |
| [rowtypes_alter:427](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L427) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:1078](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L1078) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:1126](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L1126) | 22P07 / 42883 | `SELECT rta_func();` |
| [rowtypes_alter:1328](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/rowtypes_alter.sql#L1328) | 22P07 / 42883 | `SELECT split_part(r::text, ',', 2), rta_exec('ALTER TYPE rta_type ALTER ATTRIBUTE b TYPE text'), split_part(r::text, ',' ...` |
| [explain:100](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/explain.sql#L100) | 22023 / 42883 | `select explain_filter('explain (analyze, generic_plan) select unique1 from tenk1 where thousand = $1');` |
| [event_trigger:14](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/event_trigger.sql#L14) | 0A000 / 42883 | `SELECT test_event_trigger();` |

</details>

<details>
<summary>#176: 45 statements</summary>

A statement that PostgreSQL refuses for a missing privilege succeeds (kept as a [listed difference](postgresql-differences.md#kept-differences))

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
<summary>#261: 6 statements</summary>

After a refused SET of a setting that changes answers, a statement answers as without it

| file and line | codes | statement |
| --- | --- | --- |
| [strings:61](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L61) | 0A000 / 42601 | `SELECT U&'wrong: \061';` |
| [strings:62](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L62) | 0A000 / 42601 | `SELECT U&'wrong: \+0061';` |
| [strings:63](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/strings.sql#L63) | 0A000 / 42601 | `SELECT U&'wrong: +0061' UESCAPE '+';` |
| [date:112](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L112) | 22008 / 22007 | `SELECT date '99 Jan 08';` |
| [aggregates:42](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/aggregates.sql#L42) | 00000 / 00000 | `SELECT sum(b) AS avg_431_773 FROM aggtest;` |
| [transactions:107](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/transactions.sql#L107) | 25006 / 00000 | `INSERT INTO writetest VALUES (1);` |

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
<summary>#206: 3 statements</summary>

EXTRACT of a time zone from a date answers 22023 instead of 0A000

| file and line | codes | statement |
| --- | --- | --- |
| [date:307](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L307) | 0A000 / 22023 | `SELECT EXTRACT(TIMEZONE FROM DATE '2020-08-11');` |
| [date:308](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L308) | 0A000 / 22023 | `SELECT EXTRACT(TIMEZONE_M FROM DATE '2020-08-11');` |
| [date:309](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/date.sql#L309) | 0A000 / 22023 | `SELECT EXTRACT(TIMEZONE_H FROM DATE '2020-08-11');` |

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
<summary>#191: 2 statements</summary>

After a refused CREATE FUNCTION the built-in function of the same name answers

| file and line | codes | statement |
| --- | --- | --- |
| [polymorphism:682](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L682) | 00000 / 00000 | `select concat('%', 1, 2, 3, 4, 5);` |
| [polymorphism:683](https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql/polymorphism.sql#L683) | 00000 / 00000 | `select concat('\|', 'a'::text, 'b', 'c');` |

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

## Why statements are refused

The messages are the language's own, with names, values, and numbers taken out.

### With `0A000`

| message | statements |
| --- | ---: |
| `DROP is not supported by the postgresql language` | 1,946 |
| `SET is not supported by the postgresql language` | 1,779 |
| `A subquery, a table function or LATERAL in FROM is not supported by the postgresql language` | 1,613 |
| `ALTER is not supported by the postgresql language` | 1,283 |
| `CREATE FUNCTION is not supported by the postgresql language` | 718 |
| `RESET is not supported by the postgresql language` | 667 |
| `A table option (INHERITS, PARTITION BY, USING, WITH, ON COMMIT or TABLESPACE) is not supported by the postgresql language` | 599 |
| `The function jsonb_path_query(unknown, unknown) is not supported by the postgresql language` | 479 |
| `A temporary or unlogged table is not supported by the postgresql language` | 413 |
| `FETCH is not supported by the postgresql language` | 266 |
| `CREATE TYPE is not supported by the postgresql language` | 263 |
| `The type jsonpath is not supported by the postgresql language` | 253 |
| `CREATE OR is not supported by the postgresql language` | 239 |
| `EXECUTE is not supported by the postgresql language` | 230 |
| `GRANT is not supported by the postgresql language` | 230 |
| `The type timestamptz is not supported by the postgresql language` | 213 |
| `CREATE VIEW is not supported by the postgresql language` | 197 |
| `CREATE DOMAIN is not supported by the postgresql language` | 169 |
| `CREATE ROLE is not supported by the postgresql language` | 168 |
| `This column option (CHECK, GENERATED, COLLATE, storage, a deferrable or unenforced constraint and the like) is not supported by the postgresql language` | 167 |
| `This form of a date is not supported: "...". Write year-month-day, such as n-n-n, with a time as n:n:n` | 157 |
| `ROW is not supported by the postgresql language` | 146 |
| `The operator @@ is not supported by the postgresql language` | 145 |
| `A named argument is not supported by the postgresql language` | 144 |
| `COPY is not supported by the postgresql language` | 143 |
| every other one | 10,234 |
| all | 22,861 |

### With another code

| message | statements |
| --- | ---: |
| `function does not exist` | 412 |
| `schema "..." does not exist` | 40 |
| `column "..." does not exist` | 33 |
| `column "..." must appear in the GROUP BY clause or be used in an aggregate function` | 26 |
| `syntax error at or near "..."` | 23 |
| `relation "..." does not exist` | 21 |
| `invalid input syntax for type integer: "..."` | 6 |
| `date/time field value out of range: "..."` | 5 |
| `invalid input syntax for type date: "..."` | 5 |
| `cannot cast type bytea to bigint` | 4 |
| `cannot cast type bytea to integer` | 4 |
| `cannot cast type bytea to smallint` | 4 |
| `operator does not exist` | 3 |
| `division by zero` | 2 |
| `more than one row returned by a subquery used as an expression` | 2 |
| `cannot cast type bigint to bytea` | 1 |
| `cannot cast type integer to bytea` | 1 |
| `cannot cast type smallint to bytea` | 1 |
| `column reference "..." is ambiguous, it matches "...", "..."` | 1 |
| `invalid input syntax for type integer: "..."([^"...""...")+)"` | 1 |
| every other one | 5 |
| all | 600 |

The functions that are missing most often, among the statements refused with another code:

| function | statements |
| --- | ---: |
| `explain_filter` | 29 |
| `dfunc` | 26 |
| `explain_mask_costs` | 14 |
| `explain_memoize` | 13 |
| `return_int_input` | 13 |
| `explain_parallel_append` | 12 |
| `f1` | 9 |
| `explain_merge` | 8 |
| `cast_invoker` | 7 |
| `testrngfunc` | 7 |
| `comp` | 6 |
| `dup` | 6 |
| `stats_test_func2` | 6 |
| `textrange` | 6 |
| `hobby_construct_named` | 5 |
| `my_avg` | 5 |
| `pleast` | 5 |
| `polyf` | 5 |
| `stricttest` | 5 |
| `test_temp_pin` | 5 |
| `array_elem_check` | 4 |
| `compos` | 4 |
| `dom_check` | 4 |
| `max_xacttest` | 4 |
| `myfunc` | 4 |
| every other one | 200 |
| all | 412 |

## Why statements are blocked

A statement with several reasons is counted under the first one. 659 statements are blocked first by a column of type `name`, and 452 by a column of type `char(n)`; whether the language gets the two types is [open](https://github.com/humemai/arcadedb/issues/119).

| reason | statements |
| --- | ---: |
| `inheritance and partitions are not copied` | 4,689 |
| `only tables of public and temporary tables are copied` | 2,661 |
| `the copy differs from the table in information_schema` | 1,392 |
| `the other side cannot hold a view` | 1,177 |
| `a column of type name cannot be held` | 659 |
| `a column of type int4range cannot be held` | 605 |
| `for a write: the copy does not carry a default, identity or generated column` | 584 |
| `a column of type character(n) cannot be held` | 452 |
| `a value of type numeric cannot be held` | 344 |
| `the search path puts it outside of public, and only tables of public and temporary tables are held` | 332 |
| `a column of type point cannot be held` | 234 |
| `a column of type box cannot be held` | 224 |
| `for a write: the copy does not carry a trigger` | 192 |
| `for a write: the copy does not carry a unique index` | 185 |
| `a column of type tsvector cannot be held` | 183 |
| `for a write: the copy does not carry a foreign key that refers to it` | 173 |
| `the other side cannot hold a materialized view` | 125 |
| `for a write: the copy does not carry a check constraint` | 124 |
| `a column of type timestamp(n) with time zone cannot be held` | 112 |
| `a value of type timestamp cannot be held` | 107 |
| every other one | 2,691 |
| all | 17,245 |

## Out of scope

The [list](../e2e/src/test/resources/pgregress/out-of-scope.tsv) is closed, and a change to it goes through a pull request.

| reason | statements |
| --- | ---: |
| PostgreSQL does not fix the answer | 174 |
| PostgreSQL internals | 3,750 |
| server administration | 678 |
| all | 4,602 |

| entry of the list | reason | statements |
| --- | --- | ---: |
| `explain` | PostgreSQL internals | 2,202 |
| `catalog-relation` | PostgreSQL internals | 1,194 |
| `publication` | server administration | 434 |
| `system-column` | PostgreSQL internals | 253 |
| `two-runs` | PostgreSQL does not fix the answer | 161 |
| `c-function-call` | server administration | 113 |
| `catalog-qualified` | PostgreSQL internals | 101 |
| `c-function` | server administration | 83 |
| `tablespace` | server administration | 16 |
| `language-handler` | server administration | 14 |
| `access-method` | server administration | 13 |
| `tied-order` | PostgreSQL does not fix the answer | 9 |
| `tied-rows` | PostgreSQL does not fix the answer | 4 |
| `extension` | server administration | 2 |
| `load` | server administration | 2 |
| `replication-function` | server administration | 1 |
| all | | 4,602 |

## What the missing C library changes

`pg_regress` builds a library of C functions, `regress.so`, for the suite. The harness does not. A statement that defines a function of that code, or calls one, is out of scope. What else changes is that PostgreSQL itself fails where the suite expects success, when a type or another object needs such a function; both sides are then compared with that error. The table has every file that defines or calls such a function. Compare the two last columns: they are not the same measure, because the suite runs all files in one database with its own settings.

| file | statements | defines C code | calls C code | wrong | errors of PostgreSQL | `ERROR` lines the suite expects |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `test_setup` | 71 | 4 | 0 | 0 | 4 | 0 |
| `float4` | 100 | 2 | 0 | 0 | 30 | 30 |
| `float8` | 184 | 2 | 0 | 0 | 45 | 45 |
| `type_sanity` | 63 | 1 | 1 | 0 | 2 | 0 |
| `opr_sanity` | 131 | 0 | 14 | 0 | 14 | 0 |
| `expressions` | 79 | 3 | 1 | 1 | 1 | 1 |
| `encoding` | 75 | 6 | 17 | 0 | 32 | 18 |
| `create_function_c` | 5 | 3 | 0 | 0 | 5 | 3 |
| `create_type` | 95 | 24 | 3 | 0 | 46 | 27 |
| `create_view` | 310 | 1 | 1 | 0 | 21 | 19 |
| `create_cast` | 24 | 2 | 0 | 0 | 3 | 3 |
| `triggers` | 1,073 | 1 | 2 | 0 | 75 | 72 |
| `privileges` | 1,402 | 1 | 1 | 39 | 244 | 244 |
| `lock` | 131 | 1 | 1 | 0 | 6 | 4 |
| `alter_generic` | 333 | 1 | 2 | 0 | 111 | 108 |
| `alter_operator` | 70 | 1 | 1 | 0 | 12 | 12 |
| `misc` | 61 | 2 | 3 | 0 | 5 | 0 |
| `misc_functions` | 150 | 6 | 32 | 0 | 47 | 12 |
| `psql` | 480 | 1 | 0 | 3 | 54 | 31 |
| `foreign_data` | 539 | 1 | 1 | 0 | 131 | 126 |
| `window` | 400 | 1 | 2 | 0 | 40 | 40 |
| `indirect_toast` | 28 | 1 | 1 | 0 | 7 | 0 |
| `equivclass` | 96 | 11 | 2 | 0 | 0 | 0 |
| `sqljson` | 233 | 2 | 0 | 0 | 55 | 55 |
| `conversion` | 65 | 2 | 1 | 0 | 38 | 3 |
| `rowtypes` | 294 | 0 | 5 | 2 | 37 | 32 |
| `rowtypes_alter` | 495 | 0 | 2 | 7 | 84 | 83 |
| `attribute_version` | 107 | 1 | 6 | 0 | 10 | 11 |
| `compression_pglz` | 18 | 2 | 14 | 0 | 18 | 11 |
| these files | 7,112 | 83 | 113 | 52 | 1,177 | 990 |

What differs in four of them, read from the statements on which PostgreSQL fails:

- `create_type`: the input and output functions of its new types are C code, so the types are not completed, and statements about them fail on PostgreSQL, many with `42704` (undefined object).
- `conversion`: `test_enc_setup` and `test_enc_conversion` are C code, and the calls of `test_conv`, which the file tests the conversions with, fail on PostgreSQL with `42883` (undefined function).
- `misc_functions`: the functions of the library are not made, so calls of them fail with `42883`.
- `psql`: it defines one such function. Part of its difference has another cause: it reads back files that `psql` would have written, and the harness writes none (`58P01`).

## By file

| file | statements | passed | refused, `0A000` | refused, other code | wrong | blocked | out of scope |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `test_setup` | 71 | 10 | 24 | 0 | 0 | 32 | 5 |
| `boolean` | 98 | 80 | 18 | 0 | 0 | 0 | 0 |
| `char` | 32 | 0 | 13 | 0 | 0 | 19 | 0 |
| `name` | 40 | 0 | 21 | 0 | 0 | 19 | 0 |
| `varchar` | 22 | 0 | 3 | 0 | 0 | 19 | 0 |
| `text` | 74 | 14 | 60 | 0 | 0 | 0 | 0 |
| `int2` | 76 | 65 | 11 | 0 | 0 | 0 | 0 |
| `int4` | 101 | 82 | 19 | 0 | 0 | 0 | 0 |
| `int8` | 174 | 129 | 44 | 0 | 0 | 0 | 1 |
| `oid` | 37 | 0 | 11 | 0 | 0 | 26 | 0 |
| `float4` | 100 | 65 | 32 | 1 | 0 | 0 | 2 |
| `float8` | 184 | 103 | 77 | 1 | 0 | 1 | 2 |
| `bit` | 132 | 0 | 81 | 0 | 0 | 51 | 0 |
| `numeric` | 1,057 | 112 | 333 | 0 | 0 | 612 | 0 |
| `txid` | 47 | 10 | 20 | 0 | 0 | 8 | 9 |
| `uuid` | 68 | 0 | 18 | 2 | 0 | 28 | 20 |
| `enum` | 170 | 10 | 114 | 1 | 0 | 35 | 10 |
| `money` | 113 | 0 | 58 | 0 | 0 | 55 | 0 |
| `rangetypes` | 419 | 3 | 214 | 1 | 1 | 184 | 16 |
| `pg_lsn` | 31 | 2 | 19 | 0 | 0 | 9 | 1 |
| `regproc` | 105 | 1 | 104 | 0 | 0 | 0 | 0 |
| `strings` | 505 | 148 | 311 | 21 | 6 | 8 | 11 |
| `md5` | 14 | 0 | 14 | 0 | 0 | 0 | 0 |
| `numerology` | 89 | 72 | 14 | 3 | 0 | 0 | 0 |
| `point` | 43 | 0 | 13 | 0 | 0 | 30 | 0 |
| `lseg` | 16 | 0 | 3 | 0 | 0 | 13 | 0 |
| `line` | 35 | 0 | 12 | 0 | 0 | 23 | 0 |
| `box` | 101 | 2 | 21 | 0 | 0 | 64 | 14 |
| `path` | 23 | 0 | 5 | 0 | 0 | 18 | 0 |
| `polygon` | 62 | 1 | 17 | 0 | 0 | 31 | 13 |
| `circle` | 22 | 0 | 2 | 0 | 0 | 20 | 0 |
| `date` | 271 | 77 | 168 | 10 | 10 | 6 | 0 |
| `time` | 44 | 2 | 25 | 0 | 0 | 17 | 0 |
| `timetz` | 57 | 3 | 30 | 0 | 0 | 24 | 0 |
| `timestamp` | 177 | 13 | 67 | 1 | 0 | 93 | 3 |
| `timestamptz` | 404 | 10 | 267 | 0 | 0 | 123 | 4 |
| `interval` | 446 | 67 | 324 | 0 | 0 | 54 | 1 |
| `inet` | 116 | 0 | 32 | 0 | 0 | 78 | 6 |
| `macaddr` | 35 | 0 | 5 | 0 | 0 | 30 | 0 |
| `macaddr8` | 71 | 0 | 26 | 0 | 0 | 45 | 0 |
| `multirangetypes` | 622 | 1 | 442 | 9 | 1 | 169 | 0 |
| `geometry` | 162 | 0 | 5 | 0 | 0 | 155 | 2 |
| `horology` | 399 | 21 | 340 | 7 | 0 | 27 | 4 |
| `tstypes` | 238 | 1 | 237 | 0 | 0 | 0 | 0 |
| `regex` | 105 | 0 | 93 | 3 | 0 | 0 | 9 |
| `type_sanity` | 63 | 0 | 0 | 0 | 0 | 0 | 63 |
| `opr_sanity` | 131 | 0 | 0 | 0 | 0 | 0 | 131 |
| `misc_sanity` | 5 | 0 | 0 | 0 | 0 | 0 | 5 |
| `comments` | 6 | 6 | 0 | 0 | 0 | 0 | 0 |
| `expressions` | 79 | 6 | 34 | 15 | 1 | 15 | 8 |
| `unicode` | 29 | 0 | 29 | 0 | 0 | 0 | 0 |
| `xid` | 84 | 10 | 52 | 0 | 0 | 13 | 9 |
| `mvcc` | 11 | 3 | 8 | 0 | 0 | 0 | 0 |
| `database` | 27 | 2 | 23 | 0 | 0 | 0 | 2 |
| `stats_import` | 179 | 4 | 35 | 2 | 0 | 16 | 122 |
| `encoding` | 75 | 29 | 23 | 0 | 0 | 0 | 23 |
| `euc_kr` | 2 | 0 | 2 | 0 | 0 | 0 | 0 |
| `copy` | 107 | 17 | 47 | 0 | 0 | 39 | 4 |
| `copyselect` | 40 | 8 | 16 | 0 | 0 | 16 | 0 |
| `copydml` | 59 | 16 | 3 | 0 | 0 | 40 | 0 |
| `copyencoding` | 17 | 1 | 16 | 0 | 0 | 0 | 0 |
| `insert` | 390 | 16 | 65 | 0 | 0 | 294 | 15 |
| `insert_conflict` | 261 | 38 | 37 | 0 | 1 | 159 | 26 |
| `create_function_c` | 5 | 1 | 0 | 0 | 0 | 0 | 4 |
| `create_misc` | 88 | 0 | 1 | 0 | 0 | 86 | 1 |
| `create_operator` | 115 | 40 | 72 | 1 | 0 | 0 | 2 |
| `create_procedure` | 107 | 9 | 97 | 0 | 1 | 0 | 0 |
| `create_table` | 335 | 23 | 116 | 1 | 0 | 179 | 16 |
| `create_type` | 95 | 2 | 58 | 0 | 0 | 3 | 32 |
| `create_schema` | 27 | 0 | 24 | 0 | 0 | 3 | 0 |
| `create_index` | 696 | 104 | 222 | 0 | 0 | 265 | 105 |
| `create_index_spgist` | 202 | 29 | 33 | 0 | 0 | 60 | 80 |
| `create_view` | 310 | 4 | 106 | 2 | 0 | 179 | 19 |
| `index_including` | 135 | 5 | 29 | 0 | 0 | 72 | 29 |
| `index_including_gist` | 50 | 0 | 12 | 0 | 0 | 29 | 9 |
| `create_aggregate` | 59 | 0 | 56 | 0 | 0 | 0 | 3 |
| `create_function_sql` | 181 | 0 | 121 | 14 | 1 | 32 | 13 |
| `create_cast` | 24 | 2 | 18 | 1 | 0 | 0 | 3 |
| `constraints` | 568 | 119 | 176 | 0 | 1 | 260 | 12 |
| `triggers` | 1,073 | 100 | 235 | 0 | 0 | 705 | 33 |
| `select` | 87 | 24 | 20 | 5 | 0 | 27 | 11 |
| `inherit` | 884 | 60 | 171 | 0 | 0 | 502 | 151 |
| `typed_table` | 31 | 3 | 26 | 1 | 0 | 1 | 0 |
| `vacuum` | 328 | 63 | 124 | 0 | 0 | 116 | 25 |
| `drop_if_exists` | 161 | 2 | 149 | 0 | 0 | 4 | 6 |
| `updatable_views` | 1,185 | 132 | 249 | 0 | 10 | 749 | 45 |
| `roleattributes` | 80 | 0 | 48 | 0 | 0 | 0 | 32 |
| `create_am` | 142 | 17 | 56 | 0 | 0 | 32 | 37 |
| `hash_func` | 43 | 0 | 42 | 0 | 0 | 0 | 1 |
| `errors` | 87 | 27 | 47 | 0 | 0 | 5 | 8 |
| `infinite_recurse` | 3 | 0 | 2 | 0 | 1 | 0 | 0 |
| `sanity_check` | 3 | 0 | 1 | 0 | 0 | 0 | 2 |
| `select_into` | 67 | 9 | 41 | 1 | 0 | 6 | 10 |
| `select_distinct` | 101 | 24 | 52 | 0 | 0 | 10 | 15 |
| `select_distinct_on` | 23 | 2 | 10 | 0 | 0 | 4 | 7 |
| `select_implicit` | 44 | 2 | 3 | 0 | 0 | 39 | 0 |
| `select_having` | 23 | 0 | 1 | 0 | 0 | 22 | 0 |
| `subselect` | 361 | 103 | 91 | 7 | 0 | 57 | 103 |
| `union` | 198 | 64 | 73 | 1 | 0 | 16 | 44 |
| `case` | 64 | 42 | 14 | 4 | 1 | 0 | 3 |
| `join` | 1,022 | 252 | 326 | 1 | 0 | 108 | 335 |
| `aggregates` | 559 | 41 | 306 | 19 | 1 | 127 | 65 |
| `transactions` | 490 | 235 | 243 | 5 | 1 | 2 | 4 |
| `random` | 48 | 0 | 43 | 5 | 0 | 0 | 0 |
| `portals` | 354 | 97 | 165 | 1 | 0 | 74 | 17 |
| `arrays` | 515 | 50 | 309 | 10 | 0 | 144 | 2 |
| `btree_index` | 129 | 24 | 50 | 0 | 0 | 19 | 36 |
| `hash_index` | 100 | 34 | 52 | 0 | 0 | 13 | 1 |
| `update` | 286 | 26 | 44 | 0 | 0 | 184 | 32 |
| `delete` | 10 | 2 | 1 | 0 | 0 | 7 | 0 |
| `namespace` | 42 | 5 | 14 | 0 | 0 | 19 | 4 |
| `prepared_xacts` | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| `brin` | 77 | 10 | 48 | 0 | 0 | 16 | 3 |
| `gin` | 59 | 2 | 50 | 0 | 0 | 0 | 7 |
| `gist` | 84 | 2 | 23 | 0 | 0 | 46 | 13 |
| `spgist` | 41 | 1 | 13 | 0 | 0 | 26 | 1 |
| `privileges` | 1,402 | 135 | 985 | 18 | 39 | 163 | 62 |
| `init_privs` | 4 | 0 | 0 | 0 | 0 | 0 | 4 |
| `security_label` | 28 | 2 | 25 | 0 | 0 | 1 | 0 |
| `collate` | 146 | 1 | 42 | 0 | 0 | 98 | 5 |
| `matview` | 179 | 15 | 54 | 1 | 0 | 90 | 19 |
| `lock` | 131 | 38 | 19 | 0 | 0 | 64 | 10 |
| `replica_identity` | 76 | 9 | 18 | 0 | 0 | 37 | 12 |
| `rowsecurity` | 1,242 | 9 | 380 | 0 | 0 | 728 | 125 |
| `object_address` | 82 | 0 | 70 | 0 | 0 | 6 | 6 |
| `tablesample` | 58 | 4 | 36 | 0 | 0 | 12 | 6 |
| `groupingsets` | 217 | 6 | 119 | 0 | 0 | 40 | 52 |
| `drop_operator` | 12 | 0 | 8 | 0 | 0 | 0 | 4 |
| `password` | 53 | 0 | 47 | 0 | 0 | 0 | 6 |
| `identity` | 271 | 32 | 67 | 1 | 0 | 158 | 13 |
| `generated_stored` | 440 | 5 | 42 | 0 | 1 | 374 | 18 |
| `join_hash` | 279 | 44 | 204 | 0 | 0 | 1 | 30 |
| `brin_bloom` | 40 | 1 | 26 | 0 | 0 | 11 | 2 |
| `brin_multi` | 179 | 48 | 66 | 0 | 0 | 54 | 11 |
| `create_table_like` | 183 | 33 | 87 | 0 | 0 | 52 | 11 |
| `alter_generic` | 333 | 14 | 284 | 0 | 0 | 7 | 28 |
| `alter_operator` | 70 | 0 | 54 | 0 | 0 | 0 | 16 |
| `misc` | 61 | 7 | 15 | 10 | 0 | 24 | 5 |
| `async` | 11 | 0 | 11 | 0 | 0 | 0 | 0 |
| `dbsize` | 25 | 0 | 25 | 0 | 0 | 0 | 0 |
| `merge` | 572 | 250 | 160 | 8 | 0 | 137 | 17 |
| `misc_functions` | 150 | 3 | 87 | 14 | 0 | 0 | 46 |
| `sysviews` | 29 | 2 | 4 | 0 | 0 | 0 | 23 |
| `tsrf` | 74 | 14 | 52 | 1 | 0 | 0 | 7 |
| `tid` | 41 | 8 | 22 | 0 | 0 | 7 | 4 |
| `tidscan` | 49 | 8 | 13 | 0 | 0 | 0 | 28 |
| `tidrangescan` | 55 | 5 | 17 | 0 | 0 | 0 | 33 |
| `collate.utf8` | 58 | 0 | 58 | 0 | 0 | 0 | 0 |
| `collate.icu.utf8` | 703 | 10 | 207 | 1 | 0 | 417 | 68 |
| `incremental_sort` | 120 | 29 | 47 | 6 | 0 | 1 | 37 |
| `create_role` | 146 | 4 | 138 | 0 | 0 | 3 | 1 |
| `without_overlaps` | 658 | 22 | 52 | 0 | 0 | 570 | 14 |
| `generated_virtual` | 466 | 13 | 41 | 0 | 1 | 392 | 19 |
| `rules` | 619 | 168 | 111 | 0 | 0 | 331 | 9 |
| `psql` | 480 | 184 | 238 | 4 | 3 | 46 | 5 |
| `psql_crosstab` | 30 | 19 | 11 | 0 | 0 | 0 | 0 |
| `psql_pipeline` | 154 | 116 | 19 | 0 | 7 | 12 | 0 |
| `amutils` | 10 | 0 | 7 | 0 | 0 | 0 | 3 |
| `stats_ext` | 876 | 26 | 687 | 3 | 0 | 127 | 33 |
| `collate.linux.utf8` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `collate.windows.win1252` | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| `select_parallel` | 250 | 8 | 128 | 4 | 2 | 58 | 50 |
| `write_parallel` | 22 | 2 | 8 | 0 | 0 | 8 | 4 |
| `vacuum_parallel` | 14 | 3 | 9 | 0 | 0 | 0 | 2 |
| `maintain_every` | 16 | 0 | 9 | 0 | 0 | 5 | 2 |
| `publication` | 710 | 38 | 199 | 8 | 17 | 126 | 322 |
| `subscription` | 172 | 14 | 37 | 0 | 1 | 0 | 120 |
| `select_views` | 52 | 1 | 15 | 0 | 0 | 28 | 8 |
| `portals_p2` | 41 | 2 | 26 | 0 | 0 | 13 | 0 |
| `foreign_key` | 1,313 | 132 | 178 | 2 | 0 | 956 | 45 |
| `portals_inflight` | 35 | 12 | 13 | 0 | 2 | 6 | 2 |
| `cluster` | 218 | 61 | 55 | 0 | 0 | 85 | 17 |
| `dependency` | 62 | 2 | 54 | 0 | 0 | 4 | 2 |
| `guc` | 223 | 17 | 184 | 7 | 2 | 2 | 11 |
| `bitmapops` | 12 | 5 | 7 | 0 | 0 | 0 | 0 |
| `combocid` | 62 | 39 | 10 | 0 | 0 | 0 | 13 |
| `tsearch` | 446 | 1 | 228 | 0 | 0 | 202 | 15 |
| `tsdicts` | 131 | 0 | 128 | 0 | 0 | 0 | 3 |
| `foreign_data` | 539 | 5 | 344 | 12 | 0 | 159 | 19 |
| `window` | 400 | 24 | 152 | 0 | 0 | 170 | 54 |
| `xmlmap` | 40 | 0 | 33 | 2 | 0 | 5 | 0 |
| `functional_deps` | 40 | 8 | 21 | 5 | 0 | 6 | 0 |
| `advisory_lock` | 38 | 8 | 14 | 0 | 0 | 0 | 16 |
| `indirect_toast` | 28 | 1 | 6 | 4 | 0 | 15 | 2 |
| `equivclass` | 96 | 3 | 46 | 0 | 0 | 10 | 37 |
| `alter_column_type` | 215 | 52 | 126 | 3 | 0 | 30 | 4 |
| `json` | 470 | 10 | 428 | 4 | 1 | 25 | 2 |
| `jsonb` | 1,098 | 90 | 933 | 0 | 8 | 62 | 5 |
| `json_encoding` | 44 | 9 | 35 | 0 | 0 | 0 | 0 |
| `jsonpath` | 224 | 1 | 223 | 0 | 0 | 0 | 0 |
| `jsonpath_encoding` | 32 | 0 | 32 | 0 | 0 | 0 | 0 |
| `jsonb_jsonpath` | 836 | 52 | 783 | 1 | 0 | 0 | 0 |
| `sqljson` | 233 | 8 | 192 | 0 | 0 | 6 | 27 |
| `sqljson_queryfuncs` | 356 | 5 | 340 | 1 | 0 | 9 | 1 |
| `sqljson_jsontable` | 133 | 4 | 110 | 0 | 0 | 12 | 7 |
| `plancache` | 97 | 4 | 53 | 8 | 0 | 22 | 10 |
| `limit` | 80 | 2 | 44 | 0 | 0 | 26 | 8 |
| `plpgsql` | 941 | 31 | 480 | 96 | 45 | 283 | 6 |
| `copy2` | 284 | 79 | 115 | 1 | 0 | 89 | 0 |
| `temp` | 207 | 94 | 61 | 8 | 2 | 37 | 5 |
| `domain` | 482 | 15 | 246 | 12 | 13 | 190 | 6 |
| `rangefuncs` | 428 | 24 | 292 | 22 | 1 | 65 | 24 |
| `prepare` | 33 | 0 | 18 | 0 | 0 | 7 | 8 |
| `conversion` | 65 | 16 | 46 | 0 | 0 | 0 | 3 |
| `truncate` | 193 | 39 | 23 | 0 | 0 | 131 | 0 |
| `alter_table` | 1,778 | 308 | 574 | 10 | 0 | 816 | 70 |
| `sequence` | 261 | 28 | 154 | 0 | 0 | 77 | 2 |
| `polymorphism` | 423 | 14 | 325 | 65 | 13 | 2 | 4 |
| `rowtypes` | 294 | 22 | 175 | 13 | 2 | 62 | 20 |
| `returning` | 149 | 21 | 21 | 1 | 0 | 82 | 24 |
| `largeobject` | 127 | 28 | 35 | 0 | 0 | 55 | 9 |
| `with` | 307 | 65 | 169 | 2 | 1 | 54 | 16 |
| `xml` | 272 | 3 | 206 | 0 | 0 | 55 | 8 |
| `rowtypes_alter` | 495 | 58 | 387 | 2 | 7 | 35 | 6 |
| `partition_join` | 640 | 0 | 61 | 0 | 0 | 441 | 138 |
| `partition_prune` | 853 | 28 | 147 | 12 | 0 | 363 | 303 |
| `reloptions` | 66 | 3 | 44 | 0 | 0 | 0 | 19 |
| `hash_part` | 29 | 0 | 23 | 0 | 0 | 6 | 0 |
| `indexing` | 615 | 40 | 168 | 0 | 0 | 351 | 56 |
| `partition_aggregate` | 137 | 1 | 23 | 0 | 0 | 72 | 41 |
| `partition_info` | 73 | 1 | 47 | 0 | 0 | 17 | 8 |
| `tuplesort` | 108 | 20 | 45 | 0 | 0 | 27 | 16 |
| `explain` | 60 | 4 | 21 | 29 | 1 | 5 | 0 |
| `memoize` | 87 | 6 | 35 | 13 | 0 | 26 | 7 |
| `stats` | 475 | 89 | 189 | 9 | 0 | 37 | 151 |
| `predicate` | 42 | 10 | 6 | 0 | 0 | 4 | 22 |
| `numa` | 2 | 0 | 1 | 0 | 0 | 0 | 1 |
| `attribute_version` | 107 | 8 | 64 | 2 | 0 | 25 | 8 |
| `compression` | 87 | 20 | 43 | 2 | 0 | 22 | 0 |
| `compression_pglz` | 18 | 0 | 2 | 0 | 0 | 0 | 16 |
| `oidjoins` | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| `event_trigger` | 230 | 23 | 156 | 9 | 1 | 38 | 3 |
| `event_trigger_login` | 11 | 0 | 7 | 0 | 0 | 1 | 3 |
| `fast_default` | 275 | 4 | 35 | 6 | 0 | 202 | 28 |
| `tablespace` | 204 | 13 | 66 | 2 | 0 | 81 | 42 |
| `numeric_big` | 552 | 6 | 38 | 0 | 0 | 508 | 0 |
| `reindex_catalog` | 20 | 2 | 13 | 0 | 0 | 0 | 5 |
| all | 51,598 | 6,083 | 22,861 | 600 | 207 | 17,245 | 4,602 |

## Write this page again

```sh
.github/scripts/pg_regress.sh --subject postgresql all
.github/scripts/pg_regress.sh --skip-build all
uv run --no-project python .github/scripts/pg_regress_page.py collect e2e/target/pgregress
```

CI fails when this page and the [result](../e2e/src/test/resources/pgregress/result) disagree.

## Licence of the quoted statements

The statements quoted above are from `src/test/regress/sql` of PostgreSQL at tag `REL_18_7`, under the PostgreSQL License. The notice is in [`ATTRIBUTIONS.md`](../ATTRIBUTIONS.md) and in [`LICENSES/PostgreSQL.txt`](../LICENSES/PostgreSQL.txt).
