# German style guide (de)

**Status: Claude-only proposal, 2026-11-05.** One model drafted this. The three-vendor debate has not
seen it, and no native speaker has checked it. Everything below, safety wording included, can change.
"Approved" here means "the wording this proposal asks every drafter to use".

**Wait for D3.** Do not draft German for a key whose English D3 rewords until D3's English has landed (plan 7.2,
step 0). `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` matter most: today's English still
says "Top speed" on them, so the required "Max. Tempo" and "Höchstgeschwindigkeit" fail the top-speed row, and that
row must not be widened to let a measured word onto a setting.

## 1. Register and form of address

- Address the rider as **du** (owner's decision, 2026-11-02): du, dich, dir, dein, lower case. NAVEE's
  own manual says Sie; do not copy that. Never mix the two forms.
- Tone: friendly and plain, like a good app, not a legal text and not marketing. No slang and no "Hey!".
- **Buttons and menu actions use the infinitive**, verb last: "Scooter sperren", "Verbinden", "Backup
  erstellen", "Problem melden". **Instructions in sentences use the du imperative**: "Tippe auf
  „Zulassen“.", "Verbinde dich neu.", "Gib deine Lenker-PIN ein." Write imperatives without an
  apostrophe or a final -e where none belongs ("Gib", "Nimm", "Tippe").
- Status text uses a participle or noun, no subject: "Verbunden", "Wird gesperrt…", "Messung läuft".
- Do not use "bitte" in short labels. In a sentence use it at most once, and only where a request
  really is polite.
- **Gender.** Role nouns stay short and generic: "Besitzer", "Mitnutzer". No star, colon or capital I:
  they cost width and read badly in TalkBack. Avoid the role noun where a sentence works without it
  ("Nur mit dem Besitzerschlüssel möglich"). This is a proposal for the debate.

## 2. Punctuation and quotes

- Quotes: **„…“**, nested **‚…‘**. Never "…" or «…». When the English quotes a Volt button, quote the
  German button's exact text. When it quotes a screen that stays English (the setup website, an adb
  prompt), keep the English inside German quotes: a site button such as „Choose owner file“ stays as the site
  shows it. Android's own prompts appear in the phone's language, so use the German text: „USB-Debugging zulassen?“.
- A full sentence ends with a period; a label, button or title has none. Keep the English's "…" as the
  single character … with no space before it ("Wird entsperrt…").
- Dash: en dash with spaces for a break ( – ), never an em dash. Ranges: en dash, no spaces (10–20 km).
- Keep the source's separators such as " · " as they are.
- The comma before an infinitive group with "um" is required: "Tippe hier, um zu verbinden."
- Do not use a soft hyphen (U+00AD), zero-width space or any other invisible break character.
- ß stays in ordinary text ("Größe", "außerdem"). It is in all four Saira weights, and so are ä ö ü Ä Ö Ü
  „ “ ‚ ‘ – … · °. Saira has no U+202F; use U+00A0 where a no-break space is called for. Capital ẞ is
  not needed: Android's decorative upper-casing makes SS, which is correct German.

## 3. Capitalization

- German has no title case. Nouns are capitalized everywhere, and the first word of a label, button or
  title is capitalized. Everything else stays lower case: "Geplantes Laden", "Verbindung trennen",
  "Experimentelle Funktionen".
- Inside a sentence an adjective is lower case: "Der Scooter ist gesperrt." As a standalone status it
  starts with a capital: "Gesperrt". The glossary lists one case; both are approved.
- Glossary forms inflect in a sentence: "der geplanten Ladung" is wrong (see 8), "beim geplanten Laden"
  is right.
- Keep all-caps tokens as written: WALK, ECO, DRIVE, SPORT, PIN, NAVEE, GPX, CSV. Do not write your own
  words in capitals; Volt upper-cases some captions itself.
- `km-Zähler` starts lower case even at the start of a label, because "km" is a unit symbol.

## 4. Numbers, dates and units

- Decimal comma, thousands point: **1.234,5**. Volt's `Fmt` does this at run time; a template has only
  placeholders. Do not type numbers with another format into a string, and keep every number the English
  has.
