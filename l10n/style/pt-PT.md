# Volt style guide: European Portuguese (pt-PT)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 10; the three-vendor debate decides later. No native speaker has reviewed it. It ships in `values-pt`, so every Portuguese phone that is not Brazilian gets this text: Portugal, Angola, Mozambique and the rest. Brazil has its own guide (`pt-BR`). Write European Portuguese, not a blend.

Spelling follows the 1990 agreement (AO90), as Android and Microsoft do: *ativar*, *direção*, *elétrica*, *selecionar*, *ação*. Keep the European accents: *eletrónico*, *quilómetro*, *Odómetro*, *logótipo*.

## 1. Register and form of address

- Use **tu**, singular, always: *Liga o carregador*, *a tua trotinete*, *Tens a certeza?* Leave the pronoun out unless a sentence needs it. Never *você*, *o senhor*, *a sua trotinete*: NAVEE's manual and Microsoft's guide use the formal form, but the owner chose tu.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the infinitive:** *Guardar*, *Conectar*, *Bloquear*. **Instructions in sentences use the tu imperative:** *Volta a conectar para verificar*. Don't mix the two in one string.
- **The negative imperative is the subjunctive:** *não toques*, *não abras*, *não desligues*. *Não toca* means "it doesn't touch".
- **Something in progress is *a* + infinitive, never the gerund:** *A conectar…*, *A atualizar…*, *A bloquear…*, *A desbloquear…*, *a medir*. *Conectando…* and *Bloqueando…* read as Brazilian.
- Finished events take the simple past: *Funcionou*, *Perdeu-se o sinal*. Never *tem funcionado* for a single event: it means "has been working".
- Object pronouns follow the verb with a hyphen (*Desbloqueia-a*, *bloqueá-la*, *experimenta-o*) and go before it after *não*: *não a desbloqueies*. Never start a sentence with the pronoun.
- Possessives take the article: *a tua trotinete*, *o teu telemóvel*. Avoid gendering the rider: *Tens de estar conectado* assumes a man, so write *Conecta-te primeiro* or *É preciso uma ligação*. Role labels use the generic masculine (*proprietário*).
- For what Volt or the scooter did, use *se* or the state itself: *Não foi possível ler*. Volt is feminine, like *a aplicação*: *a Volt não está afiliada*.

## 2. Punctuation and quotes

- Quotes: **«…»** first, **“…”** inside. Never straight quotes. No space inside them.
- A sentence-final period goes outside closing quotes and parentheses. No space before `:`, `;`, `?`, `!` or `%`.
- Lists use commas with no serial comma before *e* or *ou*.
- Keep the English's `·` separators and `…` as written. Use the single character `…`, never three dots.
- A colon is followed by lower case unless a name follows: *Último estado conhecido: bloqueada*.
- Contract prepositions with articles: *à trotinete*, *da bateria*, *no telemóvel*, *pela trotinete*. **A placeholder can't contract.** With a name or a phrase that carries its own article (`main_lock_your_scooter`, *a tua trotinete*), a preposition before it gives *a a tua trotinete*. Use a colon instead: *A conectar: %1$s*. Don't write *à %1$s* or *ao %1$s*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged. Saira has every Portuguese letter, `«»“”…·` and `°` in all four weights (checked with `tools/l10n/fontmetrics.py`).

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: *Limite de carga* (label), *Guardar cópia* (button), *Controlos experimentais* (title). Never English Title Case, even when the English has it (*Walk Assist* → *Assistência à caminhada*). Microsoft's guide says to follow the source's capitals; Android's own pt-PT says *Opções de programador*, and this guide follows Android.
- A word after a colon stays lower case. Feature names are lower case mid-sentence unless quoted: *ativa o carregamento programado*.
- Days and months are lower case under AO90: *segunda-feira*, *5 de março*. Languages are lower case in sentences.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS, and the drive modes WALK, ECO, DRIVE, SPORT (never translated). Say *modo SPORT*.
- Capitals keep their accents and cedillas, including where Volt upper-cases text: *DEFINIÇÕES*.

## 4. Numbers, dates and units

