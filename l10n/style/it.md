# Volt style guide: Italian (it)

**Status: Claude-only proposal (2026-11-05), not final.** One model drafted it from the sources in section 8. The three-vendor debate has not seen it and no native speaker has reviewed it. "Approved" here means "the wording this proposal asks every drafter to use", safety wording included.

## 1. Register and form of address

- Use **tu**, singular, always (owner's decision): *il tuo monopattino*, *Collega il caricabatterie*. Never *Lei* or *voi*. NAVEE's manual uses the impersonal infinitive (*Premere il pulsante*); don't copy it. NAVEE's retail text uses tu (*goditi il blocco automatico*).
- **Buttons, toggles and menu actions use the tu imperative**, as Android's Italian does (*Accoppia*): *Salva*, *Connetti*, *Blocca*, *Annulla*. **Instructions in sentences use the same form**: *Riconnetti per verificare*. A negative instruction takes the infinitive: *Non spegnere il monopattino*.
- **Status text is a participle or a noun, with no subject:** *Connesso*, *Blocco in corso…*, *Misurazione in corso*.
- Tap on the screen is **Tocca**. A physical button is **Premi**.
- Tone: calm, plain, direct. No exclamation marks in errors, no marketing and no slang. No *per favore* in short labels.
- Use the generic masculine for roles (*proprietario*, *utente autorizzato*); no asterisks or schwa. Rephrase when a sentence needn't name the rider.
- **Volt takes the feminine as an app:** *Volt non è affiliata a NAVEE*. For what Volt or the scooter did, use *si* or the state itself: *Non è stato possibile leggere*.

## 2. Punctuation and quotes

- Quotes: **«…»** first, **“…”** inside, with no spaces inside. Quote a button or feature named in a sentence: *Tocca «Salva»*.
- **Apostrophe: the straight `'`**, as Android's Italian strings write it, and as the glossary's forms do. The check compares forms exactly, so *dell’energia* with a curly apostrophe won't match *dell'energia*. Write the text plainly, without a backslash; the tools add the escape.
- Elide normally: *l'app*, *un'altra*, *dell'energia*. Accented capitals are real letters: **È**, never *E'*. Saira has à è é ì ò ó ù À È É Ì Ò Ù in all four weights (checked).
- Accents matter: *perché*, *né*, *più*, *può*, *già*, *è* (is) vs *e* (and), *sì* (yes) vs *si*.
- Ellipsis is the single character `…`; a verb in progress keeps it: *Connessione…*
- A colon is followed by lower case unless a name follows: *Ultimo stato noto: bloccato*.
- Lists use commas, with no comma before *e* or *o*. *ed* only before a word starting with *e-*.
- Keep the English's `·` separators, placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere.** Button: *Salva il backup*. Label: *Limite di carica*. Title: *Comandi sperimentali*. Never English Title Case, even where the English has it (*Walk Assist* → *Assistenza alla spinta*).
- Lower case mid-sentence for feature names, unless quoted: *attiva la ricarica programmata*.
- Days, months and languages are lower case.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth and Android. Drive modes and their level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *modalità Sport* where the English says Sport.
- After a colon, lower case, as in section 2.

## 4. Numbers, dates and units

- Decimal comma and thousands period (`decimal_style`): *22,2*, *1.234,5*. NAVEE's manual writes *22,2* and *275,4*. Volt's `Fmt` formats live numbers; never rewrite a placeholder's number or put separators around one.
- **Percent takes no space:** `%1$s%%` gives *85%*, as Android's Italian and CLDR's format do. This holds for `formatted="false"` text too, whose literal is `100%`.
- Numbers in the English stay as digits. Don't write them out or change them.
- Unit symbols stay and take a no-break space (**U+00A0**, never a plain space and never U+202F, which Saira lacks): *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *chilometri*, *miglia*, *chilometri orari*.
- About: **ca.**, as the manual writes it: *ca. 6 h*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose is *5 marzo*. Use the 24-hour clock; don't write *a.m./p.m.* yourself.
- Plurals: `one` covers exactly 1. **`many` takes *di* whenever a counted noun follows the count:** `%1$d di corse`, `%1$d di altri modelli`, `%2$d di byte`, because `many` is a value such as 1000000. Otherwise it copies `other`. Named exceptions, with no noun to link or a word that takes no *di*: *%1$d percento*, *%1$d selezionati*, *%1$d attivi*. The placeholder form stays the same in both.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Try these in order:

1. Drop words the screen already supplies: *Potenza di ricarica* → *Potenza*.
2. Use a shorter word with the same meaning, never a colloquial word or a different meaning. *monopattino* → *mezzo* only on the two keys named below.
3. Use an abbreviation from the table. Nothing else: no invented cuts.
4. If none of these fits, write the correct word and say so in your notes.

**Full → short → compact, real ladders** (widths at 12.5sp):

| English | Full | Short | Compact |
|---|---|---|---|
| Battery temp. / Temp. | *Temperatura batteria* | *Temp. batteria* (83) | *Temp.* (35) |
| Charging power / Power | *Potenza di ricarica* (106) | | *Potenza* (48) |
| Time to %1$s / To %1$s | *Tempo fino a %1$s* (105) | | *Fino a %1$s* (66 with 80%) |
| Time to full (est.) / To full (est.) | *Tempo per la carica completa (stima)* | *Fino alla carica completa (stima)* | *Fino al pieno* (74) |
| Odometer / Odo. | *Odometro* (58) | | *Odom.* (38) |
| Est. capacity (health) / Est. capacity | *Capacità stimata (salute)* | | *Cap. stimata* (71) |

*A carica completa* means "at full charge" and *Tempo a 80%* reads as time spent at 80%, so these labels say *fino a*, until. **The no-article rule is for real placeholders only** (not *all'%1$s*, which breaks for *al 90%*); *To %1$s limit* is *Fino al limite di %1$s*. A literal value or noun takes its article: *fino al 100%*, *fino alla carica completa*. The compact rung would be *Fino al 100%*, but its English isn't `formatted="false"`, so the check refuses both `100%` and `100%%` there; it says *Fino al pieno* (73.7 against the English's 68.4) until the English gains that attribute. That rung drops the estimate mark because *(stima)* doesn't fit and the value beside it already starts with ≈; *Fino a 80%* has no mark either. Plain *Time to full* is *Fino alla carica completa*.

**Literal percent:** a key without `formatted="false"` can't carry a percent sign unless the English has one. Say it in words instead, as *Carica per l'intero indicatore* does.

**Approved abbreviations.** An abbreviation ends in a period, and the period counts toward the width, except *max*, *min* and *info*, which take none (NAVEE's manual: *Velocità max*).

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Velocità massima | **Velocità max**, then **Vel. max** | 101 → 74 → 49 | The setting. *Velocità max* fits the 90dp tile. Never for a measured speed, even where the English says Max (`rides_tile_max_speed`) |
| Velocità di punta | **Vel. di punta** | 97 → 72 | The measured value. Never *Vel. max* |
| Velocità media | **Vel. media** | 85 → 60 | |
| Odometro | **Odom.** | 58 → 38 | |
| Temperatura | **Temp.** | 74 → 35 | |
| Capacità | **Cap.**, or *Capac.* alone | 49 → 38 | |
| Autonomia | **Auton.** | 64 | Only with another word: *Auton. stimata* (85) |
| stimato, stimata | **stim.** | 28 | Prefer *(stima)* after a value or name; it costs only 4dp. *(stim.)* only where you measured that *(stima)* doesn't fit |
| Impostazioni | **Impost.** | 75 → 43 | See below |
| Sperimentale | **Sperim.** | 77 → 44 | Only under 64dp |
| Limite di velocità | **Limite vel.** | 97 → 59 | |
| Numero | **N.** | | |
| informazioni | **info** | | *Info e autorizzazioni* (118) on full-width rows. *autorizzazioni* (78.7 at 12sp) doesn't fit a 64dp Quick access line, so those two rungs alone say *Info e permessi* (89) |
| massimo, minimo | **max**, **min** | | |

**Narrow budgets, measured:**

- **A Quick access name has 64dp per line at 12sp, and a single word wider than that breaks mid-word.** These words don't fit, so these rungs avoid them: *Impostazioni* 71.6, *Sperimentale* 74.3, *monopattino* 71.7, *monopattini* 67.7.
- *Settings* on a 62dp tab or 64dp button: **Impost.** On a full-width title: *Impostazioni*. **Debate item:** the full rung is also TalkBack's name, so it speaks an abbreviation here; *Opzioni* (44) is the alternative, but it isn't Android's word.
- *My scooters*: **I miei mezzi** (68; wraps at a space). *Scooter name*: **Nome mezzo** (75). *Mezzo* is NAVEE's manual's own word (*il mezzo*), allowed only on these two keys, `dashboard_model_qa_my_scooters` and `dashboard_model_qa_scooter_name`. The check accepts it everywhere and can't tell, so a judge rejects it on any other key.
- *Battery settings*: full *Impost. batteria* (wraps), short *Batteria* (47). *Ride settings*: full and short *Impost. guida* (79).
- *Experimental controls*: full **Sperim.** (42.4 at 12sp), short *Sperimentale* (77). *sperimentali* (69.3) doesn't fit a 64dp line, so *Comandi sperimentali* (126) can't go on these two rungs. The glossary row accepts both.
- *Display & sound*: full *Display e suono* (wraps), short **Display/suono** (84). *Display e suono* is 91.8, over the 90dp tile.
- *PIN & proximity*: full *PIN e prossimità* (wraps), short **PIN/prossimità** (86.5).
- *Star lights*: *Luci a stella* wraps at a space, but as one line it is 65.1 against 64, and the check measures one line, so it stays a known check error. No correct shorter name exists.
- *Charge schedule* tile: *Ricarica programmata* is 127.7 against 90 (the English is 95.2 and overflows too). No approved abbreviation; on the owner's list, with *Ricarica progr.* (83) as the candidate.
- *Charge done by*, the on/off: **Ricarica entro** (79.5) on every rung. *Charge done by time*, the time picker beside it in the same list: **Orario limite** on both rungs (71 on the one-line tile; wraps as *Orario / limite* at 64dp). Never *ora* in this name: *Ricarica ora* reads as "charge now".

**Never abbreviate:** *Blocca, Sblocca, Bloccato, Sbloccato, Blocco, Sblocco, Non verificato, Sconosciuto, Mai, Sempre, Allarme, PIN*. *Blocc.*, *Sbl.* and *Sbloc.* are not allowed. All of these fit: *Bloccato* 49, *Sbloccato* 56, *Sblocca* 44. If one doesn't fit, write it in full anyway and report it in your notes.

## 6. Safety wording

Use exactly these words. They match `l10n/glossary/it.tsv`; don't use synonyms.

| English | Italian | Rules |
|---|---|---|
| Lock (action) | **Blocca** | Button or action. Infinitive *bloccare* |
| Lock (noun, card title) | **Blocco** | Never *serratura* or *lucchetto* |
| Locked | **Bloccato** | Agrees with *monopattino*. *Bloccata* only with a feminine subject |
| Unlock (action) | **Sblocca** | Infinitive *sbloccare*; noun **Sblocco** |
| Unlocked | **Sbloccato** | Never *aperto* or *libero* |
| Lock state unverified | **Stato di blocco non verificato** | Must never read as confirmed. Keep *non* |
| Lock state unknown | **Stato di blocco sconosciuto** | Must never read as confirmed |
| Unknown (alone) | **Sconosciuto** | *Sconosciuta* with a feminine subject |
| Unverified | **Non verificato** | Never *da verificare*, which reads as a to-do |
| Never | **Mai** | With a verb always *non … mai*: *non si sblocca mai* |
| Always | **Sempre** | *Always on*: **Sempre accesa** for a light |
| Alarm | **Allarme** | Never *avviso* or *sveglia*. Alert is **Avviso**, and they stay distinct |
| Alarm sound | **Suono di allarme** | |
| Stop (a sounding alarm, a recording, a process) | **Interrompi** | Not bare *Ferma*, which also reads as "stationary" |
| Stop the scooter | **Ferma il monopattino** | *Interrompi* is for alarms, recordings and processes only: *Interrompi il monopattino* is nonsense. Write the phrase unbroken, because the check matches it as written: *Prima ferma il monopattino*. *At a stop* is **da fermo** |
| PIN | **PIN** | Masculine, invariable: *il PIN*, *i PIN*. Not *codice* or *password* |
| Handlebar PIN | **PIN del manubrio** | A backup password is **password**; keep them separate |
| PIN unlock | **Sblocco con PIN** | The toggle (`more_pin_fallback`; D3, merged) |
| Owner key | **chiave del proprietario** | A secret. Never *codice*, *password* or *chiavetta*. "Not a password" is *Non è una password*. A shared rider's is *chiave dell'utente autorizzato* |
| Charge limit | **Limite di carica** | Never *soglia*, *tetto* or *massimale* |
| Experimental | **Sperimentale**, plural **sperimentali** | Same in both genders. Badge **LAB**, spoken *Sperimentale* |
| Untested | **Non provato** | |
| Unconfirmed, Not confirmed | **Non confermato** | Both |
| not affiliated with NAVEE | **non affiliata a NAVEE** | In a sentence: *Volt non è affiliata a NAVEE*. Never soften: no *non ufficiale*, *indipendente da*, *non direttamente collegata* |

**Words that look right and mean something else:**

- **Bloccato** also means stuck or frozen. Use it only for the lock. Something stuck is *non risponde*.
- **Blocca / Blocco / Bloccato** and **Sblocca / Sblocco / Sbloccato** differ by one letter. Keep them whole. Don't describe an unlock as removing the lock (*Rimuovi il blocco*); say *Sblocca*. Only use *disattiva il blocco* where the English says turn off.
- **A state must not look like an action.** *Attiva* and *Disattiva* are imperatives (turn on, turn off), and *Attiva* also reads as an adjective. For a state write **Attivato / Disattivato**, agreeing with the thing named. Masculine *attivo / attivi* is allowed in a count of running things (*2 attivi*), because only feminine *Attiva* is ambiguous. Lights and motors use **Acceso / Spento**. Never invert them, and never use *Sì/No* in their place. *Blocco* alone never shows the state "Locked".
- **Mai** without *non* can mean "ever" in a question (*Hai mai…?*). In a label it means never, and in a sentence always pair it with *non*.
- **Carica** is both the noun (charge level) and the imperative. **Scarica** means both discharged and "download": write *Batteria scarica* in full, and never bare *Scarica*.
- **Prova** is both the button "Try" and the noun "test". Use it only for Try; a guided test is **test**.
- **Salvo** means "except" as well as saved. **Sicuro** adds reassurance the English lacks, as does *garantito*.
- **Fine ricarica** reads as a charge that has ended. The feature only plans a start, so the name is **Ricarica entro**. In a sentence it is always in «» and feminine, because bare it reads as a broken phrase: *«Ricarica entro» è disattivata*, *«Ricarica entro» non è stata impostata*. Bare *Ricarica* can read as the imperative "recharge by"; that's acceptable only for a label with its value beside it.
- Keep every negation. The check counts *non*, *mai*, *senza* and *né*; in a safety key a prefix alone doesn't satisfy an English un-word. *Sconosciuto* is on its native list.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Three speeds:** the setting is **Velocità massima**. A limit below it is **Limite di velocità**. The measured value on a trip or ride is **Velocità di punta**. Never swap them.
- **The English now follows the same split (D3, merged):** Maximum speed and Max. speed are the setting (`main_speed_*`, `dashboard_model_tile_top_speed`, `controls_experimental_speed_limit`), and top speed is the measured value (the `readings_maximum_speed_kmh` ladder). Translate them like any other key, with **Velocità massima** for the setting and **Velocità di punta** for the measured value. `rides_tile_max_speed` now says Top speed too: it is the measured value, and no glossary row covers it. It is **Velocità di punta**, or **Vel. di punta** if narrow. Never *Velocità max*.
- **Rides, trips and routes:** a recorded ride is a **corsa** (NAVEE's manual: *ogni corsa*), the screen is **Corse**. Trip has two words: the distance counter's label, since a reset, is **Parziale**, the established vehicle word; a trip as an item or a span is a **tragitto**: *%1$d tragitti*, *Durata del tragitto*, *Riepilogo tragitto*. Never *3 parziali*, *Tempo parziale* (part-time) or *Panoramica parziale* (a partial overview). A path on the map is a **percorso**.
- **Ricarica vs carica:** the act is **ricarica** (*Tempo di ricarica*, *Ricarica programmata*, *In carica* as Android's status). The level is **carica** (*Limite di carica*, *Carica completa*).
- **Alert vs alarm vs notification:** **avviso**, **allarme**, **notifica**. *Notify me* is **Avvisami**. *Prompt sound*, the scooter's beeps, is **Segnali acustici**, singular *segnale acustico* (89.7, tight on a 90dp line). *Avviso* stays exclusive to alerts.
- **Try, test, check, verify:** Try as a verb, only for trying a control, is **Prova**, *provare*; its results use **tentativo**. **An ordinary Try again is Riprova**, Android's own word, never *Prova*. A guided test is **test**, verb *esegui il test*: *Esegui il test della ricarica programmata*, *Test della ricarica programmata in corso*. A check is **controllo**: *controllo di compatibilità*. A test not finished is always *test non terminato*, never *non completato*. **Verificare** is reserved for verify, so *non verificato* keeps one meaning. Don't use the imperative *Testa*, which also reads as "head".
- **Comandi, not controlli:** controls are **comandi**: *Comandi sperimentali*, *Comandi da provare*. *Controllo* is a check, and the read-only compatibility check shares a footer with the experimental section, so *Controlli sperimentali* would read as harmless "experimental checks" about controls that write to the scooter. Keep *controllo* for checks and for named functions: *Controllo della trazione*. Commands sent to the scooter are also *comandi*, which doesn't mislead. *Cruise control* stays in English, as Italian retailers and reviewers write it.
- **Dashboard vs display:** Volt's Home layout is the **Dashboard** (feminine: *la dashboard*). The scooter's own screen is the **display del monopattino**. Never *cruscotto*: Italian riders use it for the scooter's own display. They never swap, so where the English says dashboard and means the physical display, write *display del monopattino*.
- **Settings vs setup:** settings are **Impostazioni**; a one-time setup is **configurazione**.
- **Restore vs set back:** bringing back a backup is **Ripristina**. Undoing a try is **Riporta indietro**, and with a value *Riporta a %1$s*, even where the English says restore or restoration on an Experimental key (`compat_restore_to`, `compat_restoration_*`, `compat_mark_restored`, `compat_dismiss_*`, `compat_note_restore_not_sent`, `ble_restore_failed`). Energy recovery is **Recupero di energia**, unrelated to either.
- **Readback:** **rilettura**, a neutral word, because a readback can also fail to confirm: *Rilettura diversa*. Never a phrase with *conferma* inside it.
- **Rejected:** ask who rejected. The scooter refusing a value is **Rifiutato**, for both *Rejected* and *Refused by scooter*. Data Volt leaves out of an estimate is **Scartato**, agreeing with its noun: `dashboard_rejected` counts rides, so *Scartate*.
- **Articles on commands:** a sentence-like imperative keeps its article: *Registra la corsa*, *Avvia la registrazione*, *Rinomina il monopattino*, *Chiudi l'anteprima*. Noun labels drop it: *Limite di carica*. Named exceptions: *Aggiungi widget*, *Aggiungi scorciatoia*, *Esporta CSV*, *Apri Impostazioni*. Spoken rows (TalkBack) are sentences too: *%1$s, passaggio non ancora completato*.
- **Read it alone.** Read each string without the English and rewrite it if it only makes sense as a translation. Calques that kept surviving: *tienilo privato*, *salvata in modo privato* (say *riservato*), *non scrive mai di nuovo*, *PIN di riserva senza telefono*, *per indicatore*, *nessuna modifica vista* (say *notata*), *quattro cifre decimali* (*decimale* means decimal places: *cifre numeriche*), *Ingresso* for current into the battery (*Immissione*, partner of *Prelievo*).
- **Pair, bind, connect:** Bluetooth pairing is **accoppiare** (Android's word). NAVEE account binding is **associare** (*associa l'account*). A live link is **connettere**.
- **Owner key parts:** the file is the **file di configurazione del proprietario**, shortened to **file del proprietario** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names stay English until the website changes, so quote them in English.
- **Roles:** owner is **proprietario**, shared rider is **utente autorizzato**. The compact role label (`more_role_shared`, English *Shared*) is **Autorizzato** (67.5), never *Condiviso*, which reads as a shared scooter. `compat_evidence_shared` isn't the role.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes, and agree with *recupero*: *Basso, Medio, Alto, Disattivato*. Elsewhere they agree with their own noun.
- **Kick-start:** the setting is **Velocità di avvio**. **Partenza da fermo** is its value 0, not another name for it.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and *Boost* stay in English, as the scooter shows them. **Stima Eco** is a range option. *Eco* in `drive-mode-eco` is the scooter's name too.
- **Lights:** the headlight is **luce anteriore**, the tail light **luce posteriore**, turn signals **indicatori di direzione** (NAVEE's manual). *Frecce* is the everyday word: the glossary row accepts it, but don't swap it in to fit a budget. Never *lampeggianti*.
- **Scooter:** **monopattino**, always. Never *scooter*, which in Italian is a motor scooter.
- **Words shared only by accident; don't unify:** *limit* (charge, speed, a recording's storage); *key* (owner key **chiave**, a JSON key stays **chiave** in its own sense, RSA fingerprint **impronta**); *history* (*cronologia*); *recovery* (*recupero*) vs *restore* (*ripristino*).
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.

**Android's own wording.** A capital belongs to the label named as a label (*Attiva **Debug USB***); in running text with an article it is *il debug USB*. Quote Android's settings as Android writes them: **Debug USB**, **Opzioni sviluppatore**, **Impostazioni** (read from AOSP). **Numero build** and *Informazioni sul telefono* are from memory. Phone makers rename them, so write generally where you can: *nelle impostazioni, apri le informazioni sul telefono*.

**Debate items:** *chiave del proprietario* (might read as a physical key; *codice del proprietario* is the alternative, but PIN must not be *codice*); *Corse* (also means races; *Tragitti* is the alternative, but *tragitto* is now the item word for Trip); *Dashboard* (loan word); *Riporta indietro* (stiff as a bare button and reads as "take it back"; *Torna al precedente* is the alternative for the bare button, keeping *Riporta a %1$s* with a value; *Annulla* is Cancel and *Ripristina* is Restore); *Impost.* on a TalkBack name; *Luci lineari* for Line lights, which has no source; *Odom.* (stiff; *Totale*, 34 at 12sp, as the short and compact rung, paired with *Parziale*, keeping *Odometro* in full; riders' *contachilometri* is too wide at 87 and names km when the unit can be miles); *Autorizzato* on `more_role_shared` (bare, it can read as a state; the status line shows one role at a time, so *Proprietario* isn't always beside it; *Ospite*, 36 at 12sp, is the alternative for this label only); *Modalità batteria scarica* (reads as a state; *Modalità batteria bassa* is the alternative); *Velocità di punta* vs *Velocità massima* (spec sheets use them as near-synonyms; *Velocità raggiunta*, *Vel. raggiunta*, 80.5, says measured outright); *Orario limite* for the Charge done by time picker, which leaves the feature's name out; *Fino al pieno* without an estimate mark (*pieno* leans on fuel; it goes when the English gains `formatted="false"`); *Fino a %1$s* (telegraphic, but no article can follow a placeholder); *Carica per l'intero indicatore* for Charge per gauge; *Pianificazione per una nuova ricarica*; *Carica transitata* for throughput in Ah; *Esegui il test di entrambi* (correct but stiff, because *Testa* and *Prova* are reserved); *Non è stato possibile…* against Android's shorter *Impossibile…* in toasts; *Ricarica progr.* on the charge schedule tile.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Riprova**; *Riprova tra poco*. As a button too, Android's own word. Never *Prova di nuovo*, *Prova ancora* or *Fai un altro tentativo*, which read as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **nessun dato**. Says only that no value came, not that anything is off or fine. Never *non riportato* or *non segnalato* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **indicato** (agrees with its noun); verb **indicare**: *il monopattino indica*, *Indicato dal monopattino*. Alone it hides who indicates, so write *dal monopattino* unless a neighbouring label supplies it: *Ricariche indicate dal monopattino*. A claim, not a measurement. Never *riportato*, which collides with *Riporta*, *segnalato*, which belongs to *Segnala un problema*, *rilevato* or *confermato* |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **registro attività di NAVEE**. Never bare *registro* or *registrazione*: they read as signing up for an account, and *registrazione* is also a recording |
| restore logging | turning that logging back to how it was (usually off) | **ripristina il registro attività**. Means putting that logging back as it was, usually off. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **strumento di Volt**; *strumento* alone once the screen makes clear which. Never *assistente*, which reads as a feature like *Assistenza alla spinta*. The script on the computer is the different **strumento di acquisizione** |
| stock charger | the charger that came with the scooter | **caricabatterie originale**. Never *standard* or *di serie* |
| high-speed charger | a faster charger the rider may own | **caricabatterie rapido**; alone, *Rapido*. Never *ad alta velocità*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **interruzione**. Means no data was recorded, not that power or the ride was cut. Never *buco* |
| elevation | height above sea level on a route | **altitudine**. Never *elevazione*. *Dislivello* is a different thing, the climb between two points |
| current direction | which way battery current flows | **verso della corrente**. Never *direzione*, which reads as a route and collides with *indicatori di direzione* |
| throughput | how much energy has passed through the battery | **energia transitata**. An amount of energy that passed through, not a rate, so never *flusso* or *portata*. The reading in Ah is charge, not energy: **carica transitata** (`readings_capacity_throughput_ah`) |
| battery gauge | the scooter's own battery percentage reading | **indicatore della batteria**; *indicatore* alone only where the screen is about the battery, because *indicatore di direzione* is a turn signal. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **grezzo** (agrees with its head noun, not the nearest one: *Impostazione limite di velocità (grezza)*, *Modalità batteria scarica (grezza)*, *Limite di velocità (grezzo)*); after a placeholder, whose gender is unknown, *%1$s (valore grezzo)*; as a tag **GREZZO**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Ultima lettura**; with a value, *Ultima lettura: %1$s*. The data may be old, so never word it as current. Not *Ultimo stato noto*, which is the last known state |
| add | adding a widget, scooter or item | **aggiungere**, imperative **Aggiungi**: *Aggiungi widget*, *Aggiungi un altro monopattino* |
| enter (typing) | typing a value | **inserire**, imperative **Inserisci**: *Inserisci un nuovo PIN*. Never *digita* or *immetti* |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, feminine: *l'app*, *l'app NAVEE*, *l'app ufficiale*. *applicazione* only when quoting an Android label that writes it |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **connetti il monopattino**, *Connetti prima il monopattino*, *Riconnetti il monopattino*. Avoid *connettiti*, which reads as going online, though the glossary row accepts it. The button stays *Connetti*; plugging in says *collega il caricabatterie* |
| set a value | one verb for setting a value on the scooter | **impostare**, imperative **Imposta**: *Imposta il PIN*, *Imposta %1$s*. Never *fissare*. The noun stays *impostazione*, and setting something back stays *Riporta indietro* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Future with its subject, or with *si*, in dialogs and hints alike: *Questo tragitto verrà eliminato*, *Si aprirà la mappa*. Never *Elimina…* or *Apri…*: *Elimina* means both "it removes" and the command, and reads as the command |
| screen and section titles | noun form or verb form, with one example | Noun form: *Comandi sperimentali*, *Backup e ripristino*. If the title is an action with no good noun, the tu imperative of section 1, matching its button: *Esegui il test della ricarica programmata*, *Segnala un problema*. A title asking for confirmation is *Vuoi …?*: *Vuoi interrompere la configurazione?*. Never the infinitive, except an Android dialog copied as Android writes it |
| scooter warning | a fault the scooter shows; not a battery alert | **avvertenza**: *Risolvi prima l'avvertenza del monopattino*. Never *avviso*, which belongs to alerts |
| cancelled vs interrupted | a state difference: cancelled means nothing was applied; interrupted means it stopped partway | Cancelled is **annullato**: *modifica annullata*, *Annullato prima dell'invio*. Stopped or cut off is **interrotto**. Never swap them on a lock or PIN key |
| applying (a change) | a change in progress; *applicazione* is the software word | **modifica in corso**: *%1$s: modifica in corso…*. Check that a bare verbal noun doesn't collide with another noun |
| whole hour | a time on the hour, not a duration | **orario a ora intera**: *Scegli un orario a ora intera entro 48 ore*. Never *un'ora intera*, which is sixty minutes |
| permissions | what Android grants an app | **autorizzazioni**, as Android's Italian says: *Autorizzazione di accesso alla posizione*. *permessi* only on the two 64dp Quick access rungs |
| keep it private, stored privately | an instruction about a secret file; storage on the phone | **Mantienilo riservato**; *salvata in modo riservato*. *Non condividerlo* is more natural, but a safety key can't add a negation the English lacks |

## 8. Sources consulted

**NAVEE's official Italian: partly found, and only for the manual's and retail vocabulary. The official app's Italian wording was not found:** no public page shows its strings for lock, charge limit, PIN, owner key or experimental features, so those words are Claude's own proposals and the app may differ. No APK was downloaded.

- [NAVEE S40 manual](https://www.trovaprezzi.it/manuali/entry_navee_s40.pdf), NAVEE's multi-language manual hosted by Trovaprezzi; the Italian section was read: *monopattino*, *il mezzo*, *modalità di guida*, *luce anteriore*, *luce posteriore*, *Indicatori di direzione*, *cicalino*, *ogni corsa*, *manubrio*, *Velocità max*, *Autonomia generale*, *Tempo di ricarica*, *caricabatterie*, *pneumatici*, *ca. 6*, *22,2*, *25 °C*. It doesn't cover the app's settings.
- NAVEE's [V40i Pro/V50i Pro Italian manual](https://service.naveetech.com/wp-content/uploads/2024/07/V40i-Pro-V50i-Pro-IT.pdf) exists but couldn't be read (404, then 403); only a search snippet on charging warnings was seen.
- [Unieuro, NAVEE GT3 Max](https://www.unieuro.it/online/Monopattini-elettrici/GT3-MAX-monopattino-elettrico-con-75km-di-autonomia-1000w-IPX5-e-TCS-pidNAEGT3MAX), retail text likely from NAVEE: *Sistema di Recupero dell'Energia*, *Sistema di Controllo della Trazione (TCS)*, *blocco automatico*, *L'app NAVEE*, *Velocità massima*.
- [Tecnoandroid, GT3 Pro review](https://www.tecnoandroid.it/2025/08/26/navee-gt3-pro-leggero-maneggevole-compatto-e-soprattutto-veloce-recensione-1612848/), weaker evidence: *blocco elettronico antifurto*, *regolabile su tre livelli tramite app*, *display*, and the modes *Pedonale, Standard, Sport*, which Volt doesn't use (modes stay English). Search snippets of MediaWorld listings: *Cruise Control*, *Regola il livello di recupero nell'app*.
- Android's own Italian, read from AOSP: [SettingsLib it](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-it/strings.xml) (*Debug USB*, *Opzioni sviluppatore*, *Accoppia*, *Accoppiamento*, *Disconnesso*), [Settings it](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-it/strings.xml) (*Impostazioni*). *Numero build* wasn't in the part that could be read.
- `manualedistruzioni.it` and MediaWorld's own pages returned 403.
- **Judgment calls, from general knowledge and not checked by a native speaker:** quotes, spacing, percent (CLDR's Italian format, from memory), 24-hour clock, *velocità di punta*, *Parziale*, *Odometro*, *Velocità di avvio*, *Partenza da fermo*, *Assistenza alla spinta*, *tragitto*, *comandi*, *segnali acustici*, *Riprova*, every debate item in section 7, every row of the fixed words table, and all the safety wording except *blocco*. The Microsoft Italian style guide wasn't read.
- Width figures: `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`. Glyphs: `fontmetrics.Font.missing` on all four Saira weights.
