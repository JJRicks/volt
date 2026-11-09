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
- Possessives take the article: *a tua trotinete*, *o teu telemóvel*. Avoid gendering the rider: *Tens de estar conectado* assumes a man, so write *Conecta a trotinete primeiro* or *É preciso uma ligação*. Role labels use the generic masculine (*proprietário*).
- For what Volt or the scooter did, use *se* or the state itself: *Não foi possível ler*. Volt is feminine, like *a app*: *a Volt não está afiliada*.

## 2. Punctuation and quotes

- Quotes: **«…»** first, **“…”** inside. Never straight quotes. No space inside them.
- A sentence that names a button, tab or screen puts its exact label in «…»: *Toca em «Guardar»*, *abre «Cópia de segurança e restauro»*. A feature mentioned in passing stays unquoted (section 3).
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
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android and TCS. Drive modes are never translated and keep the case the English string uses in that string: *modo Sport* where it writes Sport, *ECO* where it writes ECO.
- Capitals keep their accents and cedillas, including where Volt upper-cases text: *DEFINIÇÕES*.

## 4. Numbers, dates and units

- Decimal comma in text you write. Four-digit numbers take no separator (*1234,5*); from five digits a no-break space (U+00A0) groups thousands (*12 345,6*). Never a period as the group mark, and never U+202F, which Saira lacks. Volt's `Fmt` formats live numbers for the phone's region, so its output can differ from this; never rewrite a placeholder's number or add separators around one.
- **Percent takes no space:** `%1$s%%` gives *85%*, and `formatted="false"` text says *100%*.
- Numbers in the English stay as digits, even from one to ten, where Microsoft's guide spells them out. Don't write them out or change them. Ordinals have a period: *1.º*, *2.ª*.
- Unit symbols stay and take a no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Use the degree sign `°`, never the ordinal `º`. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *quilómetros*, *milhas*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 de março*. Portugal uses the 24-hour clock; don't write *a.m./p.m.* yourself.
- A time placeholder can't agree: *às %1$s* is wrong at one o'clock (*à 1:00*). *até às %1$s* has the same problem. Use a label form with a colon, for a deadline too: *Início: %1$s*, *Hora limite: %1$s*. In a long sentence, end the sentence and give the time as a label.
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
| proximidade | **prox.** | 73 → 30 | Only in *PIN e prox.* (about 64), the 90dp Setting tile's name for PIN & proximity (#297). Everywhere else, the full word |
| máximo, mínimo | **máx.**, **mín.** | | |
| informações | **info** | | A short word, no period: *Info e permissões* on the 64dp shortcut (102 on one line, wraps at a space); the one-line tile says *Permissões* |

Measured words that fit as they are, under the 62dp tile budget: *Bloqueio* 51, *Definições* 60.6 (tight), *Bateria* 42, *Viagens* 47. For the 90dp tile: *Condução* 57 for Ride settings, *Trotinetes* 59 for My scooters, *Em direto* 56 for Live Stats (*Dados em direto* is 94.5), *Histórico* 51 for Data & history, *Experimental* 77 for Experimental controls, *Preferências* 73 for App preferences, *Permissões* 67 for About & permissions, *Carga até* 55 for Charge done by, *Hora limite* 64 for its time, *Programação* 77 for Charge schedule (never *Carregamento* alone, which reads as the charging status), *PIN e prox.* (about 64) for PIN & proximity (the tile always shows the short name, so it names both parts of the menu). *Bloqueada* is 62.2, so it can't take a 62dp budget: the tile that names the feature says *Bloqueio*. Report any state that doesn't fit; don't shorten it.

An abbreviation ends in a period, and the period counts toward the width.

**Two widgets, two wrap rules** (`QuickAccessGrid.java`, header of `strings_dashboard_model.xml`):

- **Setting tile, 90dp at 12.5sp (`dashboard_model_qa_*_short`, `dashboard_model_tile_*`):** one line, ending in `…`. Nothing wraps. Check how the cut text would read as well as its width: *Carga pronta…* reads as a finished charge, which is a meaning defect.
- **Quick access button, 64dp at 12sp (`dashboard_model_qa_*`):** two lines, and three lines shrink to 10sp. A single word wider than the line breaks mid-word. A word from 64 to 76.8dp at 12sp is only safe if the name then runs to three lines and shrinks: *PIN e proximidade* does, *Preferências da app* (*Preferência|s da app*) doesn't, so that button says *Opções da app*. A word over 76.8dp breaks either way: *experimentais* is 79.6, no accepted short form exists, and it is reported as a design overflow.
- The width check measures the whole name on one line, so it reports *Luzes de linha* and *Luzes de estrela* although they wrap cleanly at the space. Leave them; never cut to *Luz linha*.

