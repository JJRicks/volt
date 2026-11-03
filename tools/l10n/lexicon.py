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
    contain a negation character (级别, 识别, 无线, 非常, 少ない, 危ない, 未満), which are skipped.
    The longest word wins, so 不管用 (does not work) is a negation although 不管 is ordinary.
  - Glossary targets match the way the language writes words (INFLECTING, SUFFIXING, PROCLITICS),
    and never where the next or previous word makes them the opposite term (OPPOSITE_CONTEXT:
    "lås upp", "buka kunci", "فتح القفل", 解除锁定, ロックを解除).
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
    # Urdu: "کبھی" alone is "ever" or "sometimes" (never is "کبھی نہیں"), so it is not listed.
    "ur": ({"نہیں", "نہ", "مت", "بغیر", "بنا"}, ()),
}
NEGATION = dict((k, (_nfc(words), _nfc(prefixes))) for k, (words, prefixes) in NEGATION.items())

# Negation written as a word ending: Bengali negates the past and perfect with -নি ("হয়নি", "যায়নি",
# "পারেনি", "দেয়নি"). Only the verb endings are listed; "তিনি" (he) and "পানি" (water) end in নি too.
NEGATION_SUFFIXES = {"bn": ("য়নি", "েনি", "ইনি")}
NEGATION_SUFFIXES = dict((k, _nfc(v)) for k, v in NEGATION_SUFFIXES.items())

# Phrases that contain a negation word but negate nothing (Italian "non appena", as soon as). They
# are removed before the negation words are counted.
NON_NEGATING_PHRASES = {"it": ("non appena",), "fr": ("non seulement",)}

# Negating prefixes a language uses as its standard negation, with the ordinary words that start the
# same way. A word with one of these prefixes is a negation, also in a safety key.
NATIVE_PREFIXES = {
    "ru": (("не", "без"), ("несколь", "необходим", "недел", "нефт", "небо", "небес", "нерв", "немец", "немецк",
                          "немедл", "немн", "некотор", "безопас")),
    "uk": (("не", "без"), ("неділ", "необхідн", "небо", "небес", "нерв", "негайн", "небагат", "безпек", "безпеч")),
    "pl": (("nie", "bez"), ("niektór", "niebiesk", "niech", "niezbędn", "niemal", "niemiec", "niemc", "niedziel",
                           "niezwłoczn", "nieco", "niebo", "niedawn", "niewielk", "niedług", "bezpiecz", "bezpośredn",
                           "bezprzewod")),
    "cs": (("ne", "bez"), ("nej", "nebo", "než", "neděl", "nemoc", "nechť", "němec", "nedávn", "neprodl", "nezbytn",
                          "bezpeč", "bezdrát")),
    "sk": (("ne", "bez"), ("nej", "nebo", "než", "nedeľ", "nech", "nedávn", "nevyhnut", "bezpeč", "bezdrôt")),
    "hr": (("ne", "bez"), ("nego", "nešto", "netko", "nekol", "neki", "neka", "neke", "neko", "nedjelj", "nedavn",
                          "neposredn", "bežič")),
    "de": (("un",), ("unter", "und", "uns", "unser", "unbedingt", "ungefähr", "unten", "union", "univers",
                     "unmittelbar", "unix", "unfall")),
    "nl": (("on",), ("onder", "ons", "onze", "ont", "ongeveer", "onmiddellijk", "online", "ongeacht", "onlangs")),
    "fi": (("epä",), ("epäil",)),
}
NATIVE_PREFIXES = dict((k, (_nfc(p), _nfc(x))) for k, (p, x) in NATIVE_PREFIXES.items())

