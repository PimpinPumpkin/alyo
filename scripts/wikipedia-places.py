#!/usr/bin/env python3
"""Builds the table of place names the Piper voices say wrong, from English Wikipedia.

Articles on towns and cities give the local pronunciation when a name is not said the way it is
spelled. This collects those for the United States, Canada, the United Kingdom, Ireland,
Australia and New Zealand, turns each into eSpeak NG's notation, and keeps a name only when

  - eSpeak reads it differently now, so the entry changes something;
  - the two readings are close enough to be the same name, so a pronunciation that belongs to
    another word in the article is not taken;
  - the name is not an ordinary word or one eSpeak already lists, so "Reading" the town cannot
    change how "reading" is said, and a small town cannot rename a famous city it shares a
    spelling with;
  - it is not known otherwise: where CMUdict, a dictionary of general American English, and the
    voice already agree on a word and the place says it another way, the place does not get to
    change it (Gotham, Regina);
  - the articles with that name agree on how it is said. Where North America says it one way
    and the other countries another, each gets an entry of its own: eSpeak can keep one reading
    for American voices and another for the rest.

    scripts/wikipedia-places.py <espeak-ng-data of an unpacked Piper voice>

Needs espeak-ng on the PATH and the eSpeak source that scripts/build-espeak-dict.sh fetches (run
that once first). Downloads are kept in build/wikipedia, so a second run asks Wikipedia for
nothing. Writes pronunciation/places.tsv; run scripts/build-espeak-dict.sh afterwards.

The pronunciations are from Wikipedia and its contributors, under CC BY-SA 4.0.
"""
import collections, json, pathlib, re, subprocess, sys, time, urllib.parse, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "build/wikipedia"
OUT = ROOT / "pronunciation/places.tsv"
ESPEAK_DATA = pathlib.Path(sys.argv[1]).resolve().parent
API = "https://en.wikipedia.org/w/api.php"
AGENT = "alyo-pronunciation-table/0.1 (https://github.com/PimpinPumpkin/alyo)"

SEARCHES = [
    'hastemplate:"IPAc-en" hastemplate:"Infobox settlement"',
    'hastemplate:"IPAc-en" hastemplate:"Infobox UK place"',
    'hastemplate:"IPAc-en" hastemplate:"Infobox Australian place"',
    'hastemplate:"IPAc-en" hastemplate:"Infobox U.S. county"',
]
COUNTRIES = {
    "US": r"United States|\bUSA?\b|U\.S\.",
    "CA": r"Canada|\bCAN\b",
    "GB": r"United Kingdom|England|Scotland|Wales|Northern Ireland|\bUK\b",
    "IE": r"Ireland|\bIRL\b",
    "AU": r"Australia|\bAUS\b",
    "NZ": r"New Zealand|\bNZL?\b",
}

