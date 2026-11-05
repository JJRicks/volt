# Volt style guide: Czech (cs)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in the last section; the three-vendor debate decides later. No native speaker has reviewed it. "Approved" below means "the wording this proposal asks every drafter to use". The form of address and the plural set come from plan 7.4, which the owner has not yet confirmed for Czech.

## 1. Register and form of address

- Use **vy**, always, with a lower-case *vy* and *váš*: *Připojte nabíječku*, *vaše koloběžka*. Never *ty* forms (*připoj*, *tvoje*), and never capital *Vy* or *Váš*, which belongs in letters, not in an app.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the infinitive:** *Uložit*, *Připojit*, *Zamknout*. **Instructions in sentences use the vy imperative:** *Znovu připojte koloběžku*. Never the *ty* imperative on a button (*Zamkni*). Don't mix the two forms in one string.
- **Never make the rider's gender visible.** Past participles and short adjectives agree in *vy* (*jste připojil*, *jste připojena*), so rewrite: *Koloběžka je připojena*, *Nastavení se uložilo*, *Zadejte PIN*.
- What Volt or the scooter did: use the scooter as subject (*Koloběžka odmítla hodnotu*) or a reflexive (*Nepodařilo se přečíst*). Volt never speaks in the first person: no *já*, no *moje*. A choice the rider is making may keep it (*Upozornit mě*), but drop *my* where you can: *Import my owner setup* is *Importovat nastavení vlastníka*.
- **Volt** as a subject is masculine (*Volt není přidružen*). In other cases don't decline it: write *aplikace Volt*, *v aplikaci Volt*, never *Voltu* or *Voltem*, which also read as the unit. **NAVEE** never declines: *aplikace NAVEE*, *společnosti NAVEE*.

## 2. Punctuation and quotes

- Quotes: **„…“** first, **‚…‘** inside. They sit tight against the words. Never straight quotes, never «…».
- A sentence that names a button, tab or screen puts its exact label in „…“: *Klepněte na „Povolit“*, *otevřete „Záloha a obnovení“*. A feature mentioned in passing stays unquoted.
- A period closes outside the quote after a quoted word or phrase, as in the examples above.
- Czech commas are compulsory before subordinate clauses (*že*, *když*, *protože*, *který*, *pokud*, *aby*): *Zkontrolujte, zda je koloběžka zapnutá.* English has no comma there, so add it. No comma before a plain *a*, *i* or *nebo*.
- Keep the English's `·` separators and `…` as written. A process in progress keeps `…` right after the word: *Připojování…*
- A colon has no space before it, one space after, and lower case after unless a name follows: *Poslední známý stav zámku: zamčeno*.
- Dash in a sentence: `–` with spaces. A range: `–` with no spaces (*10–20 km*). No em dash.
- Don't add no-break spaces after one-letter prepositions (*k, s, v, z, o, u, a, i*). It is a print rule, and it costs line breaks in narrow labels. Judgment call.
- Saira has no U+202F; never use it. The no-break space is U+00A0.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere:** only the first word and names take a capital. Button: *Zkusit znovu*. Label: *Limit nabití*. Title: *Experimentální funkce*. Never English Title Case, even where the English has it (*Walk Assist* becomes *Asistent chůze*).
- After a colon, lower case (section 2).
- Feature names are lower case mid-sentence: *zapněte plánované nabíjení*.
- Days and months are lower case. So are language names, in adjectival form (*anglicky*, *v angličtině*).
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android. Drive modes and level names are never translated and keep the case the English string uses in that string: *režim Sport*, *ECO*.
- Capital letters keep their marks, including where Volt upper-cases text: *BATERIE*, *ÚDAJE*, *JÍZDY*.

## 4. Numbers, dates and units