- **Percent: space before the sign.** `fmt_percent` is `%1$s` + U+00A0 + `%%` ("80 %"), following CLDR and
  DIN 5008. In running text write it the same way.
- **Number and unit:** a space, no-break in templates: `fmt_quantity` is `%1$s` + U+00A0 + `%2$s`
  ("25 km/h", "3,7 V", "20 °C"). `fmt_range` keeps its en dash ("10–20 km"), `fmt_dual` stays `%1$s / %2$s`.
- **Unit symbols stay** (km/h, mph, km, mi, V, A, W, Wh, Ah, °C, °F, min). Never "Km/h", "KMH" or "Std/km".
  Unit words are translated: Kilometer, Meilen, Minuten.
- **Durations:** compact `%1$d h %2$02d min` ("1 h 05 min"), and `min`, `s` alone. Spoken and running
  text: "1 Stunde", "2 Stunden", "5 Minuten". English's "1h 05m" with no space is not German. Other
  compact words: Tage "Tg." (or the whole word "Tage" where it fits), Monate "Mon.", Jahre "J."; "Tsd." for the chart's "k"; "Pkt." for "pts".
- **Dates and times:** from the phone's format locale. Where a template holds them: "5. Nov.", "5. Nov.
  2026", "05.11.2026", 24-hour "14:30". Add "Uhr" only in a sentence: "Der Ladevorgang startet um 14:30
  Uhr." Relative: "vor 5 Minuten". Do not upper-case months.
- Hour-only values as in scheduled charging: "14 Uhr" in text, "14:00" on a chip.
- **Plurals:** `one` and `other`. Keep the placeholder as the English has it. Both are full phrases: "%1$d Fahrt" / "%1$d Fahrten".

## 5. Narrow labels: the ladder

Every ladder runs full, `_short`, `_compact`, then icon. Width is measured in Saira, never in characters.
In the labels measured here German is 40-65% wider than English (Odometer 57.7dp, Kilometerzähler 92.4dp), so plan for it. Keep the most useful word when a rung must drop one.

**Order of tools, try each in turn:**

1. **A shorter real word** beats an abbreviation: "Optionen" for Einstellungen, "Tempo" for Geschwindigkeit,
   "Akku" for Batterie, "Licht" for Beleuchtung, "Start" for Ladestart where the context says charging.
2. **Drop the part the screen already says:** Akkuspannung → Spannung, Akkutemperatur → Temp., Fahrmodus →
   Modus, Trip-Strecke → Strecke, Trip-Zeit → Zeit.
3. **Keep only the qualifier where the screen already names the thing:** "Geschätzte Reichweite" → "Schätzung" or
   "Geschätzt". Never put "ca." behind a noun; it goes before a number.
4. **An approved abbreviation** from the table below. Only those; do not invent one.
5. **A hyphen in a compound** so it can wrap at that hyphen where the budget allows two lines
   (see the compound list).
6. If nothing fits a rung, keep the shortest honest word, let the width check warn, and leave a note for
   the judge. Never cut a word by letters to fit.

**Approved abbreviations** (period at the end; one space follows; no period on a whole word):

| Full | Abbreviation | Example | Do not write |
|---|---|---|---|
| Einstellungen (in a compound) | -Einst. | Akku-Einst. (63.5dp at 12sp), Fahr-Einst., App-Einst. | Einstlg., Einstell., Einst. alone |
| Geschwindigkeit | Geschw. or Tempo | Höchstgeschw. (87.4dp at 12.5sp), Max. Tempo | Gesch., Geschwind. |
| Maximal, Höchst- | Max. | Max. Tempo (70.0dp at 12.5sp) | Vmax, Maximal. |
| Durchschnitt | Ø | Ø Tempo | Durchs., Avg., Mittel for an average |
| Temperatur | Temp. | Akkutemp., Temp. | Tmp. **Temp. never means Tempo.** |
| Reichweite | Reichw. | Reichw. | Rw., RW |
| Kilometerzähler | km-Zähler | km-Zähler (59.5dp) | Odo., KM-Zähler, Km.-Zähler |
| Gesamt | none | Fall to the icon where "Gesamt" does not fit | Ges. (reads as Gesellschaft), Gesamtk. |
| Kapazität | Kap. | Kap. | Kapaz. |
| Funktionen | Funkt. | Exp. Funkt. (61.6dp at 12sp) | Fkt. |
| Experimentell | Exp. | Exp. Funkt. | Experim. |
| ungefähr, geschätzt | ca., before a number only | ca. 25 km. One exception: `stats_health_chip`, a separate 40dp tag after the figure, is "ca." ("Geschätzt" is 41.5dp at 9sp) | gesch., Kapazität ca., Reichweite ca. |
| Installieren | Install. | `owner_shizuku_install` | Inst. |

