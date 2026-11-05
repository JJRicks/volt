# Volt style guide: Croatian (hr)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in the last section; the three-vendor debate decides later. No native speaker has reviewed it, and it needs one. `ti` and `one, few, other` come from plan 7.4.

Write standard Croatian, not a Serbian or Bosnian variant. Latin script only.

| Use | Not | | Use | Not |
|---|---|---|---|---|
| računalo | računar, kompjuter | | datoteka | fajl |
| postavke | podešavanja | | izbornik | meni |
| zaslon | ekran, displej | | spremi | sačuvaj, snimi |
| vrijeme, mjerenje | vreme, merenje | | tko, što | ko, šta |
| tisuća | hiljada | | pogreška | greška |
| sigurnosna kopija | bekap, rezervna kopija | | spremit ću | ja ću da spremim |

Prefer an infinitive to *da* + present: *Možeš promijeniti*, not *Možeš da promeniš*. Prefer *trenutačni* for "current" (the state right now); *trenutni* means momentary.

## 1. Register and form of address

- Use **ti**, singular, always: *Poveži punjač*, *tvoj romobil*. Never *vi*, *Vi* or *vaš*, even though Android's own Croatian often uses *vi* in sentences (*Uparite novi uređaj*). No capital on *ti* or *tvoj*.
- Croatian drops the pronoun, and possessives are rarely needed. Write *Zaključaj romobil*, not *Zaključaj svoj romobil*, unless the English contrast needs it.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.

| Text type | Form | English | Croatian |
|---|---|---|---|
| Button, toggle, one-tap action | imperative, ti (the Microsoft guide's rule for a direct command, and Android's own: *Upari*, *Zaboravi*) | Save, Lock | Spremi, Zaključaj |
| Button that opens a flow (English has `…`) | verbal noun | Restore… | Vraćanje… |
| Instruction in a sentence | imperative, ti | Reconnect to verify. | Ponovno poveži romobil za provjeru. |
| Title, section header, tab | noun | Backup & restore | Sigurnosno kopiranje i vraćanje |
| What happened | participle or impersonal, with the thing as subject | Nothing was changed. | Ništa nije promijenjeno. |
| What will happen (rule 2 below) | future with *se* | Removes this ride. | Izbrisat će se ova vožnja. |

- **Keep the rider's gender out of it.** Second-person past forms carry gender (*zaboravio si*, *povezao si*). Write the event instead: *Romobil je povezan*, *Ništa nije spremljeno*. Role nouns (*vlasnik*, *ovlašteni korisnik*) are generic masculine and stay.
- **Future spelling:** a verb ending in *-ti* drops the *-i* when *će* follows: *Izbrisat će se*, *Otvorit će se*. A verb in *-ći* keeps its ending and stays separate: *Doći će*.

## 2. Punctuation and quotes

- Quotes: **„…”** first, **‚…’** inside. Never straight quotes, never the German-style closing „…“ (some manuals use it), never '…'.
- A sentence that names a button, tab or screen puts its exact label in „…”: *Dodirni „Dopusti”*, *otvori „Sigurnosno kopiranje i vraćanje”*. The label stays in its nominative inside the quotes, which frees the sentence from declining it. This departs from the Microsoft guide, which uses no quotes on control names, because Volt's labels can read as ordinary words. A feature mentioned in passing is not quoted and declines normally. Judgment call.
- A sentence-final period goes outside the closing quote unless the quote is a whole sentence.
- Ellipsis is the single character `…`, no space before: *Povezivanje…*
- Dash: en dash with spaces – for a break in a sentence; no space in a range: *10–20 km/h*. Keep the English's `·` separators as written.
- **Comma:** no comma before *i*, *ili*, *te* in a simple list. No serial comma. A comma comes before *ali*, *nego*, and after a leading subordinate clause: *Ako je romobil zaključan, otključaj ga.*
- *s* or *sa*: *sa* only before *s, š, z, ž* or a hard cluster (*sa mnom*). *k* or *ka*: *ka* only before *k, g*. Before a placeholder, you can't choose, so rephrase (rule 4).
- `&` is **i**: *Zaslon i zvuk*.
- A colon is followed by lower case unless a name follows: *Zadnje poznato: zaključano*. A bracket that is not a full sentence starts in lower case.
- Acronyms decline with a hyphen: *PIN-a*, *PIN-om*, *USB-a*, *GPS-a*. Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere:** button *Spremi kopiju*; label *Ograničenje punjenja*; title *Eksperimentalne funkcije*. Only the first word and names take a capital. English Title Case is an error (*Walk Assist* → *Pomoć pri guranju*).
- Feature names are lower case mid-sentence: *uključi zakazano punjenje*.
- Days, months and languages are lower case. An adjective from a country name is lower case.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS. Drive modes and level names are never translated and keep the case the English string uses in that string: *način Sport*, *ECO*.
- Where Volt upper-cases text, the diacritics stay: *ZAKLJUČANO*. Saira has them.

