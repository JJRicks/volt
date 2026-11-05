# Volt style guide: Spanish (es)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 10; the three-vendor debate decides later. No native speaker has reviewed it. `values-es` serves Spain and all of Latin America, so every choice aims at one text both can read.

Rule of thumb: pick the word understood everywhere, and if there is none, rephrase so no regional word is needed. Section 8 records each regional choice.

## 1. Register and address

- Use **tú**, singular, always: *Conecta el cargador*, *tu patinete*. Never usted, never vos or voseo forms (*conectá*, *tenés*), never vosotros. Tú is understood where vos is spoken.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the infinitive:** *Guardar*, *Conectar*, *Bloquear*. **Instructions in sentences use the tú imperative:** *Vuelve a conectar para verificar*. Don't mix the two in one string.
- Prefer simple past for finished events: *Funcionó*, *Se perdió la señal*. It reads naturally in both regions; *ha funcionado* sounds Spain-only to many.
- Avoid gendering the rider where a rewrite is easy. Role labels use the generic masculine (*propietario*).
- Use the passive *se* or the state itself for what Volt or the scooter did: *No se pudo leer*.

## 2. Punctuation and quotes

- Opening **¿** and **¡** are required. Saira has them.
- Quotes: **«…»** first, **“…”** inside. Never straight quotes.
- A sentence that names a button, tab or screen puts its exact label in «…»: *Toca «Permitir»*, *abre «Copia de seguridad y restauración»*. A feature mentioned in passing stays unquoted (section 3).
- Sentence-final period goes outside closing quotes and parentheses.
- Lists use commas with no serial comma before *y* or *o*. *y* becomes *e* before an *i-/hi-* sound, *o* becomes *u* before *o-/ho-*.
- Keep the English's `·` separators and `…` as written. A verb in progress keeps `…`: *Conectando…*
- A colon is followed by lower case unless a name follows: *Último conocido: bloqueado*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: *Límite de carga*, *Controles experimentales*, *Modo de batería baja*. Never English Title Case, even when the English has it (*Walk Assist* → *Asistencia al caminar*).
- Feature names are lower case mid-sentence: *activa la carga programada*.
- Days and months are lower case. Languages are lower case in sentences.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS. Drive modes and their level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *modo Sport* where the English says Sport.
- Capital letters keep their accents, including where Volt upper-cases text: *BATERÍA*.

## 4. Numbers, dates and units

- Decimal comma in text you write. Four-digit numbers take no separator (*1234,5*); from five digits a period groups thousands (*12.345,6*). Volt's `Fmt` formats live numbers for the phone's region (Mexico and Central America get `1,234.5`), so its output can differ from this; never rewrite a placeholder's number or add separators around one.
- Percent always takes a no-break space (U+00A0), never a plain space, between a number or placeholder and %: `%1$s %%` gives *85 %*. Otherwise a narrow label can wrap the sign onto its own line. Saira has U+00A0 and lacks U+202F, so don't use U+202F. The rule covers `formatted="false"` text too, whose literal is `100 %`.
- Numbers in the English stay in the Spanish as digits. Don't write them out or change them.
- Unit symbols stay and take the same no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *kilómetros*, *millas*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 de marzo*. Don't write *a.m./p.m.* yourself.
- Plurals: `one` covers exactly 1. Copy `other` into `many` unless the grammar needs *de* after a bare large number: `1 millón de viajes`. A placeholder form stays the same in both.

## 5. Shortening labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Try these in order:

1. Drop words the screen already supplies: *Ajustes de batería* → *Batería* on a Settings screen.
2. Use a shorter common word: *Temperatura* → *Temp.*
3. Use an accepted abbreviation from this table. Nothing else: no invented cuts such as *Conf.*, *Exp.*, *Bat. baja*.

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Velocidad máxima | **Vel. máxima**, then **Vel. máx.** | 105 → 70 → 52 | The setting. Fits the 90dp tile |
| Velocidad punta | **Vel. punta** | 93 → 58 | The measured value. Never *Vel. máx.* |
| Velocidad media | **Vel. media** | 95 → 60 | |
| Odómetro | **Odóm.** | 58 → 37 | The accent stays |
| Temperatura | **Temp.** | 35 | |
| Capacidad | **Capac.** | 60 → 38 | |
| Estimado, estimada | **est.** | | *Autonomía est.* |
| aproximadamente | **aprox.** | | |
| máximo, mínimo | **máx.**, **mín.** | | |
| información | **info** | | A short word, no period: *Info y permisos* is 89.6dp, under the 90dp tile |