Avoid an abbreviation in a sentence, except the common z. B., ca., d. h., with spaces inside. Never abbreviate
safety words (section 6), except "Exp. Funkt.". "Funkt." can read as the verb funkt, so the debate should weigh "Exp. Funkt."
against the real word "Labor" (31.8dp at 12sp); D3 lets a language use its own short word. Until then "Labor" is not approved.

**Real ladders**, measured with `res/font/saira_medium.ttf`:

| English | Full | `_short` | `_compact` |
|---|---|---|---|
| Odometer / Odometer / Odo. | Kilometerzähler (92.4dp at 12.5sp) | km-Zähler (59.5) | Gesamt (44.3) |
| Maximum speed tile, 90dp at 12.5sp | Höchstgeschwindigkeit (134.2) | Max. Tempo (70.0) | |
| Trip top speed / Top speed | Spitzengeschwindigkeit | Trip-Spitze | Spitze (36.5) |
| Trip avg. speed / Avg. speed | Durchschnittsgeschwindigkeit | Trip-Ø-Tempo | Ø Tempo |
| Battery voltage / Voltage | Akkuspannung | Akkuspannung | Spannung |
| Battery temp. / Temp. | Akkutemperatur | Akkutemp. | Temp. |
| Est. capacity (health) / Est. capacity | Geschätzte Kapazität (Zustand) | Geschätzte Kap. (Zustand) | Geschätzte Kap. (93.6) |
| Est. range / Range | Geschätzte Reichweite | Geschätzte Reichw.; "Geschätzt" (57.7) or "Schätzung" (61.4) only where the screen already says range | Reichweite (64.2) |
| Energy recovery | Energierückgewinnung (133.6) | Rückgewinnung (93.0) | Rekup. is not approved; fall to the icon |
| Compatibility check | Kompatibilitätsprüfung (134.8) | Kompatibilität (81.9) | Prüfung |
| Experimental controls, 90dp and 64dp | Experimentelle Funktionen | Experimentell (80.4) | Exp. Funkt. (61.6 at 12sp) |
| Unverified here / Unverified | Hier nicht verifiziert | Nicht verifiziert (89.3) | Nicht verifiziert wherever it fits; Ungeprüft (59.6) only where it does not |
| Worked here / Worked | Hat hier funktioniert | Hat funktioniert (92.8) | |
| Charge done by, 64dp and 90dp | Fertig geladen bis (99.3 at 12sp) | Fertig bis (54.0) | |
| Charge done by time, 64dp and 90dp | Fertig bis (51.9 at 12sp). This matches the switch's label, so note it for the judge | Fertig bis: Zeit (82.7) | |
| Owner key | Besitzerschlüssel (100.7) | Same word; fall to the icon if it does not fit | |

**Hard budgets in the catalog at the time of writing:**

- `onboarding_welcome_tile_*`, 62dp at 12.5sp: Sperren 46.0, **Optionen 53.2** (Einstellungen is 79.3 and does
  not fit), Akku 29.3, Fahrten 45.3.
- `dashboard_model_qa_*`, 64dp at 12sp, one line: Licht 26.8, Optionen 51.1, Live-Daten 60.2, Fahrten 43.5, Fahr-Einst.
  60.6, Akku-Einst. 63.5, Lichtleiste 56.9 (Lichtleisten is 64.03, just over), Sternlicht 53.7, App-Einst. 57.6, Fertig bis 51.9
  (`charge_done_by` and `charge_done_time`; "Fertig geladen bis" is 99.3). English overflows on
  several of these already, where the check only warns; "Einstellungen" alone would turn the Settings key
  into an error.
