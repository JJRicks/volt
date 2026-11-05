# Volt style guide: Norwegian Bokmål (nb)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in the last section; the three-vendor debate decides later, and the owner hears about it from the plan. No native speaker has reviewed it, and nothing official from NAVEE in Norwegian was found.

Rule of thumb: write what a Norwegian phone would say. Android's Norwegian comes first, ordinary everyday words second, a calque of the English never.

## 1. Register and form of address

- Use **du**, always in lower case: *Koble til laderen*, *sparkesykkelen din*. Never *De*. Possessive follows the noun: *sparkesykkelen din*, not *din sparkesykkel*, except where space is short: *Mine sparkesykler*.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles, menu actions and instructions all use the imperative:** *Lagre*, *Koble til*, *Lås*, *Lås opp*, *Gjenopprett*. This is what Android's Norwegian does, so there is no separate button form. Never the infinitive with *å* on a button.
- Labels and titles use nouns: *Ladegrense*, *Sikkerhetskopi*.
- Plain, moderate Bokmål. Definite singular in **-en**, not -a: *ladegrensen*, *boken*, never *ladegrensa*. Past tense in **-et**: *lagret*, never *lagra*. **-ing** rather than -ning where both exist: *lading*, *opplåsing*, *måling*; *avlesning* is the fixed exception because it is the established word. No Nynorsk forms.
- Finished events take the simple past or a participle: *Virket her*, *Mistet signalet*, *Lagret*.
- Address the rider as *du*, not as *føreren*, except where the English speaks about a third person.

## 2. Punctuation and quotes

- Quotes: **«…»** first, **‘…’** inside. Never straight quotes.
- A sentence that names a button, tab or screen puts its exact label in «…»: *Trykk på «Tillat»*, *åpne «Sikkerhetskopi og gjenoppretting»*. A feature mentioned in passing stays unquoted.
- A comma or period after a quote goes outside: *Trykk på «Tillat», og prøv igjen.*
- Lists take no comma before *og* or *eller*. A comma comes before *men*, and between clauses with their own subject and verb. A subordinate clause that comes first takes a comma after it: *Når sparkesykkelen er koblet til, kan du låse den.*
- A colon is followed by lower case unless a name or a whole sentence follows: *Sist kjente: låst*.
- Dashes: a spaced en dash (–) for a break in a sentence, no spaces in a range (*10–20*). Keep the English's `·` separators and `…` as written, with no space before `…`: *Kobler til…*
- A no-break space (U+00A0) goes wherever a number meets `%` or a unit. **Never U+202F:** Saira lacks it.
- Compounds are written as **one word**. Splitting them (*lade grense*, *eier nøkkel*) is the commonest error in machine Norwegian, and it can change the meaning. Write a hyphen only after an acronym, a digit or a name: *PIN-kode*, *USB-kabel*, *NAVEE-appen*, *Eco-estimat*, *Bluetooth-tilkobling*. A name with `%1$s` in it never compounds: write *appen %1$s*, not *%1$s-appen*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere**, whatever the English does: button *Koble til*, label *Planlagt lading*, title *Eksperimentelle funksjoner*, section *Skjerm og lyd*. Never English Title Case: *Walk Assist* → *Trillehjelp*, *Low Battery Mode* → *Lavbatterimodus*.
- After a colon, lower case (section 2).
- Feature names are lower case mid-sentence: *slå på planlagt lading*.
- Days, months and languages are lower case: *mandag*, *mars*, *engelsk*.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android. Drive modes and their level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *Sport-modus* where the English says Sport mode.
- Capitals keep their letters: *Æ Ø Å*. Never write *Lås* as *Las* or *LAS*: *last* and *las* are other words.

## 4. Numbers, dates and units

