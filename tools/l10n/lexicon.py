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
    and, for the lock terms, never where the next or previous word makes them the opposite term
    (OPPOSITE_CONTEXT: "lås upp", "buka kunci", "فتح القفل", 解除锁定, ロックを解除) or removes the
    lock (REMOVAL_CONTEXT: "Quitar el bloqueo", "Zdejmij blokadę", "Sperre aufheben").

These lists are a first filter. They catch the common inversions cheaply; they cannot catch every
way a language says the opposite. check.py's docstring ("Limits") lists the known gaps.
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
            "未知", "不管用", "不断开", "不斷開", "不同步", "不同意", "不", "没", "沒", "无", "無", "未", "非", "别", "別", "勿", "否", "莫", "甭"),
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
# Languages that write a noun phrase as one compound word ("Sperrdauer", "Vergrendelstatus",
# "Låstid"), with the endings dropped from a lock target before it joins one (longest first). Only
# the lock terms match this way, and only at the start of a word, so the unlock words (ent-, ont-,
# upp-) never match; other terms list their compound forms in the glossary.
COMPOUNDING = {"de": ("ung", "en", "e"), "nl": ("ing", "en", "e"), "sv": ("ning", "a", "e"), "nb": ("ing", "e")}
# The rest of a compound that removes or opens the lock ("Sperraufhebung", "Vergrendelingsopheffing",
# "Låsupplåsning"): the compound is then not the lock term.
COMPOUND_OPPOSITE = {
    "de": re.compile("aufheb|aufgehob|lös|freigab|freigeb|entfern|deaktiv|ausschalt|abschalt|öffn|beend"),
    "nl": re.compile("opheff|opgehev|uitschakel|uitgeschakel|uitzet|verwijder|deactiv|gedeactiv|open|vrijgeg|vrijgev"),
    "sv": re.compile("upp|häv|borttag|inaktiv|avaktiv|deaktiv|öppn|avstäng"),
    # Not a bare "opp": "Låseoppsett" (lock setup) is still the lock.
    "nb": re.compile("^e?opp$|opplås|opphev|fjern|deaktiv|åpn|frakobl"),
}

