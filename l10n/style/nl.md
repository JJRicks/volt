# Dutch style guide (nl)

**Status: Claude-only proposal, 2026-11-05.** One model drafted this. The three-vendor debate has not seen it, and no native speaker has checked it. Everything below, safety wording included, can change. "Approved" here means "the wording this proposal asks every drafter to use". One `values-nl` serves the Netherlands and Flanders, so avoid words only one of them uses.

**Wait for D3.** Do not draft Dutch for a key whose English D3 rewords until D3's English has landed (plan 7.2, step 0). Section 7 names the keys that matter most.

## 1. Register and form of address

- Address the rider as **je** (owner's decision, 2026-11-02): je, jij only for stress, jou, jouw only for stress. Possessive is normally **je**: *je step*. Never u, uw or a mix. NAVEE's own Dutch product page also uses je.
- Tone: plain and calm, like a good app. Not a legal text and not marketing. No slang and no exclamation marks in errors.
- **Buttons, toggles and menu actions use the infinitive**, verb last: *Vergrendelen*, *Verbinden*, *Back-up maken*, *Probleem melden*. **Instructions in sentences use the imperative**, the verb's stem with no -t: *Tik op ‘Toestaan’.*, *Verbind opnieuw.*, *Voer je stuur-PIN in.* Don't mix the two in one string.
- Status text uses a participle or noun with no subject: *Verbonden*, *Vergrendelen…*, *Bezig met meten…*. Past events use the perfect or simple past, whichever is shorter and natural: *Signaal verloren*, *Werkte hier*.
- Use **tik** for the screen and **druk** for a physical button. Android's own Dutch says tik.
- Leave *alsjeblieft* out of labels. In a sentence, use it at most once, and only where a request really is polite.
- **Gender.** Role nouns stay generic and short: *eigenaar*, *medegebruiker*. No slashes, stars or -e forms. Prefer a sentence that needs no role noun.

## 2. Punctuation and quotes

- Quotes: **‘…’**, nested **“…”** (CLDR nl; Microsoft's Dutch guide also uses single marks). Never straight quotes or «…». When the English quotes a Volt button, quote the Dutch button's exact text. When it quotes a screen that stays English (the setup website, an adb prompt), keep the English inside the quotes: *‘Choose owner file’*. Android's own prompts appear in the phone's language, so use the Dutch text.
- Apostrophe: **’** (U+2019), including in plurals that need it: *accu’s*, *foto’s*, *km’s*, *PIN’s*. It looks like the closing quote, which is normal.
- A full sentence ends with a period; a label, button or title has none. Keep the English's … as the single character with no space before it: *Ontgrendelen…*
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
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS, LAB, and the drive modes WALK, ECO, DRIVE, SPORT. Do not write your own words in capitals.

## 4. Numbers, dates and units

- Decimal comma, thousands point: **1.234,5**. `Fmt` does this at run time, so templates have only placeholders. Keep every number the English has, as digits.
- **Percent takes no space:** `fmt_percent` is `%1$s%%` (*80%*), following CLDR nl and everyday Dutch. Technical norms allow a space, but Volt follows CLDR, so never type a space or U+00A0 before % in a template or sentence.
- **Number and unit:** a no-break space (U+00A0), never a plain space and never U+202F. `fmt_quantity` is `%1$s` + U+00A0 + `%2$s` (*25 km/h*, *3,7 V*, *20 °C*). `fmt_range` keeps its en dash (*10–20 km*); `fmt_dual` stays `%1$s / %2$s`.
- **Unit symbols stay** (km/h, mph, km, mi, V, A, W, Wh, Ah, °C, °F, min). **Dutch often writes km/u, and NAVEE's site does too; Volt keeps km/h**, which is do-not-translate. Unit words are translated: *kilometers*, *mijlen*, *minuten*.
- **Durations.** Compact: `%1$d u %2$02d min` (*1 u 05 min*), and *min* and *s* alone. The hour symbol is **u** (CLDR nl, narrow), not h. In running text: *1 uur*, *2 uur*, *5 minuten*. *Uur* and *jaar* stay singular after a number: *2 uur*, *3 jaar*; but *2 dagen*, *2 maanden*. Other compact words: *d* or *dagen*, *mnd*, *jr*.
- **Dates and times** come from the phone's format where Volt formats them. In a template: *5 nov.*, *5 nov. 2026*, *5-11-2026*, 24-hour *14:30*. Add *uur* only in a sentence: *Laden begint om 14:30 uur.* Relative: *5 minuten geleden*. Months stay lower case. A chip's hour value: *14:00*.
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
| Instellingen, in a hyphenated compound | **-inst.** | Accu-inst. 54.5, App-inst. 50.4 at 12sp | Inst. alone, Instell., Instel. |
| Experimentele functies | **Exp. funct.** | 58.8 at 12sp, the 64dp shortcut only | Exp. alone, Experim., Exp. fn. |
| Installeren | **Install.** | 43.3 at 14sp, `owner_shizuku_install` only | Inst. |
| ongeveer | **ca.**, before a number | ca. 25 km | ong., +/- |

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
| Est. capacity | Geschatte capaciteit (117.0) | Gesch. cap. (64.2) | Capaciteit (57.3), only where the screen says estimate |
| Est. range / Range | Geschatte actieradius (124.9) | Geschat bereik (85.6) | Bereik (37.3) |
| Energy recovery | Energieterugwinning (122.0) | Terugwinning (79.4) | icon |
| Compatibility check | Compatibiliteitscontrole (137.9) | Compatibiliteit (84.4) | Controle (48.6) |
| Experimental controls, 90dp and 64dp | Experimentele functies (133.8) | Experimenteel (84.1) | Exp. funct. (58.8 at 12sp) |
| Unverified here / Unverified | Hier niet geverifieerd | Niet geverifieerd (97.1), wraps at the space | Same; report it if it doesn't fit |
| Worked here / Worked | Werkte hier (67.2) | Werkte hier | |
| Owner key | Eigenaarssleutel (96.6) | Same; fall to the icon if it doesn't fit | |

**Hard budgets in the catalog at the time of writing:**

- `onboarding_welcome_tile_*`, 62dp at 12.5sp: **Slot** 22.9 (*Vergrendelen* is 78.1 and *Vergrendel* 63.6, so neither fits), **Opties** 37.4 (*Instellingen* is 68.0), Accu 27.5, Ritten 35.5.
- `dashboard_model_qa_*`, 64dp at 12sp, one line: Licht 26.8, Opties 35.9 (*Instellingen* is 65.3), Livedata 47.6, Ritten 34.1, Rijden 35.4, Accu-inst. 54.5, Lijnlicht 43.5, Sterlicht 46.6, App-opties 60.9, Stepnaam 56.8, Mijn steps 57.3, Data 26.1, Display 41.5, Info 22.2, PIN & nabij 62.0, Klaar vóór 56.9, Exp. funct. 58.8.
- The same keys' `_short`, 90dp at 12.5sp: Instellingen 68.0, Verlichting, Rijinstellingen 82.7, Livegegevens 79.4, Accu-inst. (*Accu-instellingen* is 99.7), Lijnverlichting 81.6, Sterverlichting 84.9, App-inst. (*App-voorkeuren* is 93.2), Data & historie 86.2, Display/geluid 83.7 (*Display & geluid* is 94.2), Info & rechten 82.3, PIN & nabijheid 89.7, Klaar vóór 59.3, Klaar vóór: tijd 84.0, Experimenteel 84.1.
- `dashboard_model_tile_charge_schedule`, 90dp: Laadschema 72.7. `dashboard_model_tile_top_speed`, 90dp: Max. snelheid, after D3.
- `owner_shizuku_install`, 48dp at 14sp: Install. 43.3. `owner_shizuku_open`, same budget: **Open** (*Openen* is 50.5). `stats_health_chip`, 40dp at 9sp: *geschat* 32.7. `stats_raw_chip`, 40dp at 10sp: *RUW* 23.7. `stats_charge_fit`, 72dp at 14sp: *Passend* 55.3. `more_lab_badge`: LAB 20.0.

**Compounds.** Dutch writes compounds as one word. Lines break only at spaces and real hyphens. Don't split a compound with a space: *eigenaars sleutel* and *laad limiet* are spelling errors.

- **Must take a hyphen** (acronym, brand, digit, unit symbol or a vowel clash such as u-i): NAVEE-app, stuur-PIN, PIN-ontgrendeling, USB-foutopsporing, CSV-bestand, GPX-bestand, Model-ID, Volt-gegevens, Bluetooth-koppeling, 4-cijferig, km-stand, Accu-instellingen, Accu-inst., Back-up, Eco-schatting.
- **May take a hyphen, in this exact spelling:** App-instellingen, App-opties, App-inst., display-eenheden, Trip-topsnelheid.
- **Stay closed:** eigenaarssleutel (double s is correct), eigenaarsbestand, eigenaarsprofiel, eigenaarsconfiguratie, laadlimiet, laadstart, laadschema, laadopname, laadwaarschuwing, rijmodus, rijinstellingen, energieterugwinning, tractiecontrole, afdaalhulp, loophulp, topsnelheid, maximumsnelheid, snelheidslimiet, startsnelheid, kilometerstand, vergrendelstatus, vergrendelduur, vergrendelbalk, accustand, accuspanning, accutemperatuur, actieradius, compatibiliteitscontrole, compatibiliteitsrapport, dagrijlicht, achterlicht, koplamp, richtingaanwijzer, stepnaam, telefoonlogboek, ritopname, uitleesscript, livegegevens.
- **Don't coin a compound of more than three stems.** Split it: *snelheidslimiet per rijmodus*, not *rijmodussnelheidslimiet*; *waarschuwing bij lage accustand*, not *lageaccustandwaarschuwing*; *configuratiebestand van de eigenaar*, not *eigenaarsconfiguratiebestand*.
- Add a hyphen to a compound only if it's on these lists. Anything else goes to the judge with a note.

## 6. Safety wording

Use exactly these words. In a safety key a term that isn't used fails `check.py` or the judge. Never shorten, reorder or soften them. The only narrow exceptions are the rungs named here: *Slot* on `onboarding_welcome_tile_lock`, *Exp. funct.*, *Klaar vóór*, *Lage accustand*.

| English | Dutch, exact | Rules |
|---|---|---|
| Lock (action) | **vergrendelen**; button **Vergrendelen**, **Step vergrendelen**; imperative **vergrendel** | Noun: **de vergrendeling**. Tile only: **Slot**. Not *op slot zetten*, *afsluiten*, *blokkeren*. |
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
| Alarm | **alarm**, het; Alarm sound: **Alarmgeluid** | The thing that sounds. Button: **Stop**. |
| Stop | Alarm button: **Stop**; a scooter: **stilzetten** (*Zet de step eerst stil*); a recording or setup: **stoppen**, **beëindigen** | *Beëindigen* and *stilzetten* are never the alarm button. |
| Alert | **waarschuwing**; **Laadwaarschuwing**; Battery alerts: **Accuwaarschuwingen**; Low battery alert: **Waarschuwing bij lage accustand**, narrow **Lage accustand** | Never *alarm* for an alert, or the reverse. |
| Notify me | **Stuur me een melding** | A notification is **melding**. Not *alarm*. |
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

Other rules:

- Lock compounds start with *vergrendel-*: *vergrendelstatus*, *vergrendelduur*, *vergrendelbalk*. The check finds a lock word only at the start of a compound, so don't write *stepvergrendeling*.
- A two-button pair stays *Vergrendelen* and *Ontgrendelen*, never *Vergrendeling aan/uit*.
- A sentence that says a command **may** have been applied keeps the doubt: *kan … hebben toegepast*. Do not turn it into a statement.
- Translate speed and legal wording literally, and add no claim about what the law allows. Don't mention RDW or 25 km/h unless the English does.
- Add no reassurance (*veilig*, *gegarandeerd*) and no warnings the English lacks.
- Report content stays English (plan section 8), and the surrounding UI says so: *Het rapport is in het Engels.*

## 7. Scooter vocabulary

The glossary is authoritative; this explains what it can't. Official NAVEE wording comes first where one exists (section 9).

**Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning in D3, and the `top-speed` and `maximum-speed` rows already carry D3's words. So these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until D3 lands. Flag them in your notes; a judge answers fallback. Keys that already agree with D3 can go ahead: `rides_tile_max_speed` ("Max speed") is measured, so it's **Topsnelheid**.

- **Three speeds:** the setting is **Maximumsnelheid**, narrow **Max. snelheid**. A limit below it is **snelheidslimiet**. What was measured on a trip or ride is **Topsnelheid**. Never swap them, even though NAVEE's site says *topsnelheid* for the setting.

| Thing | Dutch | Note |
|---|---|---|
| scooter | **step**, de; steps; first mention **e-step** if needed | **Debate item.** In Dutch a *scooter* is first of all a moped. NAVEE's page mixes *step* and *scooter*. Keep it distinct from *stap*, a step in a procedure. |
| phone | **telefoon** | Not *mobiel*, *gsm*, *smartphone*. |
| battery | **accu**, de; plural *accu’s*; level **accustand** | NAVEE's page has both *accusysteem* and *batterij*. The phone's battery is **batterij**, as in Android's *Batterijbesparing*. **Debate item:** Flanders says *batterij* more often. |
| charger, charging | **oplader**, **laden**, **opladen** | Verb in labels: *laden*. Never *lading* for the act. |
| display on the scooter | **display**, het; **display van de step** | NAVEE: *Ingebouwd Display*. Never *dashboard* or *teller*. |
| Dashboard | **Dashboard**, het | Only Volt's Home layout. |
| handlebar, stem, deck, kickstand | **stuur**, **stuurpen**, **treeplank**, **standaard** | NAVEE: *stuur*. The rest are Claude's picks. |
| brake lever, throttle, power button | **remhendel**, **gashendel**, **aan-uitknop** | NAVEE: *remhendel*. |
| turn signal, tail light, headlight | **richtingaanwijzer**, **achterlicht**, **koplamp** | NAVEE: all three, plus *knipperlichten*. Narrow **Knipperlicht**. Sound: **Knippergeluid**. |
| drive mode | **rijmodus**, plural **rijmodi**; compact **Modus** | NAVEE: *Rijmodi*. Names stay WALK, ECO, DRIVE, SPORT, even though NAVEE's site says *voetgangersmodus*. |
| Eco, Turbo, Boost | stay in English | *Eco* and *Turbo* are do-not-translate after D3. **Eco-schatting** is a range option, translated. |
| Energy recovery levels | **Uit, Zwak, Gemiddeld, Sterk** | NAVEE's own levels. Elsewhere low, medium, high are *laag, gemiddeld, hoog*. |
| range | **actieradius**, narrow **Bereik** | NAVEE: *actieradius*. |
| trip, ride | **Trip**, **rit**, plural **ritten** | Trip is a distance counter. Rides are recorded. Route is the path on the map. |
| odometer | **Kilometerstand**, **km-stand** | Means the value, not *kilometerteller*, the device. |
| pair, connect, bind | **koppelen**, **verbinden**, **account binden** | Android's *Koppelen*. Account binding is never *koppelen*. |
| Disconnect | **Verbinding verbreken** | Android's wording. Not *ontkoppelen*, which undoes a pairing. |
| Settings, setup | **Instellingen**, **configuratie**; verb **instellen** | Settings is a page; setup happens once. |
| Home, More | **Start**, **Meer** | In a sentence, Home is **startscherm**. |
| reading, raw | **meting**, **(ruw)** | Chip RAW → RUW. A value's history: **Meetgeschiedenis**. |
| voltage, current, power, health, cycles | **spanning**, **stroom**, **vermogen**, **conditie**, **laadcycli** | |
| Quick access, Quick settings | **Snelle toegang** | Never *Snelle instellingen*: that's Android's pull-down panel. |
| Walk Assist, Power Assist | **Loophulp**, **Motorondersteuning** | Translate from the label. Loophulp is the e-bike word. |
| Hill descent control, Traction control | **Afdaalhulp**, **Tractiecontrole** | |
| Overspeed active control | **Actieve snelheidsrem** | |
| Low Battery Mode, Long range mode | **Spaarstand**, **Langeafstandsmodus** | |
| Backup, restore | **back-up**, **herstellen** | Verb: *back-up maken*. |
| permissions | **rechten** | Android's word. Not *machtigingen*. |

Android's settings keep Android's Dutch: **USB-foutopsporing**, **Ontwikkelaarsopties**, **Buildnummer**, and the screen **Over de telefoon**, which phone makers rename, so write generally where you can: *open in de instellingen de info over je telefoon*.

**Words shared only by accident (D3). Do not unify them:**

- *limit*: laadlimiet (charge), snelheidslimiet (speed), opnamelimiet or maximum (storage, time), widgetlimiet.
- *key*: eigenaarssleutel; a data key in JSON or preferences is **veld**; RSA-vingerafdruk.
- *identity*: identifying a model is **herkenning**, not the owner key.
- *shared*: "shared evidence" is **gedeeld**, not medegebruiker.
- *setup*: general is **configuratie**; owner setup is **eigenaarsconfiguratie**. The file: **configuratiebestand van de eigenaar**, on onboarding steps **eigenaarsbestand**. `navee-owner-setup.json` stays as written. Website buttons stay English until the site changes.
- *History*: Meetgeschiedenis, Ritgeschiedenis, Foutgeschiedenis. Narrow *Historie*.
- *Recovery*: energieterugwinning vs **herstel** (restore). Undoing a try is **terugzetten**.
- *Test*: **proberen**, result **poging** (a try); **testen** (guided); **Gepland laden testen**; **compatibiliteitscontrole**. Never *testen* for Try.
- *Readback*: **teruglezen**, neutral, because a readback can also fail: *Teruglezing wijkt af*. Nothing with *bevestiging* inside it.
- *Rejected* and *Refused by scooter*: both **geweigerd**.
- *Standing start*: **Start vanuit stilstand** is value 0 of **Startsnelheid**, not another name for it.
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

## 9. Sources consulted

**Official Dutch wording: partly found, for product and feature words only.** Nothing public shows the NAVEE app's Dutch screens, so its words for charge limit, PIN, owner key and experimental features are unknown, and those are Claude's own proposals. No APK was downloaded.

- **NAVEE Belgium store, GT3 page, Dutch:** [be.naveetech.com/products/gt3](https://be.naveetech.com/products/gt3), read through a summarizing fetch, so quotes may not be exact. It uses je. Printed: *step*, *scooter*, *voertuig*, *vergrendelfunctie*, *vergrendelknop*, *motorvergrendelingsstand*, *vergrendelde toestand*, *NAVEE APP*, *accusysteem*, *batterij*, *actieradius*, *topsnelheid*, *Max. Snelheid*, *maximumsnelheid*, *energieterugwinning(sfunctie)*, *energieherstelinstellingen*, levels *zwak, gemiddeld, sterk*, *Tractiecontrolesysteem (TCS)*, *Ingebouwde Richtingaanwijzers*, *geïntegreerde knipperlichten*, *Automatische Koplamp*, *Achterlicht*, *Rijmodi*, *Standaardmodus*, *voetgangersmodus*, *Ingebouwd Display*, *dashboard* for the display, *stuur*, *remhendel*, *km/u*.
- **Not reachable:** [the GT3 Pro Dutch manual](https://service.naveetech.com/wp-content/uploads/2025/01/UM-NAVEE-GT3-Pro-NL.pdf) returned 404; a search snippet only showed it exists and uses u. A later reader should try again. [be.naveetech.com/collections/all](https://be.naveetech.com/collections/all) gave 429, and [handleidingen.com's V40 Pro page](https://www.handleidingen.com/navee/handleiding-v40-pro) and [MediaMarkt NL](https://www.mediamarkt.nl/nl/product/_navee-k100-elektrische-step-blauw-1890047.html) gave 403. [TechRadar nl-be's S65C review](https://global.techradar.com/nl-be/vehicle-tech/e-scooters/navee-s65c-review) returned no article text.
- **Android, read from AOSP:** [SettingsLib nl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-nl/strings.xml) and [Settings nl](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-nl/strings.xml): *USB-foutopsporing*, *Ontwikkelaarsopties*, *Koppelen*, *Verbinding verbreken*, *Instellingen*, *Buildnummer*, *Rechten*, *Batterijbesparing*, *pincode*. *Over de telefoon* wasn't found by that search, so it's from memory.
- **Formats:** [CLDR nl](https://github.com/unicode-org/cldr-json) read directly: percent `#,##0%`, decimal comma, thousands point, quotes ‘…’, narrow hour *u*. [Taaladvies on spaces around symbols](https://taaladvies.net/wel-of-geen-spaties-voor-en-na-leestekens-en-symbolen-algemeen/) and [Onze Taal](https://onzetaal.nl/taalloket/gradenteken-en-andere-tekens-spatie-of-niet), search snippets only: technical norms want a space before %, while writing it closed is accepted in general text. [Degrees take a space](https://taaladvies.net/gradenteken-in-temperatuuraanduidingen-spatie-ervoor-of-niet/) is from the title and memory.
- **Platform style:** [Microsoft Dutch Localization Style Guide](https://download.microsoft.com/download/e/8/f/e8f04dd4-591e-46e9-a076-e49a8c8c75e4/nld-nld-StyleGuide.pdf), read directly: sentence case, lower case after a colon, avoid abbreviations, single quotes, vowel-clash hyphens, hyphens after acronyms, infinitive in UI. Its advice against en dashes isn't followed for ranges, because Volt's `fmt_range` has one. It points to the Woordenlijst, which wasn't consulted entry by entry.
- **Usage, weaker evidence:** [Androidworld.nl's S65C review](https://androidworld.nl/reviews/navee-s65c-een-elektrische-step-om-buitenlandse-steden-te-verkennen/): *step*, *ontgrendelen*, *vergrendelen*, *actieradius*, *energieterugwinning*, *stuur*, je. [Decathlon NL listings](https://www.decathlon.nl/p/mp/joyor-y8-s-abe-elektrische-step-48v-26ah-accu-en-richtingaanwijzers/6f071a45-bca2-477c-86ab-8ef63051d66f/c1), search snippet: *accu*, *richtingaanwijzers*, *elektrische step*. [Circuits Online](https://www.circuitsonline.net/forum/view/157647/1), a forum, search snippet: *gashendel*, *cruise control*.
- **From the proposer's knowledge, not looked up:** *cruisecontrol*, *dagrijlicht*, *kilometerstand*, *km-stand*, *loophulp*, *sfeerlicht*, *treeplank*, *stuurpen*, *aan-uitknop*, *gelieerd aan*, and the abbreviation table.
- **No source, Claude's choice; debate these first:** eigenaarssleutel, eigenaarsbestand, medegebruiker, stuur-PIN, laadlimiet, snelheidslimiet, Topsnelheid for the measured value, Actieve snelheidsrem, Afdaalhulp, Motorondersteuning, Spaarstand, Opgeladen vóór, Klaar vóór, Lijnverlichting, Sterverlichting, Livegegevens, Slot on the 62dp tile, the rungs of section 5 and the "not affiliated" wording.
- **Native review:** none yet.

**Choices where the sources disagree:**

- **step over scooter.** NAVEE's page mixes them and the tool tests use *scooter* as sample data, but *scooter* first means a moped in Dutch, and *step* is the shop and press word.
- **accu over batterij.** NAVEE's page has both. *Accu* is shorter (27.5 vs 43.1dp) and keeps the phone's *batterij* separate.
- **Maximumsnelheid and Topsnelheid.** NAVEE's page uses both for the setting. D3 gives Topsnelheid the measured value.
- **vergrendelen over op slot.** NAVEE's page, Android and press agree. *Slot* survives only on the one narrow tile.
- **PIN over pincode.** Android says *pincode*, but `PIN` is do-not-translate in Volt's keys. Debate whether to lift that for nl.
- **je over u.** Owner's decision. NAVEE's page uses je; its manual snippet shows u.