- Decimal comma, and a no-break space (U+00A0) groups thousands, in text you write: *1 234,5*. Volt's `Fmt` formats live numbers; never rewrite a placeholder's number or add separators around one.
- Percent always takes the no-break space: `%1$s %%` gives *85 %*. The rule covers `formatted="false"` text too, whose literal is `100 %`. Norwegian writes the space, and without U+00A0 a narrow label could wrap the sign onto its own line.
- Numbers in the English stay as digits. Don't write them out or change them.
- **Unit symbols stay as the plan says and take the same no-break space:** *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. No plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Norwegian shops write *km/t* and *t* for hour; Volt doesn't, because `km/h` is a protected token (debate item, section 8). Spell out unit words: *kilometer*, *miles*, *minutter*, *kilometer i timen*.
- **`mil` is a trap.** A Norwegian *mil* is 10 km. Miles are always **miles**, one **mile**. Never *mil*, *mila* or *engelske mil* on a label.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5. mars*, with the period. Clock time is 24-hour. In a label *kl.* comes before a value only where the value is certainly a clock time: *Start kl. %1$s*. *Ferdig kl.* is never allowed for Charge done by (section 7). Never write *am/pm*.
- Plurals: `one` covers exactly 1. *1 tur*, *2 turer*; *1 minutt*, *2 minutter*; *1 sparkesykkel*, *2 sparkesykler*. Words that don't change still get both items: *1 kilometer*, *2 kilometer*. Never copy "1 minutes" into Norwegian.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Compounds run long, and a compound counts as one unbreakable block: there is no hyphenating it. Try these in order:

1. **Drop the part the screen already supplies.** On a battery screen *Batteriinnstillinger* → *Batteri*. On a scooter screen *Sparkesykkelnavn* → *Navn*.
2. **Split a noun phrase at a real space, on a rung that can wrap.** Quick access cells (the 64dp full rung at 12sp) wrap at spaces, and a word wider than the cell breaks mid-word, so every single word must fit. Setting tiles (the `_short` rung, 90dp at 12.5sp) are **one line with end ellipsis** (`QuickAccessGrid`: `setMaxLines(1)`, `TruncateAt.END`): the whole label must fit, because nothing wraps. Don't rely on a wrap there.
3. **Use a shorter real compound**, not a cut one: *Gjennomsnittsfart* → *Snittfart*, *Kilometerteller* → *Km-teller*, *Maksimal fart* → *Maksfart*.
4. **Cut the last member of a compound at a syllable, from the table only**, and mark it with a period: *Batteriinnstillinger* → *Batteriinnst.* The first member stays whole, so it still says what it is.

Abbreviations this guide accepts. Nothing else: no invented cuts such as *Eksp.*, *Rekkev.*, *Komp.*, *Sparkes.*, *Batt.*

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Maksimal fart | **Maksfart** | 80 → 52 | The setting, held until D3 (section 7). A word, no period |
| Gjennomsnittsfart | **Snittfart** | 105 → 49 | A word, no period. Never *Gj.sn. fart* unless a budget forces it |
| Kilometerteller | **Km-teller** | 86 → 54 | The Odometer ladder: *Kilometerteller* → *Km-teller*. The *km* stays even when the display shows miles: the word names the instrument. Never *Odo.* |
| Innstillinger | **Innst.** | 69 → 33 | Fits the 62dp tile. Also in compounds |
| Kjøreinnstillinger | **Kjøreinnst.** | 99 → 63 | Ride settings: 60dp at 12sp, fits 64dp |
| Batteriinnstillinger | **Batteriinnst.**, then **Batteri** | 109 → 72 → 40 | 69dp at 12sp is over the 64dp shortcut; use *Batteri* there |
| Appinnstillinger | **Appinnst.** | 92 → 55 | |
| Temperatur | **Temp.** | 67 → 35 | |
| maksimum, minimum | **maks.**, **min.** | | *min.* also reads as minutes beside a time: there, write *minst* or *minimum* |
| omtrent, cirka, estimert | **ca.** | | Replaces English *est.*: *Til full (ca.)*, chip *ca.* (16dp in the 40dp chip). Never *est.* |
| informasjon | **info** | | A short word, no period |
| klokken | **kl.** | | Only before a clock time |

Worked ladders, full → short → compact:

- Time to full (est.): *Tid til full lading (ca.)* → *Til full lading (ca.)* → *Til full (ca.)*
- Charge done by: *Ferdigladet innen* on the wrapping 64dp rung (*Ferdigladet* is 63dp at 12sp), **Ladet innen** on the one-line 90dp tile (68dp). Charge done by time: *Ferdigladet innen kl.* → **Ladet innen kl.** (84dp). *Ferdigladet innen* is 102dp at 12.5sp and would truncate to *Ferdigladet…*, which says the charge has finished. *innen* always stays. Never *Ferdig kl.*, which states a finish.
- About & permissions: *Appinfo og tillatelser* on the wrapping rung, **Appinfo** on the one-line tile, which still speaks the full label.
- Firmware versions, compact rung: *Kontroller FW*, *Nærhet FW*, *Skjerm FW*, *Batteri FW*, *Måler FW*. FW is the accepted initialism on `_compact` firmware rungs only, as in the English. *Batteri* alone collides with the battery charge rung.
- Ride settings: *Kjøreinnstillinger* → *Kjøreinnst.* → *Kjøring*
- Live Stats: *Sanntidsdata* (77dp, fits the 90dp tile; over 64dp at 12sp) → *Sanntid*
- Scooter name: *Sparkesykkelnavn* → *Navn*

