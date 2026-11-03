"""Per-language word data for check.py: negation, negated forms, word boundaries and Latin loans.

Each check is designed per language and script so it is neither blind nor noisy:

  - Scripts that separate words with spaces are tokenized into words (letters plus their combining
    marks, so Devanagari and Bengali vowel signs stay inside the word). Scripts written without
    spaces (Han, kana, Thai, ...) are scanned with a curated word lexicon instead.
  - NEGATION lists whole negation words per language, plus prefixes that only *may* negate
    ("in-" in "información" does not). Those guesses warn; they never pass a safety key alone.
  - NATIVE_PREFIXES are negating prefixes a language uses as its standard negation of an adjective
    or adverb (Russian "неизвестно", Polish "nieznany", German "unbekannt"), with the ordinary
    words that only look like them ("несколько", "bezpieczny", "unter"). NATIVE_STEMS are the
    negated words themselves where a prefix alone is too ambiguous to trust (Spanish "imposible",
    "desconocido"; Turkish "geçersiz").
  - UNSPACED_NEGATION is the word lexicon for zh and ja: negation words, and the ordinary words that
    contain a negation character (级别, 识别, 无线, 非常, 少ない, 危ない), which are skipped.
"""

import re
import unicodedata


def _nfc(values):
    return type(values)(unicodedata.normalize("NFC", v) for v in values)


# Negation words (whole words) and prefixes that may negate, per language (primary subtag), for
# languages written with spaces between words.
NEGATION = {
    "es": ({"no", "nunca", "jamás", "ni", "ningún", "ninguno", "ninguna", "nada", "nadie", "sin", "tampoco"},
           ("des", "in", "im", "ir")),
    "pt": ({"não", "nunca", "jamais", "nem", "nenhum", "nenhuma", "nada", "ninguém", "sem"}, ("des", "in", "im")),
    "fr": ({"ne", "n", "pas", "jamais", "aucun", "aucune", "rien", "personne", "sans", "non", "ni"},
           ("in", "im", "non", "dé", "dés")),
    "it": ({"non", "mai", "nessun", "nessuno", "nessuna", "niente", "nulla", "senza", "né"}, ("in", "im", "non", "dis")),
    "de": ({"nicht", "kein", "keine", "keinen", "keinem", "keiner", "keines", "nie", "niemals", "ohne", "nichts",
            "weder"}, ("un", "nicht")),
    "nl": ({"niet", "geen", "nooit", "zonder", "niets", "noch"}, ("on",)),
    "sv": ({"inte", "ej", "ingen", "inget", "inga", "aldrig", "utan", "icke"}, ("o",)),
    "da": ({"ikke", "ingen", "intet", "aldrig", "uden"}, ("u",)),
    "nb": ({"ikke", "ingen", "intet", "aldri", "uten"}, ("u",)),
    "pl": ({"nie", "nigdy", "żaden", "żadna", "żadne", "żadnych", "bez", "ani", "nic"}, ("nie", "bez")),
    "id": ({"tidak", "tak", "bukan", "jangan", "belum", "tanpa"}, ()),
    "fi": ({"ei", "en", "et", "emme", "ette", "eivät", "älä", "ilman", "koskaan"}, ("epä",)),
    "cs": ({"ne", "nikdy", "žádný", "žádná", "žádné", "bez", "nic"}, ("ne", "bez")),
    "sk": ({"nie", "nikdy", "žiadny", "žiadna", "žiadne", "bez", "nič"}, ("ne", "bez")),
    "ro": ({"nu", "niciodată", "fără", "niciun", "nicio", "nimic"}, ("ne",)),
    "hu": ({"nem", "ne", "soha", "nincs", "sincs", "nélkül", "semmi"}, ()),
    "hr": ({"ne", "nije", "nikad", "nikada", "bez", "nema", "ništa"}, ("ne", "bez")),
    "tr": ({"değil", "yok", "asla", "hiç", "olmadan", "hayır"}, ()),
    "ru": ({"не", "нет", "никогда", "без", "ни", "ничего", "нельзя"}, ("не", "без")),
    "uk": ({"не", "ні", "немає", "ніколи", "без", "нічого", "неможна"}, ("не", "без")),
    "el": ({"δεν", "μη", "μην", "ποτέ", "χωρίς", "όχι", "κανένα"}, ("μη", "α")),
    "hi": ({"नहीं", "न", "मत", "कभी", "बिना"}, ("अ",)),
    "bn": ({"না", "নয়", "নেই", "কখনো", "ছাড়া"}, ("অ",)),
    # Arabic negates with separate words; "غير" and "عدم" also make the un- and non- words
    # ("غير معروف", unknown). The conjunctions و and ف attach to the word ("ولا").
    "ar": ({"لا", "لم", "لن", "ليس", "ليست", "لست", "لسنا", "ليسوا", "غير", "بدون", "دون", "عدم", "ولا", "فلا",
            "ولم", "فلم", "ولن", "وليس", "وغير", "وبدون", "بلا"}, ()),
}
NEGATION = dict((k, (_nfc(words), _nfc(prefixes))) for k, (words, prefixes) in NEGATION.items())