# On and off state words, for a safety key whose English says one and not the other ("Lock: on",
# "Alarm sound off", "Low battery alert enabled"). A target that has only the other state's words is
# inverted. Bare particles that are also prepositions (de "aus", "an"; nl "uit", "aan"; sv "av",
# "på") count only at the end of the text or before punctuation. Matched on normalized text.
_STATE_END = "(?=\\s*(?:$|[.,;:!?)\\]]))"
STATE_WORDS = {
    "es": ("activad\\w*|activar\\w*|activa|activo|actívala|actívalo|encendid\\w*|encender\\w*|enciende|encienda|"
           "habilitad\\w*|habilitar\\w*|habilita",
           "desactivad\\w*|desactivar\\w*|desactiva|desactive|desactívala|desactívalo|apagad\\w*|apagar\\w*|apaga|apague|"
           "deshabilitad\\w*|deshabilitar\\w*|deshabilita|inactiv\\w*|quitad\\w*|retirad\\w*"),
    "pt": ("ativad\\w*|activad\\w*|ativar|activar|ative|active|ativa|activa|ativo|activo|ligad\\w*|ligar|ligue|ligá|ativá|activá|"
           "habilitad\\w*",
           "desativad\\w*|desactivad\\w*|desativar|desactivar|desative|desactive|desativa|desactiva|desligad\\w*|"
           "desligar|desligue|desliga|desligá|desativá|desactivá|inativ\\w*|inactiv\\w*|desabilitad\\w*|removid\\w*|retirad\\w*"),
    "fr": ("activé\\w*|activer|activez|active|actif|allumé\\w*|allumer|allumez",
           "désactivé\\w*|désactiver|désactivez|désactive|éteint\\w*|éteindre|éteignez|coupé\\w*|inactif|inactive|"
           "retiré\\w*|supprimé\\w*"),
    "it": ("attivat\\w*|attivar\\w*|attiva|attivo|attivi|accesa|acceso|accesi|accese|accendi\\w*|accender\\w*|"
           "abilitat\\w*",
           "disattivat\\w*|disattivar\\w*|disattiva|disattivo|spenta|spento|spenti|spente|spegni\\w*|spegner\\w*|"
           "disabilitat\\w*|inattiv\\w*|rimoss\\w*"),
    "de": ("an" + _STATE_END + "|ein" + _STATE_END + "|eingeschaltet\\w*|einschalten|"
           "einzuschalten|aktiviert\\w*|aktivieren|aktiv\\w*",
           "aus" + _STATE_END + "|ausgeschaltet\\w*|ausschalten|auszuschalten|abgeschaltet\\w*|abschalten|abzuschalten|deaktiviert\\w*|"
           "deaktivieren|inaktiv\\w*|aufgehoben"),
    "nl": ("aan" + _STATE_END + "|in" + _STATE_END + "|ingeschakeld\\w*|inschakelen|aangezet|aanzetten|"
           "aan\\s+te\\s+zetten|in\\s+te\\s+schakelen|geactiveerd\\w*|activeren|actief|actieve",
           "uit\\s+te\\s+(?:zetten|schakelen)|uit" + _STATE_END + "|uitgeschakeld\\w*|uitschakelen|uitgezet|uitzetten|gedeactiveerd\\w*|"
           "deactiveren|inactief|inactieve|opgeheven"),
    "pl": ("włączon\\w*|włącz|włączyć|włączcie|aktywn\\w*|aktywowan\\w*|aktywuj\\w*",
           "wyłączon\\w*|wyłącz|wyłączyć|wyłączcie|nieaktywn\\w*|dezaktywowan\\w*|dezaktywuj\\w*|zdjęt\\w*|"
           "usunięt\\w*"),
    "sv": ("på" + _STATE_END + "|sl[åa]r?\\s+på|sätt\\s+på|påslag\\w*|aktiverad\\w*|aktiverat|aktivera|aktiv|"
           "aktivt|aktiva",
           "av" + _STATE_END + "|sl[åa]r?\\s+av|stäng\\w*\\s+av|avstängd\\w*|avstängt|avslag\\w*|"
           "inaktiverad\\w*|inaktiverat|inaktivera|avaktiverad\\w*|avaktiverat|avaktivera|inaktiv|inaktivt|"
           "hävd|hävt"),
    "nb": ("på" + _STATE_END + "|sl[åa]r?\\s+på|slått\\s+på|skru\\w*\\s+på|aktivert\\w*|aktivere\\w*|aktiver|aktiv|"
           "aktivt|aktive",
           "av" + _STATE_END + "|sl[åa]r?\\s+av|slått\\s+av|skru\\w*\\s+av|avslått\\w*|"
           "deaktivert\\w*|deaktivere\\w*|deaktiver|inaktiv|inaktivt|inaktive|opphevet"),
    "id": ("aktif|diaktifkan|aktifkan(?:nya)?|mengaktifkan(?:nya)?|menyala|nyala|nyalakan(?:nya)?|"
           "menyalakan(?:nya)?|dinyalakan|hidup|dihidupkan|hidupkan(?:nya)?",
           "nonaktif|dinonaktifkan|nonaktifkan(?:nya)?|menonaktifkan(?:nya)?|mati|dimatikan|matikan(?:nya)?|"
           "mematikan(?:nya)?|padam|dilepas|"
           "dihapus"),
}
STATE_WORDS = dict((k, tuple(re.compile("(?<![^\\W_])(?:%s)(?![^\\W_])" % unicodedata.normalize("NFC", p))
                             for p in v)) for k, v in STATE_WORDS.items())


# A state word counts as the opposite state when a negation word negates it: the word right before
# it ("nicht aktiv", "tidak aktif", "sin activar", "n'est pas activé"), or the one before that with
# only a copula, auxiliary, reflexive or clitic particle, or a "no longer" word, between
# (STATE_NEGATION_GAP: "no está activada", "ne s'active", "nie jest włączony", "nicht mehr aktiv"). Any other word between, such as a verb that takes
# an infinitive ("No olvides apagar", "Nie zapomnij wyłączyć", "Jangan lupa matikan", "Glöm inte att
# stänga av"), means the negation belongs to that word, and the state is read as written. A
# negation further away ("no se encuentra activa", "non sarà più attivo") is not read.
STATE_NEGATION_GAP = {
    "es": {"está", "estará", "esté", "es", "será", "se", "lo", "la", "le"},
    "pt": {"está", "estará", "esteja", "é", "será", "fica", "ficará", "se", "o", "a"},
    "fr": {"est", "sera", "soit", "se", "s", "l", "le", "la"},
    "it": {"è", "sarà", "sia", "si", "lo", "la", "più"},
    "de": {"ist", "wird", "sind", "werden", "mehr", "länger"},
    "nl": {"is", "wordt", "zijn", "worden", "meer", "langer"},
    "pl": {"jest", "będzie", "są", "się"},
    "sv": {"är", "blir", "längre"},
    "nb": {"er", "blir", "lenger"},
    "id": {"sedang", "akan", "lagi"},
}
STATE_NEGATION_GAP = dict((k, _nfc(v)) for k, v in STATE_NEGATION_GAP.items())
# French "ne ... que" says only, not not ("ne s'active que si"): a "ne" whose clause goes on to
# "que" before a real second part (pas, plus, jamais...) negates nothing. "ne ... plus" (no longer)
# negates: "plus" counts as a negation word after a "ne" in the same clause.
_FR_NE = {"ne", "n"}
_FR_NE_PARTNER = {"pas", "plus", "jamais", "rien", "aucun", "aucune", "personne", "guère", "point"}
_CLAUSE_BREAK = re.compile("[.,;:!?()\\[\\]–—]")
_WORD = re.compile("[^\\W\\d_]+")