# Negated words (stems, matched at the start of a word) where the prefix alone is ambiguous.
NATIVE_STEMS = {
    "es": ("imposib", "desconoc", "inválid", "invalid", "incorrect", "inactiv", "indispon", "inaccesib",
           "incomplet", "inesperad", "inestab", "insuficient", "incompatib", "insegur", "ilegib", "irreversib",
           "ilimitad", "innecesari", "imprevist", "incapaz"),
    "pt": ("impossív", "desconhec", "inválid", "invalid", "incorret", "inativ", "indisponív", "inacessív", "incomplet", "inesperad", "instáv", "insuficient", "incompatív", "insegur",
           "ilegív", "irreversív", "ilimitad", "desnecessári", "incapaz"),
    "fr": ("impossib", "inconnu", "invalid", "incorrect", "inactif", "inactiv", "indisponib", "inaccessib", "incomplet", "inattendu", "instab", "insuffis", "incompatib", "illisib",
           "irréversib", "illimit", "inutile", "incapab"),
    "it": ("impossib", "sconosciut", "invalid", "incorrett", "inattiv", "indisponib",
           "inaccessib", "incomplet", "inaspettat", "imprevist", "instabil", "insufficient", "incompatib", "illeggib",
           "irreversib", "illimitat", "inutil", "incapac"),
    "sv": ("okänd", "ogiltig", "omöjlig", "oväntad", "ofullständig", "otillräcklig", "inkompatib", "inaktiv",
           "oförmögen", "overifier", "obekräft"),
    "da": ("ukendt", "ugyldig", "umulig", "uventet", "ufuldstændig", "utilstrækkelig", "inkompatib", "inaktiv"),
    "nb": ("ukjent", "ugyldig", "umulig", "uventet", "ufullstendig", "utilstrekkelig", "inkompatib", "inaktiv"),
    "el": ("άγνωστ", "αδύνατ", "αδυναμ", "άκυρ", "ανενεργ", "ασύμβατ", "ανεπαρκ", "ανεπιβεβαίωτ"),
    "ro": ("necunoscut", "nevalid", "invalid", "imposibil", "incorect", "inactiv", "neașteptat", "incomplet",
           "insuficient", "incompatibil", "neconectat", "nereușit"),
    "hu": ("ismeretlen", "lehetetlen", "érvénytelen", "váratlan", "hiányos", "elégtelen", "inaktív",
           "sikertelen"),
    "fi": ("tuntemat", "mahdot", "kelvot", "odottamat", "riittämät", "yhteensopimat"),
    "tr": ("bilinmeyen", "bilinmiyor", "geçersiz", "imkansız", "imkânsız", "olanaksız", "başarısız", "beklenmeyen",
           "desteklenmeyen", "yetersiz", "uyumsuz"),
    "hi": ("अज्ञात", "असंभव", "अमान्य", "अनुपलब्ध", "असमर्थ", "अपूर्ण", "निष्क्रिय", "असंगत", "असत्यापित"),
    # Arabic "unable" and "unknown" are often single words with no negation particle.
    "ar": ("تعذر", "يتعذر", "مستحيل", "مجهول"),
    "bn": ("অজানা", "অসম্ভব", "অবৈধ", "অনুপলব্ধ", "অক্ষম", "অসম্পূর্ণ", "নিষ্ক্রিয়", "অসঙ্গত", "অযাচাই"),
}
NATIVE_STEMS = dict((k, _nfc(v)) for k, v in NATIVE_STEMS.items())
# The deactivate, disconnect and disable verbs (es "desactivar", fr "déconnecter", el "απενεργοποίηση")
# are left out on purpose: they are actions, the opposite of a term, and like "desbloquear" they never
# pass a safety key as the negation of an English un-word.

# zh and ja: negation words, longest first, and the ordinary words that contain a negation
# character (skipped before the negation words are matched).
UNSPACED_NEGATION = {
    "zh": (("没有", "沒有", "无法", "無法", "不能", "不可", "不要", "未能", "尚未", "请勿", "請勿", "并非", "並非",
            "未知", "不管用", "不断开", "不斷開", "不", "没", "沒", "无", "無", "未", "非", "别", "別", "勿", "否", "莫", "甭"),
           ("级别", "級別", "识别", "識別", "区别", "區別", "特别", "特別", "分别", "分別", "类别", "類別", "差别",
            "差別", "告别", "告別", "性别", "性別", "个别", "個別", "辨别", "辨別", "鉴别", "鑑別", "别人", "別人",
            "别的", "別的", "别处", "別處", "派别", "派別", "无线", "無線", "无人机", "無人機", "非常", "是否",
            "否则", "否則", "未来", "未來", "不过", "不過", "不断", "不斷", "不同", "不久", "不仅", "不僅", "不管",
            "无论", "無論", "不论", "不論", "除非", "不少", "不错", "不錯", "差不多", "不得不", "莫名", "别名",
            "別名")),
    "ja": (("なければ", "ません", "なかった", "ないで", "ない", "なし", "無し", "せず", "できず", "られず", "不可",
            "不明", "不要", "不能", "無効", "無理", "未", "非", "禁止", "いいえ"),
           ("少ない", "少なく", "危ない", "危なく", "かもしれない", "かもしれません", "なければなりません",
            "なければならない", "未来", "非常", "無線", "必ず", "未満", "すみません", "済みません")),
}
UNSPACED_NEGATION = dict((k, (_nfc(w), _nfc(x))) for k, (w, x) in UNSPACED_NEGATION.items())