- Decimal comma in text you write. Four-digit numbers take no separator (*1234,5*); from five digits a no-break space (U+00A0) groups thousands (*12 345,6*). Never a period as the group mark, and never U+202F, which Saira lacks. Volt's `Fmt` formats live numbers for the phone's region, so its output can differ from this; never rewrite a placeholder's number or add separators around one.
- **Percent takes no space:** `%1$s%%` gives *85%*, and `formatted="false"` text says *100%*.
- Numbers in the English stay as digits, even from one to ten, where Microsoft's guide spells them out. Don't write them out or change them. Ordinals have a period: *1.º*, *2.ª*.
- Unit symbols stay and take a no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Use the degree sign `°`, never the ordinal `º`. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *quilómetros*, *milhas*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 de março*. Portugal uses the 24-hour clock; don't write *a.m./p.m.* yourself.
- A time placeholder can't agree: *às %1$s* is wrong at one o'clock (*à 1:00*). Prefer a colon (*Início: %1$s*) where it reads well. Where a colon doesn't read well, as after a deadline, use *até às %1$s* as the general form.
- Plurals: `one` covers exactly 1, under pt-PT's own rule, which `AndroidText` applies to `values-pt`; zero takes `other` (*0 viagens*). `many` fires only on exact multiples of a million. Where a noun directly follows the number, always add *de*: `%1$d de viagens`. Otherwise copy `other` into `many`.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Try these in order:

1. Drop words the screen already supplies, including articles and prepositions: *Definições da bateria* → *Bateria* on a settings screen.
2. Use a shorter common word: *Dados em direto* → *Em direto*.
3. Use an accepted abbreviation from this table. Nothing else: no invented cuts such as *Defin.*, *Exp.*, *Bat. fraca*, *Trot.*

Real ladders from the catalog:

| Key | Full | `_short` | `_compact` |
|---|---|---|---|
| `readings_temperature_c` | Temperatura da bateria | Temp. da bateria | Temp. |
| `readings_voltage_v` | Tensão da bateria | Tensão da bateria | Tensão |
| `readings_odometer_km` | Odómetro total | Odómetro | Odóm. |
| `readings_trip_km` | Distância do trajeto atual | Distância do trajeto | Distância |
| `more_lab_status_untested` | Não testado no teu modelo | Não testado aqui | Não testado |
| `dashboard_charging_power` | Potência de carregamento | | Potência |

Accepted abbreviations:

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Velocidade máxima | **Vel. máxima**, then **Vel. máx.** | 112 → 71 → 52 | The setting. Fits the 90dp tile |
| Velocidade de ponta | **Vel. de ponta**, then **Vel. ponta** | 117 → 75 → 58 | The measured value. Never *Vel. máx.* |
| Velocidade média | **Vel. média** | 102 → 60 | |
| Odómetro | **Odóm.** | 58 → 38 | The accent stays |
| Temperatura | **Temp.** | 74 → 35 | |
| Capacidade | **Capac.** | 66 → 38 | |
| estimado, estimada, estimativa | **est.** | | *Autonomia est.* (88) |
| aproximadamente | **aprox.** | | |
| máximo, mínimo | **máx.**, **mín.** | | |
| informações | **info** | | A short word, no period: *Info e permissões* (102, wraps at a space) |

Measured words that fit as they are, under the 62dp tile budget: *Bloqueio* 51, *Definições* 60.6 (tight), *Bateria* 42, *Viagens* 47. For the 90dp tile: *Condução* 57 for Ride settings, *Trotinetes* 59 for My scooters, *Em direto* 56 for Live Stats (*Dados em direto* is 94.5), *Histórico* 51 for Data & history, *Experimental* 77 on one line or *Controlos experimentais* wrapped at the space. *Bloqueada* is 62.2, so it can't take a 62dp budget: the tile that names the feature says *Bloqueio*. Report any state that doesn't fit; don't shorten it.

