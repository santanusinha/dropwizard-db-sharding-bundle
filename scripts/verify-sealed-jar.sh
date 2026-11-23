#!/usr/bin/env bash
# Verifies the packaged jar seals the bundle and DAO packages, and that package-squatting is rejected.
set -euo pipefail
cd "$(dirname "$0")/.."
jar=$(ls target/db-sharding-bundle-*.jar 2>/dev/null | grep -v -e sources -e javadoc | head -1 || true)
[ -n "${jar:-}" ] && [ -f "$jar" ] || { echo "FAIL: no jar in target/; run mvn package first"; exit 1; }
manifest=$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')
for pkg in "io/appform/dropwizard/sharding/" "io/appform/dropwizard/sharding/dao/"; do
  echo "$manifest" | grep -A1 -x "Name: $pkg" | grep -qx "Sealed: true" \
    || { echo "FAIL: $pkg not sealed"; exit 1; }
done
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/src/io/appform/dropwizard/sharding"
cat > "$tmp/src/io/appform/dropwizard/sharding/Squatter.java" <<'EOF'
package io.appform.dropwizard.sharding;
public class Squatter {
    public static void main(String[] a) {
        ShardCalculatorRegistry.all();
        System.out.println("NOT REJECTED");
    }
}
EOF
javac -cp "$jar" -d "$tmp/out" "$tmp/src/io/appform/dropwizard/sharding/Squatter.java"
out=$(java -cp "$jar:$tmp/out" io.appform.dropwizard.sharding.Squatter 2>&1 || true)
if grep -q "sealing violation" <<<"$out"; then
  echo "OK: sealed packages enforced"
else
  echo "FAIL: package squatting was not rejected"; echo "$out"; exit 1
fi
