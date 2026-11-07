#!/usr/bin/env bash
#
# Downloads the full licence texts the "Open-source licences" screens can show (iOS, Android, desktop use the same file names).
#
#   ./fetch-license-texts.sh <output folder>
#
# Then copy that folder as:
#   iOS      add it to the DoubleClips target as a FOLDER REFERENCE named `licenses` (blue folder in Xcode; Copy items if needed)
#   Android  src/main/assets/licenses/
#   desktop  src/main/resources/licenses/
#
# The screens work without these files (they show the notice and the link); bundling them makes the app carry the texts itself.
set -euo pipefail
OUT="${1:?usage: $0 <output folder>}"
mkdir -p "$OUT"
get() { echo "  $2"; curl -fsSL "$1" -o "$OUT/$2.txt"; }
echo "Downloading licence texts into $OUT"
get https://www.gnu.org/licenses/lgpl-3.0.txt LGPL-3.0
get https://www.gnu.org/licenses/gpl-3.0.txt GPL-3.0
get https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt GPL-2.0
get https://www.apache.org/licenses/LICENSE-2.0.txt Apache-2.0
get https://raw.githubusercontent.com/spdx/license-list-data/main/text/MIT.txt MIT
get https://raw.githubusercontent.com/spdx/license-list-data/main/text/BSD-2-Clause.txt BSD-2-Clause
get https://raw.githubusercontent.com/spdx/license-list-data/main/text/BSD-3-Clause.txt BSD-3-Clause
echo "Done."
