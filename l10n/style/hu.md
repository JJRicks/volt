# Hungarian style guide (hu)

**Status: Claude-only proposal, 2026-11-05, not final.** One model drafted this from the sources in section 9. The three-vendor debate has not seen it and no native speaker has checked it. "Approved" and "exact" below mean "the wording this proposal asks every drafter to use", safety wording included. The `te` address and the wave 2 settings come from the plan's proposal, and are still to be reported to the owner.

**Wait for D3.** D3's English terms are approved but not merged. Translate to D3's terms (retired English words below map to them), and see "Held until the English terminology fix (D3) merges" in section 7 for the keys that stay English until then.

## 1. Register and form of address

- Address the rider as **te**, singular, always: *Csatlakoztasd a töltőt*, *a rollered*. Never Ön, never *önözés*, never a mix. NAVEE's own Hungarian manual mixes the two, and Android's Hungarian and Microsoft's guide use Ön, so don't copy sentences from them.
- Use no pronoun where the verb already says who: *Csatlakozz a rollerhez*, not *Te csatlakozz*. Possessives sparingly: *a roller*, and *a rollered* only where ownership matters.
- Calm, plain, direct. No marketing tone, no slang, no *kérlek* in short labels, and at most one in a sentence where a real request is polite.
- **Buttons, toggles and menu actions use the verbal noun** (-ás, -és), as Android's Hungarian does: *Mentés*, *Csatlakozás*, *Lezárás*, *Feloldás*, *Visszaállítás*. With an object the noun takes -a/-e: *Modul hozzáadása*, *Probléma jelentése*. Never the imperative or a bare infinitive on a button. The exception is *Értesíts* (Notify me), which is an answer in the rider's voice.
- **Instructions in sentences use the te imperative**: *Koppints*, *Nyisd meg*, *Add meg az új PIN-kódot*, *Csatlakozz újra*. Don't mix the two in one string.
- **Status text uses -va/-ve with no subject**: *Lezárva*, *Csatlakozva*, *Bekapcsolva*. A running action is the verbal noun plus `…`: *Csatlakozás…*, *Lezárás…*. It stays distinct from the finished *Lezárva*.
- Finished events are past tense without a subject where it's clear: *Itt működött*, *Elveszett a jel*.
- Hungarian has no grammatical gender. Role words are neutral: *tulajdonos*, *társfelhasználó*. No level word or adjective needs agreement.

## 2. Punctuation and quotes

