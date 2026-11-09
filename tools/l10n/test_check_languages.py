"""Per-language checks with real sentences: glossary word boundaries, negation and Latin loans.

Each language gets the same safety keys and a "lock" safety glossary term, so a draft is checked the
way check.py checks a real wave-3 or RTL draft. Nothing here reaches the network or reads a key.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
import council_fixture  # noqa: E402
import draft_apk  # noqa: E402
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

    # Turkish: negation is a verb ending, and the lock word has opposites written beside it.

    TR = {"tag": "tr"}

    def tr_polarity(self, english, text, *safety):
        return [f["severity"] for f in check._polarity(self.TR, english, text, True, "other", {"safety": list(safety) or ["experimental"]})]

    def test_tr_negation_inside_the_verb(self):
        for english, text in (("Unverified", "Doğrulanmadı"), ("Unverified", "Doğrulanamadı"),
                              ("Untested", "Denenmedi"), ("Unconfirmed", "Onaylanmadı"),
                              ("Change not confirmed", "Değişiklik onaylanmadı"),
                              ("Can't read the lock state", "Kilit durumu okunamıyor"),
                              ("Not yet tried", "Henüz denenmedi"), ("This can't be undone", "Bu geri alınamaz"),
                              ("Volt won't delete it", "Volt bunu silmeyecek"), ("Don't lock it", "Kilitlemeyin"),
                              ("Volt isn't affiliated", "Volt ilişkili değildir"), ("Never", "Hiçbir zaman")):
            self.assertEqual(self.tr_polarity(english, text), [], text)
        # Added and dropped negation are still errors.
        self.assertEqual(self.tr_polarity("Confirmed", "Onaylanmadı"), ["error"])
        self.assertEqual(self.tr_polarity("Not confirmed", "Onaylandı"), ["error"])
        # Ordinary words that end like a bare -ma, and "before" written with -meden, add no negation.
        self.assertEqual(self.tr_polarity("Lock duration", "Kilitleme süresi"), [])
        self.assertEqual(self.tr_polarity("Stop before locking", "Kilitlemeden önce durun"), [])

    def test_tr_lock_opposites(self):
        for text in ("Kilit açık", "Kilit: açık", "Kilit açma", "Kilitsiz", "Kilitli: açık", "Kilitleri aç",
                     "Kilit kaldırıldı", "Kilit: kapalı", "Kilit devre dışı"):
            self.assertFalse(lexicon.term_found("tr", "Latn", "kilit", text, True, True), text)
        for text in ("Scooter’ı kilitle", "Kilit durumu", "Kilit süresi", "Kilitli", "Kilit açıklaması", "Kilitli kalır"):
            self.assertTrue(lexicon.term_found("tr", "Latn", "kilit", text, True, True), text)
        # An English that itself says off or remove may say so of the lock.
        self.assertTrue(lexicon.term_found("tr", "Latn", "kilit", "Kilit kaldırıldı", True, False))
        self.assertFalse(lexicon.term_found("tr", "Latn", "kilit", "Kilit açık", True, False))

    def test_tr_on_and_off(self):
        self.assertIn("error", self.tr_polarity("Alarm sound off", "Alarm sesi açık"))
        self.assertEqual(self.tr_polarity("Alarm sound on", "Alarm sesi kapalı"), ["error"])
        self.assertEqual(self.tr_polarity("Alarm sound on", "Alarm sesi açık"), [])
        self.assertEqual(self.tr_polarity("Alarm sound off", "Alarm sesi kapalı"), [])
        self.assertEqual(self.tr_polarity("Turn off the alarm", "Alarmı devre dışı bırak"), [])
        # A negation after the word, or in its ending, makes it the other state.
        self.assertEqual(lexicon.state_words("tr", "Alarm açık değil"), ([], ["açık"]))
        self.assertEqual(lexicon.state_words("tr", "Alarm kapalı değildir"), (["kapalı"], []))
        self.assertEqual(lexicon.state_words("tr", "Alarm kapatılmadı"), (["kapatılmadı"], []))
        # The activity log is not "active".
        self.assertEqual(lexicon.state_words("tr", "NAVEE etkinlik günlüğü"), ([], []))

    def test_tr_lock_state_trap(self):
        for english, text in (("Lock: on", "Kilit: açık"), ("Lock: off", "Kilit: kapalı"),
                              ("Auto-lock: on", "Otomatik kilit açık"), ("Lock state: off", "Kilit durumu: kapalı")):
            self.assertIn("error", self.tr_polarity(english, text, "lock"), text)
        for english, text in (("Lock: on", "Kilit: etkin"), ("Lock: off", "Kilit: devre dışı"),
                              ("Unlocked", "Kilidi açık")):
            self.assertEqual(self.tr_polarity(english, text, "lock"), [], text)
        # Only a lock key.
        self.assertEqual(self.tr_polarity("Lock: on", "Kilit: açık", "alarm"), [])
        self.assertEqual(self.tr_polarity("Lock: on", "Kilit: açık", "ride", "lock"), ["error"])

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


class PolishWordingTest(unittest.TestCase):
    """Polish wording the setup review found: action against state, negation words, never."""

    LOCK = ["zablokuj", "zablokować", "zablokuje", "zablokowanie", "blokuj", "blokować", "blokowanie", "blokada"]
    LOCKED = ["zablokowana", "zablokowany", "zablokowane", "zablokowano"]
    UNLOCK = ["odblokuj", "odblokować", "odblokuje", "odblokowanie", "odblokowuj", "odblokowywać", "odblokowywanie"]
    UNLOCKED = ["odblokowana", "odblokowany", "odblokowane", "odblokowano"]

    def test_lock_action_and_state_do_not_cross(self):
        for english, text, targets in (("Lock", "Zablokowana", self.LOCK),
                                       ("Locked", "Zablokowanie", self.LOCKED),
                                       ("Unlocked", "Odblokowanie", self.UNLOCKED),
                                       ("Unlock", "Odblokowana", self.UNLOCK),
                                       ("Unlock first", "Najpierw odblokowana", self.UNLOCK)):
            self.assertIn("glossary", _errors("pl", english, text, targets, term=english.split()[0]), (english, text))

    def test_lock_families_still_inflect(self):
        for english, text, targets in (("Lock", "Zablokowanie", self.LOCK),
                                       ("Lock", "Po zablokowaniu", self.LOCK),
                                       ("Locked", "Zablokowana", self.LOCKED),
                                       ("Locked", "Zablokowanych", self.LOCKED),
                                       ("Locked", "Zablokowano", self.LOCKED),
                                       ("Unlock", "Odblokowania", self.UNLOCK),
                                       ("Unlocked", "Odblokowanej", self.UNLOCKED)):
            self.assertEqual(_errors("pl", english, text, targets, term=english), [], (english, text))

    def test_other_polish_words_keep_free_endings(self):
        self.assertTrue(lexicon.term_found("pl", "Latn", "hulajnoga", "hulajnogi", False))
        self.assertTrue(lexicon.term_found("pl", "Latn", "sparowana", "sparowanie", False))

    def test_negation_words(self):
        for english, text in (("No motion report yet", "Brak jeszcze raportu o ruchu"),
                              ("Nothing to try", "Brak funkcji do wypróbowania"),
                              ("Nothing was changed", "Niczego nie zmieniono"),
                              ("No permission", "Brakuje uprawnienia"),
                              ("Nobody connected", "Nikt nie jest połączony"),
                              ("No alerts", "Żadnej nowej wiadomości")):
            self.assertEqual(_errors("pl", english, text), [], (english, text))
        # A negation the English lacks is still an error; "missing" leaves it to the judge.
        self.assertEqual(_errors("pl", "Motion report ready", "Brak raportu o ruchu"), ["polarity"])
        self.assertEqual(_errors("pl", "Missing motion report", "Brak raportu o ruchu"), [])

    def test_never_needs_its_own_negation(self):
        for text in ("Twój sposób bez telefonu. Cyfry są zawsze pokazywane.",
                     "Twój sposób bez telefonu. Cyfry są pokazywane."):
            self.assertEqual(_errors("pl", "Your fallback. Digits are never shown.", text), ["polarity"], text)
        self.assertEqual(_errors("pl", "Digits are never shown.", "Cyfry nigdy nie są pokazywane."), [])
        self.assertEqual(_errors("pl", "Never shown, always kept.", "Nigdy nie pokazywane, zawsze przechowywane."), [])

    def test_abbreviated_states(self):
        self.assertEqual(_errors("pl", "Alarm sound off", "Dźwięk alarmu: wł."), ["polarity"])
        self.assertEqual(_errors("pl", "Alarm sound on", "Dźwięk alarmu: wył."), ["polarity"])
        self.assertEqual(_errors("pl", "Alarm sound off", "Dźwięk alarmu: wył."), [])
        self.assertEqual(_errors("pl", "Alarm sound on", "Dźwięk alarmu: wł."), [])


class NorwegianTest(unittest.TestCase):
    """nb: the lock and on or off checks, on the wordings its style guide allows and forbids."""
    LOCK = ["lås", "låse", "låst", "låsen"]

    def test_lock_inverted_is_not_the_lock_term(self):
        for text in ("Lås opp sparkesykkelen", "Lås den opp", "for å låse den opp", "Lås sparkesykkelen opp",
                     "Lås den igjen opp", "Slå av låsen", "Fjern låsen", "Åpne låsen", "Ta bort låsen",
                     "Lås: av", "Låsen er av", "Låsen er deaktivert", "Låsen ble fjernet", "Låseopphevelse",
                     "Ulåst"):
            self.assertFalse(any(lexicon.term_found("nb", "Latn", t, text) for t in self.LOCK), text)

    def test_lock_wording_kept(self):
        for text in ("Lås sparkesykkelen", "Lås", "Lås og ta opp", "Låsetid", "Låsebevegelse (rå)",
                     "Én låsemodul per dashbord", "Låsestatus ukjent", "Låseoppsett", "Låsen er på",
                     "Når sparkesykkelen er koblet til, kan du låse den.",
                     "Slå av lyset og lås sparkesykkelen"):
            self.assertTrue(any(lexicon.term_found("nb", "Latn", t, text) for t in self.LOCK), text)

    def test_english_removal_lets_the_wording_stand(self):
        self.assertTrue(lexicon.term_found("nb", "Latn", "låsen", "Slå av låsen", True, False))

    def test_state_words(self):
        for text, want in (("Alarmlyd på", "on"), ("Slå på alarmlyden", "on"), ("Alarmlyden er aktivert", "on"),
                           ("Alarmlyd av", "off"), ("Slå av alarmlyden", "off"), ("Alarmlyden er slått av", "off"),
                           ("Alarmlyd ikke aktiv", "off"), ("Alarmlyden er ikke lenger aktiv", "off"),
                           ("Alarmlyden er ikke slått av", "on")):
            on, off = lexicon.state_words("nb", text)
            self.assertEqual((bool(on), bool(off)), (want == "on", want == "off"), text)
        # "på" and "av" as prepositions are no state.
        self.assertEqual(lexicon.state_words("nb", "Alarm på telefonen av sparkesykkelen"), ([], []))

    def test_state_inverted_is_an_error(self):
        self.assertIn("polarity", _key_errors_state("Alarm sound off", "Alarmlyd på"))
        self.assertIn("polarity", _key_errors_state("Lock: on", "Lås: av"))
        self.assertNotIn("polarity", _key_errors_state("Alarm sound off", "Alarmlyd av"))
        self.assertNotIn("polarity", _key_errors_state("Alarm sound on", "Alarmlyd på"))


def _key_errors_state(english, text):
    entry = {"key": "k", "kind": "string", "english": english, "safety": "alarm", "dnt": [], "formatted": True,
             "glossary": []}
    language = {"tag": "nb", "plurals": ["one", "other"], "script": "Latn", "font": "system"}
    return sorted(set(f["check"] for f in check.check_key(entry, language, "string", {"other": text})
                      if f["severity"] == check.ERROR))


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

    def test_greek_lock_action_and_state_stay_apart(self):
        lock = ["Κλείδωμα", "κλειδώματος", "κλειδώματα", "κλείδωσε", "κλειδώνω", "κλειδώνει", "κλειδώσει", "κλειδωθεί"]
        unlock = ["Ξεκλείδωμα", "ξεκλειδώματος", "ξεκλειδώματα", "ξεκλείδωσε", "ξεκλειδώνω", "ξεκλειδωθεί"]
        for english, targets, text in (("Lock", lock, "Κλείδωμα"), ("Lock", lock, "Αποτυχία κλειδώματος"),
                                       ("Lock the scooter", lock, "Κλείδωσε το πατίνι"),
                                       ("Unlock first", unlock, "Απαιτείται ξεκλείδωμα")):
            self.assertEqual(_errors("el", english, text, targets, term=english.split()[0]), [], text)
        for english, targets, text in (("Lock", lock, "Κλειδωμένο"), ("Unlock", unlock, "Ξεκλειδωμένο"),
                                       ("Lock the scooter", lock, "Το πατίνι κλειδώθηκε")):
            self.assertEqual(_errors("el", english, text, targets, term=english.split()[0]), ["glossary"], text)

    def test_greek_negation_words(self):
        self.assertEqual(_errors("el", "No rides yet", "Καμία διαδρομή ακόμη"), [])
        self.assertEqual(_errors("el", "Never", "Ποτέ"), [])
        self.assertEqual(_errors("el", "Never", "Πότε"), ["polarity"])
        # An ordinary word in α- is no longer a possible negation.
        self.assertEqual(check.target_negation("el", "Αποθήκευση")[1], [])

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
        ("nl", "The lock is on", "De vergrendeling is open", ["vergrendeld", "vergrendeling"]),
        ("nl", "The lock is on", "De vergrendeling staat open", ["vergrendeld", "vergrendeling"]),
        ("nl", "Lock", "Van het slot halen", ["vergrendelen", "slot"]),
        ("nl", "Lock", "Tot slot: tik hier", ["vergrendelen", "slot"]),
        ("nl", "Lock", "Zet de step op slot", ["vergrendelen", "slot"]),
        ("nl", "Lock", "Slotstand", ["vergrendelen", "slot"]),
        ("pl", "Lock scooter", "Zdejmij blokadę hulajnogi", ["zablokuj", "blokada"]),
        ("pl", "Lock scooter", "Wyłącz blokadę hulajnogi", ["zablokuj", "blokada"]),
        ("pl", "Lock scooter", "Usuń blokadę", ["zablokuj", "blokada"]),
        ("pl", "The lock is on", "Blokada hulajnogi wyłączona", ["zablokowana", "blokada"]),
        ("pl", "The lock is on", "Blokada została zdjęta", ["zablokowana", "blokada"]),
        ("pl", "Lock", "Odblokuj blokadę", ["zablokuj", "blokada"]),
        ("pl", "Lock", "Odblokowanie blokady", ["zablokuj", "blokada"]),
        ("pl", "Lock", "Odblokowano blokadę", ["zablokuj", "blokada"]),
        ("pl", "Electronic lock", "Odblokuj blokadę elektroniczną", ["blokada elektroniczna"]),
        ("pl", "Electronic lock", "Blokada elektroniczna odblokowana", ["blokada elektroniczna"]),
        ("pl", "The lock is on", "Blokada została odblokowana", ["zablokowana", "blokada"]),
        ("pl", "Lock: on", "Blokada: wył.", ["zablokowana", "blokada"]),
        ("sv", "Lock scooter", "Ta bort låset", ["lås", "låset"]),
        ("sv", "Lock scooter", "Inaktivera låset", ["lås", "låset"]),
        ("sv", "The lock is on", "Låset inaktiverat", ["låst", "låset"]),
        ("id", "Lock scooter", "Lepaskan kunci skuter", ["kunci"]),
        ("id", "Lock scooter", "Nonaktifkan kunci", ["kunci"]),
        ("id", "The lock is on", "Kunci dinonaktifkan", ["terkunci", "kunci"]),
        # Later-wave languages, where the inflected glossary match had let these pass.
        ("ru", "Lock scooter", "Снять блокировку самоката", ["заблокировать", "блокировка", "блокировки"]),
        ("ru", "Lock scooter", "Отключить блокировку самоката", ["заблокировать", "блокировка", "блокировки"]),
        ("ru", "Lock scooter", "Сбросить блокировку самоката", ["заблокировать", "блокировка", "блокировки"]),
        ("ru", "Lock scooter", "Сброс блокировки", ["заблокировать", "блокировка", "блокировки"]),
        ("ru", "The lock is on", "Блокировка сброшена", ["заблокирован", "блокировка"]),
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
        ("nl", "Lock", "Slot", ["vergrendelen", "slot"]),
        ("nl", "Lock", "Tot slot: vergrendel de step", ["vergrendelen", "vergrendel", "slot"]),
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
        ("pl", "Lock or unlock", "Zablokuj lub odblokuj", ["zablokuj"]),
        ("pl", "Locked. Slide to unlock.", "Zablokowana. Przesuń, aby odblokować.", ["zablokowana"]),
        # An unlock key that mentions the lock: the English says unlock, so the wording may too.
        ("pl", "Unlock the locked scooter", "Odblokuj zablokowaną hulajnogę", ["zablokowana"]),
        ("pl", "Unlock the lock", "Odblokuj blokadę", ["blokada"]),
        ("de", "Disable the lock", "Sperre deaktivieren", ["sperren", "Sperre"]),
    )
    RO_LOCK = ["Blochează", "Blocare", "blocarea", "bloca"]
    RO_LOCK_REMOVED = (
        ("Lock scooter", "Elimină blocarea"),
        ("Lock scooter", "Scoate blocarea"),
        ("Lock scooter", "Șterge blocarea"),
        ("Lock scooter", "Dezactivează blocarea"),
        ("Lock scooter", "Anulează blocarea"),
        ("Lock scooter", "Ridică blocarea"),
        ("The lock is on", "Blocare dezactivată"),
        ("The lock is on", "Blocare: dezactivată"),
        ("The lock is on", "Blocarea a fost anulată"),
        ("The lock is on", "Blocarea trotinetei este dezactivată"),
    )
    RO_LOCK_KEPT = (
        ("Lock scooter", "Blochează trotineta"),
        ("Lock scooter", "Oprește alarma și blochează trotineta"),
        ("Lock scooter", "Deschide „Blocare”"),
        ("Lock the scooter when it is stopped", "Blochează trotineta oprită"),
        # The English itself says turn off, so the context is skipped.
        ("Turn off the lock", "Dezactivează blocarea"),
    )

    def test_romanian_lock_removed(self):
        for english, text in self.RO_LOCK_REMOVED:
            self.assertIn("glossary", _errors("ro", english, text, self.RO_LOCK), text)
        for english, text in self.RO_LOCK_KEPT:
            self.assertNotIn("glossary", _errors("ro", english, text, self.RO_LOCK), text)

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
    "ru": {"alarm-sound": "Сигнал тревоги | сигнала тревоги", "scooter": "самокат | самоката"},
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
    "ro": {"lock": "Blochează | Blocare | blocarea | a bloca", "unlock": "Deblochează | Deblocare | deblocarea",
           "scooter": "trotinetă | trotineta", "low-battery-alert": "Alertă de baterie scăzută",
           "alarm-sound": "Sunet de alarmă"},
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
        ("ro", "Alarm on", "Alarmă dezactivată"),
        ("ro", "Lock: on", "Blocare: dezactivată"),
        ("ro", "On", "Dezactivat"),
        ("ro", "Off", "Activat"),
        ("ro", "Alarm sound off", "Sunet de alarmă aprins"),
        ("ro", "Turn on the alarm", "Oprește alarma"),
        ("ro", "Alarm sound off", "Sunetul de alarmă nu este dezactivat"),
        ("es", "Lock: on", "Bloqueo: desactivado"),
        ("de", "Lock: on", "Sperre: aus"),
        ("de", "Lock: on", "Sperre: deaktiviert"),
        ("pl", "Lock: on", "Blokada: wyłączona"),
        ("nl", "Lock: on", "Vergrendeling: uit"),
        ("sv", "Lock: on", "Lås: av"),
        ("id", "Lock: on", "Kunci: dinonaktifkan"),
        # A negated state word is the other state: "not switched off" is on.
        ("de", "Alarm sound off", "Alarmton nicht ausgeschaltet"),
        ("es", "Alarm sound off", "Sonido de alarma no desactivado"),
        ("id", "Alarm sound off", "Suara alarm tidak dimatikan"),
        ("pl", "Alarm sound off", "Dźwięk alarmu nie wyłączony"),
        ("fr", "Alarm sound off", "Son d'alarme non désactivé"),
        ("it", "Lock: off", "Blocco: non disattivato"),
        ("id", "Alarm sound on", "Suara alarm tidak aktif"),
        ("es", "Alarm sound on", "Sonido de alarma sin activar"),
        ("ru", "Alarm sound off", "Сигнал тревоги включён"),
        ("ru", "Alarm sound off", "Сигнал тревоги: вкл."),
        ("ru", "Alarm sound on", "Сигнал тревоги выключен"),
        ("ru", "Alarm sound on", "Сигнал тревоги: выкл."),
        ("ru", "Alarm sound on", "Сигнал тревоги отключён"),
        ("ru", "Alarm sound on", "Сигнал тревоги не включён"),
        ("ru", "Alarm sound off", "Сигнал тревоги не будет выключен"),
    )
    STATE_KEPT = (
        ("ro", "Alarm on", "Alarmă activată"),
        ("ro", "Lock: on", "Blocare: activată"),
        ("ro", "Off", "Dezactivat"),
        ("ro", "Alarm sound off", "Sunetul de alarmă nu este activat"),
        ("ro", "Turn off NAVEE logging", "Oprește jurnalul de activitate NAVEE"),
        # "activitate" is the logging word, not an on state.
        ("ro", "NAVEE logging off", "Jurnalul de activitate NAVEE: oprit"),
        ("ro", "NAVEE logging off", "Jurnalul de activitate NAVEE"),
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
        # "Not active" is off: the standard Inactive/Off wording, not an inversion.
        ("id", "Alarm sound off", "Suara alarm tidak aktif"),
        ("id", "Low battery alert inactive", "Peringatan baterai lemah tidak aktif"),
        ("de", "Alarm sound off", "Alarmton nicht aktiv"),
        ("nl", "Alarm sound off", "Alarmgeluid niet actief"),
        ("sv", "Alarm sound off", "Larmljud inte aktivt"),
        ("it", "Alarm sound off", "Suono di allarme non attivo"),
        ("es", "Alarm sound off", "Sonido de alarma no activo"),
        ("pt-BR", "Alarm sound off", "Som de alarme não ativado"),
        ("fr", "Alarm sound off", "Son d'alarme non activé"),
        ("fr", "Alarm sound off", "Le son d'alarme n'est pas activé"),
        ("ru", "Alarm sound on", "Сигнал тревоги включён"),
        ("ru", "Alarm sound off", "Сигнал тревоги выключен"),
        ("ru", "Alarm sound off", "Сигнал тревоги: выкл."),
        ("ru", "Alarm sound off", "Сигнал тревоги не включён"),
        # "включая" (including) and "подключён" (connected) are no state.
        ("ru", "Alarm sound off", "Все сигналы выключены, включая тревогу"),
        ("ru", "Alarm sound on", "Сигнал тревоги включён, самокат подключён"),
    )
    # Locked here is the state, and the turn-off verb takes a clitic, -nya or a zu/te infinitive.
    TURN_IT_OFF = "The alarm sounds if the scooter moves while locked. Tap Stop to turn it off."
    TURN_IT_OFF_KEPT = (
        ("es", "La alarma suena si el patinete se mueve con el bloqueo activado. Toca Detener para apagarla."),
        ("it", "L'allarme suona se il monopattino si muove con il blocco attivo. Tocca Stop per spegnerlo."),
        ("pt-BR", "O alarme toca se o patinete se mover com a trava ativada. Toque em Parar para desligá-lo."),
        ("nl", "Het alarm gaat af als de scooter beweegt terwijl de vergrendeling actief is. Tik op Stop om "
               "het uit te zetten."),
        ("id", "Alarm berbunyi jika skuter bergerak saat kunci aktif. Ketuk Berhenti untuk mematikannya."),
        ("de", "Der Alarm ertönt, wenn der Roller bei aktivierter Sperre bewegt wird. Tippe auf Stopp, um ihn "
               "auszuschalten."),
    )

    def test_turn_off_clitic_and_infinitive_forms(self):
        for tag, text in self.TURN_IT_OFF_KEPT:
            self.assertNotIn("polarity", _real_errors(tag, self.TURN_IT_OFF, text, "alarm"),
                             (tag, text, _real_findings(tag, self.TURN_IT_OFF, text, "alarm")))
        self.assertEqual(lexicon.state_words("es", "para apagarla")[1], ["apagarla"])
        self.assertEqual(lexicon.state_words("es", "para activarla")[0], ["activarla"])
        self.assertEqual(lexicon.state_words("es", "para desactivarla"), ([], ["desactivarla"]))

    LOCK_VERB_FORMS = (
        # (tag, English, correct, inverted)
        ("es", "Resolve scooter warning %1$s before locking.",
         "Resuelve la advertencia %1$s del patinete antes de bloquearlo.",
         "Resuelve la advertencia %1$s del patinete antes de desbloquearlo."),
        ("de", "Resolve scooter warning %1$s before locking.", "Behebe die Rollerwarnung %1$s vor dem Sperren.",
         "Behebe die Rollerwarnung %1$s vor dem Entsperren."),
        ("fr", "Resolve scooter warning %1$s before locking.",
         "Résolvez l'avertissement %1$s de la trottinette avant de la verrouiller.",
         "Résolvez l'avertissement %1$s de la trottinette avant de la déverrouiller."),
        ("pl", "Resolve scooter warning %1$s before locking.", "Usuń ostrzeżenie hulajnogi %1$s przed zablokowaniem.",
         "Usuń ostrzeżenie hulajnogi %1$s przed odblokowaniem."),
        ("es", "This file unlocks your scooter. Keep it private.",
         "Este archivo desbloquea tu patinete. Mantenlo en privado.",
         "Este archivo bloquea tu patinete. Mantenlo en privado."),
        ("de", "This file unlocks your scooter. Keep it private.", "Diese Datei entsperrt deinen Roller. Halte sie privat.",
         "Diese Datei sperrt deinen Roller. Halte sie privat."),
        ("pl", "This file unlocks your scooter. Keep it private.",
         "Ten plik odblokowuje twoją hulajnogę. Zachowaj go w tajemnicy.",
         "Ten plik blokuje twoją hulajnogę. Zachowaj go w tajemnicy."),
        ("es", "Locking…", "Bloqueando…", "Desbloqueando…"),
        ("de", "Locking…", "Wird gesperrt…", "Wird entsperrt…"),
        ("pt-BR", "Locking…", "Travando…", "Destravando…"),
        ("pl", "Locking…", "Blokowanie…", "Odblokowywanie…"),
        ("sv", "Locking…", "Låser…", "Låser upp…"),
        ("id", "Locking…", "Mengunci…", "Membuka kunci…"),
        ("de", "Unlocking…", "Wird entsperrt…", "Wird gesperrt…"),
        ("it", "Unlocking…", "Sblocco in corso…", "Blocco in corso…"),
        ("nl", "Unlocking…", "Ontgrendelen…", "Vergrendelen…"),
        ("sv", "Unlocking…", "Låser upp…", "Låser…"),
        ("fr", "Unlocking…", "Déverrouillage…", "Verrouillage…"),
        # Indonesian pe(N)-...-an nouns of the approved roots (kunci, buka kunci).
        ("id", "Locking failed", "Penguncian gagal", "Pembukaan kunci gagal"),
        ("id", "Auto-locking", "Penguncian otomatis", "Pembukaan kunci otomatis"),
        ("id", "Unlocking failed", "Pembukaan kunci gagal", "Penguncian gagal"),
    )

    def test_indonesian_pen_an_nouns(self):
        for root, noun in (("kunci", "penguncian"), ("buka", "pembukaan"), ("tutup", "penutupan"),
                           ("simpan", "penyimpanan"), ("pakai", "pemakaian"), ("aktif", "pengaktifan"),
                           ("hapus", "penghapusan"), ("lepas", "pelepasan"), ("daftar", "pendaftaran")):
            self.assertEqual(lexicon.pen_an(root), noun)
        # Loose (-ing) matching only, and never for a prefixed or negation word.
        self.assertTrue(lexicon.term_found("id", "Latn", "kunci", "Penguncian gagal", True, True, True))
        self.assertFalse(lexicon.term_found("id", "Latn", "kunci", "Penguncian gagal", True, True, False))
        self.assertFalse(lexicon.term_found("id", "Latn", "kunci", "Pembukaan kunci gagal", True, True, True))
        self.assertIsNone(lexicon._nominal("id", "mengunci"))
        self.assertIsNone(lexicon._nominal("id", "tidak"))
        self.assertIsNone(lexicon._nominal("es", "bloquear"))

    # A negation word before a state word negates it only when nothing but a copula, auxiliary,
    # reflexive or clitic particle stands between; it negates a verb in between instead.
    NEGATION_OF_ANOTHER_VERB = (
        ("es", "Remember to turn off the alarm", "No olvides apagar la alarma"),
        ("es", "Remember to disable the alarm", "No olvides desactivar la alarma"),
        ("fr", "Remember to turn off the alarm", "N'oubliez pas d'éteindre l'alarme"),
        ("fr", "Remember to turn off the alarm", "N'oubliez pas de désactiver l'alarme"),
        ("pl", "Remember to turn off the alarm", "Nie zapomnij wyłączyć alarmu"),
        ("id", "Remember to turn off the alarm", "Jangan lupa matikan alarm"),
        ("id", "Remember to turn off the alarm", "Jangan lupa mematikan alarm"),
        ("sv", "Remember to turn off the alarm", "Glöm inte att stänga av larmet"),
        ("it", "Remember to turn off the alarm", "Non dimenticare di spegnere l'allarme"),
        ("pt-BR", "Remember to turn off the alarm", "Não se esqueça de desligar o alarme"),
        ("de", "Remember to turn off the alarm", "Vergiss nicht, den Alarm auszuschalten"),
        ("nl", "Remember to turn off the alarm", "Vergeet niet het alarm uit te zetten"),
        # fr "ne ... que" is only; it "non appena" is as soon as.
        ("fr", "The alarm activates only when movement is detected",
         "L'alarme ne s'active que si un mouvement est détecté"),
        ("it", "Once enabled, the alarm sounds when movement is detected",
         "Non appena attivato, l'allarme suona quando viene rilevato un movimento"),
        # fr "ne ... plus" (no longer) negates.
        ("fr", "Alarm sound off", "Le son d'alarme ne sera plus activé"),
    )
    NEGATED_THROUGH_A_COPULA = (
        ("fr", "Alarm stays on when the device is moved", "L'alarme ne sera plus activée quand l'appareil est déplacé"),
        ("de", "Alarm stays on when the device is moved", "Der Alarm ist dann nicht mehr aktiv, wenn das Gerät bewegt wird"),
        ("de", "Alarm stays on when the device is moved",
         "Der Alarm ist dann nicht länger aktiv, wenn das Gerät bewegt wird"),
        ("sv", "Alarm stays on when the device is moved", "Larmet är inte längre aktivt när enheten flyttas"),
        ("es", "Alarm sound on", "Sonido de alarma no está activado"),
        ("pl", "Alarm sound on", "Dźwięk alarmu nie jest włączony"),
    )

    def test_negation_reads_only_the_state_it_negates(self):
        for tag, english, text in self.NEGATION_OF_ANOTHER_VERB:
            self.assertNotIn("polarity", _real_errors(tag, english, text, "alarm"),
                             (tag, english, text, _real_findings(tag, english, text, "alarm")))
        for tag, english, text in self.NEGATED_THROUGH_A_COPULA:
            self.assertIn("polarity", _real_errors(tag, english, text, "alarm"),
                          (tag, english, text, _real_findings(tag, english, text, "alarm")))
        self.assertEqual(lexicon.state_words("fr", "l'alarme ne s'active que si"), (["active"], []))
        self.assertEqual(lexicon.state_words("fr", "l'alarme ne s'active pas"), ([], ["active"]))
        self.assertEqual(lexicon.state_words("es", "no olvides apagarla"), ([], ["apagarla"]))
        self.assertEqual(lexicon.state_words("es", "no está activada"), ([], ["activada"]))

    def test_lock_verb_forms_are_glossary_hits(self):
        es = _real_terms("es")
        self.assertEqual([t.id for t in translib.glossary_hits(es, "Unlocking…")], ["unlock"])
        self.assertEqual([t.id for t in translib.glossary_hits(es, "before locking")], ["lock"])
        self.assertEqual([t.id for t in translib.glossary_hits(es, "This file unlocks it")], ["unlock"])
        # Locked and Unlocked keep their own rows; Lock is not found inside them.
        self.assertEqual([t.id for t in translib.glossary_hits(es, "Locked")], ["locked"])
        for tag, english, good, bad in self.LOCK_VERB_FORMS:
            self.assertEqual(_real_errors(tag, english, good), [], (tag, good, _real_findings(tag, english, good)))
            self.assertIn("glossary", _real_errors(tag, english, bad), (tag, bad))

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

class FinnishChecksTest(unittest.TestCase):
    """Finnish opposite-term, removal and negation checks, with the shape of the real glossary rows."""

    LOCK = ["lukitse", "lukita", "lukitaan", "lukitus", "lukituksen", "lukitusta"]

    def test_unlock_and_removal_are_not_the_lock_term(self):
        for text in ("Avaa lukitus", "Lukitus avattu", "Lukituksen avaus", "Poista lukitus", "Lukitsematon",
                     "Avaa ensin lukitus", "Avaa potkulaudan lukitus", "Lukitusta ei voitu avata",
                     "Lukitus on auki", "Lukitus: avattu", "Pura lukitus", "Lukitus pois päältä",
                     "Lukitus poistettu", "Poista potkulaudan lukitus", "Voit avata lukituksen"):
            self.assertIn("glossary", _errors("fi", "Lock scooter", text, self.LOCK), text)

    def test_lock_wording_keeps_the_lock_term(self):
        for text in ("Lukitse potkulauta", "Lukitus", "Lukitse ja sammuta valot", "Avaa ja lukitse",
                     "Poista ajo ja lukitse", "Lukitaan…", "Lukitse, kun yhteys avataan"):
            self.assertEqual(_errors("fi", "Lock scooter", text, self.LOCK), [], text)

    def test_removal_words_are_right_when_the_english_removes(self):
        self.assertEqual(_errors("fi", "Remove the lock", "Poista lukitus", self.LOCK), [])

    def test_unlock_targets_survive_the_contexts(self):
        targets = ["avaa lukitus", "avaa ensin lukitus", "lukituksen avaus", "lukitus avattu"]
        for text in ("Avaa lukitus", "Avaa ensin lukitus", "Lukituksen avaus", "Lukitus avattu"):
            self.assertTrue(any(lexicon.term_found("fi", "Latn", t, text) for t in targets), text)

    def test_koskaan_alone_is_not_a_negation(self):
        self.assertIn("polarity", _errors("fi", "Never lock", "Koskaan lukitse", self.LOCK))
        self.assertNotIn("polarity", _errors("fi", "Never lock", "Älä koskaan lukitse", self.LOCK))

    def test_negative_participles_count_as_negated(self):
        for text in ("Vahvistamaton", "Varmistamaton", "Testaamaton", "Ei vahvistettu"):
            self.assertEqual(_errors("fi", "Unconfirmed", text), [], text)
        self.assertIn("polarity", _errors("fi", "Unconfirmed", "Vahvistettu"))


class HungarianChecksTest(unittest.TestCase):
    """hu: the lock and on or off checks, on the wordings its style guide allows and forbids."""
    LOCK = ["Lezárás", "zárd le", "lezárod", "lezárja", "zárja le", "lezárni", "lezárhatod", "lezárható",
            "zárta le", "lezárta", "zárható le", "le kell zárni"]
    LOCKED = ["Lezárva", "lezárt", "le van zárva"]

    def test_lock_inverted_is_not_the_lock_term(self):
        for text in ("A lezárás feloldása", "Lezárás feloldása", "Lezárás: feloldva", "A lezárás fel van oldva",
                     "A lezárást oldd fel", "Oldd fel a lezárást", "Oldd fel a roller lezárását",
                     "A Volt feloldja a lezárást", "lezárásfeloldás", "Lezárás-feloldás", "Lezárás kioldása",
                     "A lezárás nem oldható fel", "A lezárás kikapcsolása", "Lezárás megszüntetése", "Lezárás: ki",
                     "Lezárás: kikapcsolva", "A lezárás ki van kapcsolva", "Kapcsold ki a lezárást",
                     "Szüntesd meg a lezárást", "Lezárás törlése", "Lezárás eltávolítása"):
            self.assertFalse(any(lexicon.term_found("hu", "Latn", t, text) for t in self.LOCK), text)
        self.assertFalse(any(lexicon.term_found("hu", "Latn", t, "Lezárva: feloldva") for t in self.LOCKED))

    def test_lock_wording_keeps_the_lock_term(self):
        for text in ("Lezárás", "Lezárás…", "A roller lezárásához", "Zárd le a rollert", "Lezárás és feloldás",
                     "Lezárás vagy feloldás", "Nem zárta le a rollert", "A roller nem zárható le",
                     "Le kell zárni a rollert", "Oldd fel, majd zárd le", "Oldd fel a rollert és zárd le",
                     "A Volt nem tudja feloldani vagy lezárni", "Feloldhatod lezárás után", "A lezárás sikertelen",
                     "Kapcsold ki a lámpát lezárás előtt", "A lezárás törli a szakaszt",
                     "A lezárás kikapcsolja a lámpát", "Lezárás feloldás nélkül"):
            self.assertTrue(any(lexicon.term_found("hu", "Latn", t, text) for t in self.LOCK), text)
        for text in ("Lezárva", "Lezárva feloldásig", "A roller le van zárva", "Feloldja a lezárt rollert",
                     "Kapcsold ki a lezárt rollert"):
            self.assertTrue(any(lexicon.term_found("hu", "Latn", t, text) for t in self.LOCKED), text)

    def test_removal_words_are_right_when_the_english_removes(self):
        self.assertEqual(_errors("hu", "Turn off the lock", "Kapcsold ki a lezárást", ["Lezárás"]), [])
        self.assertIn("glossary", _errors("hu", "Lock scooter", "Kapcsold ki a lezárást", ["Lezárás"]))
        self.assertIn("glossary", _errors("hu", "Lock scooter", "A lezárás feloldása", ["Lezárás"]))

    def test_on_off_state_inversion_fails_a_safety_key(self):
        for english, text in (("Lock: on", "Lezárás: Ki"), ("Lock: on", "Lezárás: kikapcsolva"),
                              ("Alarm sound off", "Riasztási hang bekapcsolva"), ("Alarm sound off", "Riasztási hang: Be"),
                              ("Lock: on", "A lezárás nincs bekapcsolva"), ("Alarm off", "A riasztás be van kapcsolva")):
            self.assertIn("polarity", _errors("hu", english, text), text)
        for english, text in (("Alarm sound off", "Riasztási hang kikapcsolva"), ("Alarm sound off", "Riasztási hang: Ki"),
                              ("Alarm on", "Mindig bekapcsolva"),
                              ("Alarm off", "Kapcsold ki a riasztást"), ("Alarm on", "Kapcsold be a riasztást")):
            self.assertNotIn("polarity", _errors("hu", english, text), text)

    def test_a_negated_state_counts_as_the_other(self):
        self.assertEqual(lexicon.state_words("hu", "A riasztás nincs kikapcsolva"), (["kikapcsolva"], []))
        self.assertEqual(lexicon.state_words("hu", "Nem volt bekapcsolva"), ([], ["bekapcsolva"]))

    def test_power_button_is_no_state(self):
        self.assertEqual(lexicon.state_words("hu", "Nyomd meg a bekapcsoló gombot"), ([], []))
        self.assertEqual(lexicon.state_words("hu", "Kipróbálás"), ([], []))


class CroatianWordListTest(unittest.TestCase):
    """hr negation, state and removal lists, and the lock forms that must not match "conclude"."""

    LOCK = "zaključaj | zaključati | zaključan | zaključani | zaključano"

    @staticmethod
    def _errors(english, text, term, target):
        entry = {"key": "k", "kind": "string", "english": english, "safety": "lock", "dnt": [], "formatted": True,
                 "glossary": [{"id": "t", "term": term, "dnt": False, "safety": "lock",
                               "target": [t.strip() for t in target.split("|")]}]}
        language = {"tag": "hr", "plurals": ["one", "few", "other"], "script": "Latn", "font": "system"}
        return sorted(set(f["check"] for f in check.check_key(entry, language, "string", {"other": text})
                          if f["severity"] == check.ERROR))

    def test_added_negation_on_a_positive_key_fails(self):
        for text in ("Romobili nisu zaključani", "Neće se zaključati", "Nemoj zaključati romobil",
                     "Nitko nije zaključan", "Romobil nikako zaključati"):
            self.assertIn("polarity", self._errors("The scooters are locked", text, "Locked", self.LOCK), text)
        self.assertNotIn("polarity", self._errors("The scooters are locked", "Romobili su zaključani", "Locked", self.LOCK))

    def test_kept_negation_on_a_negative_key_passes(self):
        target = "nije potvrđeno | nije potvrđen | nije potvrđena | nisu potvrđene"
        self.assertNotIn("polarity", self._errors("Values not confirmed", "Vrijednosti nisu potvrđene", "not confirmed", target))
        self.assertIn("polarity", self._errors("Values not confirmed", "Vrijednosti su potvrđene", "not confirmed", "potvrđene"))

    def test_every_common_negation_word_counts(self):
        for word in ("nisu", "nisam", "nisi", "nismo", "niste", "neće", "nećeš", "nećemo", "nemoj", "nemojte", "nemam",
                     "nemaš", "nemaju", "ni", "niti", "nitko", "nikakav", "nikakva", "nikakvo", "nikako", "nigdje"):
            self.assertEqual(check.target_negation("hr", word)[0], [word])

    def test_ordinary_words_are_not_native_negation(self):
        for word in ("negdje", "negativan", "neutralan", "neto", "nekad", "bezbroj", "bezbjedan"):
            self.assertEqual(check.target_negation("hr", word)[2], [], word)
        for word in ("neprovjereno", "nepoznato", "netočno", "nekompatibilan", "nekorišten"):
            self.assertEqual(check.target_negation("hr", word)[2], [word])

    def test_on_and_off_inversion_fails(self):
        for english, text in (("Alarm sound on", "Zvuk alarma: isključeno"), ("Alarm sound off", "Zvuk alarma: uključeno"),
                              ("Alarm sound on", "Zvuk alarma nije uključen"),
                              ("Alarm stays on", "Alarm više neće biti uključen")):
            self.assertIn("polarity", self._errors(english, text, "Alarm", "alarm"), text)
        for english, text in (("Alarm sound on", "Zvuk alarma: uključeno"), ("Alarm sound off", "Zvuk alarma: isključeno"),
                              ("Alarm sound off", "Zvuk alarma nije uključen")):
            self.assertNotIn("polarity", self._errors(english, text, "Alarm", "alarm"), text)
        # "including" and "exclusively" are not states.
        self.assertEqual(lexicon.state_words("hr", "uključujući romobil, isključivo ovdje"), ([], []))

    def test_removing_the_lock_is_not_the_lock_term(self):
        for text in ("Ukloni zaključavanje", "Isključi zaključavanje", "Uklanjanje zaključavanja",
                     "Poništi zaključavanje", "Zaključavanje je isključeno", "Zaključavanje romobila je uklonjeno"):
            self.assertFalse(lexicon.term_found("hr", "Latn", "zaključavanje", text), text)
        self.assertTrue(lexicon.term_found("hr", "Latn", "zaključavanje", "Zaključavanje romobila"))
        self.assertTrue(lexicon.term_found("hr", "Latn", "zaključavanje", "Isključi zaključavanje", removal=False))

    def test_lock_forms_match_but_conclude_does_not(self):
        for target, text in (("zaključa", "Romobil se zaključa"), ("zaključao", "Romobil se nije zaključao"),
                             ("zaključaš", "prije nego što zaključaš"), ("otključa", "Romobil se otključa"),
                             ("otključao", "Romobil se nije otključao"), ("zaključala", "nije se zaključala")):
            self.assertTrue(lexicon.term_found("hr", "Latn", target, text), text)
        for target in ("zaključa", "zaključao", "zaključaj", "zaključaju", "zaključala", "zaključalo", "zaključaš"):
            for text in ("Zaključi romobil", "Zaključak", "Zaključuje", "Zaključio", "Zaključajte romobil"):
                self.assertFalse(lexicon.term_found("hr", "Latn", target, text), (target, text))


class JoinedVerbNegationTest(unittest.TestCase):
    """cs and sk write a verb's "not" joined to it ("nepotvrdila"), and that keeps the English negation."""

    def test_joined_ne_keeps_the_english_not(self):
        for tag, english, text in (("cs", "Change not confirmed", "Změna nepotvrzena"),
                                   ("cs", "The scooter didn't confirm the lock change", "Koloběžka nepotvrdila změnu"),
                                   ("cs", "PIN status did not arrive. Tap Read PIN status to try again.",
                                    "Stav kódu PIN nedorazil. Zkuste to znovu."),
                                   ("cs", "Not connected", "Nejsou připojeny"),
                                   ("cs", "Can't lock while riding", "Za jízdy nelze"),
                                   ("sk", "Change not confirmed", "Zmena nepotvrdená"),
                                   ("sk", "The scooter didn't answer", "Kolobežka neodpovedala")):
            self.assertEqual(_errors(tag, english, text), [], (tag, text))

    def test_a_lost_not_still_fails(self):
        for tag, english, text in (("cs", "Change not confirmed", "Změna potvrzena"),
                                   ("cs", "PIN status did not arrive.", "Stav kódu PIN dorazil."),
                                   ("sk", "Change not confirmed", "Zmena potvrdená")):
            self.assertEqual(_errors(tag, english, text), ["polarity"], (tag, text))

    def test_ordinary_ne_words_are_no_negation(self):
        # "Nechte" (leave), "neutrální" (neutral), "nejlepší" (best) only look negated.
        for text in ("Nechte koloběžku zapnutou", "Neutrální režim", "Nejlepší rychlost"):
            self.assertEqual(_errors("cs", "Do not ride yet", text), ["polarity"], text)

    def test_the_opposite_term_is_no_negation(self):
        # "neodemčeno" is ne- plus the approved unlock target: not a negation of the sentence.
        self.assertFalse(lexicon.native_negated("cs", "neodemčeno", ("odemčeno",)))

    def test_an_added_ne_word_only_warns_and_other_languages_are_unchanged(self):
        findings = _key_findings("cs", "Wrong password or bad file.", "Nesprávné heslo nebo neplatný soubor.")
        self.assertEqual([f["severity"] for f in findings if f["check"] == "polarity"], [check.WARN])
        self.assertEqual(_errors("cs", "Change confirmed", "Změna není potvrzena"), ["polarity"])
        # Polish writes "nie" apart from the verb, so only a separate negation word keeps "not" there.
        self.assertEqual(_errors("pl", "The scooter didn't answer", "Hulajnoga nieodpowiedziała"), ["polarity"])