Measured words that fit as they are: *Bloqueo* 48, *Ajustes* 43, *Batería* 42, *Viajes* 35 (all under the 62dp tab budget); *Mis patinetes* 78, *Conducción* 66 for Ride settings (under 90dp; 63.4 at 12sp, under the 64dp shortcut budget). *Ajustes de viaje* is 90.9dp, so it only fits wrapped at a space. *Recorridos* is 62.0dp, one reason *Viajes* was chosen. *Experimentales* is 90.3dp, so on the 90dp tile use *Experimental* (77) on one line or *Controles experimentales* wrapped at the space. No form fits the 64dp shortcut: *experimentales* is 86dp and *Experimental* 74dp, so it stays *Controles experimentales* and is reported as a design problem. *Luces de estrella* is 96.6dp on the 90dp tile, so that rung is *Estrella* unless the owner allows the wrap.

An abbreviation ends in a period, and the period counts toward the width. A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Bloquear, Desbloquear, Bloqueado, Desbloqueado, Sin verificar, Desconocido, Nunca, Siempre, Alarma, PIN*. *Bloq.* and *Desbl.* are not allowed. If one of these doesn't fit, write the whole word and report it in your notes rather than shortening it.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Spanish | Rules |
|---|---|---|
| Lock (action) | **Bloquear** | Button or action |
| Lock (noun, card title) | **Bloqueo** | Never *Cerradura* or *Candado* |
| Locked | **Bloqueado** | Agrees with *patinete*. *Bloqueada* only with a feminine subject |
| Unlock (action) | **Desbloquear** | Noun: **Desbloqueo** |
| Unlocked | **Desbloqueado** | Never *abierto* or *libre* |
| Lock state unverified | **Estado de bloqueo sin verificar** | Must never read as confirmed. Keep *sin* |
| Lock state unknown | **Estado de bloqueo desconocido** | Must never read as confirmed |
| Unknown (alone) | **Desconocido** | *Desconocida* with a feminine subject |
| Unverified | **Sin verificar** | Takes no gender |
| Never | **Nunca** | Never *no* alone where the English says never |
| Always | **Siempre** | *Always on*: **Siempre encendida** for a light |
| Alarm | **Alarma** | Never *alerta*: alert is **Alerta**, and they stay distinct |
| Alarm sound | **Sonido de alarma** | |
| Stop (a sounding alarm) | **Detener** | |
| PIN | **PIN** | Masculine, no plural change: *el PIN*, *los PIN*. Not *código* or *contraseña* |
| Handlebar PIN | **PIN del patinete** | See 8 for why not *manillar*. A password is **contraseña**; keep them separate |
| Owner key | **clave de propietario** | A secret. Never *llave*, which reads as a physical key. "Not a password" is *No es una contraseña* |
| Charge limit | **Límite de carga** | Never *techo* or *tope* |
| Experimental | **Experimental**, plural **experimentales** | |
| Untested | **Sin probar** | |
| Unconfirmed, Not confirmed | **Sin confirmar**, **No confirmado** | |
| not affiliated with NAVEE | **sin afiliación con NAVEE** | Never soften it: no *no oficial*, *independiente de*, *no asociado directamente*. In a sentence about Volt: *Volt no está afiliada a NAVEE* |