Measured words that fit the 62dp tiles as they are: *Lås* 20, *Batteri* 40, *Turer* 31. At 90dp: *Hurtigtilgang* 77, *Sikkerhetskopi* 85, *Ladeplan* 53, *Skjerm og lyd* 79, *PIN og nærhet* 84, *Rekkevidde* 67. *Eksperimentell* is 86dp, so it fits 90dp alone; *Eksperimentelle* is 93dp and doesn't, so *Eksperimentelle funksjoner* becomes the bare *Eksperimentell* on that one-line tile, never *Eksperimentell funksjon* (140dp, and singular for a page of several). In the 64dp cell *Eksperimentelle* is 90dp and breaks mid-word; the word stands and the overflow is reported (rule 8).

**No accepted short form exists for *sparkesykkel*.** *Sparkesykkel* is 77dp and *Sparkesykler* 75dp, over the 64dp shortcut budget (the English *My scooters* is 68dp at 12sp and is over it as well). Don't invent *S-sykkel* or *Elspark*, the latter being Swedish and slang. Write *Mine sparkesykler*, and say so in your notes: it is a layout problem, not a wording one. Never *Sykler*, which reads as bicycles.

An abbreviation ends in a period, and the period counts toward the width. Initialisms and unit symbols take none.

**Never abbreviate:** *Lås, Lås opp, Låst, Ulåst, Ukjent, Ikke verifisert, Aldri, Alltid, Alarm, PIN, Av, På*. If one of these doesn't fit, write the whole word and report it. *Ikke* is never shortened to *Ik.* or dropped to save room.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Norwegian | Rules |
|---|---|---|
| Lock (action, and the noun on a card) | **Lås** | Imperative and noun alike. Infinitive *låse*, verbal noun *låsing* |
| Locked | **Låst** | Takes no gender. Plural *låste* |
| Unlock (action) | **Lås opp** | Infinitive *låse opp*, noun **opplåsing**. Keep *opp* right after the verb: *Lås opp sparkesykkelen*, never *Lås sparkesykkelen opp*. Never use a pronoun object with *lås opp*: repeat the noun or drop the object, as in *Lås opp sparkesykkelen* and *for å låse opp*, never *Lås den opp* or *Lås opp den*. Never *Åpne* |
| Unlocked | **Ulåst** | The state. A completed action in a sentence: *ble låst opp*. Never *åpen* or *fri*. Plural *ulåste* |
| Lock state unverified | **Låsestatus ikke verifisert** | Never reads as confirmed. Keep *ikke*. 145dp: it wraps at spaces, never shortens |
| Lock state unknown | **Låsestatus ukjent** | Never reads as confirmed |
| Unknown (alone) | **Ukjent** | Plural *ukjente* |
| Unverified | **Ikke verifisert** | Keep the two words. Not *Uverifisert*, whose *U-* is easy to miss |
| Never | **Aldri** | Never *ikke* alone where the English says never |
| Always | **Alltid** | *Always on*: **Alltid på** |
| Alarm | **Alarm**, *alarmen* | A sounding alarm. Never *Varsel*: alert is **Varsel**, and they stay distinct |
| Alarm sound | **Alarmlyd** | |
| Stop (a sounding alarm) | **Stopp** | |
| PIN | **PIN**, in a sentence **PIN-kode**, *PIN-koden* | Never *kode* alone or *passord*. A password is *passord*, and the two stay separate |
| Handlebar PIN | **PIN-kode på styret** | Tight: *PIN på styret*. Not a compound |
| PIN unlock | **Opplåsing med PIN** | |
| Owner key | **eiernøkkel**, *eiernøkkelen*, plural *eiernøkler* | A secret. Never *eierkode* or *passord*. "Not a password" is *Ikke et passord* |
| Charge limit | **Ladegrense**, *ladegrensen* | Never *ladetak* or *maksimal lading* |
| Experimental | **Eksperimentell**, neuter **eksperimentelt**, plural **eksperimentelle** | Agrees with its noun |
| Untested | **Ikke testet** | Never *utestet*: it reads as a form of *ute* |
| Unconfirmed | **Ubekreftet** | The scooter answered, but the value wasn't kept or read back |
| Not confirmed | **Ikke bekreftet** | |
| Confirmed | **Bekreftet** | Only where the scooter really read back the value |
| not affiliated with NAVEE | **ikke tilknyttet NAVEE** | As a sentence: *Volt er ikke tilknyttet NAVEE*. Never soften it: no *uoffisiell*, *uavhengig av*, *ikke direkte tilknyttet* |