## 4. Names and placeholders

- **Names that must stay whole do not decline.** A `dnt` token must appear exactly as written, so Volt, NAVEE, Shizuku and model names go after a common noun, which declines for them: *u aplikaciji Volt*, *tvrtka NAVEE*, *romobil NAVEE*, *u aplikaciji Shizuku*, *model %1$s*. Never *Volta*, *Voltu*, *NAVEE-a*. *Volt* as a subject in the nominative is fine: *Volt nije povezan s tvrtkom NAVEE*. The exception is the hyphen form of PIN, USB and GPS, which leaves the token whole.
- **A placeholder can't decline.** Put the value in a label so it stands alone in the nominative:

| English | Croatian | Not |
|---|---|---|
| Keep %1$s | Zadrži vrijednost: %1$s | Zadrži %1$s |
| Changing to %1$s… | Mijenjanje vrijednosti: %1$s… | Mijenjanje na %1$s… |
| Last known lock state: %1$s | Zadnje poznato stanje zaključavanja: %1$s | |
| Connecting to %1$s | Povezivanje: %1$s | Povezivanje s %1$s |
| Starts at %1$s | Početak: %1$s | Počinje u %1$s |

## 5. Plurals

Give all three categories, and agree the adjective and verb too. **Keep `%1$d` in every category:** `one` also covers 21, 31 and 101.

| Category | Numbers | Examples |
|---|---|---|
| one | 1, 21, 31, 101… not 11 | 1 romobil, 1 minuta, 1 vožnja, 1 sat, 1 datoteka |
| few | 2–4, 22–24, 32–34… not 12–14 | 2 romobila, 2 minute, 2 vožnje, 2 sata, 2 datoteke |
| other | 0, 5–20, 25–30, 100, fractions | 5 romobila, 5 minuta, 5 vožnji, 5 sati, 5 datoteka |

More: *putovanje, putovanja, putovanja*; *postavka, postavke, postavki*; *widget, widgeta, widgeta*; *dan, dana, dana*; *sekunda, sekunde, sekundi*; *put, puta, puta*.

- Agreement changes with the count: *Preskočen je 1 romobil*, *Preskočena su 2 romobila*, *Preskočeno je 5 romobila*. Prefer a label that avoids it when the string is not a plural resource: *Romobili: %1$d*.
- Never copy "1 minutes" shapes from the English. `one` is grammatical even where a note says it can't be reached.

## 6. Numbers, dates and units

- Decimal comma, period for thousands, from four digits: *1.234,5*, *12.345,6*. Volt's `Fmt` formats live numbers; never rewrite a placeholder's number or add separators around one.
- Percent always takes a no-break space (U+00A0), never a plain space, between a number or placeholder and %: `%1$s %%` gives *85 %*. Saira has U+00A0 and lacks U+202F, so never use U+202F. The rule covers `formatted="false"` text too, whose literal is `100 %`.
- Numbers in the English stay as digits. Don't write them out or change them.
- Unit symbols stay and take the same no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *kilometri*, *milje*. Exceptions with no space: `°` for an angle.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5. ožujka*; a numeric date *5. 3. 2026.* with a final period and spaces. Time is 24-hour with a colon: *14:30*. Don't write *a.m./p.m.* A period after a number marks an ordinal: *2. korak*. Don't add a period after a placeholder for a count.