- `dashboard_model_tile_top_speed`, 90dp at 12.5sp: Max. Tempo.
- `owner_shizuku_install`, 48dp at 14sp: "Installieren" is 72.7. Use "Install.".

**Compounds.** Line breaking follows only spaces and real hyphens. German compounds never get an invented
break.

- **Must take a hyphen** (acronym, brand, digit, unit or a name):
  NAVEE-App, Lenker-PIN, PIN-Entsperrung, USB-Debugging, Bluetooth-Kopplung, CSV-Datei, GPX-Datei, Modell-ID, Volt-Daten,
  4-stellig, km-Zähler, Build-Nummer.
- **May take a hyphen, in this exact spelling,** because the closed form misreads or is too long to fit:
  Besitzer-Einrichtung, Besitzer-Einrichtungsdatei (closed reads like furniture), Akku-Einstellungen, Fahr-Einstellungen,
  Scooter-Display, Scooter-Name, Display-Einheiten, Telefon-Fahrtenbuch, Fahrmodus-Tempolimit, Logo-Licht,
  Live-Daten, Trip-Strecke, Trip-Zeit, Mitnutzer-Schlüssel, Auslese-Skript, Eco-Schätzung.
- **Stay closed; do not hyphenate:** Besitzerschlüssel, Besitzerdatei, Besitzerprofil, Energierückgewinnung, Kilometerzähler,
  Höchstgeschwindigkeit, Spitzengeschwindigkeit, Startgeschwindigkeit, Geschwindigkeitsbegrenzung, Kompatibilitätsprüfung,
  Kompatibilitätsbericht, Reichweitenschätzung, Beschleunigungsprofil, Ladegrenze, Ladestart, Akkustand, Akkuspannung,
  Akkuwarnung, Sperrstatus, Sperrdauer, Sperrleiste, Messwertverlauf, Traktionskontrolle, Bergabfahrhilfe.
- Do not coin a new compound of more than three stems. Split it: "Tempolimit des Fahrmodus", not
  "Fahrmodustempolimit"; "Warnung bei niedrigem Akkustand", not "Niedrigakkustandswarnung".
- Add a hyphen to a compound only if it is on these lists. Anything else goes to the judge with a note.

## 6. Safety wording

Use exactly these words. In a safety key a term that is not used fails `check.py` or the judge. Never shorten,
reorder or soften them, and do not abbreviate them on a ladder. The exceptions are the rungs named here: "Exp. Funkt." for a narrow
Experimental controls chip, "Fertig bis" and "Niedriger Akkustand".

