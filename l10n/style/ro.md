# Volt style guide: Romanian (ro)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 9; the three-vendor debate decides later. No native speaker has reviewed it, and the official NAVEE app's Romanian wording was not found. `values-ro` serves Romania and Moldova with one standard Romanian text.

**Letters.** Write **ș ț Ș Ț with the comma below (U+0219, U+021B, U+0218, U+021A)**. Never the cedilla forms **ş ţ Ş Ţ (U+015F, U+0163, U+015E, U+0162)**, which look almost the same and are wrong. The automated checks don't catch them, because Saira has both, so a draft must be typed correctly. Use **â** inside a word and **î** at its start and end (*când*, *încărcare*, *a coborî*), and keep **î** after a prefix (*reîncearcă*, *neîncărcat*). Write *sunt* and *România*. Never drop a diacritic.

## 1. Register and address

- Use **tu**, singular, always: *Conectează încărcătorul*, *trotineta ta*. Never *dumneavoastră*, *dvs.* or plural imperatives (*Conectați*). Android's own Romanian does the same: *Salvează*, *Asociază*, *Încearcă din nou*.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the tu imperative:** *Salvează*, *Conectează*, *Blochează*. Don't use the long infinitive on a button (*Salvare*); it reads as a noun and an older style.
- **Titles, labels and section names use a noun**, and that noun may be a long infinitive: *Limită de încărcare*, *Copie de rezervă și restaurare*.
- Negative instruction: *nu* plus the short infinitive: *Nu deconecta încărcătorul*.
- For what Volt or the scooter did, use the reflexive or the state itself: *Nu s-a putut citi*, *Trotineta este blocată*.
- Avoid gendering the rider where a rewrite is easy. Role labels use the generic masculine (*proprietar*).
- Don't attach a pronoun to a verb to avoid naming the scooter: write *Blochează trotineta*, not *Blocheaz-o*. The full verb stays visible, and so does its difference from *Deblochează*.

## 2. Punctuation and quotes

- Quotes: **„…”** first (U+201E, U+201D), **«…»** inside. Never straight quotes or English “…”, and no single quotes.
- A sentence that names a button, tab or screen puts its exact label in „…”: *Atinge „Permite”*, *deschide „Copie de rezervă și restaurare”*. A feature mentioned in passing stays unquoted (section 3).
- Romanian has no serial comma before *și* or *sau*. A comma never separates a subject from its verb.
- Sentence-final period goes outside a closing quote or parenthesis unless a whole sentence is inside.
- Keep the English's `·` separators and `…` as written. A verb in progress keeps `…`: *Se conectează…*
- A colon is followed by lower case unless a name follows: *Ultima stare: blocată*.
- A dash in a sentence is an en dash with spaces ( – ). A hyphen joins clitics and endings on acronyms: *s-a*, *PIN-ul*, *ID-ul*, *firmware-ul*.
- In a safety key write the negation as a whole word: *nu a*, *nu s-a*, not *n-a*. *n-a* is correct Romanian, but the checks don't read it as a negation.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: *Limită de încărcare*, *Comenzi experimentale*, *Mod baterie scăzută*. Never English Title Case, even when the English has it (*Walk Assist* → *Asistență la mers*).
- Feature names are lower case mid-sentence: *activează încărcarea programată*.
- Days, months, languages and nationalities are lower case.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS. Drive modes and their level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *modul Sport* where the English says Sport.
- Capital letters keep their diacritics, including where Volt upper-cases text: *BATERIE*, *ÎNCĂRCARE*, *SETĂRI*.

## 4. Numbers, dates and units

- Decimal comma and a period for thousands in text you write: *1.234,5*. Volt's `Fmt` formats live numbers for the phone's region, so its output can differ; never rewrite a placeholder's number or add separators around one.
- Percent always takes a no-break space (U+00A0), never a plain space, between a number or placeholder and %: `%1$s %%` gives *85 %*. Saira has U+00A0 and lacks U+202F, so don't use U+202F. The rule covers `formatted="false"` text too, whose literal is `100 %`.
- Numbers in the English stay in the Romanian as digits. Don't write them out or change them.
- Unit symbols stay and take the same no-break space after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Symbols take no plural, no period and no *de*: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *kilometri*, *mile*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 martie*. Time uses 24 hours. Don't write *a.m./p.m.* yourself.
- **Plurals have three categories, and `other` needs *de*.** `one` is exactly 1. `few` is 0, 2 to 19, and any number ending in 01 to 19 above 100. `other` is the rest, from 20 up, and takes *de* before a counted noun:

  | `one` | `few` | `other` |
  |---|---|---|
  | `%1$d minut` | `%1$d minute` | `%1$d de minute` |
  | `%1$d cursă` | `%1$d curse` | `%1$d de curse` |
  | `%1$d trotinetă` | `%1$d trotinete` | `%1$d de trotinete` |

  Never copy `few` into `other` or the reverse. Outside a plural, don't put a unit word after a number placeholder, since *de* depends on a value you can't see: write the symbol (`%1$d min`).