# Wikipedia's sounds for English (Help:IPA/English) in eSpeak's notation.
SOUNDS = {
    "b": "b", "d": "d", "dj": "dj", "dʒ": "dZ", "ð": "D", "f": "f", "ɡ": "g", "g": "g", "h": "h", "hw": "w", "j": "j", "k": "k",
    "l": "l", "lj": "lj", "m": "m", "n": "n", "nj": "nj", "ŋ": "N", "p": "p", "r": "r", "s": "s", "sj": "sj", "ʃ": "S", "t": "t",
    "tj": "tj", "tʃ": "tS", "θ": "T", "θj": "Tj", "v": "v", "w": "w", "z": "z", "zj": "zj", "ʒ": "Z", "x": "x", "ʔ": "",
    "ɑː": "A:", "ɒ": "0", "æ": "a", "aɪ": "aI", "aʊ": "aU", "ɛ": "E", "eɪ": "eI", "ɪ": "I", "iː": "i:", "oʊ": "oU", "ɔː": "O:",
    "ɔɪ": "OI", "ʊ": "U", "uː": "u:", "ʌ": "V", "ə": "@", "i": "i", "u": "u:", "ɜːr": "3:", "ər": "3", "ɑːr": "A@", "ɔːr": "O@",
    "ɛər": "e@", "ɪər": "i@", "ʊər": "U@", "aɪər": "aI@", "aʊər": "aU@", "ɔɪər": "OI@", "ær": "ar", "ɛr": "Er", "ɪr": "Ir",
    "ɒr": "0r", "ʌr": "Vr", "ʊr": "Ur", "əl": "@L", "ən": "@n", "əm": "@m", "ɜː": "3:", "ɔ": "O:", "a": "a", "e": "E", "o": "oU",
    # Spellings of the same sounds that articles also use: older symbols and the keyboard shorthand.
    "ᵻ": "I", "ɹ": "r", "ɚ": "3", "ɝ": "3:", "ɝː": "3:", "ɜr": "3:", "ɑr": "A@", "ɔr": "O@", "ɔɹ": "O@", "ɔər": "O@", "a:r": "A@",
    "juː": "ju:", "jʊər": "jU@", "əʊ": "oU", "ou": "oU", "ei": "eI", "ai": "aI", "ae": "a", "aː": "A:", "ɑ:": "A:", "a:": "A:",
    "A:": "A:", "oː": "O:", "ɔ:": "O:", "O": "O:", "o:": "oU", "i:": "i:", "u:": "u:", "@": "@", "@r": "3", "@n": "@n", "n̩": "@n",
    "I": "I", "E": "E", "U": "U", "V": "V", "Q": "0", "S": "S", "T": "T", "D": "D", "N": "N", "Z": "Z", "tS": "tS", "dZ": "dZ",
    "eI": "eI", "aI": "aI", "aU": "aU", "oU": "oU", "OI": "OI",
}
# Words in an article's title that name the kind of place, which its pronunciation tends to leave out.
KINDS = {"County", "City", "Township", "Town", "Village", "Borough", "Parish", "Island", "Islands", "River", "Lake", "Creek", "Bay",
         "Beach", "Park", "Heights", "Hills", "Springs", "Falls", "Valley", "Mountain", "Mountains", "Mount", "Fort", "Port",
         "Point", "Station", "Junction", "Mission", "East", "West", "North", "South", "New", "Upper", "Lower", "Great", "Little",
         "Old", "Saint", "San", "Santa", "Los", "Las", "El", "La", "Le"}
VOWEL = re.compile(r"[aAeEiIoOuUV03@]")
STRESS = {"ˈ": "'", "'": "'", "ˌ": ",", ",": ","}
SKIP = {"", ".", "-", "‿", "(", ")", "lang", "local", "also", "pron", "US", "UK", "uk", "us", "ipa", "audio"}


def get(params):
    """One answer from Wikipedia. An error or a missing answer is asked for again, more slowly each time."""
    url = API + "?" + urllib.parse.urlencode(params | {"format": "json", "formatversion": "2", "maxlag": "5"})
    for attempt in range(8):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": AGENT}), timeout=60) as reply:
                answer = json.load(reply)
            if "error" not in answer:
                return answer
        except Exception:
            pass
        time.sleep(5 * (attempt + 1))
    raise SystemExit("Wikipedia did not answer: " + url)


def articles():
    """Title and opening section of every article the searches find, from the cache when it is there."""
    CACHE.mkdir(parents=True, exist_ok=True)
    for number, search in enumerate(SEARCHES):
        file = CACHE / f"search-{number}.json"
        if not file.exists():
            # First which articles, then their text fifty at a time: a search that also carries
            # the text gives up partway through a long list.
            ids, more = [], {}
            while more is not None:
                reply = get({"action": "query", "list": "search", "srsearch": search, "srlimit": "50", "srinfo": "", "srprop": ""} | more)
                ids += [str(hit["pageid"]) for hit in reply["query"]["search"]]
                more = reply.get("continue")
                time.sleep(0.5)
            pages = {}
            for start in range(0, len(ids), 50):
                more = {}
                while more is not None:
                    reply = get({"action": "query", "pageids": "|".join(ids[start:start + 50]), "prop": "revisions",
                                 "rvprop": "content", "rvslots": "main", "rvsection": "0"} | more)
                    for page in reply.get("query", {}).get("pages", []):
                        revisions = page.get("revisions")
                        if revisions:
                            pages[page["title"]] = revisions[0]["slots"]["main"]["content"]
                    more = reply.get("continue")
                print(f"  {search[-30:]}: {len(pages)} of {len(ids)}", file=sys.stderr)
                time.sleep(0.5)
            file.write_text(json.dumps(pages))
        yield from json.loads(file.read_text()).items()


def country(text):
    fields = " ".join(re.findall(r"\|\s*(?:subdivision_name\d*|country|state)\s*=\s*([^\n]*)", text))
    if "Infobox Australian place" in text:
        return "AU"
    if "Infobox UK place" in text:
        return "GB"
    if "Infobox U.S. county" in text:
        return "US"
    for code, pattern in COUNTRIES.items():
        if re.search(pattern, fields):
            return code
    return None