An abbreviation ends in a period, and the period counts toward the width. A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Bloquear, Desbloquear, Bloqueada, Desbloqueada, Bloqueio, Desbloqueio, Não verificado, Desconhecido, Nunca, Sempre, Alarme, PIN*. *Bloq.* and *Desbl.* are not allowed. If one of these doesn't fit, report it in your notes rather than shortening it. Falling back to English is the safe outcome.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Portuguese | Rules |
|---|---|---|
| Lock (action) | **Bloquear** | Button or action. Official wording. Never *Trancar*, *Fechar* |
| Lock (noun, card title) | **Bloqueio** | Never *Cadeado*, *Fechadura*, *Tranca* |
| Locked | **Bloqueada** | Agrees with *trotinete*. *Bloqueado* only with a masculine subject |
| Unlock (action) | **Desbloquear** | Noun: **Desbloqueio** |
| Unlocked | **Desbloqueada** | Agrees with *trotinete*. Never *aberta* or *livre* |
| Lock state unverified | **Estado do bloqueio não verificado** | Must never read as confirmed. Keep *não*. Keep *do*: *estado de bloqueio* can mean a locked state |
| Lock state unknown | **Estado do bloqueio desconhecido** | Must never read as confirmed. Keep *do* |
| Unknown (alone) | **Desconhecido** | *Desconhecida* with a feminine subject |
| Unverified | **Não verificado** | *Não verificada* with a feminine subject. Never *Por verificar*, which reads as a queued task |
| Never | **Nunca** | Never *não* alone where the English says never |
| Always | **Sempre** | *Always on*: **Sempre ligada** for a light |
| Alarm | **Alarme** | Masculine. Never *alerta* or *aviso*: alert is **Alerta**, a warning is **Aviso**, and they stay distinct |
| Alarm sound | **Som de alarme** | |
| Stop (a sounding alarm) | **Parar** | |
| PIN | **PIN** | Masculine, no plural change: *o PIN*, *os PIN*. Not *código*, *palavra-passe* or *senha* |
| Handlebar PIN | **PIN do guiador** | PIN unlock is **Desbloqueio por PIN**. A password is **palavra-passe**; keep them separate |
| Owner key | **chave de proprietário** | A secret. Never *código* or *palavra-passe*. "Not a password" is *Não é uma palavra-passe* |
| Charge limit | **Limite de carga** | Never *teto* or *máximo de carga* |
| Experimental | **Experimental**, plural **experimentais** | |
| Untested | **Não testado** | *Não testada* with a feminine subject |
| Unconfirmed, Not confirmed | **Não confirmado** | The same words for both. Never *Por confirmar* |
| not affiliated with NAVEE | **não afiliada à NAVEE** | Never soften it: no *não oficial*, *independente da*, *sem ligação direta*. In a sentence: *A Volt não está afiliada à NAVEE*. *sem afiliação à NAVEE* is the accepted form where gender doesn't fit |

Words that look right and mean something else:

- **Bloqueada and Desbloqueada differ only by *Des-*.** Keep both whole, never truncate, and in sentences keep the full word.
- **Bloqueio de segurança is taken.** NAVEE's manual uses it for the physical folding latch. Volt's lock control is **bloqueio eletrónico**, never *bloqueio de segurança*.
- **Ligar and desligar are power, here.** They also mean connect and disconnect in Portugal. To keep a scooter that is on apart from one that is connected, power and lights use **ligar**, **desligar**, **ligada**, **desligada**, and the Bluetooth link uses **conectar**, **desconectar**, **conectada**, **desconectada**, as NAVEE's manual does in *Conectar à App NAVEE*. So *Scooter parked and powered on* is *Trotinete parada e ligada*, *Connected* is *Conectada*. Never *Desligar* for Disconnect: it would read as turning the scooter off. The noun for the link is **ligação** (*Detalhes da ligação*, *Sem ligação*), which never means power.
- **Cables and chargers take ligar.** With a cable or charger named as the object, *ligar* and *desligar* are what a native writes: *Liga o carregador*, *carregador ligado à tomada*, *Desliga o cabo e volta a ligá-lo*, *Liga o telemóvel com o cabo de dados*. Never *conectar o carregador*. The glossary check warns about *Connect* on these keys; that warning is expected, so leave the text. Bare *Ligada* and *Desligada* still mean scooter power, and the scooter's Bluetooth link stays *conectar*.
- **Carregar has three meanings:** to charge, to press and to load. Keep it for charging a battery. Press is **premir** (*prime o botão*), tap is **tocar** (*toca em Parar*), and data doesn't *carregar*: loading is *A abrir…* or *A ler…*, uploading is **enviar**. A progress line that says *A carregar…* must mean the battery.
- **Para** is both "stop!" and "for". Don't use it as a bare imperative. For the alarm button write *Toca em Parar*. For an instruction to stop something write *Tens de parar*: *Tens de parar a trotinete primeiro*, *Tens de parar a gravação para experimentar*.
- **Seguro** also means insurance, and it's reassurance the English lacks. Don't add it.