## 5. Shortening labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Try these in order:

1. Drop words the screen already supplies: *Temperatura bateriei* → *Temp. baterie* → *Temp.*
2. Drop the genitive ending and prepositions, as Romanian labels and manuals do: *Tensiunea bateriei* → *Tensiune baterie*, *Limita de încărcare* → *Limită încărcare*.
3. Use a shorter common word: *Timp până la încărcarea completă* → *Timp rămas*.
4. Use an accepted abbreviation from this table. Nothing else.

Real rungs:

| English (full, short, compact) | Romanian |
|---|---|
| Total odometer, Odometer, Odo. | *Odometru total*, *Odometru*, *Odom.* |
| Battery temperature, Battery temp., Temp. | *Temperatura bateriei*, *Temp. baterie*, *Temp.* |
| Battery voltage, Battery voltage, Voltage | *Tensiune baterie*, *Tensiune baterie*, *Tensiune* |
| Time to full (est.), Time to full (est.), To full (est.) | *Timp până la încărcarea completă (estim.)*, *Timp rămas (estim.)*, *Rămas (estim.)* |
| Unverified on your model, Unverified here, Unverified | *Neverificat pe modelul tău*, *Neverificat aici*, *Neverificat* |

Accepted abbreviations, with widths measured at 12.5sp in `saira_medium.ttf` (java.awt):

| Full | Abbreviated | Width (dp) | Note |
|---|---|---|---|
| Viteză maximă | **Viteză max.**, then **Vit. max.** | 85.9 → 67.7 → 50.4 | The setting. The full form fits the 90dp tile; after D3, the short rung is *Viteză max.* |
| Viteză de vârf | **Vit. de vârf** | 80.2 → 62.9 | The measured value. Never *Vit. max.* |
| Viteză medie | **Vit. medie** | 75.2 → 57.9 | *Vit.* only before another word, never alone |
| Odometru | **Odom.** | 58.2 → 37.5 | |
| Temperatură | **Temp.** | 74.0 → 34.6 | |
| Capacitate | **Capacit.** | 60.9 → 45.5 | Never *Capac.*, which reads as *capac*, a lid |
| estimat, estimată | **estim.** | 35.2 | *Autonomie estim.* (101.8). Never *est.*, which reads as east |
| aproximativ | **aprox.** | | |
| maxim, minim | **max.**, **min.** | | *min.* with a period means minimum; `min` with no period is minutes |
| încărcare | **Încărc.** | | Only before another word: *Încărc. rapid* (70.2) |
| informații | **Info** | | A short word, no period |

Measured words that fit as they are: *Blocare* 43.5, *Setări* 34.5, *Baterie* 42.1, *Curse* 32.6, *Blochează* 59.7 (all under the 62dp tab budget); *Trotinete* 53.1, *Conducere* 61.0 for My scooters and Ride settings (under 90dp; *Conducere* is 58.5 at 12sp, under the 64dp shortcut budget); *Viteză maximă* 85.9, *Experimental* 77.1, *Experimentale* 84.1, *Autorizat* 54.0, *Autonomie* 63.7, *Date live* 50.3, *Acces rapid* 66.0.

Words that don't fit: *Deblochează* is 74.1, over a 62dp tab, so it is never abbreviated and gets reported if a tab needs it. *Trotinetele mele* is 94.5: keep it on menu rows, and use *Trotinete* in the 90dp and 64dp rungs. *Info și permisiuni* is 99.7, so it only fits wrapped at a space. *Fără confirmare* is 91.8. *Comenzi experimentale* (136.0) and *Comenzi de încercat* (115.4) wrap at a space where the English also wraps.