## 7. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way, never hyphenate to fit, and never shrink the text. Measure with Saira; don't count letters. Try these in order:

1. Drop what the screen already supplies: *Postavke baterije* → *Baterija*.
2. Use a shorter natural word with the same meaning: *Podaci i povijest* → *Podaci*.
3. Use an abbreviation from this table, ending in a period that counts toward the width. Nothing else: Microsoft's method is truncation with a period (*maks.*).
4. If nothing fits, write the correct word and say so in your notes.

**Real ladders:**

| English full → short → compact | Croatian full → short → compact | Width at 12.5sp (dp) |
|---|---|---|
| Total odometer → Odometer → Odo. | Ukupna kilometraža → Kilometraža → **ODO** | 116 → 70 → 26 |
| Charge limit → Charge limit → Limit | Ograničenje punjenja → **Ogr. punjenja** → Ograničenje | 123 → 77 → 69 |
| Scooter's estimated remaining range → Est. range → Range | Procijenjeni preostali domet romobila → **Procj. domet** → Domet | – → 72 → 38 |
| Current trip average speed → Trip avg. speed → Avg. speed | Prosječna brzina putovanja → **Prosj. brzina** → Prosj. brzina | – → 72 → 72 |
| Scooter's current trip time → Trip time → Time | Trajanje trenutačnog putovanja → Vrijeme puta → Vrijeme | – → 74 → – |

**Approved abbreviations:**

| Short | Means | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| **ODO** | ukupna kilometraža | 26 | The display label in Croatian scooter manuals; no period |
| **Maks.** | maksimalna | *Maks. brzina* 74 | Microsoft's own example. Setting only |
| **Prosj.** | prosječna | *Prosj. brzina* 72 | |
| **Procj.** | procijenjeni, procijenjena | *Procj. domet* 72 | With a following noun |
| **Temp.** | temperatura | 35 | |
| **Ogr.** | ograničenje | *Ogr. punjenja* 77 | Only with a following noun |
| **Zak.** | zakazano | *Zak. punjenje* 78 | Only in *Zak. punjenje*, for the 90dp `dashboard_model_tile_charge_schedule` tile |
| **Eksperim.** | eksperimentalno | 57 | *Eksperimentalno* is 98, over the 90dp tile. No plain shortening fits |
| **pribl.** | približno | | |
| **br.** | broj | | |
| **Info** | informacije, o aplikaciji | *Info i dozvole* 77 | A short word, no period |

**Refused:** *Zaklj.*, *Otklj.*, *Isklj.*, *Uklj.* (reads as *uključujući*), *Post.* (reads as post), *Bat.*, *Rom.*, *Min.* for minimum (clashes with `min`), *Vlasn.*, *Neprov.*, *Nepotv.*. If a word has no row here, don't invent one.

**Measured words that fit (12.5sp):**

- Under the 62dp tile: *Postavke* 53, *Baterija* 46, *Vožnje* 39, *Zaključaj* 51.
- Under the 90dp tile: *Moji romobili* 75, *Statistika uživo* 89, *Naziv romobila* 87, *Linijska svjetla* 86, *Zaslon i zvuk* 76, *PIN i blizina* 68, *Kraj punjenja* 77, *Vrijeme kraja* 77, *Vršna brzina* 73, *Maks. brzina* 74.
- Over 90dp, so take the listed short form: *Zvjezdana svjetla* 101 → *Zvjezdana*; *Postavke vožnje* 94 → *Vožnja*; *Postavke aplikacije* 110 → *Aplikacija*; *O aplikaciji i dozvole* 117 → *Info i dozvole*; *Maksimalna brzina* 110 → *Maks. brzina*; *Zakazano punjenje* 110 → *Zak. punjenje*.
- `onboarding_welcome_tile_lock` (62dp): *Zaključavanje* is 79, so this tile alone uses **Zaključaj** (51). Debate item: a verb on a feature tile, with *Brava* 34 and *Blokada* 47 as the alternatives; both add a second lock word, and *Brava* reads as a physical lock.
- `owner_shizuku_install` (48dp at 14sp): *Instaliraj* is 57 and there is no acceptable shorter word. Write it and report the overflow.