- Quotes: **„…”**, nested **»…«** (`„A »Mentés« gomb”`). Never "…", “…” or «…». All are in the four Saira weights.
- A sentence that names a button, tab or screen quotes its exact label and adds a head noun that takes the suffix: *Koppints a „Csatlakozás” gombra*, *Nyisd meg a „Biztonsági mentés” lapot*. Never a suffix on the closing quote (*„Mentés”-re*). A website or adb label that stays English keeps its English inside the same quotes: *a „Choose owner file” gomb*.
- A full sentence ends in a period, including an imperative (the Microsoft guide's practice for instructions); a label, button or title has none. No exclamation marks.
- A comma comes before *hogy*, *ha*, *mert*, *amikor*, *ami* and the like. No comma before *és*, *vagy* in a simple list.
- Choose **a** before a consonant and **az** before a vowel sound, by the next word as you can see it. Before a placeholder see section 8.
- Dash: spaced en dash ( – ). Ranges: en dash, no spaces (*10–20 km*). Keep the English's `·`, `›` and `…` as written, with no space before `…`.
- After a colon, lower case unless a name follows: *Utolsó ismert állapot: lezárva*.
- Keep placeholders, `**`, backticks, file names and commands unchanged. No soft hyphen (U+00AD), no zero-width characters.
- **Saira has ő, Ő, ű, Ű** in all four weights (checked with `tools/l10n/fontmetrics.py` against `res/font/saira_*.ttf`), along with á é í ó ö ú ü and their capitals, „ ” » « – … · ° › and U+00A0. It lacks U+202F: never use it. Don't substitute ô or û for ő and ű.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: *Töltési korlát*, *Kísérleti funkciók*, *Alacsony akkuszint mód*. Never English Title Case, even when the English has it (*Walk Assist* → *Tolássegéd*).
- Feature names are lower case mid-sentence: *kapcsold be az ütemezett töltést*. Days, months and language names are lower case.
- Button: *Mentés*. Label: *Hatótáv*. Title: *Biztonsági mentés és visszaállítás*. After a colon: lower case (section 2).
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android. Drive modes and level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *Sport mód* where the English says Sport.
- A lower-case unit stays lower case at the start of a label: *km-számláló*.
- Capitals keep their accents, including where Volt upper-cases text: *ELŐZMÉNYEK*, *MŰKÖDÖTT*, *ÜTEMEZÉS*.

## 4. Numbers, dates and units

- **Decimal comma** (*3,7 V*). Four-digit numbers take no separator (*1234,5*); from five digits a no-break space (U+00A0) groups thousands (*12 345,6*). Volt's `Fmt` formats live numbers, so its output can differ; never rewrite a placeholder's number or add separators around one.
- **Percent has no space:** `%1$s%%` gives *85%*. Android's Hungarian strings and NAVEE's manual (*30% alatt*) both write it that way.
- **Number and unit take a no-break space (U+00A0):** *25 km/h*, *36 V*, *25 °C*, *2 h 05 min*. Symbols stay, with no period and no plural: km, mi, mph, h, min, s, V, A, W, Wh, Ah. Not *km/óra*, which everyday Hungarian also uses, because unit symbols stay as written. Unit words are translated: *kilométer*, *mérföld*, *perc*, *óra*.
- Keep every number the English has, as digits. Don't spell them out, even short ones.
- **Dates and times** come from Volt's formatters; don't hard-code an order. Hungarian runs year, month, day, and each numeric part and the day end in a period: *2026. 11. 05.*, *2026. nov. 5.*, *nov. 5.* Months are lower case. Time is 24-hour with no leading zero in the hour: *9:30*, *14:30*. A placeholder may hold *2:30 PM*, so it takes no suffix and no *-kor* (section 8).
- Compact words: *k* on a chart is *e* (ezer: *10 e*), *pts* is *pt*.
- **Plurals: `one` and `other`.** A noun after a number or a word of quantity stays singular, and so does its verb: *1 perc*, *5 perc*, *3 roller van*, *több út*. Never *5 percek* or *3 rollerek*. So `one` and `other` are often the same phrase, and that's correct, not a copy error. `one` covers exactly 1, and may drop the number (*Egy perc*). `other` must keep it.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Width is measured in Saira, never in letters. Never a break in the middle of a word. In the labels measured here Hungarian runs well wider than English (Odometer 57.7dp, Kilométer-számláló 112.6dp at 12.5sp). Try these in order:

1. **A shorter real word:** *Akkumulátor* → *Akku*, *Hőmérséklet* stays where it fits.
2. **Drop what the screen already says:** *Akkufeszültség* → *Feszültség*; *Menetmód* → *Mód*; *Energia-visszanyerés* → *Visszanyerés*; *Teljesítmény-rásegítés* → *Rásegítés*.
3. **An approved abbreviation** from the table. Nothing else: no invented cuts. An abbreviation ends in a period, which counts toward the width. A compound keeps its first part whole: *Akkuhőm.*, *Akkubeáll.*
4. **Wrap at a real space or hyphen** where the English also wraps. Line breaking follows only spaces and hyphens; *Kilométer-számláló* can break at its hyphen.
5. If nothing fits, write the correct word and say so in your notes (rule 8 below).

| Full | Abbreviation | Widths at 12.5sp (dp) | Note |
|---|---|---|---|
| Maximális | **Max.** | Max. sebesség 85.6 (full 116.7) | The setting. Fits the 90dp tile |
| Átlag-, átlagos | **Átl.** | Átl. sebesség 76.9, Átl. seb. 46.3 (Átlagsebesség 85.2) | |
| sebesség | **seb.** | | Only after *Max.* or *Átl.* Never alone: *seb* means a wound |
| hőmérséklet | **Hőm.**, in a compound **-hőm.** | Hőm. 30.3, Akkuhőm. 58.0 | |
| teljesítmény | **Telj.** | 22.9 | |
| kapacitás | **Kap.** | 25.7, Becs. kapacitás 88.8 | |
| becsült | **Becs.**; a separate tag **becs.** | Becs. hatótáv 77.9, becs. 21.2 at 9sp | `stats_health_chip` takes *becs.* |
| körülbelül | **kb.** | 16.9 | Before a number |
| beállítások | **Beáll.**, in a compound **-beáll.** | Beáll. 32.2, Akkubeáll. 60.4, Menetbeáll. 68.4, App-beáll. 58.9 | |
| kilométer | **km-** | km-számláló 73.9 | Lower case |

**Do not write:** *Lez.*, *Felold.*, *Ell.*, *Ism.*, *Tul. kulcs*, *Bizt. mentés*, *Kísérl.*, *Ütem.*, *Vil.*, *Akk.*, *Tölt.*.

**Real ladders**, measured with `res/font/saira_medium.ttf` through java.awt at 12.5sp:

| English | Full | `_short` | `_compact` |
|---|---|---|---|
| Total odometer / Odometer / Odo. | Kilométer-számláló (112.6) | km-számláló (73.9) | Össztáv (46.0) |
| Battery voltage / Voltage | Akkufeszültség (89.7) | Akkufeszültség | Feszültség (62.5) |
| Battery temperature / Battery temp. / Temp. | Akkuhőmérséklet (101.2) | Akkuhőm. (58.0) | Hőm. (30.3) |
| Battery power / Power | Akkuteljesítmény (101.5) | Akkuteljesítmény | Teljesítmény (73.8) |
| Battery current / Current | Akkuáram (59.0) | Akkuáram | Áram (31.2) |
| Trip avg. speed / Avg. speed | Szakasz átlagsebessége | Átlagsebesség (85.2) | Átl. seb. (46.3) |
| Est. range / Range | Becsült hatótáv (90.3) | Becs. hatótáv (77.9) | Hatótáv (46.0) |
| Est. capacity | Becsült kapacitás (101.1) | Becs. kapacitás (88.8) | Kap. (25.7) |
| Energy recovery | Energia-visszanyerés (124.7) | Visszanyerés (77.3) | fall to the icon |
| Compatibility check | Kompatibilitás-ellenőrzés (147.7) | Kompatibilitás (83.5) | Ellenőrzés (60.2) |
| Worked here / Worked | Itt működött (71.7) | Működött (56.0) | |
| Unverified here / Unverified | Itt nincs ellenőrizve (112.5) | Nincs ellenőrizve (98.0) | Same; never shorter |
| Untested here / Untested | Itt nincs kipróbálva | Nincs kipróbálva (95.5) | Same; never shorter |

**Hard budgets in the catalog at the time of writing:**

- `onboarding_welcome_tile_*`, 62dp at 12.5sp: Lezárás 45.1, Akku 29.3, Utak 27.3. *Beállítások* is 64.3 and doesn't fit, so **Beáll.** 32.2.
- `dashboard_model_qa_*`, 64dp at 12sp, one line: Beállítások 61.7, Világítás 48.4, Élő adatok 58.7, Utak 26.2, Akkubeáll. 58.0, Fénycsík 48.9, Csillagfény 61.8, Roller neve 62.4, Rollereim 53.0, Kísérleti 46.0, Névjegy 45.9, Adatok 39.9. Ride settings: *Menetbeáll.* is 65.7, so **Menet** 35.8. Display & sound: **Kijelző** 37.3. PIN & proximity: **PIN** 20.1. Charge done by: **Legyen kész** is 68.9 and overflows by 4.9; note it rather than swapping the meaning. Charge done time: **Befejezés** 54.8.
- `dashboard_model_qa_*_short`, 90dp at 12.5sp: Menetbeáll. 68.4, Kijelző és hang 87.4, PIN, közelség 77.7, Legyen kész 71.8, Befejezési idő 81.3, Rollereim 55.2, App-beáll. 58.9, Kísérleti 47.9. *Kísérleti funkciók* is 100.0: it may wrap at the space.
- `dashboard_model_tile_top_speed`, 90dp at 12.5sp: **Max. sebesség** 85.6 (held, section 7). `dashboard_model_tile_charge_schedule`: **Ütemezés** 57.9, since *Töltésütemezés* is 91.1.
- `owner_shizuku_install`, 48dp at 14sp: *Telepítés* is 58.2, so **Telepít** 43.5. `owner_shizuku_open`: *Megnyitás* is 69.0, so **Nyitás** 42.2. `stats_charge_fit`, 72dp at 14sp: **Illeszkedés** 71.3. `stats_raw_chip`, 40dp at 10sp: **NYERS** 32.5. `onboarding_skip`: **Kihagyás**.

**Compounds.** By Hungarian spelling, a two-part compound longer than six syllables takes a hyphen, and so does one built on an acronym, name or digit. Use these exact spellings:

- **Hyphen:** kilométer-számláló, km-számláló, energia-visszanyerés, kompatibilitás-ellenőrzés, kompatibilitás-ellenőrző, teljesítmény-rásegítés, túlsebesség-szabályozás, lejtmenet-szabályozás, kormány-PIN, PIN-kód, NAVEE-naplózás, Eco-becslés, App-beáll., Bluetooth-közelségi.
- **Closed:** csúcssebesség, átlagsebesség, sebességkorlát, akkuszint, akkufeszültség, akkuáram, akkuteljesítmény, akkuhőmérséklet, akkufigyelmeztetés, hatótávbecslés, töltőprofil, gyorstöltő, kipörgésgátló, tolássegéd, menetmód, fénycsík, csillagfény, jelzőhang, hangulatvilágítás, logóvilágítás, adatelőzmények, gyorselérés, modellazonosító, beállítófájl.
- **Separate:** *NAVEE alkalmazás*, as NAVEE's manual writes it (a spelling guide would hyphenate it; *NAVEE-alkalmazás* also passes the check, but use the manual's), *USB hibakeresés*, as Android writes it.
- Don't coin a compound of more than three stems. Split it with a possessive: *Menetmód sebességkorlátja*, not *Menetmódsebességkorlát*.