- *Bloqueado* and *Desbloqueado* differ only by *Des-*. Keep both whole, never truncate, and in sentences keep the full word so the difference stays visible.
- Don't describe an unlock as removing the lock (*Quitar el bloqueo*); say *Desbloquear*. Only use *desactivar el bloqueo* where the English itself says turn off.
- Keep every negation. *sin* and *no* count as negation words for the checks; a prefix alone (*inactivo*) only counts for the listed native words.
- On and off: settings use **Activado** and **Desactivado**, lights and motors use **Encendido** and **Apagado**. Agree in gender with the thing named. Never invert them, never use *Sí/No* in their place.
- Don't add reassurance the English lacks (*seguro*, *garantizado*), and don't add warnings it doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Three speeds:** the setting is **Velocidad máxima**. A limit below it is **Límite de velocidad**. The measured value on a trip or ride is **Velocidad punta**. Never swap them, and don't call a limit *tope*: *tope* reads as a setting.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback.
- **Rides vs trips:** a recorded ride is a **viaje**. *Trip* (the distance since a reset) is **trayecto**. A path on the map is a **ruta**.
- **Try vs retry vs test:** Trying a control (Experimental, Controls to try) is **Probar**; its results and titles use **intento**. An ordinary retry ("Try again", "Try shortly") is never *Probar*: use the retry phrase in Fixed words. A guided test is the noun **prueba**, and as a verb **hacer la prueba**: *Haz la prueba con el patinete detenido*, *Volt no hará la prueba*. Never use bare *Prueba* as an imperative for Try, because it reads as that noun; write *Pruébalo* or *Puedes probar este control*. *Test scheduled charging* is a check, so it's **Comprobar la carga programada**. A compatibility check is **comprobación de compatibilidad**.
- **Dashboard vs display:** Volt's Home layout is the **Panel**. The scooter's own screen is the **pantalla del patinete**. They never swap.
- **Settings vs setup:** settings are **Ajustes**, a one-time setup is **configuración**.
- **Restore vs set back:** bringing back a backup, or NAVEE logging as Fixed words says, is **Restaurar**. Undoing a try is **Revertir**, and so is putting back a test's earlier value even when the English says restore. Energy recovery is **Recuperación de energía**, unrelated to either.
- **Readback:** **relectura**, a neutral word, because a readback can also fail to confirm: *Relectura diferente*, *Ajuste confirmado por relectura*, *sin relectura del reloj*. Never a phrase with *confirmación* inside it.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Rechazado**.
- **Pair vs bind vs connect:** Bluetooth pairing is **emparejar**. NAVEE account binding is **vincular** (*vincular la cuenta*). A live link is **conectar**.
- **Owner key parts:** the file is the **archivo de configuración del propietario**, shortened to **archivo de propietario** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes, and agree with *recuperación*: *Baja, Media, Alta, Desactivada*. A question that turns it off names the level: *¿Cambiar la recuperación de energía a Desactivada?* Never *Dejar desactivada*, which reads as if it is already off. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Estimación Eco** is a range option, translated.
- **Roles:** owner is **propietario**, shared rider is **usuario autorizado**. The compact role label (`more_role_shared`, English *Shared*) is **Autorizado** (64dp at 12.5sp), never *Compartido*, which reads as a shared scooter.
- **Charge done by:** the feature name is **Fin de carga**, and its time setting **Hora de fin de carga**. The feature only plans a start, so never word it as a charge that has finished. In a sentence, quote the name: *«Fin de carga» está desactivado*, *desactiva «Fin de carga»*.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Acceso rápido**. Never *Ajustes rápidos*: that is Android's pull-down panel, and Settings is itself one of the shortcuts.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays (it also fits *laboratorio*). TalkBack speaks **Experimental**. *Laboratorio* as an evidence level on compatibility results is a different thing and stays.

## 8. Regional choices

`values-es` is a single text. Where regions differ:

| Concept | Choice | Others | Why |
|---|---|---|---|
| e-scooter | **patinete** | *scooter* (Mexico, Colombia, Chile, Peru), *monopatín* (Argentina; a skateboard in Spain), *patineta* | NAVEE's Spanish store uses *Patinete eléctrico* and also *scooter*; *monopatín* misleads Spain. **Debate item:** *scooter* is the serious alternative |
| mobile phone | **teléfono** | *móvil*, *celular* | Android itself says *teléfono*; no regional flag |
| Settings | **Ajustes** | *Configuración* (Android es-419) | Shorter, and *configuración* is reserved for setup. When pointing at Android's menus, say *los ajustes del teléfono*, which works for both |
| Bluetooth pairing | **emparejar** | *vincular* (Android es-419) | AOSP `values-es` says *Emparejar*, `values-es-rUS` *Vincular*. *vincular* would collide with account binding |
| handlebar | avoid; **PIN del patinete** | *manillar* (Spain), *manubrio* (Latin America) | No regional word needed. If a sentence must name the part, use **manillar** |
| parked | **detenido** | *aparcado* (Spain), *estacionado* (Latin America) | The meaning is "stationary" |
| turn signal | **indicador de giro** | *intermitente* (Spain), *direccional* (Mexico, Colombia) | NAVEE's store uses *Indicadores de giro* |
| tap | **toca** | *pulsa*, *pincha* | Android's word for the screen |
| press (physical button) | **presiona** | *pulsa* | Understood in both |
| computer | **computadora** | *ordenador* (Spain), *computador* | Understood in Spain; *ordenador* is rarer in the Americas |
| to ride | **conducir**, **conducción** | *manejar*, *montar* | NAVEE's store: *modo de conducción* |
| Done | **Listo** | *Hecho* | Reads fine in both |
| report | **informe**; *Informar de un problema* | *reporte*, *reportar* | Understood everywhere |
| live | **en vivo** | *en directo* | |
| average | **media** | *promedio* | Short, understood |
| owner key | **clave de propietario** | *código de propietario* | **Debate item:** in much of Latin America *clave* is an everyday word for a password or PIN, which sits badly with *No es una contraseña*. *código* is free, because a PIN is never *código*. Kept because it says secret |
| top speed (measured) | **Velocidad punta**, **Vel. punta** | *Velocidad alcanzada*, *Vel. alcanzada* (83dp, fits the 90dp tile) | **Debate item:** Spain-leaning (compare *hora punta*, *hora pico*), the one choice here that may not read at once everywhere. Kept because it says highest and stays distinct from *Velocidad máxima*; *alcanzada* alone only says reached |
| tire | **neumático** | *llanta* (a rim in Spain), *goma*, *rueda* | |
| file | **archivo** | *fichero* | |

