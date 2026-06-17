#!/bin/bash

set -euo pipefail

script_dir="$(dirname "$0")"

pushd "$script_dir"

cleanup() {
    popd
}

trap cleanup EXIT

go build -C go_wrapper -o gdep -gcflags="-e"

./gradlew build -x spotlessJavaCheck

rm -rf out
mkdir out

cp ./build/libs/gdep.jar ./out/gdep.jar
cp ./go_wrapper/gdep ./out/gdep

if [[ "${1-empty}" == "--copy-scripts" ]]; then
    cp ./scripts_linux/*.sh ./out/
fi

echo done

