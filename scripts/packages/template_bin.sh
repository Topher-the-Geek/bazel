#!/bin/bash -e

# Copyright 2015 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Bazel self-extractable installer

# Installation and etc prefix can be overriden from command line
install_prefix=${1:-"/usr/local"}
bazelrc=${2:-"/etc/bazel.bazelrc"}

progname="$0"

echo "Bazel installer"
echo "---------------"
echo
cat <<'EOF'
%release_info%
EOF

function usage() {
  echo "Usage: $progname [options]" >&2
  echo "Options are:" >&2
  echo "  --prefix=/some/path set the prefix path (default=/usr/local)." >&2
  echo "  --bazelrc= set the bazelrc path (default=/etc/bazel.bazelrc)." >&2
  echo "  --bin= set the binary folder path (default=%prefix%/bin)." >&2
  echo "  --base= set the base install path (default=%prefix%/lib/bazel)." >&2
  echo "  --user configure for user install, expands to" >&2
  echo '           `--bin=$HOME/bin --base=$HOME/.bazel --bazelrc=$HOME/.bazelrc`.' >&2
  exit 1
}

prefix="/usr/local"
bin="%prefix%/bin"
base="%prefix%/lib/bazel"
bazelrc="/etc/bazel.bazelrc"

for opt in "${@}"; do
  case $opt in
    --prefix=*)
      prefix="$(echo "$opt" | cut -d '=' -f 2-)"
      ;;
    --bazelrc=*)
      bazelrc="$(echo "$opt" | cut -d '=' -f 2-)"
      ;;
    --bin=*)
      bin="$(echo "$opt" | cut -d '=' -f 2-)"
      ;;
    --base=*)
      base="$(echo "$opt" | cut -d '=' -f 2-)"
      ;;
    --user)
      bin="$HOME/bin"
      base="$HOME/.bazel"
      bazelrc="$HOME/.bazelrc"
      ;;
    *)
      usage
      ;;
  esac
done

bin="${bin//%prefix%/${prefix}}"
base="${base//%prefix%/${prefix}}"
bazelrc="${bazelrc//%prefix%/${prefix}}"

function test_write() {
  local file="$1"
  while [ "$file" != "/" ] && [ -n "${file}" ] && [ ! -e "$file" ]; do
    file="$(dirname "${file}")"
  done
  [ -w "${file}" ] || {
    echo >&2
    echo "The Bazel installer must have write access to $1!" >&2
    echo >&2
    usage
  }
}

test_write "${bin}"
test_write "${base}"
test_write "${bazelrc}"

echo -n "Uncompressing."
rm -fr "${bin}" "${base}" "${bazelrc}"

mkdir -p ${bin} ${base} ${base}/bin ${base}/etc ${base}/base_workspace
echo -n .

unzip -q "${BASH_SOURCE[0]}" bazel -d "${base}/bin"
echo -n .
chmod 0755 "${base}/bin/bazel"
unzip -q "${BASH_SOURCE[0]}" -x bazel -d "${base}/base_workspace"
echo -n .
cat >"${base}/etc/bazel.bazelrc" <<EO
build --package_path %workspace%:${base}/base_workspace"
fetch --package_path %workspace%:${base}/base_workspace"
query --package_path %workspace%:${base}/base_workspace"
EO
echo -n .
chmod -R og-w "${base}"
chmod -R og+rX "${base}"
chmod -R u+rwX "${base}"
echo -n .

ln -s "${base}/bin/bazel" "${bin}/bazel"
echo -n .

if [ -f "${bazelrc}" ]; then
  echo
  echo "${bazelrc} already exists, ignoring. It is either a link to"
  echo "${base}/etc/bazel.bazelrc or that it's importing that file with:"
  echo "  import ${base}/etc/bazel.bazelrc"
else
  ln -s "${base}/etc/bazel.bazelrc" "${bazelrc}"
  echo .
fi

exit 0
