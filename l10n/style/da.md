# Volt style guide: Danish (da)

**Status: Claude-only proposal (2026-11-05), not final.** One model drafted this from the sources in section 8. The three-vendor debate has not seen it and no native speaker has reviewed it. "Approved" below means "the wording this proposal asks every drafter to use"; safety wording included, all of it can change.

## 1. Register and form of address

- Address the rider as **du**: *du, dig, din, dit, dine*, always lower case. Never *De*, never *man* as a stand-in for the rider. Possessives agree: *dit løbehjul*, *din oplader*, *dine ture*.
- Prefer the imperative to writing *du* at all: *Tilslut opladeren*, not *Du skal tilslutte opladeren*.
- **Buttons, toggles and menu actions use the imperative**, as Android's Danish does: *Gem*, *Forbind*, *Lås*, *Lås op*, *Afprøv*, *Gendan*. **Instructions in sentences use the imperative too**: *Opret forbindelse igen for at verificere.* Labels and titles are nouns: *Opladningsgrænse*, *Køretilstand*.
- **Imperatives of -ere verbs** take an accent only where the plain form would confuse (Microsoft's note): *Aktivér*, *Deaktivér*, *Rapportér*, *Sikkerhedskopiér*. *Annuller* takes none.
- Failure messages: **Kunne ikke …** for "Could not …" and "Failed to …": *Kunne ikke læse værdien.* Not *Mislykkedes med at*.
- Status in progress uses the present tense and keeps `…`: *Forbinder…*, *Låser…*, *Låser op…*, *Måler…*, *Oplader…*.
- "If" and "to" clauses: open with *Hvis du vil …, skal du …* or just the action. Don't copy English "To …" as *For at …*, unless the purpose comes second: *Opret forbindelse igen for at verificere.*
- **Tap is *tryk på*:** *Tryk på ”Tillad”*. Typing is in Fixed words.
- Calm, plain, friendly. No exclamation marks in errors, no slang, no marketing tone, no reassurance the English lacks (*sikkert*, *garanteret*).
- Role nouns are gender neutral (*ejer*, *medbruger*). Nothing else to avoid.
- Write Danish, not calqued English. A noun string in English often needs a preposition: *Advarsel om lavt batteri*, *Enheder på løbehjulets display*, *Starttid for opladning*.

## 2. Punctuation and quotes

- Quotes: **”…”** (the same mark on both sides, Microsoft's format), inside them **’…’**. Never “…”, „…“ or »…«, never straight quotes. Danish book typography also uses »…«; the debate may prefer it. Keep quotes where the English quotes a UI name. A one-word label needs none in Danish running text, but keep the mark if the English has it, so every label reads alike.
- Period and comma go outside a closing quote unless the quote is a whole sentence.
- No serial comma before *og* or *eller*. **Comma between imperative clauses with different objects** (Microsoft): *Tænd for løbehjulet, og luk NAVEE-appen.* A comma before a subordinate clause (*at, hvis, når, som*) is the usual Danish start comma, and is fine: *Tjek, at løbehjulet er tændt.*
- Dash: spaced en dash **–** in running text; unspaced for ranges *10–20 km*. Never an em dash. Keep `·` separators and `…` exactly as the English has them, with no space before `…` in UI text (Microsoft's rule for UI).
- After a colon: lower case unless a complete sentence or a name follows: *Sidst kendt: låst*.
- **Genitive takes no apostrophe:** *løbehjulets*, *Volts*. An acronym takes apostrophe plus s (Microsoft): *USB's*. NAVEE's genitive is *NAVEE's*; prefer *NAVEE-appen* where it works. A term with `'` must be escaped as the resource rules say, so avoid *model-id'et* in a label.
- **`&`:** Danish writes **og**. `&` is allowed only on a width-limited rung where *og* doesn't fit, and never in a sentence: *Display & lyd*.
- **Compounds are one word.** Writing them apart (*særskrivning*) is a serious error and can change the meaning. A compound with an acronym, a name or a digit first takes a hyphen: *NAVEE-appen*, *USB-fejlretning*, *GPX-fil*, *PIN-oplåsning*, *Sport-tilstand*. Over three parts, rephrase with a preposition: *Starttid for opladning*, not *Opladningsstarttid*; *Hastighedsgrænse for køretilstand*, not *Køretilstandshastighedsgrænse*. The check matches whole words and has no Danish compound rule, so a compound passes only if the glossary lists it. Where a correct compound isn't listed, the preposition form is the fallback: *Titel på widgetten*, *Farve på ruten*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere** (Microsoft: everything but proper nouns is lower case). Don't follow the English capitals.
  - Button: *Lås op*. Label: *Opladningsgrænse*. Title: *Eksperimentelle funktioner*. *Walk Assist* → *Skubbehjælp*.
- Feature names are lower case mid-sentence: *slå planlagt opladning til*. One exception, *Færdig senest*: see section 7. Where a sentence must name a button, keep the quotes the English has: *Tryk på ”Tillad”.*
- After a colon: see section 2. Days and months are lower case. Languages are lower case: *dansk*.
- Danish doesn't use all capitals, with three exceptions kept as the scooter or Volt shows them: **WALK, ECO, DRIVE, SPORT**, the badge **LAB**, and acronyms **PIN, USB, GPS, GPX, CSV, NAVEE, TCS**. Where Volt upper-cases at run time, Æ Ø Å stay those letters. *Pinkode* takes a capital only at the start of a label.
- A compound starting with a name keeps its capital: *NAVEE-appen*, *Volt-widget*, *Android-indstillinger*.

## 4. Numbers, dates and units

- **Decimal comma**, in text you write: *2,5* and always a zero before it: *0,5*. Thousands group with a **period**: *1.234*, *12.345*. Version numbers keep their periods: *Version 8.5*. Volt's `Fmt` formats live numbers for the phone's region, so its output can differ; never rewrite a placeholder's number. Never U+202F: Saira has no glyph for it.
- **Percent always has a no-break space (U+00A0):** `%1$s %%` gives *85 %*; `formatted="false"` text writes `100 %`. Microsoft writes *50 %*. A normal space would let the sign wrap onto its own line. A compound takes a hyphen and no space: *25%-grænse*; prefer rewriting it.
- **Number and unit** take a no-break space where the English has a space: *25 km/h*, *12 V*. Where the English has no space, follow the source template unless it would read wrong; don't add repeated spaces.
- Unit symbols stay as written: **km/h, mph, km, mi, V, A, Wh, kW, °C, h, min, s, %**. Unit **words** are translated: *kilometer, minutter, timer, volt*.
- **Trap: km/t and t.** Danish retailers and press write *km/t*, and *t* for hours (NAVEE's Danish retailers do too). The project default keeps the SI symbols, so write **km/h** and **h**. This is a debate item: see section 8.
- **Trap: *mil*.** A Danish *mil* is an old unit of about 7.5 km, and a Scandinavian one is 10 km. The English unit is always **miles** (the word) or **mi** (the symbol); bare *mil* is forbidden.
- Time: 24-hour. Dates and times come from Volt's formatters; don't hard-code an order and don't rewrite their output. Android's Danish writes *14.30*, Microsoft *14:30*. In text you write, use the form the English has around a placeholder and write *kl. %1$s*. A date in prose reads *5. marts 2026*, with a period after the day. Months and weekdays are lower case: *jan., feb., mar., apr., maj, jun., jul., aug., sep., okt., nov., dec.*; *man., tirs., ons., tors., fre., lør., søn.*
- Plurals are `one` and `other`. `one` is only exactly 1 for a whole number, and Volt's plural counts are whole numbers. If a plural ever takes a decimal, CLDR's Danish rule (`n = 1 or t != 0 and i = 0,1`) also selects `one` for any fraction below 2, such as *0,5* or *1,5*. *1 tur / 2 ture*, *1 minut / 2 minutter*, *1 time / 2 timer*. **Løbehjul doesn't change:** *1 løbehjul / 2 løbehjul*; only the definite differs: *løbehjulet / løbehjulene*. A symbol doesn't inflect: *2 km*, *2 h*.
- Digits in the English stay digits, including small numbers Danish prose might spell out.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never a break in the middle of a word and never smaller text. Measure with Saira, don't count letters. Budgets are single-line: a 64dp Quick access name may wrap at a space into two lines, but **each word must fit the line alone**, so Danish's long compounds fail where English passes. Try, in this order:

1. **Drop what the screen already supplies.** *Batteriindstillinger* → *Batteri* (it is listed among settings). *Køreindstillinger* → *Kørsel*. *Anslået rækkevidde* → *Rækkevidde*. *Lang rækkevidde* stays: there it is the name.
2. **Use the shorter first part of a compound.** *Opladnings-* → **Lade-**, which Danish already has (*ladekabel, ladetid, ladestation*): *Opladningsgrænse* → *Ladegrænse*, *Planlagt opladning* → *Ladeplan*, *Opladningsbesked* → *Ladebesked*. Never *Opl.*
3. **Split a compound with a preposition, so a space lets it wrap:** *Navn på løbehjul* (*Navn på* / *løbehjul*) rather than *Løbehjulets navn*, whose first word can't take a 64dp line.
4. **Use an abbreviation from this table.** It ends in a period, which counts toward the width, is cut after a consonant at a compound joint or syllable, and never cuts inside a safety word, with one exception: *Eksperim.* in the table, the only form of *Eksperimentel* that fits 64dp. Microsoft allows only abbreviations from Danish dictionaries; the table's *Own cut* rows go further because the ladder needs them, and each needs a native's check. **Nothing else:** no invented cuts.

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Maksimal hastighed | **Maks. hastighed**, then **Maks. hast.** | 116.7 → 95.1 → 65.8 | The setting. Only *Maks. hast.* fits the 90dp tile on one line. *maks.* is the dictionary form. *hast.*: own cut |
| Topfart | none | 42 | Measured value. Already short |
| Gennemsnitshastighed | **Gns. hastighed**, then **Gns. hast.** | 133.8 → 86 → 56.7 | *gns.* is the dictionary form |
| Kilometertæller | **Km-tæller**, then **Total** | 90.4 → 57.8 → 28.3 | English *Odo.*. *Km-tæller* is a short word, no period. *Total* only where the screen shows it is the total distance |
| Indstillinger | **Indst.** | 69 → 32.4 | Own cut. Fits the 62 and 64dp budgets; the full word doesn't (66.3 at 12sp) |
| Batteriindstillinger | **Batteriindst.**, then **Batteri** | 108.5 → 71.8 → 39.8 | At 64dp only *Batteri* fits (*Batteriindst.* is 69 at 12sp) |
| Køreindstillinger | **Køreindst.**, then **Kørsel** | 95.5 → 58.9 → 36.4 | |
| Appindstillinger | **Appindst.** | 91.9 → 55.3 | **-indst.** only with these |
| Eksperimentelle funktioner | **Eksperimentelle**, then **Eksperimentel**, **Eksperim.** | 157 → 93.4 → 83.1 → 56.9 | On the 90dp tile use *Eksperimentel*. At 64dp use *Eksperim.* (54.6 at 12sp). Own cut, for a native's check; that rung is also the screen-reader name, so flag it. Never *Forsøg*, which is the try word and reads as an invitation. Never *Eksp.* or *Eksper.* |
| Energigenvinding | **Energigenv.**, then **Genvinding** | 101.9 → 68.9 → 65.7 | Prefer *Energigenv.* wherever it fits. *Genvinding* alone first reads as recycling, so it is only the rung for a 64dp budget (63 at 12sp, *Energigenv.* 66.1), beside the battery or motor. Own cut |
| Starthastighed | **Starthast.** | 86.5 → 57.2 | Own cut |
| Opladningsgrænse | **Ladegrænse** | 109.4 → 71.8 | A whole word, no period |
| Opladning færdig senest | **Færdig senest**, then **Senest kl.** | 142.4 → 82.1 → 55.9 | *Senest kl.* only for the time setting, see 7 |
| Anslået rækkevidde | **Rækkevidde**, chip **ansl.** | 114.2 → 70.8 | English *est.* is **ansl.** on a chip |
| Temperatur | **Temp.** | 67 → 34.6 | |
| Hastighed | **Hast.** | 59.4 → 30.1 | |
| Sikkerhedskopi | **Backup** | 87.6 → 42.9 | A short word. Fits every budget here, *Sikkerhedskopi* only 90dp |
| maksimum, minimum | **maks.**, **min.** | | *maks. 25 km/h*. Beside a time *min* is the unit minutes, with no period; never let *min.* sit by a number where it could read as minutes |
| klokken | **kl.** | | *kl. %1$s* |
| cirka | **ca.** | | With the period |
| for eksempel, det vil sige, og så videre, blandt andet, på grund af, eventuelt, henholdsvis, nummer, inklusive | **f.eks., dvs., osv., bl.a., pga., evt., hhv., nr., inkl.** | | Microsoft's list, except *inkl.* (memory). Never *etc.* in a label |

**Rejected cuts:** *Kap.* for capacity means chapter (*kapitel*), and *Kapacitet* (55) fits anyway. *Opl.* could be *opladning, oplader, oplåst, oplysning*. *Eksp.* reads as *ekspedition*, *Eksper.* as *ekspert*. *Komp.* could be *kompatibilitet, kompressor*. *Op.* is not unlocked, *Lås.* is not locked.

Measured words that fit as they are: *Lås* 20, *Batteri* 39.8, *Ture* 26.1 (under the 62dp tab budget); *Linjelys* 44.7, *Stjernelys* 58.8, *Lys* 20.4, *Livedata* 49.5 (under 64dp at 12sp too); *Mine løbehjul* 78.3 (under the 90dp tile, or wrapped at 64dp, *Mine el-løbehjul* is 93.1 and doesn't fit the tile); *Ladeplan* 53.3, *Færdig senest* 82.1 and *Senest kl.* 55.9 (under the 90dp tile); *Display og lyd* 81.9, *PIN og nærhed* 86.5 (under 90dp); *Data & historik* 86, where *Data og historik* is 91.4, so it takes `&` on one line and *og* where it wraps.

Fit problems to record in notes rather than force: at 64dp at 12sp, *Indstillinger* (66.3) and *Eksperiment* (70) don't fit alone. *Om og tilladelser* is 97.6 at 12.5sp, over the 90dp tile on one line, so About & permissions becomes *Om appen* (59). *Navn på løbehjul* is 96.9 at 12.5sp, over 90dp on one line, so use it wrapped or just *Navn*. The full rung also serves as the screen-reader name, so an abbreviated full rung is read as written: flag it.

**Never abbreviate:** *Lås, Lås op, Låst, Oplåst, Ukendt, Aldrig, Altid, Alarm, Ikke verificeret, Ikke bekræftet, Ikke testet* (the only shorter form is *Utestet*), *PIN*. *Oplåst* never shortens to *Op* or *Åben*, and *Lås op* never loses *op*. If one doesn't fit, report it. Falling back to English is the safe outcome.

## 6. Safety wording

Use exactly these words. They match `l10n/glossary/da.tsv`; no synonyms.

| English | Danish | Rules |
|---|---|---|
| Lock (action, button, card title) | **Lås** | Verb *låse, låser*, in progress *Låser…*; noun *låsen, låsning*. *Lås løbehjulet* |
| Locked | **Låst** | Same form with *en*- and *et*-words; plural *låste* |
| Unlock (action) | **Lås op** | *låse op, låser op, låses op*, *Låser op…*; noun *oplåsning*. With an object it is always **Lås op for løbehjulet**; never split it (see below) |
| Unlocked | **Oplåst** | Plural *oplåste*. Android's Danish has it. *Løbehjulet er oplåst*. Never *ulåst*, *åben*, *åbnet*, *fri*. In a sentence about the state write *er oplåst* or *blev oplåst*, not *er låst op*: it starts with the Locked word and can wrap as *Låst / op*. *Ulåst* is correct Danish, and Android also uses it, but it is one letter from *Låst* at a glance |
| Lock state unverified | **Låsestatus ikke verificeret** | Must never read as confirmed. Keep *ikke*. Not *Uverificeret*: a separate *ikke* can't be misread at a glance |
| Lock state unknown | **Låsestatus ukendt** or **Ukendt låsestatus** | Must never read as confirmed |
| Unknown (alone) | **Ukendt** | *Ukendt* with *en*- and *et*-words: *Ukendt tilstand, ukendt løbehjul*. Plural *ukendte* |
| Unverified | **Ikke verificeret** | *ikke verificerede*. Not *ikke bekræftet*, which is the separate status below |
| Never | **Aldrig** | Never a bare *ikke* where the English says never |
| Always | **Altid** | *Always on*: **Altid tændt**, for a light |
| Alarm | **Alarm** | *alarmen, alarmer*. Only for the sounding alarm. Never *advarsel* or *besked*: they stay distinct |
| Alarm sound | **Alarmlyd** | |
| Alert (a level the rider chose) | **Besked** | Charge alert: *Opladningsbesked*, short *Ladebesked*. Battery alerts: *Batteribeskeder*. Notify me: *Giv mig besked*. Not *Opladningsadvarsel*, which reads as something wrong with the charging. **Debate item:** Android's word for a notification is *notifikation*, which is long |
| Low battery alert | **Advarsel om lavt batteri** | A real warning about the battery, so it keeps the word. Short: *Lavt batteri* |
| Warning (a fault the scooter reports) | **Advarsel** | Only for the scooter's own warnings and the row above |
| Stop (a sounding alarm) | **Stop** | Never *Annuller* on an alarm. *Stop watching*: *Stop overvågning*. Other stops: see 7 |
| PIN | **pinkode**, *pinkoden*, Android's word, or **PIN** alone on a rung | Not *kode* alone, not *adgangskode*. A password is **adgangskode**; they stay separate |
| Handlebar PIN | **pinkode på styret** | Short: *PIN på styret* (76). Not *Styr-PIN*, not *reserve-PIN* |
| PIN unlock | **Oplåsning med pinkode** | Short: *PIN-oplåsning* (82.2) |
| Owner key | **ejernøgle**, *ejernøglen, ejernøgler* | A secret. "Not a password": *Ikke en adgangskode*. Bare *nøgle* never, it reads as a physical key |
| Charge limit | **Opladningsgrænse** | Short: *Ladegrænse*. Never *opladningsloft*, *maks. opladning*. Keep distinct from *Hastighedsgrænse* |
| Experimental | **Eksperimentel, eksperimentelt, eksperimentelle** | *Eksperimentelle funktioner* is the section. The adjective agrees with its noun: *eksperimentel funktion*, *eksperimentelt forsøg* |
| LAB badge | **LAB** | TalkBack speaks *Eksperimentel* |
| Untested | **Ikke testet** | *ikke testede*. Compact only: *Utestet*. Never *ikke verificeret*: a different status |
| Unconfirmed, Not confirmed | **Ikke bekræftet** | Both English words. *ikke bekræftede*. In a sentence *blev ikke bekræftet*, *kunne ikke bekræftes*. Never *Ubekræftet*, one letter from *Bekræftet*, the opposite status on the same screens. Never shorten to *Bekræftet* |
| Confirmed | **Bekræftet** | The passive *blev bekræftet* is the form for a sentence and for a `%1$s` of unknown gender: *%1$s blev bekræftet.* |
| not affiliated with NAVEE | **ikke tilknyttet NAVEE** | In a sentence: *Volt er ikke tilknyttet NAVEE.* Never soften: no *uofficiel*, *uafhængig af*, *ikke direkte tilknyttet* |

Words that look right and mean something else, or that blur a state:

- **Lås vs Lås op.** The only difference is a separate word. **Never write *Lås løbehjulet op*, *Lås det op* or *Lås den op*:** natural Danish, but the *op* sits at the far end, and the check reads the start as a lock term, so it passes a Lock string and fails an Unlock one. Write *Lås op for løbehjulet*. Keep *op* on the same line as its verb in every form (*lås op, låse op, låser op, låses op, låst op, låste op*): use a no-break space (U+00A0) where a label can wrap between them.
- **Låst vs Oplåst** are clear. **Ulåst** is the everyday word for unlocked, and it is banned here only because it is one letter from *Låst*. *Ubekræftet* is banned for the same reason. *Utestet* is accepted on the compact rung only: the nearest positive, *Testet her*, keeps *her* and sits on another screen.
- **Removal is not unlock.** *Fjern låsen*, *Deaktivér låsen*, *Slå låsen fra* say the lock is gone, not that the scooter is unlocked. Only use them where the English does. **No check catches this in Danish**, so the reviewer must.
- **Til** is *on* and also the preposition *to*. **Fra** is *off* and also *from*. So *Lås: fra* is a state; use it only where the English has it. On and off: settings **Til / Fra**, as Android has it, lights **Tændt / Slukket**, never *Ja / Nej* in their place. **No check compares on and off in Danish**, so read them back.
- **Oplader** means both *the charger* and *is charging*. A bare label says which: *Oplader…* with the ellipsis is charging; the device takes *Opladeren*, or *Oplader* only in a list of devices. **Opladet** (charged), **Opladning** (the noun) differ by a letter or two; pick the state the English states. *Fuldt opladet* only when the English says full. **Never bare *fuld* for a battery:** it first reads as drunk. *Fuld sikkerhedskopi* is fine.
- **Senest** means both "last" and "by the latest". *Senest opladet* is "last charged", so the feature Charge done by is **Færdig senest**, never *Opladet senest*. *Senest kl.* means by the latest only because *kl.* and a time follow it, so it is valid only on the time tile. See 7.
- **Kontrol** means both control and **check**: *Eksperimentelle kontroller* reads as experimental inspections, so controls are **funktioner**. A check is **tjek**.
- **Aktuel** means current, not actual; **faktisk** is actual. **Eventuelt** means possibly, not eventually.
- **Afbryd** ends a connection, and can also read as interrupt. **Cancel** is **Annuller**; don't swap them.
- **Mål** is measure (imperative) and also goal. A button *Mål* is fine beside the charger; in a sentence write *Mål opladeren*.
- **Par** is pair (imperative) and also a couple. Give it an object where you can: *Par løbehjulet*.
- Keep every negation: *ikke, ingen, intet, aldrig, uden*. Prefix *u-* counts only for the listed words (*ukendt, ugyldig, umulig, uventet, ufuldstændig, utilstrækkelig*). Don't add negation the English lacks.
- Don't add reassurance or warnings the English doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **The scooter is løbehjul**, everywhere in the UI: *løbehjulet, løbehjulets, løbehjulene*. NAVEE's V40 EU manual has *Løbehjulets ringklokke* (search snippet only), and the Danish manuals read here say *løbehjul(et)* nearly every time. **El-løbehjul** (with the hyphen, NAVEE's and Elgiganten's spelling; the press also writes *elløbehjul*, which the glossary also accepts) is only for a sentence that introduces the product or where the English says electric. Don't mix within a string. The spelling is a debate item: it hasn't been checked against Retskrivningsordbogen. **Never *scooter***, which in Danish is a motor scooter. This is the first question for the debate.
- **Three speeds:** the setting is **Maksimal hastighed**. A limit below it is **Hastighedsgrænse** (*Egen hastighedsgrænse*, *Hastighedsgrænse for køretilstand*). The value measured on a trip or ride is **Topfart**. Never swap them. *Topfart* is this project's convention, not a Danish distinction: Danes also say *en topfart på 25 km/t* for the spec, so the screen does the separating. Never *Tophastighed*, a third word that the press uses for the spec. **Fart** is only in *Topfart* and *Fartpilot*.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. The `top-speed` and `maximum-speed` glossary rows already carry D3's meaning, so following them on today's English would swap the setting and the measured value. Flag those keys in your notes; a judge answers fallback. `rides_tile_max_speed` ("Max speed", measured) is not held: it is **Topfart**.
- **Rides vs trip:** a recorded ride is a **tur** (*Ture* on the tab; *tur, turen, turene*). Trip, the distance since a reset, is **trip**, as on car displays: *Trip*, *triptæller*. A path on the map is a **rute**.
- **Try vs retry vs test:** Try is **Afprøv**, one attempt on a value that can be set back; its results and titles use **forsøg**. An ordinary **Try again** is **Prøv igen**, Android's phrase, so it can't read as an experimental try. In a sentence: *for at prøve igen*, *prøv senere*, *prøv et andet sted*, and *Luk optagelserne, og prøv igen at sikkerhedskopiere* for "try the backup again". Bare *prøv* is not a glossary form, so *Prøv %1$s* fails on an experimental Try. The one exception is `more_lab_try_again_value`, which is that kind of try: *Afprøv igen: %1$s*. A guided test is **Test** or *test*. *Test scheduled charging* is *Test planlagt opladning*. A compatibility check is **kompatibilitetstjek**. *Controls to try* is *Funktioner at afprøve*.
- **Other stops:** telling the rider to stop the scooter is **Stop løbehjulet**, ending a setup is **Afbryd opsætningen**, and everything else, recordings and checks included, is **Stop**.
- **Restore vs set back vs reset:** bringing back a backup or a saved layout is **Gendan**. Undoing a try is **Sæt tilbage**, and so is every restoration in the compatibility strings: *Sæt tilbage til %1$s*, *Sat tilbage og bekræftet*. Keep *sæt tilbage* together, with no word between. Its noun is **tilbagesætning**: *Volt kunne ikke sende tilbagesætningen*; *Kunne ikke begynde at sætte tilbage*. A counter reset is **Nulstil**: *Kilometertæller nulstillet*. Energy recovery (**Energigenvinding**) is unrelated to all three.
- **Readback:** **tilbagelæsning**, a neutral word that can also fail. Never a word holding *bekræftelse*.
- **Rejected, refused:** **Afvist** for a value, **Afvist af løbehjulet** for the try.
- **Pair vs bind vs connect:** Bluetooth pairing is **par, parre, parring**, Android's word. NAVEE account binding is **tilknyt kontoen**. A live link is **forbind** (see Fixed words); ending it is **afbryd**.
- **Settings vs setup:** settings are **indstillinger**; a one-time setup is **opsætning**. Owner setup is **ejeropsætning**, its file **ejeropsætningsfil**, on onboarding steps and buttons **ejerfil**. `navee-owner-setup.json` stays as written. Website button names stay in English until the website changes, so quote them in English.
- **Dashboard vs display:** Volt's Home layout is **Oversigt**. The scooter's own screen is **løbehjulets display**, *displayet* (the manuals' word). They never swap. *Instrumentbræt* is out: it reads as the scooter's. The overview widgets on that screen never use *oversigt*, or the Oversigt screen would hold an Oversigt widget: *Scooter overview* is **Løbehjulsinfo**, *Battery overview* **Batteriinfo**, *Trip overview* **Tripinfo**. Not *Batteristatus*, which is the reading *Battery status*.
- **Roles:** owner is **ejer**, a role and never a credential, so *Connect as the owner* doesn't become *ejernøgle*. Shared rider is **medbruger** (*medbrugeren, medbrugere*); its credential is **medbrugernøgle** where the English says shared-rider key. The compact role label (`more_role_shared`, English *Shared*) is **Delt**, and *Shared access* is **Delt adgang**. The rider is otherwise *du*; where a word is needed use *fører*, the road-traffic word. `compat_evidence_shared` and `compat_report_privacy` are not the role.
- **Charge done by:** the feature is **Opladning færdig senest**, short **Færdig senest**; its time setting is **Færdig senest kl.**, short **Senest kl.**. The feature only plans a start, so never word it as a charge that has finished. Bare **Færdig senest** is for labels and tiles only. In a sentence write ”Færdig senest” in quotes or *funktionen Færdig senest*, and never lower-case *færdig senest*: *Funktionen Færdig senest er slået fra*. Scheduled charging is **Planlagt opladning**, short *Ladeplan*; charge start is **Ladestart**. Time to full is **Tid til fuld opladning**; before a value **Fuldt opladet om**, short **Opladet om** (66.8): *Opladet om 2 h*. *Time to full (est.)* is *Tid til fuld opladning (ansl.)*, narrower *Opladet om (ansl.)* (105), and *To full (est.)* is *Opladet om (ansl.)*. The alert level *Full* is **Fuldt opladet**, and *Charging to full* is *Oplader til fuldt batteri*.
- **Assists:** Walk Assist is **Skubbehjælp**, the motor helps while you push; WALK, the drive mode, stays. Power Assist is **Motorassistance**, hill descent control **Nedkørselshjælp**. Translate from the label, not from the key.
- **Motor modes:** Traction control is **Antispin**, the Danish car word. Overspeed active control is **Aktiv overhastighedsbremsning**, since it brakes the motor, *kontrol* would mean a check and *beskyttelse* adds reassurance the English lacks; *Overspeed control* is **Overhastighedsbremsning**. The glossary keeps the *-beskyttelse* forms as a fallback until the debate. Cruise control is **Fartpilot**, as the Micro and Evobike manuals say. Eco and Turbo acceleration profiles, WALK, ECO, DRIVE, SPORT stay in English, and *Boost* stays too. A mode word joins with a hyphen so the name stays intact: *Sport-tilstand*, *ECO-tilstand*, *Boost-tilstand*. Drive mode is **køretilstand**, *tilstand* where the screen is about modes. **Eco-estimat** is a range option, translated.
- **Range:** *rækkevidde*, *Anslået rækkevidde*. **Throttle** is *gashåndtag*, **brake lever** *bremsegreb*, **handlebar** *styr*, should a string need them.
- **Lights:** *Tail light* **Baglygte**, *Headlight* **Forlygte**, *Daytime running light* **Kørelys**, *Automatic lights* **Automatisk lys**, *Turn signal* **Blinklys**, sound *Blinklyd*. *Ambient light* **Stemningslys**.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Hurtig adgang**, short *Genveje*. Never *Kvikindstillinger*: that sounds like Android's pull-down panel, and Settings is itself one of the shortcuts.
- **Android's own words:** *Indstillinger*, *USB-fejlretning*, *Indstillinger for udviklere*, *Buildnummer*, *Om telefonen*. Makers rename these, so write generally where you can.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.

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
12. **Retired English terms.** Don't render owner identity, setup key or scooter key as their literal words: they are all the owner key. Don't render "speed cap" literally.
13. **"Dashboard" in the English.** Until the English fix lands, some strings say "dashboard" for the scooter's own screen. Volt's Home is one thing and the scooter's display another: translate what the string means, using the glossary's scooter-display word when the note or screen shows it is the physical display.

### Fixed words

| English concept | Meaning | This language |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Prøv igen**; *Prøv igen om lidt*. Also the button. Never *Afprøv igen*, except the one key in section 7 |
| could not confirm that… | a negated confirm or verify, rule 3 | **om**, never *at*: *Kunne ikke bekræfte, om planen blev slået fra.* *at* presupposes that it happened |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **ingen data**. Says only that no value came, not that anything is off or fine. Never *ikke rapporteret* or *ikke angivet* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **angivet** (*angivne* in the plural); verb **angive**: *løbehjulet angiver*, *Angivet af løbehjulet*. A claim, not a measurement. Never *rapporteret*, *målt* or *bekræftet*. *rapport* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **NAVEE-aktivitetslog**; *logning* for the act. Never *registrering*, which reads as signing up for an account |
| restore logging | turning that logging back to how it was (usually off) | **gendan logindstillingen**. Means putting that setting back as it was, usually off. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **Volts hjælpeprogram**; *hjælpeprogrammet* once the screen makes clear which. Never *assistent*. The script on the computer is the different **aflæsningsscript** |
| stock charger | the charger that came with the scooter | **original oplader**, *den originale oplader*. Never *standard-* |
| high-speed charger | a faster charger the rider may own | **hurtigoplader**; alone, *Hurtig*. Never *højhastigheds-*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **hul**: *hul i optagelsen*. Means no data was recorded, not that power or the ride was cut. Never *afbrydelse* |
| elevation | height above sea level on a route | **højde** |
| current direction | which way battery current flows | **strømretning**. Never bare *retning*, which reads as a route |
| throughput | how much energy has passed through the battery | **energigennemløb**; *gennemløb* once the screen has said energy. An amount of energy that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **batteriindikator**; *indikator* alone where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **rå**: *rå værdi*; as a tag **RÅ**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Sidst set**; with a value, *Sidst set %1$s*. The data may be old, so never word it as current. Not *Sidst kendt*, which is the last known state |
| add | adding a widget, scooter or item | **tilføj**: *Tilføj widget*, *Tilføj et løbehjul mere* |
| enter (typing) | typing a value | **indtast**: *Indtast en ny pinkode*. Never *angiv*, which is the reported word above |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, common gender: *appen*, *NAVEE-appen*, *den officielle app*. Never *applikation* or *program* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The button is **Forbind**. In a sentence, **opret forbindelse til løbehjulet**: *Opret forbindelse til løbehjulet først*. In progress *Forbinder…*. State *Forbundet*. Plugging in says **tilslut**: *Tilslut opladeren* |
| set a value | one verb for setting a value on the scooter | **indstil**: *Indstil pinkode*, *Indstil %1$s*. Never *sæt* alone, which sits too close to *Sæt tilbage*. The noun stays *indstilling* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | *bliver* + participle, in dialogs and hints alike: *Denne tur bliver slettet*, *Kortet bliver åbnet*. Never *Slet…* or *Åbn…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Sikkerhedskopiering og gendannelse*. If the title is an action with no good noun, the imperative, as Danish software does: *Test planlagt opladning* |

## 8. Sources consulted

**NAVEE's official Danish wording: essentially not found.** NAVEE publishes a Danish manual ([V40i Pro / V50i Pro DK](https://service.naveetech.com/wp-content/uploads/2024/07/V40i-Pro-V50i-Pro-DK.pdf)), seen in search, but it returned 404 on both WebFetch and a direct download, as did the same name on naveetech.com. The only official Danish words seen are two search snippets of the multilingual [V40 EU manual](https://service.naveetech.com/wp-content/uploads/2024/07/NAVEE-V40-Instruction-Manual-EU-version.pdf), also 404 when fetched: *El-løbehjul* and *Løbehjulets ringklokke*. The official app's Danish strings weren't available, and no APK was downloaded. Every term for lock, PIN, charge limit, owner key or experimental features is Claude's proposal, and the official wording may differ.

- Other makers' Danish manuals, read in full as text, the best evidence here: [Micro Merlin](https://www.micro-mobility.com/resources/public/lava3/media/micro_merlin_manual_danish.pdf) (*løbehjul(et)* 82 times, *fartpilot, oplader, displayet, speederen, sporttilstand, eco-tilstand, maksimal hastighed, baglys*), [Evobike Desert](https://www.evobike.se/pub_docs/files/Elscooter/Manual_Evobike-Desert_DA.pdf) (*el-løbehjul, løbehjulet, rækkevidde, hastighedsgrænsen, gashåndtag, forlygte, baglygte, eco-tilstand, fodgængertilstand*), [Audi](https://my-egret.com/media/7a/89/33/1664359700/Audi_Pro_Betriebsanleitung_DA_20220721.pdf) (*kick-løbehjul(et), el-løbehjul, gashåndtag, fodgængertilstand, sportstilstand, ladetilstand, låsen*).
- Retailers and press on NAVEE, weaker: [Mobilsiden, elløbehjul](https://mobilsiden.dk/nyheder/elloebehjul-den-smarte-genvej-i-hverdagen/) (*elløbehjul, tophastighed* as the spec, *km/t, rækkevidde, låse elløbehjulet elektronisk*); [Mobilsiden, N65i II](https://mobilsiden.dk/?p=243002). [Elgiganten's NAVEE pages](https://www.elgiganten.dk/brand/navee) refused the fetch (429), so search snippets only: *el-løbehjul*, *Bluetooth-nærhedslåsning*, *km/t*. That is the source for the proximity idea, though the guide says *oplåsning*, since retail's *-låsning* says lock.
- Language norms: [Microsoft Danish Localization Style Guide](https://download.microsoft.com/download/8/f/9/8f99052e-43df-49ac-a877-b8364e2331d7/dan-dnk-StyleGuide.pdf), read directly (4.1.1 abbreviations and list, 4.1.6 capitalization, 4.1.7 compounds, punctuation including the comma between imperatives, UI ellipsis, ”…”, thousand and decimal separators, *50 %*). The *-ér* accent rule is from Microsoft's [note on the Danish guide](https://learn.microsoft.com/en-us/archive/blogs/terminology/danish-and-ukrainian-style-guides-recently-updated), search snippet. It names Retskrivningsordbogen and Nudansk Ordbog as its norms; those and Dansk Sprognævn were **not read directly**, and the rules on *maks., gns., ca., inkl.*, en dash, ’…’, date forms and the old *mil* come from memory. Microsoft prefers avoiding UI abbreviations; Volt's ladder needs them, so section 5 goes further than it does.
- Android's own Danish, read from AOSP: [SettingsLib da](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-da/strings.xml) (*USB-fejlretning, Indstillinger for udviklere, Par, Afbrudt*), [Settings da](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-da/strings.xml) (*Buildnummer*, *pinkode* on 84 lines against *PIN-kode* on 1, *Oplåsningsmønster, oplåst, låst op*, *ulåst*), [core da](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values-da/strings.xml) (*notifikation*, *låst op*). *Om telefonen* comes from [TCL's Danish support](https://www.tcl.com/dk/da/support-mobile/faq/13210) and memory; TCL calls the build number *Byg nummer*, so names vary by maker. *Forbind* and *Opret forbindelse* as button and sentence forms come from memory of Android.
- **Judgment calls with no source**, general knowledge of Danish and car vocabulary: *ejernøgle, medbruger, afprøv* as the try word, *sæt tilbage, skubbehjælp, motorassistance, nedkørselshjælp, starthastighed, antispin, kørelys, trip, topfart, oversigt, færdig senest, besked* for a rider-chosen alert, *ikke tilknyttet*, *tilbagelæsning, hul, energigennemløb, aktivitetslog*, every *Own cut* in section 5. *Eksperim.* as the 64dp Experimental rung, *Senest kl.*, *Opladet om*, *tilbagesætning*, *overhastighedsbremsning*, *hast.*, *Km-tæller* and *Total* most need a native's check. *Optagelse* for a recording leans toward audio or video; the debate can revisit it.

**Debate items:** *km/t* and *t* instead of the project's *km/h* and *h*, since a Dane sees *km/h* as foreign but readable; *løbehjul* vs *el-løbehjul*; *Oplåst* vs Android's *Låst op*; quotes ”…” vs »…«; *besked* vs *notifikation*; *Fartpilot* is safe, *Antispin* vs NAVEE retail's *Traction Control*; *el-løbehjul* vs *elløbehjul*; *Højeste fart* for the measured value, since it clearly means reached rather than allowed; *Overhastighedsbremsning* vs the *-beskyttelse* forms; *Måleværdier* for readings, which says measured while *angivet* says a claim, against *Værdier* or *Data fra løbehjulet* (*aflæsninger* would need the capture row to move).

**Checks.** Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`, at 12.5sp unless noted. Glyphs: `fontmetrics.Font.missing` on all four Saira weights found nothing missing for æ ø å Æ Ø Å é ” ’ – … · U+00A0 % °. `lexicon.term_found` was run on the lock forms: *Lås op for løbehjulet* passes Unlock and fails Lock, and *Lås løbehjulet op* passes Lock and fails Unlock, which is the reason for the rule in section 6. The forms this guide mandates for retry, stop, restore, time to full, Charge done by and the overview widgets were run through `check._glossary` with the real glossary loader.
