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


SCRIPTS = {"el": "Grek", "hi": "Deva", "bn": "Beng", "ar": "Arab", "ur": "Arab", "zh": "Hans", "ja": "Jpan"}


def _key_findings(tag, english, target, glossary=None, kind="string", plurals=("one", "other")):
    entry = {"key": "k", "kind": kind, "english": english, "safety": "lock", "dnt": [], "formatted": True,
             "glossary": [{"term": "Lock", "target": glossary, "safety": "lock", "dnt": False}] if glossary else []}
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


if __name__ == "__main__":
    unittest.main()
