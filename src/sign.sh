#!/bin/bash
set -ex
unsigned_apk=$1
keystore=$2
output_apk=$3
apksigner=$4
zipalign=$5

# If keystore is not provided as an argument, use the default location
if [ -z "$keystore" ]; then
  keystore="weiqi/src/keystore/release.keystore"
fi

$zipalign -p -f -v 16384 $unsigned_apk $output_apk

$apksigner sign --ks "$keystore" --ks-key-alias weiqi-release --ks-pass pass:new_password --key-pass pass:new_password "$output_apk"
