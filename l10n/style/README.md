# Style guides

`l10n/style/<tag>.md` is one language's style guide. `pack.py` copies it into every work pack, and
every translator's prompt quotes it in full. `council.py prompt` refuses when the file is missing
(`--no-style-guide` waives that for a test) and when it changed after the pack was built, so run
`pack.py` again after editing it.

The guide is read by translators who see only UI strings, so it states rules and gives examples
rather than explaining the app. Write it in English, with examples in the language. Every guide
has these sections, in this order.

## Register and form of address

The form of address from `l10n/lang/<tag>.tsv` (`formality`) and how it is used: imperatives on
buttons, verbs or nouns in labels, how an instruction talks to the rider, what to do when the
language avoids direct address.

## Punctuation and quotes

Quotation marks (`quote_style`), ellipsis, colons, dashes, and the spaces a language puts inside or
around them. **Saira has no glyph for U+202F** (narrow no-break space), so a Saira language never
uses it: French writes U+00A0 before `:`, `;`, `?`, `!`, `%` and inside `«…»`. `glossary-check`
refuses U+202F in a glossary term; `check.py` only warns about it in a draft, and
`GlyphCoverageTest` refuses it in the text that ships.

## Capitalization of buttons, labels and titles

Sentence case or title case for each, with one example of each, and what a word after a colon does.

## Numbers, dates and units

Decimal and grouping style (`decimal_style`), percent (`percent_style`), date and time order, and
the space between a number and its unit. Unit symbols (km/h, V, A, Wh) stay as written and drive-mode
names stay as the scooter shows them; unit words are translated.

## How to shorten labels for ladder rungs

How `_short` and `_compact` rungs lose length, in order of preference, with at least three real
examples from full to short to compact. Which abbreviations readers of this language know, and how
they are marked (`Odo.`). Never a break in the middle of a word and never a word cut so far that it
reads as another word.

## Safety wording

The approved wording for lock, unlock, alarm, charge, owner key, PIN, experimental and the
disclaimers, matching `l10n/glossary/<tag>.tsv`. These are **never inverted or softened**: locked and
unlocked stay distinct, on and off and never and always stay what they are, "unverified" or
"unknown" never reads as confirmed, and "not affiliated with NAVEE" stays as strong as the English.
Name any word in this language that looks right and means the opposite, and any shortening that
would blur a state.

## Sources consulted

What the guide is based on, with links or titles: the official NAVEE app's wording in this language
where it exists, the platform's style guide (Android, Microsoft's language style guides), and any
native speaker's review. A rule with no source says it is a judgment call.