**Never abbreviate:** *Lezárás, Lezárva, Feloldás, Feloldva, Nincs ellenőrizve, Nincs kipróbálva, Nincs megerősítve, Ismeretlen, Soha, Mindig, Riasztás, PIN*. If one of these doesn't fit, write the whole word and report it in your notes.

## 6. Safety wording

Use exactly these words. They match `l10n/glossary/hu.tsv`; don't use synonyms.

| English | Hungarian, exact | Rules |
|---|---|---|
| Lock (action) | **Lezárás**; in a sentence **zárd le a rollert** | NAVEE's manual: *a roller lezárásához*. Not *Zárolás* (Android's lock-screen word), *Bezárás* or *Zárás* |
| Locked | **Lezárva**, adjective **lezárt** | NAVEE's manual: *nincs lezárva*. Not *Zárva*, which is a shop sign's "Closed" |
| Unlock (action) | **Feloldás**; in a sentence **oldd fel a rollert** | Never *Nyitás*, *Kinyitás* or *Kioldás* |
| Unlocked | **Feloldva**, adjective **feloldott** | Never *Nyitva*, *nyitott*, *szabad*. Not *Nincs lezárva* where the state is confirmed |
| Lock (the thing) | **zár**: **elektronikus zár** | NAVEE's menu: *Bluetooth-zár* |
| Lock state unverified | **Zár állapota nincs ellenőrizve** | Must never read as confirmed. Keep *nincs* |
| Lock state unknown | **Zár állapota ismeretlen** | Must never read as confirmed |
| Last known | **Utolsó ismert állapot: lezárva**; in full **A zár utolsó ismert állapota: feloldva. Csatlakozz újra az ellenőrzéshez.** | Keep *utolsó ismert*. Never a plain *Lezárva* |
| Unknown | **Ismeretlen** | Not *Nincs adat*, which is "not reported" |
| Never | **Soha**; in a sentence **soha nem** | Hungarian needs both words with a verb: *Soha nem küldi el*. Never *nem* alone where the English says never |
| Always | **Mindig** | Always on: **Mindig bekapcsolva** |
| On / Off | **Bekapcsolva** / **Kikapcsolva**; narrow **Be** / **Ki**; verbs **kapcsold be** / **kapcsold ki** | Never *Igen*/*Nem* in their place. Never invert them |
| Alarm | **Riasztás**; Alarm sound: **Riasztási hang** | Never *Figyelmeztetés*: alert is **Figyelmeztetés**, a notice is **Értesítés**, and the three stay distinct |
| Stop (a sounding alarm) | **Leállítás** | Not *Némítás*, which softens it. Stopping the scooter is **állj meg** |
| PIN | **PIN-kód**; narrow **PIN**; accusative **PIN-kódot** | Not *jelszó*, even though NAVEE's manual says *jelszó*; a backup password stays **jelszó** |
| Handlebar PIN | **kormány-PIN** | D3's term. Toggle: **Feloldás PIN-kóddal**. NAVEE's manual calls the feature *Jelszavas feloldás* (section 9) |
| Owner key | **tulajdonosi kulcs** | A secret. Never *azonosító* or *kulcs* alone. "Not a password" is **Nem jelszó** |
| Charge limit | **Töltési korlát** | Keep the number and %. At limit: **Elérte a korlátot** |
| Experimental | **Kísérleti**; Experimental controls: **Kísérleti funkciók** | Badge **LAB**, spoken *Kísérleti* |
| Untested | **Nincs kipróbálva** | Keep *nincs* |
| Unverified | **Nincs ellenőrizve** | Keep *nincs* |
| Unconfirmed / Not confirmed | **Nincs megerősítve** / **Nem megerősített** | Never *megerősítve* alone |
| not affiliated with NAVEE | **nem áll kapcsolatban a NAVEE céggel** | Footer: *Volt %1$s · nem áll kapcsolatban a NAVEE céggel*. Sentence: *A Volt nem áll kapcsolatban a NAVEE céggel.* Never soften it: no *nem hivatalos*, *független*, *nem közvetlenül*. No suffix on the name (section 8) |

**Words that look right and aren't:**

- **Lezárás is the lock, and removing it is Feloldás.** Never *lezárás feloldása*, *zár feloldása*, *zár kikapcsolása*, *zárolás feloldása* or *zárolásfeloldás* for Unlock. **`check.py` won't catch this:** the lexicon has no Hungarian opposite or removal patterns, so *A lezárás feloldása* counts as the Lock term (tested). Judges must read for it. Only use *a zár kikapcsolása* where the English itself says turn off.
- **Felold- has one meaning here.** NAVEE's manual also uses it for removing an account (*A NAVEE app feloldása*). In Volt, account binding is **társítás** and undoing it **társítás megszüntetése**, so *Feloldás* only ever means unlocking.
- **-atlan/-etlen words fail the checks and blur the state:** *lezáratlan*, *feloldatlan* (which also means "unresolved"), *ellenőrizetlen*, *kipróbálatlan*, *megerősítetlen*. Use *nincs* or *nem*. The native negated forms the check accepts are *ismeretlen, lehetetlen, érvénytelen, váratlan, hiányos, elégtelen, inaktív, sikertelen*; the negation words are *nem, ne, soha, nincs, sincs, nélkül, semmi*.
- **Be** and **Ki** are one syllable each and are also everyday words (in, out, who). Prefer the full *Bekapcsolva*, *Kikapcsolva* wherever they fit.
- *Lezárva* and *Lezárás…* share a stem. A running lock always keeps its `…`, so it can't read as done.

Other rules:

- Keep every negation, next to the word it negates. A sentence that says a command **may** have been sent keeps that doubt: *lehet, hogy a roller már alkalmazta*. Don't turn it into a statement.
- Don't add reassurance the English lacks (*biztonságos*, *garantált*) or warnings it doesn't have. Don't add claims about Hungarian traffic law or 20 km/h unless the English does.
- Report content stays in English, and the surrounding UI says so: *A jelentés angol nyelvű.*

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't. NAVEE's Hungarian wording comes first where it exists (section 9).

| Thing | Hungarian | Note |
|---|---|---|
| scooter | **roller**, *rollert*, *rollerhez* | NAVEE's word. Not *e-roller* unless a first mention needs it, not *robogó*, which is a moped, and not *jármű* |
| phone, the app | **telefon**, **alkalmazás** | The NAVEE app: **NAVEE alkalmazás** |
| battery | **akkumulátor** in full and in sentences; **akku** in compounds and narrow rungs | *akkuszint*, *akkufeszültség* |
| charger, charging | **töltő**, **töltés** | Battery level: **akkuszint** |
| scooter's display | **a roller kijelzője**, **kijelző** | Never *műszerfal* or *irányítópult* |
| Dashboard | **Irányítópult** | Only Volt's Home layout. Home tab: **Kezdőlap**. More tab: **Továbbiak**. Scooter tab: **Roller** |
| handlebar, stem, kickstand, deck | **kormány**, **kormányoszlop**, **kitámasztó**, **alaplemez** | NAVEE's words |
| brake lever, throttle, power button | **fékkar**, **gázkar**, **bekapcsoló gomb** | NAVEE's words |
| turn signal, headlight, tail light | **irányjelző**, **fényszóró**, **hátsó lámpa** | Irányjelző is NAVEE's. The other two are from other brands' Hungarian manuals |
| pair, bind, connect | **párosítás**, **társítás**, **csatlakozás** | NAVEE's words; keep all three distinct |
| Settings, setup | **Beállítások** (plural, the screen), **beállítás** (singular, one setting or setup) | One-time setup in a title: *Tulajdonosi beállítás* |
| tap | **koppints** | Android's and NAVEE's word. A physical button: **nyomd meg** |
| widget | **modul** | Android's Hungarian |
| ride, trip, route | **út** (*Utak*), **szakasz**, **útvonal** | A recorded ride is *út*. *Trip*, the distance since a reset, is *Szakasz*. The path on the map is *útvonal* |
| range | **hatótáv** | NAVEE's manual also says *hatótávolság*; use the shorter one |
| owner, shared rider | **tulajdonos**, **társfelhasználó** | The compact role label (`more_role_shared`) is **Társ** (25.3). Not *Megosztott*, which reads as a shared scooter |
| Charge done by | **Legyen kész**; full **Töltés legyen kész**; time setting **Befejezési idő** | The feature only plans a start, so never *Feltöltve* or *Kész*, which read as finished, and not *eddig*, which also means "so far". In a sentence: *a „Legyen kész” funkció ki van kapcsolva* |
| Quick settings | **Gyorselérés** | Never *Gyorsbeállítások*: that is Android's pull-down panel |
| Eco, Turbo, Boost | stay as written | **Eco-becslés** is a range option, translated |

**Three speeds.** The setting is **Maximális sebesség**. A limit below it is **Sebességkorlát** (**Egyéni sebességkorlát**, **Menetmód sebességkorlátja**). The measured value on a trip or ride is **Csúcssebesség**, narrow **Csúcs**. Never swap them. Don't use *végsebesség*, which reads as a specification. NAVEE's manual has *sebességkorlátozás* for the limiter, which also passes.

- **Held until the English terminology fix (D3) merges:** the rows `top-speed` and `maximum-speed` hold D3's meanings, but today's English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback. Every other key follows today's English meaning until D3 lands. `rides_tile_max_speed` isn't held: its English says Max, but it is the measured value today and after D3, and no glossary row covers it. It is **Csúcssebesség**, or **Csúcs** if narrow. Never *Max. sebesség*.

**Try, retry, test.** Trying a control (Experimental, Controls to try) is **Kipróbálás**, *próbáld ki*. The preverb *ki-* is what makes it a trial, so it stays. An ordinary retry never has it (Fixed words). A guided test is **teszt**, *Ütemezett töltés tesztelése*. A compatibility check is **kompatibilitás-ellenőrzés**.

**Restore vs set back.** Bringing back a backup, or NAVEE logging as Fixed words says, is **Visszaállítás**. Undoing a try is **Visszavonás**, and so is putting back a test's earlier value even when the English says restore. **Energia-visszanyerés** is unrelated.

**Readback and rejection.** Readback is **visszaolvasás**, a neutral word, because a readback can also fail to confirm. *Rejected* is **Elutasítva**, *Refused by scooter* **A roller elutasította**.

**Levels.** Energy recovery levels are ordinary words: *Ki, Alacsony, Közepes, Magas*. Standing start is **Állórajt**, value 0 of **Indulási sebesség** (NAVEE's manual), not another name for it.

**Words shared only by accident (D3). Do not unify them:**

- *limit*: töltési korlát (charge), sebességkorlát (speed), a storage or time limit is **határ** or **legfeljebb**, a widget limit **modulok száma legfeljebb**.
- *key*: tulajdonosi kulcs; a JSON or preference key is **mező**; RSA-ujjlenyomat.
- *identity*: identifying a model is **azonosítás**, not the owner key.
- *shared*: "shared evidence" is **megosztott**, not társfelhasználó.
- *setup*: general **beállítás**; owner setup **tulajdonosi beállítás**.
- *History*: adatelőzmények, útelőzmények, hibaelőzmények.
- *Recovery*: energia-visszanyerés vs visszaállítás.
- *Eco*: Eco (acceleration, keep), ECO (drive mode, keep), Eco-becslés (range).
- *Drive mode*: **Menetmód**, narrow **Mód**. Names stay: *Sport mód*, *ECO mód*.

## 8. Grammar traps: placeholders and suffixes

Hungarian says "to", "with", "at" and "from" with suffixes, and a suffix must agree with a word's last sound and vowels. **A placeholder's value is unknown, so a suffix can never attach to it.** No `%1$s-hoz`, `%1$s-val`, `%1$s-ra`, `%1$d-kor`, `%1$s%%-ig`, `%1$s-t`. This is the rule for phrasing around one, in order of preference:

1. **A demonstrative plus a colon, with the value last.** The suffix goes on *ez*, *ide* or *a következő*, as Android's Hungarian does (*Váltás erre: %s*, *Felülírva erre: %1$s*):
   - Connecting to %1$s → `Csatlakozás ehhez: %1$s`
   - Changing to %1$s… → `Módosítás erre: %1$s…`
   - Back to %1$s → `Vissza ide: %1$s`
   - Destination for %1$s → `Mentési hely ehhez: %1$s`
2. **A label form,** which also covers times and dates, since *-kor* and *-én* have the same problem:
   - Full backup from %1$s → `Teljes biztonsági mentés, dátum: %1$s`
   - Time to %1$s → `Idő a következőig: %1$s`
   - Setting it back to %1$s… → `Visszavonás, érték: %1$s…`
   - Restore to %1$s (undoing a try) → `Visszavonás, érték: %1$s`
   - %1$s %2$s at %3$s → `%1$s %2$s, időpont: %3$s`
3. **A head noun after the value, and the suffix on that noun.** Use it where the value's kind is known and the noun fits: `%1$d perc múlva`, `a %1$s nevű rollerhez`, `%1$s funkció`.
4. **In mid-sentence, move the value to the end behind a colon:** Charging resumed %1$s before the start → `A töltés ennyivel a kezdés előtt indult újra: %1$s.`

Also:

- **No article before a placeholder.** *a* or *az* depends on the value's first sound. Use rule 1. Android's *a(z) %1$s* is the last resort, and only with a note.
- **Numbers and percent:** the suffix would change with the number (*5-höz*, *10-hez*, *3-hoz*), so `%1$d` takes none. Use a postposition, which doesn't change (*%1$d perc alatt*, *%1$d perc múlva*), or a label (`Korlát: %1$s%%`).
- **A word in front of a placeholder keeps its dictionary form unless it's a label with a colon.** Don't leave a bare noun that needs a suffix to link to the value.
- **Fixed words next to a placeholder** can inflect as usual, since you can see them.
- **NAVEE takes no suffix,** because its pronunciation decides the ending and it isn't settled. Use a head noun: *a NAVEE alkalmazásban*, *a NAVEE céggel*. *NAVEE-vel* and *NAVEE-val* are both wrong here. A suffix on GPX, CSV, PIN or USB goes after a hyphen and follows the spoken letters: *CSV-fájl*, or better a head noun.
- **Volt is also a Hungarian word:** *volt* means "was", and it is the unit. **Never start a sentence with a bare Volt, and never put it next to a verb without an article.** *Volt nem küld semmit* reads as "There was…". Write *A Volt nem küld semmit*, or *A Volt alkalmazás…*. For a suffix use the head noun: *a Volt alkalmazásban*, not *Voltban*. The footer's *Volt %1$s ·* is a name with a version, and stays.
- **Separable verbs split.** The preverb follows the verb in imperatives and negatives: *Oldd fel a rollert*, *Nem oldotta fel*, *Zárd le*, *Kapcsold be*. The glossary lists both shapes.
- **Definite and indefinite verbs:** use the definite form with a definite object: *Mentsd a fájlt*, and the indefinite without: *Ments másolatot*.
- **Final -a and -e lengthen before a suffix:** *lámpa, lámpát*; *kezdete, kezdetét*; *diagnosztika, diagnosztikát*. Long forms are in the glossary only where the check needs them.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | Button **Újrapróbálás**; sentence **Próbáld újra.** and **Próbáld újra kicsit később.** Never *Próbáld ki újra*, *Kipróbálás* or *teszteld*, which read as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **Nincs adat**. Says only that no value came, not that anything is off or fine. Never *nincs jelentve* or *nem jelentett* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **jelzett**; verb **jelez**: *a roller jelzi*, *a roller által jelzett*. A claim, not a measurement. Never *jelentett* (*jelentés* is the document), *mért* or *megerősített* |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **NAVEE-naplózás**. Never *regisztráció* or *bejelentkezés* |
| restore logging | turning that logging back to how it was (usually off) | **naplózás visszaállítása**; running **A NAVEE-naplózás visszaállítása…** Means the earlier state, usually off. It brings back no file or backup. Where the English says turn off: **kapcsold ki** |
| Volt's helper | the small helper Volt starts inside Shizuku | **Volt segédprogram**, once the screen makes clear which **segédprogram**. Not *asszisztens*. The computer's script is the different **kiolvasó szkript** |
| stock charger | the charger that came with the scooter | **gyári töltő**. Not a claim that another charger is fake |
| high-speed charger | a faster charger the rider may own | **gyorstöltő**; alone *Gyors*. About charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **adatkiesés**. No data was recorded; not that power or the ride was cut. Never *rés* or *lyuk* |
| elevation | height above sea level on a route | **tengerszint feletti magasság**; narrow **magasság**. Not *emelkedő*, which is a slope |
| current direction | which way battery current flows | **áramirány**. Not *irány* alone, which reads as a route |
| throughput | how much energy has passed through the battery | **átáramlott energia**; narrow **Átáramlás**. An amount, not a rate. Not *teljesítmény* or *kapacitás* |
| battery gauge | the scooter's own battery percentage reading | **akkujelző**. The scooter's own figure, which can jump; not *akkuszint*, the level itself. The Home widget called Gauge is a different thing: **mutató** |
| raw | an unprocessed value, also used as a short tag | **nyers**; in brackets **(nyers)**; tag **NYERS** where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Utolsó adat**; with a value *Utolsó adat: %1$s*. The data may be old, so never word it as current. Not *utoljára látva*, and not *utolsó ismert*, which is the last known state |
| add | adding a widget, scooter or item | **hozzáadás**: *Modul hozzáadása*, *Adj hozzá egy másik rollert* |
| enter (typing) | typing a value | **megadás**: *Add meg az új PIN-kódot*. Not *beír* or *bevisz* |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **alkalmazás**: *a hivatalos alkalmazás*, *a NAVEE alkalmazás*. *app* only in *App-beáll.* on a narrow rung |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The rider or Volt is the subject: **csatlakozz a rollerhez**, *A Volt csatlakozik a rollerhez*. Button **Csatlakozás**. Never *Csatlakoztasd a rollert*, which reads as plugging it in. Plugging in says **csatlakoztasd a töltőt** |
| set a value | one verb for setting a value on the scooter | **beállítás**: *Sebességkorlát beállítása*, *Állítsd be*, *Beállítás erre: %1$s*. Setting back stays *Visszavonás*; typing a PIN stays *Add meg* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | **Ez + present tense**: *Ez törli ezt az utat.*, *Ez lecseréli a telefonon lévő összes adatot.* A hint with no subject stays third person: *Megnyitja a térképet.* Never *Töröld…* or *Nyisd meg…*, which are commands. Rule 3's open clause uses **-e**: *Nem sikerült megerősíteni, hogy az ütemezés kikapcsolt-e.* |
| screen and section titles | noun form or verb form, with one example | **Noun form**: *Biztonsági mentés és visszaállítás*, *Ütemezett töltés tesztelése*. Never the imperative |

## 9. Sources consulted

**NAVEE's official Hungarian: found, for one manual only.** Nothing shows the NAVEE app's Hungarian screens, so its wording for charge limit, owner key, experimental features and most settings is **not established**. Those words are Claude's own proposals, and the app may differ. No APK was downloaded. naveetech.com/hu returned 403.

- **NAVEE XT5 series EU User Guide**, a NAVEE-branded multilingual PDF, read directly, HU parts only. The copy read is on a retailer's site: wheelyshop.se/cdn/shop/files/NAVEE_XT5_series-EU-User_Guide.pdf. Printed: *roller*, *jármű*, *lezár*, *lezárásához*, *nincs lezárva*, *feloldás*, *oldja fel*, *Feloldás jelszóval*, *Jelszavas feloldás*, *Bluetooth-zár*, *Bluetooth-közelségi feloldás*, *jelszó*, *4 számjegyű kód*, *NAVEE alkalmazás*, *További funkciók*, *társítás*, *párosítás*, *csatlakoztatott eszköz*, *időzített töltés*, *ütemezett töltés*, *töltési ütemezés*, *indulási sebesség*, *sebességkorlátozás*, *hatótávolság*, *akkumulátor*, *töltöttségi szint*, *töltő*, *kijelző*, *irányjelző gomb*, *fékkar*, *gázkar*, *kormány*, *kormányoszlop*, *kitámasztó*, *alaplemez* and *fedélzet* for the deck, *bekapcsoló gomb*, *firmware*, *visszaállítás*, *Parkolás*, *30% alatt*, *10 km/h-ra*. It mixes Ön and te, sometimes on one page, so it isn't a style model.
- **Android's own Hungarian, read from AOSP** (SettingsLib, Settings and core `values-hu/strings.xml` on android.googlesource.com): *Fejlesztői beállítások*, *USB hibakeresés*, *Buildszám*, *Beállítások*, *Párosítás*, *Csatlakozás*, *Leválasztás*, *Visszaállítás*, *Biztonsági mentés*, *Modul*, *PIN-kód*, *Jelszó*, *Feloldás*, *Zárolás*, `%1$d%%`, and the placeholder pattern *Váltás erre: %s*, *Felülírva erre: %1$s*, *a következőhöz: %1$s*. *A telefonról* wasn't confirmed there; third-party guides name the About menu differently by maker, so write generally: *a beállításokban nyisd meg a telefon adatait*. Android says Ön.
- **Microsoft Hungarian Localization Style Guide** (download.microsoft.com hun-hun-StyleGuide.pdf), read directly: „…”, no-break space before a unit and as the thousands mark, periods after instructions, first capital only on UI elements, acronyms kept, suffix follows pronunciation, find out what replaces a placeholder. It requires Ön, which Volt doesn't follow.
- **CLDR `hu.xml`** (read directly): „ ”, » «, minimumGroupingDigits 2, `y. MMM d.`, `H:mm`, decimal comma.
- **Other brands' Hungarian manuals, weak evidence:** Vivax MS Energy e21 (robotworld.hu), Kaabo Mantis (dwn.alza.cz/manual/108604), City Boss (dwn.alza.cz/manual/118773): *hátsó lámpa*, *fényszóró*, *sebességtartó automatika*, *Sport mód*, *ECO üzemmód*, *Kilométer-számláló*, *TRIP*, *ODO*, *MAXIMÁLIS SEBESSÉG*, *végsebesség*, *Sebességhatároló*, *gumiabroncs*, *100%-ra*. *Tempomat* came from search results for Sencor's manuals, not a page read in full. *Sebességkorlát* is from Hungarian press search results.
- **Could not be read:** telex.hu's NAVEE test and alza.hu (403), Ausom's Hungarian store pages (404). Hungarian spelling rules on hyphens and compounds (the six-syllable rule), date periods and a/az come from memory of the Academy's *A magyar helyesírás szabályai* (12th edition), not a fresh reading.
- **Automotive terms from general knowledge, not looked up:** *kipörgésgátló*, *lejtmenet-szabályozás*, *nappali menetfény*, *hangulatvilágítás*, *menetmód*, *állórajt*.
- **Width figures:** measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`. Glyphs: the cmaps of all four `res/font/saira_*.ttf`.

**No source, Claude's choice. Debate these first:** *tulajdonosi kulcs*, *társfelhasználó* and *Társ*, *kormány-PIN*, *Töltési korlát*, *Csúcssebesség*, *Szakasz* for Trip (cars say *napi számláló*, which isn't daily here), *Utak* for Rides (*út* is also a road), *Össztáv*, *Legyen kész*, *Tolássegéd*, *Teljesítmény-rásegítés*, *Fénycsík*, *Csillagfény*, *Irányítópult*, *Gyorselérés*, *Visszavonás* for Set back, the "not affiliated" wording, and the fixed words table.

**Choices against a source:**

- **Lezárás, not Zárolás.** NAVEE's manual says *lezár*. Android says *Zárolás* for its screen.
- **PIN-kód, not jelszó.** D3 chose PIN, and Android and Hungarian usage say *PIN-kód*. NAVEE's manual says *jelszó* and *kód*, and its setting's name in the app is unconfirmed. A rider looking for *Jelszavas feloldás* won't see those words, so `ScooterCommands.keywords()` could add *jelszó*.
- **Ütemezett töltés, not időzített töltés.** The manual says both. *Ütemezett* matches D3's Scheduled.
- **Tempomat, not sebességtartó automatika.** Shorter, and the usual word.