Words that look right and mean something else:

- **`Låst opp` starts with `Låst`.** That is why the state is *Ulåst*: a label cut or wrapped after the first word would say the opposite. Keep *låst opp* to full sentences, where *ble* or *er* comes before it and nothing can cut it.
- **`Lås opp` versus `Last opp`.** *Last opp* means upload, and *lade* (charge) is not *laste* (load): *lading* is charging, *lasting* is loading. The *å* and the *d* have to be right.
- **`Ulåst` and `Låst` differ only by `U`.** Never shrink, never truncate. In a sentence the full word stays.
- **Exact labels, for the judge.** The glossary accepts *låst*, *låste* and *låst opp* because sentences need them, so it would also pass the wrong label. Check these by hand: `onboarding_welcome_tile_lock`, its `_short` rung and `readings_lock_status_compact` must be exactly *Lås*; `readings_value_unlocked` must be exactly *Ulåst*. A pronoun with no antecedent on a lock row (*Forblir låst når den er av*) can read as the lock being off: name the sparkesykkel. A label quoted in «…» must match a real key's translation.
- **`Av` and `På`.** On and off are **På** and **Av**, for settings and lights alike, as on Android. Verbs: *slå på*, *slå av*. Never use *Ja/Nei* in their place, and never *Aktiv* for a setting that was merely asked for.
- **`Koble til` and `Koble fra`** differ only in the particle: connect and disconnect. Never drop it.
- **Negation moves.** In a main clause *ikke* follows the verb: *Sparkesykkelen er ikke låst*. In a subordinate clause it goes before: *hvis sparkesykkelen ikke er låst*. Keep every negation; *ikke*, *ingen*, *aldri* and *uten* count as negation words for the checks.
- **`overfart`** is a ferry crossing, not overspeed: write *for høy fart*.
- Don't add reassurance the English lacks (*trygg*, *garantert*), and don't add warnings it doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Scooter:** **sparkesykkel**, *sparkesykkelen*, *sparkesykler*, *sparkesyklene*. Not *scooter*, which in Norwegian is a moped, and not *elsparkesykkel* in the UI, since it costs width and Volt has no other kind. *Sparkesykkelens* is fine, or better *på sparkesykkelen*.
- **Riding:** you **kjører** a sparkesykkel, the everyday word; never *rir* or *sykler*. **Drive mode** is **kjøremodus**.
- **Three speeds:** the setting is **Maksimal fart** (**Maksfart** on the narrow tile). A limit below it is **Fartsgrense**. The measured value on a trip or ride is **Toppfart**. Never swap them. Use *fart* over *hastighet*: shorter and everyday. *Speed cap* is **Fartsgrense**, never *fartstak*.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback. The glossary's `top-speed` and `maximum-speed` rows already carry D3's meanings, and every other key with these terms except the next one already agrees with them. `rides_tile_max_speed` says *Max speed* for a value measured on a ride: translate it **Toppfart** now, never *Maksfart*. No hold is needed, because D3 changes it to Top speed.
- **Rides vs trips:** a recorded ride is a **tur**. *Trip* (the distance since a reset) is **Tripp**, as on car displays. A path on the map is a **rute**.
- **Try vs retry vs test:** Trying a control (Experimental, Controls to try) is **Prøv**, and its results use **forsøk**. An ordinary retry is the fixed phrase below. A guided test is **test**, verb **teste**: *Test planlagt lading*. A compatibility check is **kompatibilitetssjekk**, and its screen has the same word.
- **Controls:** a UI control, as in *Experimental controls*, is **funksjon**: *Eksperimentelle funksjoner*, *Funksjoner du kan prøve*. Never *kontroller* for a UI control: it first reads as checks, and as the imperative *Check!*. *Kontroll* stays only in fixed feature names such as *Cruisekontroll*.
- **Dashboard vs display:** Volt's Home layout is the **Dashbord**. The scooter's own screen is **skjermen på sparkesykkelen**. *Dashbord* alone never means the scooter's screen, though in a car it would.
- **Units vs devices:** *enhet* means device on Android. Write **måleenhet** for units: *Måleenheter på sparkesykkelen*, or *Måleenheter på sparkesykkelens skjerm* where there is room. Never *på skjermen* alone, which reads as the phone's screen.
- **Settings vs setup:** settings are **innstillinger**, a one-time setup is **oppsett**.
- **Restore vs set back:** bringing back a backup, or the NAVEE log setting as Fixed words says, is **Gjenopprett**. Undoing a try is **Sett tilbake**, and so is putting back a test's earlier value even when the English says restore. Energy recovery is **Energigjenvinning**, unrelated to either. Avoid *tilbakestill*, which on Android means a factory reset.
- **Readback:** **tilbakelesing**, a neutral word, because a readback can also fail to confirm.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Avvist**.
- **Pair vs bind vs connect:** Bluetooth pairing is **koble sammen**, *sammenkobling*. NAVEE account binding is **knytte til kontoen**. A live link is **koble til**, *tilkobling*. Android sometimes shows pairing as *Koble til*, but Volt keeps them apart.
- **Owner key parts:** the file is the **eieroppsettfil**, shortened to **eierfil** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels are translated, unlike the drive modes, and agree with *energigjenvinning*: *Av, Lav, Middels, Høy*. Elsewhere they agree with their own noun: *lavt batteri*.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Eco-estimat** is a range option, translated.
- **Roles:** owner is **eier**, shared rider is **medbruker**. The compact role label (`more_role_shared`, English *Shared*) is also **Medbruker** (63dp at 12.5sp), never *Delt*, which also reads as "split".
- **Charge done by:** the feature name is **Ferdigladet innen**, its time setting **Ferdigladet innen kl.**, and on one-line tiles **Ladet innen**, **Ladet innen kl.** (section 5). The feature only plans a start, so *innen* always stays: *Ferdigladet* alone says the charge has finished. In a sentence, quote the name: *«Ferdigladet innen» er av*, *slå av «Ferdigladet innen»*.
- **Charging vs charger:** the status is **Lader** (`ui_battery_ring_charging`, `dashboard_charging`). A widget or reading name is **Lading** (`dashboard_model_type_charging`, `stats_row_charging_state`). The device always comes in a phrase or with a qualifier, such as *originallader*, *tilkoblet lader* or *koble til laderen*, never as a bare *Lader* label.
- **Gauge:** the battery reading is always **batterimåler**, *batterimåleren*: *Batterimåleren hoppet*. *Måler* and *Måler…* alone are only the measuring state. The widget type `dashboard_model_type_gauge` shows one reading as a dial and is not a battery word: it is **Instrument**.
- **Alerts:** an alert is **varsel**, plural *varsler*; *Varsle meg* is notify me. The scooter's beeps are **Pipelyd**, never *Varsellyd*.
- **Parts:** handlebar **styre**, brake **brems**, motor brake **motorbrems**, throttle **gass**, tyre **dekk**, charger **lader**, kickstand **støtteben**, headlight **frontlys**, tail light **baklys**, turn signal **blinklys**, range **rekkevidde**, uphill **oppoverbakke**, downhill **nedoverbakke**.
- **Touch words:** tap is **trykk** (*Trykk på*), double-tap **dobbelttrykk**, slide **skyv**. *Dobbelttrykk eller skyv for å låse opp.*
- **Recording:** *ta opp* and *opptak* mean audio and video in Norwegian, so sensor data is **logget**. Verb **logge** (*Logg tur*, status *Logger*), the act **logging** (*Stopp loggingen*), the saved result **logg** (*Loggen ble slettet*), and **turlogging**/**turlogg**, **ladelogging**/**ladelogg** where the kind matters. In a passive with no recording in view, **registrert** (*Fart ikke registrert*). Where NAVEE's logging could also be in view (owner setup, onboarding), name the kind: *Stopp turloggingen først*. The phone journal is **GPS-logg fra telefonen**; the CSV option reads *GPS-logg fra telefonen (CSV)*. Never *Telefonlogg*, which reads as a call log.
- **Widget:** **modul**, as Android's launcher says. *Tile* isn't *rute*, which means route.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays. TalkBack speaks **Eksperimentell**.