- Decimal comma; a no-break space (U+00A0) groups thousands: *1 234,5*. Volt's `Fmt` formats live numbers for the phone's region; never rewrite a placeholder's number or add separators around one.
- **Percent takes a no-break space (U+00A0):** `%1$s %%` gives *85 %*. Czech writes *85%* without a space only as an adjective (*85procentní*); don't use that form, write *85 %*. Otherwise a narrow label can wrap the sign onto its own line. The rule covers `formatted="false"` text too.
- Numbers in the English stay as digits. Don't write them out or change them.
- Unit symbols stay and take the same no-break space after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *kilometry*, *míle*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5. března*; numeric dates have spaces, *5. 3. 2026*. Time is 24-hour with a colon, *7:30*. Never *a.m./p.m.*
- **Plurals: all four categories, every time, each with its placeholder.**

  | Category | Numbers | Case | Example, with `%1$d` |
  |---|---|---|---|
  | `one` | exactly 1 | nominative singular | *%1$d koloběžka*, *%1$d minuta* |
  | `few` | 2, 3, 4 | nominative plural | *%1$d koloběžky*, *%1$d minuty* |
  | `many` | decimals only (1,5) | genitive singular | *%1$d koloběžky*, *%1$d minuty* |
  | `other` | 0 and 5 and up | genitive plural | *%1$d koloběžek*, *%1$d minut* |

  The Case column is for a bare count. After a preposition the case follows the sentence, and each category still gets its own form: *před 1 minutou*, *před 2 minutami*, *před 5 minutami*, *za 1 minutu*. `many` stays grammatical there too: *před 1,5 minuty*.

  Czech is simple about large numbers: 21, 22 and 25 take `other` (*21 minut*), unlike Polish or Russian. `many` is rarely seen, since Volt counts whole numbers, but it must be present and grammatical. `check.py` treats `one` and `many` as single-number categories, which could let them drop the placeholder; don't, keep it.
- A verb must agree with the count too, so prefer a label form that avoids one: *Přeskočené koloběžky: %1$d*, or put the verb in each plural item.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. A single word must fit one line. Try these in order:

1. Drop words the screen already supplies: *Stav baterie* → *Stav*; *Vzdálenost úseku* → *Vzdálenost*.
2. Use a shorter common word.
3. Use an accepted abbreviation from this table. Nothing else: no invented cuts such as *Nast.*, *Bat.*, *Nab.*, *Rek.*, *Zam.*, *Odem.*, *Neov.*, *kap.* (which a Czech reader takes as *kapitola*), *odh.*

| Full | Abbreviated | Width at 12.5sp, Saira Medium (dp) | Note |
|---|---|---|---|
| Maximální rychlost | **Max. rychlost** | 110 → 78 | The setting. Fits the 90dp tile. `Max.` is only for the setting |
| Nejvyšší dosažená rychlost | **Nejv. dosažená** | → 88 | The measured value. Fits 90dp. Never *Max.*, never *Nejvyšší rychlost* |
| Experimentální funkce | **Experim. funkce** | 130 → 93 | Wraps at the space. *Experim.* is 49dp at 12sp, and *Experimentální* (84dp at 12sp) is wider than the 64dp Quick access line, so use the abbreviation on the 64dp full rung. On the 90dp `_short` rung *Experimentální* (88dp) fits alone |
| Teplota baterie | **Tepl. baterie** | → 71 | Then *Teplota* (43) alone. *Teplota* needs no cut |
| průměrná rychlost | **prům. rychlost** | → 84 | |
| celkový, celkem | **celk.** | | *Celk. nájezd* 69 |
| rychlost | **rychl.** | 36 | Only where the noun around it is clear |
| maximum, minimum | **max.**, **min.** | | For speed, *max.* is only the setting; a measured speed is *Nejv. dosažená*. Other measured values may use it (*max. teplota*) |
| přibližně | **cca** | | No period |
| est. | **(odhad)**, the tag **odhad** | 26 at 9sp | Never *odh.* *Do plného nabití (odhad)*; the bare tag after a figure is *odhad* (40dp budget). Before a value in a sentence, *cca* |

**Real ladders:**

| Key | Full | `_short` | `_compact` |
|---|---|---|---|
| `readings_temperature_c` | Teplota baterie | Tepl. baterie | Teplota |
| `readings_odometer_km` | Celkový nájezd | Celk. nájezd | Nájezd |
| `readings_trip_km` | Vzdálenost aktuálního úseku | Vzdálenost úseku | Vzdálenost |
| `readings_rated_capacity_ah` | Jmenovitá kapacita baterie | Jmenovitá kapacita | Kapacita |
| `more_lab_status_unverified` | Na vašem modelu neověřeno | Zde neověřeno | Neověřeno |

