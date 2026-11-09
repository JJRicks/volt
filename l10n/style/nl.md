# Dutch style guide (nl)

**Status: Claude-only proposal, 2026-11-05.** One model drafted this. The three-vendor debate has not seen it, and no native speaker has checked it. Everything below, safety wording included, can change. "Approved" here means "the wording this proposal asks every drafter to use". One `values-nl` serves the Netherlands and Flanders, so avoid words only one of them uses.

## 1. Register and form of address

- Address the rider as **je** (owner's decision, 2026-11-02): je, jij only for stress, jou, jouw only for stress. Possessive is normally **je**: *je step*. Never u, uw or a mix. NAVEE's own Dutch product page also uses je.
- Tone: plain and calm, like a good app. Not a legal text and not marketing. No slang and no exclamation marks in errors.
- **Buttons, toggles and menu actions use the infinitive**, verb last: *Vergrendelen*, *Verbinden*, *Back-up maken*, *Probleem melden*. **Instructions in sentences use the imperative**, the verb's stem with no -t: *Tik op ‘Toestaan’.*, *Maak opnieuw verbinding.*, *Voer je stuur-PIN in.* Don't mix the two in one string.
- Status text uses a participle or noun with no subject: *Verbonden*, *Bezig met meten…*. An action still running is **Bezig met vergrendelen…**, **Bezig met ontgrendelen…**, because *Vergrendelen…* differs from the button *Vergrendelen* by one glyph. Keep the short form only on a rung with a width budget. Connecting is the exception, in Android's wording: **Verbinding maken…**, *Opnieuw verbinding maken*, never *Bezig met verbinden*. **Debate item:** whether other running actions may also take the short form (*Opslaan…*); until then they keep *Bezig met*. Past events use the perfect or simple past, whichever is shorter and natural: *Signaal verloren*, *Werkte hier*.
- Use **tik** for the screen and **druk** for a physical button. Android's own Dutch says tik.
- Leave *alsjeblieft* out of labels. In a sentence, use it at most once, and only where a request really is polite.
- **Gender.** Role nouns stay generic and short: *eigenaar*, *medegebruiker*. No slashes, stars or -e forms. Prefer a sentence that needs no role noun.

## 2. Punctuation and quotes

- Quotes: **‘…’**, nested **“…”** (CLDR nl; Microsoft's Dutch guide also uses single marks). Never straight quotes or «…». When the English quotes a Volt button, quote the Dutch button's exact text. When it quotes a screen that stays English (the setup website, an adb prompt), keep the English inside the quotes: *‘Choose owner file’*. Android's own prompts appear in the phone's language, so use the Dutch text.
- Apostrophe: **’** (U+2019), including in plurals that need it: *accu’s*, *foto’s*, *km’s*, *PIN’s*. It looks like the closing quote, which is normal.
- **Default to *en*:** *Gegevens en geschiedenis*. *&* only on a rung with a width budget (*PIN & nabijheid*, *Data & historie*).
- A full sentence ends with a period; a label, button or title has none. Keep the English's … as the single character with no space before it: *Bezig met ontgrendelen…*
- Dash: en dash with spaces for a break ( – ), never an em dash. Ranges: en dash with no spaces (*10–20 km*). Keep the source's separators such as " · ".
- After a colon, lower case unless a name or several sentences follow: *Laatst bekend: vergrendeld*.
- No comma before *en* or *of* in a simple list.
- No soft hyphen (U+00AD), zero-width space or other invisible break character. No U+202F: Saira lacks it. Use U+00A0 where a no-break space is needed.
- All four Saira weights have ë ï é ó è ö ü É Ë Ï Ó ‘ ’ “ ” – … · ° and U+00A0 (checked with `tools/l10n/fontmetrics.py`). Keep the accents: *officiële*, *Kopiëren*, *verifiëren*, *beëindigen*, *Eén*. Volt's decorative upper-casing keeps them too.

## 3. Capitalization

- **Sentence case everywhere:** first word and names only. Button: *Back-up maken*. Label: *Laadlimiet*. Title: *Experimentele functies*. Never English Title Case, even where NAVEE's site uses it (*Kantelbaar Stuur*) or the English does (*Walk Assist* → *Loophulp*).
- A feature or button named in a sentence keeps its capital and goes in quotes where it could misread: *Tik op ‘Stop’*. Otherwise feature names are lower case mid-sentence: *zet gepland laden aan*.
- Days and months are lower case. **IJ capitalizes as one letter:** *IJsblauw*, not *Ijsblauw*.
- A word that starts with a unit or lower-case symbol keeps it: **km-stand** stays lower case at the start of a label.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS, LAB. Drive modes and their level names are not translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*). Do not write your own words in capitals.

## 4. Numbers, dates and units

- Decimal comma, thousands point: **1.234,5**. `Fmt` does this at run time, so templates have only placeholders. Keep every number the English has, as digits.
- **Percent takes no space:** `fmt_percent` is `%1$s%%` (*80%*), following CLDR nl and everyday Dutch. Technical norms allow a space, but Volt follows CLDR, so never type a space or U+00A0 before % in a template or sentence.
- **Number and unit:** a no-break space (U+00A0), never a plain space and never U+202F. `fmt_quantity` is `%1$s` + U+00A0 + `%2$s` (*25 km/h*, *3,7 V*, *20 °C*). `fmt_range` keeps its en dash (*10–20 km*); `fmt_dual` stays `%1$s / %2$s`.
- **Unit symbols stay** (km/h, mph, km, mi, V, A, W, Wh, Ah, °C, °F, min). **Dutch often writes km/u, and NAVEE's site does too; Volt keeps km/h**, which is do-not-translate. Unit words are translated: *kilometers*, *mijlen*, *minuten*.
- **Durations.** Compact: `%1$d u %2$02d min` (*1 u 05 min*), and *min* and *s* alone. The hour symbol is **u** (CLDR nl, narrow), not h. In running text: *1 uur*, *2 uur*, *5 minuten*. *Uur* and *jaar* stay singular after a number: *2 uur*, *3 jaar*; but *2 dagen*, *2 maanden*. Other compact words: *d* or *dagen*, *mnd*, *jr*.
- **Dates and times** come from the phone's format where Volt formats them. In a template: *5 nov.*, *5 nov. 2026*, *5-11-2026*, 24-hour *14:30*. Add *uur* only in a sentence with a written time: *Laden begint om 14:30 uur.* A time in a placeholder takes the label form, *Starttijd: %1$s*, never *om %1$s uur*: the phone's format can be 12-hour. Relative: *5 minuten geleden*. Months stay lower case. A chip's hour value: *14:00*.
- **Plurals:** `one` and `other`, both full phrases: *%1$d rit* / *%1$d ritten*. Keep the placeholder where the English has it. Some nouns don't change: *%1$d uur* in both.

## 5. How to shorten labels for ladder rungs

Every ladder runs full, `_short`, `_compact`, then icon. Measure in Saira (`tools/l10n/fontmetrics.py`), never in characters. Microsoft's Dutch guide says the general rule is not to abbreviate, so abbreviate only when nothing shorter is a real word. Dutch compounds are as long as German ones, so plan for that. A word is never cut mid-way, and text is never shrunk.

**Order of tools, try each in turn:**

1. **A shorter real word:** *Opties* for Instellingen, *Bereik* for Actieradius, *Licht* for Verlichting, *Info* for Informatie, *Data* for Gegevens.
2. **Drop the part the screen already says:** Accuspanning → *Spanning*, Accutemperatuur → *Temp.*, Rijmodus → *Modus*, Energieterugwinning → *Terugwinning*, Compatibiliteitscontrole → *Controle*.
3. **An approved abbreviation** from the table below. Only those; don't invent one.
4. **A space or real hyphen** where the budget allows two lines: *Accu-instellingen* wraps at the hyphen.
5. If nothing fits a rung, keep the shortest honest word, let the width check warn, and leave a note for the judge.

**Approved abbreviations.** Period at the end, including where the last letter is kept (*Max.*). No period on a whole word.

| Full | Abbreviation | Example, width in dp | Do not write |
|---|---|---|---|
| Kilometerstand | **km-stand** | km-stand 54.9 at 12.5sp | Odo., KM-stand, Km.stand, K.stand |
| Maximum-, maximaal | **Max.** | Max. snelheid 80.4 at 12.5sp | Maxim., Maximumsn. |
| Gemiddeld(e) | **Gem.** | Gem. snelheid 81.5 at 12.5sp | Gemidd., Gd. |
| Temperatuur | **Temp.** | Temp. 34.6; Accutemp. 60.5 at 12.5sp | Tmp. |
| Capaciteit | **Cap.** | Gesch. cap. 64.2 at 12.5sp | Capac. |
| Geschat(te), before cap. only | **Gesch.** | Gesch. cap. | Gesch. alone, which reads as geschiedenis |
| Instellingen, in a hyphenated compound | **-inst.** | Accu-inst. 54.5 at 12sp | Inst. alone, Instell., Instel. |
| Experimentele functies | **Exp. funct.** | 58.8 at 12sp, the 64dp shortcut only | Exp. alone, Experim., Exp. fn. |
| Installeren | **Install.** | 43.3 at 14sp, `owner_shizuku_install` only | Inst. |
| ongeveer | **ca.**, before a number | ca. 25 km | ong., +/- |
| Firmware, on `*_version_compact` rungs only | **FW** | FW meter, FW accu; the English has FW too | Firmw. |

Use common sentence abbreviations sparingly: *bijv.*, *d.w.z.*. Never abbreviate a safety word (section 6). *Vergr.*, *Ontgr.*, *Ontgrend.* and *Niet geverif.* are forbidden: they hide the state.

**Real ladders**, measured with `res/font/saira_medium.ttf` at 12.5sp unless noted:

| English | Full | `_short` | `_compact` |
|---|---|---|---|
| Odometer / Odometer / Odo. | Kilometerstand (89.0) | km-stand (54.9) | Totaal (35.4) |
| Maximum speed tile, 90dp | Maximumsnelheid (106.9) | Max. snelheid (80.4) | |
| Trip top speed / Top speed | Topsnelheid van de trip | Trip-topsnelheid (95.4), wraps at the hyphen | Topsnelheid (69.8) |
| Avg. speed | Gemiddelde snelheid | Gem. snelheid (81.5) | Gem. snelheid |
| Battery voltage / Voltage | Accuspanning (81.2) | Accuspanning | Spanning (55.2) |
| Battery temp. / Temp. | Accutemperatuur (100.3) | Accutemp. (60.5) | Temp. (34.6) |
| Est. capacity | Geschatte capaciteit (117.0) | Gesch. cap. (64.2) | Gesch. cap. (64.2) |
| Rated capacity / Capacity | Nominale accucapaciteit (140.3) | Nominale capaciteit (114.2) | Capaciteit (57.3), the rated value only: never on an estimate |
| Est. range / Range | Geschatte actieradius (124.9) | Geschat bereik (85.6) | Bereik (37.3) |
| Energy recovery | Energieterugwinning (122.0) | Terugwinning (79.4) | icon |
| Compatibility check | Compatibiliteitscontrole (137.9) | Compatibiliteit (84.4) | Controle (48.6) |
| Experimental controls, 90dp and 64dp | Experimentele functies (133.8) | Experimenteel (84.1) | Exp. funct. (58.8 at 12sp) |
| Unverified here / Unverified | Hier niet geverifieerd | Niet geverifieerd (97.1), wraps at the space | Same; report it if it doesn't fit |
| Worked here / Worked | Werkte hier (67.2) | Werkte hier | |
| Owner key | Eigenaarssleutel (96.6) | Same; fall to the icon if it doesn't fit | |

**Hard budgets in the catalog at the time of writing:**

- `onboarding_welcome_tile_*`, 62dp at 12.5sp: **Slot** 22.9 (*Vergrendelen* is 78.1 and *Vergrendel* 63.6, so neither fits), **Opties** 37.4 (*Instellingen* is 68.0), Accu 27.5, Ritten 35.5.
- `dashboard_model_qa_*`, 64dp at 12sp, one line: Licht 26.8, Opties 35.9 (*Instellingen* is 65.3), Livedata 47.6, Ritten 34.1, Rijden 35.4, Accu-inst. 54.5, Lijnlicht 43.5, Sterlicht 46.6, App-opties 60.9, Stepnaam 56.8, Mijn steps 57.3, Data 26.1, Display 41.5, Info 22.2, PIN & nabij 62.0, Klaar vóór 56.9, Exp. funct. 58.8. `dashboard_model_qa_charge_done_time` has no fitting rung: *Klaar vóór: tijd* is 80.7 at 12sp and bare *Klaar vóór* duplicates its sibling, so the width check warns and the judge decides (candidate: *Eindtijd* 42.5).
- The same keys' `_short`, 90dp at 12.5sp: Instellingen 68.0, Verlichting, Rijinstellingen 82.7, Livegegevens 79.4, Accu-inst. (*Accu-instellingen* is 99.7), Lijnverlichting 81.6, Sterverlichting 84.9, App-opties 63.5 (*App-voorkeuren* is 93.2), Data & historie 86.2, Display/geluid 83.7 (*Display & geluid* is 94.2), Info & rechten 82.3, PIN & nabijheid 89.7, Klaar vóór 59.3, Klaar vóór: tijd 84.0, Experimenteel 84.1.
- `dashboard_model_tile_charge_schedule`, 90dp: Laadschema 72.7. `dashboard_model_tile_top_speed`, 90dp: Max. snelheid.
- `owner_shizuku_install`, 48dp at 14sp: Install. 43.3. `owner_shizuku_open`, same budget: **Open** (*Openen* is 50.5). `stats_health_chip`, 40dp at 9sp: *geschat* 32.7. `stats_raw_chip`, 40dp at 10sp: *RUW* 23.7. `stats_charge_fit`, 72dp at 14sp: *Passend* 55.3. `more_lab_badge`: LAB 20.0.

**Compounds.** Dutch writes compounds as one word. Lines break only at spaces and real hyphens. Don't split a compound with a space: *eigenaars sleutel* and *laad limiet* are spelling errors.

- **Must take a hyphen** (acronym, brand, digit, unit symbol or a vowel clash such as u-i): NAVEE-app, stuur-PIN, PIN-ontgrendeling, USB-foutopsporing, CSV-bestand, GPX-bestand, Model-ID, Volt-gegevens, Bluetooth-koppeling, 4-cijferig, km-stand, Accu-instellingen, Accu-inst., Back-up, Eco-schatting, NAVEE-logging, Volt-hulpprogramma.
- **May take a hyphen, in this exact spelling:** App-instellingen, App-opties, display-eenheden, Trip-topsnelheid.
- **Stay closed:** eigenaarssleutel (double s is correct), eigenaarsbestand, eigenaarsprofiel, eigenaarsconfiguratie, laadlimiet, laadstart, laadschema, laadopname, laadwaarschuwing, rijmodus, rijinstellingen, energieterugwinning, tractiecontrole, afdaalhulp, loophulp, topsnelheid, maximumsnelheid, snelheidslimiet, startsnelheid, kilometerstand, vergrendelstatus, vergrendelduur, vergrendelbalk, accustand, accuspanning, accutemperatuur, actieradius, compatibiliteitscontrole, compatibiliteitsrapport, dagrijlicht, achterlicht, koplamp, richtingaanwijzer, stepnaam, stepinstellingen, widgetlimiet, vergrendelwidget, back-upwachtwoord, back-upbestand, ritback-up, privéback-up, snellader, snellaadsessie, snellaadreferentie, telefoonlogboek, ritopname, uitleesscript, livegegevens.
- **Don't coin a compound of more than three stems.** Split it: *snelheidslimiet per rijmodus*, not *rijmodussnelheidslimiet*; *waarschuwing bij lage accustand*, not *lageaccustandwaarschuwing*; *configuratiebestand van de eigenaar*, not *eigenaarsconfiguratiebestand*.
- Add a hyphen to a compound only if it's on these lists. Anything else goes to the judge with a note.

## 6. Safety wording

Use exactly these words. In a safety key a term that isn't used fails `check.py` or the judge. Never shorten, reorder or soften them. The only narrow exceptions are the rungs named here: *Slot* on `onboarding_welcome_tile_lock` and `onboarding_welcome_tile_lock_short`, *Exp. funct.*, *Klaar vóór*, *Lage accustand*.

| English | Dutch, exact | Rules |
|---|---|---|
| Lock (action) | **vergrendelen**; button **Vergrendelen**, **Step vergrendelen**; imperative **vergrendel** | Noun: **de vergrendeling**. **Slot** only on `onboarding_welcome_tile_lock` and `_short`. The glossary row can't limit it to those keys, so a judge rejects *slot* on any other key. Not *op slot zetten*, *afsluiten*, *blokkeren*. |
| Locked | **Vergrendeld** / vergrendeld | Not *op slot*, *gesloten*, *dicht*, *geblokkeerd*. |
| Unlock (action) | **ontgrendelen**; button **Ontgrendelen**, **Step ontgrendelen**; imperative **ontgrendel** | Noun: **de ontgrendeling**. Never *vergrendeling opheffen*, *vergrendeling uitschakelen*, *deblokkeren*, *openen*. |
| Unlocked | **Ontgrendeld** / ontgrendeld | Not *open*, *vrij*. Never *niet vergrendeld* where the state is confirmed. |
| Lock state unverified | **Vergrendelstatus niet geverifieerd** | Must never read as confirmed. Keep *niet*. |
| Lock state unknown | **Vergrendelstatus onbekend** | Same meaning; keep the English's choice of word. |
| Last known | **Laatst bekend: vergrendeld** | Keep *laatst bekend*. Never a plain *Vergrendeld*. |
| Unknown | **onbekend** | Not *geen gegevens*. "Not reported" is **Niet gemeld**. |
| Never | **nooit** | Keep every one. Not *niet* alone. |
| Always | **altijd** | Always on: **Altijd aan**. |
| On / Off | **Aan** / **Uit**; verbs **aanzetten** / **uitzetten** | Off level of Energy recovery: **Uit**. Never *Ja/Nee* in their place. |
| Battery low | **Lage accustand**; in a sentence **Accu bijna leeg** | Never *zwakke accu*: that means a worn-out battery, not a low charge. *Zwak* is only an Energy recovery level. |
| Alarm | **alarm**, het; Alarm sound: **Alarmgeluid** | The thing that sounds. Button: **Stop**. |
| Stop | Alarm button: **Stop**; a scooter: **stilzetten** (*Zet de step eerst stil*); at a stop: **bij stilstand**; a recording or setup: **stoppen**, **beëindigen** | *Beëindigen* and *stilzetten* are never the alarm button. **Judge:** on `service_action_stop` only *Stop* is acceptable; the shared glossary row accepts the other senses and can't enforce this. |
| Alert | **waarschuwing**; **Laadwaarschuwing**; Battery alerts: **Accuwaarschuwingen**; Low battery alert: **Waarschuwing bij lage accustand**, narrow **Lage accustand** | Never *alarm* for an alert, or the reverse. |
| Notify me | **Stuur me een melding** | A notification is **melding**. Not *alarm*. |
| Proximity unlock | **ontgrendelen bij nabijheid**; label **Ontgrendelen bij nabijheid**; noun **ontgrendeling bij nabijheid** | Keep *ontgrendel-* whole. Not *op nabijheid*, unless the debate prefers it. |
| PIN | **PIN**, de; **stuur-PIN**; PIN unlock: **PIN-ontgrendeling** | 4-cijferig, handlebar code. `PIN` is do-not-translate in many keys, so never *pincode*, although Android says it. Not *code*, *wachtwoord*. A backup password stays **wachtwoord**. |
| Owner key | **eigenaarssleutel** | A secret. "Not a password": **Geen wachtwoord**. Never *eigenaars-ID*, *eigenaarscode* or *sleutel* alone. |
| Owner, shared rider | **eigenaar**, **medegebruiker** | Not *mederijder* or *passagier*. Never *gedeeld* for the role: *gedeeld* reads as a shared scooter. `more_role_shared` is **Medegebruiker**. |
| Charge limit | **Laadlimiet** | Keep the number and %. Not *laadplafond*, *laadgrens*. "At limit": **Op de limiet**. |
| Charge done by | **Opgeladen vóór**; narrow keys **Klaar vóór**; time setting **Klaar vóór: tijd** | Finishing by a deadline. **The accent is required:** *voor* also means *for*. Not *Opgeladen tot*, which blurs with the limit. In a sentence, quote it: *‘Opgeladen vóór’ staat uit*. |
| Experimental | **experimenteel**, **experimentele**; **Experimentele functies** | Badge **LAB**, spoken **Experimenteel**. |
| Untested | **niet getest**; **Hier niet getest** | Not *ongecontroleerd*, which belongs to neither. |
| Unverified | **niet geverifieerd** | Keep it distinct from untested and unconfirmed. |
| Unconfirmed / Not confirmed | **onbevestigd** / **niet bevestigd** | Never a form that reads as *bevestigd*. |
| Not affiliated with NAVEE | **niet gelieerd aan NAVEE** | Footer: *Volt 1.2 · niet gelieerd aan NAVEE*. In a sentence: *Volt is niet gelieerd aan NAVEE*. Not *niet officieel*, *onafhankelijk*, *geen partner*, which soften it. Not *niet verbonden met NAVEE*, which reads as a Bluetooth state. |

**Words that look right and mean something else:**

- *Vergrendeld* and *ontgrendeld* differ only in their first three letters. Keep both whole, never shorten, and never pair them with a negation.
- *Bevestigd* also means *fastened* or *mounted*. *Bevestigen* in Volt only ever means confirming a value. *Niet bevestigd* and *onbevestigd* must keep their negation.
- *Uit* and *aan* also mean *from* and *to*. A state word goes at the end: *Alarmgeluid uit*. *Zet het alarm uit* is fine; *uit het alarm* is not a state.
- *Open* can read as unlocked. Don't use it for a scooter's state. As the button `owner_shizuku_open` it opens an app.
- *Afsluiten* means both *lock up* and *close an app*: avoid it.
- *Enkele* means *single* and also *a few*: **Eén motor**, never *Enkele motor*.
- *Dashboard* in NAVEE's own Dutch means the scooter's display. In Volt it never does (section 7).
- *Voor* for *vóór*, see Charge done by.
- *Laden* is also loading data. A charging state is **Opladen** (section 7).
- *Herstellen* also means repairing: see restore logging in Fixed words.
- *Privé*, never *private*, which suggests the private sector.

Other rules:

- Lock compounds start with *vergrendel-*: *vergrendelstatus*, *vergrendelduur*, *vergrendelbalk*, *vergrendelwidget*. The check finds a lock word only at the start of a compound, so don't write *stepvergrendeling*.
- A two-button pair stays *Vergrendelen* and *Ontgrendelen*, never *Vergrendeling aan/uit*.
- A sentence that says a command **may** have been applied keeps the doubt: *kan … hebben toegepast*. Do not turn it into a statement.
- Translate speed and legal wording literally, and add no claim about what the law allows. Don't mention RDW or 25 km/h unless the English does.
- Add no reassurance (*veilig*, *gegarandeerd*) and no warnings the English lacks. A warning that the English gives still says what happens: *energieterugwinning* remains braking with the motor.
- Report content stays English (plan section 8), and the surrounding UI says so: *Het rapport is in het Engels.*

## 7. Scooter vocabulary

The glossary is authoritative; this explains what it can't. Official NAVEE wording comes first where one exists (section 9).

**The English terminology fix (D3) has merged (#208, 2026-11-05):** the English now says Maximum speed for the setting and Top speed for the measured value, matching the `maximum-speed` and `top-speed` rows, so the earlier hold on `dashboard_model_tile_top_speed`, `controls_experimental_speed_limit`, the `readings_maximum_speed_kmh` ladder and `main_speed_*` no longer applies. Draft them like any other key. `rides_tile_max_speed` ("Top speed") is measured, so it's **Topsnelheid**.

- **Three speeds:** the setting is **Maximumsnelheid**, narrow **Max. snelheid**. A limit below it is **snelheidslimiet**. What was measured on a trip or ride is **Topsnelheid**. Never swap them, even though NAVEE's site says *topsnelheid* for the setting.

| Thing | Dutch | Note |
|---|---|---|
| scooter | **step**, de; steps; first mention **e-step** if needed | **Debate item.** In Dutch a *scooter* is first of all a moped. NAVEE's page mixes *step* and *scooter*. Keep it distinct from *stap*, a step in a procedure. |
| phone | **telefoon** | Not *mobiel*, *gsm*, *smartphone*. |
| battery | **accu**, de; plural *accu’s*; level **accustand** | NAVEE's page has both *accusysteem* and *batterij*. The phone's battery is **batterij**, as in Android's *Batterijbesparing*. **Debate item:** Flanders says *batterij* more often. |
| charger, charging | **oplader**, **opladen**, **het laden** | State and status: **Opladen**, *wordt opgeladen* (Android's word), because *laden* is also loading data. The process in a sentence: *het laden is hervat*. Compounds keep *laad-*. Never *lading* for the act. |
| display on the scooter | **display**, het; **display van de step** | NAVEE: *Ingebouwd Display*. Never *dashboard* or *teller*. |
| Dashboard | **Dashboard**, het | Only Volt's Home layout. |
| handlebar, stem, deck, kickstand | **stuur**, **stuurpen**, **treeplank**, **standaard** | NAVEE: *stuur*. The rest are Claude's picks. |
| brake lever, throttle, power button | **remhendel**, **gashendel**, **aan-uitknop** | NAVEE: *remhendel*. |
| turn signal, tail light, headlight | **richtingaanwijzer**, **achterlicht**, **koplamp** | NAVEE: all three, plus *knipperlichten*. Narrow **Knipperlicht**. Sound: **Knippergeluid**. |
| drive mode | **rijmodus**, plural **rijmodi**; compact **Modus** | NAVEE: *Rijmodi*. Names are not translated and keep the case of the English string (*Sport*, *ECO*, *Drive*), even though NAVEE's site says *voetgangersmodus*. |
| Eco, Turbo, Boost | stay in English | *Eco* and *Turbo* are do-not-translate. **Eco-schatting** is a range option, translated. |
| Energy recovery levels | **Uit, Zwak, Gemiddeld, Sterk** | NAVEE's own levels, translated: they are not drive mode names. Elsewhere low, medium, high are *laag, gemiddeld, hoog*. |
| High-speed charging | **Snelladen**; charger **snellader**; charges **snellaadsessies**; reference **snellaadreferentie** | Recordings: *laadopnamen met snellader*. Never *Sterk* or *Hoog* here. |
| range | **actieradius**, narrow **Bereik** | NAVEE: *actieradius*. |
| trip, ride | **Trip**, **rit**, plural **ritten** | Trip is a distance counter. Rides are recorded. Route is the path on the map. |
| odometer | **Kilometerstand**, **km-stand** | Means the value, not *kilometerteller*, the device. **Debate item:** Volt also shows miles, and *Kilometerstand* above a value in mi contradicts itself. Rare in the Netherlands and Flanders, so accepted for now; the key can't vary by unit, and *Totaal* is the unit-neutral rung. |
| pair, connect, bind | **koppelen**, **verbinden**, **account binden** | Android's *Koppelen*. Account binding is never *koppelen*. |
| Disconnect | **Verbinding verbreken** | Android's wording. Not *ontkoppelen*, which undoes a pairing. |
| Settings, setup | **Instellingen**, **configuratie**; verb **instellen** | Settings is a page; setup happens once. |
| Home, More | **startscherm**, **Meer** | *Naar startscherm*, *Op startscherm*, *Al op startscherm*; the tab `main_nav_home` is **Startscherm** (56.7dp at 10sp). Never *Start*, which collides with starting a recording or a capture. |
| reading, raw | **meting**, **(ruw)** | Chip RAW → RUW. A value's history: **Meetgeschiedenis**. |
| voltage, current, power, health, cycles | **spanning**, **stroom**, **vermogen**, **conditie**, **laadcycli** | |
| Quick access, Quick settings | **Snelle toegang** | Never *Snelle instellingen*: that's Android's pull-down panel. |
| Walk Assist, Power Assist | **Loophulp**, **Motorondersteuning** | Translate from the label. **Debate item:** *loophulp* first means a walking aid such as a rollator, and its e-bike use is from memory. Candidate: *Loopondersteuning* (112.9dp at 12.5sp, against 54.0), which pairs with Motorondersteuning. |
| Hill descent control, Traction control | **Afdaalhulp**, **Tractiecontrole** | |
| Overspeed active control | **Actieve snelheidsrem** | |
| Low Battery Mode, Long range mode | **Spaarstand**, **Langeafstandsmodus** | |
| Backup, restore | **back-up**, **herstellen** | Verb: *back-up maken*. The `restore` glossary row also accepts *terugzetten* and *zoals die was*, for undoing a try and for NAVEE logging; a judge rejects them on a backup. |
| permissions | **rechten**; one permission **toestemming** | *Rechten* as a heading only (Android's word); singular *recht* sounds legal. Not *machtigingen*. |

Android's settings keep Android's Dutch: **USB-foutopsporing**, **Ontwikkelaarsopties**, **Buildnummer**, and the screen **Over de telefoon**, which phone makers rename, so write generally where you can: *open in de instellingen de info over je telefoon*.

**Words shared only by accident (D3). Do not unify them:**

- *limit*: laadlimiet (charge), snelheidslimiet (speed), opnamelimiet or maximum (storage, time), widgetlimiet.
- *key*: eigenaarssleutel; a data key in JSON or preferences is **veld**; RSA-vingerafdruk.
- *identity*: identifying a model is **herkenning**, not the owner key.
- *shared*: "shared evidence" is **gedeeld**, not medegebruiker.
- *setup*: general is **configuratie**; owner setup is **eigenaarsconfiguratie**. The file: **configuratiebestand van de eigenaar**, on onboarding steps **eigenaarsbestand**. `navee-owner-setup.json` stays as written. Website buttons stay English until the site changes.
- *History*: Meetgeschiedenis, Ritgeschiedenis, Foutgeschiedenis. Narrow *Historie*.
- *Recovery*: energieterugwinning vs **herstel** (restore). Undoing a try is **terugzetten**.
- *Test*: **proberen**, result **poging** (a try); **testen** (guided); **Gepland laden testen**; **compatibiliteitscontrole**. Never *testen* for Try. An ordinary retry always has *opnieuw* (see Fixed words).
- *Readback*: **teruglezen**, neutral, because a readback can also fail: *Teruglezing wijkt af*. Nothing with *bevestiging* inside it.
- *Rejected* and *Refused by scooter*: both **geweigerd**. Rides an estimate left out are **afgekeurd** (`dashboard_rejected`): nothing refused them.
- *Standing start*: **Start vanuit stilstand** is value 0 of **Startsnelheid**, not another name for it.
- *Alert* and *warning*: an alert the rider chose is **waarschuwing** (*Laadwaarschuwing*, *Accuwaarschuwingen*). A fault the scooter reports is **waarschuwing van de step**, **waarschuwingscode**. **Debate item:** *Accuwaarschuwingen* can read as a reported battery fault; the alternative is *Laadmelding*.
- *Help verify*: **Help mee verifiëren**, a card title that invites, so not the infinitive of section 1. Not *Help verifiëren*, a calque.
- "experimental motion" in Rides: **experimentele bewegingsgegevens**.

## 8. Grammar traps

- Articles: *de step*, *de accu*, *de app*, *de PIN*, *de vergrendeling*; *het display*, *het dashboard*, *het alarm*, *het slot*. Adjectives take -e after *de*, *het* and in plurals, none after *een* with a *het*-word: *de geplande start*, *een gepland alarm*.
- Glossary forms don't inflect in the check: only listed forms match. Add a form to the row, with a note, rather than bending the sentence.
- Placeholders may carry a noun whose article you can't see. Prefer wording with no article before `%1$s`: *Step: %1$s*.
- Separable verbs split in sentences: *Zet het alarm uit.* *Lees de sleutel uit.* The particle stays at the end of the clause.
- Keep negation next to what it negates: *niet geverifieerd*, *niet bevestigd*.
- *Geen* with a noun, *niet* otherwise: *Geen meting*, *Niet gemeld*.
- English "Excludes…" (`stats_diagnostics_privacy`, `stats_diagnostics_export_description`) is not *Zonder…*: that fails the polarity check, because the English has no negation word. Write *Uitgezonderd: eigenaarssleutel en PIN*.
- *Ont-* is not a negation, and the check knows it. *On-* is: *onbekend*, *onbevestigd*.
- Possessive: *display van de step*, not *step’s display*.
- **Errors:** *[X] kon niet worden [participle].* or *[X] is mislukt.* Not a subjectless *Kon … niet* or *Kan … niet*, and not a negated subject (*Geen browser kon …*): *Er is geen browser gevonden om …*
- A bare *deze* needs its noun: *om deze functies te proberen*.
- A question about what just happened uses the perfect: *Heb je het zien veranderen?*
- A setting label in a sentence goes in quotes with no agreement: *Staat het achterlicht op ‘%1$s’?*
- A value uses the same noun as its label (*Gemelde laadcycli* → *12 laadcycli*).
- Two-noun rows are telegraphic with no article (*Afstand trip*, *Status accountbinding*, *Instelling X (ruw)*); write one closed compound only where this guide lists it.

## Rules every language follows

These came from the independent review of the first full draft (Spanish, 2026-11-05). They override anything above that disagrees. The table at the end holds this language's fixed words; a term in it is used every time the concept appears, in every area.

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
12. **Retired English terms.** D3 retired owner identity, setup key, scooter key and speed cap. Where one still appears in the English (`Owner identity rejected`, `Scooters & setup keys`), don't render it literally: the first three are all the owner key.
13. **"Dashboard" in the English.** The English fix has merged, so a visible "Dashboard" now means Volt's Home layout. Volt's Home is one thing and the scooter's display another: if a string or its note means the physical screen, use the glossary's scooter-display word.

### Fixed words

| English concept | Meaning | This language |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Opnieuw proberen** (button); **Probeer het opnieuw.** and **Probeer het zo meteen opnieuw.** in a sentence. Always with *opnieuw*, the word that makes it a retry. Bare *proberen* and *poging* stay the experimental try. Not *testen*, *nog een poging*. |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **Niet gemeld**. Says only that no value came, never that the value is zero or that nothing is wrong. Not *onbekend*, *geen gegevens*, *niet gerapporteerd*. |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **gemeld**, in full **door de step gemeld**. A claim, not a measurement. Not *gerapporteerd* (a *rapport* is the document), *gemeten* or *bevestigd*. |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **NAVEE-logging**, then *logging* where the screen makes it clear. Debug logging on the phone. Never *registratie* or *aanmelden*, and not *logboek*, which is the rider's *Telefoonlogboek*. |
| restore logging | turning that logging back to how it was (usually off) | One form per use. Button: **NAVEE-logging herstellen**; running: **Bezig met herstellen van NAVEE-logging…**; done: **NAVEE-logging teruggezet**; waiting: *tot de NAVEE-logging is teruggezet*; imperative: **Zet de NAVEE-logging terug zoals die was.** A bare *Herstel de logging* or *NAVEE-logging hersteld* reads as repairing it or turning it on. Where the English says turn off, write **uitzetten**. No backup is involved. **Debate item:** *terugzetten* for the button and running state too. |
| Volt's helper | the small helper Volt starts inside Shizuku | **Volt-hulpprogramma**, short **hulpprogramma**. Not the computer's **uitleesscript**, and not *helper* or *hulp*, which belong to Loophulp and Afdaalhulp. |
| stock charger | the charger that came with the scooter | **originele oplader**. The one that came with the scooter; not a claim that another charger is fake. Not *standaardoplader*: **standaard** is also the kickstand. |
| high-speed charger | a faster charger the rider may own | **snellader**. The rider's own faster charger. Recordings: *laadopnamen met snellader*. Never *Sterk* or *Hoog*. |
| gap (in a recording) | a stretch of a recording with no data | **onderbreking**, plural **onderbrekingen**; Recording gaps: **Onderbrekingen in de opname**. A stretch with no data, not a pause the rider chose. |
| elevation | height above sea level on a route | **hoogte**: *Hoogte niet gemeten*. Not *stijging* (slope) or *hoogteverschil* (height climbed). |
| current direction | which way battery current flows | **stroomrichting**. Which way battery current flows, not the direction of riding. |
| throughput | how much energy has passed through the battery | **doorvoer**; Lifetime throughput: **Totale doorvoer**. Not *vermogen* or *capaciteit*. |
| battery gauge | the scooter's own battery percentage reading | **accumeter**. The scooter's own percentage reading, which can jump. Not *accustand*, the level itself. The Home widget called Gauge is a different thing. |
| raw | an unprocessed value, also used as a short tag | **ruw**; in brackets **(ruw)**; chip **RUW**. |
| last seen | when Volt last had data from the scooter | **laatst ontvangen**; with a value **Laatst ontvangen: %1$s**. It dates the data and does not say the scooter is connected. Not *laatst gezien*, a calque, and not *Laatst bekend*, which goes with a state (*Laatst bekend: vergrendeld*). |
| add | adding a widget, scooter or item | **toevoegen**; button **Toevoegen**, **Widget toevoegen**; *Voeg een step toe.* |
| enter (typing) | typing a value | **invoeren**: *Voer je stuur-PIN in.* Not *intypen* or *ingeven*. |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, de. Never *applicatie* or *toepassing*. |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | **verbinding maken met**: *Maak verbinding met de step.*, *Maak eerst verbinding met de step*, *Volt maakt opnieuw verbinding.*, running *Verbinding maken…* (Android's wording). Button: **Verbinden**; state: **Verbonden**. Dutch *verbinden* wants an object, so no intransitive *Verbind met de step*, and not *Verbind de step*, which reads as plugging something in. Not *koppelen*, which is pairing. |
| set a value | one verb for setting a value on the scooter | **instellen**: *Snelheidslimiet instellen*, *Stel de snelheidslimiet in.* Not *zetten* or *vastleggen*. *Aanzetten*, *uitzetten* and *terugzetten* keep their own meanings. |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | **Dit + verb in -t**: *Dit verwijdert de geschiedenis van alle steps. Ritten blijven bewaard.* *Dit vervangt alles op deze telefoon.* Never the imperative *Verwijder…* or the button's infinitive *Verwijderen…*, and not a bare *Verwijdert…*, which reads as a slip for the imperative. |
| disconnected | a link that dropped, against one that isn't up | **Verbinding verbroken**, *Verbinding met de step verbroken*: the link dropped. **Niet verbonden**: no link now, and also for a *disconnected* value that only means not connected (`more_link_disconnected`, `ui_live_stats_disconnected`). |
| the alert's watch | Volt watching a charge for an alert | **bewaken**: *deze laadsessie blijven bewaken*, button **Stoppen met bewaken**. Not *volgen*. |
| one charge, charge count | one charging session; the count the scooter reports | **laadsessie**, plural **laadsessies** (*snellaadsessie*). Not *laadbeurt*. The reported count is **laadcyclus**, **laadcycli**, in label and value alike. |
| samples | single recorded points | **meetpunt**, **meetpunten**. *Meting* is a reading. |
| unfinished | a try or test not completed | **onafgerond**. Not *onvoltooid*. |
| prompt | Android's dialog on the phone | **venster**. *Verzoek* only for Android's Bluetooth pairing request. |
| authenticated owner | connected over the scooter link as owner | **als eigenaar verbonden**. Not *aangemeld*: the rider never logs in to Volt. |
| shared-user identity | the key of a shared rider | **Sleutel van medegebruiker**, Claude's coinage. **Debate item.** Keep it apart from *eigenaarssleutel*. |
| unplugged charger | the charger is no longer plugged in | **niet aangesloten**. Never a *koppel-* word: those are Bluetooth pairing. |
| proximity key (reading) | the setting's reading under another English name | **Ontgrendelen bij nabijheid**. No *nabijheidssleutel*: *sleutel* is the owner key. |
| undoing a try | restore or restoration on try and test cards | **terugzetten**, *teruggezet*, including *Terugzetten bevestigd*. *Herstel* is for backups. |
| line and star pattern | a light's pattern, not the light | **Lijnpatroon**, **Sterpatroon**, against **Lijnverlichting**, **Sterverlichting**. |
| other reading names | one name under `readings_*`, `stats_row_*`, `compat_field_*` | **Hellingshulp**, **Spraaktaal**, **Lichtsensor**, **Snelheidswaarschuwing**, **Actieradiusalgoritme**, **Modus actieradiusalgoritme** (narrow *Modus actieradius*), **E9-vlag**, **Status accountbinding**, lower-case **trip** mid-label. |
| value outside the official range | "OEM feature domain" | **Waarde valt buiten wat de officiële app (OEM) toestaat**; `OEM` is do-not-translate here. No *OEM-bereik*: *bereik* is range. |
| screen and section titles | noun form or verb form, with one example | **Noun form**: *Compatibiliteitscontrole*, *Back-up en herstel*. If the title is an action with no good noun, the infinitive: *Gepland laden testen*. Never the imperative. Buttons keep the infinitive of section 1. |

## 9. Sources consulted

**Official Dutch wording: partly found, for product and feature words only.** Nothing public shows the NAVEE app's Dutch screens, so its words for charge limit, PIN, owner key and experimental features are unknown, and those are Claude's own proposals. No APK was downloaded.

- **NAVEE Belgium store, GT3 page, Dutch:** [be.naveetech.com/products/gt3](https://be.naveetech.com/products/gt3), read through a summarizing fetch, so quotes may not be exact. It uses je. Printed: *step*, *scooter*, *voertuig*, *vergrendelfunctie*, *vergrendelknop*, *motorvergrendelingsstand*, *vergrendelde toestand*, *NAVEE APP*, *accusysteem*, *batterij*, *actieradius*, *topsnelheid*, *Max. Snelheid*, *maximumsnelheid*, *energieterugwinning(sfunctie)*, *energieherstelinstellingen*, levels *zwak, gemiddeld, sterk*, *Tractiecontrolesysteem (TCS)*, *Ingebouwde Richtingaanwijzers*, *geïntegreerde knipperlichten*, *Automatische Koplamp*, *Achterlicht*, *Rijmodi*, *Standaardmodus*, *voetgangersmodus*, *Ingebouwd Display*, *dashboard* for the display, *stuur*, *remhendel*, *km/u*.
- **Not reachable:** [the GT3 Pro Dutch manual](https://service.naveetech.com/wp-content/uploads/2025/01/UM-NAVEE-GT3-Pro-NL.pdf) returned 404; a search snippet only showed it exists and uses u. A later reader should try again. [be.naveetech.com/collections/all](https://be.naveetech.com/collections/all) gave 429, and [handleidingen.com's V40 Pro page](https://www.handleidingen.com/navee/handleiding-v40-pro) and [MediaMarkt NL](https://www.mediamarkt.nl/nl/product/_navee-k100-elektrische-step-blauw-1890047.html) gave 403. [TechRadar nl-be's S65C review](https://global.techradar.com/nl-be/vehicle-tech/e-scooters/navee-s65c-review) returned no article text.
- **Android, read from AOSP:** [SettingsLib nl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-nl/strings.xml) and [Settings nl](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-nl/strings.xml): *USB-foutopsporing*, *Ontwikkelaarsopties*, *Koppelen*, *Verbinding verbreken*, *Instellingen*, *Buildnummer*, *Rechten*, *Batterijbesparing*, *pincode*. *Over de telefoon* wasn't found by that search, so it's from memory.
- **Formats:** [CLDR nl](https://github.com/unicode-org/cldr-json) read directly: percent `#,##0%`, decimal comma, thousands point, quotes ‘…’, narrow hour *u*. [Taaladvies on spaces around symbols](https://taaladvies.net/wel-of-geen-spaties-voor-en-na-leestekens-en-symbolen-algemeen/) and [Onze Taal](https://onzetaal.nl/taalloket/gradenteken-en-andere-tekens-spatie-of-niet), search snippets only: technical norms want a space before %, while writing it closed is accepted in general text. [Degrees take a space](https://taaladvies.net/gradenteken-in-temperatuuraanduidingen-spatie-ervoor-of-niet/) is from the title and memory.
- **Platform style:** [Microsoft Dutch Localization Style Guide](https://download.microsoft.com/download/e/8/f/e8f04dd4-591e-46e9-a076-e49a8c8c75e4/nld-nld-StyleGuide.pdf), read directly: sentence case, lower case after a colon, avoid abbreviations, single quotes, vowel-clash hyphens, hyphens after acronyms, infinitive in UI. Its advice against en dashes isn't followed for ranges, because Volt's `fmt_range` has one. It points to the Woordenlijst, which wasn't consulted entry by entry.
- **Usage, weaker evidence:** [Androidworld.nl's S65C review](https://androidworld.nl/reviews/navee-s65c-een-elektrische-step-om-buitenlandse-steden-te-verkennen/): *step*, *ontgrendelen*, *vergrendelen*, *actieradius*, *energieterugwinning*, *stuur*, je. [Decathlon NL listings](https://www.decathlon.nl/p/mp/joyor-y8-s-abe-elektrische-step-48v-26ah-accu-en-richtingaanwijzers/6f071a45-bca2-477c-86ab-8ef63051d66f/c1), search snippet: *accu*, *richtingaanwijzers*, *elektrische step*. [Circuits Online](https://www.circuitsonline.net/forum/view/157647/1), a forum, search snippet: *gashendel*, *cruise control*.
- **From the proposer's knowledge, not looked up:** *cruisecontrol*, *dagrijlicht*, *kilometerstand*, *km-stand*, *loophulp*, *sfeerlicht*, *treeplank*, *stuurpen*, *aan-uitknop*, *gelieerd aan*, and the abbreviation table.
- **No source, Claude's choice; debate these first:** Loophulp against Loopondersteuning, Kilometerstand on the miles path, waarschuwing against melding for alerts, eigenaarssleutel, eigenaarsbestand, medegebruiker, stuur-PIN, laadlimiet, snelheidslimiet, Topsnelheid for the measured value, Actieve snelheidsrem, Afdaalhulp, Motorondersteuning, Spaarstand, Opgeladen vóór, Klaar vóór, Lijnverlichting, Sterverlichting, Livegegevens, Slot on the 62dp tile, the rungs of section 5, the "not affiliated" wording and the new picks in Fixed words (NAVEE-logging, Volt-hulpprogramma, originele oplader, onderbreking, doorvoer, accumeter, laatst ontvangen, Dit + verb).
- **Native review:** none yet.

**Choices where the sources disagree:**

- **step over scooter.** NAVEE's page mixes them and the tool tests use *scooter* as sample data, but *scooter* first means a moped in Dutch, and *step* is the shop and press word.
- **accu over batterij.** NAVEE's page has both. *Accu* is shorter (27.5 vs 43.1dp) and keeps the phone's *batterij* separate.
- **Maximumsnelheid and Topsnelheid.** NAVEE's page uses both for the setting. D3 gives Topsnelheid the measured value.
- **vergrendelen over op slot.** NAVEE's page, Android and press agree. *Slot* survives only on the one narrow tile.
- **PIN over pincode.** Android says *pincode*, but `PIN` is do-not-translate in Volt's keys. Debate whether to lift that for nl.
- **je over u.** Owner's decision. NAVEE's page uses je; its manual snippet shows u.