def _negation_word(primary, word, before, after):
    """Whether word, with the words before and after it in its clause, negates."""
    if primary == "fr":
        if word == "plus":
            return any(w in _FR_NE for w in before)
        if word in _FR_NE:
            for w in after:
                if w in _FR_NE_PARTNER:
                    break
                if w in ("que", "qu"):
                    return False
    return word in NEGATION.get(primary, (set(), ()))[0]


def _negated(primary, head, tail=""):
    """Whether a state word, with the text head before it and tail after it, is negated."""
    for phrase in NON_NEGATING_PHRASES.get(primary, ()):
        # "non appena attivato" (as soon as): the phrase ends a clause of its own.
        head = re.sub(r"(?<![^\W_])" + r"\s+".join(map(re.escape, phrase.split())) + r"(?![^\W_])", ".", head)
    clause = _CLAUSE_BREAK.split(head)[-1]
    words = _WORD.findall(clause)
    after = _WORD.findall(_CLAUSE_BREAK.split(tail)[0])
    gap = STATE_NEGATION_GAP.get(primary, set())
    for back in (1, 2):
        if len(words) < back:
            return False
        if _negation_word(primary, words[-back], words[:-back], words[len(words) - back + 1:] + after):
            return True
        if words[-back] not in gap:
            return False
    return False


def state_words(primary, text):
    """(on words, off words) found in a target text, for the languages in STATE_WORDS. A state word
    a negation word negates ("nicht aktiv", "no desactivado", "no está activada"; see _negated)
    counts as the other state."""
    patterns = STATE_WORDS.get(primary)
    if not patterns:
        return None
    text = normalize(text)
    found = ([], [])
    for side, p in enumerate(patterns):
        for m in p.finditer(text):
            found[1 - side if _negated(primary, text[:m.start()], text[m.end():]) else side].append(m.group(0))
    return found