def to_espeak(template):
    """An {{IPAc-en}} template's sounds in eSpeak's notation, or None if any of it is not understood."""
    out, pending, words = [], "", 1
    for part in re.sub(r"<!--.*?-->", "", template).split("|")[1:]:
        part = part.strip()
        # A second way of saying the name follows: the first is the one to keep.
        if part == ",_" and out:
            break
        if "=" in part or part in SKIP:
            continue
        part = part.rstrip("-")
        if part in ("_", " "):
            words += 1
            continue
        if part in STRESS:
            pending = STRESS[part]
            continue
        sound = SOUNDS.get(part)
        if sound is None:
            return None
        # eSpeak wants the stress mark right before the vowel, Wikipedia puts it before the syllable.
        if pending and VOWEL.match(sound):
            out.append(pending)
            pending = ""
        out.append(sound)
    text = "".join(out)
    return (text, words) if VOWEL.search(text) and "'" in text or len(VOWEL.findall(text)) == 1 else None


ARPABET = {
    "AA": "A:", "AE": "a", "AH": "V", "AO": "O:", "AW": "aU", "AY": "aI", "EH": "E", "ER": "3:", "EY": "eI", "IH": "I", "IY": "i:",
    "OW": "oU", "OY": "OI", "UH": "U", "UW": "u:", "B": "b", "CH": "tS", "D": "d", "DH": "D", "F": "f", "G": "g", "HH": "h",
    "JH": "dZ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "N", "P": "p", "R": "r", "S": "s", "SH": "S", "T": "t", "TH": "T",
    "V": "v", "W": "w", "Y": "j", "Z": "z", "ZH": "Z",
}


def general_readings():
    """How CMUdict, a dictionary of general American English, says each word it has, in eSpeak's notation."""
    file = CACHE / "cmudict.dict"
    if not file.exists():
        request = urllib.request.Request("https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict", headers={"User-Agent": AGENT})
        file.write_bytes(urllib.request.urlopen(request, timeout=60).read())
    readings = collections.defaultdict(list)
    for line in file.read_text().splitlines():
        word, *phones = line.split("#")[0].split()
        out = []
        for phone in phones:
            base, stress = (phone[:-1], phone[-1]) if phone[-1].isdigit() else (phone, "")
            sound = {"AH": "@", "ER": "3"}.get(base, ARPABET[base]) if stress == "0" else ARPABET[base]
            out.append({"1": "'", "2": ","}.get(stress, "") + sound)
        readings[re.sub(r"\(\d+\)$", "", word)].append("".join(out))
    return readings


def loose(spoken):
    """[broad] with stress and the weak vowels evened out as well, for telling whether two readings are one."""
    return re.sub(r"[ɪɛ]", "ə", broad(spoken).replace("ˈ", ""))


def known_otherwise(name, target, now, voice, general):
    """
    True when the general dictionary and the voice already agree on how [name] is said and the
    place says it some other way. The dictionary alone is not enough: many of its names are
    guesses, and where the voice disagrees with it too there is nothing settled to protect.
    """
    for reading in general.get(name.lower(), []):
        usual = loose(ipa(f"[[{reading}]]", voice))
        if distance(usual, loose(now)) <= 1 and distance(usual, loose(target)) >= 2:
            return True
    return False


def ipa(text, voice="en-us"):
    return subprocess.run(["espeak-ng", f"--path={ESPEAK_DATA}", "-v", voice, "-q", "--ipa", text], capture_output=True, text=True).stdout.strip().replace("\n", " ")


def broad(spoken):
    """Sounds with the differences that do not matter taken out: secondary stress, vowel shades, flaps."""
    for a, b in (("ˌ", ""), ("ᵻ", "ɪ"), ("ɐ", "ə"), ("ɚ", "əɹ"), ("ɾ", "t"), ("ɜː", "əɹ"), ("ː", ""), (" ", ""), ("ɔ", "ɑ"), ("ʔ", "t")):
        spoken = spoken.replace(a, b)
    return spoken


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (x != y))
    return row[-1]