# Scripts written without spaces between words (language "script" values and script_of codes).
UNSPACED_SCRIPTS = {"Hans", "Hant", "Hani", "Jpan", "Kana", "Thai", "Laoo", "Khmr", "Mymr"}
# Languages whose words take suffixes: a glossary term matches at the start of a word ("kilit" in
# "kilitle"; Korean particles), not only as a whole word.
SUFFIXING = {"tr", "fi", "hu", "et", "ko", "az", "kk", "uz"}
# Languages that inflect a word by changing its ending (gender, case, number): a glossary target
# whose last letters are vowels also matches with another ending of up to four letters
# ("блокировка" -> "блокировку", "bloqueado" -> "bloqueada", "zablokowany" -> "zablokowana").
# The word must still start with the target, so a prefixed opposite ("desbloqueada") never matches.
INFLECTING = {"es", "pt", "fr", "it", "ro", "ca", "ru", "uk", "be", "pl", "cs", "sk", "sl", "hr", "sr", "bs",
              "bg", "mk", "el"}
_ENDING_LETTERS = "aeiouyáéíóúàèìòùâêîôûãõăаеёиоуыэюяьйъіїєαεηιουωάέήίόύώ"
# Clitics written joined to the next word: a glossary term may follow them ("القفل", "والقفل", and
# "للقفل", where لِ plus the article is written لل).
PROCLITICS = {"ar": "(?:[وف]?(?:لل|[بلك]?(?:ال)?))", "he": "(?:[ושהבלמכ]{0,3})"}
# Words next to a glossary target that make it the opposite term, per language: (a pattern the
# text before the target must not end with, a pattern the text after it must not start with). In
# zh the after pattern also skips 定 and 住, so the target 锁 inside 锁定解除 is not found either.
# Unlock is often the lock word plus a separate word or particle: zh 解锁, 解除锁定, 打开…的锁,
# 锁定解除; ja ロック解除, ロックを解除, ロックを外す; ko 잠금 해제, 잠금을 풀다; sv "lås upp"; nb "lås opp";
# da "lås op"; id "buka kunci"; ar "فتح القفل", "فك قفل", "إلغاء قفل"; ur "ان لاک", "لاک کھولیں";
# hi "लॉक खोलें"; bn "লক খুলুন". A target found only in such a place is not found.
# The gap stops at punctuation and at the words that start a new step (后, 并, 再, 时: "解除报警后锁定",
# lock after the alarm is dismissed, is not an unlock).
_ZH_GAP = "[^，。、；：！？,.;:!?\n后後并並和及再然时時]{0,8}"
OPPOSITE_CONTEXT = {
    "zh": ("(?:解|开|開|取消|解除%s|(?:打开|打開)%s的)\\s*$" % (_ZH_GAP, _ZH_GAP),
           "^\\s*(?:定|住)?\\s*(?:已|被|已被)?\\s*(?:解除|打开|打開|解开|解開)"),
    "ja": ("(?:解|アン)\\s*$", "^\\s*(?:を|が|は|も)?\\s*(?:解除|解|外|はず|開け|あけ)"),
    "ko": ("", "^(?:을|를|이|가|은|는|도)?\\s*(?:해제|풀|열)"),
    "sv": ("", "^[^\\W\\d_]*\\s+upp(?![^\\W_])"),
    "nb": ("", "^[^\\W\\d_]*\\s+opp(?![^\\W_])"),
    "nn": ("", "^[^\\W\\d_]*\\s+opp(?![^\\W_])"),
    "da": ("", "^[^\\W\\d_]*\\s+op(?![^\\W_])"),
    "id": ("(?<![^\\W_])(?:buka|bukalah|membuka|dibuka|terbuka|pembukaan)\\s+$", ""),
    "ar": ("(?<![^\\W_])[وف]?[لب]?(?:ال)?(?:فتح|افتح|يفتح|تفتح|فك|يفك|إلغاء|الغاء)\\s+$", ""),
    "ur": ("(?<![^\\W_])ان\\s*$", "^\\S*\\s+(?:کھول|کھل)"),
    "hi": ("", "^\\S*\\s+(?:खोल|खुल)"),
    "bn": ("", "^\\S*\\s+(?:খুল|খোল)"),
}
OPPOSITE_CONTEXT = dict((k, tuple(re.compile(unicodedata.normalize("NFC", p)) if p else None for p in v))
                        for k, v in OPPOSITE_CONTEXT.items())

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
    """Negation words in a zh or ja text, skipping the ordinary words that contain one.

    The longest word at each position wins, so a negation that extends an ordinary word ("不管用",
    does not work, against "不管", no matter) is still found.
    """
    words, ordinary = UNSPACED_NEGATION[primary]
    candidates = sorted([(w, True) for w in words] + [(w, False) for w in ordinary], key=lambda c: -len(c[0]))
    found, i = [], 0
    while i < len(text):
        hit = next((c for c in candidates if text.startswith(c[0], i)), None)
        if hit:
            if hit[1]:
                found.append(hit[0])
            i += len(hit[0])
            continue
        i += 1
    return found