## 8. Choices for the debate

| Concept | Choice | Alternatives | Why |
|---|---|---|---|
| Unlocked | **Ulåst** | *Låst opp*, *Opplåst* | *Låst opp* starts with the opposite word. *Opplåst* is usual for phones and also reads well; this draft leans slightly to it for the debate, since three added letters mark the state better than one small *U*. If it changes, add *opplåst*, *opplåste* to the `unlocked` row and replace *Ulåst* in sections 5 and 6 |
| Owner key | **eiernøkkel** | *eierkode* | D3's reasoning: a key is a secret that opens something. May suggest a physical key; digital *nøkkel* is common in Norwegian |
| Shared rider | **medbruker** | *delt bruker*, *gjestebruker* | *Delt* also reads as split, and a guest is a different relation. *Medbruker* is an existing word, a co-user |
| Walk Assist, Power Assist | **Trillehjelp**, **Krafthjelp** | *Gåhjelp*, *Gåassistanse*, *Motorhjelp* | Judgment calls. Wheeling a sparkesykkel is *trille* |
| Traction control | **Antispinn** | *Traksjonskontroll* | Car word every driver knows, and much narrower |
| Kick-start speed | **Startfart** | *Sparkestartfart* | The official English is Startup Speed. The compound with *spark* is hard to read |
| hour and km/h | **h**, **km/h** | *t*, *km/t* | *km/t* is what Norwegian shops write and *km/h* is the protected token. Readers understand both. Owner's unit default decides |
| Experimental controls, Controls to try | **Eksperimentelle funksjoner**, **Funksjoner du kan prøve** | *Eksperimentelle kontroller*, *Kontroller å prøve* | *Kontroller* reads as checks, and the app's read-only check would then seem to include the tries. NAVEE's English says Lab Features. A native reader should confirm |
| Speed limit | **Fartsgrense** | *Fartsbegrensning*, *Fartssperre* | *Fartsgrense* first means the legal road limit but matches NAVEE's Speed Limit; a device setting is more naturally *fartsbegrensning* |
| Gauge widget (a dial) | **Instrument** | *Måleinstrument*, *Viser* | Must not be *Måler*, the measuring state. *Viser* also reads as "shows" |
| Recording | **logge**, **logg**, **logging** | *ta opp*, *opptak*, *registrere* | *opptak* suggests sound or video. *registrering* reads as signing up. *logg* sits near NAVEE's *aktivitetslogging*, so owner-setup strings name the kind. A native reader should confirm |
| Usable energy | **Nyttbar energi til kjøring** | *Brukbar energi*, *Tilgjengelig energi* | *brukbar* also means passable. *nyttbar* is the battery trade's word, and may be too technical |
| Standalone hour and day | **h**, **d** | *t*, *d.* | Natives write *3 t*. *km/h* can stay protected without forcing standalone *h*; section 4 ties them together only for the moment |
| Scooter name | **Sparkesykkelnavn** | *Navn på sparkesykkelen* | Correct but clunky. The one-word compound rule shouldn't push long coinages where natives write a phrase |
| Experimental in a 64dp cell | **Eksperimentelle funksjoner** | *Lab-funksjoner* | The first breaks mid-word in the cell; the second wraps at its hyphen but drops the established word |
| Maximum speed, Top speed | **Maksimal fart**, **Toppfart** | *Makshastighet* | Norwegian shops use *toppfart* for the maximum as well, so the distinction rests on Volt's labels; *Oppnådd toppfart* is the longer reading if needed |