# Clitics written joined to the next word: a glossary term may follow them ("القفل", "والقفل", and
# "للقفل", where لِ plus the article is written لل).
PROCLITICS = {"ar": "(?:[وف]?(?:لل|[بلك]?(?:ال)?))", "he": "(?:[ושהבלמכ]{0,3})"}
# Words next to a lock glossary target that make it the opposite term, per language: (a pattern the
# text before the target must not end with, a pattern the text after it must not start with). They
# apply only to the lock terms (check.LOCK_TERM): next to another term, such as an alarm, the same
# words are an ordinary verb ("打开警报", turn on the alarm). In zh the after pattern also skips 定
# and 住, so the target 锁 inside 锁定解除 is not found either.
# Unlock is often the lock word plus a separate word or particle: zh 解锁, 解除锁定, 锁定解除; ja
# ロック解除, ロックを解除, ロックの解除, ロックを外す; ko 잠금 해제, 잠금을 풀다; sv "lås upp"; nb "lås opp";
# da "lås op"; id "buka kunci"; ar "فتح القفل", "فك قفل", "إلغاء قفل"; ur "ان لاک", "لاک کھولیں";
# hi "लॉक खोलें"; bn "লক খুলুন". A target found only in such a place is not found.
# The gap stops at punctuation and at the words that start a new step (后, 并, 再, 时: "解除报警后锁定",
# lock after the alarm is dismissed, is not an unlock).
_ZH_GAP = "[^，。、；：！？,.;:!?\n后後并並和及再然时時]{0,8}"
_ZH_CLAUSE_END = "^(?:定)?\\s*(?:$|[，。、；：！？,.;:!?])"
OPPOSITE_CONTEXT = {
    # 开 alone is 开锁 (unlock); 打开 before the target is handled in OPPOSITE_AROUND.
    "zh": ("(?:解|(?<![打])开|(?<![打])開|取消|解除%s)\\s*$" % _ZH_GAP,
           "^\\s*(?:定|住)?\\s*(?:已|被|已被)?\\s*(?:解除|打开|打開|解开|解開)"),
    "ja": ("(?:解|アン)\\s*$", "^\\s*(?:を|が|は|も|の)?\\s*(?:解除|解錠|解い|解く|解け|解か|外す|外し|外れ|外さ|はず|開け|あけ)"),
    "ko": ("", "^(?:을|를|이|가|은|는|도)?\\s*(?:해제|풀(?:기|어|려|리|고|면|다|린|릴)|열(?:기|어|려|리|고|면|다|린|릴))"),
    "sv": ("", "^[^\\W\\d_]*\\s+upp(?![^\\W_])"),
    # Up to two object words may come before opp ("Lås den opp", "for å låse sparkesykkelen opp"), but
    # not across a word that starts a new step ("Lås og ta opp").
    "nb": ("", "^[^\\W\\d_]*(?:\\s+(?!(?:og|eller|men|før|når|hvis|mens|så|etter|for)(?![^\\W_]))[^\\W\\d_]+){0,2}"
               "\\s+opp(?![^\\W_])"),
    "nn": ("", "^[^\\W\\d_]*\\s+opp(?![^\\W_])"),
    "da": ("", "^[^\\W\\d_]*\\s+op(?![^\\W_])"),
    "id": ("(?<![^\\W_])(?:buka|bukalah|membuka|dibuka|terbuka|pembukaan)\\s+$", ""),
    "ar": ("(?<![^\\W_])[وف]?[لب]?(?:ال)?(?:فتح|افتح|يفتح|تفتح|فك|يفك|إلغاء|الغاء)\\s+$", ""),
    "ur": ("(?<![^\\W_])ان\\s*$", "^\\S*\\s+(?:کھول|کھل)"),
    "hi": ("", "^\\S*\\s+(?:खोल|खुल)"),
    "bn": ("", "^\\S*\\s+(?:খুল|খোল)"),
}


def _before(verbs, determiners=""):
    """Text before a target that ends with one of the verbs (or nouns), then an optional determiner."""
    det = "(?:(?:%s)\\s+|l['’]\\s*)?" % determiners if determiners else ""
    return "(?<![^\\W_])(?:%s)\\s+%s$" % (verbs, det)


# Between the lock word and a participle after it: spaces, or a status layout's colon, dash or
# opening parenthesis ("Sperre: aus", "Bloqueo — quitado", "Blokada (wyłączona)").
_SEP = "(?:\\s*[:(\\-\u2013\u2014]\\s*|\\s+)"


def _after(participles, gap="", aux="", tail=""):
    """Text after a target: the rest of its word, an optional genitive gap ("del patinete"), an
    optional auxiliary, then a participle; or one of the tail words at the end of the text."""
    body = "[^\\W\\d_]*(?:%s)?%s(?:(?:%s)\\s+)?(?:%s)" % (gap, _SEP, aux, participles) if aux else \
        "[^\\W\\d_]*(?:%s)?%s(?:%s)" % (gap, _SEP, participles)
    if tail:
        return "^(?:%s|[^\\W\\d_]*%s(?:%s)\\s*[.!?)]?\\s*$)" % (body, _SEP, tail)
    return "^" + body


# Lock removed: unlock said as "remove / lift / release / turn off the lock" ("Quitar el bloqueo",
# "Zdejmij blokadę", "Sperre aufheben", "Снять блокировку", "ロックをオフにする", "关闭滑板车锁定"), and
# the lock said to be removed ("Bloqueo desactivado", "Blokada wyłączona"). Same shape as
# OPPOSITE_CONTEXT, but skipped when the English itself says off, remove, disable and the like
# (check.EN_REMOVAL: "Turn off the lock" is rightly "Desactivar el bloqueo"). These words are a first
# filter: a phrasing missing here still has to pass every drafter, the back-translation and the judge.
_NOT_A_GAP = "(?!(?:y|e|o|u|ni|et|ou|ni|ed|od|i|oraz|lub|albo|a|gdy|kiedy|jeśli|po|przy|und|oder|en|of|"\
    "och|eller|dan|atau|saat|jika|ketika|setelah|lalu|и|или|а|когда|при|если|после|і|або|коли|якщо|"\
    "και|η|οταν|μετα)(?![^\\W_]))"