**Never use:** *coger* (vulgar in much of Latin America; use *tomar* or rephrase), *vale*, *ordenador*, *móvil*, *celular*, *ahorita*, *chequear* (use *comprobar*).

## 9. Android's own wording

Quote Android's settings as Android writes them: **Depuración por USB**, **Opciones para desarrolladores**, **Número de compilación**. *Ajustes › Información del teléfono* differs by region, and phone makers rename it, so write generally where you can: *en los ajustes, abre la información del teléfono*.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Inténtalo de nuevo**; *Inténtalo de nuevo en un momento*. As a button, **Reintentar**. Never *Probar*, *Pruébalo de nuevo* or *Haz otro intento*, which read as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **sin datos**. Says only that no value came, not that anything is off or fine. Never *sin informar*, *no informado* or *no reportado* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **indicado** (agrees with its noun); verb **indicar**: *el patinete indica*, *Indicado por el patinete*. A claim, not a measurement. Never *informado*, *reportado* or *confirmado*. *informe* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **registro de actividad de NAVEE**. Never bare *registro*, which reads as signing up for an account |
| restore logging | turning that logging back to how it was (usually off) | **restaurar el registro de actividad**. Means putting that logging back as it was, usually off. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **herramienta de Volt**; *herramienta* alone once the screen makes clear which. Never *asistente*. The script on the computer is the different **herramienta de captura** |
| stock charger | the charger that came with the scooter | **cargador original**. Never *estándar* |
| high-speed charger | a faster charger the rider may own | **cargador rápido**; alone, *Rápido*. Never *de alta velocidad*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **corte**. Means no data was recorded, not that power or the ride was cut. Never *hueco* |
| elevation | height above sea level on a route | **altitud** |
| current direction | which way battery current flows | **sentido de la corriente**. Never *dirección*, which reads as a route or the handlebars |
| throughput | how much energy has passed through the battery | **flujo de energía**; *flujo* once the screen has said energy. An amount of energy that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **indicador de batería**; *indicador* alone where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **en bruto**; as a tag **BRUTO**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Última lectura**; with a value, *Última lectura: %1$s*. The data may be old, so never word it as current. Not *último conocido*, which is the last known state |
| add | adding a widget, scooter or item | **agregar**: *Agregar widget*, *Agrega otro patinete*. **Debate item:** *añadir* is Android's word in Spain; both are understood |
| enter (typing) | typing a value | **escribir**: *Escribe un PIN nuevo*. Never *introducir* or *ingresar*, which divide the regions |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, feminine: *la app*, *la app de NAVEE*, *la app oficial*. *aplicación* only when quoting Android's own label (section 9) |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **conecta el patinete**, *Conecta el patinete primero*, *Volver a conectar el patinete*. Never *conéctate*, which reads as going online. The button stays *Conectar*; plugging in says *conecta el cargador* |
| set a value | one verb for setting a value on the scooter | **establecer**: *Establecer PIN*, *Establecer %1$s*. Never *fijar*. The noun stays *ajuste*, and setting something back stays *Revertir* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Future with *se*, in dialogs and hints alike: *Se eliminará este viaje*, *Se abrirá el mapa*. Never *Elimina…* or *Abre…*, which read as commands |
| sign (+ or −) | whether a value is positive or negative | **signo**: *Signos de batería*. Never *señal*, which means a signal |
| window (of time) | the span of time a chart or widget covers | **periodo**: *Periodo de tiempo*. Never *ventana*, which reads as a window on screen |
| connected history | readings stored while connected, not a log of connections | **Historial de lecturas**. *Actividad de conexión* is the log |
| app preferences | Volt's own settings section and its shortcut | **Ajustes de la app**, the same on the shortcut and the section. *Preferencias del patinete* stays for scooter preferences saved in Volt, which aren't the settings on the scooter |
| a value you can't see | a question or sentence ending in an on, off or level word | Name it without agreement: *¿La luz trasera está en este estado: %1$s?* |
| NAVEE, meaning the app | the English says NAVEE alone for the app | **la app de NAVEE**: *La app de NAVEE no se conectó*. Never *NAVEE* with feminine agreement |
| screen and section titles | noun form or verb form, with one example | Noun form: *Copia de seguridad y restauración*. If the title is an action with no good noun, the infinitive: *Comprobar la carga programada*. Never the imperative |

