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

# Fork only (humemai/arcadedb, fork issue #198): writes the list of what PostgreSQL 18 has, from the system catalog of a server
# that this script starts in a container: functions, operators, implicit casts, and types of the schema pg_catalog. The
# postgresql language reads the list to answer 0A000, not 42883, for a function or operator that it lacks. The file is
# committed; CI runs this script and fails when the committed file is not what a server gives. It needs a container runtime.
#
#   .github/scripts/pg_catalog_list.sh
set -euo pipefail

version=18.7
image="postgres:${version}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/engine/src/main/resources/com/arcadedb/query/postgresql/eval/postgresql-18-catalog.tsv"

container="$(docker run -d --rm -e POSTGRES_HOST_AUTH_METHOD=trust "$image")"
trap 'docker rm -f "$container" >/dev/null' EXIT

# The server that initdb starts listens on its socket only, so an answer over TCP is the one of the real server.
ready=no
for _ in $(seq 1 120); do
  if docker exec "$container" pg_isready -q -h 127.0.0.1 -U postgres; then
    ready=yes
    break
  fi
  sleep 1
done
if [ "$ready" != yes ]; then
  echo "the PostgreSQL server of $image did not become ready" >&2
  exit 1
fi

query() {
  docker exec -i "$container" psql -h 127.0.0.1 -U postgres -XAtq -v ON_ERROR_STOP=1 -f -
}

server="$(echo 'SHOW server_version;' | query)"
if [ "${server%% *}" != "$version" ]; then
  echo "the server of $image is version $server, not $version" >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
{
  # REUSE-IgnoreStart
  cat <<HEADER
# What PostgreSQL ${version} has, from the system catalog of a server (pg_proc, pg_operator, pg_cast, and pg_type, the schema
# pg_catalog), written by .github/scripts/pg_catalog_list.sh. Do not edit. Fields are separated by tabs, "-" is none:
#
#   cast      source type, target type                                       an implicit cast
#   function  name, argument types, arguments with a default, element type of VARIADIC
#   operator  name, left type, right type
#   type      name, category
#
# SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
# SPDX-FileCopyrightText: 1996-2026 PostgreSQL Global Development Group
# SPDX-FileCopyrightText: 1994 The Regents of the University of California
# SPDX-License-Identifier: Apache-2.0 AND PostgreSQL
#
# The list is data of PostgreSQL, under the PostgreSQL License:
#
# Portions Copyright (c) 1996-2026, PostgreSQL Global Development Group
# Portions Copyright (c) 1994, The Regents of the University of California
#
# Permission to use, copy, modify, and distribute this software and its
# documentation for any purpose, without fee, and without a written agreement
# is hereby granted, provided that the above copyright notice and this
# paragraph and the following two paragraphs appear in all copies.
#
# IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR
# DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING
# LOST PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS
# DOCUMENTATION, EVEN IF THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE
# POSSIBILITY OF SUCH DAMAGE.
#
# THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES,
# INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
# AND FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
# ON AN "AS IS" BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATIONS TO
# PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
#
HEADER
  # REUSE-IgnoreEnd
  query <<'SQL'
WITH line(kind, a, b, c, d) AS (
  SELECT 'cast', s.typname::text, t.typname::text, NULL, NULL
    FROM pg_catalog.pg_cast c
    JOIN pg_catalog.pg_type s ON s.oid = c.castsource
    JOIN pg_catalog.pg_type t ON t.oid = c.casttarget
   WHERE c.castcontext = 'i'
     AND s.typnamespace = 'pg_catalog'::regnamespace AND t.typnamespace = 'pg_catalog'::regnamespace
  UNION ALL
  SELECT 'function', p.proname::text,
         coalesce((SELECT string_agg(t.typname, ',' ORDER BY x.n)
                     FROM unnest(p.proargtypes::oid[]) WITH ORDINALITY x(arg, n)
                     JOIN pg_catalog.pg_type t ON t.oid = x.arg), '-'),
         p.pronargdefaults::text,
         coalesce((SELECT t.typname::text FROM pg_catalog.pg_type t WHERE t.oid = p.provariadic), '-')
    FROM pg_catalog.pg_proc p
   WHERE p.pronamespace = 'pg_catalog'::regnamespace
  UNION ALL
  SELECT 'operator', o.oprname::text,
         coalesce((SELECT t.typname::text FROM pg_catalog.pg_type t WHERE t.oid = o.oprleft), '-'),
         (SELECT t.typname::text FROM pg_catalog.pg_type t WHERE t.oid = o.oprright),
         NULL
    FROM pg_catalog.pg_operator o
   WHERE o.oprnamespace = 'pg_catalog'::regnamespace
  UNION ALL
  SELECT 'type', t.typname::text, t.typcategory::text, NULL, NULL
    FROM pg_catalog.pg_type t
   WHERE t.typnamespace = 'pg_catalog'::regnamespace AND t.typtype IN ('b', 'r', 'm') AND t.typcategory <> 'A'
)
SELECT concat_ws(E'\t', kind, a, b, c, d)
  FROM line
 ORDER BY kind COLLATE "C", a COLLATE "C", b COLLATE "C", c COLLATE "C", d COLLATE "C";
SQL
} > "$out.new"
mv "$out.new" "$out"
echo "wrote $out: $(grep -vc '^#' "$out") entries"