def main():
    listed = set()
    for line in (ROOT / "build/espeak/src/dictsource/en_list").read_text(errors="ignore").splitlines():
        if line.strip() and not line.startswith("//"):
            listed.add(line.split()[0].strip("()").lower())
    ordinary = {w.strip() for w in open("/usr/share/dict/words") if w.strip().islower()}
    by_hand = {line.split("\t")[0].lower() for line in (ROOT / "app/src/main/assets/pronunciation/en.tsv").read_text().splitlines() if "\t" in line}

    found = collections.defaultdict(dict)  # name -> sounds -> (article, country)
    seen = set()
    for title, text in articles():
        if title in seen:
            continue
        seen.add(title)
        where = country(text)
        template = re.search(r"\{\{IPAc-en\|[^{}]*\}\}", text)
        if not where or not template:
            continue
        name = re.sub(r"\s*\(.*\)$", "", title.split(",")[0]).strip()
        if not re.fullmatch(r"[A-Za-z]+(?: [A-Za-z]+){0,3}", name):
            continue
        heard = to_espeak(template.group(0)[2:-2])
        if not heard:
            continue
        sounds, spoken_words = heard
        # "Allegan County" gives the sounds of "Allegan" alone: when fewer words are sounded than
        # written, the words that say what kind of place it is are taken off until the two match.
        words = name.split()
        while len(words) > spoken_words and words[-1] in KINDS:
            words.pop()
        while len(words) > spoken_words and words[0] in KINDS:
            words.pop(0)
        if len(words) != spoken_words or len(words) > 3:
            continue
        found[" ".join(words)].setdefault(sounds, (title, where))

    counts, rows, rejected = collections.Counter(), [], []
    general = general_readings()

    def consider(name, readings, voice, who):
        """Adds the row for [name] as the voices in [who] should say it, if the articles in [readings] agree and it is a fix."""
        if not readings:
            return
        wanted = {sounds: broad(ipa(f"[[{sounds}]]", voice)) for sounds in readings}
        if len(set(wanted.values())) > 1:
            counts["articles disagree"] += 1
            return
        sounds, target = next(iter(wanted.items()))
        now = broad(ipa(name, voice))
        if target == now:
            counts["already said right"] += 1
        elif distance(target, now) > 0.75 * max(len(target), len(now)):
            counts["too different to be the same name"] += 1
            rejected.append(f"{name}\t{target}\t{now}\t{next(iter(readings.values()))[0]}")
        elif " " not in name and known_otherwise(name, target, now, voice, general):
            # A word people know apart from this place, said another way: Gotham, Regina. The
            # place does not get to change it.
            counts["known otherwise as a general word or name"] += 1
            rejected.append(f"{name}\t{target}\tgeneral: {general[name.lower()][0]}\t{next(iter(readings.values()))[0]}")
        else:
            title, where = readings[sounds]
            rows.append((name, sounds, who, where, title))
            counts["kept"] += 1

    for name, readings in sorted(found.items()):
        lower = name.lower()
        if lower in listed or lower in by_hand:
            counts["eSpeak or the hand-written table has it"] += 1
            continue
        if lower in ordinary:
            counts["also an ordinary word"] += 1
            continue
        american = {sounds: source for sounds, source in readings.items() if source[1] in ("US", "CA")}
        other = {sounds: source for sounds, source in readings.items() if source[1] not in ("US", "CA")}
        same = len({broad(ipa(f"[[{sounds}]]")) for sounds in readings}) == 1
        if same or not american or not other:
            consider(name, readings, "en-us", "all")
        else:
            # Said one way in North America and another elsewhere: each accent gets its own.
            counts["split by region"] += 1
            consider(name, american, "en-us", "american")
            consider(name, other, "en-gb", "british")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w") as out:
        out.write("# Place names the Piper voices would say wrong, with the local pronunciation in eSpeak NG's\n")
        out.write("# notation. Made by scripts/wikipedia-places.py; do not edit by hand, fix a name in\n")
        out.write("# app/src/main/assets/pronunciation/en.tsv instead, which wins over this file.\n#\n")
        out.write("# The pronunciations are from the English Wikipedia articles named in the last column, by\n")
        out.write("# their contributors, under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).\n")
        out.write("#\n# The third column says which voices an entry is for: a name said one way in North America and\n")
        out.write("# another elsewhere has a line for American voices and a line for the rest.\n")
        out.write("#\n# name\tsounds\tvoices\tcountry\tarticle\n")
        for row in rows:
            out.write("\t".join(row) + "\n")
    # For looking over by hand: names left out because their sounds were far from the spelling or from general use.
    (CACHE / "left-out.tsv").write_text("\n".join(rejected) + "\n")
    for reason, n in counts.most_common():
        print(f"{n:6d}  {reason}")
    print("by country:", dict(collections.Counter(row[3] for row in rows)))


main()