**Never abbreviate:** *Zaključaj, Otključaj, Zaključano, Otključano, Neprovjereno, Nepoznato, Nikad, Uvijek, Uključeno, Isključeno, Alarm, PIN, vlasnički ključ*. If one doesn't fit, write the whole word and report it.

## 8. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Croatian | Rules |
|---|---|---|
| Lock (action) | **Zaključaj** | Infinitive *zaključati* |
| Lock (noun, card title) | **Zaključavanje** | Never *Brava*, *Blokada* or *Lokot* |
| Locked | **Zaključano** | The neuter is the state label, with no gender to agree. In a sentence, *Romobil je zaključan* |
| Unlock (action) | **Otključaj** | Noun **Otključavanje**. Infinitive *otključati* |
| Unlocked | **Otključano** | In a sentence, *Romobil je otključan*. Never *otvoreno*, *slobodno*, *nezaključano* |
| Lock state unverified | **Stanje zaključavanja nije provjereno** | Must never read as confirmed. Keep *nije* |
| Lock state unknown | **Stanje zaključavanja nije poznato** | Must never read as confirmed |
| Unknown (alone) | **Nepoznato** | Agrees with a stated subject: *nepoznat*, *nepoznata* |
| Unverified | **Neprovjereno** | Agrees: *neprovjerena funkcija* |
| Never | **Nikad** | *Nikada* is allowed only where rhythm needs it. Never just *ne* where the English says never |
| Always | **Uvijek** | |
| Always on | **Uvijek uključeno** | Agrees with *svjetlo* |
| Alarm | **Alarm** | Declines *alarma, alarmu, alarmom*. Never *upozorenje*: alert is **Upozorenje**, and they stay distinct |
| Alarm sound | **Zvuk alarma** | |
| Stop (a sounding alarm) | **Zaustavi** | |
| Stop watching | **Prekini praćenje** | |
| PIN | **PIN** | Masculine: *PIN-a, PIN-u, PIN-om*. Plural *PIN-ovi*. Not *šifra* or *lozinka*; *PIN kod* is redundant |
| Handlebar PIN | **PIN na upravljaču** | A password is **lozinka**; keep them separate |
| PIN unlock | **Otključavanje PIN-om** | Held until D3 merges (see section 9) |
| Owner key | **vlasnički ključ** | A secret that opens access. Declines *vlasničkog ključa, vlasničkim ključem*. Never bare *šifra*. "Not a password" is *Nije lozinka* |
| Charge limit | **Ograničenje punjenja** | Never *limit*, *strop* or *granica* |
| Experimental | **Eksperimentalno** | Agrees: *eksperimentalna funkcija*. Experimental controls: **Eksperimentalne funkcije** |
| Untested | **Neisprobano** | Agrees: *neisprobana funkcija*. *Not* the same as *Neprovjereno* |
| Unconfirmed | **Nepotvrđeno** | |
| Not confirmed | **Nije potvrđeno** | |
| not affiliated with NAVEE | **nije povezan s tvrtkom NAVEE** | Footer: *Volt %1$s · nije povezan s tvrtkom NAVEE*. *povezana* with *aplikacija*. *tvrtkom* is required: without it *povezan* reads as "connected", which is true of the scooter. Never soften: no *neslužbeni*, *neovisan o*, *nije izravno povezan*, *nije službeno povezan* |

**Words that look right and aren't:**

