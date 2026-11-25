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

# Fork only, the last step of the jobs of the prototype's workflows: counts the files of the local Maven repository by the id of
# the repository Maven recorded for each one (the _remote.repositories files), so that a run shows whether its dependencies
# came from the mirror (google-central) or from somewhere else. An empty id is a file the build installed itself.
set -euo pipefail

[ "${GITHUB_ACTIONS:-}" = true ] || { echo "runs in GitHub Actions only" >&2; exit 1; }
repository="$HOME/.m2/repository"
[ -d "$repository" ] || { echo "no local Maven repository: nothing was resolved"; exit 0; }

{
  echo "### Where the Maven files of this job came from"
  echo
  echo "| repository id | files |"
  echo "| --- | ---: |"
  find "$repository" -name _remote.repositories -exec cat {} + |
    sed -n 's/^[^#].*>\(.*\)=$/\1/p' |
    awk '{ id = $0 == "" ? "installed by this build" : $0; n[id]++ } END { for (id in n) printf "| %s | %d |\n", id, n[id] }' |
    sort
  echo
  find "$HOME/.m2/wrapper/dists" -name 'maven-resolver-impl-*.jar' -printf 'Resolver: `%f`\n' || true
} | tee -a "$GITHUB_STEP_SUMMARY"

# the artifacts of this tree are built by every run: keep them out of the cache
rm -rf "$repository/com/arcadedb"