# Negating prefixes a language uses as its standard negation, with the ordinary words that start the
# same way. A word with one of these prefixes is a negation, also in a safety key.
NATIVE_PREFIXES = {
    "ru": (("не", "без"), ("несколь", "необходим", "недел", "нефт", "небо", "небес", "нерв", "немец", "немецк",
                          "немедл", "немн", "безопас")),
    "uk": (("не", "без"), ("неділ", "необхідн", "небо", "небес", "нерв", "негайн", "небагат", "безпек", "безпеч")),
    "pl": (("nie", "bez"), ("niektór", "niebiesk", "niech", "niezbędn", "niemal", "niemiec", "niemc", "niedziel",
                           "niezwłoczn", "nieco", "niebo", "bezpiecz", "bezpośredn", "bezprzewod")),
    "cs": (("ne", "bez"), ("nej", "nebo", "než", "neděl", "nemoc", "nechť", "němec", "nedávn", "neprodl", "nezbytn",
                          "bezpeč", "bezdrát")),
    "sk": (("ne", "bez"), ("nej", "nebo", "než", "nedeľ", "nech", "nedávn", "nevyhnut", "bezpeč", "bezdrôt")),
    "hr": (("ne", "bez"), ("nego", "nešto", "netko", "nekol", "neki", "neka", "neke", "neko", "nedjelj", "nedavn",
                          "neposredn", "bežič")),
    "de": (("un",), ("unter", "und", "uns", "unser", "unbedingt", "ungefähr", "unten", "union", "univers",
                     "unmittelbar", "unix")),
    "nl": (("on",), ("onder", "ons", "onze", "ont", "ongeveer", "onmiddellijk", "online", "ongeacht", "onlangs")),
    "fi": (("epä",), ("epäil",)),
}
NATIVE_PREFIXES = dict((k, (_nfc(p), _nfc(x))) for k, (p, x) in NATIVE_PREFIXES.items())