## 9. Android's own wording

Quote Android's settings as Android writes them: **USB-feilsøking**, **Utvikleralternativer**, **Delversjonsnummer**. **Innstillinger** and *Om telefonen* come first in the path, written *Innstillinger › Om telefonen*, though phone makers rename it, so write generally where you can.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Prøv igjen**; *Prøv igjen snart*. It is Android's idiom and doesn't read as testing. Never *Test igjen* or *Gjør et nytt forsøk*: *forsøk* belongs to Experimental |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **ingen data**. Says only that no value came, not that anything is off or fine. Never *ikke rapportert* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **oppgitt**; verb **oppgi**: *sparkesykkelen oppgir*, *Oppgitt av sparkesykkelen*, *Oppgitt batterihelse*. On a countable noun use **ifølge**: *Ladinger ifølge sparkesykkelen*, not *Oppgitte ladinger*. A claim, not a measurement. Never *rapportert* or *bekreftet*. *rapport* stays the word for a document |
| advertised (capacity) | the maker's figure, not the scooter's | **ifølge produsenten**: *Kapasitet ifølge produsenten*. Never *annonsert* |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **aktivitetslogging for NAVEE**; *aktivitetsloggen* for the log. Never *registrering*, which reads as signing up |
| restore logging | turning that logging back to how it was (usually off) | **gjenopprett logginnstillingen**, *for NAVEE* where needed: *Gjenopprett logginnstillingen for NAVEE*, *Gjenopprett logginnstillingen igjen*, *Shizuku ble borte. Gjenopprett logginnstillingen.* It names the setting, so it reads as that setting going back to its old value, usually off. It brings back no file or backup. Never *gjenopprett aktivitetsloggingen*, which reads as turning logging back on |
| Volt's helper | the small helper Volt starts inside Shizuku | **Volt-hjelperen**; *hjelperen* once the screen makes clear which. Never *assistent*. The script on the computer is the different **henteskript** |
| stock charger | the charger that came with the scooter | **originallader**. Never *standardlader* |
| high-speed charger | a faster charger the rider may own | **hurtiglader**; alone, *Hurtig*. This is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **datahull**, the same in the plural; *Datahull i loggen*. Means no data was recorded, not that power or the ride stopped. Never *opphold*, which means a stop or a stay |
| elevation | height above sea level on a route | **høyde**; *høyde over havet* where the screen doesn't make it clear |
| current direction | which way battery current flows | **strømretning**. Never *retning* alone, which reads as a route |
| throughput | how much energy has passed through the battery | **energigjennomstrømning**; *gjennomstrømning* once the screen has said energy. An amount of energy that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **batterimåler**, *batterimåleren*, always the whole word, because *Måler* alone is the measuring state. The scooter's own figure, not a measurement by Volt |
| raw | an unprocessed value, also used as a short tag | **rå**; *rådata* for data; as a tag **RÅ**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Sist avlest**; with a value, *Sist avlest: %1$s*. The data may be old, so never word it as current. Not *Sist kjente*, which is the last known state, and not *Sist sett*, which is said of people |
| add | adding a widget, scooter or item | **legg til**: *Legg til modul*, *Legg til enda en sparkesykkel*. As on Android |
| enter (typing) | typing a value | **skriv inn**: *Skriv inn en ny PIN-kode*. Never *tast inn* |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, *appen*, plural *apper*: *NAVEE-appen*, *den offisielle appen*. *applikasjon* only when quoting Android's own label |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **koble til sparkesykkelen**, *Koble til sparkesykkelen først*. Never *koble deg til*, which reads as going online. The button stays *Koble til*; plugging in is *koble til laderen* |
| set a value | one verb for setting a value on the scooter | **angi**: *Angi PIN-kode*, *Angi %1$s*. With a value use a label form: *Angi maksimal fart: %1$s*, never *angi … til*. Never *sett*, which collides with *Sett tilbake*. The noun stays *innstilling* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | **blir** with a participle for anything that changes or destroys: *Denne turen blir slettet*. Never *Sletter…* alone or *Slett…*, which read as an act under way or a command |
| "Opens…", "Shows…" in hints | says where a tap leads; nothing changes | Present tense, as Android's Norwegian: **Åpner** *turdetaljene*, **Viser** *detaljene*. It can't read as a command, because the imperative is *Åpne*. Not *blir åpnet*, which reports an event |
| confirmation dialog titles | a question before an action | **Vil du …?**: *Vil du slette denne turen?*, *Vil du slå av antispinn?*. Never a bare imperative or infinitive with a question mark (*Slå av antispinn?*, *Erstatte …?*), which reads as an instruction |
| clear (data) | removing stored results | **Slett**, *slette*. Not *Fjern* or *tøm* for stored data |
| stays (data, a location) | kept data, or something still found somewhere | **beholdes**: *Turene beholdes*. Where the place matters **blir på**: *Turene blir på telefonen*. A control still in a menu: **finner du fortsatt i**. Never *blir værende* |
| read back, as rider text | the scooter's value read again | Technical noun **tilbakelesing**. Verb **lese av**: *leser av verdien*. Never *lese tilbake* in rider text |
| review (a try, a test, a backup) | look through before deciding | **Se gjennom**. Never *Gå gjennom* for the same action |
| resolve a scooter warning | deal with its cause | **håndter**: *Håndter advarselen fra sparkesykkelen først*. Never *Rett opp* or *Løs* |
| broad debugging access | what Shizuku grants | **omfattende feilsøkingstilgang**. Never *bred* |
| accepted (a command) | the scooter took it but didn't read it back; not confirmed | **Godtatt**; *sparkesykkelen godtok*. Never *Bekreftet* |
| charging schedule | what scheduled charging follows | **ladeplan**, *ladeplanen*. Keep *Planlagt lading* for the feature name |
| shared-user identity | a shared rider's counterpart of the owner key | **medbrukernøkkel**. Never *identitet* |
| chart, graph | a plotted reading | **diagram**, plural *diagrammer*, in every compound: *Sanntidsdiagram*, *ladediagram*. Never *graf* |
| sign (plus or minus) | whether a value is positive or negative | **fortegn**: *Batterifortegn*. Never *tegn*, which reads as a character or a symptom |
| first, second (of two rides) | which of two compared rides | **tur 1**, **tur 2**. *Andre* alone also means others, so never a bare ordinal on a short label |
| sample | one recorded measurement | **måling**, *målinger*. A value shown for a reading stays *avlesning* |
| screen and section titles | noun form or verb form, with one example | Noun form: *Sikkerhetskopi og gjenoppretting*. If the title is an action with no good noun, the imperative: *Test planlagt lading* |