Further rules:

- Don't describe an unlock as removing the lock (*Retirar o bloqueio*, *Levantar o bloqueio*); say *Desbloquear*. Only use *desativar o bloqueio* where the English itself says turn off.
- Keep every negation. *não*, *sem*, *nunca* and *nem* count as negation words for the checks; a prefix alone (*inativo*) only counts for the listed native words.
- On and off: settings use **Ativado** and **Desativado**, lights and motors use **Ligado** and **Desligado**. Agree in gender with the thing named. Never invert them, never use *Sim/Não* in their place.
- Don't add warnings the English doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Trotinete** is feminine: *a trotinete*, *conectada*, *bloqueada*, *simulada*. NAVEE's manual mixes *trotineta*, *trotinete*, *patinete* and *scooter*; only *trotinete* is used here.
- **Three speeds:** the setting is **Velocidade máxima**. A limit below it is **Limite de velocidade**. The measured value on a trip or ride is **Velocidade de ponta**. Never swap them.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung and `rides_tile_max_speed` say maximum or max speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. When `rides_tile_max_speed` opens, it is measured: *Vel. ponta*, never *Vel. máx.* Flag them in your notes; a judge answers fallback.
- **Rides vs trips:** a recorded ride is a **viagem**, the manual's word. *Trip* (the distance since a reset) is **trajeto**. A path on the map is a **rota**.
- **Try vs test:** *Try* as a verb is **Experimentar**; its results and titles use **tentativa**. A guided test is the noun **teste**, and as a verb **fazer o teste**: *Faz o teste com a trotinete parada*. *Test scheduled charging* is a check, so it's **Verificar o carregamento programado**. A compatibility check is **verificação de compatibilidade**. *Controls to try* is **Controlos a experimentar**. A generic retry is **Tentar novamente**, *tenta novamente*; *Experimentar* is only for attempting a control.
- **Standing start** is **Arranque a partir de parado**, or *A partir de parado* where the setting's name is beside it: the value 0 of Kick-start speed, where the motor helps from a standstill. Never *Arranque parado*, which can read as a start that is stopped or turned off, so a rider may not expect the scooter to pull away. Not *Arranque sem impulso* either: the English has no negation, so the polarity check fails *sem* on this safety key.
- **Charge vs charging:** the level and what limits it are **carga** (*Limite de carga*, *Alerta de carga*). The act and its timing are **carregamento** (*Carregamento programado*, *Início do carregamento*, *Potência de carregamento*). Port: *porta de carregamento*. Load, meaning weight carried, is **peso**, never *carga*: *Modo, rota, peso…*
- **Dashboard vs display:** Volt's Home layout is the **Painel**. The scooter's own screen is the **ecrã da trotinete**, even where NAVEE's manual says *painel de instrumentos*. They never swap. A phone or computer screen is also *ecrã*, never *tela*.
- **Settings vs setup:** settings are **Definições**, a one-time setup is **configuração**.
- **Restore vs set back:** bringing back a backup is **Restaurar**. Undoing a try is **Repor**. Resetting a trip is **Repor a zero**. Energy recovery is **Recuperação de energia**, unrelated to any of them.
- **Readback:** **releitura**, a neutral word, because a readback can also fail to confirm: *Releitura diferente*, *Definição confirmada por releitura*, *sem releitura do relógio*. Never a phrase with *confirmação* inside it.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Recusado**. *Refused by scooter* is **Recusado pela trotinete**. A sentence takes the verb: *A trotinete recusou repor o valor*.
- **Pair vs bind vs connect:** Bluetooth pairing is **emparelhar**. NAVEE account binding is **vincular** (the manual's *Vincular Meu Dispositivo*). A live link is **conectar**. Android's own button says *Sincronizar*, so don't quote it as a button name; *sincronizar* stays for clocks and data.
- **Owner key parts:** the file is the **ficheiro de configuração do proprietário**, shortened to **ficheiro de proprietário** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels agree with *recuperação*: *Baixa, Média, Alta, Desativada*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Estimativa Eco** is a range option, translated.
- **Roles:** owner is **proprietário**, shared rider is **utilizador autorizado**. The compact role label (`more_role_shared`, English *Shared*) is **Autorizado** (64dp at 12.5sp), never *Partilhado*, which reads as a shared scooter.
- **Charge done by:** the feature name is **Carga pronta até**, and its time setting **Hora de carga pronta**. The feature only plans a start, so never word it as a charge that has finished. In a sentence, quote the name: *«Carga pronta até» está desativada*.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Acesso rápido**. Never *Definições rápidas*: that is Android's pull-down panel, and Settings is itself one of the shortcuts.
- **Prompt sound:** the scooter's ordinary beeps are **Sinal sonoro**, plural *sinais sonoros*. Never *Som de aviso*, which would read as a warning sound, and keep it apart from *Som de alarme*.
- **Readings:** voltage is **Tensão**, never *Voltagem*. Current is **Corrente**, power is **Potência**, range is **Autonomia**.
- **Parts, from NAVEE's manual:** **guiador** (handlebar), **travão** (brake), **acelerador** (throttle), **farol** (headlight), **luz traseira** (tail light), **pisca**, plural **piscas**, masculine (turn signal; the sound toggle is *Som dos piscas*; the manual's **luz de direção** is accepted), **pneu** (tire), **botão de alimentação** (power button), **bateria fraca** (low battery), **condutor** (rider), **conduzir** (to ride).
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays (it also fits *laboratório*). TalkBack speaks **Experimental**.