## 10. Sources consulted

**NAVEE's official Spanish: partly found, and only for marketing words.** The official app's Spanish wording was **not found**: no public page shows its strings for lock, charge limit, PIN, owner key or experimental features. Those words are Claude's own proposals, and the official app may differ. No APK was downloaded.

- [NAVEE store, GT3 (es)](https://naveetech.us/es/products/navee-gt3): *Patinete eléctrico urbano*, *aplicación NAVEE*, *modo de conducción*, *Sistema de recuperación de energía*, *autonomía*, *Indicadores de giro*, *Faros automáticos*, TCS kept as *TCS*.
- [NAVEE store, ST3 Pro (es)](https://naveetech.us/es/products/navee-st3-pro) and [ST3 (es)](https://naveetech.us/es/products/navee-st3): *intermitentes delanteros*, *luz trasera*, *velocidad máxima*, *scooter*, *APP NAVEE*. The store mixes tú and usted and *patinete* with *scooter*, so it isn't a style model.
- NAVEE manuals on manualslib ([N65](https://manualslib.es/manual/701069/Navee-N65.html?page=9), [N65i](https://manualslib.mx/manual/909648/Navee-N65I.html?page=5), [S65](https://manualslib.mx/manual/799297/Navee-S65.html?page=5)): only headings were readable in Spanish (*Pasos para plegar el scooter*). Body pages returned were English. The official manual PDFs on service.naveetech.com (N65 and V40 EU) returned 404.
- Android's own Spanish, read from AOSP: [SettingsLib es](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-es/strings.xml) and [es-rUS](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-es-rUS/strings.xml) (*Depuración por USB*, *Opciones para desarrolladores*, *Emparejar* vs *Vincular*); Settings [es](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-es/strings.xml) and [es-rUS](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-es-rUS/strings.xml) (*Ajustes* vs *Configuración*). *Número de compilación* comes from third-party Android guides ([example](https://androidguias.com/que-es-la-depuracion-usb-en-android-y-como-sacarle-partido/)); its AOSP value wasn't readable. *Información del teléfono* is from memory.
- Retailer and search-snippet usage, weaker evidence: [Wellbots](https://www.wellbots.com/es/products/navee-gt3-pro-e-scooter) (*desbloqueo por proximidad*), [MediaMarkt](https://www.mediamarkt.es/es/product/_patinete-electrico-navee-s65-modo-crucero-ipx5-iluminacion-25w-velocidad-maxima-32kmh-blanconegro-1538938.html) (*modo crucero*), *control de crucero*, *bloqueo electrónico*.
- Regional usage for *monopatín* and *patineta*: [La FM, Colombia](https://www.lafm.com.co/economia/monopatines-buscan-imponerse-ante-el-caos-de-movilidad-en-america-latina-136952), [Mercado Libre Peru](https://www.mercadolibre.com.pe/shorts/clips/4oiA4X/patinete-electrico-navee-k100-max-monopatin-ninos). These are thin.
- **RAE and Fundéu could not be read** (403 and blocked). The rules on quotes, percent spacing, symbols and capitalization come from memory of the RAE *Ortografía* (2010), not a fresh reading. Other regional tables in section 8 are also from general knowledge, unverified by a native speaker.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`.