**Odometer has no good abbreviation.** Czech has no *Odo.*: *Odom.* and *Tach.* are not words a rider knows, and *tachometr* is also the speedometer. So the rung for English *Odo.* is the word **Nájezd** (40dp), not an abbreviation. *Celkový nájezd* is 86dp, and `_short` is *Celk. nájezd* (69dp), which is clearer beside trip distance; a card too narrow for it falls to `_compact`. *Počítadlo kilometrů* is out because the unit can be miles.

Measured words that fit as they are: *Zámek* 39, *Nastavení* 58, *Baterie* 42, *Jízdy* 29 (all under the 62dp tab budget); *Spoluuživatel* 79; *koloběžky* 57, *oprávnění* 56, *statistiky* 53, *přiblížení* 51 at 12sp, all under the 64dp shortcut line. *Plán nabíjení* 75 fits the 90dp tile. *Plánované nabíjení* is 110, so it wraps at the space. *Nabít včas* is 58 at 12sp, so it fits the 64dp line whole.

An abbreviation ends in a period, and the period counts toward the width. A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Zamknout, Odemknout, Zamčeno, Odemčeno, Neověřeno, Neznámé, Nikdy, Vždy, Alarm, PIN*. If one of these doesn't fit, write the whole word and report it in your notes.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Czech | Rules |
|---|---|---|
| Lock (action) | **Zamknout** | Imperative *zamkněte*. Noun *zamknutí*. Never *blokovat* or *uzamknout*. *zamčení* appears only in *Doba zamčení* |
| Lock (noun, tab title) | **Zámek** | Never *Blokace* |
| Locked | **Zamčeno** | Agreeing with the scooter: *zamčená*. Never *zamknuto*, which sits one glance from *zapnuto* |
| Unlock (action) | **Odemknout** | Imperative *odemkněte*. Noun *odemknutí*, never *odemčení*. Never *odblokovat* or *otevřít* |
| Unlocked | **Odemčeno** | Agreeing with the scooter: *odemčená*. Never *nezamčeno*, *otevřeno* or *volné* |
| Lock state unverified | **Stav zámku neověřen** | Must never read as confirmed. Keep *ne-* |
| Lock state unknown | **Stav zámku neznámý** | Must never read as confirmed |
| Unknown (alone) | **Neznámé** | As Android writes it. *Neznámý* with a masculine noun, *neznámá* with a feminine one |
| Unverified | **Neověřeno** | *neověřený*, *neověřená* in agreement. Never *nepotvrzeno* |
| Never | **Nikdy** | Czech needs a second negation with a verb: *Nikdy nevypínat*. That is one negative, never read it as a double |
| Always | **Vždy** | *Always on*: **Vždy zapnuto** |
| Alarm | **Alarm** | Never *budík* (alarm clock). Never *upozornění*: an alert is **Upozornění**, and they stay distinct |
| Alarm sound | **Zvukový alarm** | The choice that sounds, against *Upozornit mě* |
| Stop (a sounding alarm) | **Zastavit** | |
| PIN | **PIN** | Masculine. In the nominative and accusative write *PIN*; in other cases *kód PIN*, *kódu PIN*, *kódem PIN*. Never *heslo* (password) |
| Handlebar PIN | **PIN na řídítkách** | Toggle row: **Odemknutí kódem PIN**. Keep *na řídítkách*. A password is **heslo**; keep them separate |
| Owner key | **klíč vlastníka** | A secret. Czech has one word for a physical and a digital key; the context and *vlastníka* carry it. "Not a password" is *Není to heslo* |
| Charge limit | **Limit nabití** | Never *strop*. In a sentence: *limit nabití 80 %* |
| Experimental | **Experimentální** | Same form for every gender. *Experimentální funkce* for the section |
| Untested | **Nevyzkoušeno** | From *Vyzkoušet*, the try verb. Never *otestováno* in any form |
| Unconfirmed, Not confirmed | **Nepotvrzeno** | Both are the same word |
| not affiliated with NAVEE | **není přidružen k NAVEE** | Full form *Volt není přidružen ke společnosti NAVEE*. Masculine, agreeing with Volt. Never soften it: no *neoficiální*, *nezávislý na*, *není přímo spojen*. *není spojen* is out too, because *spojení* reads as a Bluetooth link |

