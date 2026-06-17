#!/bin/bash

set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "[ERROR]: no arguments provided" >&2
    exit 2
fi

cur_dir=$(dirname "$0")

gdep_exe="$cur_dir/gdep"

gdep_out=$("$gdep_exe" dirs)

while IFS= read -r line; do
    set +e # grep exits with 1 if it couldn't find anything
    grep -r "$@" "$line"
    set -e
done <<< "$gdep_out"