## 10. Sources consulted

**NAVEE's official Norwegian: not found.** No public NAVEE page, manual or app string in Norwegian could be read. The official app's Norwegian wording, if it has any, is unknown, and every safety word above is Claude's own proposal; the official app may differ. No APK was downloaded.

- [NAVEE store](https://naveetech.us/nb/products/navee-gt3): no Norwegian path (404). NAVEE's manuals on service.naveetech.com appeared in search only in English.
- Elkjøp.no NAVEE listings ([brand page](https://www.elkjop.no/brand/navee), [GT5 Max](https://www.elkjop.no/product/sport-hobby-og-fritid/elektriske-kjoretoy/elektrisk-sparkesykkel/navee-gt5-max-elsparkesykkel-ne1730-sort/1041508), [EasyRide Pro document](https://www.elkjop.no/document/dv_doc_D18000130470/888578/navee-easyride-pro-sparkesykkel-ne1668-sort.PDF)): retailer text, seen only as titles and search summaries, since direct fetches returned 429. Titles use *sparkesykkel* and *elsparkesykkel*. Summaries say *km/t* for speed; other retailer terms weren't confirmed word for word.
- [Micro Merlin Norwegian manual](https://www.micro-mobility.com/resources/public/lava3/media/micro_merlin_manual_norwegian.pdf), read as extracted text; another maker's translated manual, weak as style: *Styre*, *Håndbremse*, *motorbrems*, *Cruisekontroll*, *Lading av batteriet*. It says *scooteren*, which this guide rejects.
- Android's own Norwegian, read from AOSP: [SettingsLib nb](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-nb/strings.xml) (*USB-feilsøking*, *Utvikleralternativer*, *Koble til*, *Sammenkobling*, *Av*, *På*, *Ukjent*) and [Settings nb](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-nb/strings.xml) (*Innstillinger*, *Delversjonsnummer*, *Legg til*). *Om telefonen*, *modul* for widget and *trykk* for tap are from memory.
- Norwegian writing rules, through Språkrådet-following agency guides and search summaries; Språkrådet's own page returned 403: [Skatteetaten on percent](https://www.skatteetaten.no/stilogtone/skrive/skriveregler/prosent/) (a space before %, as summarized by search), [Miljødirektoratet](https://www.miljodirektoratet.no/om-oss/sprakprofil/sprakprofil-bokmal/skriveregler/) (*«»*, dates), [regjeringen.no](https://www.regjeringen.no/no/om-nettstedet/innholdsstrategi/ti-bud-for-godt-innhold-pa-regjeringen.no/skriveregler-for-regjeringen.no/id2517901/) (*«»*), [SNL, forkortelse](https://snl.no/forkortelse) and other search hits (period after abbreviations, none after initialisms or unit symbols), [Riksmålsforbundet on quote punctuation](https://riksmalsforbundet.no/qa_faqs/tegnsetting-rundt-sitater) (comma after the closing quote since 2008, contested). Inner ‘…’ quotes and comma rules are from memory.
- **Microsoft's Norwegian style guide was not found** in search. Its conventions on imperatives are from memory.
- Judgment calls with no source: every term in section 8, *Tripp*, *Km-teller*, *Innst.*, *Pipelyd*, *medbruker*, *ladeopptak*, *turopptak*, and the bans on *utestet* and *uverifisert*. Which short forms a native reader accepts should go to a native speaker first.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`. All four Saira weights have æ ø å Æ Ø Å « » ‘ ’ … – · ° and U+00A0 (`fontmetrics.Font` cmap check), so `font` stays `saira`.
