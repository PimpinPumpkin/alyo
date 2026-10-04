#!/bin/sh
# Builds the English dictionary the Piper voices read with, from eSpeak NG's own word list plus
# the entries in app/src/main/assets/pronunciation/en.tsv that give exact sounds (third column).
# The app puts the result in place of the dictionary inside each English Piper voice.
#
#   scripts/build-espeak-dict.sh <espeak-ng-data>
#
# <espeak-ng-data> is that folder from any unpacked Piper voice: the dictionary has to be built
# against the same sound tables the voices carry. Needs espeak-ng (brew install espeak-ng, apt
# install espeak-ng) and git. Run it after changing a third column, and commit the two files it
# writes under app/src/main/assets/pronunciation/.
set -eu
cd "$(dirname "$0")/.."
DATA="$(cd "${1:?give the espeak-ng-data folder of an unpacked Piper voice}" && pwd)"
TABLE="$PWD/app/src/main/assets/pronunciation/en.tsv"
OUT="$PWD/app/src/main/assets/pronunciation"
WORK="$PWD/build/espeak"
# The source the voices' dictionary was built from: compiled untouched it gives the same file.
SOURCE_REPO=https://github.com/rhasspy/espeak-ng
SOURCE_COMMIT=8593723f10cfd9befd50de447f14bf0a9d2a14a4

mkdir -p "$WORK"
if [ ! -d "$WORK/src" ]; then
    git init -q "$WORK/src"
    git -C "$WORK/src" fetch -q --depth 1 "$SOURCE_REPO" "$SOURCE_COMMIT"
    git -C "$WORK/src" checkout -q FETCH_HEAD
fi
rm -rf "$WORK/data" && mkdir -p "$WORK/data" && cp -R "$DATA" "$WORK/data/espeak-ng-data"
sum() { shasum -a 256 "$1" | cut -c1-16; }
BEFORE="$(sum "$WORK/data/espeak-ng-data/en_dict")"

cd "$WORK/src/dictsource"
rm -f en_extra
espeak-ng --path="$WORK/data" --compile=en >/dev/null 2>&1
if [ "$(sum "$WORK/data/espeak-ng-data/en_dict")" != "$BEFORE" ]; then
    echo "The eSpeak source no longer reproduces the voices' dictionary; other words could change. Stopping." >&2
    exit 1
fi

# word<tab>spelling<tab>sounds  ->  an eSpeak list line. Names of several words go in brackets,
# and a full stop is a word of its own there.
grep -v '^#' "$TABLE" | awk -F'\t' 'NF >= 3 && $3 != "" {
    word = tolower($1); gsub(/\./, " .", word); gsub(/-/, " ", word)
    if (word ~ / /) word = "(" word ")"
    print word "\t" $3
}' > en_extra
espeak-ng --path="$WORK/data" --compile=en 2>&1 | grep -i -E "error|line" && { echo "eSpeak rejected an entry." >&2; exit 1; }

cp "$WORK/data/espeak-ng-data/en_dict" "$OUT/en_dict"
sum "$OUT/en_dict" > "$OUT/en_dict.version"
echo "Built $(wc -l < en_extra | tr -d ' ') entries into the dictionary. How the voices now read them:"
grep -v '^#' "$TABLE" | awk -F'\t' 'NF >= 3 && $3 != "" { print $1 }' | while read -r word; do
    printf '  %-22s %s\n' "$word" "$(espeak-ng --path="$WORK/data" -v en-us -q --ipa "$word")"
done