class TermInAnotherSenseTest(unittest.TestCase):
    """An ordinary "try again" is not the experimental Try term (check.NOT_THE_TERM)."""

    @staticmethod
    def _glossary_errors(english, text, safety="lock"):
        entry = {"key": "k", "kind": "string", "english": english, "safety": safety, "dnt": [], "formatted": True,
                 "glossary": [{"term": "Try", "target": ["Essayer", "essayez"], "safety": "experimental",
                               "dnt": False}]}
        language = {"tag": "fr", "plurals": ["one", "other"], "script": "Latn", "font": "system"}
        return [f for f in check.check_key(entry, language, "string", {"other": text})
                if f["check"] == "glossary" and f["severity"] == check.ERROR]

    def test_try_again_needs_no_try_term(self):
        for english in ("The scooter wasn't ready; try again", "Tap Read PIN status to try again.",
                        "Trying again in 5 s"):
            self.assertEqual(self._glossary_errors(english, "La trottinette n'était pas prête ; réessayez"), [],
                             english)
            entry = {"kind": "string", "english": english, "safety": "lock",
                     "glossary": [{"term": "Try", "target": ["Essayer"], "safety": "experimental"}]}
            self.assertEqual(list(check._terms_in(entry, english)), [], english)

    def test_the_experimental_try_still_needs_its_term(self):
        self.assertTrue(self._glossary_errors("Try this control", "Tester cette commande", "experimental"))
        # One ordinary "try again" does not excuse the experimental Try beside it.
        self.assertTrue(self._glossary_errors("Try failed. Try again or try another control.",
                                              "Échec. Réessayez ou testez une autre commande.", "experimental"))


