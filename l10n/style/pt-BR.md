# Volt style guide: Brazilian Portuguese (pt-BR)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 10; the three-vendor debate decides later. No native speaker has reviewed it. `values-pt-rBR` serves Brazil only. European Portuguese is a separate language (`pt-PT`, `values-pt`), so never mix the two: section 8 lists the words that give it away.

Rule of thumb: write what a Brazilian Android phone would say. Where Android has a word, use it.

## 1. Register and address

- Address the rider as **você**, and leave the pronoun out wherever the sentence reads well without it: *Conecte o carregador*, *seu patinete*. Never *tu*, never *o senhor*. Never mix *você* forms with *teu* or *te*.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang.
- **Buttons, toggles and menu actions use the infinitive,** as Android does: *Salvar*, *Conectar*, *Bloquear*, *Parear*. **Instructions in sentences use the você imperative:** *Toque em “Salvar”*, *Conecte o patinete novamente para verificar*, *Bloqueie o patinete*. Don't mix the two in one string. Don't turn an instruction into *você deve* where the imperative is enough.
- **An action in progress uses the gerund and `…`:** *Conectando…*, *Medindo…*. Never *A conectar*, which is European.
- Volt is masculine and takes the article in a sentence: *O Volt não consegue ler…*, *no Volt*. NAVEE, as a company, is feminine: *a NAVEE*, *à NAVEE*, *da NAVEE*. The app is *o app*: *o app NAVEE*, *o app oficial* (Fixed words).
- **Patinete is masculine:** *o patinete*, *seu patinete*, *conectado*. Agree with it. Some Brazilian press writes *a patinete*; don't.
- Avoid gendering the rider where a rewrite is easy: prefer a statement about the scooter to one describing the rider. Role labels use the generic masculine (*proprietário*, *usuário autorizado*).
- Prefer the simple past for finished events: *Funcionou*, *O sinal foi perdido*. For what Volt or the scooter could not do, name the subject or use *Não foi possível*: *Não foi possível ler o valor*.

## 2. Punctuation and quotes

- Quotes: **“…”** first, **‘…’** inside. Never straight quotes in new text. Never « », which belong to European typography.
- A sentence-final period goes outside closing quotes and parentheses: *Toque em “Salvar”.*
- A sentence that names a button, tab or screen puts its exact label in “…”: *Toque em “Salvar”*, *abra “Detalhes da conexão”*. A feature mentioned in passing stays unquoted (section 3). Quotes belong to sentences: buttons, toggles and titles don't quote a label (*Ir para o Início*, *Permitir acesso a dispositivos por perto*).
- Lists use commas, with no serial comma before *e* or *ou*.
- Use the crase where it belongs: *à NAVEE*, *às 7:00*, *Volte à tela inicial*. Don't add it before a masculine noun or a verb.
- A colon is followed by lower case unless a name follows: *Último valor: bloqueado*.
- No space before `:`, `;`, `?`, `!` or `%`. Keep the English's `·` separators and `…` as written.
- Parentheses take no inner spaces. Don't use dashes where a comma or a colon works.
- Follow the 2009 spelling agreement as used in Brazil: *eletrônico*, *quilômetro*, *ideia*, no trema. Keep the circumflex (*eletrônico*, not *electrónico*) and write no silent *c* or *p*: *ativado*, *ação*, *direção*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: button *Tentar novamente*; label *Limite de carga*; title *Controles experimentais*. Never English Title Case, even when the English has it (*Walk Assist* → *Assistência ao caminhar*, *Low Battery Mode* → *Modo de bateria fraca*).
- Feature names are lower case mid-sentence unless quoted: *ative o carregamento programado*. A name quoted from a label keeps the label's capital: *desative “Carga pronta até”*.
- Days, months and languages are lower case.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS. Drive modes and their level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *modo Sport* where the English says Sport.
- Capital letters keep their accents, including where Volt upper-cases text: *BATERIA*, *INÍCIO*, *CONEXÃO*.

## 4. Numbers, dates and units

- **Decimal comma, period for thousands, grouping from four digits:** *1.234,5*. This differs from European Portuguese, which leaves four digits ungrouped. Volt's `Fmt` formats live numbers for the phone's region, so never rewrite a placeholder's number or add separators around one.
- **Percent takes no space:** `%1$s%%` gives *85%*. Text with `formatted="false"` writes *100%*. Brazilian usage and the NAVEE guide (*30%*) both write it this way. So unlike Spanish or German, percent needs no U+00A0.
- **Unit symbols take a no-break space (U+00A0) after a number or placeholder:** *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Never a plain space, and never U+202F, which Saira lacks. Symbols take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. Unit words are translated: *quilômetros*, *milhas*, *quilômetros por hora*.
- Numbers in the English stay in the Portuguese as digits. Don't write them out or change them.
- Dates and times come from Volt's formatters, so don't hard-code an order. Brazil reads day, month, year and a 24-hour clock. A fixed date in prose reads *5 de março*. Don't write *a.m./p.m.* yourself. Around a time placeholder, prefer a colon or *até*: *Início: %1$s*, *até %1$s*. Never *às %1$s*: it would give *às 1:00* and *às 0:00*, where the norm is *à*, and grammar can't depend on a value you can't see.
- **In “X of Y nouns” the noun agrees with the total,** so `one` keeps the plural: *%1$d de %2$d atalhos*.
- **Plurals: `one` covers 0 and 1 in Brazilian Portuguese.** So an `one` form always keeps its count placeholder (`check.py` requires it) and has to read correctly with 0: *%1$d patinete*. Don't write *Um patinete* or *Nenhum patinete*. Copy `other` into `many`. The grammar only differs with a bare large number (*1 milhão de viagens*); a placeholder's digits don't trigger it, so the form stays the same.

