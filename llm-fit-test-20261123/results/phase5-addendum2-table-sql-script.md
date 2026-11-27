| model | arm | runs | success (Wilson 95%) | task bootstrap 95% | state ok strict | state ok normalised | answers ok | lenient state ok | calls/run | error rate per call | refused 0A000 | syntax | runtime | budget exhausted | runs with a 0A000 refusal | failed: wrong state only / wrong answer only / both / engine timeout | USD per task |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| claude-haiku-5-5 | pg | 84 | 78/84 = 92.9% [85.3, 96.7] | [83.3, 100.0] | 80 | 80 | 82 | 80 | 4.30 | 86/361 = 23.8% | 63 | 0 | 23 | 1 | 51 | 4 / 2 / 0 / 0 | 0.0011 |
| claude-haiku-5-5 | sql | 84 | 77/84 = 91.7% [83.8, 95.9] | [82.1, 98.8] | 77 | 77 | 84 | 77 | 4.39 | 38/369 = 10.3% | 0 | 30 | 8 | 1 | 0 | 7 / 0 / 0 / 0 | 0.0010 |
| claude-haiku-5-5 | sql_script | 84 | 78/84 = 92.9% [85.3, 96.7] | [82.1, 100.0] | 78 | 78 | 84 | 78 | 4.36 | 34/366 = 9.3% | 0 | 26 | 8 | 1 | 0 | 6 / 0 / 0 / 0 | 0.0010 |
| claude-opus-5-5 | pg | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 4.45 | 84/374 = 22.5% | 67 | 0 | 17 | 3 | 54 | 3 / 0 / 0 / 0 | 0.0264 |
| claude-opus-5-5 | sql | 84 | 69/84 = 82.1% [72.6, 88.9] | [69.0, 92.9] | 69 | 69 | 82 | 69 | 4.45 | 47/374 = 12.6% | 0 | 29 | 18 | 1 | 0 | 13 / 0 / 2 / 0 | 0.0235 |
| claude-opus-5-5 | sql_script | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 4.11 | 37/345 = 10.7% | 0 | 33 | 4 | 0 | 0 | 3 / 0 / 0 / 0 | 0.0205 |
| claude-sonnet-5-5 | pg | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 2.93 | 39/246 = 15.9% | 23 | 0 | 16 | 0 | 20 | 3 / 0 / 0 / 0 | 0.0092 |
| claude-sonnet-5-5 | sql | 84 | 81/84 = 96.4% [90.0, 98.8] | [89.3, 100.0] | 81 | 81 | 84 | 81 | 3.32 | 19/279 = 6.8% | 0 | 14 | 5 | 0 | 0 | 3 / 0 / 0 / 0 | 0.0080 |
| claude-sonnet-5-5 | sql_script | 84 | 80/84 = 95.2% [88.4, 98.1] | [86.9, 100.0] | 80 | 80 | 84 | 80 | 3.33 | 19/280 = 6.8% | 0 | 16 | 3 | 0 | 0 | 4 / 0 / 0 / 0 | 0.0080 |

Success per family (Wilson 95%)

| model | arm | remember | recall | correct | forget | consolidate | reason |
|---|---|---|---|---|---|---|---|
| claude-haiku-5-5 | pg | 14/15 = 93.3% [70.2, 98.8] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 10/12 = 83.3% [55.2, 95.3] |
| claude-haiku-5-5 | sql | 14/15 = 93.3% [70.2, 98.8] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 9/12 = 75.0% [46.8, 91.1] | 12/12 = 100.0% [75.8, 100.0] |
| claude-haiku-5-5 | sql_script | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 9/12 = 75.0% [46.8, 91.1] | 12/12 = 100.0% [75.8, 100.0] |
| claude-opus-5-5 | pg | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-opus-5-5 | sql | 4/15 = 26.7% [10.9, 52.0] | 15/15 = 100.0% [79.6, 100.0] | 11/15 = 73.3% [48.0, 89.1] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-opus-5-5 | sql_script | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-sonnet-5-5 | pg | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-sonnet-5-5 | sql | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 15/15 = 100.0% [79.6, 100.0] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |
| claude-sonnet-5-5 | sql_script | 15/15 = 100.0% [79.6, 100.0] | 15/15 = 100.0% [79.6, 100.0] | 12/15 = 80.0% [54.8, 93.0] | 14/15 = 93.3% [70.2, 98.8] | 12/12 = 100.0% [75.8, 100.0] | 12/12 = 100.0% [75.8, 100.0] |

Error calls by SQLSTATE (or exception class when the engine has none)

