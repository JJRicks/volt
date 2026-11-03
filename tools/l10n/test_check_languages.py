"""Per-language checks with real sentences: glossary word boundaries, negation and Latin loans.

Each language gets the same safety keys and a "lock" safety glossary term, so a draft is checked the
way check.py checks a real wave-3 or RTL draft. Nothing here reaches the network or reads a key.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
import council_fixture  # noqa: E402
import fontmetrics  # noqa: E402
import lexicon  # noqa: E402
import pack  # noqa: E402
import translib  # noqa: E402

STRINGS = (
    '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">\n'
    '    <string name="langx_lock_now" volt:safety="lock">Lock scooter</string>\n'
    '    <string name="langx_lock_level" volt:safety="lock">Lock at speed level 1</string>\n'
    '    <string name="langx_unknown" volt:safety="lock">Lock state unknown</string>\n'
    '    <string name="langx_unable" volt:safety="lock">Unable to lock the scooter</string>\n'
    '    <string name="langx_wireless" volt:safety="lock">Wireless lock</string>\n'
    '    <string name="langx_open_app">Open the app over Wi-Fi</string>\n'
    '    <string name="langx_bluetooth">Turn on Bluetooth</string>\n'
    '</resources>\n')

# tag: (folder, endonym, plurals, script, rtl, lock targets)
LANGUAGES = {
    "zh-Hans": ("values-b+zh+Hans", "简体中文", "other", "Hans", "false", "锁定 | 上锁 | 锁"),
    "ja": ("values-ja", "日本語", "other", "Jpan", "false", "ロック | 施錠"),
    "ru": ("values-ru", "Русский", "one,few,many,other", "Cyrl", "false", "заблокировать | блокировка | блокировки"),
    "pl": ("values-pl", "Polski", "one,few,many,other", "Latn", "false", "zablokować | zablokuj | blokada | blokady"),
    "es": ("values-es", "Español", "one,many,other", "Latn", "false", "bloquear | bloqueo | bloqueado"),
    "de": ("values-de", "Deutsch", "one,other", "Latn", "false", "sperren | Sperre | gesperrt"),
    "ar": ("values-ar", "العربية", "zero,one,two,few,many,other", "Arab", "true", "قفل | اقفل"),
}

GLOSSARY_HEAD = "# Test glossary.\nid\tterm\tdefinition\tpos\tdnt\tsafety\tsource\n"


class LanguageChecksTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = council_fixture.FixtureRepo()
        cls.repo.write("res/values/strings_langx.xml", STRINGS)
        ru = cls.repo.read("l10n/lang/ru.tsv")
        for tag, (folder, endonym, plurals, script, rtl, lock) in LANGUAGES.items():
            if tag not in ("ru", "es", "de"):
                cls.repo.write("l10n/lang/%s.tsv" % tag, ru.replace("\tru\n", "\t%s\n" % tag)
                               .replace("values-ru", folder).replace("Русский", endonym)
                               .replace("one,few,many,other", plurals).replace("Cyrl", script)
                               .replace("rtl\tfalse", "rtl\t" + rtl)
                               .replace("font\tsystem", "font\t" + ("saira" if script == "Latn" else "system")))
            path = "l10n/glossary/%s.tsv" % tag
            existing = cls.repo.read(path) if os.path.exists(cls.repo.path(path)) else GLOSSARY_HEAD
            cls.repo.write(path, existing + "lock\t%s\tLock.\tverb\tno\tlock\tfixture\n" % lock)
        measurer = fontmetrics.Measurer(cls.repo.paths.font(), use_java=False)
        cls.font = fontmetrics.Font(cls.repo.paths.font())
        cls.packs = dict((tag, pack.build_pack(cls.repo.paths, tag, "langx", measurer)) for tag in LANGUAGES)

    @classmethod
    def tearDownClass(cls):
        cls.repo.close()

    def findings(self, tag, key, text):
        return check.check_targets(self.packs[tag], {key: ("string", {"other": text}, [])}, self.font, None)[key]

    def errors(self, tag, key, text):
        return sorted(set(f["check"] for f in self.findings(tag, key, text) if f["severity"] == check.ERROR))

    def warnings(self, tag, key, text):
        return sorted(set(f["check"] for f in self.findings(tag, key, text) if f["severity"] == check.WARN))

    def assertClean(self, tag, key, text):
        self.assertEqual(self.errors(tag, key, text), [], (tag, key, text, self.findings(tag, key, text)))

    # Glossary: whole words only where the script separates words.

    def test_glossary_in_scripts_without_word_spaces(self):
        self.assertClean("zh-Hans", "langx_lock_now", "锁定滑板车")
        self.assertClean("zh-Hans", "langx_lock_now", "给滑板车上锁")
        self.assertClean("zh-Hans", "langx_lock_level", "以速度级别 1 锁定滑板车")
        self.assertClean("ja", "langx_lock_now", "スクーターをロック")
        self.assertClean("ja", "langx_lock_now", "スクーターをロックする")
        self.assertClean("ja", "langx_lock_level", "速度レベル1でスクーターを施錠")
        # The opposite term is not the glossary's term: 解锁 (unlock) beside 锁, ロック解除 after ロック.
        self.assertEqual(self.errors("zh-Hans", "langx_lock_now", "解锁滑板车"), ["glossary"])
        self.assertEqual(self.errors("ja", "langx_lock_now", "スクーターのロック解除"), ["glossary"])
        self.assertEqual(self.errors("ja", "langx_lock_now", "スクーターを開ける"), ["glossary"])

    def test_glossary_whole_words_in_spaced_scripts(self):
        self.assertClean("ru", "langx_lock_now", "Заблокировать самокат")
        self.assertClean("pl", "langx_lock_now", "Zablokuj hulajnogę")
        self.assertClean("es", "langx_lock_now", "Bloquear patinete")
        self.assertClean("de", "langx_lock_now", "Roller sperren")
        self.assertClean("ar", "langx_lock_now", "قفل السكوتر")
        # Arabic joins the article and conjunctions to the word.
        self.assertClean("ar", "langx_lock_level", "القفل عند مستوى السرعة 1")
        # The unlock words contain the lock words; they are not the term.
        self.assertEqual(self.errors("es", "langx_lock_now", "Desbloquear patinete"), ["glossary"])
        self.assertEqual(self.errors("de", "langx_lock_now", "Roller entsperren"), ["glossary"])
        self.assertEqual(self.errors("ru", "langx_lock_now", "Разблокировать самокат"), ["glossary"])
        self.assertEqual(self.errors("pl", "langx_lock_now", "Odblokuj hulajnogę"), ["glossary"])

    def test_term_found_per_script(self):
        self.assertTrue(lexicon.term_found("zh", "Hans", "锁定", "锁定滑板车"))
        self.assertTrue(lexicon.term_found("zh", "Hans", "上锁", "上 锁"))
        self.assertFalse(lexicon.term_found("zh", "Hans", "锁", "解锁滑板车"))
        self.assertTrue(lexicon.term_found("ja", "Jpan", "ロック", "スクーターをロックする"))
        self.assertFalse(lexicon.term_found("ja", "Jpan", "ロック", "ロック解除"))
        # Suffixing languages: the term starts a word.
        self.assertTrue(lexicon.term_found("tr", "Latn", "kilit", "Scooter'ı kilitle"))
        self.assertFalse(lexicon.term_found("es", "Latn", "bloquear", "bloqueart"))
        self.assertFalse(lexicon.term_found("es", "Latn", "bloquear", "desbloquear"))
        self.assertTrue(lexicon.term_found("ar", "Arab", "قفل", "والقفل"))

    # Polarity: word-level negation for zh and ja.

    def test_zh_negation_is_word_level(self):
        # 级别 (level), 无线 (wireless), 识别, 非常 contain a negation character but negate nothing.
        self.assertClean("zh-Hans", "langx_lock_level", "以速度级别 1 锁定滑板车")
        self.assertClean("zh-Hans", "langx_wireless", "无线锁定")
        self.assertClean("zh-Hans", "langx_lock_now", "识别后锁定滑板车")
        self.assertEqual(lexicon.unspaced_negations("zh", "以速度级别 1 锁定，非常安全"), [])
        # A real negation added to "Lock scooter" is an error in a safety key.
        self.assertEqual(self.errors("zh-Hans", "langx_lock_now", "不要锁定滑板车"), ["polarity"])
        self.assertEqual(self.errors("zh-Hans", "langx_lock_now", "别锁定滑板车"), ["polarity"])
        self.assertEqual(self.errors("zh-Hans", "langx_lock_level", "速度级别 1 时未锁定"), ["polarity"])
        # The un-words keep their negation.
        self.assertClean("zh-Hans", "langx_unknown", "锁定状态未知")
        self.assertClean("zh-Hans", "langx_unable", "无法锁定滑板车")
        self.assertEqual(self.errors("zh-Hans", "langx_unknown", "锁定状态已知"), ["polarity"])
        self.assertEqual(self.errors("zh-Hans", "langx_unable", "可以锁定滑板车"), ["polarity"])

    def test_ja_negation_is_word_level(self):
        self.assertClean("ja", "langx_unknown", "ロック状態が不明")
        self.assertClean("ja", "langx_unable", "スクーターをロックできません")
        self.assertClean("ja", "langx_wireless", "無線でロック")
        self.assertEqual(self.errors("ja", "langx_lock_now", "スクーターをロックしない"), ["polarity"])
        self.assertEqual(self.errors("ja", "langx_unable", "スクーターをロックできます"), ["polarity"])
        self.assertEqual(lexicon.unspaced_negations("ja", "バッテリーが少ないので必ずロック"), [])

    # Polarity: native negated forms are a negation in a safety key.

    def test_native_negated_forms_pass_a_safety_key(self):
        self.assertClean("ru", "langx_unknown", "Состояние блокировки неизвестно")
        self.assertClean("ru", "langx_unable", "Невозможно заблокировать самокат")
        self.assertClean("pl", "langx_unknown", "Stan blokady nieznany")
        self.assertClean("pl", "langx_unable", "Nie można zablokować hulajnogi")
        self.assertClean("es", "langx_unable", "Imposible bloquear el patinete")
        self.assertClean("es", "langx_unknown", "Estado de bloqueo desconocido")
        self.assertClean("de", "langx_unknown", "Status der Sperre unbekannt")
        self.assertClean("de", "langx_unable", "Roller kann nicht gesperrt werden")
        self.assertClean("ar", "langx_unknown", "حالة القفل غير معروفة")
        self.assertClean("ar", "langx_unknown", "حالة القفل غَير معروفة")
        self.assertClean("ar", "langx_unable", "تعذر قفل السكوتر")
        self.assertClean("ar", "langx_unable", "لا يمكن قفل السكوتر")

    def test_words_that_only_look_negated_do_not(self):
        # "несколько" (several), "niebieski" (blue), "información", "unten" (below) negate nothing.
        self.assertEqual(self.errors("ru", "langx_unknown", "Несколько состояний блокировки"), ["polarity"])
        self.assertEqual(self.errors("pl", "langx_unknown", "Stan blokady niebieski"), ["polarity"])
        self.assertEqual(self.errors("es", "langx_unknown", "Información del estado de bloqueo"), ["polarity"])
        self.assertEqual(self.errors("de", "langx_unknown", "Status der Sperre unten"), ["polarity"])
        self.assertEqual(self.errors("ar", "langx_unable", "يمكن قفل السكوتر"), ["polarity"])
        # The opposite glossary term is not a negation either.
        self.assertIn("polarity", self.errors("es", "langx_unable", "Se puede desbloquear el patinete"))
        self.assertFalse(lexicon.native_negated("ru", "безопасный"))
        self.assertTrue(lexicon.native_negated("ru", "небезопасный"))

    def test_added_negation_in_spaced_scripts(self):
        self.assertEqual(self.errors("ar", "langx_lock_now", "لا تقفل السكوتر"), ["glossary", "polarity"])
        self.assertEqual(self.errors("ru", "langx_lock_now", "Не блокировать самокат"), ["glossary", "polarity"])
        self.assertEqual(self.errors("de", "langx_lock_now", "Roller nicht sperren"), ["polarity"])

    # Script: Latin loans without case, hyphen-aware; kanji belong to Japanese.

    def test_latin_loans(self):
        self.assertClean("zh-Hans", "langx_open_app", "通过 Wi-Fi 打开 App")
        self.assertEqual(self.warnings("zh-Hans", "langx_open_app", "通过 Wi-Fi 打开 App"), [])
        self.assertClean("ja", "langx_open_app", "Wi‑Fi でアプリを開く")
        self.assertClean("ar", "langx_open_app", "افتح التطبيق عبر Wi-Fi")
        self.assertClean("ru", "langx_open_app", "Откройте приложение через Wi-Fi")
        # A loan the English lacks warns; a word from another language is still an error.
        self.assertClean("zh-Hans", "langx_bluetooth", "打开 Bluetooth（WiFi 与 App 无关）")
        self.assertIn("script", self.warnings("ar", "langx_bluetooth", "شغّل البلوتوث عبر App"))
        self.assertEqual(self.errors("zh-Hans", "langx_open_app", "通过 Wi-Fi 打开 Anwendung"), ["script"])
        self.assertEqual(self.errors("ru", "langx_open_app", "Откройте Aplicación через Wi-Fi"), ["script"])
        self.assertEqual(self.errors("ar", "langx_open_app", "افتح Wi-Anwendung"), ["script"])

    def test_japanese_mixes_kanji_and_kana(self):
        self.assertNotIn("script", self.errors("ja", "langx_lock_level", "速度レベル1でロック"))
        self.assertEqual(self.errors("ja", "langx_lock_now", "스쿠터 ロック"), ["script"])

    # Unlock written as the lock word plus another word or particle is not the lock term.

    def test_unlock_phrasings_in_fixture_languages(self):
        for tag, text in (("zh-Hans", "解除锁定滑板车"), ("zh-Hans", "解除滑板车锁定"), ("zh-Hans", "打开滑板车的锁"),
                          ("zh-Hans", "滑板车锁定已解除"), ("ja", "スクーターのロックを解除"),
                          ("ja", "スクーターのロックを解除する"), ("ja", "ロックを外す"), ("ar", "فتح القفل"),
                          ("ar", "فتح قفل السكوتر"), ("ar", "إلغاء قفل السكوتر"), ("ar", "فك قفل السكوتر"),
                          ("ar", "اضغط لفتح القفل")):
            self.assertEqual(self.errors(tag, "langx_lock_now", text), ["glossary"], (tag, text))
        # The same words in another role do not make the lock term its opposite.
        self.assertClean("zh-Hans", "langx_lock_now", "取消骑行后锁定滑板车")
        self.assertClean("zh-Hans", "langx_lock_now", "解除报警后锁定滑板车")
        self.assertClean("ja", "langx_lock_now", "スクーターをロックする")
        self.assertClean("ar", "langx_lock_now", "اضغط للقفل")

    def test_inflected_glossary_targets(self):
        self.assertClean("ru", "langx_lock_now", "Включите блокировку самоката")
        self.assertClean("es", "langx_lock_now", "Patinete bloqueada")
        self.assertEqual(self.errors("ru", "langx_lock_now", "Включите разблокировку"), ["glossary"])
        self.assertEqual(self.errors("es", "langx_lock_now", "Patinete desbloqueada"), ["glossary"])

    def test_zh_ja_ordinary_words_are_not_negation(self):
        self.assertEqual(lexicon.unspaced_negations("ja", "バッテリーが20%未満のときにロック"), [])
        self.assertEqual(lexicon.unspaced_negations("ja", "スクーターをロックします。すみません"), [])
        self.assertClean("zh-Hans", "langx_lock_now", "锁定别名")
        # A negation that extends an ordinary word is still a negation.
        self.assertEqual(lexicon.unspaced_negations("zh", "锁不管用"), ["不管用"])
        self.assertEqual(lexicon.unspaced_negations("zh", "请不断开连接"), ["不断开"])
        self.assertEqual(self.errors("zh-Hans", "langx_lock_now", "滑板车锁不管用"), ["polarity"])

    def test_opposite_actions_and_ordinary_prefix_words_are_not_native_negation(self):
        self.assertIn("polarity", self.errors("es", "langx_unable", "Desactivar el bloqueo del patinete"))
        self.assertFalse(lexicon.native_negated("es", "desactivar", {"activar"}))
        self.assertEqual(self.errors("ru", "langx_unknown", "Некоторые блокировка"), ["polarity"])
        self.assertEqual(self.errors("pl", "langx_unknown", "Blokada niedawno"), ["polarity"])
        self.assertEqual(self.errors("de", "langx_unknown", "Sperre Unfall"), ["polarity"])
        self.assertClean("es", "langx_unable", "No se puede bloquear el patinete")

    def test_arabic_words_that_only_look_negated(self):
        self.assertEqual(check.target_negation("ar", "غيّر صوت الإنذار")[0], [])
        self.assertEqual(check.target_negation("ar", "غَيِّرْ صوت الإنذار")[0], [])
        # Without the shadda the imperative is the negation's spelling; that stays a negation.
        self.assertEqual(check.target_negation("ar", "غير صوت الإنذار")[0], ["غير"])
        self.assertEqual(check.target_negation("ar", "حالة القفل غير معروفة")[0], ["غير"])


SCRIPTS = {"el": "Grek", "hi": "Deva", "bn": "Beng", "ar": "Arab", "ur": "Arab", "zh": "Hans", "ja": "Jpan",
           "ru": "Cyrl", "uk": "Cyrl", "ko": "Kore"}


def _key_findings(tag, english, target, glossary=None, kind="string", plurals=("one", "other"), term="Lock"):
    entry = {"key": "k", "kind": kind, "english": english, "safety": "lock", "dnt": [], "formatted": True,
             "glossary": [{"term": term, "target": glossary, "safety": "lock", "dnt": False}] if glossary else []}
    language = {"tag": tag, "plurals": list(plurals), "script": SCRIPTS.get(tag, "Latn"), "font": "system"}
    return check.check_key(entry, language, kind, check._items(kind, target))


def _errors(*args, **kwargs):
    return sorted(set(f["check"] for f in _key_findings(*args, **kwargs) if f["severity"] == check.ERROR))


class MoreLanguagesTest(unittest.TestCase):
    """Safety keys checked directly for languages the fixture repository does not set up."""

    def test_unlock_as_lock_word_plus_another_word(self):
        cases = (("sv", ["lås", "låsa"], "Lås sparkcykeln", "Lås upp sparkcykeln"),
                 ("nb", ["lås", "låse"], "Lås sparkesykkelen", "Lås opp sparkesykkelen"),
                 ("id", ["kunci"], "Kunci skuter", "Buka kunci skuter"),
                 ("id", ["kunci"], "Kunci skuter", "Membuka kunci skuter"),
                 ("ur", ["لاک"], "اسکوٹر لاک کریں", "اسکوٹر ان لاک کریں"),
                 ("hi", ["लॉक"], "स्कूटर लॉक करें", "स्कूटर का लॉक खोलें"),
                 ("bn", ["লক"], "স্কুটার লক করুন", "স্কুটারের লক খুলুন"))
        for tag, targets, good, bad in cases:
            self.assertEqual(_errors(tag, "Lock scooter", good, targets), [], (tag, good))
            self.assertEqual(_errors(tag, "Lock scooter", bad, targets), ["glossary"], (tag, bad))
        self.assertFalse(lexicon.term_found("ko", "Hang", "잠금", "잠금해제"))
        self.assertFalse(lexicon.term_found("ko", "Hang", "잠금", "잠금을 해제"))
        self.assertTrue(lexicon.term_found("ko", "Hang", "잠금", "스쿠터 잠금"))

    def test_glossary_inflection_and_arabic_lil(self):
        self.assertEqual(_errors("ar", "Tap to lock", "اضغط للقفل", ["قفل"]), [])
        self.assertEqual(_errors("pl", "Scooter locked", "Hulajnoga zablokowana", ["zablokowany", "zablokowane"]), [])
        self.assertEqual(_errors("es", "Screen locked", "Pantalla bloqueada", ["bloqueado"]), [])
        # A short stem does not stretch to another word ("verrou" is not in "verre").
        self.assertFalse(lexicon.term_found("fr", "Latn", "verrou", "verre"))
        self.assertTrue(lexicon.term_found("el", "Grek", "κλείδωμα", "Αποτυχία κλειδώματος"))

    def test_natural_negations_pass(self):
        cases = (("bn", "The scooter did not respond", "স্কুটার সাড়া দেয়নি"),
                 ("bn", "Lock was not confirmed", "লক নিশ্চিত হয়নি"),
                 ("bn", "Unable to lock the scooter", "স্কুটার লক করা যায়নি"),
                 ("bn", "Unable to lock the scooter", "স্কুটার লক করতে পারেনি"),
                 ("bn", "Lock state unverified", "লক অবস্থা যাচাই করা হয়নি"),
                 ("sv", "Lock state unverified", "Låsstatus overifierad"),
                 ("sv", "Lock state unverified", "Låsstatus obekräftad"),
                 ("el", "Lock state unverified", "Ανεπιβεβαίωτη κατάσταση κλειδώματος"),
                 ("el", "Unable to lock the scooter", "Αδυναμία κλειδώματος του πατινιού"),
                 ("hi", "Lock state unverified", "लॉक स्थिति असत्यापित"),
                 ("ur", "Lock was not confirmed", "لاک کی تصدیق نہیں ہوئی"))
        for tag, english, text in cases:
            self.assertEqual(_errors(tag, english, text), [], (tag, text, _key_findings(tag, english, text)))
        # Ordinary Bengali words ending in নি are not a negation.
        self.assertEqual(_errors("bn", "Lock scooter", "তিনি স্কুটার লক করবেন"), [])
        self.assertEqual(_errors("bn", "Lock was not confirmed", "লক নিশ্চিত হয়েছে"), ["polarity"])

    def test_urdu_negation_and_plural_one(self):
        self.assertEqual(_errors("ur", "Lock was not confirmed", "لاک کی تصدیق ہو گئی"), ["polarity"])
        self.assertEqual(_errors("ur", {"one": "%1$d scooter locked", "other": "%1$d scooters locked"},
                                 {"one": "ایک اسکوٹر لاک ہے", "other": "%1$d اسکوٹر لاک ہیں"}, kind="plural"), [])

    def test_ordinary_words_are_not_added_negation(self):
        self.assertEqual(_errors("ar", "Change the alarm sound", "غيّر صوت الإنذار"), [])
        self.assertEqual(_errors("ar", "Alarm when battery falls below 20%%", "تنبيه عندما تنخفض البطارية دون 20%%"), [])
        self.assertEqual(_errors("ja", "Alarm when battery is below 20%%", "バッテリーが20%%未満のときにアラーム"), [])
        self.assertEqual(_errors("it", "Lock as soon as the scooter stops", "Blocca non appena il monopattino si ferma"),
                         [])
        self.assertEqual(_errors("it", "Lock the scooter", "Non bloccare il monopattino"), ["polarity"])
        # The Arabic zero form says "no scooters" naturally: a warning for the judge, not an error.
        english = {"one": "%1$d scooter locked", "other": "%1$d scooters locked"}
        target = {"zero": "لا توجد سكوترات مقفلة", "one": "سكوتر واحد مقفل", "two": "سكوتران مقفلان",
                  "few": "%1$d سكوترات مقفلة", "many": "%1$d سكوترًا مقفلًا", "other": "%1$d سكوتر مقفل"}
        self.assertEqual(_errors("ar", english, target, kind="plural",
                                 plurals=("zero", "one", "two", "few", "many", "other")), [])
        target["other"] = "%1$d سكوتر غير مقفل"
        self.assertEqual(_errors("ar", english, target, kind="plural",
                                 plurals=("zero", "one", "two", "few", "many", "other")), ["polarity"])

    # Unlock said as "remove / lift / turn off the lock", and the lock said to be removed.

    LOCK_REMOVED = (
        ("es", "Lock scooter", "Quitar el bloqueo del patinete", ["bloquear", "bloqueo"]),
        ("es", "Lock scooter", "Levantar el bloqueo del patinete", ["bloquear", "bloqueo"]),
        ("es", "The lock is on", "Bloqueo desactivado", ["bloqueo", "bloqueado"]),
        ("es", "The lock is on", "Bloqueo del patinete quitado", ["bloqueo", "bloqueado"]),
        ("pt-BR", "Lock scooter", "Remover o bloqueio do patinete", ["bloquear", "bloqueio"]),
        ("pt-PT", "Lock scooter", "Retirar o bloqueio da trotinete", ["bloquear", "bloqueio"]),
        ("pt-BR", "The lock is on", "Bloqueio desativado", ["bloqueio", "bloqueado"]),
        ("fr", "Lock scooter", "Retirer le verrouillage de la trottinette", ["verrouiller", "verrouillage"]),
        ("fr", "Lock scooter", "Lever le verrou", ["verrouiller", "verrou"]),
        ("fr", "Lock scooter", "Désactiver l'antivol", ["verrouiller", "antivol"]),
        ("fr", "The lock is on", "Verrouillage désactivé", ["verrouillé", "verrouillage"]),
        ("it", "Lock scooter", "Rimuovi il blocco del monopattino", ["bloccare", "blocca", "blocco"]),
        ("it", "Lock scooter", "Togli il blocco", ["bloccare", "blocca", "blocco"]),
        ("it", "The lock is on", "Blocco disattivato", ["bloccato", "blocco"]),
        ("de", "Lock scooter", "Sperre des Rollers aufheben", ["sperren", "Sperre"]),
        ("de", "Lock scooter", "Sperre lösen", ["sperren", "Sperre"]),
        ("de", "Lock scooter", "Entfernen Sie die Sperre", ["sperren", "Sperre"]),
        ("de", "Lock scooter", "Hebe die Sperre auf", ["sperren", "Sperre"]),
        ("de", "The lock is on", "Sperre aufgehoben", ["gesperrt", "Sperre"]),
        ("nl", "Lock scooter", "Vergrendeling opheffen", ["vergrendelen", "vergrendeling"]),
        ("nl", "Lock scooter", "Schakel de vergrendeling uit", ["vergrendelen", "vergrendeling"]),
        ("nl", "Lock scooter", "Verwijder de vergrendeling", ["vergrendelen", "vergrendeling"]),
        ("nl", "The lock is on", "Vergrendeling uitgeschakeld", ["vergrendeld", "vergrendeling"]),
        ("pl", "Lock scooter", "Zdejmij blokadę hulajnogi", ["zablokuj", "blokada"]),
        ("pl", "Lock scooter", "Wyłącz blokadę hulajnogi", ["zablokuj", "blokada"]),
        ("pl", "Lock scooter", "Usuń blokadę", ["zablokuj", "blokada"]),
        ("pl", "The lock is on", "Blokada hulajnogi wyłączona", ["zablokowana", "blokada"]),
        ("pl", "The lock is on", "Blokada została zdjęta", ["zablokowana", "blokada"]),
        ("sv", "Lock scooter", "Ta bort låset", ["lås", "låset"]),
        ("sv", "Lock scooter", "Inaktivera låset", ["lås", "låset"]),
        ("sv", "The lock is on", "Låset inaktiverat", ["låst", "låset"]),
        ("id", "Lock scooter", "Lepaskan kunci skuter", ["kunci"]),
        ("id", "Lock scooter", "Nonaktifkan kunci", ["kunci"]),
        ("id", "The lock is on", "Kunci dinonaktifkan", ["terkunci", "kunci"]),
        # Later-wave languages, where the inflected glossary match had let these pass.
        ("ru", "Lock scooter", "Снять блокировку самоката", ["заблокировать", "блокировка", "блокировки"]),
        ("ru", "Lock scooter", "Отключить блокировку самоката", ["заблокировать", "блокировка", "блокировки"]),
        ("uk", "Lock scooter", "Зняти блокування самоката", ["заблокувати", "блокування"]),
        ("el", "Lock scooter", "Άρση κλειδώματος πατινιού", ["κλείδωμα", "κλειδώνω"]),
        ("ja", "Lock scooter", "スクーターのロックの解除", ["ロック"]),
        ("ja", "Lock scooter", "ロックをオフにする", ["ロック"]),
        ("zh", "Lock scooter", "关闭滑板车锁定", ["锁定", "锁"]),
        ("zh", "Lock scooter", "打开滑板车的锁", ["锁定", "锁"]),
    )

    LOCK_KEPT = (
        ("es", "Lock scooter", "Bloquear el patinete", ["bloquear", "bloqueo"]),
        ("es", "Lock and turn off the lights", "Bloquear y apagar las luces", ["bloquear"]),
        ("es", "Lock when the app opens", "Bloquear al abrir la app", ["bloquear"]),
        ("pt-BR", "Lock and turn off", "Bloquear e desligar", ["bloquear"]),
        ("fr", "Lock and switch off the lights", "Verrouiller et éteindre les feux", ["verrouiller"]),
        ("it", "Lock and turn off", "Blocca e spegni", ["blocca"]),
        ("de", "Lock remotely", "Sperren aus der Ferne", ["sperren"]),
        ("de", "Lock and switch off the lights", "Roller sperren und Licht ausschalten", ["sperren"]),
        ("nl", "Lock remotely", "Op afstand vergrendelen", ["vergrendelen"]),
        ("nl", "Lock and switch off", "Vergrendelen en uitschakelen", ["vergrendelen"]),
        ("pl", "Lock and turn off the lights", "Zablokuj i wyłącz światła", ["zablokuj"]),
        ("pl", "Lock when the motor stops", "Blokada przy wyłączonym silniku", ["blokada"]),
        ("sv", "Lock scooter", "Lås sparkcykeln", ["lås"]),
        ("id", "Lock if it dies", "Kunci jika mati", ["kunci"]),
        ("ru", "Turn on the lock", "Включите блокировку", ["блокировка"]),
        ("zh", "Open the app's lock page", "打开应用的锁定页面", ["锁定"]),
        ("zh", "Turn on the lock feature", "打开锁定功能", ["锁定"]),
        ("ja", "Lock from outside", "ロックは外部から", ["ロック"]),
        # The English itself turns the lock off: the removal words are the right translation.
        ("es", "Turn off the lock", "Desactivar el bloqueo", ["bloquear", "bloqueo"]),
        ("pl", "Remove the lock", "Zdejmij blokadę", ["zablokuj", "blokada"]),
        ("de", "Disable the lock", "Sperre deaktivieren", ["sperren", "Sperre"]),
    )

    def test_lock_removed_is_not_the_lock_term(self):
        for tag, english, text, targets in self.LOCK_REMOVED:
            self.assertIn("glossary", _errors(tag, english, text, targets), (tag, text))

    def test_removal_words_in_other_roles_keep_the_lock_term(self):
        for tag, english, text, targets in self.LOCK_KEPT:
            self.assertNotIn("glossary", _errors(tag, english, text, targets),
                             (tag, text, _key_findings(tag, english, text, targets)))

    def test_contexts_apply_only_to_lock_terms(self):
        # "Turn off the alarm" and "turn on cruise control" are ordinary verbs next to other terms.
        for tag, english, text, targets, term in (
                ("es", "Turn off the alarm", "Desactivar la alarma", ["alarma"], "alarm"),
                ("pl", "Turn off the alarm", "Wyłącz alarm", ["alarm"], "alarm"),
                ("zh", "Turn on cruise control", "打开定速巡航", ["定速巡航"], "Cruise control"),
                ("zh", "Turn on the alarm", "打开警报", ["警报"], "alarm")):
            self.assertNotIn("glossary", _errors(tag, english, text, targets, term=term), (tag, text))

    def test_cjk_context_words_in_other_roles(self):
        self.assertTrue(lexicon.term_found("ko", "Kore", "잠금", "키보드 잠금 열쇠"))
        self.assertTrue(lexicon.term_found("ko", "Kore", "잠금", "잠금을 열심히"))
        self.assertFalse(lexicon.term_found("ko", "Kore", "잠금", "잠금을 열어"))
        self.assertFalse(lexicon.term_found("ja", "Jpan", "ロック", "ロックを外す"))
        self.assertTrue(lexicon.term_found("ja", "Jpan", "ロック", "ロックは外部から"))
        self.assertEqual(lexicon.unspaced_negations("zh", "数据不同步"), ["不同步"])
        self.assertEqual(lexicon.unspaced_negations("zh", "我不同意"), ["不同意"])
        self.assertEqual(lexicon.unspaced_negations("zh", "不同的颜色"), [])
        self.assertEqual(_errors("zh", "Data synced", "数据不同步"), ["polarity"])


# First-wave approved targets by en.tsv id, as a council-drafted l10n/glossary/<tag>.tsv would hold them.
FIRST_WAVE_TARGETS = {
    "es": {"lock": "bloquear | bloqueo", "locked": "bloqueado | bloqueada", "unlock": "desbloquear | desbloqueo",
           "unlocked": "desbloqueado | desbloqueada", "energy-recovery": "recuperación de energía",
           "energy-recovery-off": "Desactivada | Desactivado", "energy-recovery-low": "Baja | Bajo",
           "low-battery-alert": "alerta de batería baja", "charge-alert": "alerta de carga | alertas de carga",
           "alarm-sound": "sonido de alarma", "lock-duration": "duración del bloqueo", "scooter": "patinete",
           "cruise-control": "control de crucero"},
    "de": {"lock": "sperren | Sperre", "locked": "gesperrt", "unlock": "entsperren", "unlocked": "entsperrt",
           "energy-recovery-off": "Aus", "energy-recovery-low": "Niedrig",
           "low-battery-alert": "Warnung bei niedrigem Akkustand", "charge-alert": "Ladealarm",
           "alarm-sound": "Alarmton", "lock-duration": "Sperrdauer", "lock-bar": "Sperrleiste", "scooter": "Roller",
           "lock-state-unknown": "Sperrstatus unbekannt"},
    "fr": {"lock": "verrouiller | verrouillage", "unlock": "déverrouiller | déverrouillage", "scooter": "trottinette",
           "low-battery-alert": "alerte de batterie faible"},
    "it": {"lock": "bloccare | blocca | blocco", "unlock": "sbloccare | sblocca", "scooter": "monopattino",
           "low-battery-alert": "avviso batteria scarica"},
    "pt-BR": {"lock": "travar | trava | bloquear", "unlock": "destravar | desbloquear", "scooter": "patinete",
              "low-battery-alert": "alerta de bateria fraca"},
    "nl": {"lock": "vergrendelen | vergrendeling | vergrendel", "unlock": "ontgrendelen | ontgrendel",
           "scooter": "scooter", "low-battery-alert": "waarschuwing bij lage batterij"},
    "pl": {"lock": "zablokuj | zablokować | blokada", "unlock": "odblokuj | odblokować", "scooter": "hulajnoga",
           "low-battery-alert": "alert niskiego poziomu baterii"},
    "sv": {"lock": "lås | låsa", "unlock": "lås upp | låsa upp", "scooter": "skotern",
           "low-battery-alert": "varning för låg batterinivå"},
    "id": {"lock": "kunci | mengunci", "unlock": "buka kunci | membuka kunci", "scooter": "skuter",
           "low-battery-alert": "peringatan baterai lemah"},
}
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _real_terms(tag):
    targets = FIRST_WAVE_TARGETS[tag]
    rows = translib._read_glossary(os.path.join(REPO_ROOT, "l10n", "glossary", "en.tsv"))
    return [translib.Term(i, term, d, pos, dnt == "yes", safety if safety != "-" else "",
                          [t.strip() for t in targets[i].split("|")] if i in targets else None)
            for i, term, d, pos, dnt, safety, _ in rows]


def _real_findings(tag, english, text, safety="lock"):
    """check_key on a safety key whose glossary hits come from the real en.tsv, as pack.py builds them."""
    hits = [{"id": t.id, "term": t.term, "dnt": t.dnt, "safety": t.safety or None, "target": t.targets}
            for t in translib.glossary_hits(_real_terms(tag), english)]
    entry = {"key": "k", "kind": "string", "english": english, "safety": safety, "dnt": [], "formatted": True,
             "glossary": hits}
    language = {"tag": tag, "plurals": ["one", "other"], "script": "Latn", "font": "system"}
    return check.check_key(entry, language, "string", {"other": text})


def _real_errors(tag, english, text, safety="lock"):
    return sorted(set(f["check"] for f in _real_findings(tag, english, text, safety) if f["severity"] == check.ERROR))


class FirstWaveRealGlossaryTest(unittest.TestCase):
    """First-wave safety keys checked against the real English glossary (review round 4)."""

    def test_unlock_drafted_as_lock_fails(self):
        # en.tsv has an Unlock row, so the unlock word is required; the lock word alone fails.
        for tag, good, bad in (("es", "Desbloquear el patinete", "Bloquear el patinete"),
                               ("de", "Roller entsperren", "Roller sperren"),
                               ("fr", "Déverrouiller la trottinette", "Verrouiller la trottinette"),
                               ("it", "Sbloccare il monopattino", "Bloccare il monopattino"),
                               ("pt-BR", "Destravar o patinete", "Travar o patinete"),
                               ("nl", "Ontgrendel de scooter", "Vergrendel de scooter"),
                               ("pl", "Odblokuj hulajnogę", "Zablokuj hulajnogę"),
                               ("sv", "Lås upp skotern", "Lås skotern"),
                               ("id", "Buka kunci skuter", "Kunci skuter")):
            self.assertEqual(_real_errors(tag, "Unlock scooter", good), [],
                             (tag, good, _real_findings(tag, "Unlock scooter", good)))
            self.assertIn("glossary", _real_errors(tag, "Unlock scooter", bad), (tag, bad))
        # "proximity unlock" is its own term; the Unlock inside it is not required separately.
        hits = [t.id for t in translib.glossary_hits(_real_terms("es"), "Turn on proximity unlock")]
        self.assertIn("proximity-unlock", hits)
        self.assertNotIn("unlock", hits)

    def test_longest_term_wins(self):
        for tag, english, text in (("de", "Lock duration", "Sperrdauer"), ("de", "Lock bar", "Sperrleiste"),
                                   ("de", "Low battery alert", "Warnung bei niedrigem Akkustand"),
                                   ("es", "Low battery alert", "Alerta de batería baja")):
            self.assertEqual(_real_errors(tag, english, text, "alarm"), [],
                             (tag, text, _real_findings(tag, english, text, "alarm")))
        hits = [t.id for t in translib.glossary_hits(_real_terms("de"), "Lock duration")]
        self.assertEqual(hits, ["lock-duration"])
        # The longer term is still required.
        self.assertEqual(_real_errors("de", "Lock duration", "Fahrdauer"), ["glossary"])

    def test_lock_target_starts_a_compound(self):
        for tag, text, targets in (("de", "Sperrstatus unbekannt", ["sperren", "Sperre"]),
                                   ("de", "Gesperrter Roller", ["gesperrt"]),
                                   ("nl", "Vergrendelstatus onbekend", ["vergrendeling"]),
                                   ("nl", "Vergrendelingsstatus niet bevestigd", ["vergrendeling"]),
                                   ("sv", "Låsstatus okänd", ["lås", "låsa"]),
                                   ("sv", "Låstid", ["lås", "låsa"]),
                                   ("sv", "Låsstatus inte verifierad", ["lås", "låsa"])):
            self.assertTrue(any(lexicon.term_found(tag, "Latn", t, text) for t in targets), (tag, text))
        # Never after an unlock prefix, nor when the rest of the compound removes the lock.
        for tag, text, targets in (("de", "Entsperrstatus", ["sperren", "Sperre"]),
                                   ("de", "Sperraufhebung", ["sperren", "Sperre"]),
                                   ("de", "Sperre lösen", ["sperren", "Sperre"]),
                                   ("nl", "Ontgrendelstatus", ["vergrendeling", "vergrendel"]),
                                   ("nl", "Vergrendelingsopheffing", ["vergrendeling"]),
                                   ("sv", "Upplåst", ["lås", "låsa"]),
                                   ("sv", "Låsupplåsning", ["lås"]),
                                   ("sv", "Lås upp skotern", ["lås", "låsa"])):
            self.assertFalse(any(lexicon.term_found(tag, "Latn", t, text) for t in targets), (tag, text))
        # Other terms stay whole words.
        self.assertFalse(lexicon.term_found("de", "Latn", "Alarm", "Alarmton", opposite=False))

    def test_setting_value_only_in_its_own_sense(self):
        for tag, english, text in (("es", "Turn off charge alerts", "Desactivar las alertas de carga"),
                                   ("es", "Alarm sound off", "Sonido de alarma apagado"),
                                   ("de", "Turn off charge alerts", "Ladealarm ausschalten")):
            self.assertEqual(_real_errors(tag, english, text, "alarm"), [],
                             (tag, text, _real_findings(tag, english, text, "alarm")))
        es = _real_terms("es")
        self.assertIn("energy-recovery-off", [t.id for t in translib.glossary_hits(es, "Off")])
        self.assertIn("energy-recovery-off", [t.id for t in translib.glossary_hits(es, "Energy recovery: Off")])
        self.assertNotIn("energy-recovery-off", [t.id for t in translib.glossary_hits(es, "Alarm sound off")])
        self.assertEqual(_real_errors("es", "Off", "Apagada", "ride"), ["glossary"])

    STATE_INVERTED = (
        ("es", "Alarm sound on", "Sonido de alarma desactivado"),
        ("es", "Alarm sound off", "Sonido de alarma activado"),
        ("es", "Cruise control on", "Control de crucero desactivado"),
        ("fr", "Low battery alert enabled", "Alerte de batterie faible désactivée"),
        ("it", "Low battery alert enabled", "Avviso batteria scarica disattivato"),
        ("pt-BR", "Low battery alert enabled", "Alerta de bateria fraca desativado"),
        ("de", "Low battery alert on", "Warnung bei niedrigem Akkustand aus"),
        ("de", "Low battery alert off", "Warnung bei niedrigem Akkustand an"),
        ("pl", "Low battery alert enabled", "Alert niskiego poziomu baterii wyłączony"),
        ("nl", "Low battery alert enabled", "Waarschuwing bij lage batterij uitgeschakeld"),
        ("sv", "Low battery alert enabled", "Varning för låg batterinivå avstängd"),
        ("id", "Low battery alert enabled", "Peringatan baterai lemah dinonaktifkan"),
        ("es", "Lock: on", "Bloqueo: desactivado"),
        ("de", "Lock: on", "Sperre: aus"),
        ("de", "Lock: on", "Sperre: deaktiviert"),
        ("pl", "Lock: on", "Blokada: wyłączona"),
        ("nl", "Lock: on", "Vergrendeling: uit"),
        ("sv", "Lock: on", "Lås: av"),
        ("id", "Lock: on", "Kunci: dinonaktifkan"),
    )
    STATE_KEPT = (
        ("es", "Alarm sound on", "Sonido de alarma activado"),
        ("es", "Alarm sound off", "Sonido de alarma desactivado"),
        ("fr", "Low battery alert disabled", "Alerte de batterie faible désactivée"),
        ("de", "Low battery alert on", "Warnung bei niedrigem Akkustand an"),
        ("pl", "Low battery alert enabled", "Alert niskiego poziomu baterii włączony"),
        ("nl", "Low battery alert enabled", "Waarschuwing bij lage batterij ingeschakeld"),
        ("sv", "Low battery alert on", "Varning för låg batterinivå på"),
        ("id", "Low battery alert enabled", "Peringatan baterai lemah diaktifkan"),
        ("es", "Lock: on", "Bloqueo: activado"),
        # "on" as a preposition is no state; nor is an off word next to an English negation.
        ("de", "Alarm sounds on the phone", "Alarm ertönt auf dem Telefon, Display aus"),
        ("es", "Alarm not active", "Alarma no activa"),
        ("pl", "Lock and power down", "Zablokuj i wyłącz"),
        ("pl", "Alarm on", "Alarm włączony, tylko wyłącznie dźwięk"),
    )

    def test_on_off_state_inversion_fails_a_safety_key(self):
        for tag, english, text in self.STATE_INVERTED:
            self.assertIn("polarity", _real_errors(tag, english, text, "alarm"),
                          (tag, english, text, _real_findings(tag, english, text, "alarm")))
        for tag, english, text in self.STATE_KEPT:
            self.assertNotIn("polarity", _real_errors(tag, english, text, "alarm"),
                             (tag, english, text, _real_findings(tag, english, text, "alarm")))

    def test_lock_removed_after_a_status_colon(self):
        for tag, text, target in (("es", "Bloqueo — quitado", "bloqueo"), ("de", "Sperre: aus", "Sperre"),
                                  ("pl", "Blokada: wyłączona", "blokada"),
                                  ("nl", "Vergrendeling: uit", "vergrendeling"),
                                  ("sv", "Lås: av", "lås"), ("es", "Bloqueo (desactivado)", "bloqueo")):
            self.assertFalse(lexicon.term_found(tag, "Latn", target, text), (tag, text))
        for tag, text, target in (("de", "Sperre: Motor bleibt ausgeschaltet", "Sperre"),
                                  ("it", "Bloccato: motore spento", "bloccato"),
                                  ("es", "Bloqueo: activado", "bloqueo")):
            self.assertTrue(lexicon.term_found(tag, "Latn", target, text), (tag, text))

if __name__ == "__main__":
    unittest.main()
