#!/bin/sh
# Shows how the Piper voices read each word in a pronunciation table and the spelling given for
# it, in phonetic symbols, so a new entry can be checked without a phone. Needs espeak-ng
# (brew install espeak-ng, apt install espeak-ng).
#   scripts/pronunciation-check.sh            the whole English table
#   scripts/pronunciation-check.sh Tucson     only lines with that word
cd "$(dirname "$0")/.."
grep -v '^#' app/src/main/assets/pronunciation/en.tsv | grep -i "${1:-.}" | while IFS="$(printf '\t')" read -r word spelling; do
    [ -n "$word" ] || continue
    printf '%-20s %-18s -> %-20s %s\n' "$word" "$(espeak-ng -v en-us -q --ipa "$word")" "$spelling" "$(espeak-ng -v en-us -q --ipa "$spelling")"
done