_GAP_WORD = "\\s+%s[^\\W\\d_]+" % _NOT_A_GAP
REMOVAL_CONTEXT = {
    "es": (_before("quitar|quita|quite|quitá|retirar|retira|retire|retirá|eliminar|elimina|elimine|eliminá|"
                   "levantar|levanta|levante|levantá|desactivar|desactiva|desactive|desactivá|deshabilitar|"
                   "deshabilita|deshabilite|apagar|apaga|apague|liberar|libera|libere|abrir|abre|abra|anular|"
                   "anula|anule|cancelar|cancela|cancele|eliminación|desactivación|retirada|liberación|"
                   "apertura|anulación|cancelación|levantamiento",
                   "el|la|los|las|lo|un|una|su|sus|tu|tus|del|de\\s+la|de\\s+los|de\\s+las|al"),
           _after("quitad|retirad|eliminad|levantad|desactivad|deshabilitad|apagad|liberad|abiert|anulad|cancelad",
                  "\\s+(?:del|de\\s+(?:la|las|los|el|su|tu))\\s+[^\\W\\d_]+",
                  "está|esta|están|fue|ha\\s+sido|se\\s+ha|queda|quedó")),
    "pt": (_before("remover|remova|remove|retirar|retire|retira|tirar|tire|tira|desativar|desative|desativa|"
                   "desactivar|desactive|desactiva|desligar|desligue|desliga|levantar|levante|levanta|liberar|"
                   "libere|libera|libertar|liberte|liberta|abrir|abra|abre|eliminar|elimine|elimina|cancelar|"
                   "cancele|cancela|anular|anule|anula|remoção|retirada|desativação|desactivação|liberação|"
                   "libertação|abertura|eliminação|cancelamento|anulação|levantamento",
                   "o|a|os|as|um|uma|do|da|dos|das|seu|sua|seus|suas|ao|à|o\\s+seu|a\\s+sua"),
           _after("removid|retirad|tirad|desativad|desactivad|desligad|levantad|liberad|libertad|abert|eliminad|"
                  "cancelad|anulad", "\\s+(?:do|da|dos|das)\\s+[^\\W\\d_]+",
                  "está|estão|foi|foram|fica|ficou|é|será|tem\\s+sido")),
    "fr": (_before("retirer|retirez|retire|enlever|enlevez|enlève|supprimer|supprimez|supprime|désactiver|"
                   "désactivez|désactive|lever|levez|lève|ôter|ôtez|ôte|ouvrir|ouvrez|ouvre|libérer|libérez|"
                   "libère|annuler|annulez|annule|couper|coupez|coupe|éteindre|éteignez|éteins|retrait|"
                   "suppression|désactivation|levée|ouverture|libération|annulation",
                   "le|la|les|un|une|du|des|de\\s+la|son|sa|ses|votre|vos|ton|ta|tes|au|aux"),
           _after("retiré|enlevé|supprimé|désactivé|levé|ôté|ouvert|libéré|annulé|coupé|éteint",
                  "\\s+(?:du|des|de\\s+la|de)\\s+[^\\W\\d_]+|\\s+de\\s+l['’][^\\W\\d_]+",
                  "est|sont|a\\s+été|été|sera")),
    "it": (_before("rimuovi|rimuovere|rimuova|rimuovete|togli|togliere|tolga|togliete|disattiva|disattivare|"
                   "disattivi|disattivate|disabilita|disabilitare|disabiliti|rilascia|rilasciare|apri|aprire|"
                   "apra|annulla|annullare|annulli|spegni|spegnere|spenga|elimina|eliminare|elimini|leva|"
                   "levare|revoca|revocare|rimozione|disattivazione|disabilitazione|rilascio|apertura|"
                   "annullamento|eliminazione",
                   "il|lo|la|i|gli|le|un|uno|una|un['’]|del|dello|della|dei|degli|delle|dell['’]|al|allo|"
                   "alla|il\\s+suo|la\\s+sua|suo|sua"),
           _after("rimoss|tolt|disattivat|disabilitat|rilasciat|apert|annullat|spent|eliminat|levat|revocat",
                  "\\s+(?:del|dello|della|dei|degli|delle)\\s+[^\\W\\d_]+|\\s+dell['’][^\\W\\d_]+",
                  "è|e'|sono|viene|è\\s+stato|è\\s+stata|stato|stata")),
    "de": (_before("aufheben|hebe|entfernen|entferne|entfernt|deaktivieren|deaktiviere|deaktiviert|lösen|löse|"
                   "löst|ausschalten|abschalten|freigeben|öffnen|öffne|öffnet|aufhebung|entfernung|"
                   "deaktivierung|freigabe|öffnung|abschaltung",
                   "(?:sie\\s+)?(?:der|die|das|den|dem|des|eine|einer|einen|ihre|ihrer|ihren|deine|deiner|"
                   "seine|seiner)|sie"),
           _after("aufheben|aufgehoben|lösen|gelöst|entfernen|entfernt|deaktivieren|deaktiviert|ausschalten|"
                  "ausgeschaltet|abschalten|abgeschaltet|öffnen|geöffnet|freigeben|freigegeben|beenden|beendet",
                  "\\s+(?:des|der|vom|von\\s+(?:der|dem|ihrem|deinem))\\s+[^\\W\\d_]+",
                  "ist|wird|wurde", "auf|aus")),
    "nl": (_before("verwijder|verwijderen|deactiveer|deactiveren|hef|opheffen|open|openen|uitschakelen|"
                   "uitzetten|annuleer|annuleren|opheffing|verwijdering|deactivering|uitschakeling|opening|"
                   "vrijgeven|geef", "de|het|een|je|uw|jouw|zijn|haar"),
           _after("opheffen|opgeheven|uitschakelen|uitgeschakeld|uitzetten|uitgezet|verwijderen|verwijderd|"
                  "deactiveren|gedeactiveerd|openen|geopend|annuleren|geannuleerd|vrijgeven|vrijgegeven",
                  "\\s+van\\s+(?:de|het|je|uw)\\s+[^\\W\\d_]+", "is|wordt|werd", "uit|op|eraf|af|open")),
    "pl": (_before("zdejmij|zdejmijcie|zdjąć|zdejmowanie|zdjęcie|wyłącz|wyłączcie|wyłączyć|wyłączenie|"
                   "wyłączanie|usuń|usuńcie|usunąć|usunięcie|usuwanie|zwolnij|zwolnić|zwolnienie|znieś|znieść|"
                   "zniesienie|otwórz|otworzyć|otwarcie|dezaktywuj|dezaktywować|dezaktywacja|dezaktywacji|"
                   "anuluj|anulować|anulowanie"),
           _after("wyłączon|zdjęt|usunięt|zniesion|zwolnion|otwart|dezaktywowan|anulowan", _GAP_WORD,
                  "została|zostanie|zostało|został|zostały|jest|są")),
    "sv": (_before("ta\\s+bort|tar\\s+bort|inaktivera|avaktivera|deaktivera|stäng\\s+av|stänger\\s+av|"
                   "slå\\s+av|öppna|frigör|häv|upphäv|borttagning\\s+av|avaktivering\\s+av|"
                   "inaktivering\\s+av|hävning\\s+av", "det|den|ditt|din"),
           _after("inaktiver|avaktiver|deaktiver|borttag|hävt|hävd|hävs|öppnat|öppnad|frigjor|avstängt|"
                  "avstängd|bort(?![^\\W_])", "", "är|har\\s+tagits|har\\s+hävts|blev|har\\s+blivit", "av")),
    "nb": (_before("fjern|fjerne|fjerner|fjernes|fjerning\\s+av|ta\\s+bort|tar\\s+bort|slå\\s+av|slår\\s+av|skru\\s+av|"
                   "skrur\\s+av|deaktiver|deaktivere|deaktiverer|deaktivering\\s+av|opphev|oppheve|opphever|"
                   "oppheving\\s+av|åpne|åpner|frigjør", "den|det|din|ditt|en|et"),
           _after("deaktiver|fjernet|fjernes|tatt\\s+bort|opphevet|oppheves|åpnet|åpnes|åpen|åpent|slått\\s+av|avslått|"
                  "av\\s*[.!?)]?\\s*$", "", "er|ble|blir|har\\s+blitt", "av")),
    "id": (_before("lepas|lepaskan|melepas|melepaskan|nonaktifkan|menonaktifkan|matikan|mematikan|hapus|"
                   "hapuskan|menghapus|copot|mencopot|cabut|mencabut|batalkan|membatalkan|pelepasan|"
                   "penonaktifan|penghapusan|pembatalan"),
           _after("dibuka|terbuka|dilepas|dilepaskan|terlepas|dinonaktifkan|dimatikan|dihapus|dicabut|dicopot|"
                  "dibatalkan", _GAP_WORD, "telah|sudah|akan")),
    "ru": (_before("снять|снимите|сними|снятие|снятия|отключить|отключите|отключи|отключение|отключения|"
                   "выключить|выключите|выключи|выключение|убрать|уберите|убери|отменить|отмените|отмена|"
                   "отмены|деактивировать|деактивируйте|деактивация|открыть|откройте|открой"),
           _after("снят|отключен|отключён|выключен|выключён|убран|отменен|отменён|деактивирован", _GAP_WORD,
                  "была|был|было|будет")),
    "uk": (_before("зняти|зніміть|зніми|зняття|вимкнути|вимкніть|вимкни|вимкнення|скасувати|скасуйте|"
                   "скасування|прибрати|приберіть|деактивувати|деактивуйте|відкрити|відкрийте"),
           _after("знят|вимкнен|скасован|прибран|деактивован", _GAP_WORD, "було|був|була|буде")),
    # Greek is matched without accents (term_found strips them).
    "el": (_before("αρση|απενεργοποιηση|απενεργοποιηστε|απενεργοποιησε|απενεργοποιω|αφαιρεση|αφαιρεστε|"
                   "αφαιρεσε|ανοιγμα|ανοιξτε|ανοιξε|ακυρωση|ακυρωστε|καταργηση",
                   "του|της|το|την|τα|των|τον|η|ο"),
           _after("απενεργοποιη|αφαιρεθ|αφαιρεμεν|ακυρωθ|ακυρωμεν|ανοιχτ|ανοιξ", _GAP_WORD,
                  "εχει|ειναι|θα")),
    "ja": ("", "^\\s*(?:を|が|は|も|の)?\\s*(?:オフ|無効)"),
    "zh": ("(?:关闭|關閉|停用|禁用)\\s*$", "^\\s*(?:定|住)?\\s*(?:已|被|已被)?\\s*(?:关闭|關閉|停用|禁用|取消)"),
}

