# Polish style guide (pl)

**Status: Claude-only proposal, 2026-11-05.** Drafted by Claude alone. The three vendors have not debated it, no native reader has reviewed it, and nothing here is final. It uses the D3 English terms (approved, not merged yet), so "owner key", "shared rider", "Maximum speed" and so on are the source meanings even where `res/values` still shows older words. Plan 7.2 stage 5 still decides every safety string.

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

## Punctuation and quotes

- Quotes: `„…”` outside, `«…»` inside. Closing `”` is U+201D, never a straight `"`.
- Quote an option or button named inside a sentence: `opcję „Dźwięk alarmu”`. The quoted name stays in the nominative, so it needn't decline.
- Commas are required before `aby, że, który, gdy, jeśli, ponieważ, bo`. No comma before a single `i, oraz, lub, albo`.
- No full stop on buttons, labels, titles or chips, unless the English sentence has one.
- `…` is one character, as in English. Ranges take an unspaced `–` (`10–20 km`); a pause is a spaced `–`, never `-`. After a colon, continue in lower case. `·` separators stay as the English has them.
- Keep spaces and line breaks exactly as the English has them. Don't add no-break spaces after one-letter words (`i, w, z, o, a, u`).

## Capitalization

Sentence case for buttons, labels, titles and tabs: first word only (`Ustawienia jazdy`, `Limit ładowania`, `Kopia zapasowa`), never title case. Days and months are lower case. All capitals only where the English has them (`WALK`, `ECO`, `LAB`, `PID`). Names stay as written: Volt, NAVEE, Shizuku, ADB, GPX, CSV, Bluetooth, USB.

## Names that can't decline

`check.py` looks for protected tokens as written, and Polish wants to decline them. Put a noun before the name and decline the noun.