## 5. Shortening labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Try these in order:

1. Drop words the screen already supplies: *Distância do trajeto atual do patinete* → *Distância do trajeto* → *Distância*.
2. Use a shorter word this guide accepts, with the same meaning: *Configurações* → *Ajustes*.
3. Use an accepted abbreviation from this table. Nothing else: no invented cuts such as *Conf.*, *Exp.*, *Bat.*, *Patin.*, *Carreg.*

An abbreviation ends in a consonant and a period, keeps its accents, and the period counts toward the width.

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Velocidade máxima | **Vel. máxima**, then **Vel. máx.** | 112 → 71 → 52 | The setting. The full form doesn't fit the 90dp tile |
| Velocidade de pico | **Vel. de pico** | 107 → 65 | The measured value. Never *Vel. máx.* |
| Velocidade média | **Vel. média** | 102 → 60 | |
| Odômetro | **Odôm.** | 58 → 38 | The accent stays |
| Temperatura | **Temp.** | 74 → 35 | |
| Capacidade | **Capac.** | 66 → 38 | |
| Configurações | **Ajustes**, a word and not a cut | 83 → 43 | Only in tiles under 83dp. See below |
| Carga pronta até | **Pronta até** | 96 → 61 | Never *Carga pronta*, see section 6 |
| estimado, estimada | **estim.** | 35 | *Autonomia estim.* (102). *est.* can read as *estado* |
| aproximadamente | **aprox.** | | |
| máximo, mínimo | **máx.**, **mín.** | | |
| informações | **info**, no period | | *Info e permissões* is 102: it wraps at a space on the 64dp shortcut. The one-line 90dp tile says *Permissões* |

**Ladder examples from the catalog:**

| Key | Full | `_short` | `_compact` |
|---|---|---|---|
| `readings_odometer_km` | Odômetro total (88) | Odômetro (58) | Odôm. (38) |
| `readings_trip_km` | Distância do trajeto atual do patinete | Distância do trajeto (112) | Distância (53) |
| `readings_trip_minutes` | Tempo do trajeto atual do patinete | Tempo do trajeto | Tempo |
| `readings_maximum_speed_kmh`, after D3 only | Velocidade de pico do trajeto atual | Pico do trajeto (83) | Vel. de pico (65) |

**Tile budgets.** At the 62dp welcome tiles and 64dp shortcut tiles (12.5sp and 12sp), these fit: *Bloqueio* 51, *Ajustes* 43, *Bateria* 42, *Viagens* 47, *Luzes* 33, and *Condução* 54 for Ride settings. *Configurações* (79 at 12sp) doesn't fit either, so those tiles say *Ajustes*, which Brazilians know from iPhone and which reads as an ordinary word. Everywhere there is room, Settings is *Configurações*.

**Two tile kinds, and `_short` is the wider one.** `dashboard_model_qa_*` is the 64dp Quick access shortcut, whose label wraps with no line limit and `BREAK_STRATEGY_SIMPLE`, so a two-word label wraps at a space but any single word over 64dp breaks mid-word. `dashboard_model_qa_*_short` is the 90dp Setting tile, whose label is **one line with an end ellipsis** (`QuickAccessGrid.java:225`): it never wraps, and what doesn't fit is cut.

