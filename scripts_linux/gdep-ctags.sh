#!/bin/bash

set -euo pipefail

cur_dir=$(dirname "$0")

gdep_exe="$cur_dir/gdep"

"$gdep_exe" files | ctags -L -
