# Volt style guide: French (fr)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 10; the three-vendor debate decides later. No native speaker has reviewed it. "Approved" here means "the wording this proposal asks every drafter to use". `values-fr` serves France, Belgium, Switzerland and Canada, so prefer words all of them read the same way.

**The non-breaking space.** In this guide the spaces before `:` `;` `?` `!` `%`, inside `« »`, and between a number and its unit are U+00A0 (no-break space). Copy them. **Never U+202F**: Saira has no glyph for it.

## 1. Register and form of address

- Use **vous**, always, formal and singular in sense: *Connectez le chargeur*, *votre trottinette*. Never tu. The owner set vous.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the infinitive:** *Enregistrer*, *Connecter*, *Verrouiller*. Android's own French does this.
- **Instructions in sentences use the vous imperative:** *Reconnectez-vous pour vérifier*, *Appuyez sur Verrouiller*. Don't mix the two forms in one string.
- **Labels and titles use nouns:** *Limite de recharge*, *Durée de verrouillage*. **A process in progress is a noun plus `…`:** *Connexion…*, *Verrouillage…*, never a gerund.
- Tap on screen: **Appuyez sur**. Never *Cliquez*, *Tapez* or *Touchez*. A physical button: also **Appuyez sur**.
- Use the passive or an impersonal form for what Volt or the scooter did: *Lecture impossible*, *Impossible de lire la valeur*. Past events take the passé composé: *A fonctionné*, *Signal perdu*.
- Role labels use the generic masculine (*propriétaire*, *utilisateur autorisé*). No midpoint forms (*utilisateur·rice*): they are wide and read badly on a screen reader.
- Volt is feminine, as *l’application*: *Volt n’est pas affiliée à NAVEE*.

## 2. Punctuation and quotes

- **U+00A0 goes before `:` `;` `?` `!` and `%`:** *Batterie : %1$s*, *Continuer ?*, *85 %*. Also around a placeholder: `%1$s : %2$s`, `%1$s %%`. The space after the mark is a normal space.
- **Quotes: `« … »`** with U+00A0 inside both marks: *« Finir la recharge à »*. Inner quotes are `“…”`, with no space. Never straight quotes.
- A final period goes outside closing quotes and parentheses. No space inside parentheses.
- **Apostrophe: always `’` (U+2019), never `'`:** *d’énergie*, *l’application*. Glossary forms use `’`, and the term check matches it literally, so a straight apostrophe makes a safety key fail. Saira has `’`.
- Keep `·`, `•`, `–` and `…` from the English. `…` is one character (U+2026) with no space before it. Numeric ranges keep the en dash with no spaces (`fmt_range`).
- Lists have no serial comma before *et* or *ou*.
- A dash in running text is a spaced en dash with U+00A0 before it. Prefer a colon or two sentences.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.
- Capitals keep accents and cedillas, always: *À propos*, *État*, *Écran*, *ÉCRAN*. Saira has them, along with `œ`.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere:** buttons (*Tout remplacer*), labels (*Limite de recharge*), titles and section headers (*Commandes expérimentales*). Never English Title Case, even when the English has it: *Walk Assist* → *Assistance à la marche*, *Low Battery Mode* → *Mode batterie faible*.
- Feature names are lower case mid-sentence: *activez la recharge programmée*.
- After a colon, use lower case unless a name follows: *Dernier état connu : verrouillée*.
- Days, months and languages are lower case.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS and the drive modes WALK, ECO, DRIVE, SPORT. Say *mode SPORT*.

## 4. Numbers, dates and units