- *Zaključano* and *Otključano* share the stem *-ključ-*; only the prefix differs. Keep both whole. Don't describe an unlock as removing the lock (*Ukloni zaključavanje*, *Isključi zaključavanje*); say *Otključaj*, and use *isključi* only where the English itself says turn off.
- *Zaključi*, *zaključak* mean "conclude", "conclusion". The imperative is *Zaključaj*, with *-aj*. Never *Zaključi* for Lock.
- *Nezaključano* says only "not locked" and sounds like an oversight; it isn't our Unlocked. Use it nowhere unless the English says not locked, and then *Nije zaključano*.
- *Uključeno* and *Isključeno* differ by one sound. Settings and lights both use them; never invert them, never use *Da/Ne*, *Aktivno/Neaktivno* or short forms in their place.
- *Neprovjereno* (not checked), *Neisprobano* (not tried) and *Nepotvrđeno* (no readback) are three different words. *Nepoznato* says nothing was learned. Don't swap them.
- *Provjereno* and *Potvrđeno* are claims. *Provjeri* is a command to check. Don't let an unverified state turn into one of them by dropping *ne-* or *nije*.
- *Ne-* words are written together (*neprovjereno*), and *nije* is separate.
- Keep every negation. *ne, nije, nikad, nikada, bez, nema, ništa* count for the checks; the prefixes *ne-* and *bez-* count for a listed safety word. There is no removal list for Croatian, so the checks won't catch *Ukloni zaključavanje*. You must.
- Don't add reassurance the English lacks (*sigurno*, *zajamčeno*), and don't add warnings it doesn't have.

## 9. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't. *Romobil* is masculine and inanimate: *romobil, romobila, romobilu, romobil, romobilom*. Plain *romobil* alone is the app's word; *električni romobil* only where the English says electric. *Skuter* is a motorbike-style vehicle, so never use it.