# Negated words (stems, matched at the start of a word) where the prefix alone is ambiguous.
NATIVE_STEMS = {
    "es": ("imposib", "desconoc", "inválid", "invalid", "incorrect", "inactiv", "desconect", "desactiv",
           "deshabilit", "indispon", "inaccesib", "incomplet", "inesperad", "inestab", "insuficient", "incompatib",
           "insegur", "ilegib", "irreversib", "ilimitad", "innecesari", "imprevist", "inhabilit", "incapaz"),
    "pt": ("impossív", "desconhec", "inválid", "invalid", "incorret", "inativ", "desconect", "desativ", "desabilit",
           "indisponív", "inacessív", "incomplet", "inesperad", "instáv", "insuficient", "incompatív", "insegur",
           "ilegív", "irreversív", "ilimitad", "desnecessári", "incapaz"),
    "fr": ("impossib", "inconnu", "invalid", "incorrect", "inactif", "inactiv", "déconnect", "désactiv",
           "indisponib", "inaccessib", "incomplet", "inattendu", "instab", "insuffis", "incompatib", "illisib",
           "irréversib", "illimit", "inutile", "incapab"),
    "it": ("impossib", "sconosciut", "invalid", "incorrett", "inattiv", "disconness", "disattiv", "indisponib",
           "inaccessib", "incomplet", "inaspettat", "imprevist", "instabil", "insufficient", "incompatib", "illeggib",
           "irreversib", "illimitat", "inutil", "incapac"),
    "sv": ("okänd", "ogiltig", "omöjlig", "oväntad", "ofullständig", "otillräcklig", "inkompatib", "inaktiv",
           "oförmögen"),
    "da": ("ukendt", "ugyldig", "umulig", "uventet", "ufuldstændig", "utilstrækkelig", "inkompatib", "inaktiv"),
    "nb": ("ukjent", "ugyldig", "umulig", "uventet", "ufullstendig", "utilstrekkelig", "inkompatib", "inaktiv"),
    "el": ("άγνωστ", "αδύνατ", "άκυρ", "ανενεργ", "ασύμβατ", "ανεπαρκ", "απενεργοποι", "αποσυνδεδεμέν"),
    "ro": ("necunoscut", "nevalid", "invalid", "imposibil", "incorect", "inactiv", "neașteptat", "incomplet",
           "insuficient", "incompatibil", "dezactivat", "deconectat", "neconectat", "nereușit"),
    "hu": ("ismeretlen", "lehetetlen", "érvénytelen", "váratlan", "hiányos", "elégtelen", "inaktív",
           "sikertelen"),
    "fi": ("tuntemat", "mahdot", "kelvot", "odottamat", "riittämät", "yhteensopimat"),
    "tr": ("bilinmeyen", "bilinmiyor", "geçersiz", "imkansız", "imkânsız", "olanaksız", "başarısız", "beklenmeyen",
           "desteklenmeyen", "yetersiz", "uyumsuz"),
    "hi": ("अज्ञात", "असंभव", "अमान्य", "अनुपलब्ध", "असमर्थ", "अपूर्ण", "निष्क्रिय", "असंगत"),
    # Arabic "unable" and "unknown" are often single words with no negation particle.
    "ar": ("تعذر", "يتعذر", "مستحيل", "مجهول"),
    "bn": ("অজানা", "অসম্ভব", "অবৈধ", "অনুপলব্ধ", "অক্ষম", "অসম্পূর্ণ", "নিষ্ক্রিয়", "অসঙ্গত"),
}
NATIVE_STEMS = dict((k, _nfc(v)) for k, v in NATIVE_STEMS.items())

# zh and ja: negation words, longest first, and the ordinary words that contain a negation
# character (skipped before the negation words are matched).
UNSPACED_NEGATION = {
    "zh": (("没有", "沒有", "无法", "無法", "不能", "不可", "不要", "未能", "尚未", "请勿", "請勿", "并非", "並非",
            "未知", "不", "没", "沒", "无", "無", "未", "非", "别", "別", "勿", "否", "莫", "甭"),
           ("级别", "級別", "识别", "識別", "区别", "區別", "特别", "特別", "分别", "分別", "类别", "類別", "差别",
            "差別", "告别", "告別", "性别", "性別", "个别", "個別", "辨别", "辨別", "鉴别", "鑑別", "别人", "別人",
            "别的", "別的", "别处", "別處", "派别", "派別", "无线", "無線", "无人机", "無人機", "非常", "是否",
            "否则", "否則", "未来", "未來", "不过", "不過", "不断", "不斷", "不同", "不久", "不仅", "不僅", "不管",
            "无论", "無論", "不论", "不論", "除非", "不少", "不错", "不錯", "差不多", "不得不", "莫名")),
    "ja": (("なければ", "ません", "なかった", "ないで", "ない", "なし", "無し", "せず", "できず", "られず", "不可",
            "不明", "不要", "不能", "無効", "無理", "未", "非", "禁止", "いいえ"),
           ("少ない", "少なく", "危ない", "危なく", "かもしれない", "かもしれません", "なければなりません",
            "なければならない", "未来", "非常", "無線", "必ず")),
}
UNSPACED_NEGATION = dict((k, (_nfc(w), _nfc(x))) for k, (w, x) in UNSPACED_NEGATION.items())

# Scripts written without spaces between words (language "script" values and script_of codes).
UNSPACED_SCRIPTS = {"Hans", "Hant", "Hani", "Jpan", "Kana", "Thai", "Laoo", "Khmr", "Mymr"}
# Languages whose words take suffixes: a glossary term matches at the start of a word ("kilit" in
# "kilitle"; Korean particles), not only as a whole word.
SUFFIXING = {"tr", "fi", "hu", "et", "ko", "az", "kk", "uz"}
# Clitics written joined to the next word: a glossary term may follow them ("القفل", "والقفل").
PROCLITICS = {"ar": "(?:[وف]?[بلك]?(?:ال)?)", "he": "(?:[ושהבלמכ]{0,3})"}
# Text next to a glossary target in an unspaced script that makes it the opposite term
# (解锁 unlock beside 锁; ロック解除 unlock after ロック).
OPPOSITE_AFFIXES = {
    "zh": (("解", "开", "開", "取消"), ()),
    "ja": (("解", "アン"), ("解除",)),
}