An abbreviation ends in a period, and the period counts toward the width. Keep diacritics in an abbreviation (*Încărc.*). A two-word label may wrap at a space where the English also wraps.

**No invented cuts.** Besides the two above: *Bat.* (reads as *bat*, I hit), *Permis.* (reads as *permis*, a licence), *Conf.*, *Exp.*, *Set.*.

**Never abbreviate:** *Blochează, Deblochează, Blocare, Deblocare, Blocată, Deblocată, Neverificat, Necunoscut, Niciodată, Întotdeauna, Alarmă, PIN*. *Bloc.* (reads as *bloc*, a block of flats) and *Debl.* are not allowed. If one of these doesn't fit, write the whole word and report it in your notes rather than shortening it.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Romanian | Rules |
|---|---|---|
| Lock (action) | **Blochează** | Button or action. Infinitive *a bloca* |
| Lock (noun, card title) | **Blocare** | Definite *blocarea*, genitive *blocării*. Never *lacăt*, *încuietoare*, *închidere* |
| Locked | **Blocată** | Agrees with *trotinetă*. *Blocat* only with a masculine or neuter subject |
| Unlock (action) | **Deblochează** | Noun: **Deblocare** |
| Unlocked | **Deblocată** | Never *deschisă*, *liberă* |
| Lock state unverified | **Stare de blocare neverificată** | Must never read as confirmed |
| Lock state unknown | **Stare de blocare necunoscută** | Must never read as confirmed |
| Unknown (alone) | **Necunoscut** | *Necunoscută* with a feminine subject |
| Unverified | **Neverificat** | The word for a control's status. *Neverificată* with a feminine subject |
| Never | **Niciodată** | In a sentence it needs *nu* too, and that is not an inversion: *Cifrele nu se afișează niciodată* |
| Always | **Întotdeauna** | *Always on* for a light: **Mereu aprinsă** |
| Alarm | **Alarmă** | Never *alertă*: alert is **Alertă**, and they stay distinct |
| Alarm sound | **Sunet de alarmă** | |
| Stop (a sounding alarm) | **Oprește** | *Oprește alarma* where there is room |
| PIN | **PIN** | Neuter: *PIN-ul*, *PIN-uri*. Not *cod* or *parolă* |
| Handlebar PIN | **PIN de ghidon** | *PIN-ul de ghidon*, or *PIN-ul de pe ghidon* in a sentence. A password is **parolă**; keep them separate |
| PIN unlock | **Deblocare cu PIN** | |
| Owner key | **cheie de proprietar** | A secret. Definite: *cheia de proprietar*. "Not a password" is *Nu este o parolă* |
| Charge limit | **Limită de încărcare** | Never *plafon* |
| Experimental | **Experimental**, **experimentală**, plural **experimentale** | Agrees with its noun |
| Untested | **Netestat** | *Netestată* with a feminine subject |
| Unconfirmed | **Neconfirmat** | |
| Not confirmed | **Fără confirmare** | Gender-free. In a sentence: *nu a fost confirmată* |
| not affiliated with NAVEE | **nu este afiliată cu NAVEE** | Feminine, for *aplicația*: *Volt 1.2 · nu este afiliată cu NAVEE*. Never soften it: no *neoficial*, *independent de*, *neafiliată direct*. Where a gender can't fit, **fără afiliere cu NAVEE** |

**Words that look right and aren't:**

- **a se bloca** also means to jam or freeze. *Trotineta s-a blocat* can read as "got stuck". Say *Trotineta este blocată* for the state and *Blocarea a reușit* for the result. An app that hangs *nu răspunde*.
- **se încarcă** means both "is charging" and "is loading". Keep it for the battery. For data, name the action: *Se citesc datele…*, *Se deschide…*. Never *Se încarcă…* for loading on a screen about charging.
- **deschis** means open and also light in colour (*albastru deschis*). It never means unlocked.
- **oprit** means stopped, off, and also forbidden. For a scooter's power say **pornită, oprită**, for settings **Activat, Dezactivat**, for lights **Aprinsă, Stinsă**. Never *Da* or *Nu* in their place, and never invert them.
- **indicat** also means advisable. That's one reason "reported" isn't *indicat* (see Fixed words).

Keep these distinct and whole:

- *Blocată* and *Deblocată* differ only by *De-*. Keep both whole, never truncate, and in sentences keep the full word so the difference stays visible.
- Don't describe an unlock as removing the lock (*Elimină blocarea*, *Scoate blocarea*); say *Deblochează*. Only say *dezactivează blocarea* where the English itself says turn off. The checks have no list of these phrases for Romanian, so a judge has to catch them.
- Keep every negation. For the checks, *nu, niciodată, fără, niciun, nicio, nimic* are negation words.
- **In a safety key, an English un- word needs one of these forms:** a negation word (*Nu este disponibil*, not *Indisponibil*; *Nu este acceptat*, not *Neacceptat*), or a glossary word (*Neverificat*, *Netestat*, *Neconfirmat*), or a form the checks list (*Necunoscut*, *Imposibil*, *Incompatibil*, *Neconectat*, *Nereușit*).
- **In a safety key, if the English has no negation, don't add one.** "Stop watching" is *Oprește urmărirea*, never *Nu mai urmări*.
- *neafiliat* alone fails the checks and reads softer; keep *nu este afiliată*.
- Don't add reassurance the English lacks (*sigur*, *garantat*), and don't add warnings it doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Three speeds:** the setting is **Viteză maximă**. A limit below it is **Limită de viteză**. The measured value on a trip or ride is **Viteză de vârf**. Never swap them.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback.
- **Rides vs trips:** a recorded ride is a **cursă** (plural *curse*), the word Romanian ride apps use. *Trip* (the distance since a reset) is **parcurs**. A path on the map is a **rută**. Riding is **conducere**, and a drive mode is a **mod de condus**.
- **Try vs retry vs test:** Trying a control (Experimental, Controls to try) is **Încearcă**, with the noun **încercare**. An ordinary retry uses the retry phrase in Fixed words. A guided test is the noun **test**, verb **testează**: *Testează cu trotineta parcată*, *Volt nu va face testul*. *Test scheduled charging* is a check, so it's **Verifică încărcarea programată**. A compatibility check is **verificare de compatibilitate**.
- **Dashboard vs display:** Volt's Home layout is the **Panou**. The scooter's own screen is the **afișajul trotinetei**. They never swap, and *bord* is not used for either. *Display & sound* is **Afișaj și sunet**.
- **Settings vs setup:** settings are **Setări**, a one-time setup is **configurare**. The noun for one setting is **setare**.
- **Restore vs set back:** bringing back a backup, or NAVEE logging as Fixed words says, is **Restaurează**. Undoing a try is **Revino** (*Revino la %1$s*), noun **revenire**, and so is putting back a test's earlier value even when the English says restore. Energy recovery is **Recuperare de energie**, unrelated to either.
- **Readback:** **recitire**, a neutral word, because a readback can also fail to confirm: *Recitire diferită*, *Setare confirmată prin recitire*. Never a phrase with *confirmare* inside it. **Readings** are **citiri**.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Respins**.
- **Pair vs bind vs connect:** Bluetooth pairing is **asociază**, as in Android. NAVEE account binding is **leagă** (*leagă contul*, noun *legarea contului*). A live link is **conectează**.
- **Owner key parts:** the file is the **fișier de configurare pentru proprietar**, shortened to **fișier de proprietar** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes, and agree with *recuperare*: *Scăzută, Medie, Ridicată, Dezactivată*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Estimare Eco** is a range option, translated.
- **Roles:** owner is **proprietar**, shared rider is **utilizator autorizat**. The compact role label (`more_role_shared`, English *Shared*) is **Autorizat**, never *Partajat*, which reads as a shared scooter.
- **Charge done by:** the feature name is **Final de încărcare**, and its time setting **Ora de final de încărcare**. The feature only plans a start, so never word it as a charge that has finished (no *finalizată*, *gata*). In a sentence, quote the name: *„Final de încărcare” este dezactivat*.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Acces rapid**. Never *Setări rapide*, which is Android's pull-down panel.
- **Controls:** a control is a **comandă**, plural **comenzi**. Features named Control keep *control*: *Controlul tracțiunii*, *Control de croazieră*. Never *pilot automat* for cruise control, which promises more than the feature does.
- **Parts:** handlebar **ghidon**, headlight **far**, tail light **lumină spate**, brake **frână**, charger **încărcător**, range **autonomie**, battery **baterie**, turn signal **semnalizare**, tire **anvelopă**, throttle **accelerație**.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays. TalkBack speaks **Experimental**.

## 8. Android's own wording