# Pairs where both must hold to make the target its opposite: zh "打开滑板车的锁" (open the scooter's
# lock) and "关闭滑板车锁定" (turn off the scooter lock) only when the target ends the clause, so
# "打开应用的锁定页面" (open the app's lock page) keeps its lock term. The removal pairs follow
# REMOVAL_CONTEXT's English rule.
OPPOSITE_AROUND = {
    "zh": (("(?:打开|打開)(?:%s的)?\\s*$" % _ZH_GAP, _ZH_CLAUSE_END),),
}
REMOVAL_AROUND = {
    "zh": (("(?:关闭|關閉|停用|禁用)%s的?\\s*$" % _ZH_GAP, _ZH_CLAUSE_END),),
}


def _compiled(table):
    return dict((k, tuple(re.compile(unicodedata.normalize("NFC", p)) if p else None for p in v))
                for k, v in table.items())


OPPOSITE_CONTEXT = _compiled(OPPOSITE_CONTEXT)
REMOVAL_CONTEXT = _compiled(REMOVAL_CONTEXT)
OPPOSITE_AROUND = dict((k, tuple(tuple(re.compile(unicodedata.normalize("NFC", p)) for p in pair) for pair in v))
                       for k, v in OPPOSITE_AROUND.items())
REMOVAL_AROUND = dict((k, tuple(tuple(re.compile(unicodedata.normalize("NFC", p)) for p in pair) for pair in v))
                      for k, v in REMOVAL_AROUND.items())

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


