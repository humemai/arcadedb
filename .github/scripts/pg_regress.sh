#!/usr/bin/env bash
#
# Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
# SPDX-License-Identifier: Apache-2.0
#

# Fork only (humemai/arcadedb, fork issue #119): the one command that runs PostgreSQL's regression files and writes one verdict
# for each statement. The rules are in docs/postgresql-regression-rules.md. It needs git, a JDK 21 and a container runtime.
#
#   .github/scripts/pg_regress.sh                       the eight files of the syntax corpus, on ArcadeDB
#   .github/scripts/pg_regress.sh case,join             these files
#   .github/scripts/pg_regress.sh all                   every file
#   .github/scripts/pg_regress.sh --subject postgresql  PostgreSQL on both sides: only passed and out of scope may come out
#   .github/scripts/pg_regress.sh --self-test           the self-tests of the harness first
#   .github/scripts/pg_regress.sh --skip-build          when the modules are built already
#
# PostgreSQL's files are not part of this repository: they are fetched here at one tag, into e2e/target (or into
# PGREGRESS_CHECKOUT). The results are in e2e/target/pgregress/<subject>/.
set -euo pipefail

TAG=REL_18_7
COMMIT=f5c41f6681c910b3f69951bf6fb97c6d74605d25

usage() {
  echo "usage: pg_regress.sh [--subject arcadedb|postgresql] [--self-test] [--skip-build] [file,file,...|all]" >&2
  exit 2
}

subject=arcadedb
tests=PgRegressIT
build=yes
files=
while [ $# -gt 0 ]; do
  case "$1" in
    --subject)
      [ $# -ge 2 ] || usage
      subject="$2"
      shift 2
      ;;
    --self-test)
      tests=PgRegressSelfIT,PgRegressIT
      shift
      ;;
    --skip-build)
      build=no
      shift
      ;;
    -*) usage ;;
    *)
      files="$1"
      shift
      ;;
  esac
done

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
checkout="${PGREGRESS_CHECKOUT:-$root/e2e/target/pgregress-postgres}"

if [ ! -d "$checkout/src/test/regress/sql" ]; then
  git clone --quiet --depth 1 --branch "$TAG" --filter=blob:none --sparse https://github.com/postgres/postgres.git "$checkout"
  git -C "$checkout" sparse-checkout set src/test/regress
fi
fetched="$(git -C "$checkout" rev-parse HEAD)"
if [ "$fetched" != "$COMMIT" ]; then
  echo "the checkout in $checkout is at $fetched, and $TAG is $COMMIT" >&2
  exit 1
fi

# The e2e module needs the engine test-jar, a package-phase artifact: build what it depends on, without any test.
if [ "$build" = yes ]; then
  ./mvnw install -DskipTests --batch-mode --errors --show-version -pl e2e -am
fi

./mvnw verify -Pintegration --batch-mode --errors --show-version -pl e2e \
  "-Dit.test=$tests" \
  "-Dpgregress.dir=$checkout/src/test/regress" \
  "-Dpgregress.files=$files" \
  "-Dpgregress.subject=$subject" \
  "-Dpgregress.tag=$TAG, commit $COMMIT" \
  "-Dpgregress.engine=$(git rev-parse HEAD)" \
  "-Dpgregress.run=${PGREGRESS_RUN_URL:-none}"