- **Words that look right and mean the opposite, or something else:**
  - *Zamčeno* and *Odemčeno* differ by their prefix, and *zamknout* and *odemknout* likewise. Write them whole, and never shorten them to the shared stem.
  - A button is never the state word. *Zamčeno* on a button reads as already locked; the action is *Zamknout*.
  - *nezamčeno* looks like a synonym of *odemčeno* but reads as a warning about something left undone. Don't use it.
  - *Zajištěno* (secured) sounds like locked but promises safety. Don't use it.
  - *otevřít* is opening a screen or file, never unlocking.
  - *Zapnuto* and *Vypnuto* differ by a prefix too. Never replace them with *Ano/Ne*.
  - *spolujezdec* looks like shared rider and means a passenger. Never use it.
  - *tachometr* is a speedometer in everyday speech. Never use it for the odometer.
- Don't describe an unlock as removing the lock (*Zrušit zámek*); say *Odemknout*.
- Keep every negation. Czech negation is usually a joined prefix (*nelze*, *není*, *nepodařilo se*). See "Known check false alarm" below.
- On and off: settings and lights both use **Zapnuto** and **Vypnuto**. Agree in gender only where a noun is in the same phrase (*vypnutá rekuperace*).
- Don't add reassurance the English lacks (*bezpečně*, *zaručeně*), and don't add warnings it doesn't have.

**Known check false alarm.** `check.py` finds an English *not* or *no* through the whole words `ne`, `nikdy`, `žádný`, `žádná`, `žádné`, `bez`, `nic`, and through a *ne-* or *bez-* word only when more than three letters follow the prefix. So *Nepotvrzeno*, *Nepodařilo*, *neznámý* and *neověřeno* count, but short words such as *nelze*, *není*, *nebyl*, *nemá*, *nesmí* and *nejde* (also caught by the ordinary-word *nej-*) report "no negation word", and in a safety key that is an error. `more_footer`'s *není přidružen* is one such case. Write the correct Czech anyway and say so in your notes. Don't bend a sentence around *bez* or *žádný* to pass. This has been reported to the owner as a tooling gap.

