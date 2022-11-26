#!/opt/homebrew/bin/bash

set -xeuo pipefail

srcdir="$PWD"

source ./PKGBUILD

cleanup_dir() {
  cd  "$srcdir"
  rm -rf intellij-community
  rm -rf idea-android
  rm -rf idea-adt-tools-base
  rm -rf junit-3.8.1.jar
}

trap "cleanup_dir" ERR

download_sources() {
  git clone --depth=1 --branch=idea/${_build} https://github.com/JetBrains/intellij-community.git
  git clone --depth=1 --branch=idea/${_build} https://github.com/didot/idea-android-mirror.git idea-android
  # adt-tools-base is not getting updated with new tags
  git clone --depth=1 --branch=idea/201.7223.18 https://github.com/didot/adt-tools-base.git idea-adt-tools-base

  curl -fsSL -O https://repo1.maven.org/maven2/junit/junit/3.8.1/junit-3.8.1.jar
}

unpack_files() {
  if [[ -d intellij-community ]]; then
    command rm -rf intellij-community/
  fi

  if [[ -f intellij-community.tar ]]; then
    atool -x intellij-community.tar 2>/dev/null
    cp -a MacDmgBuilder.groovy          intellij-community/platform/build-scripts/groovy/org/jetbrains/intellij/build/impl/MacDmgBuilder.groovy
    cp -a MacDistributionBuilder.groovy intellij-community/platform/build-scripts/groovy/org/jetbrains/intellij/build/impl/MacDistributionBuilder.groovy
    cp -a signapp.sh                    intellij-community/platform/build-scripts/tools/mac/scripts/signapp.sh
  else
    download_sources
  fi
}

unset LESS LESS_TERMCAP_mb ENV LESS_TERMCAP_md ENV LESS_TERMCAP_me ENV LESS_TERMCAP_se ENV LESS_TERMCAP_so ENV LESS_TERMCAP_ue ENV LESS_TERMCAP_us

unpack_files

prepare
cd "$srcdir"

build
cd "$srcdir"