## 8. Portuguese, not Brazilian

A word that is normal in Brazil can read as a mistake here. A Brazilian word in this text is a defect.

| Concept | Use | Never | Note |
|---|---|---|---|
| e-scooter | **trotinete** | *patinete*, *scooter* in text | |
| mobile phone | **telemóvel** | *celular* | Android and the manual agree |
| screen | **ecrã** | *tela* | |
| Settings | **Definições** | *Configurações*, *Ajustes* | Android |
| file | **ficheiro** | *arquivo* | |
| password | **palavra-passe** | *senha* | |
| user | **utilizador** | *usuário* | |
| app | **app**, **aplicação** | *aplicativo* | *a app* is feminine |
| Save | **Guardar** | *Salvar* | |
| Delete | **Eliminar** | *Excluir*, *Deletar* | |
| Download, upload | **Transferir**, **Enviar** | *Baixar*, *Carregar* | |
| Share | **Partilhar** | *Compartilhar* | |
| Tap, press | **toca**, **prime** | *clica*, *aperta*, *carrega* | Brake levers are the exception: *aperta o travão* |
| Done | **Concluído** | *Pronto* for a finished step | |
| backup | **cópia de segurança** | *backup* in text | Android |
| report | **relatório**, *Comunicar um problema* | *reportar*, *reporte* | |
| live | **em direto** | *ao vivo* | |
| control | **controlo** | *controle* | |
| your | **o teu**, **a tua** | *seu*, *sua* | |
| spelling | *eletrónico*, *quilómetro*, *registo* | *eletrônico*, *quilômetro*, *registro* | |
| trip reset | **Repor a zero** | *Zerar* | |

**Debate items.** These are judgment calls the other vendors should attack:

- **Conectar** over Android's *Ligar*. Android pt-PT and many Portuguese readers say *Ligar*, and some will find *conectar* slightly Brazilian. It's kept because the manual uses it and because *Ligada* can't then be mistaken for a connected scooter or the reverse.
- **Painel** for Volt's Dashboard, since NAVEE's manual calls the scooter's own display *painel de instrumentos*. The string says Painel only for Volt's Home; **ecrã da trotinete** carries the other sense.
- **Piscas** over NAVEE's *luz de direção*: riders say *piscas*, and *Som dos piscas* (89dp at 12.5sp) suits a toggle, but no official source was found for it.
- **Chave de proprietário** can suggest a physical key, as Portuguese has only *chave*. *Código* is taken by the PIN's sense.
- **Carga pronta até** for Charge done by, which has no fixed Portuguese form. *Pronta* can suggest a charge that has finished, and on a tile with no time after it the phrase dangles. A verb can't read as a finished state: *Terminar carga até* or *Concluir carga até*. All three wrap to fit the 64dp@12sp shortcut in two lines (widest line 51 to 58dp), and all wrap on the 90dp@12.5sp tile (96, 108 and 103dp on one line).
- **Autorizado** for the role label `more_role_shared`. Alone it can read as a status ("authorized: yes") rather than a role beside *Proprietário*. *Convidado* (61dp at 12.5sp, against 64dp) and *utilizador convidado* are the alternatives.
- **Vincular** for NAVEE account binding. It's the manual's word, so a rider can match it to the official app, but it leans Brazilian; pt-PT usually says *associar* (*associar a conta*).
- **Controlo de cruzeiro** for Cruise control. Portuguese retailers often write *Cruise control* in English.

