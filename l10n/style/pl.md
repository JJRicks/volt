# Polish style guide (pl)

**Status: Claude-only proposal, 2026-11-05.** Drafted by Claude alone. The three vendors have not debated it, no native reader has reviewed it, and nothing here is final. It uses the D3 English terms, merged with the English fixes (#208, 2026-11-05), so "owner key", "shared rider", "Maximum speed" and so on are the source meanings, and `res/values` now uses them. Plan 7.2 stage 5 still decides every safety string.

## Register and form of address

The owner's rule: **ty, with impersonal phrasing for instructions.** Never `Pan/Pani/Państwo`, never `proszę`.

| Text type | Form | English | Polish |
|---|---|---|---|
| Button or one-tap command | imperative, ty (Android's own style) | Restore / Save backup | Przywróć / Zapisz kopię zapasową |
| Addressing the rider, their things, a question | ty | Your alarm sound | Twój dźwięk alarmu |
| | | You have unsaved changes | Masz niezapisane zmiany |
| How-to step, requirement, hint | impersonal: `należy` or `trzeba` + infinitive | Turn on Notify me or Alarm sound to get alerts. | Aby otrzymywać alerty, należy włączyć opcję „Powiadom mnie” lub „Dźwięk alarmu”. |
| | | Reconnect to verify. | Aby to zweryfikować, należy połączyć się ponownie. |
| Something optional | impersonal: `można` + infinitive | You can change this later. | Można to później zmienić. |
| What happened | impersonal `-no/-to` or a noun | Nothing was changed. / Connection lost. | Niczego nie zmieniono. / Utracono połączenie. |
| Short TalkBack hint on a control | imperative is allowed | Double-tap or slide to unlock | Dotknij dwukrotnie lub przesuń, aby odblokować |

- Use `należy` for safety and setup steps, `trzeba` only when `należy` reads stiff in a one-line hint. Don't mix `należy` and an imperative inside one sentence.
- **Keep the rider's gender out of it.** Second-person past tense and predicative adjectives are gendered: not `zapomniałeś`, `jesteś pewien`, `połączyłeś`. Write `Na pewno usunąć?`, `Połączono`, `Czy chcesz…`. Rider nouns are avoided by rephrasing; `właściciel` and `współużytkownik` are role names and stay as they are.
- Capitalize `Twój, Twoja, Twoje, Twoim…` in UI text (Google's Polish UI habit).
- Use the possessive only when ownership is the point (`Twoja hulajnoga. Twoje dane.`, `dla Twojego modelu`, `Twój adres IP`). English puts "your" everywhere; Polish drops it: `Znaleziono hulajnogę`, `Łączenie z hulajnogą`, `Tutaj pojawią się zapisane hulajnogi`.

## Punctuation and quotes

- Quotes: `„…”` outside, `«…»` inside. Closing `”` is U+201D, never a straight `"`.
- Quote an option or button named inside a sentence: `opcję „Dźwięk alarmu”`. The quoted name stays in the nominative, so it needn't decline.
- Commas are required before `aby, że, który, gdy, jeśli, ponieważ, bo`. No comma before a single `i, oraz, lub, albo`.
- No full stop on buttons, labels, titles or chips, unless the English sentence has one.
- `…` is one character, as in English. Ranges take an unspaced `–` (`10–20 km`); a pause is a spaced `–`, never `-`. After a colon, continue in lower case. `·` separators stay as the English has them.
- Keep spaces and line breaks exactly as the English has them. Don't add no-break spaces after one-letter words (`i, w, z, o, a, u`).

## Capitalization

Sentence case for buttons, labels, titles and tabs: first word only (`Ustawienia jazdy`, `Limit ładowania`, `Kopia zapasowa`), never title case. Days and months are lower case. All capitals only where the English has them (`LAB`, `PID`). Drive-mode and level names keep the case of the English string they are in: `Sport` where it writes Sport, `WALK` or `ECO` where it writes capitals. Names stay as written: Volt, NAVEE, Shizuku, ADB, GPX, CSV, Bluetooth, USB.

## Names that can't decline

`check.py` looks for protected tokens as written, and Polish wants to decline them. Put a noun before the name and decline the noun.

- `aplikacja Volt`, `w aplikacji Volt` (not `w Volcie`, `Volta`); `w aplikacji NAVEE` (the manual's own wording); `aplikacja Shizuku`.
- `tryb Sport`, `w trybie Sport`, `w trybie ECO`, `tryb Boost`, each in the case its English string uses; `profil Eco`, `profil Turbo`; `plik GPX`, `plik CSV`.
- Don't translate the names of the drive modes or levels. The manual's Polish, under the vocabulary table below, is for reference only.

## Placeholders and case

A value put into `%1$s` can't decline, and the sentence around it often needs a case. Build the sentence so that the value stands alone in the nominative:

| English | Polish | Not |
|---|---|---|
| Keep %1$s | Zachowaj wartość: %1$s | Zachowaj %1$s |
| Changing to %1$s… | Zmiana na wartość: %1$s… | Zmiana na %1$s… |
| Last known lock state: %1$s | Ostatni znany stan blokady: %1$s | |
| Restore to %1$s | Przywróć wartość: %1$s | |

Before a placeholder, `z/ze` and `w/we` can't be chosen by ear, so prefer a noun in between (`w aplikacji %1$s`).

## Plurals

Give all four categories, and agree the adjective and verb too.

| Category | Numbers | Nominative | After a verb (accusative) | Adjective |
|---|---|---|---|---|
| one | 1 | 1 hulajnoga | Pominięto 1 hulajnogę | pominięta |
| few | 2–4, 22–24, 32–34, 102… not 12–14 | 2 hulajnogi | Pominięto 2 hulajnogi | pominięte |
| many | 0, 5–21, 25–31, 100… | 5 hulajnóg | Pominięto 5 hulajnóg | pominiętych |
| other | fractions | 1,5 hulajnogi | Pominięto 1,5 hulajnogi | pominiętej |

More: `minuta, minuty, minut, minuty`; `przejazd, przejazdy, przejazdów, przejazdu`; `kategoria, kategorie, kategorii, kategorii`; `ustawienie, ustawienia, ustawień, ustawienia`; `plik, pliki, plików, pliku`; `widżet, widżety, widżetów, widżetu`; `raz, razy, razy, raza`.

- `other` is rarely seen, because Volt's plurals count whole numbers, but it is required and is never a copy of `many`: use the genitive singular.
- Keep `%1$d` in every category, including `one`, unless the English drops it. `check.py` lets `one` drop it, but string comments may forbid that.
- Prefer a verb form that fits every count: `Pominięto` fits all four, while `została pominięta` / `zostały pominięte` / `zostało pominiętych` must change with the count.
- When a count isn't a plural resource, avoid grammar that needs one: `Hulajnogi: %1$d`.

## Numbers, dates and units

- Decimal comma; grouping with a no-break space from five digits on: `1234,5` and `12 345,6` (`Fmt` and the locale produce these; don't hand-write them).
- Percent has no space: `80%`, and `fmt_percent` stays `%1$s%%`.
- Unit symbols stay and take a space, through `fmt_quantity`: `25 km/h`, `36 V`, `5 A`, `350 W`, `25 °C`, `mph`, `Wh`, `Ah`. `min` has no full stop. Unit words are translated: `kilometry`, `mile`, `minuty`.
- Hours as a word: `godz.` with the full stop (`2 godz. 15 min`). Where the English shows the symbol `h`, keep `h`.
- 24-hour time from the system (`7:00`). Fixed text may say `o 7:00` in a sentence or `do 7:00` for a deadline, but a time in `%1$s` takes the label form: `Start ładowania: %1$s`. Dates come from the system; written out, the month is genitive and lower case (`5 listopada 2026`).

## Shortening labels

Fit is measured, so use a shorter rung only as far as the budget needs. In order:

1. Say less: drop the word the context supplies (`Temperatura baterii`, then `Temp. baterii`, then `Temp.`).
2. Use a shorter natural word with the same meaning: `Opcje` for `Ustawienia` in a 62 dp tile (`Ustawienia` measures 65 dp).
3. Use only an abbreviation from the list below.
4. If nothing fits, write the correct word and say so in the reply's notes. Never swap in another meaning or a colloquial word.

Never break a word, never hyphenate to fit, never shrink text. An abbreviation that keeps the word's last letter has no full stop (`nr`); one that cuts the word has one (`maks.`).

**Approved abbreviations**

| Short | Means | Short | Means |
|---|---|---|---|
| `ODO` | przebieg całkowity (the NAVEE manual's label) | `Temp.` | temperatura |
| `Maks.` | maksymalna (not `Max`) | `Śr.` | średnia |
| `Najw.` | najwyższa (see the Top speed rule below) | `Szac.` | szacowany |
| `Prędk.` | prędkość | `Wł.`, `Wył.` | włączone, wyłączone: narrow on and off values (Android's own short forms, with no gender to agree) |
| `Ust.` | ustawienia, only with a following noun (`Ust. jazdy`); alone it reads as "ustęp" | `Ład.` | ładowania |
| `Wyśw.` | wyświetlacz | `Poz.` | poziom |
| `Poj.` | pojemność | `Eksperym.` | eksperymentalne |
| `Niezweryf.` | niezweryfikowana | `Nieprzetest.` | nieprzetestowana |
| `godz.` | godzina | `nr` | numer |
| `wg` | według, only for a reported figure (`Kondycja wg hulajnogi`); never the bare participle `Podawana kondycja` | `FW` | firmware, in the narrowest rung (`FW baterii`), always with a genitive noun |
| `jedn.` | jednostka, only in `na jedn. dystansu` when a tile is narrow | | |

**Refused:** `Św.` for światła (it reads as "święty"; use `Liniowe`, `Gwiezdne`), `Bat.` (it reads as `bat`, a whip; write `baterii` or drop the word), `Hulajn.`, `Ustaw.` (reads as the imperative "Ustaw"), `Min.` for minimum (clashes with `min`), and any shortening of a lock word (`Zablok.`, `Odblok.`, `Blok.`): only the prefix tells locked from unlocked, so both words stay whole. Drop other words first.

**Real rungs, measured with `fontmetrics` (hmtx at the given size, which errs slightly wide)**

| English rungs | Polish rungs | Widths |
|---|---|---|
| Total odometer / Odometer / Odo. | Przebieg całkowity / Przebieg / ODO | ODO 25 dp at 12 sp (Odo. 26) |
| Maximum speed / Max. speed | Prędkość maksymalna / Maks. prędkość | 89.9 of 90 dp at 12.5 sp: tight, recheck with java.awt |
| Trip top speed / Top speed | Najw. prędkość na trasie / Najw. na trasie | 86 dp at 12.5 sp (`Najw. osiągnięta` is 96.5, over the 90 dp budget) |
| Battery temp. / Temp. | Temp. baterii / Temp. | 73 / 34 dp at 12 sp |
| Trip avg. speed / Avg. speed | Śr. prędkość na trasie / Śr. prędkość | 68 dp at 12 sp (English 61) |
| Est. range / Range | Szac. zasięg / Zasięg | 68 / 37 dp at 12 sp |
| Trip distance / Distance | Dystans trasy / Dystans | 78 dp at 12 sp |
| Electronic lock / Lock | Blokada elektroniczna / Blokada | 45 dp at 12 sp |
| Unverified here / Unverified | Niezweryf. tutaj / Niezweryf. | 61 dp at 12 sp |
| Untested here / Untested | Nieprzetest. tutaj / Nieprzetest. | 68 dp at 12 sp |
| Ride settings, 64 dp tile | Jazda | 31 of 64 dp at 12 sp |
| Battery settings, 64 dp tile | Bateria | 41 of 64 dp at 12 sp |
| Charge done by / tile | Termin końca ładowania / Koniec ład. | 141 of 90 dp at 12.5 sp, which wraps as the English does / 60.5 of 64 dp at 12 sp |
| Charge done by time | Koniec ład.: godz. | two lines; `Koniec ład.:` 63.5 of 64 dp at 12 sp, 66 of 90 dp at 12.5 sp |
| Live Stats, 64 dp tile | Na żywo | 49 of 64 dp at 12 sp |
| My scooters, 64 dp tile | Hulajnogi | 53 of 64 dp at 12 sp |
| Experimental controls, 64 dp tile / 90 dp tile | Eksperym. / Funkcje eksperym. | 59 of 64 dp at 12 sp / two lines, 45 and 61 of 90 dp at 12.5 sp |
| PIN & proximity, 64 dp tile | PIN i zbliż. | 58 of 64 dp at 12 sp |
| Line lights, 64 dp tile | Liniowe | fits |

These are examples of the method, not approved strings. `Zbliż.` is allowed only in this one label. Bare `zbliżenie` is never allowed: it means a close-up or intimacy. The 90 dp rung is `PIN i klucz zbliżeniowy`; a title with room says `PIN i odblokowanie zbliżeniowe`. A narrower rung must never hold a longer text than a wider one, and check the longest single word against the budget before keeping a rung.

**Top speed rule:** `Maks. prędkość` (the setting) and `Najw. prędkość` (the measured value) are near synonyms in Polish, so a rider can't tell them apart alone. A measured value takes `najwyższa` even when the English says "maximum speed" (`Current trip maximum speed`). Use `najwyższa osiągnięta prędkość` where there is room. In a narrow label, keep the trip or ride in it (`Najw. na trasie`), and don't write `Najw. prędkość` with no trip or ride context beside it.

## Safety wording

Use exactly these. The glossary's safety rows carry the same words with their inflected forms.

| English | Polish | Notes |
|---|---|---|
| Lock (action) | **Zablokuj** | Title or noun: **Blokada**. In-progress or nominal: `Blokowanie`, `zablokowanie`. |
| Unlock (action) | **Odblokuj** | Nominal: `odblokowanie`. |
| Locked | **Zablokowana** | Agrees with `hulajnoga`. `Zablokowano` for the event. |
| Unlocked | **Odblokowana** | `Odblokowano` for the event. Never `niezablokowana`. |
| Lock state unverified | **Stan blokady niezweryfikowany** | Never says it was confirmed. |
| Lock state unknown | **Stan blokady nieznany** | Same meaning as above. |
| Unknown | **nieznany / nieznana / nieznane** | Agrees with its noun. |
| Never | **nigdy** | Polish needs the extra `nie`: `Nigdy nie blokuj`. |
| Always | **zawsze** | |
| Alarm | **alarm** | Alarm sound: **Dźwięk alarmu**. Stop: **Zatrzymaj** (`Zatrzymaj alarm`). |
| PIN | **PIN** | `kod PIN`, `PIN-u`, `PIN-em`. Handlebar PIN: **PIN na kierownicy**. PIN unlock: **Odblokowanie PIN-em**. Never `hasło`. |
| Owner key | **klucz właściciela** | A secret. Not `klucz hulajnogi`, `identyfikator`, `hasło`. |
| Charge limit | **Limit ładowania** | |
| Experimental | **eksperymentalny / -a / -e** | Experimental controls: **Funkcje eksperymentalne**. Badge: `LAB`, spoken form **Eksperymentalne**. |
| Untested | **nieprzetestowana** | Feminine with `funkcja`. No one has tried it on this model. |
| Unverified | **niezweryfikowana** | Volt hasn't confirmed it on this model. |
| Unconfirmed | **niepotwierdzona** | The scooter acknowledged it, but the value wasn't kept or read back. |
| Not confirmed | **Nie potwierdzono** | Two words, so the negation stands. A clause after it takes `czy`, not `że`, so nothing is presupposed: `Nie potwierdzono, czy zaplanowane ładowanie zostało wyłączone`. |
| Confirmed | **potwierdzona**, **Potwierdzono** | Only when the scooter read the value back. |
| not affiliated with NAVEE | **nie jest powiązany z NAVEE** | `Volt %1$s · nie jest powiązany z NAVEE`; the subject is Volt. Not `niezależny`, `nieoficjalny` or `niepowiązany` (a prefix alone fails the check). |

**Lock rules**

- Say a lock state with `zablokowana` or `odblokowana`. **Never** `Blokada wyłączona`, `Blokada: wył.`, `Wyłącz blokadę`, `Zdejmij blokadę`, `Usuń blokadę`, `Odblokuj blokadę`, `Blokada odblokowana` or `Otwórz`: `check.py` rejects them, and "lock off" or "unlock the lock" is ambiguous on a safety control. Unlocking is said of the scooter: `Odblokuj hulajnogę`.
- **Buttons act; states report.** Buttons use `Zablokuj` / `Odblokuj`, states use `Zablokowana` / `Odblokowana`. A Lock button that reads `Zablokowana` tells the rider the scooter is already locked. `check.py` keeps `zablokowanie` and `zablokowana` apart for Lock, Locked, Unlock and Unlocked, but not for the English -ing forms (`Locking…` accepts any form starting `zablok-`) or in other wording. Judges: confirm action or state on every lock key.
- Never `zamek` (the manual's parts diagram has a mechanical `zamek bezpieczeństwa` beside the quick-release lever), `kłódka` or `zabezpieczona`.
- Keep the two words visibly different in a sentence: `Hulajnoga zablokowana. Przesuń, aby odblokować.`
- The checks accept `Nie udało się zablokować` for "Locking failed", and it is the natural wording. A negation the English lacks is still an error in other safety strings, so read the finding before rewording.

**Negation rules**

- **English `not X` in a safety key takes a separate `nie` with a verb:** `Telefon nie jest sparowany`, `Nie potwierdzono`. A prefix alone fails the check (`Telefon niesparowany`, `Zmiana niepotwierdzona`), although Polish spelling favours it. English `un-` words keep the prefix form, as in the table above.
- **`Brak` is the standard "No X":** `Brak funkcji do wypróbowania`, `Brak danych`. **"No X yet" takes `Nie ma jeszcze X`** (`Nie ma jeszcze danych o ruchu`, `Nie ma jeszcze przejazdów`), never `Brak jeszcze`. For English `Missing`, which has no negation word, `Brak` gets a warning for the judge; that is expected.
- **Never:** the check wants `nigdy` or `nie` and refuses `zawsze`, unless the English also says always. It can't tell which verb a `nie` belongs to, so judges read every safety key with `never` or `nigdy` by hand: `Cyfry nigdy nie są pokazywane`.

## Scooter vocabulary

**O** is NAVEE's Polish manual, **E** is established Polish (other sellers' manuals, Android, or common usage from memory), and **P** is proposed here with no outside evidence.

| English | Polish | | English | Polish | |
|---|---|---|---|---|---|
| scooter | hulajnoga (not `skuter`, a motor scooter) | O | drive mode | tryb jazdy | O |
| handlebar | kierownica | O | Kick-start speed | prędkość startowa | O |
| brake lever | dźwignia hamulca | O | Standing start | start z miejsca | P |
| throttle | manetka (manual: `przyspieszacz`) | E | Maximum speed (setting) | prędkość maksymalna | O |
| battery | bateria (not `akumulator`) | O | Top speed (measured) | najwyższa osiągnięta prędkość, najwyższa prędkość | P |
| charger | ładowarka | O | speed limit | limit prędkości | P |
| charging port | gniazdo ładowania | O | Traction control | kontrola trakcji | O |
| range | zasięg | O | Cruise control | tempomat | E |
| speed | prędkość | O | Energy recovery | odzysk energii | E |
| Odometer | przebieg całkowity, ODO | O | Boost | Boost (manual: `tryb BOOST`) | O |
| Trip | trasa | O | Walk Assist | wspomaganie prowadzenia pieszo; short: wspomaganie prowadzenia | P |
| Route (on the map) | ślad przejazdu, ślad; `Ślad GPS` for the GPS route | P | Power Assist | wspomaganie mocy (or `wspomaganie mocą`) | P |
| Rides (records) | przejazdy; riding as an activity: jazda | P | Hill descent control | kontrola zjazdu ze wzniesienia | P |
| Dashboard (Volt's Home) | pulpit | P | Tail light | światło tylne | O |
| scooter's display | wyświetlacz hulajnogi | O | headlight | reflektor, światło przednie | O |
| widget | widżet | E | Turn signal | kierunkowskaz | O |
| firmware | oprogramowanie sprzętowe; `Firmware` when narrow | E | Automatic lights | automatyczne światła | O |
| voltage, current, power | napięcie, prąd, moc | E | Prompt sound | sygnały dźwiękowe | P |
| capacity, health | pojemność, kondycja | E | Daytime running light | światła do jazdy dziennej | E |
| pair / connect | sparuj / połącz | E | Android settings | Opcje programisty, Debugowanie USB, Numer kompilacji, Informacje o telefonie | E |

The manual calls the scooter's display `Deska rozdzielcza` as well as `wyświetlacz`. Volt uses only `wyświetlacz`, because `pulpit` already means Volt's Home. Drive-mode names stay English (the owner's default); the manual's Polish is `tryb spacerowy` (walk, 6 km/h), `tryb standardowy` (D) and `tryb sportowy` (S). D3 made `Eco` and `Turbo` do-not-translate. `Model ID` stays in English, as its glossary definition asks, though `ID modelu` would read more naturally.

**Open for the debate:** Walk Assist (`prowadzenie` alone can read as driving, hence `pieszo`; the manual has `tryb spacerowy` and other sellers `tryb pieszy`); Power Assist (`wspomaganie mocy` is a calque that reads as "assisting the power", and `wspomaganie mocą` or `dodatkowa moc` may be better; the current form stays until the debate rules); Charge done by (`Termin końca ładowania` replaces `Koniec ładowania do`, whose preposition dangles; `ładowanie do godziny` was not used because `do godziny` also reads as "up to an hour").

**Same English, different meaning, so keep them apart**

- Trip `trasa` / Route `ślad` / Rides `przejazdy`. Polish would use `trasa` for both of the first two, and the manual takes it for Trip. So never `ślad trasy`.
- Maximum speed `prędkość maksymalna` (a setting) / Top speed `najwyższa prędkość` (measured).
- Try a control `wypróbuj`, `próba` / Try again `Spróbuj ponownie` (including `more_lab_try_again_value`; never `Wypróbuj ponownie`) / Test `test` / compatibility check `sprawdzanie zgodności`.
- Restore a backup `przywróć` / Set back a try `cofnij zmianę`.
- Dashboard `pulpit` / scooter's display `wyświetlacz`.
- Owner key `klucz właściciela` / JSON or preference key `klucz`: same word, but keep `właściciela` only on the first.
- Connect a charger `podłącz` / Connect over Bluetooth `połącz`. `Najpierw podłącz ładowarkę` gets a glossary warning; that is expected on charger strings, so don't switch to `połącz`.
- While parked `na postoju` or `gdy hulajnoga jest zaparkowana` / at a stop `po zatrzymaniu`: `stop` is the alarm's safety term, so `na postoju` doesn't satisfy it.
- Pair `sparuj` / Connect `połącz` / NAVEE account binding `powiązanie konta`, which is not the disclaimer's `powiązany`.
- Standing start (value 0) / Kick-start speed (the setting).
- Energy recovery `odzysk energii` / restoring data `przywracanie`.

**Matching, for `check.py`:** a form inflects only if it ends in a vowel and its stem has five letters or more, so `hulajnóg`, `hulajnodze`, `tras` and `prób` must be listed. They are. Polish lock words are the exception: `zablokowanie` inflects only as a verbal noun and `zablokowana` only as a participle. Word order matters in a multi-word term, so `limit ładowania` is not found in `ładowania limit`.

## Rules every language follows

These came from the independent review of the first full draft (Spanish, 2026-11-05). They override anything above that disagrees. The table at the end holds this language's fixed words; a term in it is used every time the concept appears, in every area.

1. **Try versus try again.** The glossary's "try" word is only for trying a control (Experimental, Controls to try). An ordinary retry ("Try again", "Try shortly") uses the fixed retry phrase below, so it can never read as "test this".
2. **Subjectless English verbs.** Dialogs and hints such as "Removes…", "Replaces…", "Opens…" describe what will happen. They must not read as a command to the rider, and a destructive confirmation must never sound like an instruction to do it. Use the form the table below gives.
3. **Unconfirmed clauses.** After a negated "confirm" or "verify" ("could not confirm that the schedule turned off"), use a form that does not presuppose the event happened. An unconfirmed state never reads as done. A bare status such as "Logging cleanup unconfirmed" must say what is in doubt, so read the comment for the state: `Czyszczenie niepotwierdzone: zapisywanie dziennika może być nadal włączone`.
4. **Placeholders.** No grammar may depend on a value you can't see: no "at %1$s" that changes with the hour, no elision, article, gender or case that a value would have to agree with. Prefer a label form: "Start time: %1$s". Numbers and units after a placeholder follow this guide's spacing.
5. **Warnings keep the effect.** Where a feature's name replaced a word that explained the hazard (motor braking became energy recovery), a warning sentence still says what happens: it brakes with the motor, so the brakes work harder.
6. **Plurals.** Every category is grammatical, even where the English `one` is wrong or a note says it can't be reached. Never copy "1 minutes" into your language.
7. **Drive modes and level names.** Keep the case the English string uses in that string (Sport, ECO, Drive). Don't change it, and don't translate them.
8. **Fit.** Never swap in a different meaning, or a colloquial word, to fit a budget. Use this guide's accepted abbreviation. If none fits, write the correct word and say so in the reply's notes: an overflow is a design problem that gets fixed, a wrong word isn't noticed.
9. **Naming things on screen.** When a sentence names a button, tab or screen, use exactly that label's translation and this guide's rule for marking it. Don't paraphrase a label.
10. **Same concept, same word.** The same English on different screens gets the same translation unless the screen really changes the meaning. Readings appear under several keys (`readings_*`, `stats_row_*`, `compat_field_*`, `dashboard_*`): the name doesn't change between them.
11. **Opaque English.** If a word-for-word rendering would be unclear ("Connected history", "Charge per gauge", "Battery-side power"), translate the meaning given by the screen and note. Don't copy the shape of the English. "Reported" and "not reported" are never word-for-word calques: use the table.
12. **Retired English terms.** Don't render owner identity, setup key or scooter key as their literal words: they are all the owner key. Don't render "speed cap" literally.
13. **"Dashboard" in the English.** The English fix (D3) has merged, but a few strings still say "dashboard" for the scooter's own screen. Volt's Home is one thing and the scooter's display another: translate what the string means, using the glossary's scooter-display word when the note or screen shows it is the physical display.

### Fixed words

| English concept | Meaning | Polish |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Spróbuj ponownie**, **Spróbuj za chwilę**. Never `Wypróbuj`, which means testing a control. |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **brak danych**, with the name as a label: `Bateria: brak danych`. "No motion report" is `danych o ruchu`, never `raportu o ruchu`: `raport` is the emailed compatibility report. Not `nie zgłoszono`, not `niezgłoszone`. |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **podawane przez hulajnogę** (`sterownik` for the controller; verb `podaje`), agreeing with its noun; short form `wg hulajnogi`, never a bare `podawana`. The scooter's figure, so never `zmierzone` or `potwierdzone`, and not `raportowane`. |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **zapisywanie dziennika NAVEE**. Never `logowanie` (reads as signing in) or `rejestracja`, `rejestrowanie` (reads as creating an account). |
| restore logging | turning that logging back to how it was (usually off) | **Przywróć ustawienie dziennika NAVEE**. Says it goes back to how it was; `Przywróć zapisywanie` would read as turning logging on. |
| Volt's helper | the small helper Volt starts inside Shizuku | **program pomocniczy Volt**. Not `pomocnik` (a person). The computer's capture helper stays `program przechwytujący`. |
| stock charger | the charger that came with the scooter | **standardowa ładowarka**. Not `oryginalna`, which would imply a faster charger isn't genuine. |
| high-speed charger | a faster charger the rider may own | **szybka ładowarka**; alone as an option: `Szybka`. |
| gap (in a recording) | a stretch of a recording with no data | **przerwa w zapisie**, plural `przerwy w zapisie`. Matches the glossary's `zapis`. Not `luka`. |
| elevation | height above sea level on a route | **wysokość n.p.m.**; narrow: `Wysokość`. Not `wzniesienie` (a hill) or `przewyższenie` (height gained). |
| current direction | which way battery current flows | **kierunek prądu**. `Current` is the electric one; never `aktualny kierunek`. |
| throughput | how much energy has passed through the battery | **przepływ energii**; narrow: `Przepływ`; lifetime: `łączny przepływ`. An amount that has passed, not a rate, and never `przepustowość`. |
| battery gauge | the scooter's own battery percentage reading | **wskazanie baterii**; narrow: `wskazanie`. The scooter's own percentage, not a measured level. Not `wskaźnik`, the device. |
| raw | an unprocessed value, also used as a short tag | **surowa wartość**; on a `_short` rung, as a tag agreeing with its noun: `(surowy)`, `(surowe)`. Alone `surowy` can read as "strict", so use it only beside a noun. See the row below for placeholders. |
| last seen | when Volt last had data from the scooter | **ostatni odczyt**, as a label: `Ostatni odczyt: %1$s`. Never `ostatnio widziana`, which reads as a person sighted and carries gender. |
| add | adding a widget, scooter or item | **Dodaj**; infinitive `dodać`. |
| enter (typing) | typing a value | **Wpisz** on a field prompt (`Wpisz nowy PIN`), `należy wpisać` in a how-to step. Not `Wprowadź`, and not `Podaj`, which is the scooter's verb above. |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **aplikacja**, always; Polish has no shorter neutral word and no second one. Never `apka` or `program`. Before a name: `aplikacja Volt`. |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | Reflexive, with the scooter in the instrumental: **połączyć się z hulajnogą** (`należy połączyć się z hulajnogą`). Button: `Połącz`. Event: `Połączono`. Never `połączyć hulajnogę`, which reads as joining it to something; a charger takes `podłącz`. |
| set a value | one verb for setting a value on the scooter | **Ustaw**, infinitive `ustawić` (`Ustaw limit ładowania`). `Zmień` only where the English says change. |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | **Spowoduje to** + verbal noun: `Spowoduje to usunięcie konfiguracji połączenia z tego telefonu.` Never `Usuń…`, a command. In a summary line, the bare noun: `Ustawienia aplikacji: zastąpienie 3 ustawień`. A hint that opens something: `Otwiera ustawienia baterii`. |
| screen and section titles | noun form or verb form, with one example | Noun form: `Ustawienia jazdy`, `Przywracanie kopii zapasowej`. The button on that screen stays imperative: `Przywróć`. |
| a "first" prerequisite or refusal reason | requirement form, one per English | **Najpierw należy** + infinitive (`Najpierw należy zatrzymać hulajnogę`, `Najpierw należy połączyć się z hulajnogą`), in `controls_*`, `ble_*`, `service_*`, toasts and tiles alike. Not `Najpierw zatrzymaj`, not `Najpierw trzeba`. |
| resolve a warning | deal with its cause | **usunąć przyczynę ostrzeżenia**. Never bare `usuń ostrzeżenie`, which reads as dismissing the message. |
| wait | an instruction to wait | **poczekać** (`Należy poczekać na…`). Not `zaczekać`. |
| watch a charge | Volt following a charge for alerts | **monitorować**, matching the button `Zakończ monitorowanie`. Not `śledzić`. |
| naming Charge done by in a sentence | the feature name | **funkcja „Termin końca ładowania”**. Not `opcja`. |
| configure a PIN | giving the PIN a value | **ustawić PIN**; prerequisite: `należy ustawić i włączyć PIN na kierownicy`. Not `skonfigurować`. |
| recording a ride | the action, the state and the saved file | Action **Nagraj przejazd**; state **Nagrywanie**, `Podczas nagrywania`; the saved thing **nagranie**. Not `zapis`, because `Zapisz` is Save on the same screens (`Zapisz przejazd` would say save the ride). A charge recording stays **zapis ładowania**, a developer log, and a gap stays `przerwa w zapisie`. |
| slot (saved layouts) | a place holding a layout | **miejsce**, naming the layout when it is what changes: `Zastąpić układ w miejscu 3?`. Not `slot`. |
| estimate (noun) | a computed figure | **oszacowanie**, plural `oszacowania`. Not `szacunek`, which also means respect. The adjective `szacowany` and the abbreviation `Szac.` stay. |
| storage (ride, history, charge) | where saved data lives | Name the data: `Zapisane przejazdy niedostępne`, `Zapisana historia niedostępna`. Not `Pamięć przejazdów`, which gives rides a memory. `Pamięć lokalna` for the phone's own storage is fine. |
| prompt (a system dialog) | the dialog on the phone | **komunikat**. Not `monit`, not `pytanie`. |
| More as a place | the screen | **sekcja „Więcej”**. Not `karta`. |
| offered for a model / state on a model | which preposition | Offered **dla modelu** (`dla Twojego modelu`); a state or test **w modelu** (`w tym modelu`, `nie przetestowała tej zmiany w tym modelu`). Not `na modelu`. |
| a shared rider's credential | the 12-character value for a shared rider | **klucz współużytkownika**. Proposed; the debate decides it. Never `klucz właściciela` for this one. |
| raw after a placeholder or on a full rung | a fixed form, since `%1$s` has an unseen gender | **(surowa wartość)** on every full rung and after any placeholder. The agreeing tags `(surowy)`, `(surowe)`, `(surowa)` only on `_short` rungs with a fixed noun. The `surowa` chip stands for `wartość`. |
| battery sign states | plus or minus | **znak**, `Znaki baterii`. Not `oznaki` (symptoms) or `oznaczenia` (markings). |
| timed charging | the reading of scheduled charging | **zaplanowane ładowanie**, the same as the setting. Not `ładowanie czasowe`, which reads as temporary. |
| voice language | the setting | **język komunikatów głosowych**. Not `język głosu`. |

## Sources consulted

Fetched, with the text extracted; the controls, display and icon sections were read closely:

- NAVEE ST5 Pro / ST5 Max, "Ważne informacje" (Polish), via Media Expert: https://www.mediaexpert.pl/products/files/11/11669881/instrukcja-obslugi-NAVEE_ST5-Max-2.pdf. It is the main source of the **O** terms: `hulajnoga`, `Blokada`, `zablokowana`, `zablokować/odblokować`, `Prędkość startowa`, `ODO`, `Trasa`, `Pozostały zasięg`, `TCS (System kontroli trakcji)`, `Automatyczne światło`, `kierunkowskaz`, `tryb BOOST`, `aplikacja NAVEE`.

Search snippets only (the page itself wasn't read):

- NAVEE V40i Pro / V50i Pro Polish manual, V1.0-PL: https://service.naveetech.com/wp-content/uploads/2024/07/V40i-Pro-V50i-Pro-PL.pdf (404 when fetched). The snippet matches the lock wording above.
- Other brands' Polish manuals on mediaexpert.pl (Ruptor, Ducati, Aprilia, MV Agusta) for `tempomat` and `tryb pieszy`.
- MediaMarkt and x-kom NAVEE listings for `system odzyskiwania energii`.
- Android's developer page, https://developer.android.com/studio/debug/dev-options?hl=pl, plus adapty.io and ASUS Polish help for `Opcje programisty`, `Debugowanie USB`, `Numer kompilacji`. The Android page came back in English when fetched.

Repository: `tools/l10n/fontmetrics.py` over `res/font/saira_*.ttf` covers every Polish letter (ą ć ę ł ń ó ś ź ż and capitals), „ ” « » – … · ° and the no-break space in all four weights, with real hmtx advances. ✓ is missing, as expected: it is on the README's fallback list.

**Not found:** the NAVEE app's Polish wording. No Polish app strings turned up, so charge limit, PIN, owner key, shared riders, energy recovery, cruise control, Walk Assist and Power Assist have no official Polish here. No APK was downloaded. Volt's own "official" English terms come from D3.

**From memory, not checked online:** the punctuation, capitalization and abbreviation rules (PWN conventions), CLDR's plural rules and number format for Polish, Google's `Twój` habit. The plural categories match `l10n/lang/pl.tsv` and the README.
