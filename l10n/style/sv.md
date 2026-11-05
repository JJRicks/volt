# Volt style guide: Swedish (sv)

**Status: Claude-only proposal (2026-11-05), not final.** One model drafted this from the sources in section 8. The three-vendor debate has not seen it and no native speaker has reviewed it. "Approved" below means "the wording this proposal asks every drafter to use"; safety wording included, all of it can change.

## 1. Register and form of address

- Address the rider as **du** (owner's decision, 2026-11-02): *du, dig, din, ditt, dina*, always lower case. Never *ni*, never *man* as a stand-in for the rider. Possessives agree: *din elsparkcykel*, *ditt lösenord*, *dina turer*.
- Prefer the imperative to writing *du* at all (Microsoft's Swedish guide says the same): *Anslut laddaren*, not *Du ansluter laddaren*.
- **Buttons, toggles and menu actions use the imperative**, as Swedish software does: *Spara*, *Anslut*, *Lås*, *Lås upp*, *Prova*, *Återställ*. **Instructions in sentences use the imperative too**: *Anslut igen för att verifiera.* Labels and titles are nouns: *Laddningsgräns*, *Körläge*.
- Failure messages: **Det gick inte att …** for "Could not …" and "Failed to …": *Det gick inte att läsa värdet.* Not *Misslyckades med att*.
- Status in progress uses the present tense and keeps `…`: *Ansluter…*, *Låser…*, *Låser upp…*, *Mäter…*.
- "If" and "to" clauses: open with **om** and the action, or use *när du vill*. Do not copy English "To …, do …" as *För att …, gör …*. (Microsoft marks that form as wrong.)
- Calm, plain, friendly. No exclamation marks in errors, no slang, no marketing tone, no reassurance the English lacks (*säkert*, *garanterat*).
- Swedish role nouns in -are are gender neutral (*ägare*, *förare*). Nothing else to avoid.
- Write Swedish, not calqued English. A noun string in English often needs a preposition in Swedish: *Varning för låg batterinivå*, *Enheter på elsparkcykelns display*.

## 2. Punctuation and quotes

- Quotes: **”…”** (the same mark on both sides), inside them **’…’**. Never “…”, „…“ or «…», never straight quotes. Keep quotes where the English quotes a UI name.
- Period and comma go outside a closing quote unless the quote is a whole sentence.
- No serial comma before *och* or *eller*. Comma before a subordinate clause only where it helps reading.
- Dash: spaced en dash **–** in running text; unspaced for ranges *10–20 km*. Never an em dash. Keep `·` separators and `…` exactly as the English has them, with no space before `…` in UI text (Microsoft's rule for UI).
- A colon is followed by lower case unless what follows is a complete sentence or a name: *Senast kända: låst*.
- Colon also joins an ending to digits and all-capital abbreviations: *22:a*, *USB:n*. **Genitive takes no apostrophe:** *elsparkcykelns*, *Volts*. NAVEE's genitive is *NAVEE:s*; prefer *NAVEE-appen* where it works.
- **`&`:** Swedish writes **och**. `&` is allowed only on a width-limited rung where *och* doesn't fit, and never in a sentence: *Display & ljud*.
- **Compounds are one word.** Writing them apart (*särskrivning*) is a serious error and can change the meaning (*fel sökning* vs *felsökning*). A compound with a name, an acronym or a digit first takes a hyphen: *NAVEE-appen*, *PIN-kod*, *USB-felsökning*, *GPX-fil*, *Sport-läge*. Over four parts, rephrase with a preposition: *Starttid för laddning*, not *Laddningsstarttid*. The check matches whole words, so a compound passes only if the glossary lists it. Where a correct compound isn't listed, the preposition form is the fallback: *Titel på widgeten*, *Färg på rutten*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere**, only the first word and names take a capital. Don't follow the English capitals (Microsoft: "Don't follow the capitalization in the source text").
  - Button: *Lås upp*. Label: *Laddningsgräns*. Title: *Experimentella reglage*. *Walk Assist* → *Gångassistans*.
- Feature names are lower case mid-sentence: *slå på schemalagd laddning*. One exception, *Klar senast*: see section 7. Where a sentence must name a button, keep the quotes the English has: *Tryck på ”Tillåt”.*
- After a colon: see section 2. Days and months are lower case. Languages are lower case: *svenska*.
- Swedish doesn't use all capitals, with three exceptions kept as the scooter or Volt shows them: **WALK, ECO, DRIVE, SPORT**, the badge **LABB**, and acronyms **PIN, USB, GPS, GPX, CSV, NAVEE, TCS**. Where Volt upper-cases at run time, Å Ä Ö keep their dots and rings.
- A compound starting with a name keeps its capital: *NAVEE-appen*, *Volt-widget*, *Android-inställningar*.

## 4. Numbers, dates and units

- **Decimal comma**, in text you write: *2,5*. Thousands group with a **no-break space (U+00A0)** from five digits (*12 345*); four digits can stay closed in labels (*1234*). Volt's `Fmt` formats live numbers for the phone's region, so its output can differ; never rewrite a placeholder's number. Never U+202F: Saira has no glyph for it.
- **Percent always has a no-break space (U+00A0):** `%1$s %%` gives *85 %*; `formatted="false"` text writes `100 %`. A normal space would let the sign wrap onto its own line. *25-procentig* has a hyphen and no sign.
- **Number and unit** take a no-break space where the English has a space: *25 km/h*, *12 V*. Where the English has no space, follow the source template unless it would read wrong; don't add repeated spaces.
- Unit symbols stay as written: **km/h, mph, km, mi, V, A, Wh, kW, °C, h, min, s, %**. Unit **words** are translated: *kilometer, minuter, timmar, volt*.
- **Trap: *mil*.** A Swedish *mil* is **10 km**. The English unit is always **engelska mil** (the word) or **mi** (the symbol); bare *mil* is forbidden. The English "Miles" option is *Engelska mil*.
- Time: 24-hour with colon, **14:30**. Clock label: *kl. 14:30*. Date: *12 okt.*, *12 oktober 2026* or ISO *2026-10-12*; day comes first; no ordinals. Weekday abbreviations *mån, tis, ons, tors, fre, lör, sön* take no capital mid-sentence.
- Plurals are `one` and `other`. `one` is only exactly 1. *1 tur / 2 turer*, *1 minut / 2 minuter*, *1 elsparkcykel / 2 elsparkcyklar*. A symbol doesn't inflect: *2 km*, *2 h*.
- Digits in the English stay digits, including small numbers Swedish prose might spell out.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never a break in the middle of a word and never smaller text. Measure with Saira, don't count letters. Budgets are single-line: a 64dp Quick access name may wrap at a space into two lines, but **each word must fit the line alone**, so Swedish's long compounds fail where English passes. Try, in this order:

1. **Drop what the screen already supplies.** *Batteriinställningar* → *Batteri* (it is listed among settings). *Körinställningar* → *Körning*. *Beräknad räckvidd* → *Räckvidd*.
2. **Use the shorter compound first part.** *Laddnings-* → **Ladd-** where Swedish already has it (*laddtid, laddschema, laddgräns*): *Laddningsgräns* → *Laddgräns*, *Laddningsschema* → *Laddschema*.
3. **Split a compound into words with a preposition if that puts a space in**, so it wraps: *Namn på elspark* (*Namn på* / *elspark*) rather than *Elsparkcykelns namn*, whose first word is 82.5dp at 12sp. *Elsparkcykel* alone is 68.8dp at 12sp, so it can't take a 64dp line: there the short form **elspark** is used (see 7).
4. **Use an abbreviation from this table.** It ends in a period (Swedish writes the period, and it counts toward the width), is cut after a consonant at a natural syllable or compound joint, and never cuts inside a safety word. **Nothing else:** no invented cuts.

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Maxhastighet | **Maxhast.** | 80 → 53 | The setting. Never *Max. hastighet*, which contradicts the *max* row below, and never *Maxfart* |
| Toppfart | none | 49 | Measured value. Already short |
| Inställningar | **Inställn.** | 73 → 46 | Fits the 62 and 64dp budgets; the full word doesn't |
| Batteriinställningar | **Batteriinställn.**, then **Batteri** | 112 → 85.5 → 40 | Same pattern for *Körinställn.* (66) |
| Appinställningar | **Appinställn.**, then **Appinst.** | 96 → 69, and 46 at 12sp | **-inst.** only as a compound tail, because alone it reads as *institution* |
| Experimentella reglage | **Experimentella**, then **Experiment** | 134 → 87.5 → 66.9 | A whole word, so no period. Never *Exp.*: it isn't an accepted cut |
| Energiåtervinning | **Energiåterv.** | 104 → 70.5 | Never *Återvinning*: to any Swedish reader that is waste recycling. *Regen* (37) is a further rung only if a budget ever needs it, and none does today; it would need a glossary form first |
| Sparkstartshastighet | **Sparkstart** | 122 → 61 | A short word, no period |
| Vägmätare | **Totalt**, or **Vägm.** | 63 → 33 or 36 | English *Odo.*. Prefer *Totalt*. *Vägm.* isn't an established cut, so use it only where the label must name the odometer; the debate should compare it with *Mätarst.* (48.6) |
| Temperatur | **Temp.** | 35 | |
| Hastighet | **Hast.** | 30 | Also *Medelhast.* |
| ungefär, cirka | **ca** | | No period |
| maximum, minimum | **max**, **min** | | Without a period beside a number or noun: *max 25 km/h* |
| klockan | **kl.** | | *kl. 14:30* |
| timme, timmar | **tim** or the symbol **h** | | |
| information | **info** | | No period |
| till exempel, och så vidare, med mera, på grund av, bland annat, respektive, fortsättning | **t.ex., o.s.v., m.m., p.g.a., bl.a., resp., forts.** | | Microsoft's accepted list. Never *etc.* |

Measured words that fit as they are: *Lås* 20, *Batteri* 40, *Turer* 31 (under the 62dp tab budget); *Linjeljus* 48, *Stjärnljus* 55, *Ljus* 24 (under 64dp at 12sp too); *Mina elsparkar* 86.4 (under the 90dp tile, or wrapped in two lines at 64dp; *Mina elsparkcyklar* is 108.8 and fits neither); *Maxhastighet* 80 and *Laddschema* 73 (under the 90dp tile); *Display & ljud* 80 and *Data & historik* 86 (under 90dp, where *och* doesn't fit: *Display och ljud* is 91.1).

Fit problems to record in notes rather than force: *Experimentella*, *Behörigheter*, *Inställningar* each exceed 64dp at 12sp alone, and *Experiment* is 64.2dp there, 0.2 over. *Namn på elspark* is 97.5 at 12.5sp, over the 90dp tile on one line. *About & permissions* becomes *Om appen*. The full rung also serves as the screen-reader name, so an abbreviated full rung is read as written: flag it.

**Never abbreviate:** *Lås, Lås upp, Låst, Upplåst, Okänd, Aldrig, Alltid, Larm, Inte verifierad* (the only shorter form is *Ej verifierad*), *Otestad, PIN*. *Upplåst* never shortens to *Upp* or *Öppen*, and *Lås upp* never loses *upp*. If one doesn't fit, report it. Falling back to English is the safe outcome.

## 6. Safety wording

Use exactly these words. They match `l10n/glossary/sv.tsv`; no synonyms.

| English | Swedish | Rules |
|---|---|---|
| Lock (action, button, card title) | **Lås** | Verb *låsa, låser*, in progress *Låser…*; noun *låset, låsning* |
| Locked | **Låst** | Same form with *en*- and *ett*-words; plural *låsta* |
| Unlock (action) | **Lås upp** | *låsa upp, låser upp*, *Låser upp…*; noun *upplåsning*. Two words, and *upp* is never dropped |
| Unlocked | **Upplåst** | Plural *upplåsta*. Android uses it. Never *olåst*, *öppen*, *öppnad*, *fri*. *Olåst* is correct Swedish, but it is one letter from *Låst* at a glance |
| Lock state unverified | **Låsstatus inte verifierad** | Short rung: *Låsstatus ej verifierad*. Must never read as confirmed. Keep *inte* or *ej*. Not *Overifierad*: *inte verifierad* is the glossary's word, and a separate *inte* can't be misread at a glance |
| Lock state unknown | **Låsstatus okänd** or **Okänd låsstatus** | Must never read as confirmed |
| Unknown (alone) | **Okänd** | *Okänt* with *ett*-words, *Okända* plural |
| Unverified | **Inte verifierad** | Short: *Ej verifierad*. *inte verifierat*, *inte verifierade* |
| Never | **Aldrig** | Never a bare *inte* where the English says never |
| Always | **Alltid** | *Always on*: **Alltid på**, for a light also *Alltid tänd* |
| Alarm | **Larm** | Noun. Never *varning* or *avisering*: they stay distinct. *Alarm* (Swedish clock word) is not used |
| Alarm sound | **Larmljud** | |
| Alert (a level the rider chose) | **Avisering**, Android's word | Charge alert: *Laddningsavisering*, Battery alerts: *Batteriaviseringar*. Not *Laddningsvarning*, which reads as something wrong with the charging. Notifications are *aviseringar* too: the alert reaches the rider as one |
| Low battery alert | **Varning för låg batterinivå** | A real warning about the battery, so it keeps the word |
| Warning (a fault the scooter reports) | **Varning** | Only for the scooter's own warnings: *Åtgärda elsparkcykelns varning*. *Overspeed alert*, a scooter setting, is *Överhastighetsvarning* |
| Stop (a sounding alarm) | **Stoppa** | Never *Avbryt* on an alarm. *Stop watching*: *Sluta bevaka*. Other stops: see 7 |
| PIN | **PIN-kod**, *PIN-koden*, or **PIN** alone on a rung | Not *kod* alone, not *lösenord*. A password is **lösenord**; they stay separate |
| Handlebar PIN | **PIN-kod på styret** | Short: *PIN på styret* (76). Not *Styr-PIN*, which reads as a control PIN, not *fallback*, not *reserv-PIN* |
| PIN unlock | **PIN-upplåsning** | |
| Owner key | **ägarnyckel**, *ägarnyckeln, ägarnycklar* | A secret. "Not a password": *Inte ett lösenord*. Bare *nyckel* never, it reads as a physical key |
| Charge limit | **Laddningsgräns** | Short: *Laddgräns*. Never *laddningstak*, *maxladdning*. Keep distinct from *Hastighetsgräns* |
| Experimental | **Experimentell, experimentellt, experimentella** | *Experimentella reglage* is the section. The adjective agrees with its noun: *experimentellt reglage*, *experimentell funktion* |
| LAB badge | **LABB** | TalkBack speaks *Experimentell* |
| Untested | **Otestad** | *otestat, otestade*. Never *ej verifierad*: a different status |
| Unconfirmed, Not confirmed | **Obekräftad**, **Inte bekräftad** | Short: *Ej bekräftad*. In a sentence *bekräftades inte*, *har inte bekräftats*, *kunde inte bekräftas*. Never shorten to *Bekräftad* |
| Confirmed | **Bekräftad** | The passive *bekräftades* is the form for a sentence, and for a `%1$s` whose gender is unknown: *%1$s bekräftades.* |
| not affiliated with NAVEE | **inte knuten till NAVEE** | In a sentence: *Volt är inte knuten till NAVEE.* Or *Volt har ingen koppling till NAVEE.* Never soften: no *inofficiell*, *oberoende*, *inte direkt knuten* |

Words that look right and mean something else, or that blur a state:

- **Lås vs Lås upp.** The only difference is a separate word. Keep *upp* on the same line where you can; never shrink it away.
- **Låst vs Upplåst** are clear. **Olåst** is the everyday word for unlocked, and it is banned here only because it is one letter from *Låst*. *Obekräftad* and *Otestad* are accepted: each is a separate status with no glossary alternative of that meaning, and the glossary rows hold drafters to them.
- **Removal is not unlock.** *Ta bort låset*, *Inaktivera låset*, *Stäng av låset* say the lock is gone, not that the scooter is unlocked. Only use them where the English does.
- **Av** is *off* and also the preposition *of*. **På** is *on* and also *on/at*. So *Lås: av* is a state; use it only where the English has it. On and off: settings **På / Av**, lights **Tänd / Släckt** where natural, never *Ja / Nej* in their place.
- **Laddar** (charging, in progress), **Laddad** (charged), **Laddning** (the noun) differ by a letter or two; pick the state the English states. *Fulladdad* only when the English says full.
- **Senast** means both "last" and "by the latest". *Senast laddad* is "last charged", so the feature Charge done by is **Klar senast**, never *Laddat senast*. See 7.
- **Kontroller** means **checks**, not controls: *kompatibilitetskontroll* is right and *Experimentella kontroller* is wrong. Controls are **reglage**.
- **Aktuell** means current, not actual; **faktisk** is actual.
- Keep every negation: *inte, ej, ingen, inget, inga, aldrig, utan*. Prefix *o-* counts for the listed words (*okänd, obekräftad*). Don't add negation the English lacks.
- Don't add reassurance or warnings the English doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **The scooter is elsparkcykel**, one word, everywhere: *elsparkcykeln, elsparkcykelns, elsparkcyklar, elsparkcyklarna*. It is what Swedish riders, the press and Transportstyrelsen say. **Elspark** (*elsparken, elsparkar*), the established short form, is for width-limited labels only: *Mina elsparkar*, *Namn på elspark*. **Never *skoter***, which in Sweden means a snowmobile (*snöskoter*) or a moped. **Not *elscooter***: Inet and Elgiganten use it, and it is 3 letters shorter, but it also means a seated e-moped or a seniors' mobility scooter. This is the first question for the debate; if it reverses this, record that width was the reason. Don't mix them.
- **Three speeds:** the setting is **Maxhastighet**. A limit below it is **Hastighetsgräns** (*Egen hastighetsgräns*, *Hastighetsgräns för körläge*). The value measured on a trip or ride is **Toppfart**. Never swap them, and never *Maxfart* or *Topphastighet*, which blur the two.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. The `top-speed` and `maximum-speed` glossary rows already carry D3's meaning, so following them on today's English would swap the setting and the measured value. Flag those keys in your notes; a judge answers fallback. `rides_tile_max_speed` ("Max speed", measured) is not held: it is **Toppfart**.
- **Rides vs trip:** a recorded ride is a **tur** (*Turer* on the tab). Avoid bare singular *Tur* on a label, since it also means luck and turn: write *Den här turen*. The debate should compare *Åktur*. Trip, the distance since a reset, is **tripp**, as on car displays: *Trippsträcka*, *Tripptid*. A path on the map is a **rutt**.
- **Try vs test:** Try is **Prova**, one attempt, on a value that can be set back. A guided test is **Testa** or *test*. *Test scheduled charging* is *Testa schemalagd laddning*. A compatibility check is **kompatibilitetskontroll**. *Controls to try* is *Reglage att prova*. An ordinary **Try again** is **Försök igen**, so it can't read as an experimental try. The one exception is `more_lab_try_again_value`, which is that kind of try and keeps *Prova igen*.
- **Other stops:** telling the rider to stop the scooter is **Stanna elsparkcykeln**, ending a setup is **Avbryt konfigurationen**, and everything else, recordings and checks included, is **Stoppa**.
- **Restore vs set back vs reset:** bringing back a backup, a saved layout or NAVEE logging is **Återställ**. Undoing a try is **Ställ tillbaka**, and so is every restoration in the compatibility strings: *Ställ tillbaka till %1$s*, *Tillbakaställd och bekräftad*. A counter reset is **nollställ**: *Vägmätare nollställd*. Energy recovery (**Energiåtervinning**) is unrelated to all three.
- **Readback:** **återläsning**, a neutral word that can also fail. Never a word holding *bekräftelse*.
- **Rejected, refused:** **Avvisad** for a value, **Nekades av elsparkcykeln** for the try.
- **Pair vs bind vs connect:** Bluetooth pairing is **parkoppla**, Android's word. NAVEE account binding is **koppla kontot**. A live link is **ansluta**; ending it is **koppla från**.
- **Settings vs setup:** settings are **inställningar**; a one-time setup is **konfiguration**. Owner setup is **ägarkonfiguration**, its file **ägarkonfigurationsfil**, on onboarding steps and buttons **ägarfil**. `navee-owner-setup.json` stays as written. Website button names stay in English until the website changes, so quote them in English.
- **Dashboard vs display:** Volt's Home layout is **Översikt**. The scooter's own screen is **elsparkcykelns display**. They never swap. *Instrumentpanel* is out: it reads as the scooter's. The overview widgets on that screen never use *översikt*, or the Översikt screen would hold an Översikt widget: *Scooter overview* is **Elsparkcykelinfo**, *Battery overview* **Batteriinfo**, *Trip overview* **Trippinfo**. Not *Batteristatus*, which is the reading *Battery status*.
- **Roles:** owner is **ägare**, a role and never a credential, so *Connect as the owner* doesn't become *ägarnyckel*. Shared rider is **inbjuden förare** (*inbjudna förare, den inbjudna föraren*); *delad förare* reads as a split driver. The compact role label (`more_role_shared`, English *Shared*) is **Delad**, and *Shared access* is **Delad åtkomst**. The rider is *förare*, the road-traffic word.
- **Charge done by:** the feature is **Laddning klar senast**, short **Klar senast**; its time setting is **Klar senast kl.**, short *Klar kl.*. The feature only plans a start, so never word it as a charge that has finished. Bare **Klar senast** is for labels and tiles only. In a sentence write ”Klar senast” in quotes or *funktionen Klar senast*, and never lower-case *klar senast*: *Funktionen Klar senast är av*, not *Klar senast är av*. Scheduled charging is **Schemalagd laddning**; charge start is **Laddningsstart**. Time to full is **Tid till fulladdat**, its short form **Fulladdad om**; *To full (est.)* is *Fulladdad om (ca)*.
- **Assists:** Walk Assist is **Gångassistans**, Power Assist **Kraftassistans**, hill descent control **Nedförsassistans**. Translate from the label, not from the key.
- **Motor modes:** Traction control is **Antispinn**, the Swedish car word. Overspeed active control is **Aktivt överhastighetsskydd**, since it brakes the motor and *kontroll* would mean a check; *Overspeed control* is **Överhastighetsskydd**. Eco and Turbo acceleration profiles, WALK, ECO, DRIVE, SPORT stay in English, and *Boost* stays too. A mode word joins with a hyphen so the name stays intact: *Sport-läge*, *ECO-läge*, *Boost-läge*. **Eco-beräkning** is a range option, translated.
- **Lights:** *Tail light* **Bakljus**, *Headlight* **framljus**, *Daytime running light* **Varselljus**, *Automatic lights* **Automatisk belysning** (short *Autoljus*), *Turn signal* **Blinkers**, sound *Blinkersljud*.
- **Android's own words:** *Inställningar*, *USB-felsökning*, *Utvecklaralternativ*, *Versionsnummer*, *Om telefonen*. Makers rename these, so write generally where you can.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.

## 8. Sources consulted

**NAVEE's official Swedish wording: not found.** A NAVEE Swedish manual appears by URL in search results ([V40i Pro / V50i Pro SE](https://naveetech.com/wp-content/uploads/2024/10/V40i-Pro-V50i-Pro-SE.pdf)), but the fetch returned 404, as did the same name on service.naveetech.com. The official app's Swedish strings were not available, and no APK was downloaded. Every term here is Claude's proposal, and the official wording may differ.

- Swedish retailers and press on NAVEE, weaker evidence: [Inet, "Navee är här"](https://www.inet.se/nyhet/11035/navee-ar-har) (*elscooter, elscootrar, display*); [M3, N65i II test](https://www.m3.se/article/3143225/navee-n65i-ii-elspark-test.html) (*elsparkcykel, Navee-appen, energiregenereringen, farthållare, automatisk belysning, låsa elektroniskt, maxhastighet, räckvidd, displayen*); [M3, S65 test](https://www.m3.se/article/2005675/navee-s65-elspark-test.html) (not read in full). Elgiganten's product pages appeared in search (*elscooter*, *e-scooter*, *maxhastighet* in titles) but refused the fetch (429); titles and snippets only.
- Other scooter makers, search snippets only: [Xiaomi Electric Scooter 5 Max, Swedish page](https://www.mi.com/se/product/xiaomi-electric-scooter-5-max/) (*energiåtervinning*, *fasta programvaran*; the page itself refused the fetch).
- Language norms: [Microsoft Swedish Localization Style Guide](https://download.microsoft.com/download/5/0/9/5095f52b-dd67-4951-9afa-c15bb1696a4a/swe-swe-StyleGuide.pdf), read directly (4.1.1 abbreviations, 4.1.5 capitalization, 4.1.6 compounds, 4.1.16 punctuation including ”…” and the UI ellipsis, 4.1.18 no-break spaces, 5.5.2 instructions). It names SAOL and *Svenska skrivregler* as its norms; those were **not read directly**, and the rules on ’…’, *ca*, percent spacing, en dash, *NAVEE:s* and dates come from memory of them. Microsoft prefers avoiding UI abbreviations; Volt's ladder needs them, so section 5 goes further than it does.
- Android's own Swedish, read from AOSP: [SettingsLib sv](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-sv/strings.xml) (*USB-felsökning, Utvecklaralternativ, Parkoppla*); [Settings sv](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-sv/strings.xml) (*Inställningar*). *Versionsnummer* and *Om telefonen* come from [third-party guides](https://www.tcl.com/se/sv/support-mobile/faq/19731) and memory; their AOSP values weren't reached, and the build-number name varies by maker.
- *Elsparkcykel* vs *elscooter*: Transportstyrelsen's page path uses *elsparkcykel* (seen in the Inet link), M3 tests under *elsparkcykel* and *elspark*, a [Riksdag motion](https://www.riksdagen.se/sv/dokument-lagar/dokument/motion/_H7021778) argues for *elscooter*, and Wiktionary treats them as synonyms. No Språkrådet ruling was found. That *elscooter* also means a seated e-moped or a seniors' mobility scooter comes from the independent review and general knowledge, not from a source read here.
- **Judgment calls with no source**, general knowledge of Swedish and car vocabulary: *ägarnyckel, inbjuden förare, gångassistans, kraftassistans, nedförsassistans, sparkstartshastighet, antispinn, varselljus, tripp, toppfart, översikt, klar senast, inte knuten till*, every shortening in section 5 other than Microsoft's list, *avisering* for a rider-chosen alert, *elspark* as the short form. *Inbjuden förare* replaced *delad förare* on the independent review; it says how the person got access, which the English doesn't. *PIN på styret*, *Experiment* and *Vägm.* most need a native's check. *Inspelning* for a recording is acceptable, as Strava-style apps use it, though it leans toward audio or video; the debate can revisit it.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`, at 12.5sp unless noted. Glyphs: `fontmetrics.Font.missing` on all four Saira weights.