- Decimal comma; a U+00A0 groups thousands: **1 234,5**. Volt's `Fmt` formats live numbers for the phone's region and locale, so never rewrite a placeholder's number or add separators around one.
- **Percent:** U+00A0 before the sign. `fmt_percent` is `%1$s %%` (*85 %*). The same goes for literal text and for `formatted="false"` text (`100 %`).
- **Number and unit:** U+00A0 between them: `fmt_quantity` is `%1$s %2$s`. *25 km/h*, *36 V*, *25 °C*, *2 h 5 min*. `fmt_range` is `%1$s–%2$s %3$s` (*10–20 km*). `fmt_dual` stays `%1$s / %2$s`.
- **Unit symbols stay** and take no plural or period: `km/h`, `km`, `mi`, `mph`, `V`, `A`, `W`, `Wh`, `Ah`, `°C`, `°F`, `h`, `min`, `s`. Never *Km/h* or *kmh*. Unit words are translated: *kilomètres*, *miles*, *minutes*.
- Numbers in the English stay as digits. Don't write them out or change them.
- **Dates and times** come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 mars* (*1er mars* for the first). A time in prose reads *à 14 h 30*, on a chip *14:30*. Keep a placeholder's time as it comes. No *AM/PM* of your own.
- **Plurals, `one`, `many`, `other`.** French `one` covers **0 and 1**, and fractions below 2 (*0 trajet*, *1 trajet*, *1,5 kilomètre*), so `one` must keep the count placeholder. It may never say *Un trajet*.
- **`many` is `other`,** unless a noun follows the number directly: `many` applies only to round millions and larger, and French then needs *de*. `other` *%1$d trajets*, `many` *%1$d de trajets* (*1 000 000 de trajets*). A symbol takes no *de*: *1 000 000 km*. The placeholder stays the same.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. French runs wider than English (Energy recovery → *Récupération d’énergie*, 134dp at 12.5sp). Try these in order:

1. **Drop the words the screen already supplies:** *Réglages de conduite* → *Conduite*, *Tension de la batterie* → *Tension*, *Durée du parcours* → *Durée*.
2. **Use a shorter real word:** *Réglages* not *Paramètres*, *Feux* for lights, *Infos* for *Informations*, *Appli* for *Application*, *Stats* for *Statistiques*, *Verrou* for the lock as a card or tile title.
3. **Use an abbreviation from this table.** Nothing else: no invented cuts such as *Verr.*, *Déverr.*, *Conf.*, *Exp.*, *Rech.*, *est.* (it reads as the verb *est*)

| Full | Abbreviation | Where | Do not write |
|---|---|---|---|
| Vitesse | **Vit.** | *Vit. max.* (50.4), *Vit. moy.* (51.1), *Vit. de pointe* (76.6) | *V.*, *Vts* |
| maximale, maximum | **max.** | *Vitesse max.* (73.6) | *maxi* |
| minimale, minimum | **min.** | Never next to a duration, where *min* is minutes | |
| moyenne | **moy.** | *Vit. moy.* | |
| Température | **Temp.** (34.6) | *Temp. batterie* (83.3) | *Tº* |
| batterie | **batt.** | *Temp. batt.* (64.4) | *bat.* |
| Capacité | **Cap.** | *Cap. estimée* (73.7) | |
| environ | **env.** | Before a number: *env. 25 km* | |
| Puissance | **Puiss.** (34.4) | Only where *Puissance* (58.6) doesn't fit | |
| Programme | **Prog.** | *Prog. recharge* (84.4) | |
| Préférences | **Préf.** | *Préf. appli* (56.7 at 12sp) | |
| proximité | **prox.** | *PIN, prox.* (53.9 at 12sp) | |
| trottinettes | **trott.** | Only *Mes trott.* (54.2 at 12sp) | Anywhere else |
| Installer | **Install.** | Only `owner_shizuku_install` | Anywhere else |
| Expérimental | **Expérim.** | Only `dashboard_model_qa_experimental` (48.8 at 12sp), where *Expérimental* doesn't fit | Anywhere else; *Exp.*; *Labo* |

An abbreviation ends in a period, which counts toward the width, unless the word stays whole in the cut (*Infos*, *Appli*, *Stats*, *Labo*). A two-word label may wrap at a space where the English also wraps. **"Odo." is not French:** the odometer rungs are real words (table below).

**Real ladders.** Widths in dp at 12.5sp, measured with `res/font/saira_medium.ttf`:

| English (full / short / compact) | Full | `_short` | `_compact` |
|---|---|---|---|
| Total odometer / Odometer / Odo. | Kilométrage total (100.5) | Kilométrage (70.8) | Total (28.3) |
| Battery voltage / same / Voltage | Tension de la batterie (124.2) | same | Tension (44.8) |
| Battery temp. / Temp. | Température de la batterie (153.3) | Temp. batterie (83.3) | Temp. (34.6) |
| Est. capacity (health) / Est. capacity | Capacité estimée (état) (132.6) | Cap. estimée (état) (108.0) | Cap. estimée (73.7) |
| Trip top speed / Top speed | Vitesse de pointe du parcours | Vit. de pointe (76.6) | Pointe (37.7) |
| Trip avg. speed / Avg. speed | Vitesse moyenne du parcours | Vit. moy. du parcours (122.4) | Vit. moy. (51.1) |
| Trip distance / Distance | Distance du parcours (121.1) | same | Distance |
| Est. range / Range | Autonomie estimée (112.7) | same | Autonomie (63.7) |
| Maximum speed, the setting | Vitesse maximale (102.1) | Vitesse max. (73.6) | Vit. max. (50.4) |
| Energy recovery | Récupération d’énergie (134.3) | Récupération (77.2) | icon |
| Compatibility check | Vérification de compatibilité (161.9) | Compatibilité (76.5) | Vérification (66.5) |
| Experimental controls; `dashboard_model_qa_experimental` is 64dp and its `_short` 90dp | Commandes expérimentales (164.1) | `_short`: Expérimental (77.1) | The 64dp key: Expérim. (48.8 at 12sp) |
| Unverified here / Unverified | Non vérifié ici (78.8) | Non vérifié (63.5) | Non vérifié |
| Worked here / Worked | A fonctionné ici (89.2) | A fonctionné (73.8) | |
| Charge done by; on the 64dp and 90dp shortcut keys use the narrow form | Finir la recharge à (103.3) | Finir à (34.5 at 12sp, 36.0 at 12.5sp) | |
| Charge done by time | Heure de fin de recharge (142.4) | Heure de fin (71.1) | Heure fin (51.6 at 12sp) |

**Hard budgets in the catalog at the time of writing:**