def suffix_negated(primary, word):
    """Whether a word carries the language's negating verb ending (Bengali "হয়নি")."""
    return any(word.endswith(s) and len(word) > len(s) for s in NEGATION_SUFFIXES.get(primary, ()))


# Arabic "غيّر" (change!, with shadda) is written like "غير" (not) once the vowel marks are gone.
_AR_CHANGE = re.compile("(?<![^\\W_])([وف]?)غ[\u064b-\u0650\u0652]*ي[\u064b-\u0650\u0652]*\u0651[\u064b-\u0650\u0652]*ر(?![^\\W_])")


def negation_text(primary, text):
    """A target text prepared for counting negation words: the phrases and verb forms that only
    look like a negation are replaced, then the text is normalized."""
    if primary == "ar":
        text = _AR_CHANGE.sub(lambda m: m.group(1) + "تغيير", unicodedata.normalize("NFC", text))
    text = normalize(text)
    for phrase in NON_NEGATING_PHRASES.get(primary, ()):
        text = re.sub(r"(?<![^\W_])" + r"\s+".join(map(re.escape, phrase.split())) + r"(?![^\W_])", " ", text)
    return text


def native_negated(primary, word, stems=()):
    """Whether a word is the language's own negated form ("неизвестно", "imposible").

    A prefix plus one of stems (the approved glossary targets) is the opposite term, not a
    negation of the sentence ("desbloquear"), so it does not count.
    """
    prefixes, ordinary = NATIVE_PREFIXES.get(primary, ((), ()))
    every_prefix = tuple(prefixes) + tuple(NEGATION.get(primary, ((), ()))[1])
    if any(word.startswith(p) and any(word[len(p):].startswith(stem) for stem in stems) for p in every_prefix):
        return False
    for stem in NATIVE_STEMS.get(primary, ()):
        if word.startswith(stem):
            return True
    if any(word.startswith(o) for o in ordinary):
        return False
    return any(word.startswith(p) and len(word) > len(p) + 3 and
               not any(word[len(p):].startswith(stem) for stem in stems) for p in prefixes)


def _strip_accents(text):
    return unicodedata.normalize("NFC", "".join(c for c in unicodedata.normalize("NFD", text)
                                                if not unicodedata.combining(c)))


def _inflected(primary, word):
    """A pattern for one word of a target: the word, or in an inflecting language its stem plus
    any ending of up to four letters."""
    if primary in INFLECTING:
        stem = word.rstrip(_ENDING_LETTERS)
        if stem != word and len(stem) >= 5:
            return re.escape(stem) + r"[^\W\d_]{0,4}"
    return re.escape(word)


def term_found(primary, script, term, text):
    """Whether a glossary target appears in a text, matched the way the language writes words.

    Spaced scripts: the target starts a word ("bloquear" is not inside "desbloquear"), and ends one
    unless the language takes suffixes or inflects (INFLECTING). Unspaced scripts: anywhere. In
    both, a match next to words that make it the opposite term (OPPOSITE_CONTEXT) does not count.
    """
    text, term = normalize(text), normalize(term)
    if primary == "el":
        text, term = _strip_accents(text), _strip_accents(term)
    if not term.split():
        return False
    if script in UNSPACED_SCRIPTS:
        body = r"\s*".join(r"\s*".join(re.escape(c) for c in p) for p in term.split())
        pattern = body
    else:
        body = r"\s+".join(_inflected(primary, p) for p in term.split())
        end = "" if primary in SUFFIXING or body.endswith("{0,4}") else r"(?![^\W_])"
        pattern = r"(?<![^\W_])" + PROCLITICS.get(primary, "") + body + end
    before, after = OPPOSITE_CONTEXT.get(primary, (None, None))
    for m in re.finditer(pattern, text, re.UNICODE):
        if before is not None and before.search(text[:m.start()]):
            continue
        if after is not None and after.search(text[m.end():]):
            continue
        return True
    return False


def latin_loan(word, allowed):
    """Whether a Latin word is a loan: in LATIN_LOANS or allowed (lower case), whole or every part."""
    word = normalize(word)
    if word in LATIN_LOANS or word in allowed:
        return True
    parts = [p for p in re.split("[%s]" % HYPHENS, word) if p]
    return len(parts) > 1 and all(p in LATIN_LOANS or p in allowed for p in parts)
