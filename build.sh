#!/usr/bin/env bash

set -xeuo pipefail

srcdir="$PWD"

source ./PKGBUILD

cleanup_dir() {
  cd  "$srcdir"
  rm -rf intellij-community
  rm -rf idea-android
  rm -rf junit-3.8.1.jar
}

trap "cleanup_dir" ERR

git_shallow_checkout() {
  local repo="$1"
  local tag="$2"
  local target_dir="$3"

  [[ ! -d "$target_dir" ]] && mkdir "$target_dir"
  pushd "$target_dir"
  [[ ! -d .git ]] && { git init; git remote add origin "$repo"; }
  git fetch --depth 1 origin tag "$tag"
  git checkout "$tag"
  popd
}

download_sources() {
  git_shallow_checkout https://github.com/JetBrains/intellij-community.git "idea/${_build}" intellij-community

  pushd intellij-community
  git_shallow_checkout https://github.com/didot/idea-android-mirror "idea/${_build}" android
  popd
}

unpack_files() {
  download_sources

  curl -fsSL -O https://repo1.maven.org/maven2/junit/junit/3.8.1/junit-3.8.1.jar
}

unset LESS LESS_TERMCAP_mb ENV LESS_TERMCAP_md ENV LESS_TERMCAP_me ENV LESS_TERMCAP_se ENV LESS_TERMCAP_so ENV LESS_TERMCAP_ue ENV LESS_TERMCAP_us

rm -rf intellij-community/out
rm -rf intellij-community/build/jps-bootstrap-work
unpack_files

prepare
cd "$srcdir"

build
cd "$srcdir"
