# Slovak style guide (sk)

**Status: Claude-only proposal, 2026-11-05.** Drafted by Claude alone. The three vendors have not debated it, no native speaker has reviewed it, and nothing here is final. The language's decisions (vy; one, few, many, other; Saira) are the plan's 7.4 proposals, which the owner has yet to confirm for this wave. The guide uses the D3 English terms (approved, not merged yet), so "owner key", "shared rider", "Maximum speed" and so on are the source meanings even where `res/values` still shows older words. Plan 7.2 stage 5 still decides every safety string.

## Register and form of address

**Vy**, always, with one rider in mind. Never tykanie (`zamkni`, `pripoj`, `tvoja`).

| Text type | Form | English | Slovak |
|---|---|---|---|
| Button, toggle, menu action | infinitive (Android's and Microsoft's Slovak habit) | Save / Lock / Restore | Uložiť / Zamknúť / Obnoviť |
| Instruction in a sentence | imperative, 2nd plural | Connect the charger. | Pripojte nabíjačku. |
| Purpose before a step | `Ak chcete…` or `Na…` | Reconnect to verify. | Na overenie sa znova pripojte. |
| | | Turn on Notify me or Alarm sound to get alerts. | Ak chcete dostávať upozornenia, zapnite „Upozorniť ma“ alebo „Zvuk alarmu“. |
| The rider's things | `váš`, or `svoj` when the rider is the subject, in lower case | Your alarm sound | Váš zvuk alarmu |
| Something optional | `môžete` | You can change this later. | Neskôr to môžete zmeniť. |
| What happened | reflexive passive or a noun | Nothing was changed. | Nič sa nezmenilo. |
| A status | adjective or participle | Unverified | Neoverená |

- Don't mix infinitive and imperative in one string. A button stays an infinitive even if a sentence on the same screen uses the imperative.
- **Keep the rider's gender out.** Vykanie's plural past is fine (`Pripojili ste`), but a predicate adjective shows gender (`Ste si istý/istá`, `Boli ste pripojený`). Write `Naozaj chcete…?`, `Pripojené`.
- No `prosím`, no exclamation marks in errors, no slang. The pronoun `vy` is rarely written at all: the verb carries it.
- Slovak, not Czech: `kolobežka`, `zámok`, `nabíjačka`, `batéria`, `zapnuté`. Czech forms such as `koloběžka`, `zamčeno`, `baterie`, `zapnuto` are errors even though riders would understand them.

## Punctuation and quotes

- Quotes: `„…“` outside, `‚…‘` inside. The closing `“` is U+201C, not `”` and not a straight `"`.
- A sentence that names a button, tab or screen puts its exact label in `„…“`, in the nominative, so it needn't decline: `zapnite „Upozorniť ma“`, `Funkcia „Nabité do“ je vypnutá.`
- A comma comes before `že, aby, ktorý, keď, ak, lebo, pretože, či`. None before a single `a, i, alebo`.
- No full stop on buttons, labels, titles or chips unless the English sentence has one.
- `…` is one character, with no space before it: `Pripája sa…`. Ranges take an unspaced `–` (`10–20 km`). A pause is a spaced `–`, never `-`. `·` separators stay as the English has them. After a colon, continue in lower case unless a name follows.
- Keep spaces and line breaks as the English has them. Don't add no-break spaces after one-letter words (`a, i, k, s, v, z, o, u`), although Slovak print typography does.
- **Saira has no U+202F.** The only no-break space used is U+00A0.

## Capitalization of buttons, labels and titles

Sentence case for all of them: the first word only. Button: `Obnoviť zálohu`. Label: `Limit nabíjania`. Title: `Experimentálne funkcie`. Never English title case, even where the English has it (`Walk Assist` → `Asistent pri tlačení`). Days and months are lower case. A feature mentioned in a sentence without quotes goes lower case: `zapnite plánované nabíjanie`. All capitals only where the English has them, keeping accents (`BATÉRIA`, `ÚDAJE`). Drive-mode and level names keep the case of the English string they are in: `Sport` where it writes Sport, `WALK` or `ECO` where it writes capitals.

### Names that can't decline

