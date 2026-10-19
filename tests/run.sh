#!/data/data/com.termux/files/usr/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/src/com/cofreseguro"
OUT="$ROOT/build/tests"

rm -rf "$OUT"
mkdir -p "$OUT"
javac --release 8 -encoding UTF-8 -d "$OUT" \
    "$SRC/BackupCrypto.java" \
    "$SRC/PasswordGenerator.java" \
    "$SRC/PasswordStrength.java" \
    "$SRC/CsvImport.java" \
    "$ROOT/tests/com/cofreseguro/PureLogicTest.java"
java -cp "$OUT" com.cofreseguro.PureLogicTest