class StopSensesRealGlossaryTest(unittest.TestCase):
    """Stop has two senses (Codex's review of #320): silencing an alarm or ending a recording (the stop
    row) and stopping the scooter (the stop-scooter, bring-to-a-stop and stop-to-change rows). Checked
    against the real l10n/glossary files: a scooter-stop form must not pass for the alarm, and an alarm
    verb must not pass for the scooter."""

    TAGS = ("es", "pt-PT", "pt-BR", "sv", "de", "fr", "it")
    # The texts each language ships (res/values-*/), by English.
    SCOOTER = {
        "Bring the scooter to a stop first": {  # ble_stop_scooter_first, controls_reason_moving
            "es": "Detén el patinete primero", "pt-PT": "Tens de parar a trotinete primeiro",
            "pt-BR": "Pare o patinete primeiro", "sv": "Stanna elsparkcykeln först",
            "de": "Halte den Scooter zuerst an", "fr": "Immobilisez d’abord la trottinette",
            "it": "Prima ferma il monopattino"},
        "Stop to change": {  # main_row_stop_to_change
            "es": "Detén el patinete para cambiarlo", "pt-PT": "Tens de parar a trotinete para alterar",
            "pt-BR": "Pare o patinete para alterar", "sv": "Stanna för att ändra",
            "de": "Halte den Scooter an, um Änderungen vorzunehmen.",
            "fr": "Immobilisez la trottinette pour modifier", "it": "Ferma il monopattino per modificare"},
        "Stop the scooter to try these.": {  # more_lab_held_moving
            "es": "Detén el patinete para probar estos controles.",
            "pt-PT": "Tens de parar a trotinete para experimentar estes controlos.",
            "pt-BR": "Pare o patinete para experimentar estes controles.",
            "sv": "Stanna elsparkcykeln för att prova reglagen.",
            "de": "Halte den Scooter an, um diese auszuprobieren.",
            "fr": "Immobilisez la trottinette pour essayer ces commandes.",
            "it": "Ferma il monopattino per provare questi comandi."},
    }
    ALARM = {  # main_alert_stop_alarm, "Stop alarm"
        "es": "Detener alarma", "pt-PT": "Parar alarme", "pt-BR": "Parar alarme", "sv": "Stoppa larmet",
        "de": "Alarm stoppen", "fr": "Arrêter l’alarme", "it": "Interrompi l'allarme"}
    RECORDING = {  # main_row_recording_label, "Recording label · stop to change"
        "es": "Etiqueta de grabación · detén la grabación para cambiarla",
        "pt-PT": "Etiqueta da gravação · tens de parar para alterar",
        "pt-BR": "Rótulo da gravação · pare para alterar",
        "sv": "Inspelningsetikett · stoppa för att ändra",
        "de": "Aufzeichnungsbezeichnung · zum Ändern Aufzeichnung beenden",
        "fr": "Libellé de l’enregistrement · arrêtez l’enregistrement pour le modifier",
        "it": "Etichetta della registrazione · interrompi per modificare"}

    @staticmethod
    def _hits(tag, english):
        return pack.glossary_hits(translib.load_glossary(translib.Paths(REPO_ROOT), tag), english)

    @classmethod
    def _findings(cls, tag, english, text, safety):
        """Glossary findings of check_key on a key whose hits come from the real glossary, as pack.py
        builds them."""
        entry = {"key": "k", "kind": "string", "english": english, "safety": safety, "dnt": [],
                 "formatted": True, "glossary": cls._hits(tag, english)}
        language = {"tag": tag, "plurals": ["one", "other"], "script": "Latn", "font": "system"}
        return [(f["severity"], f["message"]) for f in check.check_key(entry, language, "string", {"other": text})
                if f["check"] == "glossary"]

    def _assert_error(self, tag, english, text, safety):
        self.assertIn(check.ERROR, [s for s, _ in self._findings(tag, english, text, safety)], (tag, text))

    def test_the_shipped_scooter_texts_pass(self):
        for english, texts in self.SCOOTER.items():
            for tag in self.TAGS:
                self.assertEqual(self._findings(tag, english, texts[tag], "ride"), [], (tag, english))

    def test_the_shipped_alarm_and_recording_texts_pass(self):
        for tag in self.TAGS:
            self.assertEqual(self._findings(tag, "Stop alarm", self.ALARM[tag], "alarm"), [], tag)
            self.assertEqual(self._findings(tag, "Recording label · stop to change", self.RECORDING[tag], None),
                             [], tag)

    def test_scooter_stop_forms_fail_for_the_alarm(self):
        # es, pt-PT and pt-BR use one verb for both senses (detener, parar), so only the languages
        # with a separate scooter verb can tell these apart.
        for tag, text in (("fr", "Immobilisez l’alarme"), ("sv", "Stanna larmet"),
                          ("de", "Halte den Scooter an: Alarm"), ("it", "Ferma il monopattino: allarme")):
            self._assert_error(tag, "Stop alarm", text, "alarm")

    def test_alarm_verbs_fail_for_the_scooter(self):
        for tag, text in (("sv", "Stoppa elsparkcykeln först"), ("de", "Stopp den Scooter zuerst"),
                          ("it", "Interrompi prima la marcia"), ("fr", "Arrêtez d’abord l’alarme")):
            self._assert_error(tag, "Bring the scooter to a stop first", text, "ride")
        self._assert_error("sv", "Stop to change", "Stoppa för att ändra", "ride")
        self._assert_error("de", "Stop the scooter to try these.", "Stopp den Scooter, um diese auszuprobieren.",
                           "ride")

    def test_which_stop_a_text_uses(self):
        def ids(english):
            return [h["id"] for h in self._hits("de", english) if h["id"].startswith(("stop", "bring"))]
        self.assertEqual(ids("Stop alarm"), ["stop"])
        self.assertEqual(ids("Bring the scooter to a stop first"), ["bring-to-a-stop"])
        self.assertEqual(ids("Stop the scooter to try these."), ["stop-scooter"])
        self.assertEqual(ids("Stop to change"), ["stop-to-change"])
        self.assertEqual(ids(", stop to change"), ["stop-to-change"])
        # The recording stops here, not the scooter: the Stop term applies (translib.OTHER_SENSE).
        self.assertEqual(ids("Recording label · stop to change"), ["stop"])
        # A pack entry that still lists both terms is read the same way (check._terms_in).
        both = {"kind": "string", "glossary": [{"term": "Stop", "target": ["Stopp"], "safety": "alarm"},
                                               {"term": "Stop to change", "target": ["anhalten"], "safety": "ride"}]}
        self.assertEqual([h["term"] for h in check._terms_in(both, "Recording label · stop to change")], ["Stop"])
        self.assertEqual([h["term"] for h in check._terms_in(both, "Stop to change")], ["Stop to change"])


