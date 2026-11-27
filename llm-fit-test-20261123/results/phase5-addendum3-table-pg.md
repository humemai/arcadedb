| model | arm | runs | success (Wilson 95%) | task bootstrap 95% | state ok strict | state ok normalised | answers ok | lenient state ok | calls/run | error rate per call | refused 0A000 | syntax | runtime | budget exhausted | runs with a 0A000 refusal | failed: wrong state only / wrong answer only / both / engine timeout | USD per task |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| claude-haiku-5-5 | pg | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 2.87 | 8/241 = 3.3% | 5 | 2 | 1 | 0 | 5 | 3 / 0 / 0 / 0 | 0.0009 |
| claude-opus-5-5 | pg | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 3.43 | 4/288 = 1.4% | 4 | 0 | 0 | 0 | 4 | 3 / 0 / 0 / 0 | 0.0226 |
| claude-sonnet-5-5 | pg | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 2.63 | 9/221 = 4.1% | 6 | 0 | 3 | 0 | 6 | 3 / 0 / 0 / 0 | 0.0087 |

Success per family (Wilson 95%)

| model | arm | remember | recall | correct | forget | consolidate | reason |
|---|---|---|---|---|---|---|---|
| claude-haiku-5-5 | pg | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-opus-5-5 | pg | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-sonnet-5-5 | pg | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |

Error calls by SQLSTATE (or exception class when the engine has none)

| model | arm | SQLSTATE or class | calls |
|---|---|---|---|
| claude-haiku-5-5 | pg | 0A000 | 5 |
| claude-haiku-5-5 | pg | 42601 | 2 |
| claude-haiku-5-5 | pg | 23505 | 1 |
| claude-opus-5-5 | pg | 0A000 | 4 |
| claude-sonnet-5-5 | pg | 0A000 | 6 |
| claude-sonnet-5-5 | pg | 42804 | 2 |
| claude-sonnet-5-5 | pg | 42703 | 1 |

Stop reasons of the requests (refusal = a safety classifier; max_tokens = a cut answer)

| model | arm | stop reason | requests |
|---|---|---|---|
| claude-haiku-5-5 | pg | end_turn | 105 |
| claude-haiku-5-5 | pg | tool_use | 241 |
| claude-opus-5-5 | pg | end_turn | 105 |
| claude-opus-5-5 | pg | tool_use | 288 |
| claude-sonnet-5-5 | pg | end_turn | 105 |
| claude-sonnet-5-5 | pg | tool_use | 221 |

The re-run next to the registered pg arm and the sql_script arm (Wilson 95%; disjoint = the registered reading rule)

| model | pg on d5cf2af42e | registered pg | sql_script | vs registered pg | vs sql_script |
|---|---|---|---|---|---|
| Haiku 5.5 | 81/84 = 96.4% [90.0, 98.8] | 78/84 = 92.9% [85.3, 96.7] | 78/84 = 92.9% [85.3, 96.7] | overlap | overlap |
| Sonnet 5.5 | 81/84 = 96.4% [90.0, 98.8] | 81/84 = 96.4% [90.0, 98.8] | 80/84 = 95.2% [88.4, 98.1] | overlap | overlap |
| Opus 5.5 | 81/84 = 96.4% [90.0, 98.8] | 81/84 = 96.4% [90.0, 98.8] | 81/84 = 96.4% [90.0, 98.8] | overlap | overlap |

Error calls by class, registered pg against pg on d5cf2af42e

| class | Haiku registered | Haiku d5cf2af42e | Sonnet registered | Sonnet d5cf2af42e | Opus registered | Opus d5cf2af42e |
|---|---|---|---|---|---|---|
| ILIKE (0A000) | 59 | 0 | 17 | 0 | 63 | 0 |
| correlated scalar subquery (42P01) | 13 | 0 | 14 | 0 | 17 | 0 |
| UPDATE of an edge column (0A000, by design) | 3 | 4 | 6 | 6 | 3 | 3 |
| other (42804) | 3 | 0 | 0 | 2 | 0 | 0 |
| string_agg (42883) | 5 | 0 | 0 | 0 | 0 | 0 |
| other (0A000) | 1 | 1 | 0 | 0 | 1 | 1 |
| other (42703) | 0 | 0 | 2 | 1 | 0 | 0 |
| other (22P02) | 2 | 0 | 0 | 0 | 0 | 0 |
| other (42601) | 0 | 2 | 0 | 0 | 0 | 0 |
| other (23505) | 0 | 1 | 0 | 0 | 0 | 0 |
| calls | 361 | 241 | 246 | 221 | 374 | 288 |