## 9. Android's own wording

Quote Android's settings as Android writes them: **Depuração USB**, **Opções de programador**, **Número de compilação**. Phone makers rename the path to the About page, so write generally: *nas definições, abre as informações do telemóvel*.

## 10. Sources consulted

**NAVEE's official Portuguese: found for the manual, not for the app.** The official app's Portuguese wording was **not found**: no public page shows its strings for PIN, owner key, charge limit or experimental features. Those words are Claude's own proposals, and the official app may differ. No APK was downloaded.

- [NAVEE V40i Pro/V50i Pro manual, PT](https://www.naveetech.com/wp-content/uploads/2025/07/V40i-Pro-V50i-Pro-PT.pdf), read in full text: *Bloquear*, *bloqueada*, *desbloquear*, *“Desbloquear”* in the app, *aplicação NAVEE*, *App NAVEE*, *Conectar à App NAVEE*, *Vincular Meu Dispositivo*, *telemóvel*, *guiador*, *travão*, *farol*, *luz traseira*, *luz de direção*, *modos de condução*, *modo de caminhada*, *velocidade máxima*, *arranca*, *bateria fraca*, *carregamento*, *porta de carregamento*, *definições de fábrica*, *viagem*, *condutor*, *20%* and *6 km/h* for spacing. It's a weak style model: machine-translated in places, formal in address, and with Brazilian leaks (*patinete*, *guidão*, *entre em contato*).
- NAVEE E20 quick start guide, PT (service.naveetech.com): seen only as a search snippet; the PDF returned 404. The V40 EU manual also returned 404.
- [PcComponentes PT, NAVEE GT3](https://www.pccomponentes.pt/trotinete-eletrica-navee-gt3-25km-h-50km-de-autonomia-700w-com-pneus-tubeless-10-cinzenta), retailer wording, weaker evidence: *Trotinete elétrica*, *Autonomia*, *Velocidade máxima*, *tempos de carregamento*, *sistema antiderrapante TCS*, *Sinais de direção*. powerplanetonline.com/pt returned 403.
- Android's own pt-PT, read from AOSP: [SettingsLib](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-pt-rPT/strings.xml) (*Depuração USB*, *Opções de programador*, *Sincronizar*, *A ligar...*, *Telemóvel*); [Settings](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-pt-rPT/strings.xml) (*Definições*, *Número de compilação*, *palavra-passe*).
- [Microsoft Portuguese (Portugal) style guide](https://download.microsoft.com/download/9/9/5/995a8ca6-383c-439d-9db0-cd457c9c2724/por-prt-StyleGuide.pdf): *a* + infinitive for progress, infinitive for commands, imperative for instructions, abbreviation with a final period, AO90 via the ILTEC *Vocabulário Ortográfico*. This guide differs on purpose where the owner or the project has decided: tu, sentence case, digits, «…».
- [CLDR pt-PT numbers](https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json/cldr-numbers-full/main/pt-PT/numbers.json): decimal comma, space as group mark, minimum grouping digits 2, percent pattern `#,##0%`. The page didn't show the code point of the group mark; U+00A0 is from memory. The plural categories are those in `l10n/README.md`.
- Search-snippet usage, thin: [4gnews](https://4gnews.pt/melhores-trotinetes-eletricas/) (*controlo de tração*), [Goldenergy](https://goldenergy.pt/blog/?p=8052) (*trotinetes elétricas*, *travagem regenerativa*), [isinwheel on Decathlon PT](https://www.decathlon.pt/p/mp/trotinete-eletrica-isinwheel-s10-plus-motor-500w-bateria-36v-15ah/b1510dc1-e442-46d7-9307-c8e4d0cd2742/c1) (*Cruise control*).
- **Priberam and Ciberdúvidas were not read:** searches didn't return them. The rules on «…», the negative imperative, pronoun placement and the Brazilian-word table come from general knowledge of the language, unverified by a native speaker. So do *velocidade de ponta*, *arranque a partir de parado*, *sinal sonoro*, *piscas*, *monitorizar*, *luz de circulação diurna*, *emparelhar* and *premir*.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`.