- `aplikacja Volt`, `w aplikacji Volt` (not `w Volcie`, `Volta`); `w aplikacji NAVEE` (the manual's own wording); `aplikacja Shizuku`.
- `tryb SPORT`, `w trybie ECO`; `profil Eco`, `profil Turbo`; `plik GPX`, `plik CSV`.
- Translate the names of the drive modes only if the owner decides to. The manual's Polish is in the vocabulary table below.

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
- 24-hour time from the system (`7:00`); `o 7:00` in a sentence, `do 7:00` for a deadline. Dates come from the system; written out, the month is genitive and lower case (`5 listopada 2026`).

## Shortening labels

Fit is measured, so use a shorter rung only as far as the budget needs. In order:

1. Say less: drop the word the context supplies (`Temperatura baterii`, then `Temp. baterii`, then `Temp.`).
2. Use a shorter natural word: `Opcje` for `Ustawienia` in a 62 dp tile (`Ustawienia` measures 65 dp).
3. Use only an abbreviation from the list below.

Never break a word, never hyphenate to fit, never shrink text. An abbreviation that keeps the word's last letter has no full stop (`nr`); one that cuts the word has one (`maks.`).

**Approved abbreviations**

| Short | Means | Short | Means |
|---|---|---|---|
| `ODO` | przebieg całkowity (the NAVEE manual's label) | `Temp.` | temperatura |
| `Maks.` | maksymalna (not `Max`) | `Śr.` | średnia |
| `Najw.` | najwyższa | `Szac.` | szacowany |
| `Prędk.` | prędkość | `Bat.` | baterii |
| `Ust.` | ustawienia, only with a following noun (`Ust. jazdy`); alone it reads as "ustęp" | `Ład.` | ładowania |
| `Wyśw.` | wyświetlacz | `Poz.` | poziom |
| `Pojem.` | pojemność | `Eksperym.` | eksperymentalne |
| `Niezweryf.` | niezweryfikowana | `Nieprzetest.` | nieprzetestowana |
| `godz.` | godzina | `nr` | numer |

**Refused:** `Św.` for światła (it reads as "święty"; use `Liniowe`, `Gwiezdne`), `Hulajn.`, `Ustaw.` (reads as the imperative "Ustaw"), `Min.` for minimum (clashes with `min`), and any shortening of a lock word (`Zablok.`, `Odblok.`, `Blok.`): only the prefix tells locked from unlocked, so both words stay whole. Drop other words first.

**Real rungs, measured with `fontmetrics` (hmtx at the given size, which errs slightly wide)**

| English rungs | Polish rungs | Widths |
|---|---|---|
| Total odometer / Odometer / Odo. | Przebieg całkowity / Przebieg / ODO | ODO 25 dp at 12 sp (Odo. 26) |
| Maximum speed / Max. speed | Prędkość maksymalna / Maks. prędkość | 89.9 of 90 dp at 12.5 sp: tight, recheck with java.awt |
| Trip top speed / Top speed | Najw. prędkość na trasie / Najw. prędkość | 88 dp at 12.5 sp |
| Battery temp. / Temp. | Temp. baterii / Temp. | 73 / 34 dp at 12 sp |
| Trip avg. speed / Avg. speed | Śr. prędkość na trasie / Śr. prędkość | 68 dp at 12 sp (English 61) |
| Est. range / Range | Szac. zasięg / Zasięg | 68 / 37 dp at 12 sp |
| Trip distance / Distance | Dystans trasy / Dystans | 78 dp at 12 sp |
| Electronic lock / Lock | Blokada elektroniczna / Blokada | 45 dp at 12 sp |
| Unverified here / Unverified | Niezweryf. tutaj / Niezweryf. | 61 dp at 12 sp |
| Untested here / Untested | Nieprzetest. tutaj / Nieprzetest. | 68 dp at 12 sp |
| Ride settings, 64 dp tile | Ust. jazdy | 55 of 64 dp at 12 sp |
| Battery settings, 64 dp tile | Ust. baterii | 61 of 64 dp at 12 sp |
| Live Stats, 64 dp tile | Na żywo | 49 of 64 dp at 12 sp |
| My scooters, 64 dp tile | Hulajnogi | 53 of 64 dp at 12 sp |
| Experimental controls, 64 dp tile | Eksperym. | 59 of 64 dp at 12 sp |
| PIN & proximity, 64 dp tile | PIN i zbliż. | 58 of 64 dp at 12 sp |
| Line lights, 64 dp tile | Liniowe | fits |

These are examples of the method, not approved strings. `Zbliż.` is allowed only in this one label.

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
| Not confirmed | **Nie potwierdzono** | Two words, so the negation stands. |
| Confirmed | **potwierdzona**, **Potwierdzono** | Only when the scooter read the value back. |
| not affiliated with NAVEE | **nie jest powiązany z NAVEE** | `Volt %1$s · nie jest powiązany z NAVEE`; the subject is Volt. Not `niezależny`, `nieoficjalny` or `niepowiązany` (a prefix alone fails the check). |

**Lock rules**

- Say a lock state with `zablokowana` or `odblokowana`. **Never** `Blokada wyłączona`, `Wyłącz blokadę`, `Zdejmij blokadę`, `Usuń blokadę` or `Otwórz`: `check.py` rejects them, and "lock off" is ambiguous on a safety control.
- Never `zamek` (the manual's parts diagram has a mechanical `zamek bezpieczeństwa` beside the quick-release lever), `kłódka` or `zabezpieczona`.
- Keep the two words visibly different in a sentence: `Hulajnoga zablokowana. Przesuń, aby odblokować.`
- The checks accept `Nie udało się zablokować` for "Locking failed", and it is the natural wording. A negation the English lacks is still an error in other safety strings, so read the finding before rewording.

## Scooter vocabulary

**O** is NAVEE's Polish manual, **E** is established Polish (other sellers' manuals, Android, or common usage from memory), and **P** is proposed here with no outside evidence.

| English | Polish | | English | Polish | |
|---|---|---|---|---|---|
| scooter | hulajnoga (not `skuter`, a motor scooter) | O | drive mode | tryb jazdy | O |
| handlebar | kierownica | O | Kick-start speed | prędkość startowa | O |
| brake lever | dźwignia hamulca | O | Standing start | start z miejsca | P |
| throttle | manetka (manual: `przyspieszacz`) | E | Maximum speed (setting) | prędkość maksymalna | O |
| battery | bateria (not `akumulator`) | O | Top speed (measured) | najwyższa prędkość | P |
| charger | ładowarka | O | speed limit | limit prędkości | P |
| charging port | gniazdo ładowania | O | Traction control | kontrola trakcji | O |
| range | zasięg | O | Cruise control | tempomat | E |
| speed | prędkość | O | Energy recovery | odzysk energii | E |
| Odometer | przebieg całkowity, ODO | O | Boost | Boost (manual: `tryb BOOST`) | O |
| Trip | trasa | O | Walk Assist | wspomaganie prowadzenia | P |
| Route (on the map) | ślad trasy, ślad | P | Power Assist | wspomaganie mocy | P |
| Rides (records) | przejazdy; riding as an activity: jazda | P | Hill descent control | kontrola zjazdu ze wzniesienia | P |
| Dashboard (Volt's Home) | pulpit | P | Tail light | światło tylne | O |
| scooter's display | wyświetlacz hulajnogi | O | headlight | reflektor, światło przednie | O |
| widget | widżet | E | Turn signal | kierunkowskaz | O |
| firmware | oprogramowanie sprzętowe; `Firmware` when narrow | E | Automatic lights | automatyczne światła | O |
| voltage, current, power | napięcie, prąd, moc | E | Prompt sound | sygnały dźwiękowe | P |
| capacity, health | pojemność, kondycja | E | Daytime running light | światła do jazdy dziennej | E |
| pair / connect | sparuj / połącz | E | Android settings | Opcje programisty, Debugowanie USB, Numer kompilacji, Informacje o telefonie | E |

The manual calls the scooter's display `Deska rozdzielcza` as well as `wyświetlacz`. Volt uses only `wyświetlacz`, because `pulpit` already means Volt's Home. Drive-mode names stay English (the owner's default); the manual's Polish is `tryb spacerowy` (walk, 6 km/h), `tryb standardowy` (D) and `tryb sportowy` (S). D3 made `Eco` and `Turbo` do-not-translate. `Model ID` stays in English, as its glossary definition asks, though `ID modelu` would read more naturally.

**Same English, different meaning, so keep them apart**

- Trip `trasa` / Route `ślad` / Rides `przejazdy`. Polish would use `trasa` for both of the first two, and the manual takes it for Trip.
- Maximum speed `prędkość maksymalna` (a setting) / Top speed `najwyższa prędkość` (measured).
- Try `wypróbuj`, `próba` / Test `test` / compatibility check `sprawdzanie zgodności`.
- Restore a backup `przywróć` / Set back a try `cofnij zmianę`.
- Dashboard `pulpit` / scooter's display `wyświetlacz`.
- Owner key `klucz właściciela` / JSON or preference key `klucz`: same word, but keep `właściciela` only on the first.
- Pair `sparuj` / Connect `połącz` / NAVEE account binding `powiązanie konta`, which is not the disclaimer's `powiązany`.
- Standing start (value 0) / Kick-start speed (the setting).
- Energy recovery `odzysk energii` / restoring data `przywracanie`.

**Matching, for `check.py`:** a form inflects only if it ends in a vowel and its stem has five letters or more, so `hulajnóg`, `hulajnodze`, `tras` and `prób` must be listed. They are. Word order matters in a multi-word term, so `limit ładowania` is not found in `ładowania limit`.

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