- `onboarding_welcome_tile_*`, 62dp at 12.5sp: *Verrouiller* 60.6 (tight; *Verrouillage* is 70.3 and doesn't fit), *Réglages* 53.7 (*Paramètres* is 67.4), *Batterie* 46.8, *Trajets* 40.1.
- `dashboard_model_qa_*`, 64dp at 12sp, one line: *Feux* 27.1, *Réglages* 51.6, *En direct* 48.7, *Trajets* 38.5, *Conduite* 49.4, *Batterie* 44.9, *Bandes* 41.8, *Étoiles* 37.6, *Écran, son* 57.1, *PIN, prox.* 53.9, *Nom* 26.1, *Préf. appli* 56.7, *Mes trott.* 54.2 (*Trottinettes* is 65.9), *Expérim.* 48.8, *Données* 48.8, *À propos* 49.5, *Finir à* 34.5, *Heure fin* 51.6.
- `dashboard_model_tile_*`, 90dp at 12.5sp: *Vitesse max.* 73.6, *Prog. recharge* 84.4. The `_short` rungs of `dashboard_model_qa_*` are 90dp at 12.5sp too: *Expérimental* 77.1, *Finir à* 36.0, *Heure de fin* 71.1.
- `more_lab_badge`, 40dp at 10.5sp: *LABO* 27.4. *LABO* is for the badge keys only.
- `owner_shizuku_install`, 48dp at 14sp: *Installer* is 52.9, over budget. Use *Install.*, measured below.

**Never abbreviate:** *Verrouiller, Déverrouiller, Verrouillée, Déverrouillée, Non vérifié, Inconnu, Jamais, Toujours, Alarme, PIN*. *Verr.* and *Déverr.* are not allowed. If one of these doesn't fit, report it in your notes rather than shortening it. Falling back to English is the safe outcome.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | French | Rules |
|---|---|---|
| Lock (action) | **Verrouiller** | Sentences: *Verrouillez la trottinette* |
| Lock (noun, card title) | **Verrouillage**; **Verrou** on a narrow title | Never *Blocage*, *Antivol* or *Cadenas* |
| Locked | **Verrouillée** | Agrees with *la trottinette*. *Verrouillé* only with a masculine subject (*le moteur*) |
| Unlock (action) | **Déverrouiller** | Noun: **Déverrouillage** |
| Unlocked | **Déverrouillée** | Never *ouverte*, *libre* or *débloquée* |
| Lock state unverified | **État de verrouillage non vérifié** | Must never read as confirmed. Keep *non* |
| Lock state unknown | **État de verrouillage inconnu** | Must never read as confirmed |
| Unknown (alone) | **Inconnu** | *Inconnue* with a feminine subject |
| Unverified | **Non vérifié** | *Non vérifiée* with a feminine subject. Never *Invérifié* or *À vérifier* |
| Never | **Jamais** | In a sentence *ne … jamais*, and keep the *ne* |
| Always | **Toujours** | See the trap below. *Always on* is **Allumé en permanence** (132.7), agreed with its noun, and **En permanence** (89.7) where narrow. Never *Toujours allumé*, which can read as "still on" |
| Alarm | **Alarme** | Never *alerte*: alert is **Alerte**, and they stay distinct |
| Alarm sound | **Son d’alarme** | |
| Stop (a sounding alarm) | **Arrêter** | *Arrêter l’alarme* |
| PIN | **code PIN**; **PIN** alone on a narrow label | Masculine, no plural change: *le code PIN*, *les codes PIN*. A password is **mot de passe**; keep them separate |
| Handlebar PIN | **code PIN du guidon** | Toggle: **Déverrouillage par code PIN** |
| Owner key | **clé de propriétaire** | A secret. *Clé* is the usual word for a digital key too. "Not a password" is *Ce n’est pas un mot de passe* |
| Charge limit | **Limite de recharge** | Never *Limite de charge*, which reads as a load limit in kilograms (see section 7). Never *plafond* |
| Experimental | **Expérimental**, *expérimentale*, *expérimentaux*, *expérimentales* | The section is **Commandes expérimentales**. *Expérim.* only on the 64dp shortcut |
| Untested | **Non testé** | *Non testée* with a feminine subject. The card title *Untested controls* is **Commandes non testées** until D3 |
| Unconfirmed | **Sans confirmation** | Acknowledged but not kept or read back. Always a status beside its control, never "done without asking". **Debate item:** *Non maintenu*, or *Non confirmé (non relu)*; *Non confirmé* alone is taken by *Not confirmed* |
| Not confirmed | **Non confirmé** | Not read back |
| not affiliated with NAVEE | **non affiliée à NAVEE** | Never soften it: no *non officielle*, *indépendante de*, *sans lien direct*. In a sentence about Volt: *Volt n’est pas affiliée à NAVEE* |

**Words that look right and aren't:**

- ***Toujours* also means "still".** *Toujours verrouillée* can read as "still locked" rather than "always locked". Where both readings are possible, write *en permanence* for always (*Allumé en permanence*). Write *encore* for still. Never use *toujours* for still in a state message.
- ***plus* means "more" and "no more".** *Plus de charge* is ambiguous. Keep the *ne* (*ne charge plus*), or write it another way.
- ***Bloquée*** means stuck or jammed, not locked. ***Débloquée*** isn't unlocked. Use only *verrouillée* and *déverrouillée*.
- ***Fermée*** and ***ouverte*** are not lock states. *Ouvrir* and *Annuler* next to *le verrouillage* read as removing the lock.
- ***Sécurisée*** adds reassurance the English lacks. Don't add it, and don't add warnings the English doesn't have.
- ***Personne*** is both "nobody" and "person". Avoid it in safety text.

**Rules:**

- *Verrouillée* and *Déverrouillée* differ only by *Dé-*. Keep both whole and never truncate them. In sentences keep the full word so the difference stays visible.
- Don't describe an unlock as removing the lock (*Retirer le verrouillage*, *Désactiver le verrouillage*); say *Déverrouiller*. Use *désactiver le verrouillage* only where the English itself says turn off.
- Keep every negation in full, with *ne* and its partner: *ne … pas*, *ne … jamais*, *ne … plus*. *sans* and *non* count as negation words for the checks; a prefix alone counts only for the listed words such as *inconnu*.
- **On and off:** settings use **Activé** and **Désactivé**, lights and motors **Allumé** and **Éteint**. Agree them with the thing named. Never invert them, and never use *Oui/Non* in their place.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **e-scooter:** **trottinette**, feminine, as NAVEE's French material says. Never *scooter*, which in French is a moped, and never *patinette*. Agreement: *la trottinette est verrouillée*, *trottinette simulée*.
- **Three speeds:** the setting is **Vitesse maximale** (NAVEE's word). A limit below it is **Limite de vitesse**. The measured value on a trip or ride is **Vitesse de pointe**. Never swap them.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. So does `rides_tile_max_speed`: its English says Max speed for a value measured on a ride, D3 makes it Top speed, and its French is then **Vitesse de pointe**, never *Vitesse max.*. The glossary rows `top-speed` and `maximum-speed` already carry D3's meanings, so following them now would call the setting *Vitesse de pointe* and the measured value *Vitesse maximale*. Flag these keys in your notes; a judge answers fallback.
- **Charge and recharge:** the act and the session are **recharge** (*Recharge programmée*, *Limite de recharge*, *Début de recharge*); the level in the battery is **charge** (*Niveau de charge*, and the compact reading *Charge*). The device is the **chargeur**. This keeps every limit from reading as a weight. **Debate item:** NAVEE's store says *Temps de charge*.
- **Charge done by:** a finish-by deadline. The feature name is **Finir la recharge à**, its time setting **Heure de fin de recharge**, and the narrow label **Finir à**. The feature only plans a start, so never word it as a finished charge (*Recharge terminée*, *Recharge finie à*), and never as the bare event *Fin de recharge*, which on a tile can read as "stop charging". In a sentence, quote the name: *La fonction « Finir la recharge à » est désactivée*. **Debate item:** *Fin de recharge* (89.1) fits a 90dp tile and pairs with *Début de recharge*, but has no deadline sense; if the debate keeps it, keep the quoted-name rule and never use it as a bare button label.
- **Rides vs trips:** a recorded ride is a **trajet**. *Trip* (the distance since a reset) is **parcours**, invariable. A path on the map is a **tracé**. Never *voyage*. **Debate item:** *trajet* and *parcours* are near-synonyms, so a rider can't infer the distance since a reset; *Partiel* (*Distance partielle*, *Vitesse de pointe partielle*) is the trip-meter word.
- **Try vs test:** *Try* is **Essayer**, its result **essai**. *Try again* is **Réessayer**, and in a sentence *Réessayez*. A guided test is **test**, **tester**. A compatibility check is **vérification de compatibilité**. *Test scheduled charging* is **Tester la recharge programmée**.
- **Dashboard vs display:** Volt's Home layout is the **Tableau de bord**. The scooter's own screen is the **écran de la trottinette**. They never swap, even though French calls a car's instruments a *tableau de bord*.
- **Settings vs setup:** settings are **Réglages**, a one-time setup is **configuration**. Android's own settings are **Paramètres** (section 9).
- **Restore vs set back:** bringing back a backup is **Restaurer**. Undoing a try is **Rétablir**. Energy recovery is **Récupération d’énergie**, unrelated to either.
- **Readback:** **relecture**, a neutral word, because a readback can also fail to confirm: *Relecture différente*, *Réglage confirmé par relecture*. Never a phrase with *confirmation* inside it.
- **Refused vs rejected:** *Refused by scooter* is **Refusé par la trottinette**, *Rejected* is **Rejeté**. Agree with the noun: *valeur rejetée*.
- **Pair vs bind vs connect:** Bluetooth pairing is **associer**, as Android writes it. NAVEE account binding is **lier** (*lier le compte*). A live link is **connecter**.
- **Owner key parts:** the file is the **fichier de configuration du propriétaire**, shortened to **fichier de propriétaire** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Roles:** owner is **propriétaire**, shared rider is **utilisateur autorisé**. The compact role label (`more_role_shared`, English *Shared*) is **Autorisé** (49.1), for that key only and never as a state, never *Partagé*, which reads as a shared scooter. **Debate item:** *Invité* (32.7), since bare *Autorisé* reads as "allowed". `compat_evidence_shared` and `compat_report_privacy` are not the role: they use *partagé*. A shared rider's key is **clé d’utilisateur autorisé**.
- **Levels:** Energy recovery levels agree with *récupération*: *Désactivée, Faible, Moyenne, Élevée*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Estimation Eco** is a range option, translated.
- **Drive modes:** WALK, ECO, DRIVE, SPORT stay as the scooter shows them (`drive_mode_names` is english), even though NAVEE's store says *mode piéton*, *mode standard*, *mode sport*. A drive mode in general is a **mode de conduite**. **Walk Assist** is **Assistance à la marche**, and **Power Assist** is **Assistance motrice**.
- **Kick-start speed vs standing start:** the setting is **Vitesse de démarrage**, and value 0 of it is **Démarrage à l’arrêt**.
- **Parked:** the meaning is stationary, so **à l’arrêt**. Not *garée*. *À l’arrêt* can also mean switched off, so where power is in question use **immobile**: `onboarding_prepare_parked` is *Trottinette immobile et allumée*.
- **Readings and measuring:** telemetry values are **relevés** (*Historique des relevés*). Watching the charger is **Mesurer**, and its state is *Mesure en cours*.
- **Parts:** *guidon* (handlebar), *phare* (headlight, NAVEE's word), *feu arrière*, *feu stop*, *clignotant*, *batterie* (never *pile* or *accu*), *pneu*, *frein*, *accélérateur* (throttle), *béquille*, *micrologiciel* (firmware, as Android writes it). The range is **autonomie**.
- **Quick settings:** the Dashboard's shortcut row and widget (*Quick access*, *Quick settings*) are **Accès rapide**. Never *Réglages rapides*: Settings is itself one of the shortcuts.
- **Experimental LAB badge:** **LABO**, a French word that needs no period, on the badge keys only (`more_lab_badge`, `rides_lab_badge`). D3 lets a language use its own short word. TalkBack speaks **Expérimental**. A shortcut names the section instead: **Expérimental**, or *Expérim.* at 64dp. **Debate item:** Spanish keeps LAB.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Odometer:** **Kilométrage total**, **Kilométrage**, **Total**. *Compteur kilométrique* is accepted in running text. Never bare *Compteur*, which riders use for the speedometer or the display unit. **Debate item:** *Kilométrage* and *Compteur kilométrique* say kilometers even when Volt shows miles. *Odomètre* (57.7) avoids that, but it reads as rare or Canadian in France.
- **Regional words:** say *téléphone*, never *portable* or *cellulaire*; say *e-mail*, the form Android uses for France, where a Canadian reader would say *courriel*.

## 8. Grammar traps

- **Gender drives agreement, and the placeholder hides it.** *la trottinette, la batterie, l’application, la clé, la limite, la recharge, la récupération* are feminine; *le code PIN, le moteur, le feu, le phare, le tableau de bord, le parcours, le trajet* are masculine. A state alone with no noun agrees with *trottinette* where the scooter is the subject (*Verrouillée*), and otherwise with the noun on screen.
- Prefer wording with no article before `%1$s`: *Trottinette : %1$s*, not *la %1$s*. Elision can't be predicted either, so never write *de %1$s* or *le %1$s* before a name. Use *pour : %1$s* or a colon.
- Elide before a vowel or mute *h*: *l’alarme*, *d’énergie*, *l’écran*, *n’est pas*. A placeholder or a symbol doesn't elide.
- Contract *à le* and *de le*: *au guidon*, *du téléphone*. *de la* and *de l’* stay.
- The *one* item also covers 0, so write the same sentence for 0 and 1 without *un* or *une* in place of the number.

## 9. Android's own wording

Quote Android's settings as Android writes them: **Débogage USB**, **Options pour les développeurs**, **Numéro de build**, *Paramètres › À propos du téléphone*. Phone makers rename and move these, so write generally where you can: *dans les paramètres, ouvrez les informations du téléphone*.

## 10. Sources consulted

**NAVEE's official French: partly found, and only for marketing words.** The official app's French wording was **not found**: no public page shows its strings for lock, charge limit, PIN, owner key or experimental features. Those words are Claude's own proposals, and the official app may differ. No APK was downloaded. Read through a fetch tool's summaries unless noted.

- [NAVEE France store, GT5 Pro](https://naveetech.fr/products/navee-trottinette-electrique-gt5-pro): *fonction de verrouillage*, *déverrouillage de proximité*, *application intelligente NAVEE*, *mode piéton*, *mode standard*, *mode sport*, *récupération d’énergie*, *Système de contrôle de traction*, *Phare à détection automatique*, *Clignotants*, *Temps de charge*, *Écran LED*.
- [NAVEE press release, France, March 2025](https://media.naveetech.com/wp-content/uploads/2025/03/Press-Release-Draft-FR-.pdf), read directly, marked a draft: *trottinettes électriques*, *verrouillage/déverrouillage par proximité*, *vitesse maximale*, *autonomie*, *système de traction TCS*.
- [NAVEE product leaflet (fr)](https://service.naveetech.com/wp-content/uploads/2025/03/Product-Leaflet-FR.pdf): seen as a search snippet only (*Déverrouillage de Proximité*); fetching it returned 404. So did the [French catalogue](https://naveetech.com/wp-content/uploads/2024/08/Product-catalogue-FR.pdf) and the [S65 EU manual](https://service.naveetech.com/wp-content/uploads/2024/07/NAVEE-S65-Instruction-Manual-EU-version.pdf).
- NAVEE manuals on manualslib ([S65](https://manualslib.fr/manual/614015/Navee-S65.html?page=36), [S65C](https://manualslib.fr/manual/686649/Navee-S65C.html?page=8)): the pages returned had English bodies with French site navigation, so they gave nothing.
- Android's own French, from AOSP: [SettingsLib fr](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-fr/strings.xml) (*Débogage USB*, *Options pour les développeurs*, *Associer*); [Settings fr](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-fr/strings.xml) (*Paramètres*). *Numéro de build* and *À propos du téléphone* come from search snippets of Google's [Android developer docs](https://developer.android.com/studio/debug/dev-options) and [Pixel help](https://support.google.com/pixelphone/answer/6398243?hl=fr). *micrologiciel* is from memory.
- Retailer and press usage, weaker evidence: [Wee-Bot GT3](https://www.wee-bot.com/products/trottinette-electrique-navee-gt3) (*système de contrôle de traction (TCS)*, *Temps de recharge*, *Vitesse maximale*, *Guidon*); [Journal du Geek, N65](https://www.journaldugeek.com/test/test-de-la-trottinette-electrique-navee-n65-un-poids-lourd-qui-passe-presque-partout/) (*verrouiller la trottinette*, *régulateur de vitesse*, *feu arrière*, *mode de conduite*, *mode piéton*, *un code à 4 chiffres*); [Fnac L’Éclaireur](https://leclaireur.fnac.com/test/629140-test-labo-de-la-navee-gt3maxn-une-trottinette-presque-parfaite/) (*Vitesse max mesurée*, *Clignotants*, *Feu stop*). A search on charge limits showed *charge* used for the rider's weight, which led to the *recharge* rule; that rule is a judgment call.
- **Typography:** the U+00A0 rules follow the plan and `l10n/style/README.md`. French spacing and capitalization follow memory of the *Lexique des règles typographiques en usage à l’Imprimerie nationale*, which wasn't read here. A search found only general support ([Purnelle syllabus](https://orbi.uliege.be/bitstream/2268/298446/1/R%C3%A8glesTypographiquesSyllabus-Purnelle.pdf), [OQLF](https://vitrinelinguistique.oqlf.gouv.qc.ca/fiche-gdt/fiche/8361819/espace)). Buttons in the infinitive and *Appuyez sur* follow memory of Microsoft's French style guide and Android, also not read here.
- **Judgment calls, with no source:** *Vitesse de pointe*, *parcours*, *tracé*, *relevés*, *relecture*, *Assistance motrice*, *Contrôle actif de survitesse*, *Commandes expérimentales*, *utilisateur autorisé*, *clé de propriétaire*, *Finir la recharge à*, *Kilométrage*, *immobile*, *Suit le phare*, the abbreviation table. No French term list for electric scooters was found.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `res/font/saira_medium.ttf`. Glyphs: every French letter and mark used here is in all four Saira weights; U+202F is in none.