# Script codes a language's "script" value allows (Japanese mixes kanji and kana).
SCRIPT_SETS = {"Hans": {"Hani"}, "Hant": {"Hani"}, "Jpan": {"Hani", "Kana"}, "Kore": {"Hang", "Hani"}}

# Latin words a non-Latin translation may use although the English does not (a warning only),
# compared without case; a hyphenated word is allowed when it, or every part, is allowed.
LATIN_LOANS = {"android", "bluetooth", "google", "wi-fi", "wifi", "wi", "fi", "volt", "navee", "play", "pixel",
               "shizuku", "iphone", "app", "apps", "email", "e-mail", "gps", "usb", "ok", "pin", "qr", "sms", "id"}

HYPHENS = "-‐‑"
ARABIC_MARKS = re.compile("[ؐ-ًؚ-ٰٟـ]")


def normalize(text):
    """NFC, lower case, Arabic vowel marks and tatweel removed, typographic hyphens made plain."""
    text = ARABIC_MARKS.sub("", unicodedata.normalize("NFC", text)).lower()
    return text.replace("‐", "-").replace("‑", "-")


def tokens(text):
    """Words of a spaced script: letters with their combining marks (no digits or underscores)."""
    out, run = [], []
    for ch in text:
        cat = unicodedata.category(ch)
        if cat.startswith("L") or (cat.startswith("M") and run):
            run.append(ch)
            continue
        if run:
            out.append("".join(run))
        run = []
    if run:
        out.append("".join(run))
    return out


def unspaced_negations(primary, text):
    """Negation words in a zh or ja text, skipping the ordinary words that contain one."""
    words, ordinary = UNSPACED_NEGATION[primary]
    words = sorted(words, key=len, reverse=True)
    found, i = [], 0
    while i < len(text):
        skip = next((w for w in ordinary if text.startswith(w, i)), None)
        if skip:
            i += len(skip)
            continue
        hit = next((w for w in words if text.startswith(w, i)), None)
        if hit:
            found.append(hit)
            i += len(hit)
            continue
        i += 1
    return found


def native_negated(primary, word, stems=()):
    """Whether a word is the language's own negated form ("неизвестно", "imposible").

    A prefix plus one of stems (the approved glossary targets) is the opposite term, not a
    negation of the sentence ("desbloquear"), so it does not count.
    """
    for stem in NATIVE_STEMS.get(primary, ()):
        if word.startswith(stem):
            return True
    prefixes, ordinary = NATIVE_PREFIXES.get(primary, ((), ()))
    if any(word.startswith(o) for o in ordinary):
        return False
    return any(word.startswith(p) and len(word) > len(p) + 3 and
               not any(word[len(p):].startswith(stem) for stem in stems) for p in prefixes)


def term_found(primary, script, term, text):
    """Whether a glossary target appears in a text, matched the way the language writes words.

    Spaced scripts: the target starts a word ("bloquear" is not inside "desbloquear"), and ends one
    unless the language takes suffixes. Unspaced scripts: anywhere, except beside an affix that
    makes it the opposite term.
    """
    text, term = normalize(text), normalize(term)
    parts = [re.escape(p) for p in term.split()]
    if not parts:
        return False
    if script in UNSPACED_SCRIPTS:
        body = r"\s*".join(r"\s*".join(re.escape(c) for c in p) for p in term.split())
        before, after = OPPOSITE_AFFIXES.get(primary, ((), ()))
        for m in re.finditer(body, text):
            head, tail = text[:m.start()].rstrip(), text[m.end():].lstrip()
            if any(head.endswith(a) for a in before) or any(tail.startswith(a) for a in after):
                continue
            return True
        return False
    body = r"\s+".join(parts)
    end = "" if primary in SUFFIXING else r"(?![^\W_])"
    return re.search(r"(?<![^\W_])" + PROCLITICS.get(primary, "") + body + end, text, re.UNICODE) is not None


def latin_loan(word, allowed):
    """Whether a Latin word is a loan: in LATIN_LOANS or allowed (lower case), whole or every part."""
    word = normalize(word)
    if word in LATIN_LOANS or word in allowed:
        return True
    parts = [p for p in re.split("[%s]" % HYPHENS, word) if p]
    return len(parts) > 1 and all(p in LATIN_LOANS or p in allowed for p in parts)
