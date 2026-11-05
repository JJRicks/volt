# Volt style guide: Greek (el)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 9; the three-vendor debate decides later. No native speaker has reviewed it. The owner's direction is that non-Latin languages come only if easy, so this setup is kept proportionate and the language stays `draft`. Greek is monotonic modern Greek, for Greece and Cyprus alike.

`font=system`: Saira has no Greek letters, so Greek text draws in Android's system font and **Saira width budgets can't be measured for it**. `check.py` flags these keys for the device fit pass that wave 3 needs. Follow section 5 anyway: Greek words run long.

## 1. Register and address

- Use **εσύ**, singular, in every sentence: *Σύνδεσε τον φορτιστή*, *το πατίνι σου*. Never the plural εσείς forms (*Συνδέστε*, *σας*). **Debate item:** Android's and Microsoft's Greek use the polite plural (*Πατήστε*), so Volt will read friendlier than the phone around it. εσύ is the plan's proposal (7.4), awaiting the owner's confirmation.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the verbal noun, as Android's Greek does:** *Σύνδεση*, *Αποθήκευση*, *Κλείδωμα*, *Ακύρωση*. They carry no person, so they never clash with εσύ. **Instructions in sentences use the singular imperative:** *Σύνδεσε ξανά για να το επαληθεύσεις*. Don't mix the two in one string.
- Prefer the perfective imperative for one action: *Πάτησε*, *Κλείδωσε*, *Όρισε*. Use *Πάτησε* for tap, never *Πατήστε* or *Κάνε κλικ*.
- A negative instruction uses *μην*: *Μην κλείσεις την εφαρμογή*. Keep the final ν of *δεν* and *μην* before every letter (Microsoft's convention).
- Use the passive or the state itself for what Volt or the scooter did: *Δεν ήταν δυνατή η ανάγνωση*.
- Generic masculine for roles (*ιδιοκτήτης*), and rewrite to avoid gendering the rider where that is easy.
- Volt is neuter when an article is needed: *το Volt*, *του Volt*. NAVEE is feminine, for the company: *η NAVEE*, *τη NAVEE*, *της NAVEE*. Both names never decline.

## 2. Punctuation and quotes

- **The Greek question mark is `;`.** Type the ordinary ASCII semicolon U+003B, never the look-alike U+037E, which normalizes to it anyway: *Να διαγραφεί το %1$s;*. The English `?` must not survive in Greek text. **There is no semicolon as a pause in Volt's Greek:** use a comma, a period or the rewrite, so `;` always means a question.
- Quotes: **«…»** first, **“…”** inside. Never straight quotes. Saira's coverage doesn't matter here: the system font has them.
- A sentence that names a button, tab or screen puts its exact label in «…»: *Πάτησε «Επιτρέπεται»*, *άνοιξε «Αντίγραφο ασφαλείας και επαναφορά»*. A feature mentioned in passing stays unquoted, with lower case.
- A sentence-final period goes outside closing quotes and parentheses when the quote is a label: *Πάτησε «Σύνδεση».* Microsoft's guide puts it inside (*«ΟΚ.»*), but that changes the label; a judgment call.
- Commas: no comma before *και* or *ή* in a simple list.
- Keep the English's `·` separators and `…` as written (U+2026, not three periods). A verb in progress is the noun plus `…`: *Σύνδεση…*, *Κλείδωμα…*.
- A colon in a label is followed by lower case unless a name follows: *Τελευταία γνωστή: κλειδωμένο*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.
- **The article's final ν:** *τον* and *έναν* always keep it. *την* drops it before β, γ, δ, ζ, θ, λ, μ, ν, ρ, σ, φ, χ: *τη NAVEE*, *τη φόρτιση*. Keep it before every vowel and stop, and μπ, ντ, γκ count as stops: *την μπαταρία*.
- Only NFC monotonic letters: the tonos forms U+03AC and so on (ά έ ή ί ό ύ ώ), never the polytonic oxia U+1F71 family, and never a Latin look-alike in a Greek word (Latin A, E, O, K, M, T). `check.py` refuses mixed letters.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: *Όριο φόρτισης*, *Πειραματικά στοιχεία ελέγχου*, *Λειτουργία χαμηλής μπαταρίας*. Never English Title Case, even when the English has it (*Walk Assist* → *Υποβοήθηση βαδίσματος*).
- Feature names are lower case mid-sentence: *ενεργοποίησε την προγραμματισμένη φόρτιση*.
- Days, months and language names take a capital in Greek: *Τρίτη*, *5 Μαρτίου*, *στα Αγγλικά*. Adjectives from a country do not: *ελληνικές γραμματοσειρές*.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS. Drive modes and their level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *λειτουργία Sport* where the English says Sport.
- **Write every word with its tonos and its final sigma, in the case you are writing:** *Μπαταρίας*, not *Μπαταριας*, and never σ at the end of a word. A word's first capital keeps its tonos, placed before the letter: *Ώρα*, *Έναρξη*, *Άγνωστο*, *Όριο*.
- **All-caps text takes no tonos.** The whole word is capitals and no accent: *ΜΠΑΤΑΡΙΑ*, *ΡΥΘΜΙΣΕΙΣ*, *ΦΩΤΑ*. Final sigma is plain Σ. Three exceptions: the disjunctive *ή* keeps it as **Ή**; a dialytika stays (*ΑΫΛΟΣ*); and a tonos on the first vowel of a pair that would read as a diphthong becomes a dialytika on the second (*Μάιος* → *ΜΑΪΟΣ*, *τρόλεϊ* → *ΤΡΟΛΕΪ*). *ΦΏΤΑ* and *ΜΠΑΤΑΡΊΑ* are wrong.
- **Volt upper-cases some labels itself:** section headings through `Ui.sectionLabel` and `setAllCaps(true)`. Write those strings in ordinary sentence case with their accents and final sigma, and let the app capitalize them. Only type capitals by hand where the English string itself is capitals. Because capitals lose the tonos, don't use a word on an upper-cased heading whose meaning depends on it. See *Ποτέ* in section 6.

## 4. Numbers, dates and units

- Decimal comma and thousands period in text you write: *3,5*, *1.234,5*. Volt's `Fmt` formats live numbers; never rewrite a placeholder's number or add separators around one.
- **Percent takes no space:** `%1$s%%` gives *85%*, as Microsoft's Greek writes *12%*. The rule covers `formatted="false"` text too, whose literal is `100%`.
- Numbers in the English stay in the Greek as digits, Latin digits only. Don't write them out or change them.
- Unit symbols stay in Latin and take a no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Never U+202F. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. **Don't use the Greek *χλμ.* or *χλμ/ώρα***, however common: the project keeps SI symbols. Unit words are translated: *χιλιόμετρα*, *μίλια*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose is day, then the month in the genitive: *5 Μαρτίου*. Don't write *π.μ./μ.μ.* yourself.
- Plurals: `one` covers exactly 1, `other` everything else: *1 διαδρομή*, *%1$d διαδρομές*. A plural is real grammar in both forms, and the noun changes: *1 πατίνι*, *%1$d πατίνια*. Don't use the numerals *ένα*, *μία* in place of the digit the English shows.

## 5. Shortening labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. **No Saira measurements exist for Greek**, so these rules are by judgment: keep rungs clearly shorter than the rung above and let the device pass find what doesn't fit. Try these in order:

1. Drop words the screen already supplies: *Ρυθμίσεις μπαταρίας* → *Μπαταρία* on a Settings tile.
2. Drop the part the rung doesn't need: *Ρεύμα μπαταρίας* → *Ρεύμα*.
3. Use a shorter common word: *Αντίγραφο ασφαλείας* → *Αντίγραφο*.
4. Use an accepted abbreviation from this table. Greek cuts end **on a consonant, before a vowel**, then a period (*αρ.*, *δηλ.*), and keep the tonos if its syllable remains. Nothing else: no invented cuts, no cut that stops on a vowel, and no slash forms like Android's *Δημ/γία*.

| Full | Abbreviated | Compact | Note |
|---|---|---|---|
| Οδόμετρο | **Οδόμ.** | | The accent stays. Never *Χλμ.*, which reads as the unit |
| Μέγιστη ταχύτητα | **Μέγ. ταχύτητα** | **Μέγ. ταχ.** | The setting. Never for the measured value |
| Υψηλότερη ταχύτητα | **Υψηλ. ταχύτητα** | **Υψηλ. ταχ.** | The measured value. Never *Μέγ.* |
| Μέση ταχύτητα | **Μέση ταχ.** | | *Μέση* stays whole |
| Θερμοκρασία | **Θερμ.** | | |
| Χωρητικότητα | **Χωρητ.** | | |
| Αυτονομία | **Αυτον.** | | *Εκτίμηση αυτον.* |
| Μπαταρία | **Μπατ.** | | Only where *Μπαταρία* doesn't fit |
| Ρυθμίσεις | **Ρυθμ.** | | |
| Πληροφορίες | **Πληροφ.** | | |
| ελάχιστο, μέγιστο | **ελάχ.**, **μέγ.** | | |
| περίπου | **περ.** | | |
| αριθμός | **αρ.** | | |
| εκτιμώμενο, εκτιμώμενη | **εκτιμ.** | | Never *εκτ.* |

Real ladders, full → short → compact:

- *Current trip average speed*, *Trip avg. speed*, *Avg. speed*: **Μέση ταχύτητα τρέχοντος ταξιδιού** → **Μέση ταχ. ταξιδιού** → **Μέση ταχ.**
- *Total odometer*, *Odometer*, *Odo.*: **Συνολικό οδόμετρο** → **Οδόμετρο** → **Οδόμ.**
- *Battery current (signed)*, *Battery current*, *Current*: **Ρεύμα μπαταρίας (με πρόσημο)** → **Ρεύμα μπαταρίας** → **Ρεύμα**
- *Rated battery capacity*, *Rated capacity*, *Capacity*: **Ονομαστική χωρητικότητα μπαταρίας** → **Ονομαστική χωρητικότητα** → **Χωρητικότητα**, or **Χωρητ.** if that doesn't fit

An abbreviation ends in a period, and it counts toward the width. Abbreviations don't change in the plural. A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Κλείδωμα, Ξεκλείδωμα, Κλειδωμένο, Ξεκλειδωμένο, Μη επαληθευμένο, Άγνωστο, Ποτέ, Πάντα, Συναγερμός, PIN*. *Κλειδ.* and *Ξεκλ.* are not allowed: a cut of the same stem hides the only difference between the two states. If one of these doesn't fit, write the whole word and report it in your notes rather than shortening it.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms. State words agree with what they describe; for the scooter, *πατίνι*, that is neuter.

| English | Greek | Rules |
|---|---|---|
| Lock (action, card title) | **Κλείδωμα** | Button or title. Verb *κλειδώνω*: *Κλείδωσε το πατίνι*. Never a state |
| Locked | **Κλειδωμένο** | Agrees: *κλειδωμένη* with a feminine subject. Never *Κλειστό* (closed, or switched off) or *Ασφαλισμένο* (insured) |
| Unlock (action) | **Ξεκλείδωμα** | Verb *ξεκλειδώνω*: *Ξεκλείδωσε το πατίνι* |
| Unlocked | **Ξεκλειδωμένο** | Never *Ανοιχτό* (open, or on), *Ελεύθερο* or *Ξεκλείδωτο* |
| Lock state unverified | **Μη επαληθευμένη κατάσταση κλειδώματος** | Must never read as confirmed. Keep *Μη* |
| Lock state unknown | **Άγνωστη κατάσταση κλειδώματος** | Must never read as confirmed |
| Unknown (alone) | **Άγνωστο** | Agrees: *άγνωστη*, *άγνωστος* |
| Unverified | **Μη επαληθευμένο** | Agrees. *Χωρίς επαλήθευση* where a noun's gender can't be known. Never *ανεπαλήθευτο* |
| Never | **Ποτέ** | The stress is on the last syllable. See below |
| Always | **Πάντα** | *Always on*: **Πάντα αναμμένο**, agreeing with the light |
| Alarm | **Συναγερμός** | Masculine; genitive *συναγερμού*. Never *ξυπνητήρι* (a clock's alarm), and never *ειδοποίηση*: an alert is **Ειδοποίηση**, and they stay distinct |
| Alarm sound | **Ήχος συναγερμού** | |
| Stop (a sounding alarm) | **Διακοπή** | Sentence: *σταμάτα τον συναγερμό* |
| PIN | **PIN** | Neuter, never declines: *το PIN*, *του PIN*, *τα PIN*. Not *κωδικός* |
| Handlebar PIN | **PIN τιμονιού** | A password is **κωδικός πρόσβασης**; keep them separate |
| PIN unlock | **Ξεκλείδωμα με PIN** | The toggle |
| Owner key | **κλειδί ιδιοκτήτη** | A secret, and always digital. "Not a password" is *Δεν είναι κωδικός πρόσβασης*. Never *κωδικός ιδιοκτήτη* |
| Charge limit | **Όριο φόρτισης** | Never *ανώτατο σημείο* or *ταβάνι* |
| Experimental | **Πειραματικό**, agreeing: *πειραματική*, *πειραματικά* | |
| Untested | **Μη δοκιμασμένο** | Agrees. *Χωρίς δοκιμή* where gender can't be known |
| Unconfirmed, Not confirmed | **Μη επιβεβαιωμένο**, **Δεν επιβεβαιώθηκε** | Keep the negation |
| not affiliated with NAVEE | **χωρίς σχέση με τη NAVEE** | Never soften it: no *μη επίσημο*, *ανεξάρτητο από*, *όχι άμεσα συνδεδεμένο*. **Never *μη συνδεδεμένο με*: *συνδεδεμένο* is this guide's word for connected over Bluetooth, so it would say "not connected to NAVEE".** In a sentence: *Το Volt δεν έχει σχέση με τη NAVEE* |

**Words that look right and mean the opposite, or blur a state:**

- ***Ποτέ* (never) and *πότε* (when) differ only by where the accent falls.** Never drop or move that accent. In all-caps they are the same word, *ΠΟΤΕ*: if a string that says never lands on an upper-cased heading, rewrite the heading or report it.
- *Κλείσιμο*, *Κλειστό* and *Άνοιγμα*, *Ανοιχτό* talk about closing and opening, often about power. Don't use them for lock or unlock: *Ανοιχτό* next to a scooter reads as powered on or as unlocked.
- *Τέλος* is Android's *Done*. Bare *Τέλος φόρτισης* reads as a charge that has ended, which is why the feature name always keeps its *έως* (section 7).
- *Φόρτιση* (charging) is not *Φορτισμένο* (charged).
- *Άρση κλειδώματος* and *Απενεργοποίηση κλειδώματος* remove a lock. Say *Ξεκλείδωμα*. Use *Απενεργοποίηση* only where the English itself says turn off.
- *Κλειδωμένο* and *Ξεκλειδωμένο* differ only by *Ξε-*. Keep both whole.
- *Ενεργό* and *Ανενεργό* differ only by *Αν-*. Settings use **Ενεργό** and **Ανενεργό**; lights and motors use **Αναμμένο** and **Σβηστό**. Agree in gender with the thing named. Never invert them, never use *Ναι/Όχι* in their place. A bare *Ενεργοποίηση* is an action, not a state: don't use it as a state label.
- *η* and *ή*: **ή** with a tonos means "or", and *η* is the article. *πού/πώς* keep their accent when asking where or how.
- Keep every negation. *δεν*, *μη*, *μην*, *χωρίς*, *όχι* and *κανένα* count as negation words for the checks, and a prefix alone only counts for listed native words such as *άγνωστο*, *ανενεργό*.
- Don't add reassurance the English lacks (*ασφαλές*, *εγγυημένο*), and don't add warnings it doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Scooter:** **πατίνι**, neuter, plural *πατίνια*, genitive *πατινιού*. Never *σκούτερ*, which reads as a motor scooter; *ηλεκτρικό πατίνι* only where the English says electric.
- **Three speeds:** the setting is **Μέγιστη ταχύτητα**. A limit below it is **Όριο ταχύτητας**. The measured value on a trip or ride is **Υψηλότερη ταχύτητα**. Never swap them. *Τελική ταχύτητα* is what car specs say for a design top speed, so it's not used.
- **Held until the English terminology fix (D3) merges:** the rows `top-speed` and `maximum-speed` hold D3's meanings, but today's English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback.
- **Rides, trips and routes:** a recorded ride is a **διαδρομή**, the screen is **Διαδρομές**. *Trip*, the distance since a reset, is **ταξίδι**, the word of a vehicle's trip computer: *Απόσταση ταξιδιού*. A path on the map is a **πορεία**. Never call that path *διαδρομή*, though Maps does.
- **Try vs retry vs test:** Trying a control (Experimental, Controls to try) is **Δοκιμή**, verb *δοκιμάζω*: *Δοκίμασέ το*. Its results use **προσπάθεια**. An ordinary retry never uses *δοκιμ-*: see Fixed words. A guided test is **τεστ**, indeclinable: *Κάνε το τεστ με το πατίνι σταματημένο*. *Test scheduled charging* is a check, so it's **Έλεγχος προγραμματισμένης φόρτισης**. A compatibility check is **έλεγχος συμβατότητας**.
- **Dashboard vs display:** Volt's Home layout is the **Πίνακας ελέγχου**, or *Πίνακας* on a narrow rung. The scooter's own screen is the **οθόνη πατινιού**. They never swap. *ταμπλό* is not used.
- **Settings vs setup:** settings are **Ρυθμίσεις**, plural. One-time setup is **ρύθμιση**, singular: *ρύθμιση ιδιοκτήτη*. As Android's Greek does; don't let the number slip.
- **Restore vs set back:** bringing back a backup, or NAVEE logging as Fixed words says, is **Επαναφορά**. Undoing a try is **Αναίρεση**, and so is putting back a test's earlier value even when the English says restore. Energy recovery is **Ανάκτηση ενέργειας**, unrelated to either.
- **Readback:** **επανανάγνωση**, a neutral word, because a readback can also fail to confirm: *Διαφορετική επανανάγνωση*, *Επιβεβαιωμένο με επανανάγνωση*. Never a phrase with *επιβεβαίωση* inside it.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Απορρίφθηκε**; add *από το πατίνι* where the English names it.
- **Pair vs bind vs connect:** Bluetooth pairing is **σύζευξη** (Android's word). NAVEE account binding is **δέσμευση λογαριασμού**. A live link is **σύνδεση**. Never swap them.
- **Owner key parts:** the file is the **αρχείο ρύθμισης ιδιοκτήτη**, shortened to **αρχείο ιδιοκτήτη** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English. Reading the key from the NAVEE app is **λήψη κλειδιού**; **κλειδί** always means the owner key, and never a physical key or a Bluetooth pairing code.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes, and agree with *ανάκτηση*: *Ανενεργή, Χαμηλή, Μεσαία, Υψηλή*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Εκτίμηση Eco** is a range option, translated.
- **Roles:** owner is **ιδιοκτήτης**, shared rider is **εξουσιοδοτημένος αναβάτης**. The compact role label (`more_role_shared`, English *Shared*) is **Κοινή χρήση**. Never *αναβάτης με άδεια*, which reads as a licensed rider, and never *συνεπιβάτης*, a passenger.
- **Rider:** *αναβάτης* only in that role name. Everywhere else, address the rider directly.
- **Parked:** the meaning is stationary, so **σταματημένο**, or *σε στάση* with no gender. Not *παρκαρισμένο*.
- **Charge done by:** the feature name is **Τέλος φόρτισης έως**, and its time setting **Ώρα τέλους φόρτισης**. The feature only plans a start, so never word it as a charge that has finished, and never drop *έως*. *Φόρτιση έως* would read as the charge limit (*Φόρτιση έως 80%*). In a sentence, quote the name: *το «Τέλος φόρτισης έως» είναι ανενεργό*.
- **Cruise control:** **Διατήρηση ταχύτητας**, what it does. **Debate item:** Greek riders also say *cruise control* in Latin.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Γρήγορη πρόσβαση**. Never *Γρήγορες ρυθμίσεις*: that is Android's pull-down panel.
- **Turn signal:** **Φλας**, indeclinable, in Greek letters.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays, in Latin: it's short, and no Greek cut of *Πειραματικό* is one readers know. TalkBack speaks **Πειραματικό**.

## 8. Android's own wording

Quote Android's settings as Android writes them: **Εντοπισμός σφαλμάτων USB**, **Επιλογές για προγραμματιστές**, **Αριθμός έκδοσης**, **Ρυθμίσεις**. Phone makers rename the about-phone page and I couldn't confirm its AOSP title, so write generally: *στις Ρυθμίσεις, άνοιξε τις πληροφορίες του τηλεφώνου*. Android's button words used here: *Σύνδεση*, *Αποσύνδεση*, *Σύζευξη*, *Αποθήκευση*, *Ακύρωση*, *Επανάληψη*, *Τέλος*, *ΟΚ*.

## Rules every language follows

These came from the independent review of the first full draft (Spanish, 2026-11-05, carried over to every language). They override anything above that disagrees. The table at the end holds this language's fixed words; a term in it is used every time the concept appears, in every area.

1. **Try versus try again.** The glossary's "try" word is only for trying a control (Experimental, Controls to try). An ordinary retry ("Try again", "Try shortly") uses the fixed retry phrase below, so it can never read as "test this".
2. **Subjectless English verbs.** Dialogs and hints such as "Removes…", "Replaces…", "Opens…" describe what will happen. They must not read as a command to the rider, and a destructive confirmation must never sound like an instruction to do it. Use the form the table below gives.
3. **Unconfirmed clauses.** After a negated "confirm" or "verify" ("could not confirm that the schedule turned off"), use a form that does not presuppose the event happened. An unconfirmed state never reads as done.
4. **Placeholders.** No grammar may depend on a value you can't see: no "at %1$s" that changes with the hour, no elision, article, gender or case that a value would have to agree with. Prefer a label form: "Start time: %1$s". Numbers and units after a placeholder follow this guide's spacing.
5. **Warnings keep the effect.** Where a feature's name replaced a word that explained the hazard (motor braking became energy recovery), a warning sentence still says what happens: it brakes with the motor, so the brakes work harder.
6. **Plurals.** Every category is grammatical, even where the English `one` is wrong or a note says it can't be reached. Never copy "1 minutes" into your language.
7. **Drive modes and level names.** Keep the case the English string uses in that string (Sport, ECO, Drive). Don't change it, and don't translate them.
8. **Fit.** Never swap in a different meaning, or a colloquial word, to fit a budget. Use this guide's accepted abbreviation. If none fits, write the correct word and say so in the reply's notes: an overflow is a design problem that gets fixed, a wrong word isn't noticed.
9. **Naming things on screen.** When a sentence names a button, tab or screen, use exactly that label's translation and this guide's rule for marking it. Don't paraphrase a label.
10. **Same concept, same word.** The same English on different screens gets the same translation unless the screen really changes the meaning. Readings appear under several keys (`readings_*`, `stats_row_*`, `compat_field_*`, `dashboard_*`): the name doesn't change between them.
11. **Opaque English.** If a word-for-word rendering would be unclear ("Connected history", "Charge per gauge", "Battery-side power"), translate the meaning given by the screen and note. Don't copy the shape of the English. "Reported" and "not reported" are never word-for-word calques: use the table.
12. **Retired English terms.** Don't render owner identity, setup key or scooter key as their literal words: they are all the owner key. Don't render "speed cap" literally.
13. **"Dashboard" in the English.** Until the English fix lands, some strings say "dashboard" for the scooter's own screen. Volt's Home is one thing and the scooter's display another: translate what the string means, using the glossary's scooter-display word when the note or screen shows it is the physical display.

### Fixed words

| English concept | Meaning | This language |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Προσπάθησε ξανά**; *Προσπάθησε ξανά σε λίγο*. As a button, **Επανάληψη**. Never *Δοκίμασε ξανά*, which reads as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **χωρίς δεδομένα**. Says only that no value came, not that anything is off or fine. Never *δεν αναφέρθηκε* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **δηλωμένο** (agrees with its noun); verb **δηλώνει**: *το πατίνι δηλώνει*, *Δηλωμένη υγεία*. A claim, not a measurement. Never *αναφερόμενο* or *επιβεβαιωμένο*. *αναφορά* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **καταγραφή δραστηριότητας NAVEE**. Never *εγγραφή*, which reads as signing up for an account |
| restore logging | turning that logging back to how it was (usually off) | **επαναφορά καταγραφής δραστηριότητας**. Means putting that logging back as it was, usually off. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **βοηθητικό πρόγραμμα του Volt**; *βοηθητικό πρόγραμμα* alone once the screen makes clear which. Never *βοηθός*. The script on the computer is the different **βοηθητικό πρόγραμμα λήψης** |
| stock charger | the charger that came with the scooter | **αρχικός φορτιστής**. Never *τυπικός* |
| high-speed charger | a faster charger the rider may own | **γρήγορος φορτιστής**; alone, *Γρήγορος*. Never *υψηλής ταχύτητας*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **κενό**. Means no data was recorded, not that power or the ride was cut |
| elevation | height above sea level on a route | **υψόμετρο** |
| current direction | which way battery current flows | **φορά ρεύματος**. Never *κατεύθυνση*, which reads as a route |
| throughput | how much energy has passed through the battery | **ροή ενέργειας**; *ροή* once the screen has said energy. An amount of energy that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **ένδειξη μπαταρίας**; *ένδειξη* alone where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **ακατέργαστη τιμή**; as a tag or in `(raw)`, **RAW**, in Latin as the English writes it. Greek has no short accepted word. **Debate item** |
| last seen | when Volt last had data from the scooter | **Τελευταία ανάγνωση**; with a value, *Τελευταία ανάγνωση: %1$s*. The data may be old, so never word it as current. Not *τελευταία γνωστή*, which is the last known state |
| add | adding a widget, scooter or item | **Προσθήκη** as a button; *πρόσθεσε* in a sentence: *Πρόσθεσε άλλο πατίνι* |
| enter (typing) | typing a value | **πληκτρολόγησε**: *Πληκτρολόγησε νέο PIN*. Never *Εισαγωγή* or *εισάγω*, which also mean import |
| the app | one word for "app" used everywhere | **εφαρμογή**, feminine: *η εφαρμογή NAVEE*, *η επίσημη εφαρμογή*. Never *app* or *αππ* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **σύνδεσε το πατίνι**, *Σύνδεσε πρώτα το πατίνι*. Never *συνδέσου*, which reads as signing in. The button stays *Σύνδεση*; plugging in says *σύνδεσε τον φορτιστή* |
| set a value | one verb for setting a value on the scooter | **ορίζω**: *Ορισμός PIN*, *Όρισε %1$s*. The noun stays *ρύθμιση*, and setting something back stays *Αναίρεση* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Future with *θα*, in dialogs and hints alike: *Θα διαγραφεί αυτή η διαδρομή*, *Θα ανοίξει ο χάρτης*. Never *Διάγραψε…* or *Άνοιξε…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Αντίγραφο ασφαλείας και επαναφορά*, *Έλεγχος προγραμματισμένης φόρτισης*. Never the imperative |
| at a time | a time placeholder | Label form: *Ώρα έναρξης: %1$s*. Never *στις %1$s*: Greek says *στη 1:00* but *στις 2:00*, so the article changes with the hour |

## 9. Sources consulted

**NAVEE's official Greek: nothing found.** No Greek NAVEE page, manual or app wording turned up in the searches below; a snippet of the EU N65 manual lists English, Spanish, French, Polish, Italian and German only, so there may be none. **Every Greek word here for lock, charge limit, PIN, owner key or experimental features is Claude's own proposal**, and the official app may differ. No APK was downloaded.

- Android's own Greek, read from AOSP: [SettingsLib values-el](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-el/strings.xml) and [Settings values-el](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-el/strings.xml): *Εντοπισμός σφαλμάτων USB*, *Επιλογές για προγραμματιστές*, *Αριθμός έκδοσης*, *Σύζευξη*, *Σύνδεση*, *Αποσύνδεση*, *Ρυθμίσεις*, *Κλείδωμα*, *Ξεκλείδωμα*, *Επανάληψη*, *Τέλος*, *ΟΚ*, the backup wording *Δημιουργία αντιγράφου ασφαλείας και επαναφορά*, the verbal-noun button pattern, and the polite plural *πατήστε*. *ΣΥΖΕΥΞΗ* (`bluetooth_pairing_accept_all_caps`) confirms capitals without a tonos.
- [Microsoft Greek Localization Style Guide](https://download.microsoft.com/download/3/9/6/3963b5d8-7e0d-49c7-868e-ad4b03a7eee5/ell-grc-StyleGuide.pdf), text extracted locally: abbreviation shapes and common abbreviations, sentence case in UI, capitals on days, months and languages, the *έως* use, the final-ν rules, *12%* and *3,5*, chevron quotes as usual Greek, and *Κλείσιμο…* for an action in progress. Volt departs from it on εσύ (it uses the polite plural), on quotes (it uses straight ones for its products) and on the period after a label.
- Xiaomi's Greek pages, known only from search listings, because their fetch returned 403: titled [Ηλεκτρικό πατίνι](https://www.mi.com/gr/product/mi-electric-scooter-3/). A summarised search answer also showed *ανάκτηση ενέργειας* and *cruise control* in Greek coverage of [Xiaomi's](https://www.mi.com/gr/product/xiaomi-electric-scooter-5-max/) and Segway's scooters. That's thin evidence: the summary may have translated. [Skroutz](https://www.skroutz.cy/s/65853352/xiaomi-electric-scooter-6-ultra-bhr08ktgl-ilektriko-patini-me-25km-h-max-tachytita-kai-75km-aytonomia-se-kitrino-chroma.html) lists scooters as *ηλεκτρικό πατίνι*.
- Greek orthography on all-caps text, the dialytika, the final sigma and the question mark: from general knowledge, with only *ΣΥΖΕΥΞΗ* above as a fresh check. That Android's `toUpperCase` and `setAllCaps` drop the tonos under an `el` locale is from memory of the platform and **needs the device pass**.
- Judgment calls, unverified by a native speaker: *ταξίδι*, *πορεία*, *Οδόμετρο*, *κλειδί ιδιοκτήτη*, *εξουσιοδοτημένος αναβάτης*, *δέσμευση λογαριασμού*, *Υψηλότερη ταχύτητα*, *Διατήρηση ταχύτητας*, *Τέλος φόρτισης έως*, *χωρίς σχέση με τη NAVEE*, and all abbreviations in section 5 beyond Microsoft's list.
