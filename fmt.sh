#!/bin/bash

set -euo pipefail

./gradlew spotlessApply

gofmt -w -s .

echo done