Quote Android's settings as Android writes them: **Remedierea erorilor prin USB**, **Opțiuni pentru dezvoltatori**, **Numărul versiunii**. On a phone screen, tap is **atinge**; a physical button is **apasă**. *Despre telefon* could not be confirmed and phone makers rename it, so write generally where you can: *în setări, deschide informațiile despre telefon*.

## Rules every language follows

These came from the independent review of the first full draft (Spanish, 2026-11-05). They override anything above that disagrees. The table at the end holds this language's fixed words; a term in it is used every time the concept appears, in every area.

1. **Try versus try again.** The glossary's "try" word is only for trying a control (Experimental, Controls to try). An ordinary retry ("Try again", "Try shortly") uses the fixed retry phrase below, so it can never read as "test this".
2. **Subjectless English verbs.** Dialogs and hints such as "Removes…", "Replaces…", "Opens…" describe what will happen. They must not read as a command to the rider, and a destructive confirmation must never sound like an instruction to do it. Use the form the table below gives.
3. **Unconfirmed clauses.** After a negated "confirm" or "verify" ("could not confirm that the schedule turned off"), use a form that does not presuppose the event happened. An unconfirmed state never reads as done. In Romanian use *dacă*, not *că*: *Nu s-a putut confirma dacă programarea s-a dezactivat*.
4. **Placeholders.** No grammar may depend on a value you can't see: no "at %1$s" that changes with the hour, no elision, article, gender or case that a value would have to agree with. Prefer a label form: "Start time: %1$s". Numbers and units after a placeholder follow this guide's spacing. In Romanian this includes *de* before a counted noun (section 4); *la ora %1$s* is safe.
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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Încearcă din nou**, as Android writes it; *Încearcă din nou în scurt timp*. On a narrow button that isn't a safety key, *Reîncearcă* (63.6dp). In Romanian *din nou* makes it a retry; never *Testează din nou* |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **fără date**. Says only that no value came, not that anything is off or fine. Never *neraportat* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **transmis** (agrees with its noun); verb **a transmite**: *trotineta transmite*, *Transmis de trotinetă*. A claim, not a measurement. Never *raportat*, *indicat* or *confirmat*. *raport* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **jurnalul de activitate NAVEE**. Never bare *înregistrare*, which reads as signing up or as recording a ride |
| restore logging | turning that logging back to how it was (usually off) | **restaurează jurnalul de activitate**. Means putting that logging back as it was, usually off. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **instrumentul Volt**; *instrumentul* alone once the screen makes clear which. Never *asistent*. The script on the computer is the different **instrument de captură** |
| stock charger | the charger that came with the scooter | **încărcătorul original**. Never *standard* |
| high-speed charger | a faster charger the rider may own | **încărcător rapid**; alone, *Rapid*. Never *de mare viteză*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **întrerupere**. Means no data was recorded, not that power or the ride was cut. Never *gol* |
| elevation | height above sea level on a route | **altitudine** |
| current direction | which way battery current flows | **sensul curentului**. Never *direcție*, which reads as a route or steering |
| throughput | how much energy has passed through the battery | **flux de energie**; *flux* once the screen has said energy. An amount of energy that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **indicatorul de baterie**; *indicatorul* alone where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **brut**, agreeing with its noun; as a tag **BRUT**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Ultima citire**; with a value, *Ultima citire: %1$s*. The data may be old, so never word it as current. Not *ultima stare*, which is the last known state |
| add | adding a widget, scooter or item | **adaugă**: *Adaugă un widget*, *Adaugă altă trotinetă*. Android's word |
| enter (typing) | typing a value | **introdu**: *Introdu un PIN nou*. Never *tastează* or *scrie* for a value |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **aplicație**, feminine: *aplicația*, *aplicația NAVEE*, *aplicația oficială*. Never *app* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **conectează trotineta**, *Conectează mai întâi trotineta*, *Reconectează trotineta*. Never *conectează-te*, which reads as going online. The button stays *Conectează*; plugging in says *conectează încărcătorul* |
| set a value | one verb for setting a value on the scooter | **setează**: *Setează PIN-ul*, *Setează %1$s*. Never *fixează*. The noun stays *setare*, and setting something back stays *Revino* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Future with *se*, in dialogs and hints alike: *Se va șterge această cursă*, *Se va deschide harta*. Never bare *Șterge…* or *Deschide…*, which read as commands: in Romanian the tu imperative and the English-like subjectless form are the same word |
| screen and section titles | noun form or verb form, with one example | Noun form: *Copie de rezervă și restaurare*. If the title is an action, use the long-infinitive noun: *Verificarea încărcării programate*. Never the imperative |