`check.py` looks for protected tokens as written, and Slovak wants to decline them. Put a noun before the name and decline the noun.

- `aplikácia Volt`, `v aplikácii Volt` (not `vo Volte`, `Voltu`); `v aplikácii NAVEE`; `aplikácia Shizuku`.
- `režim Sport`, `v režime Sport`, `v režime ECO`, `režim Boost`; `profil Eco`, `profil Turbo`; `súbor GPX`, `súbor CSV`.
- **PIN:** never `PIN-u`, `PIN-om`, `PINu`. Decline `kód` before it: `kód PIN`, `kódu PIN`, `kódom PIN`. `Zadajte nový kód PIN`.

### Placeholders and case

A value in `%1$s` can't decline, so build the sentence so the value stands alone in the nominative.

| English | Slovak | Not |
|---|---|---|
| Keep %1$s | Ponechať hodnotu: %1$s | Ponechať %1$s |
| Changing to %1$s… | Mení sa na hodnotu: %1$s… | Mení sa na %1$s… |
| Restore to %1$s | Obnoviť hodnotu: %1$s | |
| Last known lock state: %1$s | Posledný známy stav zámku: %1$s | |
| Starts at %1$s | Začiatok: %1$s | Začne sa o %1$s |

Prepositions change before some sounds (`k/ku`, `s/so`, `v/vo`, `z/zo`), so never put one directly before a placeholder: `v aplikácii %1$s`, `Názov: %1$s`. Before the scooter itself it's `ku kolobežke`, `s kolobežkou`, `z kolobežky`, `v kolobežke`.

### Plurals

**Slovak's categories are not Polish's.** `many` is for fractions only, and `other` is 0 and 5 upward.

| Category | Numbers | Nominative | Adjective, verb | Accusative |
|---|---|---|---|---|
| one | 1 | 1 kolobežka | Preskočená 1 kolobežka | Odstránili ste 1 kolobežku |
| few | 2–4 | 2 kolobežky | Preskočené 2 kolobežky | Odstránili ste 2 kolobežky |
| many | fractions only | 1,5 kolobežky | Preskočené 1,5 kolobežky | Odstránili ste 1,5 kolobežky |
| other | 0, 5 and up | 5 kolobežiek | Preskočených 5 kolobežiek | Odstránili ste 5 kolobežiek |

More (one, few, many, other): `minúta, minúty, minúty, minút`; `jazda, jazdy, jazdy, jázd`; `úsek, úseky, úseku, úsekov`; `súbor, súbory, súboru, súborov`; `nastavenie, nastavenia, nastavenia, nastavení`; `miniaplikácia, miniaplikácie, miniaplikácie, miniaplikácií`; `kategória, kategórie, kategórie, kategórií`.

- `many` is seldom seen, because Volt counts whole numbers, but it is required and is never a copy of `other`: use the genitive singular.
- Keep `%1$d` in every category, unless the English drops it in `one`.
- A verb that fits every count is easiest: `Odstránili ste` fits all four, while `bola preskočená / boli preskočené / bolo preskočených` must change with the count. Where a count isn't a plural resource, avoid the grammar: `Kolobežky: %1$d`.

## Numbers, dates and units

- Decimal comma; grouping with U+00A0 from four digits: `1 234,5` (`Fmt` and the locale produce these; don't hand-write them, and never rewrite a placeholder's number).
- Percent takes U+00A0, never a plain space or U+202F: `80 %`, and `fmt_percent` is `%1$s %%` with U+00A0. Slovak drops the space only in the adjective `80%` ("80-percent"), and Volt doesn't use it: write `80 %` every time, including `formatted="false"` text.
- Unit symbols stay and take U+00A0 after a number or placeholder: `25 km/h`, `36 V`, `5 A`, `350 W`, `25 °C`, `2 h 15 min`. No full stop and no plural on `km, mi, mph, h, min, s, V, A, W, Wh, Ah`. Unit words are translated: `kilometre`, `míle`, `minúty`.
- 24-hour time from the system (`7:00`). Dates come from the system; written out, the day has a full stop and the month is genitive and lower case: `5. novembra 2026`; numeric `5. 11. 2026`. No `a.m./p.m.`. `o %1$s` isn't allowed; use the label form (`Začiatok: %1$s`).

