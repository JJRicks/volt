"""The committed l10n/ files keep their formats: languages, glossary, ledgers and lint baselines."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_catalog  # noqa: E402
import hardcoded_lint  # noqa: E402
import l10nlib  # noqa: E402
import ledger_check  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
L10N = os.path.join(ROOT, "l10n")
GLOSSARY_HEADER = ("id", "term", "definition", "pos", "dnt", "safety", "source")


class RepositoryFilesTest(unittest.TestCase):
    def test_languages(self):
        languages = gen_catalog.load_languages(os.path.join(L10N, "lang"))
        english = languages[0]
        self.assertEqual((english.tag, english.folder, english.status, english.plurals), ("en", "values", "stable", "one,other"))

    def test_glossaries(self):
        directory = os.path.join(L10N, "glossary")
        self.assertIn("en.tsv", os.listdir(directory))
        for name in os.listdir(directory):
            if not name.endswith(".tsv"):
                continue
            _, rows = l10nlib.read_tsv(l10nlib.read_text(os.path.join(directory, name)), GLOSSARY_HEADER)
            ids = [fields[0] for _, fields in rows]
            self.assertEqual(len(ids), len(set(ids)), name)
            for line, (term_id, term, definition, pos, dnt, safety, source) in rows:
                where = "%s:%d" % (name, line)
                self.assertRegex(term_id, r"^[a-z][a-z0-9-]*$", where)
                self.assertTrue(term and definition and pos and source, where)
                self.assertIn(dnt, ("yes", "no"), where)
                self.assertTrue(safety == "-" or safety in gen_catalog.SAFETY, where)
                # One matchable term and one part of speech per row; aliases get rows of their own.
                self.assertNotIn(";", term, where)
                self.assertNotIn("(", term, where)
                self.assertNotIn(";", pos, where)
            if name == "en.tsv":
                self.assertGreaterEqual(len(rows), 50)
                for term_id in ("not-affiliated", "owner-identity", "owner-key", "handlebar-pin", "charge-ceiling"):
                    self.assertIn(term_id, ids)

    def test_ledgers_parse(self):
        directory = os.path.join(L10N, "ledger")
        for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
            if name.endswith(".tsv"):
                ledger_check.parse_ledger(l10nlib.read_text(os.path.join(directory, name)), name)

    def test_baselines_parse_and_name_real_files(self):
        # Baselines only shrink as batches convert files, so this checks their shape, not their number.
        sources = set(hardcoded_lint.baseline_name(p) for p in hardcoded_lint.source_files(ROOT))
        directory = os.path.join(L10N, "baseline")
        names = [n for n in os.listdir(directory) if n.endswith(".txt")] if os.path.isdir(directory) else []
        for name in names:
            self.assertIn(name, sources)
            counter = hardcoded_lint.parse_baseline(l10nlib.read_text(os.path.join(directory, name)))
            self.assertTrue(counter, "%s is empty; delete it instead" % name)


if __name__ == "__main__":
    unittest.main()