def _compound(primary, word):
    """A pattern for the last word of a lock target in a compounding language: its stem at the
    start of a word, then the rest of the compound in the group "cx" ("Sperre" in "Sperrdauer",
    "lås" in "Låsstatus", "vergrendeling" in "Vergrendelstatus")."""
    stem = word
    for ending in COMPOUNDING[primary]:
        if word.endswith(ending) and len(word) - len(ending) >= 3:
            stem = word[:-len(ending)]
            break
    return re.escape(stem) + r"(?P<cx>[^\W\d_]*)"


def _loose(word, group=False):
    """A pattern for one word of a target in any form: its first letters (all but the last three,
    and at least four) at the start of a word, then any letters ("bloque" in "bloqueando", "trav"
    in "travando", "zablok" in "zablokowaniem")."""
    stem = word[:max(min(len(word), 4), len(word) - 3)]
    return re.escape(stem) + ("(?P<cx>[^\\W\\d_]*)" if group else "[^\\W\\d_]*")


# Indonesian pe(N)-...-an nouns of a verb root ("penguncian" from "kunci", "pembukaan" from "buka"):
# the nasal of peN- takes the place of the root's first sound. Used for a loose match (the English
# has an -ing form: "Locking failed" -> "Penguncian gagal"). Roots that already carry a prefix
# (me-, di-, ter-, ber-, pe-) or are negation words are not nominalised.
NOMINALISING = {"id"}
_ID_PREFIXED = re.compile("^(?:me|di|ter|ber|pe)")


