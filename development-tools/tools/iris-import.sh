#!/bin/sh
set -eu
tool_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java --enable-native-access=ALL-UNNAMED -cp "$tool_dir/lib/*" com.koper.greenmarine.development.IrisImporter "$@"