**Not machine-checked in Czech.** The tool has no on/off state words and no opposite or removal lists for `cs`, so a swapped *Zapnuto*/*Vypnuto* and *Zrušit zámek* pass it. Only the back-translation and the judges catch them, so check these yourself.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Three speeds:** the setting is **Maximální rychlost**. A limit below it is **limit rychlosti**. The measured value on a trip or ride is **Nejvyšší dosažená rychlost**. Never swap them. *Nejvyšší rychlost* alone is forbidden, because Czech readers take it as a synonym of *maximální*; *dosažená* is what says measured.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Today's English keeps that old meaning, and those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until D3 lands. The `top-speed` and `maximum-speed` glossary rows already hold D3's meaning, so don't apply them to these keys now. Flag them in your notes; a judge answers fallback. Every other key with these terms except `rides_tile_max_speed` already matches D3.
- **`rides_tile_max_speed`** says Max speed for the speed measured on a ride, and D3 only renames it to Top speed. The meaning doesn't change, so translate it now as **Nejv. dosažená**, never *Max. rychlost*. No glossary row covers "Max speed", so `check.py` stays silent on this swap.
- **Rides, trips, routes:** a recorded ride is a **jízda**. *Trip* (the distance since a reset) is **úsek**, never the loan *trip*. A path on the map is a **trasa**.
- **Try, retry, test:** Trying a control (Experimental, Controls to try) is **Vyzkoušet**, noun **vyzkoušení** for the act. One attempt, counted or listed, is **pokus**: *Vymazáno pokusů: %1$d*, *Nedokončené pokusy*, *Před pokusem*. An ordinary retry is never *Vyzkoušet*: use the retry phrase in Fixed words. The `try` glossary row also lists the retry forms, because the matcher sees "Try" in "try again"; that doesn't make them a word for trying a control. A guided test is **test**, verb **otestovat**; *Test scheduled charging* is **Otestovat plánované nabíjení**. A compatibility check is **kontrola kompatibility**.
- **Controls:** the Experimental card's controls are **funkce**, which doesn't change between singular and plural in the nominative. *Ovládací prvky* is too long for any tile. *Traction control* is **Kontrola trakce**; *Cruise control* is **Tempomat**.
- **Overspeed:** *Overspeed active control* brakes the motor above the speed limit, so it is **Aktivní brzdění při překročení rychlosti**, and *Overspeed control* (`compat_field_*`, `readings_*`, `stats_row_*`) is **Brzdění při překročení rychlosti**, short form *Brzdění při překročení*. Never *kontrola*, which reads as checking or monitoring and hides that the motor brakes (rule 5). *Overspeed alert* stays **Upozornění na překročení rychlosti**.
- **Motors:** *Dual motor drive* is **Dvoumotorový pohon**. The option *Dual motor* is **Dva motory**, next to *Jeden motor*.
- **Dashboard vs display:** Volt's Home layout is the **Přehled**. The scooter's own screen is the **displej koloběžky**. They never swap. Don't use *přístrojová deska* or *palubní deska*.
- **Settings vs setup:** Czech uses **nastavení** for both. Where a one-time setup could be confused with settings, say *úvodní nastavení*. Owner setup stays **nastavení vlastníka**.
- **Restore vs set back:** bringing back a backup is **Obnovit**, always. Undoing a try, or putting back a test's earlier value even when the English says restore, is **Vrátit zpět**. The `restore` glossary row lists the *vrátit* forms only because "restore logging" uses them (Fixed words). Energy recovery is **Rekuperace energie**, unrelated to either.
- **Readback:** **zpětné načtení**, a neutral word, because a readback can also fail to confirm. Never a phrase with *potvrzení* inside it.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Odmítnuto**; the longer form is *Odmítnuto koloběžkou*. Never *Zamítnuto*.
- **Pair, bind, connect:** Bluetooth pairing is **spárovat**, as Android writes the button; *párování* for the process. NAVEE account binding is **propojit** (*propojit účet*). A live link is **připojit**.
- **Owner key parts:** the file is the **soubor nastavení vlastníka**, shortened to **soubor vlastníka** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Roles:** owner is **vlastník**, shared rider is **spoluuživatel**. The compact role label (`more_role_shared`, English *Shared*) is **Spoluuživatel** (79dp), never *Sdílený*, which reads as a shared scooter.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes, and agree with *rekuperace*: *Nízká, Střední, Vysoká*; off is *Vypnuto*.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them, with *Boost* declining only in a sentence (*Boostu*, *Boostem*). **Odhad Eco** is a range option, translated.
- **Charge done by:** the feature name is **Nabít včas**, on every rung, and its time setting **Čas dokončení nabití**. The feature only plans a start, so never word it as finished (*Nabito*). Never *Nabít do*: next to a percentage, *do* means "up to" (*do 80 %*), which reads as the charge limit. In a sentence, quote the name: *„Nabít včas“ je vypnuto*.
- **Time to full:** **Do plného nabití**, or *Čas do plného nabití* where there is room. Keep *plného*: on a model with a charge limit, *Čas do nabití* could read as until the limit. *Time to full (est.)* is *Do plného nabití (odhad)*, and the compact *To full (est.)* is *Do plného (odhad)*.
- **Charge schedule tile:** *Plán nabíjení*.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Rychlý přístup**. Never *Rychlé nastavení*, which is Android's pull-down panel.
- **Tap, press:** on screen **klepnout** (*klepněte*), never *kliknout* or *ťuknout*. A physical button is **stisknout** (*stiskněte*).
- **Charger, battery, range:** **nabíječka**, **baterie**, **dojezd**. Never *akumulátor* or *dosah* (Bluetooth has a *dosah*, a scooter has a *dojezd*).
- **Turn signal:** **směrovka**, never the colloquial *blinkr*, whatever retailers write.
- **Kick-start speed:** **Rychlost pro rozjezd**, the speed you must push to; *Rychlost rozjezdu* would read as how fast it pulls away. Its value 0, *Standing start*, is **Rozjezd z místa**.
- **Phone journal:** **Deník jízd z telefonu**. *Deník telefonu* reads as the phone's own diary.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays (20dp at 10.5sp, budget 40dp). TalkBack speaks **Experimentální**.

## 8. Android's own wording

Quote Android's settings as Android writes them: **Oznámení**, **Ladění přes USB**, **Číslo sestavení**, and for developer options **Pro vývojáře** (the current Settings label; *možnosti pro vývojáře* in running text). Phone makers rename *Informace o telefonu*, so write generally where you can: *v nastavení otevřete informace o telefonu*.

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

**Rule 3 in Czech.** After *check* or *could not confirm*, use *zda* or a noun, never *že* with a past tense, which reads as if it happened: *Zkontrolujte, zda se nabíjení obnovilo*, *Zkontrolujte, zda je nabíječka připojena*, *Nepodařilo se potvrdit vypnutí plánu* (*ověřit* where the English says verify). Not *Nepodařilo se potvrdit, že se plán vypnul*.

**Rule 4 in Czech.** The prepositions *v/ve*, *k/ke*, *s/se*, *z/ze* change with the next sound, so *v %1$s* is wrong at some hours (*v 8:00*, but *ve 2:00*). Case is the same trap: a scooter name or a value can't be declined after a preposition. Use a label form: *Začátek: %1$s*, *Koloběžka: %1$s*.

### Fixed words

| English concept | Meaning | This language |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Zkuste to znovu**; *Zkuste to za chvíli*. As a button, **Zkusit znovu**. Never *Vyzkoušet* or *Vyzkoušejte znovu*, which read as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **bez údajů**. Says only that no value came, not that anything is off or fine. Never *nehlášeno* or *neuvedeno* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **hlášený** (agrees with its noun); verb **hlásit**: *koloběžka hlásí*, *Hlášený stav baterie*. A claim, not a measurement. Never *potvrzený*. A document is a **zpráva** |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **protokolování aktivity NAVEE**. Never *registrace*, and never *přihlášení* |
| restore logging | turning that logging back to how it was (usually off) | **vrátit protokolování zpět**. Means putting that logging back as it was, usually off. It brings back no file or backup, so never *obnovit* |
| Volt's helper | the small helper Volt starts inside Shizuku | **pomocná služba Volt**; *pomocná služba* alone once the screen makes clear which. Never *asistent*, which is Walk Assist's word. The script on the computer is the different **nástroj pro zachycení** |
| stock charger | the charger that came with the scooter | **původní nabíječka**. Never *standardní* or *sériová* |
| high-speed charger | a faster charger the rider may own | **rychlonabíječka**; alone, *Rychlá*. Never *vysokorychlostní*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **výpadek záznamu**; *výpadek* once the screen is about the recording. Means no data was recorded, not that power or the ride was cut. Never *mezera* |
| elevation | height above sea level on a route | **nadmořská výška** |
| current direction | which way battery current flows | **směr proudu**. Never *směr* alone, which reads as a route |
| throughput | how much energy has passed through the battery | **prošlá energie**. An amount that passed through, not a rate, so never *průtok* or *propustnost* |
| battery gauge | the scooter's own battery percentage reading | **ukazatel baterie**; *ukazatel* alone where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **surová hodnota**; in parentheses *(surové)*; as a tag **RAW**, where the English writes RAW, known from cameras. Judgment call |
| last seen | when Volt last had data from the scooter | **Poslední údaj**; with a value, *Poslední údaj: %1$s*. The data may be old, so never word it as current. Not *Naposledy viděno*, a calque, and not *poslední známý stav*, which is the last known state |
| last known lock state | the state Volt last had, which may be out of date | **Poslední známý stav zámku**: *Poslední známý stav zámku: zamčeno*. In the short status row, *Poslední známý stav: zamčeno*. Never *Naposledy známý*, which isn't idiomatic |
| notification, alert, alarm | three different things; never merge them | **oznámení** is the Android notification, as Android names it: *Povolte oznámení aplikace*. **upozornění** is Volt's alert. **alarm** is the sound. Keep them distinct |
| add | adding a widget, scooter or item | **přidat**: *Přidat widget*, *Přidejte další koloběžku* |
| enter (typing) | typing a value | **zadat**: *Zadejte nový PIN* |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **aplikace**: *aplikace NAVEE*, *oficiální aplikace*. Never *appka* or *apka* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **připojte koloběžku**, *Nejprve připojte koloběžku*, *Znovu připojit koloběžku*. Never *připojte se*, which reads as going online. The button stays *Připojit*; plugging in says *připojte nabíječku* |
| set a value | one verb for setting a value on the scooter | **nastavit**: *Nastavit PIN*, *Nastavit %1$s*. The noun is *nastavení*, and setting something back stays *Vrátit zpět* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Reflexive future, in dialogs and hints alike: *Odstraní se tato jízda*, *Otevře se mapa*, *Nahradí se všechna data*. It has no gender to agree with. Never *Odstraňte…* or *Otevřete…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Záloha a obnovení*. If the title is an action with no good noun, the infinitive: *Otestovat plánované nabíjení*. Never the imperative |

## Sources consulted

**NAVEE's official Czech: not found.** No page showed the official app's Czech wording for lock, charge limit, PIN, owner key or experimental features, so those words are Claude's own proposals, and the official app may differ. No APK was downloaded.

- NAVEE publishes Czech manuals, and they should be read at the debate: search listed [NAVEE GT3 UM CZ](https://service.naveetech.com/wp-content/uploads/2026/03/NAVEE-GT3-UM-CZ.pdf) and [NAVEE E25 Pro UM CZSK](https://service.naveetech.com/wp-content/uploads/2026/03/NAVEE-E25-Pro-UM-CZSK.pdf), titled *Elektrická koloběžka*, *Uživatelská příručka*. **Both returned 404** when fetched on 2026-11-05, so only the title words count as official. [eu.naveetech.com/cs](https://eu.naveetech.com/cs/products/navee-gt3-e-scooter) also returned 404.
- Czech reviews of NAVEE scooters, weaker evidence: [Svět Androida, N65](https://www.svetandroida.cz/kolobezka-navee-n65-recenze/): *elektronického zámku*, *čtyřmístný PIN*, *tempomatu*, *rekuperace energie*, *rychlostní režimy*, *dojezd*, *počítadlo kilometrů*, *trip*.
- Other brands' Czech scooter material, read as search summaries only, weaker again: [Xiaomi 4 Pro manual](https://www.itmix.cz/wp-content/uploads/2023/03/Manual-xiaomi-mi-electric-scooter-4-pro.pdf), [Ninebot F30E manual](https://www.itmix.cz/wp-content/uploads/2023/04/Manual-Ninebot-Segway-F30E.pdf): *zámek*, *rekuperace*, *tempomat*, *režim*.
- Android's own Czech, read from AOSP: [SettingsLib cs](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-cs/strings.xml) (*Pro vývojáře*, *Ladění přes USB*, *Spárovat*, *Párování…*, *Připojování…*, *Odpojeno*, *Neznámé*, *Vypnuto*, *Zapnuto*) and [Settings cs](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-cs/strings.xml) (*Číslo sestavení*, *Nastavení*, *možnosti pro vývojáře*).
- [Mozilla's Czech localization guide](https://www.github.com/bobreyes/styleguides/blob/master/docs/cs/general.md), a copy: *vy*, infinitive buttons, sentence case, *klepnout*, „ “, *99 %* as noun and *99%* as adjective, decimal comma, 24-hour time.
- Czech university typography handouts, read as a search summary ([Masaryk University, example](https://is.muni.cz/el/phil/jaro2013/HIB0241/Strucna-pravidla-hladke-sazby.pdf?kod=IAJp15)): quotes tight against the word, percent spacing.
- **Not read:** [Microsoft's Czech style guide](https://learn.microsoft.com/cs-cz/globalization/reference/microsoft-style-guides) and the ÚJČ [Internetová jazyková příručka](https://prirucka.ujc.cas.cz) were not fetched. Rules on commas, the plural cases, *v/ve* and capitalization come from general knowledge of Czech. Every abbreviation in section 5 and the *blinkr*, *tachometr*, *spolujezdec* and *dosah* notes are judgment calls, unverified by a native speaker. *Odhad Eco*, *Úsek*, *Nájezd*, *Přehled*, *Spoluuživatel*, *Nabít včas*, *Rychlost pro rozjezd*, *Dvoumotorový pohon*, *Deník jízd z telefonu* and *Aktivní brzdění při překročení rychlosti* are the choices most worth debating, and should be checked against NAVEE's Czech manuals.
- CLDR: the plural categories come from `l10n/lang/cs.tsv` and plan 7.4; the number format is from general knowledge of CLDR, not re-checked.
- Fonts and widths: `tools/l10n/fontmetrics.py` over `res/font/saira_*.ttf` covers every Czech letter (ě š č ř ž ý á í é ů ú ď ť ň, capitals, ó), „ “ ‚ ‘ … – — · ° and U+00A0 in all four weights; none is missing, and U+202F is absent as expected. Widths are measured with java.awt on `saira_medium.ttf`.