def pen_an(root):
    """The Indonesian pe(N)-root-an noun of a verb root: kunci -> penguncian, buka -> pembukaan,
    tutup -> penutupan, simpan -> penyimpanan, pakai -> pemakaian, aktif -> pengaktifan,
    lepas -> pelepasan."""
    first = root[:1]
    if first in "bfv":
        stem = "pem" + root
    elif first == "p":
        stem = "pem" + root[1:]
    elif first in "dcjz":
        stem = "pen" + root
    elif first == "t":
        stem = "pen" + root[1:]
    elif first == "s":
        stem = "peny" + root[1:]
    elif first == "k":
        stem = "peng" + root[1:]
    elif first in "gh" or first in "aeiou":
        stem = "peng" + root
    else:
        stem = "pe" + root
    return stem + "an"


def _nominal(primary, word):
    """A pattern for the nominal form of a target's first word, or None (see NOMINALISING)."""
    if primary not in NOMINALISING or len(word) < 3 or _ID_PREFIXED.match(word) or \
            word in NEGATION.get(primary, (set(), ()))[0]:
        return None
    return re.escape(pen_an(word)) + "(?:nya)?"


def term_found(primary, script, term, text, opposite=True, removal=True, loose=False):
    """Whether a glossary target appears in a text, matched the way the language writes words.

    Spaced scripts: the target starts a word ("bloquear" is not inside "desbloquear"), and ends one
    unless the language takes suffixes or inflects (INFLECTING). Unspaced scripts: anywhere.
    With opposite (a lock term), a match next to words that make it the opposite term
    (OPPOSITE_CONTEXT, OPPOSITE_AROUND) does not count; with removal as well, nor does one next to
    words that remove or turn off the lock (REMOVAL_CONTEXT, REMOVAL_AROUND). A lock term in a
    compounding language (COMPOUNDING) may start a compound, unless the rest of the compound
    removes the lock (COMPOUND_OPPOSITE: "Sperraufhebung").
    With loose (the English has only an -s or -ing form of a lock verb: "Locking…"), each word of
    the target matches in any form that starts like it (_loose), still at the start of a word and
    still subject to the contexts above; in Indonesian its first word may also take its pe(N)-...-an
    noun form (pen_an: "Penguncian gagal", "Pembukaan kunci gagal").
    """
    text, term = normalize(text), normalize(term)
    if primary == "el":
        text, term = _strip_accents(text), _strip_accents(term)
    if not term.split():
        return False
    compound = opposite and primary in COMPOUNDING and script not in UNSPACED_SCRIPTS
    if script in UNSPACED_SCRIPTS:
        body = r"\s*".join(r"\s*".join(re.escape(c) for c in p) for p in term.split())
        pattern = body
    else:
        words = term.split()
        parts = [(_loose(p) if loose else _inflected(primary, p)) for p in words]
        if loose:
            parts[-1] = _loose(words[-1], group=compound)
            nominal = _nominal(primary, words[0])
            if nominal:  # "Penguncian" (kunci), "Pembukaan kunci" (buka kunci)
                parts[0] = "(?:%s|%s)" % (nominal, parts[0])
        elif compound:
            parts[-1] = _compound(primary, words[-1])
        body = r"\s+".join(parts)
        end = "" if loose or compound or primary in SUFFIXING or body.endswith("{0,4}") else r"(?![^\W_])"
        pattern = r"(?<![^\W_])" + PROCLITICS.get(primary, "") + body + end
    contexts, around = [], []
    if opposite:
        contexts.append(OPPOSITE_CONTEXT.get(primary, (None, None)))
        around.extend(OPPOSITE_AROUND.get(primary, ()))
        if removal:
            contexts.append(REMOVAL_CONTEXT.get(primary, (None, None)))
            around.extend(REMOVAL_AROUND.get(primary, ()))
    for m in re.finditer(pattern, text, re.UNICODE):
        head, tail = text[:m.start()], text[m.end():]
        if compound and m.group("cx") and COMPOUND_OPPOSITE[primary].search(m.group("cx")):
            continue
        if any((b is not None and b.search(head)) or (a is not None and a.search(tail)) for b, a in contexts):
            continue
        if any(b.search(head) and a.search(tail) for b, a in around):
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
