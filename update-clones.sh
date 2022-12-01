#!/usr/bin/env bash

set -euo pipefail

git clone --bare git://git.jetbrains.org/idea/android.git idea-android

pushd idea-android
git filter-repo --invert-paths --path-glob '*/testData/*'
{
  for t in $(git tag); do
    if [[ $t != idea/222* ]] && [[ $t != idea/223* ]]; then
      echo "$t"
    fi
  done
} | xargs git tag -d
git filter-repo --replace-refs delete-no-add
env \
  GIT_CURL_VERBOSE=1 \
  GIT_TRACE=1 \
  git push --mirror https://github.com/didot/idea-android-mirror.git
popd
