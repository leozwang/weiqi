#!/bin/bash
set -ex
unsigned_bundle=$1
keystore=$2
output_bundle=$3
jarsigner_path=$JAVA_HOME/bin/jarsigner

# If JAVA_HOME/bin/jarsigner doesn't exist, try just 'jarsigner'
if [ ! -f "$jarsigner_path" ]; then
    jarsigner_path=$(which jarsigner)
fi

cp "$unsigned_bundle" "$output_bundle"
"$jarsigner_path" -keystore "$keystore" -storepass new_password -keypass new_password "$output_bundle" wukong-release