**Never abbreviate:** *Bloquear, Desbloquear, Bloqueada, Desbloqueada, Bloqueio, Desbloqueio, Não verificado, Desconhecido, Nunca, Sempre, Alarme, PIN*. *Bloq.* and *Desbl.* are not allowed. If one of these doesn't fit, write the full word and report it in your notes: an overflow is a design problem that gets fixed.

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
- **Cables and chargers take ligar.** With a cable or charger named as the object, *ligar* and *desligar* are what a native writes: *Liga o carregador*, *Desliga o cabo e volta a ligá-lo*, *Liga o telemóvel com o cabo de dados*. Never *conectar o carregador*. *Plugged in* and *unplugged* are plain *ligado* and *desligado*: name the wall socket only where the English does, because a charger in the wall but not in the scooter must not pass the check. A phone to a computer or to a website also takes *ligar*: *Ligar ao computador*, *O site ligou-se ao telemóvel*. Intransitive *conectar* needs an object: *conecta a trotinete*. The glossary check warns about *Connect* on these keys; that warning is expected, so leave the text. Bare *Ligada* and *Desligada* still mean scooter power, and the scooter's Bluetooth link stays *conectar*.
- **Carregar has three meanings:** to charge, to press and to load. Keep it for charging a battery. Press is **premir** (*prime o botão*), tap is **tocar** (*toca em «Parar»*), and data doesn't *carregar*: loading is *A abrir…* or *A ler…*, uploading is **enviar**. A progress line that says *A carregar…* must mean the battery.
- **Para** is both "stop!" and "for". Don't use it as a bare imperative. For the alarm button write *Toca em «Parar»*. For an instruction to stop something write *Tens de parar*: *Tens de parar a trotinete primeiro*, *Tens de parar a gravação para experimentar*.
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
- **The English now follows the same split (D3, merged):** Maximum speed and Max. speed are the setting (`main_speed_*`, `dashboard_model_tile_top_speed`, `controls_experimental_speed_limit`), and top speed is the measured value (the `readings_maximum_speed_kmh` ladder and `rides_tile_max_speed`). Translate them like any other key, with **Velocidade máxima** for the setting and **Velocidade de ponta** for the measured value. `rides_tile_max_speed` is measured: *Vel. ponta*, never *Vel. máx.*
- **Rides vs trips:** a recorded ride is a **viagem**, the manual's word, and so are the rides stored on the scooter (*Stored trips* is *Viagens guardadas*). *Trip* (the distance since a reset) is **trajeto**. A path on the map is a **rota**.
- **Try vs test:** *Try* as a verb is **Experimentar**; its results and titles use **tentativa**. A guided test is the noun **teste**, and as a verb **fazer o teste**: *Faz o teste com a trotinete parada*. *Test scheduled charging* is a check, so it's **Verificar o carregamento programado**. A compatibility check is **verificação de compatibilidade**. *Controls to try* is **Controlos a experimentar**. A generic retry is **Tentar novamente**, *tenta novamente*; *Experimentar* is only for attempting a control.
- **Standing start** is **Arranque a partir de parado**, or *A partir de parado* where the setting's name is beside it: the value 0 of Kick-start speed, where the motor helps from a standstill. Never *Arranque parado*, which can read as a start that is stopped or turned off, so a rider may not expect the scooter to pull away. Not *Arranque sem impulso* either: the English has no negation, so the polarity check fails *sem* on this safety key.
- **Charge vs charging:** the level and what limits it are **carga** (*Limite de carga*, *Alerta de carga*). The act and its timing are **carregamento** (*Carregamento programado*, *Início do carregamento*, *Potência de carregamento*). Port: *porta de carregamento*. Load, meaning weight carried, is **peso**, never *carga*: *Modo, rota, peso…*
- **Dashboard vs display:** Volt's Home layout is the **Painel**, and Home as a destination is the same word: *Ir para o painel*, *No painel*. Never *Início*. Lower case mid-label unless quoted (section 3): *Esquema do painel*. The scooter's own screen is the **ecrã da trotinete**, even where NAVEE's manual says *painel de instrumentos*. They never swap. A phone or computer screen is also *ecrã*, never *tela*.
- **Settings vs setup:** settings are **Definições**, a one-time setup is **configuração**.
- **Restore vs set back:** bringing back a backup is **Restaurar**, noun *restauro*. Undoing a try or a guided test is **Repor**, noun *reposição*, even where the English says restore: *Não foi possível iniciar a reposição*, *Repor %1$s*. Saved layouts and default widgets also take *Restaurar*. The check accepts both verbs on *restore*, so it won't catch a swap. Resetting a trip is **Repor a zero**. Energy recovery is **Recuperação de energia**, unrelated to any of them.
- **Readback:** **releitura**, a neutral word, because a readback can also fail to confirm: *Releitura diferente*, *Definição confirmada por releitura*, *sem releitura do relógio*. Never a phrase with *confirmação* inside it.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Recusado**. *Refused by scooter* is **Recusado pela trotinete**. A sentence takes the verb: *A trotinete recusou repor o valor*.
- **Pair vs bind vs connect:** Bluetooth pairing is **emparelhar**. NAVEE account binding is **vincular** (the manual's *Vincular Meu Dispositivo*). A live link is **conectar**. Android's own button says *Sincronizar*, so don't quote it as a button name; *sincronizar* stays for clocks and data.
- **Owner key parts:** the file is the **ficheiro de configuração do proprietário**, shortened to **ficheiro de proprietário** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels agree with *recuperação*: *Baixa, Média, Alta, Desativada*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Estimativa Eco** is a range option, translated.
- **Roles:** owner is **proprietário**, shared rider is **utilizador autorizado**. The compact role label (`more_role_shared`, English *Shared*) is **Autorizado** (64dp at 12.5sp), never *Partilhado*, which reads as a shared scooter.
- **Charge done by:** the feature name is **Carga pronta até**, and its time setting **Hora de carga pronta**. On the one-line 90dp tile they are **Carga até** and **Hora limite**, because *Carga pronta…* would read as finished. The feature only plans a start, so never word it as a charge that has finished. In a sentence, quote the name: *«Carga pronta até» está desativada*.
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
| app | **app** | *aplicativo* | *a app* is feminine. *aplicação* only when quoting Android's or NAVEE's own label |
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
- **Carga pronta até** for Charge done by, which has no fixed Portuguese form. *Pronta* can suggest a charge that has finished, and on a tile with no time after it the phrase dangles. A verb can't read as a finished state: *Terminar carga até* or *Concluir carga até*. All three wrap to fit the 64dp@12sp shortcut in two lines (widest line 51 to 58dp). None fits the one-line 90dp@12.5sp tile (96, 108 and 103dp), so the tile says *Carga até* for now.
- **Autorizado** for the role label `more_role_shared`. Alone it can read as a status ("authorized: yes") rather than a role beside *Proprietário*. *Convidado* (61dp at 12.5sp, against 64dp) and *utilizador convidado* are the alternatives.
- **Vincular** for NAVEE account binding. It's the manual's word, so a rider can match it to the official app, but it leans Brazilian; pt-PT usually says *associar* (*associar a conta*).
- **Cruise Control** stays in English (owner decision, 2026-11-08, on a GT3 Max rider's note that riders in Portugal use the English term; retailers write it that way too). The council's earlier term was *controlo de cruzeiro*.

## 9. Android's own wording

Quote Android's settings as Android writes them: **Depuração USB**, **Opções de programador**, **Número de compilação**. Phone makers rename the path to the About page, so write generally: *nas definições, abre as informações do telemóvel*.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Tenta novamente**; *Tenta novamente daqui a pouco*. As a button, **Tentar novamente**. Never *Experimenta outra vez* or *Volta a experimentar*, which read as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **sem dados**: *Bateria sem dados*. Says only that no value came, not that anything is off or fine. Never *não reportado*, *não comunicado* or *não confirmado* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **indicado** (agrees with its noun); verb **indicar**: *a trotinete indica*, *Indicado pela trotinete*. A claim, not a measurement. Never *reportado*, *comunicado* or *confirmado*. *relatório* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **registo de atividade da NAVEE**. Never bare *registo*, which reads as signing up for an account |
| restore logging | turning that logging back to how it was (usually off) | **repor o registo de atividade**: *A repor o registo de atividade da NAVEE…*. Means putting that logging back as it was, usually off. Not *restaurar*, which here means bringing back a backup (section 7) |
| Volt's helper | the small helper Volt starts inside Shizuku | **ferramenta da Volt**; *ferramenta* alone once the screen makes clear which. Never *assistente* or *ajudante*. The script on the computer is the different **ferramenta de captura** |
| stock charger | the charger that came with the scooter | **carregador original**. Never *padrão* |
| high-speed charger | a faster charger the rider may own | **carregador rápido**; alone, *Rápido*. Never *de alta velocidade*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **lacuna**: *Lacunas na gravação*. Means no data was recorded, not that power or the ride was cut. Never *falha*, which reads as a fault |
| elevation | height above sea level on a route | **altitude**. Never *elevação* |
| current direction | which way battery current flows | **sentido da corrente**. Never *direção*, which reads as steering or as *luz de direção* |
| throughput | how much energy has passed through the battery | **fluxo de energia**; *fluxo* once the screen has said energy. An amount of energy that passed through, not a rate. Never *débito*, which is a rate |
| battery gauge | the scooter's own battery percentage reading | **indicador da bateria**; *indicador* alone only where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **em bruto**: *Estado de carregamento (em bruto)*; as a tag **BRUTO**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Última leitura**; with a value, *Última leitura: %1$s*. The data may be old, so never word it as current. Not *Último estado conhecido*, which is the last known state |
| add | adding a widget, scooter or item | **adicionar**: *Adicionar widget*, *Adiciona outra trotinete* |
| enter (typing) | typing a value | **introduzir**: *Introduz um PIN novo*. Never *digitar*, which leans Brazilian |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, feminine: *a app*, *a app NAVEE*, *a app oficial*. *aplicação* only when quoting Android's or NAVEE's own label |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **conecta a trotinete**, *Conecta a trotinete primeiro*, *Volta a conectar a trotinete*. Never *conecta-te*, which reads as going online. The button stays *Conectar*; plugging in says *liga o carregador* (section 6) |
| set a value | one verb for setting a value on the scooter | **definir**: *Definir PIN*, *Definir %1$s*. Never *fixar*, and *configurar* is for a one-time setup. The noun stays *definição*, and setting something back stays *Repor* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | *Isto vai* + infinitive, in dialogs and hints alike: *Isto vai eliminar esta viagem*, *Isto vai abrir o mapa*. Never *Elimina…* or *Abre…*: with tu, the bare verb is also the command |
| layout slot | a saved Dashboard layout position | **espaço**: *Guardar no espaço 3*. Never *posição* |
| Auto | a choice that lets Volt decide | **Automático**, everywhere |
| per full gauge | per 100% of the scooter's battery reading | **por 100 por cento do indicador**. Never *indicador cheio*. The digit form needs a `%` that these strings can't carry |
| coverage (of readings) | the share of a ride with readings | **cobertura das leituras de potência**; bare *Cobertura* where the screen says of what |
| Clear | removing stored data | **Limpar** for history and try results, **Eliminar** for recordings and files |
| serial number | a device's unique number | **número de série**, in every rung. Never bare *Série*, which is the model series. Don't abbreviate to *N.º*: the script check refuses `º` |
| unfinished (test or try) | a test or try still to be reviewed | **não concluído**, *não concluída*. Never *por concluir*: the check needs a negation word on these keys |
| charges (a count) | how many charges the scooter reports | **cargas**: *%1$d cargas*, *Cargas indicadas*. Recordings of a charge stay *carregamentos* |
| OEM feature domain | the values NAVEE's firmware mapping allows | **opções OEM desta funcionalidade**. OEM stays. Never expand it to *app oficial*: the English doesn't mention the app |
| stop watching, keep watching | every form of the alert-watch verb | **monitorizar**: *Deixar de monitorizar*, *A Volt deixou de monitorizar*, *continuar a monitorizar*. Never *acompanhar* |
| "one" meaning only one | an instruction where the point is a single item | **uma só**: *de uma só conta*. Plain *uma* reads as an indefinite article |
| Keep it private, stored privately, seen | calques to avoid | *Guarda-o só para ti*, *guardada em privado*, *observada*. Never *Mantém-no privado*, *de forma privada*, *vista* |
| screen and section titles | noun form or verb form, with one example | Noun form: *Cópia de segurança e restauro*. If the title is an action with no good noun, the infinitive: *Verificar o carregamento programado*. Never the imperative |

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