## How to shorten labels for ladder rungs

Fit is measured, so use a shorter rung only as far as the budget needs. In order:

1. Say less: drop what the screen supplies (`Teplota batérie`, then `Tepl. batérie`, then `Tepl.`).
2. Use a shorter natural word with the same meaning (`Jazda` for `Nastavenia jazdy` in a 64 dp tile).
3. Use only an abbreviation from the list below.
4. If nothing fits, write the correct word and say so in the reply's notes. Never swap in another meaning or a colloquial word.

Never break a word, never hyphenate to fit, never shrink text. An abbreviation that cuts a word has a full stop (`Max.`); `ODO` and symbols don't.

**Approved abbreviations**

| Short | Means | Short | Means |
|---|---|---|---|
| `ODO` | celkový nájazd (as scooter displays print it) | `Tepl.` | teplota |
| `Max.` | maximálna | `Priem.` | priemerná |
| `Najv.` | najvyššia (see the Top speed rule) | `Odh.` | odhadovaný |
| `Rýchl.` | rýchlosť, only as a last resort | `Nast.` | nastavenia, only with a following noun (`Nast. jazdy`) |
| `Experim.` | experimentálne | `Zap.`, `Vyp.` | zapnuté, vypnuté: narrow on and off values |
| `Neover.` | neoverená, where the whole word misses by little | `Netest.` | netestovaná, likewise |
| `č.` | číslo | | |

**Refused:**
- **`Odo.`, the English rung's obvious copy: it's the start of `Odomknúť` and `Odomknutá`.** The odometer's short form is capital `ODO` only.
- Any shortening of a lock word (`Zamk.`, `Odomk.`, `Zám.`, `Odom.`): both stay whole.
- `Nast.` alone and `Nastav.` (`nastav` reads as an imperative in the ty form); `Kap.` (reads as kapitola); `Min.` for minimum (clashes with `min`); `Bat.`, `Kolob.`: drop the word instead; `Temp.`, the Latin-looking copy, because Slovak writes `tepl.`; `Exp.`, which reads as expedícia or exponent.

**Real rungs, measured with `fontmetrics` (java.awt, Saira Medium)**

| English rungs | Slovak rungs | Widths in dp |
|---|---|---|
| Total odometer / Odometer / Odo. | Celkový nájazd / Nájazd / ODO | 83 / 39 / 25 at 12 sp |
| Maximum speed / Max. speed (90 dp tile) | Maximálna rýchlosť / Max. rýchlosť | 114 (over) / 78 at 12.5 sp |
| Trip top speed / Top speed | Najv. rýchlosť na úseku / Najv. na úseku | - / 81 at 12 sp |
| Trip avg. speed / Avg. speed | Priem. rýchlosť na úseku / Priem. rýchlosť | - / 84 at 12 sp |
| Battery temp. / Temp. | Tepl. batérie / Tepl. | 68 / 26 at 12 sp |
| Est. range / Range | Odh. dojazd / Dojazd | 66 / 38 at 12 sp |
| Trip distance / Distance | Dĺžka úseku / Vzdialenosť | 67 / 65 at 12 sp |
| Electronic lock / Lock | Elektronický zámok / Zámok | 109 / 38 at 12 sp |
| Unverified here / Unverified | Neoverená tu / Neoverená | 75 / 60 at 12 sp |
| Untested here / Untested | Netestovaná tu / Netestovaná | 85 / 71 at 12 sp |

Quick access tiles: 64 dp at 12 sp, then 90 dp at 12.5 sp.