| English | German, exact | Rules |
|---|---|---|
| Lock (action) | **sperren**; button **Sperren**, **Scooter sperren** | Noun: die **Sperre**. |
| Locked | **Gesperrt** / gesperrt | State. Not verriegelt, blockiert, abgeschlossen, zu. |
| Unlock (action) | **entsperren**; button **Entsperren**, **Scooter entsperren** | Noun: die **Entsperrung**. Never "Sperre aufheben", "Sperre lösen", "Sperre aus", "freischalten" or "öffnen". |
| Unlocked | **Entsperrt** / entsperrt | State. Not offen, frei, freigeschaltet. Never "nicht gesperrt" where the state is confirmed. |
| Lock state unverified | **Sperrstatus nicht verifiziert** | Must never read as confirmed. Not "Sperrstatus geprüft", not a plain "Sperrstatus". |
| Lock state unknown | **Sperrstatus unbekannt** | Same meaning as above; keep the English's choice of word. |
| Last known | **Zuletzt bekannt: gesperrt**; **Letzter bekannter Sperrstatus: entsperrt** | Keep "last known". Never a plain "Gesperrt". |
| unknown | **unbekannt** | Not "keine Angabe". "Not reported" is **Nicht gemeldet**. |
| never | **nie**; **niemals** in warnings | Keep every one. |
| always | **immer** | Always on: **Immer an**. |
| On / Off | **An** / **Aus**; verbs **einschalten** / **ausschalten** | Off level of Energy recovery: **Aus**. |
| alarm | **Alarm**; Alarm sound: **Alarmton** | The thing that sounds. Stop button: **Stopp**. |
| stop | Alarm button: **Stopp**; a scooter: **anhalten** ("Halte den Scooter zuerst an"); a recording or setup: **beenden** ("Beende zuerst die Aufzeichnung") | "beenden" and "anhalten" are never the alarm button. |
| alert | **Warnung**; **Ladewarnung**; Battery alerts: **Akkuwarnungen**; Low battery alert: **Warnung bei niedrigem Akkustand**, narrow **Niedriger Akkustand** | Never "Alarm" for an alert, or the reverse. **Akkuwarnung** names only the umbrella, never the low battery alert alone. |
| Charge done by | **Fertig geladen bis**; narrow keys **Fertig bis** | Finishing by a deadline. Not "Geladen bis", which reads as "charged up to" or "charging until" and blurs with Ladegrenze. A feature name: in a sentence it goes in quotes: „Fertig geladen bis“ ist aus. |
| PIN | **die PIN** (feminine); **Lenker-PIN**; **PIN-Entsperrung** | Handlebar code, 4-stellig. Not "PIN-Code", "PIN-Nummer", "Passwort" or "Zugangscode". A backup password stays **Passwort**. |
| owner key | **Besitzerschlüssel** | A secret. "Not a password": **Kein Passwort**. Never "Besitzer-ID" or "Schlüssel" alone. |
| owner, shared rider | **Besitzer**, **Mitnutzer** | Not "Mitfahrer", which means passenger. |
| charge limit | **Ladegrenze** | Keep the number and %. Not "Ladelimit". "At limit" for this term: **An der Grenze**. |
| experimental | **experimentell**; **Experimentelle Funktionen** | Badge LAB, spoken **Experimentell**. |
| untested | **ungetestet**; **Hier ungetestet** | Not "ungeprüft", which belongs to unverified. |
| unverified | **nicht verifiziert** (narrowest rung **ungeprüft**) | Prefer "Nicht verifiziert" wherever it fits: Ungeprüft and Ungetestet read as near synonyms. Debate this pair. |
| unconfirmed / not confirmed | **unbestätigt** / **nicht bestätigt** | Never a form that reads as bestätigt. |
| not affiliated with NAVEE | **steht in keiner Verbindung zu NAVEE** | The footer reads „Volt 1.2 · steht in keiner Verbindung zu NAVEE“. Not "nicht offiziell", "unabhängig" alone or "kein Partner", which soften it. Not "nicht mit NAVEE verbunden", which reads as a Bluetooth state. |

Other rules:

- gesperrt and entsperrt differ by their first letters only. Keep them whole and never pair them with a negation.
- Lock compounds start with Sperr-: Sperrstatus, Sperrdauer, Sperrleiste. "Status der Sperre" is also fine. Do not use "Scooter-Sperre" or "Bluetooth-Sperre": the check finds a lock word only at the start of a compound.
- "Sperren" is also a noun-verb in lists. In a two-button pair the labels stay "Sperren" and "Entsperren", never "Sperre an/aus".
- A sentence that says a command **may** have been sent ("may have applied it") keeps that doubt: "kann ... übernommen haben". Do not turn it into a statement.
- Translate speed and legal wording literally, and add no claim about what the law allows. Don't mention StVO, eKFV or 20 km/h unless the English does.
- Report content stays English (plan section 8), and the surrounding UI says so: "Der Bericht ist auf Englisch."

## 7. Scooter vocabulary

Official NAVEE German wording comes first, where one exists (section 9).

