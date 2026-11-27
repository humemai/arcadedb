#!/usr/bin/env bash
# The PostgreSQL 18 reference server of the pg13 study:  pg_ref_server.sh start|stop <OUT label>
# One container named pg13-ref-<label>, started on the two cores of the lane (HUMEM_HOST_CPUS), 8g memory, data in a RAM-backed
# volume (nothing root-owned is left on the disk), bound to 127.0.0.1 only. Settings are the lane's own PostgreSQL arm: the image's
# defaults and synchronous_commit=off (experiments/runner.py BACKENDS["postgres"]). Only the container of this label is touched.
# The container tool comes from PG13_CT (default: the one the benchmark uses).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
set -a
# shellcheck source=config.env
. "$HERE/config.env"
set +a
CT="${PG13_CT:-docker}"
NAME="pg13-ref-${2:?label}"
case "${1:-}" in
start)
    "$CT" rm -f "$NAME" >/dev/null 2>&1 || true # a leftover of this label from an earlier try
    "$CT" run -d --name "$NAME" --cpuset-cpus "${HUMEM_HOST_CPUS:?HUMEM_HOST_CPUS is not set}" --memory "$PG13_PG_MEM" --shm-size 1g \
        --tmpfs /var/lib/postgresql:rw,size=6g -p "127.0.0.1:${PG13_PG_PORT}:5432" \
        -e POSTGRES_PASSWORD=CHANGE_ME -e POSTGRES_DB=bench "$PG13_PG_IMAGE" -c synchronous_commit=off
    "$CT" inspect --format '{{.Image}} {{.HostConfig.CpusetCpus}} {{.HostConfig.Memory}}' "$NAME"
    ;;
stop)
    "$CT" rm -f "$NAME"
    ;;
*)
    echo "usage: $0 start|stop <OUT label>" >&2
    exit 2
    ;;
esac