- **Three speeds:** the setting is **Maksimalna brzina**. A limit below it is **Ograničenje brzine**. The measured value on a trip or ride is **Vršna brzina**. Never swap them. *Najveća brzina* isn't used, because it could mean either. **Debate item:** *vršna* is established for power (*vršna snaga*) and less so for speed; *Najveća postignuta brzina* is the long alternative.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. The rows `top-speed` and `maximum-speed` already carry D3's meaning, and these named strings still follow today's English meaning until D3 lands. Flag them in your notes; a judge answers fallback.
- **Rows held with D3:** the rows for D3's new terms (`pin-unlock`, `shared-rider`, `owner-file`, `controls-to-try`, `lab-badge`, `eco-estimate`, `speed-limit`, `custom-speed-limit`, `drive-mode-speed-limit`, `maximum-speed-short`, `scooter-display`, `display-sound`) wait in `l10n/pending/glossary-d3/hr.tsv`. Use their words anyway.
- **Rides vs trips:** a recorded ride is a **vožnja** (tab: *Vožnje*). *Trip* (the scooter's own distance since a reset) is **putovanje**, shortened to **put** in narrow rungs: *Duljina puta*, *Vrijeme puta*. A path on the map is a **ruta**.
- **Try vs retry vs test:** Trying a control is **Isprobaj**, noun **isprobavanje**. An ordinary retry is **Pokušaj ponovno**, never *Isprobaj*. A guided test is **test**, verb **testiraj**. *Test scheduled charging* is a check: **Provjeri zakazano punjenje**. A compatibility check is **provjera kompatibilnosti**.
- **Dashboard vs display:** Volt's Home layout is the **Nadzorna ploča** (90.1dp, so it overflows a 90dp tile by a hair; use *Ploča* there). The scooter's own screen is the **zaslon romobila**. They never swap.
- **Settings vs setup:** settings are **Postavke**. A one-time setup is **postavljanje**.
- **Restore vs set back:** bringing back a backup is **Vrati**, noun **vraćanje**. Undoing a try is **Poništi promjenu**. Energy recovery is **rekuperacija energije**, unrelated to either.
- **Readback:** **povratno očitanje**, a neutral word, because a readback can also fail to confirm.
- **Rejected:** *Rejected* is **Odbijeno**; *Refused by scooter* is **Romobil je odbio**.
- **Pair vs bind vs connect:** Bluetooth pairing is **upari**, *uparivanje* (Android's word). NAVEE account binding is **vezanje računa**, verb *vezati račun*. A live link is **poveži**, *povezivanje*; ending it is **Prekini vezu**. Plugging in a charger is **priključi**.
- **Owner key parts:** the file is the **datoteka s vlasničkim ključem**, shortened to **vlasnička datoteka** on onboarding steps. Getting the key is **dohvat**, the computer's script is the **alat za dohvat**. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels are translated, unlike the drive modes, and agree with *rekuperacija*: *Isključena, Niska, Srednja, Visoka*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Eco procjena** is a range option, translated.
- **Drive mode:** **način vožnje**, with its name kept: *način Sport*. Never *stupanj vožnje* (one manual's word) or *brzina*.
- **Roles:** owner is **vlasnik**, shared rider is **ovlašteni korisnik**. The compact role label (`more_role_shared`, English *Shared*) is **Ovlašteni** (55dp), never *Dijeljeno*, which reads as a shared scooter.
- **Charge done by:** the feature name is **Kraj punjenja**, and its time setting **Vrijeme kraja punjenja**, short **Vrijeme kraja**. The feature only plans a start, so never word it as a charge that has finished. In a sentence, quote the name: *„Kraj punjenja” je isključen*.
- **Scheduled charging:** **Zakazano punjenje**. The start is **Početak punjenja**.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Brzi pristup**. Never *Brze postavke*: that is Android's panel.
- **Controls:** the section is **Eksperimentalne funkcije**, the card **Funkcije za isprobavanje**. *Kontrola* is kept for *kontrola proklizavanja* and similar systems, so *Eksperimentalne kontrole* would read as tests.
- **Motor and ride systems:** **tempomat**, **kontrola proklizavanja**, **rekuperacija energije**, **pomoć pri guranju** (Walk Assist), **pomoć motora** (Power Assist). The stored ids are not shown.
- **Parts:** handlebar **upravljač**, display **zaslon**, tail light **stražnje svjetlo**, headlight **prednje svjetlo**, turn signal **žmigavac**, brake **kočnica**, throttle **ručica gasa**, tire **guma**, charger **punjač**, battery **baterija**, range **domet**, voltage **napon**, current **struja**, power **snaga**.
- **Parked:** the meaning is stationary, so **u mirovanju**; *romobil mora mirovati*.
- **Firmware:** **firmver**, declined. Volt never changes it.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does: *Sadržaj je na engleskom*.
- **Experimental LAB badge:** **LAB** stays. TalkBack speaks **Eksperimentalno**.

## 10. Android's own wording

Quote Android's settings as Android writes them: **Otklanjanje pogrešaka putem USB-a**, **Opcije za razvojne programere**, **Broj međuverzije**, *Postavke › O telefonu*. Phone makers rename them, so write generally where you can: *u postavkama otvori podatke o telefonu*.

Everyday verbs: tap **dodirni**; press a physical button **pritisni**; save **spremi**; delete **izbriši**; remove **ukloni**; cancel **odustani**; done **gotovo**; OK **U redu**; share **dijeli**; skip **preskoči**; back **natrag**; next **dalje**; allow **dopusti**. Home tab **Početna**, More tab **Više**.

## Rules every language follows

These came from the independent review of the first full draft (Spanish, 2026-11-05). They override anything above that disagrees. The table at the end holds this language's fixed words; a term in it is used every time the concept appears, in every area.

1. **Try versus try again.** The glossary's "try" word is only for trying a control (Experimental, Controls to try). An ordinary retry ("Try again", "Try shortly") uses the fixed retry phrase below, so it can never read as "test this".
2. **Subjectless English verbs.** Dialogs and hints such as "Removes…", "Replaces…", "Opens…" describe what will happen. They must not read as a command to the rider, and a destructive confirmation must never sound like an instruction to do it. Use the form the table below gives.
3. **Unconfirmed clauses.** After a negated "confirm" or "verify" ("could not confirm that the schedule turned off"), use a form that does not presuppose the event happened: *Nije moguće potvrditi je li raspored isključen*, with *je li*, not *da je*. An unconfirmed state never reads as done.
4. **Placeholders.** No grammar may depend on a value you can't see: no "at %1$s" that changes with the hour, no elision, article, gender or case that a value would have to agree with. Prefer a label form: *Početak: %1$s*. Numbers and units after a placeholder follow this guide's spacing.
5. **Warnings keep the effect.** Where a feature's name replaced a word that explained the hazard (motor braking became energy recovery), a warning sentence still says what happens: it brakes with the motor, so the brakes work harder (*koči motorom, pa kočnice više rade*).
6. **Plurals.** Every category is grammatical, even where the English `one` is wrong or a note says it can't be reached. Never copy "1 minutes" into your language.
7. **Drive modes and level names.** Keep the case the English string uses in that string (Sport, ECO, Drive). Don't change it, and don't translate them.
8. **Fit.** Never swap in a different meaning, or a colloquial word, to fit a budget. Use this guide's accepted abbreviation. If none fits, write the correct word and say so in the reply's notes: an overflow is a design problem that gets fixed, a wrong word isn't noticed.
9. **Naming things on screen.** When a sentence names a button, tab or screen, use exactly that label's translation and this guide's rule for marking it (section 2). Don't paraphrase a label.
10. **Same concept, same word.** The same English on different screens gets the same translation unless the screen really changes the meaning. Readings appear under several keys (`readings_*`, `stats_row_*`, `compat_field_*`, `dashboard_*`): the name doesn't change between them.
11. **Opaque English.** If a word-for-word rendering would be unclear ("Connected history", "Charge per gauge", "Battery-side power"), translate the meaning given by the screen and note. Don't copy the shape of the English. "Reported" and "not reported" are never word-for-word calques: use the table.
12. **Retired English terms.** Don't render owner identity, setup key or scooter key as their literal words: they are all the owner key. Don't render "speed cap" literally.
13. **"Dashboard" in the English.** Until the English fix lands, some strings say "dashboard" for the scooter's own screen. Volt's Home is one thing and the scooter's display another: translate what the string means, using the glossary's scooter-display word when the note or screen shows it is the physical display.

### Fixed words

| English concept | Meaning | Croatian |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Pokušaj ponovno**; **Pokušaj ponovno za trenutak**. Never *Isprobaj*, which means testing a control. |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **nema podataka**, with the name as a label: *Baterija: nema podataka*. Never *nije prijavljeno* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | verb **javlja**: *romobil javlja*; as a label **prema romobilu**. A claim, not a measurement. Never *prijavljeno* or *potvrđeno*. **izvješće** stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **zapisivanje aktivnosti NAVEE**. Never *prijava* or *registracija*, which read as signing in |
| restore logging | turning that logging back to how it was (usually off) | **Vrati postavku zapisivanja**. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **pomoćni program Volt**. Never *pomoćnik*, a person. The computer's script is the different **alat za dohvat** |
| stock charger | the charger that came with the scooter | **originalni punjač**. Never *standardni* |
| high-speed charger | a faster charger the rider may own | **brzi punjač**; alone as an option, *Brzi*. Never about riding speed |
| gap (in a recording) | a stretch of a recording with no data | **prekid zapisa**. Means no data was recorded, not that power or the ride was cut |
| elevation | height above sea level on a route | **nadmorska visina**; narrow *Visina* |
| current direction | which way battery current flows | **smjer struje**. Never *trenutačni smjer* |
| throughput | how much energy has passed through the battery | **protok energije**; *protok* once the screen has said energy. An amount that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **prikaz baterije**; *prikaz* where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **neobrađeno**; as a tag **SIROVO** (37dp at 10sp, under the 40dp `stats_raw_chip`) where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Zadnje očitanje**; with a value, *Zadnje očitanje: %1$s*. The data may be old, so never word it as current. Not *zadnje poznato*, which is the last known state |
| add | adding a widget, scooter or item | **Dodaj**; infinitive *dodati* |
| enter (typing) | typing a value | **Unesi**: *Unesi novi PIN*. Infinitive *unijeti* |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **aplikacija**, always. Never *app*, *apk*, *program* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **poveži romobil**, *Najprije poveži romobil*, *Ponovno poveži romobil*. Never *poveži se*, which reads as going online. The button stays *Poveži*; plugging in says *priključi punjač* |
| set a value | one verb for setting a value on the scooter | **Postavi**, infinitive *postaviti*: *Postavi PIN*, *Postavi ograničenje punjenja*. *Promijeni* only where the English says change. Setting something back stays *Poništi promjenu* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Future with *se*: *Izbrisat će se ova vožnja*, *Otvorit će se karta*, *Zamijenit će se svi podaci*. Never *Izbriši…*, *Otvori…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Sigurnosno kopiranje i vraćanje*, *Postavke vožnje*. The button on that screen stays imperative: *Vrati*. Never an imperative as a title |

## Sources consulted

**NAVEE's official Croatian: nothing found.** Two searches aimed at NAVEE found no app, product page or manual in Croatian. NAVEE's public manuals that surfaced are in English or other languages. The official app's Croatian wording, if it has any, is unknown, so every term here is Claude's proposal and may differ from it. No APK was downloaded.

- [Microsoft Croatian Localization Style Guide](https://download.microsoft.com/download/0/6/2/0626b4d3-ed24-4c11-8676-ec5aa03576c7/hrv-hrv-StyleGuide.pdf), read in full text for these points: quotes „…”, no inverted commas; no-break space before % (*50 %*); imperative second-person singular for direct commands, nouns for commands that open a flow; no English capitalization; truncation with a period (*maksimalno = maks.*); `&` is *i*; and it names [pravopis.hr](http://pravopis.hr/) as its norm. Where this guide differs (quotes on control names, `ti`), the section says so.
- Android's own Croatian, read from AOSP: [SettingsLib hr](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-hr/strings.xml) (*Otklanjanje pogrešaka putem USB-a*, *Opcije za razvojne programere*, *Upari*, *Uparivanje*, *Punjenje*, *Nije povezano*) and [Settings hr](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-hr/strings.xml) (*Postavke*, *Zaboravi*, *Obavijesti*, *Uparite novi uređaj*). *Broj međuverzije* and *O telefonu* weren't in the parts read. They come from memory and a search summary, and need a check on a phone.
- Croatian e-scooter manuals from other brands, as terminology only: [Audi Pro (Egret) manual, HR](https://my-egret.com/media/76/b9/a6/1664359730/Audi_Pro_Betriebsanleitung_HR_20220721.pdf) (*romobil*, *zaslon*, *upravljač*, *PIN*, *unesite PIN*, *način vožnje*, *pješački način rada*, *brojač ukupno prijeđenih kilometara (ODO)*, *dnevni brojač kilometara (TRIP)*, *Bluetooth uparivanje*, *originalni punjač*, *domet*, *ručica gasa*, *blokada s PIN kodom*); [Riley RS3 manual, HR](https://cdn.shopify.com/s/files/1/0473/9340/7134/files/RS3-Croatia.pdf) (*e-romobil*, *stražnje svjetlo*, *prednje svjetlo*, *način tempomata*, *zaključavanje … e-romobila* for the app feature).
- Retail listings seen only as search summaries, weaker evidence: [Xiaomi 6 Ultra hr](https://www.mi.com/hr/product/xiaomi-electric-scooter-6-ultra/) (*rekuperacija energije*, TCS and *kontrola proklizavanja*; direct fetch refused, 403), [Trevi on mall.hr](https://www.mall.hr/romobili/trevi-velociptor-skill-2-es82w-elektricni-romobil-350w-led-svjetla-zmigavci-sklopivi-zaslon-do-20-km-crna) (*žmigavci*), [Alfa E-Power on nabava.net](https://www.nabava.net/elektricni-romobili/alfa-e-power-as60-elektricni-romobil-cijena-893408431) (*tempomat*).
- **Pravopis not read.** pravopis.hr was not fetched. Dates, ordinal periods, *s/sa*, *k/ka* and the future spelling come from memory of Croatian orthography. CLDR number formats and plural rules for `hr` also come from memory; they match the plan's `one, few, other`.
- **Judgment calls with no source:** *vršna brzina*, *ovlašteni korisnik*, *vlasnički ključ*, *putovanje* for Trip, *Eksperimentalne funkcije*, *pomoć pri guranju*, *pomoć motora*, *način slabe baterije*, *način velikog dometa*, *Kraj punjenja*, *nije povezan s tvrtkom NAVEE*, and all the Fixed words.
- Glyphs: `tools/l10n/fontmetrics.py` over all four `res/font/saira_*.ttf` weights found no missing glyph for č ć đ š ž, their capitals, „ ” ‚ ’, `…`, `·`, `–`, U+00A0, `°` and `›`. Widths: `fontmetrics.Measurer`, java.awt, `saira_medium.ttf`.