class TurkishCaseTest(unittest.TestCase):
    """Turkish lower case: "KAPALI" is "kapalı" and "AÇIK" is "açık" (I to ı, İ to i), so changing
    only the case of a draft does not get an inverted state past the on/off check."""

    TR_STRINGS = (
        '<resources xmlns:volt="https://volt.jjricks.com/ns/l10n">\n'
        '    <string name="trcase_on" volt:safety="alarm">Alarm sound on</string>\n'
        '    <string name="trcase_off" volt:safety="alarm">Alarm sound off</string>\n'
        '</resources>\n')
    TR = LanguageChecksTest.TR

    @classmethod
    def setUpClass(cls):
        cls.repo = council_fixture.FixtureRepo()
        cls.repo.write("res/values/strings_trcase.xml", cls.TR_STRINGS)
        cls.repo.write("l10n/glossary/en.tsv", cls.repo.read("l10n/glossary/en.tsv") +
                       "alarm-sound\tAlarm sound\tThe alarm's sound.\tnoun\tno\talarm\tfixture\n")
        cls.repo.write("l10n/lang/tr.tsv", cls.repo.read("l10n/lang/ru.tsv").replace("\tru\n", "\ttr\n")
                       .replace("values-ru", "values-tr").replace("Русский", "Türkçe")
                       .replace("one,few,many,other", "one,other").replace("Cyrl", "Latn")
                       .replace("font\tsystem", "font\tsaira"))
        cls.repo.write("l10n/glossary/tr.tsv", GLOSSARY_HEAD +
                       "alarm-sound\tAlarm sesi\tThe alarm's sound.\tnoun\tno\talarm\tfixture\n")
        measurer = fontmetrics.Measurer(cls.repo.paths.font(), use_java=False)
        cls.font = fontmetrics.Font(cls.repo.paths.font())
        cls.pack = pack.build_pack(cls.repo.paths, "tr", "trcase", measurer)
        cls.sha = dict((e["key"], e["source_sha1"]) for e in cls.pack["entries"])

    @classmethod
    def tearDownClass(cls):
        cls.repo.close()

    def classify(self, key, text):
        """draft_apk.classify_area's state for one drafted key."""
        entry = translib.DraftEntry(key, "string", {"other": text}, text, 1, [], self.sha[key])
        draft = translib.Draft("claude-opus", "claude", "opus", "opus", None, {key: entry}, None)
        states, _, warned = draft_apk.classify_area(self.pack, draft, self.font, None)
        return states[key][0], states[key][1], warned.get(key, "")

    def test_the_case_of_an_inverted_state_does_not_hide_it(self):
        for key, text in (("trcase_on", "Alarm sesi kapalı"), ("trcase_on", "Alarm sesi KAPALI"),
                          ("trcase_on", "ALARM SESİ KAPALI"), ("trcase_on", "Alarm sesi Kapalı"),
                          ("trcase_off", "Alarm sesi açık"), ("trcase_off", "Alarm sesi AÇIK"),
                          ("trcase_off", "ALARM SESİ AÇIK"), ("trcase_off", "Alarm sesi Açık")):
            state, reason, _ = self.classify(key, text)
            self.assertEqual(state, "failing", (key, text, reason))
            self.assertIn("the opposite state", reason, (key, text))

    def test_the_right_state_in_any_case_passes(self):
        for key, text in (("trcase_off", "Alarm sesi kapalı"), ("trcase_off", "Alarm sesi KAPALI"),
                          ("trcase_off", "ALARM SESİ KAPALI"), ("trcase_off", "Alarm Sesi Kapalı"),
                          ("trcase_on", "Alarm sesi açık"), ("trcase_on", "Alarm sesi AÇIK"),
                          ("trcase_on", "ALARM SESİ AÇIK"), ("trcase_on", "Alarm sesi Açık"),
                          ("trcase_on", "ALARM SESİ ETKİN"), ("trcase_off", "ALARM SESİ DEVRE DIŞI")):
            self.assertEqual(self.classify(key, text), ("translated", "", ""), (key, text))

    def test_negation_and_lock_words_in_capitals(self):
        self.assertEqual(lexicon.state_words("tr", "ALARM AÇIK DEĞİL"), ([], ["açık"]))
        self.assertEqual(lexicon.state_words("tr", "Alarm KAPALI DEĞİLDİR"), (["kapalı"], []))
        self.assertEqual(lexicon.state_words("tr", "ALARM KAPATILMADI"), (["kapatılmadı"], []))
        polarity = LanguageChecksTest.tr_polarity
        self.assertEqual(polarity(self, "Volt isn't affiliated", "Volt İLİŞKİLİ DEĞİLDİR"), [])
        self.assertEqual(polarity(self, "Never", "HİÇBİR ZAMAN"), [])
        self.assertEqual(polarity(self, "Not confirmed", "ONAYLANDI"), ["error"])
        self.assertEqual(polarity(self, "Confirmed", "ONAYLANMADI"), ["error"])
        for text in ("KİLİTLİ", "Kilitli", "KİLİT DURUMU", "SCOOTER’I KİLİTLE"):
            self.assertTrue(lexicon.term_found("tr", "Latn", "kilit", text, True, True), text)
        for text in ("KİLİT AÇIK", "Kilit: AÇIK", "KİLİTSİZ", "KİLİT KALDIRILDI", "KİLİT DEVRE DIŞI"):
            self.assertFalse(lexicon.term_found("tr", "Latn", "kilit", text, True, True), text)
        # An approved target written with a dotted capital still matches a lower-case draft.
        self.assertTrue(lexicon.term_found("tr", "Latn", "KİLİT", "Kilit durumu", True, True))
        for english, text in (("Lock: on", "KİLİT: AÇIK"), ("Lock: off", "Kilit: KAPALI")):
            self.assertIn("error", polarity(self, english, text, "lock"), text)

    def test_lower_for(self):
        self.assertEqual(lexicon.lower_for("tr", "KAPALI AÇIK İZİN"), "kapalı açık izin")
        self.assertEqual(lexicon.lower_for("az", "İŞIQ"), "işıq")
        # Every other language keeps Python's lower case, byte for byte.
        for lang in ("de", "en", "es", "ru", None):
            for text in ("AUS", "Alarm AUS", "KAPALI", "İstanbul", "ÄÖÜ ẞ"):
                self.assertEqual(lexicon.lower_for(lang, text), text.lower(), (lang, text))
        self.assertEqual(lexicon.state_words("de", "Alarm AUS"), lexicon.state_words("de", "Alarm aus"))
        self.assertEqual(lexicon.normalize("İ"), "İ".lower())

    def test_the_turkish_lists_are_in_turkish_lower_case(self):
        def words(value):
            if isinstance(value, str):
                yield value
            elif isinstance(value, (set, frozenset, tuple, list)):
                for v in value:
                    yield from words(v)
            elif hasattr(value, "pattern"):
                yield value.pattern
        tables = [getattr(lexicon, name) for name in dir(lexicon) if name.isupper()]
        for table in tables:
            if not isinstance(table, dict):
                continue
            for lang in ("tr", "az"):
                for word in words(table.get(lang, ())):
                    stripped = re.sub(r"\\[A-Za-z]", "", word)
                    self.assertEqual(stripped, lexicon.lower_for(lang, stripped), (lang, word))


class NegatedAdjectiveTest(unittest.TestCase):
    """fr "inachevé" and sv "oavslutat" are the languages' own word for unfinished."""

    def test_unfinished_as_a_native_negated_word(self):
        self.assertEqual(_errors("fr", "Unfinished test", "Test inachevé"), [])
        self.assertEqual(_errors("fr", "2 unfinished tests", "2 tests inachevés"), [])
        self.assertEqual(_errors("sv", "Unfinished test", "Oavslutat test"), [])
        self.assertEqual(_errors("sv", "Unfinished tests", "Oavslutade tester"), [])

    def test_the_un_word_dropped_still_fails(self):
        self.assertEqual(_errors("fr", "Unfinished test", "Test achevé"), ["polarity"])
        self.assertEqual(_errors("sv", "Unfinished test", "Avslutat test"), ["polarity"])

    def test_added_where_the_english_is_positive_only_warns(self):
        for tag, english, text in (("fr", "Test finished", "Test inachevé"), ("sv", "Test finished", "Oavslutat test")):
            polarity = [f["severity"] for f in _key_findings(tag, english, text) if f["check"] == "polarity"]
            self.assertEqual(polarity, [check.WARN], text)


if __name__ == "__main__":
    unittest.main()