| Thing | German | Note |
|---|---|---|
| scooter | **Scooter**, der; des Scooters; die Scooter | Official. Not Roller, which also means a moped. E-Scooter only where a first mention needs it. |
| phone | **Telefon** | Official and Android's word. Not Smartphone or Handy. |
| battery | **Akku**, Akkustand | Official in manuals. Batterie only if the English distinguishes. |
| charger, charging | **Ladegerät**, **Laden**, Ladevorgang | Official. |
| display on the scooter | **Display**, Scooter-Display | Official. **Never Dashboard, Tacho or Armaturenbrett.** |
| Dashboard | **Dashboard** | Only Volt's Home layout. |
| handlebar, stem, deck, kickstand | **Lenker**, **Vorbau**, **Deck**, **Ständer** | Official. |
| brake lever, throttle, power button | **Bremshebel**, **Gashebel**, **Ein-/Aus-Schalter** | Brake lever and switch are official. Gashebel is Claude's pick: the guide says both Beschleunigungshebel and Gaspedal. |
| turn signal, tail light, headlight | **Blinker**, **Rücklicht**, **Scheinwerfer** | Official. Turn signal sound: **Blinkerton**. |
| drive mode | **Fahrmodus**, compact **Modus** | Names stay WALK, ECO, DRIVE, SPORT. |
| range, trip, ride | **Reichweite**, **Trip**, **Fahrt** | Trip is a distance counter; Fahrten are recorded rides. |
| pair, connect | **koppeln**, **verbinden** | Official. Keep distinct, and from account binding (**Kontobindung**). |
| Settings, Home, More | **Einstellungen**, **Start**, **Mehr** | Start is only the tab label. In a sentence Home is **Startseite**: "Zur Startseite", "Bereits auf der Startseite". |
| reading, raw, packed | **Messwert**, **(roh)**, **(gepackt)** | Chip RAW → ROH. |
| voltage, current, power, health, cycles | **Spannung**, **Strom**, **Leistung**, **Zustand**, **Ladezyklen** | |
| history, last seen, no reading | **Verlauf**, **Zuletzt**, **Kein Messwert** | |

Android's settings keep Android's German: **USB-Debugging**, **Entwickleroptionen**, **Build-Nummer**, and the path
**Einstellungen › Über das Telefon**. Volt's own names keep the glossary's words.

**Words shared only by accident (D3). Do not unify them:**

- *limit*: Ladegrenze (charge), Tempolimit (speed), Aufzeichnungslimit or Maximum (storage or time), Widget-Limit.
- *key*: Besitzerschlüssel; a data key in JSON or preferences is **Eintrag** or **Feld**; RSA-Fingerabdruck.
- *identity*: identifying a model is **Erkennung**, not the owner key.
- *shared*: "shared evidence" is **geteilt**, not Mitnutzer.
- *setup*: general is **Einrichtung**; owner setup is **Besitzer-Einrichtung**.
- *Eco*: Eco (acceleration, keep), ECO (drive mode, keep), **Eco-Schätzung** (range).
- *History*: Messwertverlauf, Fahrtverlauf, Fehlerverlauf.
- *Recovery*: Energierückgewinnung vs **Wiederherstellung** (restore).
- *Test*: **Ausprobieren** (a try), **Test** (guided), **Geplantes Laden testen**, **Kompatibilitätsprüfung**. Never "testen" for Try.
- *Speed*: **Höchstgeschwindigkeit** is the setting and **Spitzengeschwindigkeit** is what was measured. Tempolimit is below them. Do not swap them.
- *Standing start*: **Start aus dem Stand** is value 0 of **Startgeschwindigkeit**, not another name for it.
- "experimental motion" in Rides takes its own gender: **experimentelle Bewegungsdaten**.

## 8. Grammar traps

- Genitive and dative change the glossary form: "des Scooters", "beim geplanten Laden", "am Scooter-Display". Use "Laden" (a verb used as a noun), never "die Ladung".
- Placeholders may carry a noun with a gender you cannot see. Prefer wording that needs no article before %1$s: "Scooter: %1$s" or "Name: %1$s".
- Separable verbs split in sentences: "Schalte den Alarm aus." Keep the particle at the end of the clause.
- Keep a sentence's negation next to the word it negates: "nicht verifiziert", "nicht bestätigt".
- English "Excludes…" (`stats_diagnostics_privacy`, `stats_diagnostics_export_description`) is not "Ohne…": that fails the polarity check, because the English has no negation word. Write "Ausgenommen: Besitzerschlüssel und PIN".
- German does not inflect in the check: only the listed forms match. Every adjective phrase row lists the -e, -en, -em, -er and -es forms it needs ("das geplante Laden", "beim geplanten Laden", "des geplanten Ladens").

**Regions.** One `values-de` serves Germany, Austria and Switzerland. Swiss readers see ß. Do not use regional words.