| English | 64 dp | 90 dp |
|---|---|---|
| Settings | Nastavenia (63, tight) | Nastavenia |
| Ride settings | Jazda (31) | Nast. jazdy (65) |
| Battery settings | Batéria (41) | Nast. batérie (74) |
| Live Stats | Živé údaje (57) | Živé údaje (60) |
| Rides | Jazdy (32) | Jazdy |
| Lights | Svetlá (35) | Svetlá |
| Line lights / Star lights | Líniové (40) / Hviezdne (52) | Líniové svetlá (79) / Hviezdne (`Hviezdne svetlá` is 92, over) |
| My scooters | Kolobežky (58) | Kolobežky (`Moje kolobežky` is 90.7, over) |
| Experimental controls | Experim. (49) | Experim. (`Experimentálne` is 91.6, over) |
| Data & history | Údaje (32) | Údaje (`Údaje a história` is 90.4, over) |
| Display & sound | Displej (38) | Displej a zvuk (80) |
| PIN & proximity | PIN a prib. (58) | PIN a blízkosť (79) |
| App preferences | Predvoľby (57) | Predvoľby (59) |
| About & permissions | O aplikácii (57) | O aplikácii (59) |
| Scooter name | Názov (35) | Názov (36) |
| Charge done by | Nabité do (54) | Nabité do (56) |
| Charge done by time | Čas nabitia (61) | Čas nabitia (64) |
| Shared (role, `more_role_shared`) | Oprávnený (64 at 12.5 sp) | |

These are examples of the method, not approved strings. `prib.` is allowed only in that one label; it is a judgment call and the debate may do better.

**Top speed rule:** `Max. rýchlosť` (the setting) and `Najv. rýchlosť` (the measured value) read almost alike. Use `Najvyššia dosiahnutá rýchlosť` where there is room. In a narrow label keep the trip or ride in it (`Najv. na úseku`, `Najv. na jazde`), and don't write `Najv. rýchlosť` with no trip or ride context beside it.

## Safety wording

Use exactly these. The glossary's safety rows carry the same words with their inflected forms.

| English | Slovak | Notes |
|---|---|---|
| Lock (action, button) | **Zamknúť** | Instruction: `Zamknite kolobežku`. Noun: `zamknutie`. In progress: `Zamyká sa…`. Failed: `Zamknutie zlyhalo`. |
| Lock (title, row, the thing) | **Zámok** | Electronic lock: **Elektronický zámok**. Lock bar: **Lišta zámku**. Lock duration: **Trvanie zamknutia**. |
| Unlock (action, button) | **Odomknúť** | Instruction: `Odomknite kolobežku`. Noun: `odomknutie`. In progress: `Odomyká sa…`. |
| Locked | **Zamknutá** | Feminine, agreeing with `kolobežka`, on every bare state label. With a noun beside it, agree with that noun: `Elektronický zámok, zamknutý`. |
| Unlocked | **Odomknutá** | Same agreement rule. Never `nezamknutá`. |
| Lock state unverified | **Stav zámku nie je overený** | Never reads as confirmed. |
| Lock state unknown | **Stav zámku je neznámy** | Same meaning as above. |
| Last known: locked / unlocked | **Posledný známy stav: zamknutá / odomknutá** | With "Reconnect to verify": `Na overenie sa znova pripojte.` |
| Unknown | **neznámy / neznáma / neznáme** | Agrees with its noun. |
| Never | **nikdy** | Slovak needs the second negation too: `Nikdy sa nezobrazí`. |
| Always | **vždy** | `Vždy zapnuté`. |
| On, Off | **Zapnuté, Vypnuté** | Agree with the noun: `Rekuperácia: vypnutá`. |
| Alarm | **alarm** | Alarm sound: **Zvuk alarmu**. Stop: **Zastaviť** (`Zastaviť alarm`). Stop watching: **Prestať sledovať**. Not `budík`, an alarm clock. |
| PIN | **PIN**, **kód PIN** | Handlebar PIN: **PIN na riadidlách**. PIN unlock: **Odomknutie kódom PIN**. Never `heslo`. |
| Owner key | **kľúč vlastníka** | A secret. Not `kľúč kolobežky`, `identifikátor`, `heslo`. |
| Charge limit | **Limit nabíjania** | Not `strop`, `hranica`. |
| Experimental | **experimentálny / experimentálna / experimentálne** | Experimental controls: **Experimentálne funkcie**. Badge: `LAB`, spoken form **Experimentálne**. Controls to try: **Funkcie na vyskúšanie**. |
| Untested | **Netestovaná** | Feminine with `funkcia`. No one has tried it on this model. |
| Unverified | **Neoverená** | Volt hasn't confirmed it on this model. |
| Unconfirmed | **Nepotvrdená** | The scooter acknowledged it, but the value wasn't kept or read back. |
| Not confirmed | **Nie je potvrdené** | Three words, so the negation stands. A clause after it takes `či`, not `že`, so nothing is presupposed: `Nie je potvrdené, či sa plánované nabíjanie vyplo.` |
| Confirmed | **potvrdená**, **Potvrdené** | Only when the scooter read the value back. |
| not affiliated with NAVEE | **nie je spojený s NAVEE** | `Volt %1$s · nie je spojený s NAVEE`; the subject is Volt. Not `nezávislý`, `neoficiálny`, `nepridružený`, or a softer `nie je priamo spojený`. |