On the 90dp tiles, these fit: *Meus patinetes* 89.5, *Dados ao vivo* 80, *Experimental* 77, *Programação* 77 for Charge schedule, *Visor e som* 67 (the scooter's display and sound, see section 7), *Luzes de linha* 83, *Limite de carga* 88, *Pronta até* 61, *Horário limite* 79, *Preferências* 73, *Permissões* 67, *Histórico*, *Nome*. Never *Carga pronta até* (96) here: the ellipsis leaves *Carga pronta…*, which section 6 forbids. Two have no good fit and are reported, not cut: *Luzes de estrela* 94 and *PIN e proximidade* 106. Tile value lines and readings `_compact` rungs have no budget yet (*Não programado* 140 at 18sp, *Ativado • 07:00* 131), so an ellipsis can hide the time there: report these.

*Experimental* is 74 at 12sp, over the 64dp shortcut budget, and it has no accepted shorter form. The English word has the same problem. Report it rather than cutting it. *Preferências* (70) and *proximidade* (69.5) are the same case on that shortcut, new in Portuguese: report them. `FW` is not an accepted abbreviation; a version row drops the word (*Controlador*, *Proximidade*). *Instalar* (`owner_shizuku_install`, 48dp at 14sp) measures 49.3, a hair over. Report that too; it isn't to be replaced by a word that means something else.

A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Bloquear, Desbloquear, Bloqueado, Desbloqueado, Não verificado, Desconhecido, Nunca, Sempre, Alarme, PIN, Ligado, Desligado*. *Bloq.* and *Desbl.* are not allowed. If one of these doesn't fit, write the whole word and report it in your notes rather than shortening it.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Portuguese | Rules |
|---|---|---|
| Lock (action) | **Bloquear** | Button or action. Instruction: *Bloqueie* |
| Lock (noun, card title) | **Bloqueio** | Never *Trava*, *Cadeado*, *Fechadura* |
| Locked | **Bloqueado** | Agrees with *patinete*. *Bloqueada* only with a feminine subject |
| Unlock (action) | **Desbloquear** | Noun: **Desbloqueio**. Instruction: *Desbloqueie* |
| Unlocked | **Desbloqueado** | Never *aberto*, *livre*, *liberado* |
| Lock state unverified | **Estado de bloqueio não verificado** | Must never read as confirmed. Keep *não* |
| Lock state unknown | **Estado de bloqueio desconhecido** | Must never read as confirmed |
| Unknown (alone) | **Desconhecido** | *Desconhecida* with a feminine subject. Android's word |
| Unverified | **Não verificado** | Agrees in gender and number |
| Never | **Nunca** | Never *não* alone where the English says never |
| Always | **Sempre** | *Always on*: **Sempre ligada** for a light |
| Alarm | **Alarme** | Masculine. Never *alerta*: alert is **Alerta**, and they stay distinct |
| Alarm sound | **Som de alarme** | |
| Stop (a sounding alarm) | **Parar** | *Parar alarme* |
| PIN | **PIN** | Masculine: *o PIN*. Plural *os PINs*. Not *código* or *senha* |
| Lock state, PIN state, status | **Estado** | *Estado de bloqueio*, *Ler estado do PIN*, *Estado da resposta*. Never *status* |
| Handlebar PIN | **PIN do guidão** | A password is **senha**; keep them separate. *PIN unlock* is **Desbloqueio por PIN** |
| Owner key | **chave de proprietário** | A secret, not a physical key. "Not a password" is *Não é uma senha* |
| Charge limit | **Limite de carga** | Never *teto*. See below on *carga* |
| Experimental | **Experimental**, plural **experimentais** | Never *de laboratório* |
| Untested | **Não testado** | Agrees in gender and number |
| Unconfirmed | **Sem confirmação** | Takes no gender. Where a sentence needs an agreeing word (*confirmado para %1$s, não confirmado para %2$s*), **não confirmado** |
| Not confirmed | **Não confirmado** | Agrees in gender and number |
| not affiliated with NAVEE | **não afiliado à NAVEE** | Never soften it: no *não oficial*, *independente da*, *sem vínculo com*, *não associado diretamente*. In a sentence: *O Volt não é afiliado à NAVEE*. Footer: *Volt %1$s · não afiliado à NAVEE* |

**Words that look right and mean something else:**

- **Para before a noun.** Since 2009 the verb *para* has no accent, so *o freio para o patinete* first reads as "the brake for the scooter". Don't put the verb directly before a noun: *ao frear, o patinete para*.
- **After switching a feature off, name the scooter.** *até saber como ele se comporta* points back to the feature just turned off: write *como o patinete se comporta*.

- **Trava, travar, destravar.** Everyday Brazilian for a lock, and the NAVEE guide says *trancar* once too. Not here: *travar* also means an app or a device has frozen, so *O patinete travou* could read as a crash. *Bloquear* is also Android's word (*Tela de bloqueio*).
- **Bloqueado and Desbloqueado** differ only by *Des-*. Keep both whole, never truncate, and in sentences keep the full word so the difference stays visible.
- **Desligar is not desbloquear.** *Desligado* says the power is off and nothing about the lock. Don't describe an unlock as removing the lock (*Remover o bloqueio*, *Tirar o bloqueio*, *Liberar o patinete*); say *Desbloquear*. Only use *desativar o bloqueio* where the English itself says turn off.
- **Carga pronta** alone reads as "the charge has finished". The feature only plans a start, so *Carga pronta até* always keeps *até*, and the short form is *Pronta até*. In a sentence, quote the name: *“Carga pronta até” está desativado*.
- **Carga** also means a load, as in a scooter's maximum weight. A bare *Limite de carga* is clear because it sits with a percentage in battery settings. Don't place it where a weight could be meant, and don't use it for a range of kilos.
- **Sem vínculo com** sounds strong, but *vincular* here means account binding, so it says the wrong thing. Use *não afiliado*.
- **Bloqueado after a noun means blocked.** *Locked movement* means the scooter moved while locked, but *Movimento bloqueado* reads as the movement being stopped, close to the opposite: it sounds as if the lock held. Say **Aviso de movimento com o patinete bloqueado**, short **Movimento com bloqueio**. Attach *bloqueado* to the scooter, not to what it did.
- **Verificação and verificado** share a root, where the English has *check* and *unverified*. A finished compatibility check says **Verificação concluída**, never *verificado*, which would read as a confirmed result. *Verificado* is reserved for a control Volt has confirmed.
- **Seguro** means safe but also insurance, and adds reassurance. Don't add *seguro* or *garantido*, and don't add warnings the English lacks.

**Negation and states:**

- Keep every negation. *não*, *nunca*, *nem*, *nenhum*, *sem* count as negation words for the checks. A prefix alone counts only for the native words the checks list (*desconhecido*, *inválido*, *indisponível*, *impossível*, *incompatível*). *Inativo* is listed but isn't one of the approved words here.
- On and off: settings use **Ativado** and **Desativado**, lights and motors use **Ligado** and **Desligado**. Agree in gender with the thing named. Never invert them, and never use *Sim/Não* in their place.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Three speeds:** the setting is **Velocidade máxima**. A limit below it is **Limite de velocidade**. The measured value on a trip or ride is **Velocidade de pico**. Never swap them. Don't call a limit *teto*, and don't write *velocidade máxima atingida* for the measured value.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. The glossary rows `top-speed` and `maximum-speed` are written for D3's meanings, so they are on hold too: until D3 lands, the strings named here keep today's English meaning, and those rows must not be applied to them. Flag them in your notes; a judge answers fallback.
- **Dashboard meaning the scooter's own display:** `compat_question_units_mph`, `compat_question_units_kmh`, `compat_question_range`, `dashboard_model_qa_dashboard_sound` and its `_short`, and `main_group_dashboard_sound` if it arrives first say *dashboard* but mean the scooter's own display, until D3 rewrites them. Translate what they mean, with **visor do patinete**: *O visor do patinete mostra mph?*, *Visor e som*. The `dashboard` row (*Painel*, Volt's Home screen) doesn't apply to them, because the rider would be asked to confirm the wrong thing.
- **`rides_tile_max_speed`** (*Max speed*) is the measured value of a ride, so it's **Vel. de pico** (65dp at 12.5sp), never *Vel. máx.* No glossary row steers it.
- **Rides vs trips:** a recorded ride is a **viagem**. *Trip* (the distance since a reset) is a **trajeto**. A path on the map is a **rota**. Never *corrida*, which reads as a race or a taxi ride.
- **Try vs retry vs test:** Trying a control (Experimental, Controls to try) is **Experimentar**, and its results and titles use **tentativa**: *Tentativa · %1$s*, *Tentativa não concluída*. A guided test is **teste**, verb **testar**: *O Volt não vai testar*. *Test scheduled charging* is a check, so it's **Verificar o carregamento programado**. A compatibility check is **verificação de compatibilidade**. The schedule test: the action is *Verificar*, what runs and its result are **teste** (*Aguarde a conclusão do teste da programação*). An ordinary retry (*Try again*, *Try shortly*) is never *Experimentar*: use the retry phrase in Fixed words.
- **Dashboard vs display:** Volt's Home layout is the **Painel**. The scooter's own screen is the **visor do patinete**, the NAVEE guide's word. They never swap. Stores call the scooter's screen *painel* or *display*; here it's only *visor*. A screen in the app or on the phone is a **tela**. Home is **Início**.
- **Settings vs setup:** settings are **Configurações**, a single setting is a **configuração**. *Ajustes* is only the tile word from section 5. A one-time setup is **configuração inicial**. *Owner setup* is the named **configuração do proprietário**; after a possessive, **de proprietário** (*Sua configuração de proprietário*), since *sua configuração do proprietário* sounds like someone else's. Where context doesn't make it clear, say *inicial*.
- **Charge vs charging:** **carga** is the level or the amount, and **carregamento** is the act. So *Limite de carga*, *Alerta de carga*, *carga completa*, but *Carregamento programado* and *Início do carregamento*, as in NAVEE's guide. Tight rungs may use *Carga programada* and *Início da carga*. To charge is **carregar**, a charger is **carregador**. *Carregando* also means loading, so use it for a battery only where it's clearly the battery, and on a charging screen write *Aguarde…* for a load.
- **Charge done by:** the feature is **Carga pronta até**, and its time setting **Horário limite da carga**. See section 6.
- **Restore vs set back:** bringing back a backup is **Restaurar**. The button and verb *Set back* is **Reverter**. Where a try card's English says restore or restoration (`compat_restore_to`, `compat_note_restore_not_sent`, `compat_mark_restored`, `compat_restoration_confirmed`), use **restaurar** and **restauração**, which name the earlier value. Energy recovery is **Recuperação de energia**, unrelated to either.
- **Readback:** **releitura**, a neutral word, because a readback can also fail to confirm: *Releitura diferente*, *Configuração confirmada por releitura*. Never a phrase with *confirmação* inside it.
- **Rejected:** both *Rejected* and *Refused by scooter* use **Rejeitado**, and in a sentence *O patinete rejeitou*.
- **Pair vs bind vs connect:** Bluetooth pairing is **parear**. NAVEE account binding is **vincular**, the NAVEE guide's word. A live link is **conectar**. The English *Bond* in `ble_needs_bond` and `controls_refusal_proximity_bond` is **parear**, never *vincular*: *Pareie este telefone antes de ativar o desbloqueio por proximidade*.
- **Lock duration:** `compat_field_auto_lock_time` (*Auto lock time*) is **Duração do bloqueio**, how long the scooter stays locked when off. Never *bloqueio automático*, which says the scooter locks itself.
- **Untested controls:** `more_lab_untested` is **Controles não testados** while the English says *Untested controls*. *Controles para experimentar* is only for D3's *Controls to try*.
- **Owner key parts:** the file is the **arquivo de configuração do proprietário**, shortened to **arquivo de proprietário** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Roles:** owner is **proprietário**, shared rider is **usuário autorizado**. The word *role* itself is **função**. The compact role label (`more_role_shared`, English *Shared*) is **Autorizado** (64dp at 12.5sp), never *Compartilhado*, which reads as a shared scooter. Evidence and privacy lines that say *Shared* for something else (`compat_evidence_shared`) do use **Compartilhado**.
- **Levels:** Energy recovery levels are ordinary words, translated, unlike the drive modes, and agree with *recuperação*: *Baixa, Média, Alta, Desativada*. Elsewhere they agree with their own noun.
- **Kick-start speed:** **Velocidade inicial**, the NAVEE guide's phrase. Its value 0 (*Standing start*) is **Partida do zero**.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Estimativa Eco** is a range option, translated.
- **Turn signal:** **Seta**, and its sound **Som da seta**. *Seta* also means an arrow, and the NAVEE guide calls the handlebar buttons *botões das setas*. Where an on-screen arrow is meant too, name what you mean.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Acesso rápido**. Never *Configurações rápidas*: that is Android's pull-down panel.
- **Watch:** an alert's watch is **monitorar**: *Monitorando esta carga*, *Parar de monitorar*. Never *assistir* or *vigiar*.
- **Low battery:** **bateria fraca**, Android's usage. A level called Low elsewhere is *Baixo* or *Baixa*.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays (it also fits *laboratório*). TalkBack speaks **Experimental**.

## 8. Brazilian words, and European ones to avoid

| Concept | Use | Not (European or other) | Why |
|---|---|---|---|
| e-scooter | **patinete** | *trotinete*, *trotineta*, *scooter* | Owner's choice. In Brazil, *scooter* usually means a motor scooter. NAVEE's EU guide says *trotinete* |
| app | **app** | *aplicação*; *aplicativo* except when quoting a label that says it | Android's word. Both *app* and *aplicativo* appear in the NAVEE guide. *o app*, masculine |
| phone | **telefone** | *telemóvel*, *celular* | Android's word. *celular* is everyday Brazilian, but less exact in UI |
| screen | **tela** | *ecrã* | |
| Settings | **Configurações** | *Definições* | Android |
| Pair | **parear** | *emparelhar*, *sincronizar* | Android |
| handlebar | **guidão** | *guiador* | Both appear in the NAVEE guide |
| brake | **freio** | *travão* | |
| file | **arquivo** | *ficheiro* | |
| user | **usuário** | *utilizador* | |
| password | **senha** | *palavra-passe* | |
| download, save | **Baixar**, **Salvar** | *Transferir*, *Guardar* | Android |
| delete | **Excluir** | *Eliminar*, *Apagar* | |
| share | **Compartilhar** | *Partilhar* | |
| tap | **toque em** | *tocar em*, *carregar em*, *clique* | *Carregar em* would read as charging |
| press (physical button) | **pressione** | *prima* | |
| to ride | **andar** (de patinete), noun **condução** | *pilotar*, *guiar* | *modo de condução* appears in Portuguese listings |
| Done | **Concluído** | *Feito*, *Pronto* | Android. *Pronto* is reserved for the charge feature |
| report | **relatório**; *Relatar um problema* | *reportar* | |
| live | **ao vivo** only in the name *Dados ao vivo* and the status *Ao vivo*; a value is **em tempo real** (*potência em tempo real*) | *em direto* | *Ao vivo* belongs to broadcasts. The name is a debate item |
| average | **média** | | |
| tire | **pneu** | | |
| computer | **computador** | | |

**Debate items.** These are Claude's judgment calls, and a native reader should look at them first:

- **Dados ao vivo:** *Dados em tempo real* may be more natural, but it is longer than the 90dp tile's 80dp.
- **Diário do telefone** reads as a personal diary. *Registro do telefone* is an alternative, with care around *registro de atividades*.
- **Telefone and *Sobre o telefone*:** a reviewer recalls Google's current pt-BR often saying *smartphone*. Unchecked; the mock Settings path strings depend on it.
- **Espaço** for a layout slot sounds odd in *Restaurar espaço %1$s*.
- **Subjectless hints:** this guide uses the future passive everywhere, and *Os detalhes da viagem serão abertos* can sound machine-made. A reviewer proposes the descriptive *Abre…* for non-destructive TalkBack hints; rule 2 keeps it out until the debate.
- **Carga por indicador completo** is a stand-in: no percent sign can be used and the concept is hard to name.

- **Owner key: *chave de proprietário*.** *Chave* is both a physical key and a digital one (*chave de acesso*, *chave Pix*). Portuguese has no separate word, as Spanish does with *clave*. On a scooter it could suggest an ignition key. The alternative *código de proprietário* collides with the code a rider enters on the handlebar.
- **Top speed: *Velocidade de pico*.** Says highest and stays distinct from *Velocidade máxima*, but sounds a little technical. The alternative *Maior velocidade* is plainer and wider.
- **Rides: *Viagens*.** Slightly grand for a short ride, but ride apps in Brazil use it. Alternatives: *Trajetos* (taken by Trip), *Passeios* (leisure only).
- **Odometer: *Odômetro*.** Brazilian car manuals and CONTRAN often say *hodômetro*, and both spellings are accepted. *Odômetro* is shorter and common in speech. *Hodôm.* (45) would still fit.
- **Charge done by: *Carga pronta até*.** Clear, but it's a phrase rather than a name, so a sentence around it needs quotes.
- **Lock: *Bloquear* over *Travar*.** The NAVEE guide mixes *trancar*, *bloqueio* and *desbloquear*, and the official app's Portuguese is unknown.
- **PIN:** the NAVEE guide says *código* and *senha* for this code, and its menu says *Desbloqueio por código*. Volt keeps *PIN* to separate it from a backup password.
- **Patinete's gender.** A reviewer recalls, from memory and with moderate confidence, that Brazilian dictionaries such as Michaelis have recorded *patinete* as feminine, while retail usage is mostly *o patinete elétrico*. This guide keeps the masculine, but a proofreader may "correct" it. Check against VOLP.
- **Controls to try: *Controles para experimentar*.** D3 changes the English from *Untested controls*, and in Portuguese the new title drops the warning word and invites the rider to try. D3's own review note raises this softening. It waits in the pending glossary until the debate settles it.
- **Trajeto vs Rota vs Viagem.** These are near-synonyms to a Brazilian, while the app needs three distinct ideas. *Distância do trajeto* may be read as the current ride or the route rather than the distance since a reset. No better short word turned up (*Tempo parcial* reads as part-time).
- **Owner setup: *configuração do proprietário*.** A Brazilian may read it as the owner's settings rather than getting the key. The alternative is *Configuração inicial do proprietário* where width allows.
- **Shared role: *Autorizado*.** Alone as a status value it can read as permission granted, though this role has less access than *Proprietário*. The alternative is *Convidado* (61dp at 12.5sp).
- **Actual riding: *Uso real*.** It replaced *Condução real*, which sounds translated and suggests a vehicle or transit in Brazil. The old form stays in the glossary until the debate.

## 9. Android's own wording

Quote Android's settings as Android writes them: **Depuração USB**, **Opções do desenvolvedor**, **Número da versão**, **Configurações**. *Sobre o telefone* is the usual path, but phone makers rename it (Samsung adds *Informações do software*), so write generally where you can: *nas configurações, abra as informações do telefone*. Never *Modo desenvolvedor* for the settings page name, though Brazilian guides say it.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Tente novamente**; *Tente novamente em instantes*. As a button, **Tentar novamente**. Never *Experimente* or *Teste*, which read as testing something |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **sem dados**. Says only that no value came, not that anything is off or fine. Never *não informado*, *não relatado* or *não reportado* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **informado** (agrees with its noun); verb **informar**: *o patinete informa*, *Informado pelo patinete*. A claim, not a measurement. Never *relatado*, *reportado* or *confirmado*, and not *indicado*, which can read as recommended. *relatório* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **registro de atividades da NAVEE**. Never bare *registro*, which reads as signing up for an account |
| restore logging | turning that logging back to how it was (usually off) | **restaurar o registro de atividades**. Means putting that logging back as it was, usually off. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **ferramenta do Volt**; *ferramenta* alone once the screen makes clear which. Never *assistente*. The script on the computer is the different **ferramenta de captura** |
| stock charger | the charger that came with the scooter | **carregador original**. Never *padrão* |
| high-speed charger | a faster charger the rider may own | **carregador rápido**; alone, *Rápido*. Never *de alta velocidade*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **lacuna**. Means no data was recorded, not that power or the ride stopped. Never *falha* or *buraco* |
| elevation | height above sea level on a route | **altitude**. Never *elevação*, which reads as a slope |
| current direction | which way battery current flows | **sentido da corrente**. Never *direção*, which reads as a route or the steering |
| throughput | how much energy has passed through the battery | **fluxo de energia**; *fluxo* once the screen has said energy. An amount of energy that passed through, not a rate and not what the battery holds now. Never *taxa de transferência* |
| battery gauge | the scooter's own battery percentage reading | **indicador de bateria**; *indicador* alone where the screen is about the battery. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **bruto** (agrees with its noun: *valor bruto*, *leitura bruta*); as a tag **BRUTO**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Última leitura**; with a value, *Última leitura: %1$s*. The data may be old, so never word it as current. Never *visto por último* |
| add | adding a widget, scooter or item | **adicionar**: *Adicionar widget*, *Adicione outro patinete*. Never *agregar* or *incluir* |
| enter (typing) | typing a value | **digitar**: *Digite um novo PIN*. Never *inserir* or *introduzir*, and never *entrar*, which reads as signing in |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **app**, masculine: *o app*, *o app NAVEE*, *o app oficial*. *aplicativo* only when quoting a label that says it. Never *aplicação* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **conecte o patinete**, *Conecte o patinete primeiro*, *Conectar o patinete novamente*. Never *conecte-se*, which reads as going online. The button stays *Conectar*; plugging in says *conecte o carregador* |
| set a value | one verb for setting a value on the scooter | **definir**: *Definir PIN*, *Definir %1$s*. Never *fixar* or *ajustar*. The noun stays *configuração*, never *definição*, and setting something back stays *Reverter* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Future passive, in dialogs and hints alike: *Esta viagem será excluída*, *O mapa será aberto*. Where a placeholder names the thing, *Isso vai excluir %1$s*, which needs no agreement. Never *Exclui…*, *Exclua…*, *Abre…* or *Abra…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Detalhes da conexão*, *Restauração*. If the title is an action with no good noun, the infinitive: *Verificar o carregamento programado*. Never the imperative, except a blocking heading that is itself an instruction to the rider: *Conclua a limpeza primeiro* |
| setting the clock | the clock is a value set on the scooter | **definir o relógio**, same as any set value. Natural *acertar o relógio* is a debate item |
| prompt (a system dialog) | a request on the phone, not a warning | **solicitação**. *Aviso* is a warning. The scooter's *Som de aviso* stays |
| time window | the period a figure covers | **período**. Never *janela*, which reads as a UI window |
| clear (results or history) | removing saved results | Action **limpar**, result **removido**: *%1$d tentativa removida*, *Histórico removido*. Never *limpa*, which reads as "clean" |
| fit (chart, map) | zoom to show the whole thing | Button **Enquadrar**; description *Mostrar … inteira no gráfico*. Never *ajustar* |
| only | | **somente**, never *apenas* |
| Location (permission or sensor) | | **Localização**, never bare *Local* |
| Allow Nearby devices | the Android permission | **Permitir acesso a dispositivos por perto**. The step and permission name stay *Dispositivos por perto* |
| rate (charging estimate) | how fast the battery is charging now | **ritmo**: *Ritmo atual*, *estimativa pelo ritmo*. *Taxa* only in *Taxa de autodescarga* |
| per full gauge | energy across the whole gauge scale | **indicador completo**; in a sentence *a cada 100 por cento do indicador*. No `%` sign: these English strings have none, so a lone % fails the format check |
| charges (counted events) | | **carregamentos**. *Cargas* only on a tight `_compact` rung |
| (raw) tag | | Full rung **(valor bruto)**, which needs no agreement. Short rung **(bruto)** or **(bruta)**, agreeing with the noun. After a placeholder, invariable *%1$s (bruto)*, because the placeholder is the value |
| stored, finish | | **salvo** for stored trips; **término** for a charge's finish, never *fim* |
| hill or slope assist | | **Assistência em subidas**, on every key |
| No X yet | | **Ainda não há X**: *Ainda não há viagens*. Not *Nenhuma viagem ainda*. Also: no trailing *em vez disso* on a label, and no transitive verb left without an object (*conecte-o*) |
| chart name placeholder | %1$s is a capitalized name | Use a colon: *Gráfico de valores medidos: %1$s*. Never *em %1$s*, which reads as a unit |

## 10. Sources consulted

**NAVEE's official Portuguese: found in one printed guide, not in the app.** The official app's Portuguese strings were **not found**: no public page shows them for charge limit, owner key, experimental features or most controls, and no Brazilian NAVEE site or store turned up. Those words are Claude's own proposals, and the official app may differ. No APK was downloaded.

- [NAVEE XT5 series EU User Guide](https://wheelyshop.se/cdn/shop/files/NAVEE_XT5_series-EU-User_Guide.pdf), a NAVEE-branded multilingual PDF on a retailer's site, Portuguese parts read directly as extracted text. It's an unedited mix of European and Brazilian Portuguese (*guiador* and *guidão*, *travão* and *freio*, *aplicação* and *aplicativo*), so it isn't a style model. Used for: *aplicativo NAVEE*, *app NAVEE*, *Vincular meu dispositivo*, *Desbloqueio por código*, *Bloqueio por Bluetooth*, *desbloqueio por proximidade Bluetooth*, *carregamento programado*, *horário de carregamento*, *visor*, *velocidade inicial*, *limite de velocidade*, *preferências de condução*, *botões das setas*, *30%*, *3 km/h*. It also prints *trancar*, *trotinete* and *senha*, which this guide rejects for the reasons in sections 6 and 8. It doesn't name cruise control, energy recovery, lights, range or charge limit in Portuguese.
- Retailer listings and search snippets, weaker evidence: [Informática Barata, GT3 (pt)](https://informaticabarata.com/pt/scooters-eletricos/patinete-eletrico-navee-gt3) (snippet only, the page returned 403: *Modos de condução*, *aplicativo NAVEE*, *velocidade máxima*); [PcComponentes, G5 (pt)](https://www.pccomponentes.pt/navee-g5-400w-trotinete-electrica) (*trotinete*, European).
- Brazilian scooter usage, from search snippets only: [Extra](https://www.extra.com.br/patinete-eletrico-maxshot-cinza-e-vermelho-motor-350w-bateria-36v-52ah-pneu-216cm-dobravel-modelo-v7/p/1582628233), [Casas Bahia](https://www.casasbahia.com.br/patinete-eletrico-maxshot-cinza-escuro-vermelho-motor-500w-suspensao-dupla-pneu-solido-dobravel-v1spro/p/1582628185), [Magazine Luiza](https://www.magazineluiza.com.br/patinete-eletrico-xiaomi-m365/p/5541323/es/eptn/), [Mercado Livre](https://produto.mercadolivre.com.br/MLB-2697255300-patinete-eletrico-85-lindo-30kmh-ate-120kg-tipo-xiaomi-_JM) (*patinete elétrico*, *guidão*, *painel*, *display*, *controle de cruzeiro*, *piloto automático*, *freio regenerativo*, *autonomia*, *farol*). Press: [Canaltech](https://canaltech.com.br/produtos/tudo-o-que-voce-precisa-saber-para-utilizar-um-patinete-eletrico-141995/) (*um patinete*), [Olhar Digital](https://olhardigital.com.br/noticia/uber-entra-para-o-mercado-brasileiro-de-patinetes-eletricas/93859) (*patinetes elétricas*, the feminine this guide rejects).
- Android's own Portuguese, read from AOSP through a summarizing fetch, so quotes may not be exact: [SettingsLib pt-rBR](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-pt-rBR/strings.xml) (*Depuração USB*, *Opções do desenvolvedor*, *Parear*, *Desconectado*, *Ativado*, *Desativado*); [SystemUI pt-rBR](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/res/values-pt-rBR/strings.xml) (*Permitir a depuração USB?*, *Sempre permitir a partir deste computador*); SettingsLib also gives *Revogar autorizações de depuração USB*; [Settings pt-rBR](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-pt-rBR/strings.xml) (*Configurações*, *Desconhecido*, *Parear novo dispositivo*, *Tela de bloqueio*, *Sempre*, *Nunca*, *Número da versão*). *Sobre o telefone* comes from third-party guides ([Tecnoblog](https://tecnoblog.net/responde/como-ativar-o-modo-desenvolvedor-no-android/), [anexplorer](https://anexplorer.io/pt/devmode/android)); its AOSP value wasn't reached.
- [Microsoft Portuguese (Brazil) style guide](https://download.microsoft.com/download/8/e/3/8e349e32-9eb9-4b63-9909-7586b94a24dd/por-bra-StyleGuide.pdf), read as extracted text: abbreviations end in a consonant and keep accents, a no-break space between number and unit, avoid gendered references, no capital after a colon. **This guide departs from it once:** Microsoft follows the source's capitals in UI options, and Volt uses sentence case, as Android does.
- Numbers: the [Ipea editorial manual](https://www.ipea.gov.br/manualeditorial/padroes/padroes-textuais/abreviaturas.html) (no space before %, space before other units; it also writes hours without a space, and this guide keeps SI's space for *2 h 5 min*), and [Inmetro's unit table](https://www.gov.br/inmetro/pt-br/centrais-de-conteudo/publicacoes/documentos-tecnicos-em-metrologia/quadro-geral-de-unidades-de-medida-no-brasil.pdf). Both from search snippets only. No ABNT text could be read.
- **From memory, not checked:** four-digit grouping as CLDR's pt-BR rule; `one` covering 0 and 1 is CLDR's and confirmed by `check.py`; *Luz de rodagem diurna* as the CONTRAN term; *hodômetro* in car manuals; *bateria fraca* and *Concluído* as Android's wording; the words in section 8's table not named above. No native speaker has checked any of it.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`. Glyphs checked with its `Font.missing`: Saira has every Portuguese letter in both cases, “ ” ‘ ’ · … › ° and U+00A0, so `font` stays `saira`.