## 9. Sources consulted

**Official German wording: found, with limits.** Nothing shows the NAVEE app's German screens. No APK was downloaded, and D3's local dump of app 2.1.9 holds English only. So menu names other than those printed below are not established.

- **NAVEE XT5 series EU User Guide**, a NAVEE-branded multilingual PDF, read directly, DE parts only. The copy read is on a retailer's site: wheelyshop.se/cdn/shop/files/NAVEE_XT5_series-EU-User_Guide.pdf. Printed: Scooter (also Elektroroller, Roller, Fahrzeug), sperren, gesperrt, Zum Entsperren, Bluetooth-Sperre, Entsperrung mit Code / per Passwort / per Code, "Entsperrung per Code oder per Bluetooth-Nähe", Bluetooth-Näherungsentsperrung, Bluetooth-Näherungssperre, Zugangscode, Entriegelung (once), NAVEE-App, Weitere Funktionen, Geplantes Laden, Ladezeitplan, Startgeschwindigkeit, Geschwindigkeitsbegrenzung, Kopplung, koppeln, verbunden, Akkustand, Ladegerät, Signalton, Blinker-Tasten, Bremshebel, Beschleunigungshebel, Gaspedal, Vorbau, Lenker, Ständer, Display, Firmware-Version, Zurücksetzen, Ein-/Aus-Schalter. It uses Sie.
- **naveetech.de** (home page, GT3 Max, UT5 Max and XT5 Ultra product pages, a blog post), read through a summarizing fetch, so quotes may not be exact: E-Scooter, Fernverriegelung, "entsperren/verriegeln", Energierückgewinnung, Traktionskontrollsystem (TCS), Werkseitige Höchstgeschwindigkeit, Reichweite, Rücklicht, Blinker, Automatisches Scheinwerferlicht, Tagfahrlicht, Geschwindigkeitsmodi. Mostly Sie, with du in some marketing. Customer reviews there also say Schiebeunterstützung, Zero-Start, Wegfahrsperre and Seitenleuchten; those are not NAVEE's wording.
- **Not reachable:** the PDFs at service.naveetech.com, including a German-only V40i Pro/V50i Pro manual, returned 404. A later reader should try them again.
- **Android:** developer.android.com/studio/debug/dev-options?hl=de (search result): Entwickleroptionen, Build-Nummer, Über das Telefon, USB-Debugging.
- **Formats:** CLDR (de: percent with no-break space, decimal comma) and DIN 5008, by secondary sources (Duden's own rules pages were not reached). Abbreviations and hyphen rules are the proposer's knowledge of Duden, not looked up rule by rule.
- **Established terms** in the glossary's source column are the proposer's knowledge of German usage, not looked up one by one. Searches did show Tempomat and Traktionskontrolle in German e-scooter reviews (notebookcheck.com, gamestar.de) and gave nothing on Bergabfahrhilfe or Schiebehilfe beyond the review above.
- **No source, Claude's choice:** Besitzer, Besitzerschlüssel, Mitnutzer, Lenker-PIN, Ladegrenze, Tempolimit for the rider's limits, Spitzengeschwindigkeit, Aktive Tempobremse (Tempokontrolle reads as a police speed check), Motorunterstützung, Fertig geladen bis, Sternlicht, Lichtleiste, Live-Daten, the rungs of section 5, and the "not affiliated" wording. Debate these first.

**Choices where the official sources disagree:**

- **sperren over verriegeln.** The manual uses sperren and entsperren for the app and handlebar flow, and Android uses them too. naveetech.de's marketing says Fernverriegelung and entsperren/verriegeln.
- **Scooter over E-Scooter or Roller.** The manual's main word, the same width as English; naveetech.de prefers E-Scooter, which is wider.
- **Tempolimit over Geschwindigkeitsbegrenzung.** The manual's word is 26 letters; the longer one stays approved where it fits.
- **Akku over Batterie.** The manual says Akku. The spec tables say Batterie.
- **Lenker-PIN over Zugangscode.** D3 chose PIN. The manual says Code, Passwort and Zugangscode, and the app setting's German name is unconfirmed.