**Lock rules**

- Say a lock state with `zamknutá` or `odomknutá`. **Never** `Zámok vypnutý`, `Zámok zapnutý`, `Vypnúť zámok`, `Odstrániť zámok`, `Zrušiť zámok`, `Odomknúť zámok`, `Otvoriť`, `Otvorená`. "Lock off" and "open" are ambiguous on a safety control. Unlocking is said of the scooter: `Odomknúť kolobežku`. **`check.py` has no Slovak list for these phrases, so nothing stops them but the judge.**
- **Buttons act; states report.** Buttons use `Zamknúť` / `Odomknúť`; states use `Zamknutá` / `Odomknutá`. A Lock button that reads `Zamknutá` tells the rider it is already locked. The check can't tell them apart reliably, so judges confirm action or state on every lock key.
- **Not used:** `zablokovaná` and `odblokovať` (`zablokované koleso` is a jammed wheel, which reads as a fault), `uzamknúť` (correct Slovak, but one word per concept here), `poistka`, `zabezpečená`.
- `zámok` alone can also be a mechanical lock (other brands' manuals have `zámok na kľúč`), so write `Elektronický zámok` wherever the two could be confused.
- Keep the two words visibly different in a sentence: `Kolobežka je zamknutá. Posunutím ju odomknete.`

**Words that look right and mean the opposite, or blur a state**

- `Zamknutie` is locking and `odomknutie` is unlocking, so never drop the prefix of a sentence's verb to save space.
- `Nezamknutá` means "not locked", which may not be a known unlock. Use it only if the English really says not locked.
- `Vypnuté` and `Zapnuté` differ by their first letter only; `Vyp.` and `Zap.` are approved, nothing shorter.
- `Overená`/`Neoverená` and `Potvrdená`/`Nepotvrdená` differ by the prefix alone. Check every one.
- `Spolujazdec` is a passenger, not a shared rider.

**Negation rules: where the check differs from natural Slovak**

Slovak writes verb negation as one word (`nemá`, `nepodarilo sa`, `nezobrazí sa`). On a safety key, an English `not`, `no`, `never`, `can't`, `don't` or `couldn't` needs one of the separate words `nie`, `nikdy`, `žiadny`, `žiadna`, `žiadne`, `bez`, `nič`, or it fails. A joined verb alone fails, and so do other cases of `žiadny` (tested: `Nepodarilo sa potvrdiť`, `Nezamykajte`, `Toto nemôžete zmeniť`, `Žiadnu kolobežku` all fail).

- Write `Nie je možné potvrdiť…` for "could not confirm", `Telefón nie je spárovaný` for "phone not paired", `Nikdy sa nezobrazí` for "never shown".
- English `un-` words keep the prefix form (`Neoverená`, `Netestovaná`, `Neznámy`, `Nedostupné`); these pass.
- Where no natural wording fits, keep the correct Slovak and say so in the reply's notes rather than bending the meaning. A false alarm falls back to English, which is safe.
- Don't add `nie` or `bez` where the English has no negation: that is an error on a safety key.
- `Zlyhalo` is the wording for "failed" (`Zamknutie zlyhalo`). It adds no negation.
- The check can't tell which verb a `nie` belongs to, so judges read every safety key with `never` or `nikdy` by hand.
- Slovak has no on and off word list in the check, so an English `off` drafted correctly (`je vypnutá`) still gets a warning for the judge. That is expected. The same gap means the check can't see an inverted on or off: judges read those by hand.

## Scooter vocabulary

**E** is established Slovak seen in other brands' Slovak manuals or retailer pages (see sources), **M** is established Slovak from memory, not checked online, and **P** is proposed here with no outside evidence. **Nothing is official NAVEE Slovak**, because none could be read.

| English | Slovak | | English | Slovak | |
|---|---|---|---|---|---|
| scooter | kolobežka (not `skúter`, a motor scooter) | E | drive mode | jazdný režim | M |
| handlebar | riadidlá (plural: `na riadidlách`) | M | Kick-start speed | štartovacia rýchlosť | P |
| brake lever | brzdová páčka | E | Standing start | rozjazd z miesta | P |
| throttle | plynová páčka | E | Maximum speed (setting) | maximálna rýchlosť | E |
| battery | batéria (not `akumulátor`) | M | Top speed (measured) | najvyššia dosiahnutá rýchlosť | P |
| charger | nabíjačka | M | speed limit | limit rýchlosti | P |
| range | dojazd | E | Traction control | kontrola trakcie | M |
| speed | rýchlosť | E | Cruise control | tempomat | E |
| Odometer | celkový nájazd, nájazd, ODO | P | Energy recovery | rekuperácia energie | M |
| Trip | úsek | P | Boost | Boost | |
| Route (on the map) | trasa | M | Walk Assist | asistent pri tlačení | P |
| Rides (records) | jazdy; one: jazda | M | Power Assist | asistent výkonu | P |
| Dashboard (Volt's Home) | panel | P | Hill descent control | kontrola zjazdu zo svahu | P |
| scooter's display | displej kolobežky (not `obrazovka`, `prístrojová doska`) | M | Tail light | zadné svetlo | E |
| widget | miniaplikácia (Android's word) | M | headlight | predné svetlo | E |
| firmware | firmvér | M | Turn signal | smerovka | E |
| voltage, current, power | napätie, prúd, výkon | M | Automatic lights | automatické svetlá | P |
| capacity, health | kapacita, stav batérie | M | Prompt sound | zvukové signály | P |
| charge (percent) | nabitie | M | Daytime running light | denné svietenie | M |
| pair / connect | spárovať / pripojiť | M | Android settings | Pre vývojárov (older phones: Možnosti pre vývojárov), Ladenie cez USB, Číslo zostavy, Informácie o telefóne | M |
| owner | vlastník | M | shared rider | oprávnený jazdec; compact: Oprávnený | P |
| Live Stats | Živé údaje | P | readings | údaje | P |

Drive-mode names stay English (the owner's default). D3 made `Eco` and `Turbo` do-not-translate. `Boost` also stays as the scooter shows it. `Model ID` stays in English, as its glossary definition asks, though `ID modelu` would read more naturally. Android names vary by phone maker; a Samsung may differ.

**Open for the debate:** Walk Assist (e-bike Slovak has `pomoc pri tlačení`; `asistent chôdze` reads as a walking aid); Power Assist (a calque); Shared rider (`zdieľaný jazdec` reads oddly, and `zdieľaná` would describe the scooter); Charge done by (`Nabité do` reads as a state, which is why it's always quoted in sentences); Trip (`úsek` is clear but unusual on scooters, and a NAVEE display may print `TRIP`); Kick-start speed (no Slovak source explains this setting); Dashboard (`panel` vs `prehľad`); `prib.`.

**Same English, different meaning, so keep them apart**

- Trip `úsek` / Route `trasa` / Rides `jazdy`.
- Maximum speed `maximálna rýchlosť` (a setting) / Top speed `najvyššia dosiahnutá rýchlosť` (measured) / speed limit `limit rýchlosti` (rider-set, below the maximum). `Limits downhill speed` and similar use a verb, not this noun.
- Try a control `Vyskúšať`, `pokus` / Try again `Skúsiť znova` (never `Vyskúšať znova`) / Test `test`, `Otestovať` / compatibility check `kontrola kompatibility`.
- Restore a backup `Obnoviť` / Set back a try `Vrátiť späť` / Energy recovery `rekuperácia`, unrelated to either.
- Dashboard `panel` / scooter's display `displej`.
- Owner key `kľúč vlastníka` / JSON or preference key `kľúč`: same word, but `vlastníka` only on the first.
- Settings `nastavenia` / Owner setup `nastavenie vlastníka`: nearly the same word. Keep `vlastníka` on the second, and don't shorten it to `nastavenie` where settings could be meant.
- Pair `spárovať` / Connect `pripojiť` / NAVEE account binding `prepojenie účtu`, which isn't the disclaimer's `spojený`.
- Standing start (value 0) `rozjazd z miesta` / Kick-start speed (the setting) `štartovacia rýchlosť`.
- Levels: Energy recovery levels are translated, unlike drive modes, and agree with `rekuperácia`: `Vypnutá, Nízka, Stredná, Vysoká`. Elsewhere they agree with their own noun.
- Experimental adjective: controls `experimentálne funkcie`, a ride's motion `experimentálny pohyb`.

**Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. The glossary's `top-speed` and `maximum-speed` rows already carry D3's meanings, the opposite of what those strings mean today. Read those strings by today's meaning, what the value really is, never by the English word. These keys, the whole `readings_maximum_speed_kmh` ladder (with `_short` and `_compact`) and any `main_speed_*` key stay in English until D3 lands. Flag them in your notes; a judge answers fallback.

**Matching, for `check.py`:** a form inflects only if it ends in a plain vowel (`a e i o u y á é í ó ú`, not `ý`, `ô`, `ä`) and its stem has five letters or more. So `kolobežiek`, `zámku`, `úseku`, `rýchlosti` and the `-ý` forms must be listed, and they are. Word order matters in a multi-word term, so `limit nabíjania` isn't found in `nabíjania limit`. Stems differ by aspect, so `zamyká sa` and `odomyká sa` are listed too.

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

| English concept | Meaning | Slovak |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | Button: **Skúsiť znova**. Sentence: **Skúste to znova**, **Skúste to o chvíľu**. Never `Vyskúšať`, which means testing a control. |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **bez údajov**, with the name as a label: `Batéria: bez údajov`. Not `nenahlásené`, `nehlásené`. |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **podľa kolobežky** (`podľa riadiacej jednotky` for the controller; verb `uvádza`). Never `nameraná`, `potvrdená` or `hlásená`. |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **zaznamenávanie denníka NAVEE**. Never `prihlasovanie` (signing in) or `registrácia`. |
| restore logging | turning that logging back to how it was (usually off) | **Vrátiť nastavenie denníka NAVEE**. Says it goes back to how it was; `Obnoviť zaznamenávanie` would read as turning logging on. |
| Volt's helper | the small helper Volt starts inside Shizuku | **pomocný program Volt**. Not `pomocník` (a person or help text). The computer's capture helper stays `pomocný skript na zachytenie`. |
| stock charger | the charger that came with the scooter | **štandardná nabíjačka**. Not `originálna`, which would imply a faster charger isn't genuine. |
| high-speed charger | a faster charger the rider may own | **rýchla nabíjačka**; alone as an option: `Rýchla`. |
| gap (in a recording) | a stretch of a recording with no data | **výpadok v zázname**, plural `výpadky v zázname`. Not `medzera`, which also means a typed space. |
| elevation | height above sea level on a route | **nadmorská výška**; narrow: `Výška`. Not `prevýšenie` (height gained) or `stúpanie`. |
| current direction | which way battery current flows | **smer prúdu**. `Current` is the electric one; never `aktuálny smer`. |
| throughput | how much energy has passed through the battery | **prenesená energia**; narrow: `Energia`; lifetime: `celková prenesená energia`. An amount that has passed, not a rate, and never `priepustnosť`. |
| battery gauge | the scooter's own battery percentage reading | **údaj o nabití**; narrow: `Údaj`. "Charge per gauge": `Nabitie podľa kolobežky`. The scooter's own percentage, not a measured level. Not `ukazovateľ`, the device. |
| raw | an unprocessed value, also used as a short tag | **nespracovaná hodnota**; as a tag, agreeing with its noun: `(surový)`, `(surová)`, `(surové)`. Use the tag only beside a noun; alone `surový` can read as "harsh". |
| last seen | when Volt last had data from the scooter | **posledný údaj**, as a label: `Posledný údaj: %1$s`. Never `naposledy videná`, which reads as a person sighted and carries gender. |
| add | adding a widget, scooter or item | **Pridať**; instruction `pridajte`. |
| enter (typing) | typing a value | **Zadať** on a button, **Zadajte** in a prompt: `Zadajte nový kód PIN`. Not `Vložiť` (paste) and not `Uviesť`, the scooter's verb above. |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **aplikácia**, always. Never `appka`, `apka` or `program`. Before a name: `aplikácia Volt`. |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | Reflexive, with the dative: **pripojiť sa ku kolobežke** (`Pripojte sa ku kolobežke`). Button: `Pripojiť`. Event: `Pripojené`. In progress: `Pripája sa…`. Never `spojiť kolobežku`. A charger takes it without `sa`: `Pripojte nabíjačku`. |
| set a value | one verb for setting a value on the scooter | **Nastaviť** (`Nastaviť limit nabíjania`). `Zmeniť` only where the English says change. |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | **Týmto sa** + 3rd person future: `Týmto sa z tohto telefónu odstráni nastavenie pripojenia.` Never `Odstráňte…` or `Odstrániť…`, a command. In a summary line, the bare noun: `Nastavenia aplikácie: nahradenie 3 nastavení`. A hint that opens something: `Otvorí nastavenia batérie`. |
| screen and section titles | noun form or verb form, with one example | Noun form: `Nastavenia jazdy`, `Obnovenie zálohy`. The button on that screen stays an infinitive: `Obnoviť`. |

## Sources consulted

**Official NAVEE wording in Slovak: none was read.** A NAVEE user manual labelled CZ/SK for the E25 Pro appears in search results (https://service.naveetech.com/wp-content/uploads/2026/03/NAVEE-E25-Pro-UM-CZSK.pdf), but it returned 404 when fetched and the snippet showed only Czech safety text. So every term here should be compared with that manual if someone can get it. No Slovak NAVEE app strings turned up, and no APK was downloaded. Volt's own "official" English terms come from D3.

Search snippets only (the pages themselves were not read):

- Slovak product listing for the NAVEE ST3 Pro, top4mobile.sk: `Maximálna rýchlosť`, `dojazd`. Fetching it returned only the title.
- Kugoo Kirin M4 PRO manual, Slovak section (shopum.cz): `Plynová páčka`, `Brzdová páčka`, `Zámok na kľúč`, `Smerovky`.
- CITY BOSS Slovak manual via datart.sk (https://datart.sk/document/4/0/6/doc_3894604.pdf; connection refused when fetched): `Páčka zadnej brzdy`, predné and zadné svetlo.
- Micro Merlin Slovak manual (micro-mobility.com): `Tempomat`.
- LAMAX E-Scooters app listing: `tempomat`, `zámok kolobežky`.

Fetched with no Slovak text: the NAVEE ST3 Pro EU manual at https://dwn.alza.cz/manual/146926 is in English.

Repository: `tools/l10n/fontmetrics.py` over the four `res/font/saira_*.ttf` weights covers every Slovak letter (ľ ĺ ŕ ô ä š č ž ť ď ň ý á í é ú ó and their capitals), `„ “ ‚ ‘ – … · ° %` and U+00A0. U+202F is missing, as expected. Negation and matching behaviour was tested against `check.py` and `lexicon.py` on sample strings. Widths come from `fontmetrics` with java.awt.

**From memory, not checked online (judgment calls):** Slovak punctuation, capitalization, the 80 % rule and abbreviation habits (Pravidlá slovenského pravopisu, Microsoft's Slovak style guide); CLDR's plural rules and number format for Slovak; Android's Slovak wording (`Ladenie cez USB`, `Číslo zostavy`, `Pre vývojárov`, `miniaplikácia`, `klepnúť`). The search for those Android names found only Czech pages, so they need a look on a phone set to Slovak.
