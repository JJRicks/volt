# Volt style guide: Finnish (fi)

**Status: Claude-only proposal (2026-11-05), not final.** One model drafted this from the sources in section 10. The three-vendor debate has not seen it and no native speaker has reviewed it. "Approved" below means "the wording this proposal asks every drafter to use"; safety wording included, all of it can change. The form of address is the plan's proposal and the owner has not confirmed it yet.

The two things that break Finnish in an app are a case ending on a value the translator can't see, and a long compound that doesn't fit. Section 7 covers the first, section 5 the second. Read both before drafting.

## 1. Register and form of address

- Address the rider as **sinä**, but almost never write the word. The verb and the possessive suffix carry it (Microsoft's Finnish guide says the same): *Haluatko jatkaa?*, *Yhdistä laturi*, *potkulautasi*. Never *te*, never *sinun* where the suffix does the job, never the third person (*käyttäjä voi*).
- **Buttons, toggles and menu actions use the singular imperative:** *Tallenna*, *Yhdistä*, *Lukitse*, *Avaa lukitus*, *Kokeile*, *Palauta*.
- **Instructions in sentences use the imperative too** when the rider has to act: *Yhdistä uudelleen, niin voit varmistaa tilan.* **Use the passive** for what happens without the rider, and for general rules: *Ajo poistetaan*, *Potkulaudan on oltava pysäköitynä*, *Asetus tallennetaan puhelimeen*.
- **Labels and titles are nouns:** *Latausraja*, *Ajotila*, *Varmuuskopio*.
- **Errors state the fact in the passive or as a noun clause** (Microsoft: error messages are usually passive, direct and short): *Arvon lukeminen epäonnistui*, *Arvoa ei voitu lukea*. Don't apologise, even if the English does.
- **A process in progress uses the passive and keeps `…`:** *Yhdistetään…*, *Lukitaan…*, *Avataan lukitusta…*, *Mitataan…*.
- Don't copy English "To …, do …": *Jos haluat vaihtaa PIN-koodin, yhdistä omistajana*, or just the imperative.
- Calm, plain, friendly. No exclamation marks in errors, no slang (*skuutti*, *äppi*), no marketing tone, no reassurance the English lacks (*turvallisesti*, *taatusti*).
- Finnish has no grammatical gender and no articles. Nothing agrees with a gender, and *hän* is never needed. The rider, when named, is **kuljettaja**.
- Write Finnish, not calqued English. Use a verb where Finnish wants one, and one sentence's idea at a time: Finnish doesn't chain participles well on a phone screen.

## 2. Punctuation and quotes

- Quotes: **”…”**, and **’…’** inside. The same mark opens and closes; never “…”, «…» or straight quotes. Microsoft says Finnish uses quotes less than English, so don't copy decorative English quotes.
- **A sentence that names a button, tab or screen** puts a generic noun in the case the sentence needs, then the exact label in ”…” in its nominative: *napauta painiketta ”Avaa lukitus”*, *avaa näkymä ”Varmuuskopio”*, *ota käyttöön asetus ”Lataus valmiiksi”*. The label never takes an ending. This is a judgment call: *Tallenna-painiketta* is the more native form, but it fails on multi-word labels and placeholders, and one rule is safer.
- A period or comma goes after the closing quote unless the quote is a whole sentence.
- **Colon:** lower case after it when one sentence or a value follows (Microsoft): *Viimeisin tieto: lukittu*. A case ending joins an abbreviation or symbol with a colon and no space: *EU:n*, *USB:n*, *85 %:n*, but see section 7 before using one.
- **Dashes:** en dash **–** with a space on both sides as a sentence dash; en dash with no spaces in a range: *5–10 min*. Never the em dash. A hyphen joins compounds.
- **Loose compound:** a multi-word first part takes a space before the hyphen: *Akku vähissä -varoitus*.
- **Hyphen at a vowel clash:** when a compound's first part ends in the vowel the second part starts with, it takes a hyphen: *omistaja-avain*, *toimintamatka-arvio*. Don't drop it.
- **Apostrophe:** the Finnish words that have one (*liu’uta*, *ruo’on*) use the typographic ’ (U+2019), which needs no escape and which Saira has. *Liu’uta* is the word for "slide" on the lock row.
- **`&`** in running text and in labels becomes *ja*, as Microsoft's guide says: *Näyttö ja ääni*, *Tiedot ja luvat*. Keep it only in code and file names.
- No comma before *ja* or *tai* in a list. A subordinate clause takes a comma: *Yhdistä, kun potkulauta on lähellä.*
- Keep the English's `·` separators and `…` as written. Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles and section headers. Only the first word and names take a capital: button *Avaa lukitus*, label *Latausraja*, title *Kokeelliset toiminnot*. Never English Title Case, even when the English has it (*Walk Assist* → *Talutustuki*).
- Feature names are lower case in a sentence, unless quoted as a label: *ota ajastettu lataus käyttöön*.
- Days, months and languages are lower case: *maanantai*, *maaliskuu*, *suomi*.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android and model names. Drive modes keep the case the English string uses in that string, with a hyphen to the noun: *Sport-tila*, *ECO-tila*, *ajotila Drive*.
- Capitals keep their dots where Volt upper-cases text: *LÄMPÖTILA*, *AKKU*, *YHTEENSOPIVUUS*.
- After a colon: lower case (section 2).

## 4. Numbers, dates and units

- **Decimal comma** in text you write: *2,5*. Thousands group with a **no-break space (U+00A0)** from five digits (*12 345*); four digits can stay closed in labels (*1234*). Volt's `Fmt` formats live numbers for the phone's region, so its output can differ; never rewrite a placeholder's number. Never U+202F: Saira has no glyph for it.
- **Percent always takes a no-break space (U+00A0)** between the number or placeholder and the sign (Kielitoimisto: *10,5 %*): `%1$s %%` gives *85 %*. A plain space could wrap the sign onto its own line. The same goes for `formatted="false"` text, whose literal is `100 %`.
- **Unit symbols stay** and take the same no-break space: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. They take no period and no plural: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Never *km/t*, even though Finnish shops write it. **Unit words are translated:** *kilometrit*, *mailit*, *minuutti*.
- Numbers in the English stay in the Finnish as digits. Don't write them out or change them.
- **Plurals: `one` is exactly 1.** A count takes a **singular** noun: `one` is the nominative, `other` the **partitive singular**, and the verb stays singular: *1 ajo tallennettu*, *%1$d ajoa tallennettu*. Never the plural partitive after a number (*5 ajoja*). In any other case both forms match: *1 minuutin kuluttua*, *%1$d minuutin kuluttua*. Keep the placeholder in `one`; never copy "1 minutes".
- **Dates and times come from Volt's formatters;** don't hard-code an order. A fixed date in prose is *5. maaliskuuta*, a time *klo 7.30*. Finnish uses a period in the time, but Volt's output may differ, so don't rewrite it. Never *a.m./p.m.* Write **klo** before a time placeholder: *klo %1$s*.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. **A Finnish compound is one word, so it can't wrap.** Budgets bind on the longest single word. Measure with Saira (`tools/l10n/fontmetrics.py`); don't count letters. Try these in order:

1. **Drop the part the screen already supplies.** *Akkuasetukset* → *Akku* on a settings screen. *Latausraja* → *Raja* only on a card already about charging.
2. **Open the compound into a genitive phrase, so it can wrap at the space.** This is the Finnish move: *Yhteensopivuustarkistus* → *Yhteensopivuuden tarkistus*, *Ajoasetukset* (73dp at 12sp, over the 64dp shortcut budget) → *Ajon asetukset*. Never invent a hyphen inside a word to make a break.
3. **Use a shorter real word.** *Reaaliaikatiedot* → *Reaaliaika*.
4. **Use an accepted abbreviation from this table.** Nothing else. A Finnish abbreviation ends in a period, cuts after a consonant and keeps its dots: *lämp.*, *keskim.* A compound keeps its first part whole, and its second part shrinks to its start: *matkam.*

| Full | `_short` | `_compact` | Width at 12.5sp (dp) | Note |
|---|---|---|---|---|
| Enimmäisnopeus | **Maks. nopeus** | **Maks.** | 99.5 → 80.0 → 34.4 | The setting. *maks.* is the standard abbreviation; *enimm.* isn't one. Fits the 90dp tile |
| Huippunopeus | **Huippunopeus** | **Huippunop.** | 84.6 → 67.1 | The measured value. Never *Maks.* |
| Matkamittari | **Matkam.** | icon | 75.5 → 50.9 | The `odometer-short` row, Finnish for `Odo.` |
| Keskinopeus | **Keskinop.** | | 73.9 → 56.4 | |
| Lämpötila | **Lämp.** | | 57.6 → 35.2 | |
| Yhteensopivuus | **Yhteensop.** | | 93.0 → 65.0 | |
| Kapasiteetti | keep | | 70.4 | Don't use *Kap.*; it isn't recognised |
| noin, keskimäärin, enintään, vähintään | **n.**, **keskim.**, **enint.**, **väh.** | | | Before a value: *n. 5 min*, *enint. 25 km/h* |
| arvio, arvioitu | **arvio** | | 20.9 at 9sp | The `est.` chip. It's short already; *arv.* isn't recognised |

Measured words that fit as they are: *Lukitus* 42.7, *Asetukset* 58.5, *Akku* 29.3, *Ajot* 23.7 (all under the 62dp tab budget); *Lukittu* 41.1, *Latausraja* 61.1, *Osamatka* 58.7, *Valot* 29.9, *Reaaliaika* 60.5 (58.1 at 12sp), *Pikatoiminnot* 81.3, *Lisäkäyttäjä* 71.4, *Koontinäyttö* 76.2, *Näyttö ja ääni* 81.4, *Tiedot ja luvat* 82.1, *Ajoasetukset* 76.0, *Akkuasetukset* 86.3, *Toimintamatka* 86.6 (all under the 90dp tile at 12.5sp). *Viimeisin lukema* is 86.3 at 11sp, under the 96dp budget.

**Tile ladders:** the shortcut full rung has 64dp a line at 12sp, the short rung 90dp at 12.5sp. So a full rung opens the compound and a short rung may close it. *Ride settings*: *Ajon asetukset* → *Ajoasetukset*. *Battery settings*: *Akun asetukset* → *Akkuasetukset*. *App preferences*: *Voltin asetukset* (*Sovelluksen* alone is 67dp, over budget). *Experimental controls*: *Kokeelliset toiminnot* (each word fits) → *Kokeelliset* 63.1. *Charge done by*: *Lataus valmiiksi* (92.8 on one line, so it wraps at the space). Its time: *Lataus valmiiksi klo* → *Valmiiksi klo* 71.9.

**Known overflow, not to be fixed with a wrong word:** *My scooters*: the word *potkulaudat* is 67.5dp at 12sp, 3.5dp over the shortcut budget. Write *Omat potkulaudat*, short *Potkulautani* (74.4), and report it in your notes.

An abbreviation counts its period, takes no case ending, and belongs only in a label, never in a sentence. A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Lukitse, Avaa lukitus, Lukittu, Lukitus avattu, Tuntematon, Ei varmistettu, Ei koskaan, Aina, Hälytys, PIN-koodi, Kokeellinen*. Never drop the *ei* from a negative to save room; *Varmistettu* is the opposite of *Ei varmistettu*. If one of these doesn't fit, write the whole word and report it rather than shortening it.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Finnish | Rules |
|---|---|---|
| Lock (action) | **Lukitse** | Button. In progress *Lukitaan…* |
| Lock (noun, card title) | **Lukitus** | Never as a state: *Lukitus* alone doesn't say locked or unlocked |
| Locked | **Lukittu** | Not *lukossa*, which is colloquial |
| Unlock (action) | **Avaa lukitus** | Always both words. Noun **lukituksen avaus**. In progress *Avataan lukitusta…* |
| Unlocked | **Lukitus avattu** | Never *Lukitsematon* (see below). In a sentence *lukitus on avattu*. **Auki** only where the row's lock icon or title supplies the context and *Lukitus avattu* doesn't fit |
| Lock state unverified | **Lukituksen tilaa ei ole varmistettu** | Must never read as confirmed. Keep *ei* |
| Lock state unknown | **Lukituksen tila on tuntematon** | As a short status *Lukituksen tila tuntematon*. Must never read as confirmed |
| Unknown (alone) | **Tuntematon** | Android's own word. In a sentence *ei tiedossa* is fine |
| Unverified | **Ei varmistettu** | Agrees with nothing. *varmistamaton* before a noun |
| Unconfirmed | **Vahvistamaton** | Also *ei vahvistettu* |
| Not confirmed | **Ei vahvistettu** | |
| Never | **Ei koskaan** | *koskaan* alone means **ever**. In a sentence the verb carries the *ei*: *Numeroita ei koskaan näytetä* |
| Always | **Aina** | *Always on*: **Aina päällä**. *ei aina* means "not always" |
| Alarm | **Hälytys** | Alert is **varoitus**, a notification is **ilmoitus**. The three stay distinct |
| Alarm sound | **Hälytysääni** | |
| Stop (a sounding alarm) | **Lopeta** | Not *Pysäytä*, which reads as stopping the scooter |
| PIN | **PIN-koodi** | The word is always written *PIN-koodi*, and it inflects: *PIN-koodin*, *PIN-koodia*. Not *tunnusluku* or *salasana* |
| Handlebar PIN | **ohjaustangon PIN-koodi** | A password is **salasana**, a separate thing. "Your phone-free fallback" is a description: *Varakeinosi ilman puhelinta* |
| PIN unlock | **PIN-avaus** | The toggle |
| Owner key | **omistaja-avain** | A secret. *avain* reads as a digital key here, as in *salausavain*. The hyphen stays. "Not a password" is *Ei salasana* |
| Charge limit | **Latausraja** | Never *latauskatto*, or *latausrajoitus*, which reads as a restriction on charging |
| Experimental | **Kokeellinen**, plural **Kokeelliset** | *Kokeelliset toiminnot* for the section |
| Untested | **Ei testattu** | *testaamaton* before a noun. Status only |
| Try (a control) | **Kokeile** | Not the retry phrase. See Fixed words |
| not affiliated with NAVEE | **ei ole sidoksissa NAVEE-yhtiöön** | Footer: *Volt %1$s · ei ole sidoksissa NAVEE-yhtiöön*. Never soften it: no *epävirallinen*, *riippumaton*, *ei suoraan*. Its head noun carries the ending, so NAVEE stays unchanged |

- **Lukittu vs Lukitus avattu** are visibly different. **Lukitsematon** is the dictionary word for unlocked, and it's banned here because it starts exactly like *Lukitse*, ends in a negative only a careful reader catches, and the checks count it as *lukitse*. So is *lukitsematta* in a state.
- **Bare *Avaa* means Open.** Volt also opens screens and files, so an unlock never shortens to *Avaa*.
- **Don't describe an unlock as removing or switching off the lock** (*Poista lukitus*, *Lukitus pois*). Only use *poista käytöstä* where the English itself says turn off.
- **On and off:** settings use **Käytössä** and **Ei käytössä**, lights and motors use **Päällä** and **Pois päältä**. Never shorten *Pois päältä* to *Päältä*, which alone says nothing. Never *Kyllä/Ei* in their place.
- **Keep every negation.** Prefer the word *ei* or *älä*, which the checks and a reader both see. A negative suffix (*-maton*, *-matta*, *-ton*) is correct Finnish but easy to miss, and `check.py` knows only a few of them, so a draft with one gets a polarity warning that a judge has to read. *epäonnistui* (failed) is fine and counts.
- **varmistaa vs vahvistaa:** *varmistaa* is what Volt or the rider does to verify; *vahvistaa* is what a readback confirms. They look alike and aren't interchangeable here.
- **Unconfirmed clauses** use *ei voitu vahvistaa, …-ko*, which doesn't presuppose the event: *Ei voitu vahvistaa, poistuiko aikataulu käytöstä.*
- **`check.py` doesn't check Finnish inversion.** Finnish has no list of unlock or removal contexts yet, so *Avaa lukitus* counts as containing the lock term and passes where the English says Lock. Only you and the judge catch this. Read every lock string twice.

## 7. Case endings and placeholders

A placeholder's value can't take a case ending. You can't see the value, and its last vowel decides the ending (*GT3:een* or *Maxiin*?). **Never put an ending, a colon-ending or a compound hyphen on a placeholder.** The value always stands in its nominative. Rephrase with one of these, in this order:

| Way | English | Finnish | Never |
|---|---|---|---|
| **Label form** | Destination for %1$s: %2$s | `Kohde (%1$s): %2$s`, or `%1$s · kohde: %2$s` | `%1$s:n kohde` |
| **Label form** | Rejected (%1$s) | `Hylätty (%1$s)` | |
| **Head noun takes the case, value follows** | Connecting to %1$s | `Yhdistetään potkulautaan %1$s` | `Yhdistetään %1$s:een` |
| **Head noun** | Reading %1$s… | `Luetaan arvoa %1$s…`, or `%1$s: luetaan…` | `Luetaan %1$s:ää…` |
| **Head noun** | Applying %1$s… | `Otetaan käyttöön: %1$s…` | `Otetaan %1$s:ää käyttöön` |
| **Quoted name after a head noun** | Data from “%1$s” goes to: | `Kohteen ”%1$s” tiedot menevät:` | `”%1$s”:n tiedot` |
| **klo before a time** | at %1$s | `klo %1$s` | `%1$s:ltä`, `%1$s:ssa` |
| **Number with a fixed head** | Charging to %1$s%% | `Ladataan tasolle %1$s %%` | `Ladataan %1$s %%:iin` |
| **Postposition that needs no agreement** | in %1$s | `%1$s kuluttua`, or `Aikaa: %1$s` | `%1$s:ssa` |

Generic head nouns to use: *potkulauta*, *malli*, *kohde* (a target of any kind), *arvo* (a value), *asetus*, *painike*, *näkymä*, *tiedosto*, *taso* (a percentage level), *aika* (*ajassa %1$s*).

More rules for the same problem:

- **Duration values** are written with symbols or by `Fmt`, so *kuluttua* after one is safe: *5 min kuluttua*. *Kuluttua* takes the genitive, which the symbol hides.
- **An adjective or noun before the value doesn't agree with it,** because the value stays nominative: *mallissa %1$s*, *potkulaudan %1$s akku*.
- **Literal percent and symbol endings exist** (Kielitoimisto: *11 %:n*, *9 %:iin*) and are correct when the number is literal. With a placeholder, use *tasolle* or a label instead, because a translator can't check what the sentence does to the numeral's own case.
- **Volt** inflects like any name, and the checks accept it: *Voltin*, *Voltissa*, *Voltiin*, *Voltille*. **NAVEE doesn't:** use a head noun, *NAVEE-sovellus*, *NAVEE-sovelluksessa*, *NAVEE-yhtiö*, so the name stays exactly as written. **A literal model name** that ends in a digit takes a colon: *GT3:n*. A model name in a placeholder follows this section.
- **Drive modes:** *Sport-tilassa*, *ECO-tilaan*. The ending goes on *tila*, not on the name. The English's own word stays unchanged.
- **A word in a placeholder's slot may be a translated label or status**, such as "Last known: %1$s" with *lukittu* or *lukitus avattu*. Write it as a label so the value's nominative fits: *Viimeisin tieto: %1$s*.
- **A count in a string that isn't a plural in the English** goes in a label, so no noun has to agree with it: *Ajot: %1$d*. A real plural follows section 4.

## 8. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Scooter:** **potkulauta** everywhere. *sähköpotkulauta*, the word Finnish shops and NAVEE's retailers use, is only for where the English says "electric scooter", because at 98.6dp it can't sit in a label and the app is only ever about electric ones. Never *skuutti* or *skootteri* (*skootteri* is a moped). **Debate item.**
- **Three speeds:** the setting is **Enimmäisnopeus** (*Maks. nopeus* on the tile). A limit below it is **nopeusrajoitus**. The measured value on a trip or ride is **Huippunopeus**. Never swap them.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback. The `top-speed` and `maximum-speed` glossary rows already carry D3's meanings, and every other string with these terms already matches D3.
- **Rides vs trips:** a recorded ride is an **ajo** (*Ajot* for the screen). *Trip*, the distance since a reset, is **osamatka**, as on a car's trip meter. A path on the map is a **reitti**. Don't use *matka* alone, which could be either.
- **Range:** **toimintamatka**, as Finnish shops write it. *toimintasäde* is also used; don't mix them.
- **Try vs retry vs test:** Trying a control is **Kokeile**, and its results use **kokeilu**. An ordinary retry is **Yritä uudelleen**. A guided test is **testi**, verb **testata**. *Test scheduled charging* is a check: **Tarkista ajastettu lataus**. A compatibility check is **yhteensopivuuden tarkistus**.
- **Dashboard vs display:** Volt's Home layout is the **Koontinäyttö**, Microsoft's Finnish word for a dashboard. The scooter's own screen is the **potkulaudan näyttö**. *Kojelauta* is banned for Volt's Home: Finnish scooter manuals use it for the scooter's own display, the very thing this distinction separates. The Home tab is **Etusivu**.
- **Settings vs setup:** settings are **asetukset**, a one-time setup is **käyttöönotto**.
- **Restore vs set back:** bringing back a backup, or NAVEE logging as Fixed words says, is **Palauta**. Undoing a try is **Peru muutos**, also where a test's earlier value goes back even if the English says restore. **Energy recovery is Energian talteenotto**, unrelated to either. Cancel is **Peruuta**, one letter away from *Peru*: only *Peru muutos* uses the shorter verb.
- **Readback:** **takaisinluku**, a neutral word, because a readback can also fail to confirm: *Takaisinluku poikkeaa*, *Vahvistettu takaisinluvulla*. Never a word with *vahvistus* inside it.
- **Rejected:** *Rejected* is **Hylätty**, and *Refused by scooter* **Potkulauta hylkäsi**.
- **Pair vs bind vs connect:** Bluetooth pairing is **muodosta laitepari**, as Android says, nouns **laitepari**, **paritus**. NAVEE account binding is **liitä tiliin**. A live link is **yhdistä**, and the link **yhteys**. Don't use *yhteystiedot* for connection details: that word means contact details. **Yhteyden tiedot.**
- **Owner key parts:** the file is **omistajan asetustiedosto**, on onboarding steps **omistajatiedosto**. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Roles:** owner is **omistaja**, shared rider is **lisäkäyttäjä**. The compact role label (`more_role_shared`, English *Shared*) is also **Lisäkäyttäjä** (71.4dp), never *Jaettu*, which reads as a shared scooter.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes: *Pois päältä, Matala, Keskitaso, Korkea*.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Eco-arvio** is a range option, translated.
- **Charge done by:** the feature name is **Lataus valmiiksi**, and its time setting **Lataus valmiiksi klo**. The feature only plans a start, so never word it as a charge that has finished: *Lataus valmis* says it's done and is banned. In a sentence, name it after a head noun: *asetus ”Lataus valmiiksi” on pois päältä*.
- **Limit words that aren't the same:** charge limit is **latausraja**; *At limit* in a schedule result is **Rajalla**; speed limit is **nopeusrajoitus**. A recording or backup limit is plain **raja**, or say what it limits.
- **Quick settings:** the Dashboard's shortcut row and widget (*Quick access*, *Quick settings*) are **Pikatoiminnot**. Never *Pika-asetukset*: that is Android's pull-down panel.
- **Lisää** is both *Add* and the *More* tab, as in Android. The context separates them; don't invent another word.
- **Parts:** handlebar **ohjaustanko**, throttle **kaasu**, brake lever **jarrukahva**, power button **virtapainike**, tail light **takavalo**, headlight **ajovalo**, turn signal **suuntavilkku**, charger **laturi**, charging port **latausportti**, motor **moottori**, battery **akku**, charge level **akun varaus**.
- **Experimental LAB badge:** **KOE** (20.7dp at 10.5sp, under the 40dp budget), as D3 allows a language its own short word, because *LAB* means nothing in Finnish. TalkBack speaks **Kokeellinen**. **Debate item.**
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does: *Raportti on englanniksi*.

## 9. Android's own wording

Quote Android's settings as Android writes them: **USB-vianetsintä**, **Kehittäjäasetukset**, **Koontiversion numero**. *Asetukset › Tietoja puhelimesta*: phone makers rename it, so write generally where you can: *avaa asetuksista puhelimen tiedot*.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Yritä uudelleen**; *Yritä hetken kuluttua uudelleen*. Never *Kokeile*, which reads as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **ei tietoa**. Says only that no value came. Never *ei raportoitu* or *ei ilmoitettu* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **potkulaudan ilmoittama**; verb **ilmoittaa**: *potkulauta ilmoittaa*. A claim, not a measurement. Never *raportoitu*, *vahvistettu* or *mitattu*. *raportti* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **NAVEE-tapahtumaloki**. Never *rekisteröinti* or *kirjautuminen*, which read as an account |
| restore logging | turning that logging back to how it was (usually off) | **palauta tapahtumaloki ennalleen**. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **Voltin apuohjelma**; *apuohjelma* alone once the screen makes clear which. The script on the computer is the different **kaappausohjelma** |
| stock charger | the charger that came with the scooter | **alkuperäinen laturi**. Not *vakiolaturi*, so that one word stays in use |
| high-speed charger | a faster charger the rider may own | **pikalaturi**; alone, *Pika*. Never a phrase with *nopeus*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **katkos**. Means no data was recorded, not that power or the ride was cut |
| elevation | height above sea level on a route | **korkeus** |
| current direction | which way battery current flows | **virran suunta** |
| throughput | how much energy has passed through the battery | **akun läpi kulkenut energia**; *energia* once the screen has said so. An amount, not a rate |
| battery gauge | the scooter's own battery percentage reading | **akkumittari**. The scooter's own figure, not a measurement. Not *akun varaus*, which is the level itself |
| raw | an unprocessed value, also used as a short tag | **raaka**: *raaka-arvo*; as a tag **RAAKA** (34.0dp at 10sp, under the 40dp budget), where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Viimeisin lukema**; with a value, *Viimeisin lukema: %1$s*. The data may be old, so never word it as current. Not *Viimeisin tieto*, which is the last known state |
| add | adding a widget, scooter or item | **lisää**: *Lisää widget*, *Lisää toinen potkulauta* |
| enter (typing) | typing a value | **anna**: *Anna uusi PIN-koodi*. Never *syötä*, which is more technical, or *kirjoita* for a code |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **sovellus**: *virallinen sovellus*, *NAVEE-sovellus*. Never *appi*, *äppi* or *applikaatio* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter takes the illative: **yhdistä potkulautaan**, *Yhdistä ensin potkulautaan*, *Yhdistä uudelleen*. The button stays *Yhdistä*. Plugging in is a different verb: *kytke laturi* |
| set a value | one verb for setting a value on the scooter | **aseta**: *Aseta PIN-koodi*, *Aseta: %1$s*. The noun stays *asetus*, and setting something back stays *Peru muutos* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Passive present, in dialogs and hints alike: *Ajo poistetaan*, *Kartta avataan*, *Tiedot korvataan*. Never *Poista…* or *Avaa…*, which read as commands, nor *Poistaa…* with no subject |
| screen and section titles | noun form or verb form, with one example | Noun form: *Varmuuskopio ja palautus*. If the title is an action with no good noun, the imperative as on a button: *Tarkista ajastettu lataus* |

## 10. Sources consulted

**NAVEE's official Finnish: not found.** The official app's Finnish wording wasn't found: no public page shows its strings for lock, charge limit, PIN, owner key or experimental features. Those words are Claude's own proposals, and the official app may differ. No APK was downloaded.

- **NAVEE's own Finnish manual: exists but not read.** A search snippet shows the [V40 EU manual](https://service.naveetech.com/wp-content/uploads/2024/07/NAVEE-V40-Instruction-Manual-EU-version.pdf) lists FI on its cover with the word *Sähköpotkulauta*, but the URL returned 404. **The debate should try to get it.** It's the closest thing to official wording.
- **Retailer text for NAVEE products, weak evidence, seen only as search snippets** (the pages returned 429): [Gigantti NAVEE brand page](https://www.gigantti.fi/brand/navee) (*NAVEE-sovellus*, *ajohistoria*, *latausrajoja*, *energian talteenotto*), [N65i II](https://www.gigantti.fi/product/urheilu-ja-vapaa-aika/sahkopyorat-ja-muut-sahkoajoneuvot/sahkopotkulaudat/navee-n65i-ii-sarjan-sahkopotkulauta-n65i-ii-e-musta/986256) (*lukitseminen ja avaaminen*, *suuntavilkut*), [GT3 Max-E](https://www.gigantti.fi/product/urheilu-ja-vapaa-aika/sahkopyorat-ja-muut-sahkoajoneuvot/sahkopotkulaudat/navee-gt3-max-e-sahkopotkulauta-ne1676-musta/888584) (*elektroninen lukitus*, *toimintasäde*, *TCS*, *km/t*), [GT5 Max](https://www.gigantti.fi/product/urheilu-ja-vapaa-aika/sahkopyorat-ja-muut-sahkoajoneuvot/sahkopotkulaudat/navee-gt5-max-sahkopotkulauta-ne1729-musta/1041497) (*luistonesto*). [Vertaa.fi](https://www.vertaa.fi/potkulaudat/navee/gt3_sahkopotkulauta_1081690708/) returned 403.
- **Other brands' Finnish manuals, read in full, good for parts and modes:** [Xiaomi Mi Electric Scooter Pro](https://admin.abicart.se/shop/ws4/50604/files/Manualer/Finland/Scootterit/Mi_Electric_Scooter_Pro_k%C3%A4ytt%C3%B6ohje_FI_v1.2.pdf) (*potkulauta*, *ajotila*, *Sport-tila*, *ohjaustanko*, *kaasu*, *jarrukahva*, *virtapainike*, *ajovalo*, *latausportti*, *laturi*, *alkuperäinen laturi*, *maksiminopeus*). [Niu](https://cdn.verkkokauppa.com/files/63074/e26d3/6114f/21902/3976.pdf) (*sähköpotkulauta*, *kojelauta* for the scooter's own display, *ajotila*, *talutustila*, *virtapainike*, *enimmäisnopeus*).
- **Android's Finnish:** [AOSP SettingsLib fi](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-fi/strings.xml) (*USB-vianetsintä*, *Kehittäjäasetukset*, *Muodosta laitepari*, *Yhdistetään…*, *Yhteys katkaistu*, *Tuntematon*). **From memory, not read:** *Koontiversion numero*, *Tietoja puhelimesta*, *Pika-asetukset*, *widget*, *Avaa lukitus*.
- [Microsoft Finnish localization style guide](https://aka.ms/finnish-styleguide), read: leaving out "you", lower case after a colon, en dash rules, sparing quotes, `&` as *ja*, passive error messages, abbreviations must follow normal grammar, avoid long compounds. *Koontinäyttö* is from memory of Microsoft's Finnish products, not this guide.
- [Kielitoimiston ohjepankki, percent sign](https://www.kielitoimistonohjepankki.fi/ohje/prosenttimerkki/), read: the space, and *11 %:n*, *9 %:a*, *9 %:iin*. **Kielitoimisto's abbreviation list wasn't read** ([link from Microsoft's guide](http://www.kielitoimistonohjepankki.fi/haku/lyhenteet/ohje/435)): *maks.*, *keskim.*, *lämp.*, *enint.*, *väh.*, *n.* and the colon rule are from memory. *Matkam.*, *Keskinop.*, *Huippunop.* and *Yhteensop.* are Claude's formations by the usual pattern, not listed abbreviations. **A native speaker should check them first.**
- **Judgment calls with no source:** *potkulauta* over *sähköpotkulauta*, *Lukitus avattu*, *omistaja-avain*, *lisäkäyttäjä*, *läheisyysavaus*, *Koontinäyttö*, *Lataus valmiiksi*, *KOE*, *takaisinluku*, and the quoted-label rule in section 2.
- **Saira:** all four weights in `res/font/` have ä, ö, å, š, ž and their capitals, ”, ’, –, ·, …, › and U+00A0, read from the cmap with `tools/l10n/fontmetrics.py`. They lack U+202F. Widths: same tool, java.awt, `saira_medium.ttf`.