## 9. Sources consulted

**NAVEE's official Romanian: not found.** No public NAVEE page, manual or app text in Romanian was found. NAVEE's EU manuals appeared in other languages; a NAVEE GT3 manual hosted at electrodepot.fr returned 404, so a snippet saying it has a Romanian safety page could not be checked. The official app's Romanian, if any, is unknown. Every safety word here is Claude's own proposal, and the official app may differ. No APK was downloaded.

- [Dedeman, NAVEE EasyRide E25 Pro](https://www.dedeman.ro/ro/trotineta-electrica-adulti-navee-easyride-e25-pro-motor-480-w-viteza-maxima-25-km/h-autonomie-25-km-roti-8-5-inch-max-90-kg-pliabila-pentru-asfalt-negru-aluminiu-110-5-x-47-5-x-115-5-cm/p/7077828) (a Romanian retailer, not NAVEE, written without diacritics): *trotinetă electrică*, *aplicația dedicată NAVEE*, *blocare la distanță*, *viteză maximă*, *autonomie*, *far frontal*, *stop spate*.
- [gadget.ro on a NAVEE scooter](https://gadget.ro/trotineta-electrica-cu-autonomie-de-pana-la-65-km-si-pret-de-479-euro-livrare-gratuita-din-depozit-european/): *autonomie*, *viteza maximă*, *frâne*.
- [Evocity Veloci UP manual](https://ebd-cdn.serverhost.ro/support/vehicule-electrice/evocity-veloci-up/manual-de-utilizare-trotineta-electrica-evocity-veloci-up.pdf) (another brand): *frână*, *încărcător*, *manetă accelerație*, *lumini frontale*, *port de încărcare*, and label style without genitives (*buton pornire*). It says *display* where this guide says *afișaj*.
- Android's own Romanian, read from AOSP: [SettingsLib ro](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-ro/strings.xml) (*Remedierea erorilor prin USB*, *Opțiuni pentru dezvoltatori*, *Asociază*, *Necunoscut*) and [Settings ro](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-ro/strings.xml) (*Setări*, *Numărul versiunii*, *Salvează*, *Adaugă*, *Încearcă din nou*, *Asociază un nou dispozitiv*, *Ecran de blocare*). The SettingsLib words came through a page reader, not an exact file search.
- [Microsoft Romanian Localization Style Guide](https://download.microsoft.com/download/9/c/d/9cd86a60-685a-477f-9286-a610addd10e8/ron-rou-StyleGuide.pdf): „…” and «…», sentence case, a space before % and units, abbreviation rules. **This guide departs from it on address:** Microsoft uses the plural imperative for buttons, and the owner's plan and Android use tu.
- Search snippets only, weak evidence: [Acer Predator ES Thunder](https://www.acer.com/ro-ro/predator/emobility/escooters/predator-es-thunder) for *electronic lock* in a Romanian listing (the page itself timed out). [Xiaomi Romania](https://www.mi.com/ro/xiaomi-electric-scooter-6-ultra/) returned 403.
- **From memory, not read:** DOOM3 for â, î, ș and ț and the plural *de*; CLDR ro for one, few, other and the spaced percent; *cursă* as the ride-app word; *viteză de vârf*, *Control de croazieră*, *Controlul tracțiunii*, *anvelopă* and *lumini de zi* as motoring usage. **Debate items:** *cheie de proprietar* (*cheie* also means a physical key; Romanian has no better word for a secret), *Control de croazieră* (against *tempomat*), *Date live*, *cursă* (it can also mean a race), *Lumini liniare* and *Lumini stelare* (literal, since nothing official was found), and feminine *afiliată* for Volt.
- All other terms are judgment calls.
- Fonts: `tools/l10n/fontmetrics.py` `Font.missing` on all four `res/font/saira_*.ttf`: every Romanian letter is present, including ș ț Ș Ț (U+0219, U+021B, U+0218, U+021A), and „ ” « » … – · and U+00A0. Only U+202F is missing. Widths: `fontmetrics.Measurer`, java.awt, `saira_medium.ttf`.