| model | arm | SQLSTATE or class | calls |
|---|---|---|---|
| claude-haiku-5-5 | pg | 0A000 | 63 |
| claude-haiku-5-5 | pg | 42P01 | 13 |
| claude-haiku-5-5 | pg | 42883 | 5 |
| claude-haiku-5-5 | pg | 42804 | 3 |
| claude-haiku-5-5 | pg | 22P02 | 2 |
| claude-haiku-5-5 | sql | CommandSQLParsingException | 30 |
| claude-haiku-5-5 | sql | UnknownFunctionException | 5 |
| claude-haiku-5-5 | sql | NullPointerException | 2 |
| claude-haiku-5-5 | sql | SchemaException | 1 |
| claude-haiku-5-5 | sql_script | CommandSQLParsingException | 26 |
| claude-haiku-5-5 | sql_script | UnknownFunctionException | 6 |
| claude-haiku-5-5 | sql_script | SchemaException | 1 |
| claude-haiku-5-5 | sql_script | DuplicatedKeyException | 1 |
| claude-opus-5-5 | pg | 0A000 | 67 |
| claude-opus-5-5 | pg | 42P01 | 17 |
| claude-opus-5-5 | sql | CommandSQLParsingException | 29 |
| claude-opus-5-5 | sql | NullPointerException | 14 |
| claude-opus-5-5 | sql | UnknownFunctionException | 3 |
| claude-opus-5-5 | sql | SchemaException | 1 |
| claude-opus-5-5 | sql_script | CommandSQLParsingException | 33 |
| claude-opus-5-5 | sql_script | UnknownFunctionException | 3 |
| claude-opus-5-5 | sql_script | SchemaException | 1 |
| claude-sonnet-5-5 | pg | 0A000 | 23 |
| claude-sonnet-5-5 | pg | 42P01 | 14 |
| claude-sonnet-5-5 | pg | 42703 | 2 |
| claude-sonnet-5-5 | sql | CommandSQLParsingException | 14 |
| claude-sonnet-5-5 | sql | UnknownFunctionException | 3 |
| claude-sonnet-5-5 | sql | DuplicatedKeyException | 2 |
| claude-sonnet-5-5 | sql_script | CommandSQLParsingException | 16 |
| claude-sonnet-5-5 | sql_script | UnknownFunctionException | 3 |

Stop reasons of the requests (refusal = a safety classifier; max_tokens = a cut answer)

| model | arm | stop reason | requests |
|---|---|---|---|
| claude-haiku-5-5 | pg | end_turn | 105 |
| claude-haiku-5-5 | pg | tool_use | 361 |
| claude-haiku-5-5 | sql | end_turn | 105 |
| claude-haiku-5-5 | sql | tool_use | 369 |
| claude-haiku-5-5 | sql_script | end_turn | 105 |
| claude-haiku-5-5 | sql_script | tool_use | 366 |
| claude-opus-5-5 | pg | end_turn | 105 |
| claude-opus-5-5 | pg | tool_use | 374 |
| claude-opus-5-5 | sql | end_turn | 105 |
| claude-opus-5-5 | sql | tool_use | 374 |
| claude-opus-5-5 | sql_script | end_turn | 105 |
| claude-opus-5-5 | sql_script | tool_use | 345 |
| claude-sonnet-5-5 | pg | end_turn | 105 |
| claude-sonnet-5-5 | pg | tool_use | 246 |
| claude-sonnet-5-5 | sql | end_turn | 105 |
| claude-sonnet-5-5 | sql | tool_use | 279 |
| claude-sonnet-5-5 | sql_script | end_turn | 105 |
| claude-sonnet-5-5 | sql_script | tool_use | 280 |

The sensitivity arm next to the registered pg and sql arms (Wilson 95%; disjoint = the registered reading rule)

| model | sql_script | sql (registered) | pg (registered) | sql_script vs sql disjoint | sql_script vs pg disjoint |
|---|---|---|---|---|---|
| claude-haiku-5-5 | 78/84 = 92.9% [85.3, 96.7] | 77/84 = 91.7% [83.8, 95.9] | 78/84 = 92.9% [85.3, 96.7] | no | no |
| claude-sonnet-5-5 | 80/84 = 95.2% [88.4, 98.1] | 81/84 = 96.4% [90.0, 98.8] | 81/84 = 96.4% [90.0, 98.8] | no | no |
| claude-opus-5-5 | 81/84 = 96.4% [90.0, 98.8] | 69/84 = 82.1% [72.6, 88.9] | 81/84 = 96.4% [90.0, 98.8] | yes | no |
