# Volt style guide: Russian (ru)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in the last section; the three-vendor debate decides later. No native speaker has reviewed it. It uses the D3 English terms (approved, not merged), so "owner key", "shared rider" and "Maximum speed" are the source meanings even where `res/values` still shows older words, apart from the holds in Scooter vocabulary. Russian is wave 3 and the owner's direction is non-Latin only if easy, so this setup is a proposal to report to the owner.

**Font.** `font=system`: Saira has no Cyrillic, so `check.py` measures no widths for this language and fit is checked on a device. Follow the shortening rules below without being able to measure them.

## Register and form of address

- Use **вы**, always lower case, as in Android's own Russian. Never ты. Capital *Вы* belongs to personal letters.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone, no slang (*самик*, *зарядник*).
- **Buttons, toggles and menu actions use the infinitive:** *Подключить*, *Сохранить*, *Заблокировать*. **Instructions in sentences use the вы imperative:** *Подключите зарядное устройство*, *Подключите самокат снова, чтобы проверить*. Don't mix the two in one string.
- **Screen and section titles use a noun:** *Резервное копирование и восстановление*. If no good noun exists, use the infinitive: *Проверить зарядку по расписанию*. Never an imperative in a title.
- **Subjectless English hints** ("Removes…", "Opens…") say what will happen, in the passive future or an impersonal form: *Поездка будет удалена*, *Откроется карта*. Never *Удалите поездку*, which reads as a command to do it.
- **What happened** uses a short participle or a neuter status: *Сохранено*, *Не удалось прочитать*. Prefer *Не удалось…* for a failure.
- вы forms carry no gender, so a sentence about the rider needs no gendered word: *Вы подключили самокат*. Role nouns stay as the glossary has them (*владелец*).

## Punctuation and quotes

- Quotes: **«…»** first, **„…“** inside. Never straight quotes.
- A sentence that names a button, tab or screen puts its exact label in «…», in the nominative, with a noun before it where a case is needed: *Нажмите «Подключить»*, *в разделе «Экспериментальные функции»*. A label in quotes doesn't decline.
- **Ё is always written:** *отчёт*, *подключён*, *сопряжён*, *ещё*, *всё*. *Все* and *всё* are different words, and a glossary form won't match without it.
- Sentence-final period goes outside closing quotes and parentheses. No period on buttons, labels, titles or chips, unless the English sentence has one.
- Dash: spaced **—** in a sentence (*Volt — это…*). Ranges: unspaced **–** (*10–20*). Hyphen only inside a word (*круиз-контроль*). Keep the English's `·` separators and `…` as written. A verb in progress keeps `…`: *Подключение…*
- After a colon, continue in lower case: *Последнее состояние: заблокирован*.
- Commas are required before *что, чтобы, если, когда, который, потому что*. Volt's short labels mostly avoid them; sentences don't.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.
- **Never join Latin and Cyrillic with a hyphen.** `check.py` refuses it as mixed script, even where a Russian writer would use it: not *PIN-код*, *USB-кабель*, *GPS-трек*, *Bluetooth-соединение*. Write *PIN*, *кабель USB*, *трек GPS*, *соединение Bluetooth*. **Debate item:** this is a tool limit, not Russian usage; *PIN-код* and *USB-кабель* are the standard forms and Android writes *PIN-код*, so the debate may ask for an allow-list.

## Capitalization of buttons, labels and titles

- **Sentence case everywhere:** button *Сохранить изменения*, label *Предел заряда*, title *Экспериментальные функции*. Only the first word and names take a capital. Never English Title Case, even when the English has it (*Walk Assist* → *Помощь при ведении*).
- A word after a colon is lower case unless it is a name. Feature names are lower case mid-sentence: *включите зарядку по расписанию*.
- Days, months and languages are lower case.
- Keep capitals and Latin spelling on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, TCS, ADB. Drive modes are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *режим Sport* where the English says Sport. The same goes for the names *Eco*, *Turbo* and *Boost*. Other level names are translated: see Levels.
- Don't transliterate names (*Вольт*, *Нави*).

## Numbers, dates and units

- Decimal comma in text you write. From four digits a no-break space (U+00A0) groups thousands (*1 234,5*, *12 345,6*), the same as Volt's `Fmt`, which prints live numbers with Java's Russian data. Never rewrite a placeholder's number or add separators around one.
- Percent takes a no-break space (U+00A0), never a plain space or U+202F: `%1$s %%` gives *85 %*. It follows GOST 8.417 and the phone's own Russian number format. Many Russian publishers write *85%* without the space. **Debate item.** The rule covers `formatted="false"` text too, whose literal is `100 %`.
- Numbers in the English stay as digits. Don't write them out or change them.
- Unit symbols take U+00A0 after a number or placeholder: *25 km/h*, *36 V*, *25 °C*. Owner default: SI and imperial symbols stay as written, so `km/h`, `km`, `mi`, `mph`, `V`, `A`, `W`, `Wh`, `Ah` stay Latin, with no plural or period. Unit words are translated: *километры*, *мили*. **Debate item:** Russian readers and GOST expect *км/ч*, *км*, *В*, *А*, *Вт·ч*, and a speed in *km/h* can look like an untranslated string. The reviewer recommends all-Cyrillic symbols. That needs an owner decision and a change to `km-h`'s do-not-translate row.
- **Time symbols follow the same decision:** they stay as written (`min`, `h`, `s`, including `units_minute`) until that debate item is settled. Russian *ч*, *мин*, *с* alone would put two alphabets in one row (*25 km/h · 2 ч 5 мин*), so don't adopt them without the rest. If the debate chooses Cyrillic, write them without a period, as Microsoft's Russian guide lists them: *2 ч 5 мин*. The owner default keeps symbols, and strings_units.xml says a change needs the glossary to say so.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 марта*. Don't write *a.m./p.m.*: Russian uses 24 hours.
- **Plurals** have four categories, each grammatical, each with its own noun form:

| Category | Numbers | Example |
|---|---|---|
| `one` | 1, 21, 31, 101 | `%1$d минута` |
| `few` | 2–4, 22–24 | `%1$d минуты` |
| `many` | 0, 5–20, 25–30, 100 | `%1$d минут` |
| `other` | fractions; integers never reach it | `%1$d минуты` |

`one` also covers 21 and 31, so it always keeps the count placeholder, never *Одна минута*. Agree adjectives and verbs too: *Осталась %1$d минута*, *Осталось %1$d минут*. Prefer a form that doesn't change: *Осталось минут: %1$d*.

## How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Russian runs longer than English and nothing here can be measured yet. As an estimate only, a 90dp tile at 12.5sp holds about 13 Cyrillic letters per line. Try these in order:

1. Drop words the screen already supplies: *Настройки батареи* → *Батарея* on a settings screen.
2. Use a shorter common word: *Текущие показатели* → *Показатели*.
3. Wrap at a space where the English also wraps.
4. Use an abbreviation from this table. Nothing else: no invented cuts such as *Настр.*, *Эксп.*, *Батар.*, *Зар.*

| Full | Short | Compact | Note |
|---|---|---|---|
| Максимальная скорость | **Макс. скорость** | **Макс. скор.** | The setting only |
| Пиковая скорость | **Пик скорости** | **Пик скорости** | The measured value. Never *Макс.* anything |
| Средняя скорость | **Ср. скорость** | **Ср. скор.** | |
| Одометр | **Одометр** | **Одометр** | Keep the full word on every rung. *Одом.* is rare; use it only if a device check shows overflow |
| Текущий пробег | **Тек. пробег** | **Пробег** | Distance only. Bare *Пробег* reads as total mileage, so it is for compact rungs only |
| Температура батареи | **Темп. батареи** | **Темп.** | Keep the period: *темп* alone means pace |
| Напряжение батареи | **Напряжение** | **Напряж.** | |
| Заряд батареи | **Заряд** | **Заряд** | |
| Состояние батареи | **Состояние** | **Состояние** | Battery health |
| Экспериментальные функции | **Эксперимент** | **LAB** | LAB is the compact badge only; TalkBack says *Экспериментальная функция* |
| Количество | **Кол-во** | **Кол-во** | Hyphen form, no period |
| Следующий, предыдущий | **След.**, **Пред.** | | |
| максимум, минимум | **макс.**, **минимум** | | Never *мин.* for minimum: it reads as minutes |
| включено, выключено | **вкл.**, **выкл.** | | Android's own form, for non-safety labels only |
| Подключение | **Подключение** | **Подкл.** | Never for a state: see below |

An abbreviation ends in a period, and the period counts toward the width. Cut after a consonant, never after a vowel: *Напряж.*, not *Напря.*

**Never abbreviate:** *Заблокировать, Разблокировать, Заблокирован, Разблокирован, Не подтверждено, Не проверено, Не опробовано, Неизвестно, Никогда, Всегда, Сигнал тревоги, Подключён, Отключён*. *Блок.*, *Заблок.*, *Разблок.*, *Откл.* and *Подкл.* for a state are not allowed. If one of these doesn't fit, write the whole word and report it in your notes.

## Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Russian | Rules |
|---|---|---|
| Lock (action) | **Заблокировать** | Instruction: *Заблокируйте самокат* |
| Lock (noun, card title) | **Блокировка** | Never *Замок*, a physical lock |
| Locked | **Заблокирован** | Agrees with *самокат*. *Заблокировано* in a neutral status, *заблокирована* with a feminine subject |
| Unlock (action) | **Разблокировать** | Noun: **Разблокировка**. Instruction: *Разблокируйте самокат* |
| Unlocked | **Разблокирован** | Never *открыт*, *свободен* or *незаблокирован* |
| Lock state unverified | **Состояние блокировки не подтверждено** | Must never read as confirmed. Keep *не* |
| Lock state unknown | **Состояние блокировки неизвестно** | Must never read as confirmed |
| Last known: locked | **Последнее состояние: заблокирован** | It is old data; never word it as current |
| Unknown (alone) | **Неизвестно** | *Неизвестный* with a masculine noun where the English has one |
| Unverified | **Не проверено** | Agrees where a noun is present: *не проверена* (*функция*) |
| Untested | **Не опробовано** | *Не опробована* (*функция*). Never *Не протестировано*: test is a different word |
| Unconfirmed | **Нет подтверждения** | The scooter acknowledged, the value was not kept or read back. Never *Без подтверждения*: it reads as "no confirmation needed". Keep it distinct from *Не подтверждено* |
| Not confirmed | **Не подтверждено** | Never reads as confirmed |
| Never | **Никогда** | Never bare *не* where the English says never |
| Always | **Всегда** | *Always on*: **Всегда включена** for a light |
| Alarm, Alarm sound | **Сигнал тревоги** | Never *будильник* (a clock) or *сигнализация* (an anti-theft system). Alert is **оповещение**, notification **уведомление**: all three stay distinct. **Debate item:** *Сигнал тревоги* may be too strong for a charge-level sound; *Громкий сигнал* is the alternative |
| Stop (a sounding alarm) | **Стоп**; **Выключить сигнал** where an alarm sheet has room | *Остановить* asks for an object, and a rider may supply *зарядку*, believing charging stopped when only the sound did. Keep *Остановить* for recording and setup. Notification buttons (`service_action_stop`) are always *Стоп* |
| PIN | **PIN** | Latin, no hyphen, doesn't decline: *введите PIN*, *для PIN*, *по PIN*. Not *код* or *пароль*. A password is **пароль**; keep them separate |
| Handlebar PIN | **PIN на руле** | |
| Owner key | **ключ владельца** | A secret. Not physical; the context says so. "Not a password" is *Это не пароль* |
| Charge limit | **Предел заряда** | Never *потолок*. **Debate item:** the alternatives are *Лимит заряда* and *Ограничение заряда*, which would match *Ограничение скорости*; a reader sees no reason for two words for limit |
| Experimental | **Экспериментальный**, **экспериментальные функции** | |
| not affiliated with NAVEE | **не связан с компанией NAVEE** | Agrees with Volt: *Volt 1.0 · не связан с компанией NAVEE*. *Приложение не связано с компанией NAVEE*. Bare *не связан с NAVEE* can flash as a connection state, because *связь* is the Bluetooth link. Never soften it: no *неофициальный*, *независимый от*, *не напрямую связан* |

- **Look-alikes.** *Заблокирован* and *Разблокирован* differ by the first letters only, and so do *Заблокировать* and *Разблокировать*. Keep both whole, never truncate, and never use the bare stem (*блокирован*). *Блокировка* in a label means the feature, not a state, so never use it alone as the state *Locked*.
- **The lock word never turns into unlock by a verb.** *Снять блокировку*, *отключить блокировку*, *убрать блокировку*, *открыть самокат* all fail or mislead: say *Разблокировать*. Only where the English itself says turn off a feature may *выключить* appear.
- **Words that look right and mean something else:** *открыт* is not unlocked. *Закрыт* is not locked. *Отключён* (disconnected) is not *выключен* (powered off) and neither is *разблокирован*. *Отключить* means disconnect here; turning a setting off is **выключить**.
- Keep every negation. *не*, *нет*, *без*, *никогда* count as negation words for the checks; *не-* written solid only counts for listed words, so write *не проверено* apart, as the table does.
- On and off: settings and lights use **Включено** and **Выключено**, agreeing with the thing named (*включена*, *выключен*). Never invert them, never use *Да/Нет* in their place. Use *вкл.* and *выкл.* only outside safety keys, because they differ by one letter at a glance.
- Don't add reassurance the English lacks (*безопасно*, *гарантированно*), and don't add warnings it doesn't have.

## Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Scooter:** **самокат** everywhere. *Электросамокат* is a sales word; don't alternate. Never *скутер*, a moped.
- **Three speeds:** the setting is **Максимальная скорость**. A limit below it is **Ограничение скорости**. The measured value on a trip or ride is **Пиковая скорость**. Never swap them. **Debate item:** *пиковая* is Claude's word; *наибольшая* is the alternative, and trackers often say *макс. скорость*, which this app can't use.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. The rows `top-speed` and `maximum-speed` already carry D3's meanings, and the strings above follow today's English until D3 lands. Flag them in your notes; a judge answers fallback.
- **Not held, translate now:** `rides_tile_max_speed` (English *Max speed*, D3 *Top speed*) is the measured value: **Пиковая скорость**, short **Пик скорости**, never *Макс. скорость*.
- **Rides vs trip:** a recorded ride is a **поездка**. *Trip* depends on what is measured. Distance is **Текущий пробег**, short *Тек. пробег*, with bare *Пробег* on compact rungs only. Time, speed, overview and counts use **поездка**, or **текущая поездка** where the ride history could be confused: *Время поездки*, *3 поездки*. A map path is a **маршрут**. Range is **запас хода**, never *пробег*.
- **Charge:** the battery's level is **заряд**. The process is **зарядка**. The charger is **зарядное устройство**. *Предел заряда*, but *зарядка по расписанию*.
- **Try vs retry vs test:** trying a control is **Попробовать**; its result is **попытка**. An ordinary retry is never *попробовать*: **Повторите попытку**, as a button **Повторить**. A guided test is **проверка**, verb **Проверить**. Compatibility check is **проверка совместимости**.
- **Pair vs bind vs connect:** Bluetooth pairing is **сопряжение** (*Выполнить сопряжение*, state *Сопряжён*). NAVEE account binding is **привязка** (*привязать аккаунт*). A live link is **подключение** (*Подключить*). The object is the scooter: *Подключите самокат*. Android's Russian says *Подключить* for pairing as well; Volt can't, because it must keep them apart. **Debate item.**
- **Disconnect vs off:** *Отключить* ends the Bluetooth link. *Выключить* turns a setting or light off.
- **Dashboard vs display:** Volt's Home layout is the **Панель**. The scooter's own screen is the **дисплей самоката**. They never swap. Some English still says dashboard for the scooter's screen until D3 merges. Translate these now and expect a glossary warning: `compat_question_units_mph`, `compat_question_units_kmh` and `compat_question_range` use *дисплей самоката*; `dashboard_model_qa_dashboard_sound`, its `_short` and `main_group_dashboard_sound` use *Дисплей и звук*.
- **Settings vs setup:** settings are **Настройки**. A one-time setup is **настройка** too, so name what is being set up: *Получение ключа владельца*.
- **Restore vs set back:** bringing back a backup is **Восстановить**. Undoing a try is **Вернуть**, with a noun where needed: *Вернуть прежнее значение*. Energy recovery is **Рекуперация энергии**, unrelated to either; *Рекуперация* alone once the screen has said it. Reviews and retailers use this word.
- **Readback:** **контрольное считывание**, a neutral word, because a readback can also fail to confirm.
- **Rejected:** on an Experimental try, *Rejected* is **Отклонено**, *Refused by scooter* **Отклонено самокатом**. On the capacity card it means rides left out of the estimate (rule 14): **Не учтено в оценке**, with the count *Не учтено поездок: %1$d*. Never *Отклонено* there.
- **Roles:** owner is **владелец**. Shared rider is **пользователь с общим доступом**. The compact role label (`more_role_shared`, English *Shared*) is **Общий доступ**. **Debate item:** long, but no shorter Russian word says it without inventing a role.
- **Owner key parts:** the file is **файл с ключом владельца**, shortened to **файл владельца** on onboarding steps. `navee-owner-setup.json` stays as written. It carries the secret, so never *файл настройки владельца*, which reads as a harmless settings file. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels are translated and agree with *рекуперация*: *Выключена, Низкая, Средняя, Высокая*.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Оценка Eco** is a range option: translate *estimate*, keep *Eco*.
- **Experimental LAB badge:** **LAB** stays as a Latin badge; a Cyrillic cut has no accepted form. **Debate item.** The word *control* becomes **функция** here. Today's *Untested controls* is **Неопробованные функции**. D3's *Controls to try* is **Функции на пробу**. Never *Можно попробовать*: it reads as permission, on a heading for controls Volt has no evidence for.
- **Charge done by:** the feature name is **Зарядка к сроку**, its time setting **Срок окончания зарядки**. The feature only plans a start, so never word it as finished. In a sentence, quote the name: *«Зарядка к сроку» выключена*.
- **Quick settings:** the Dashboard's shortcut row is **Быстрый доступ**. Never *Быстрые настройки*: that is Android's pull-down panel.
- **Parked:** means stationary: **остановлен**. For *while parked*, write *пока самокат стоит*, *на стоянке* or *во время стоянки*; all are approved. Never *припаркован*, and not *неподвижен*, whose *не-* raises a polarity warning.
- **App:** always **приложение**. *Приложение NAVEE*, *официальное приложение*.
- **Tap, press:** **нажмите** for both.
- **Android's own words:** **Отладка по USB**, **Для разработчиков** (some phones say *Параметры разработчика*), **Номер сборки**, **Настройки**. *О телефоне* is from memory, and phone makers rename it, so write generally where you can: *в настройках откройте сведения о телефоне*.
- **Other terms for the debate,** all understandable, none changed yet: *Контроль пробуксовки* for *Контроль тяги*; *Торможение при превышении скорости* for *Активный контроль превышения скорости*, which reads as monitoring; *Помощь мотора* for *Усиление мощности*, which is close to Boost; *Режим экономии заряда* for *Режим низкого заряда*, which reads as a condition; *Своё ограничение скорости* for *Пользовательское ограничение скорости*; *Сработало на этом самокате* for *Сработало здесь*.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.

### Names and placeholders

- Latin names don't decline. Put a noun before the name and decline the noun: *в приложении Volt*, *режим Sport*, *в режиме Sport*, *файл GPX*. Bare *в Volt* is fine; *в Volte* is not.
- A value in `%1$s` can't decline, so the value must stand alone in the nominative. Use a label form:

| English | Russian | Not |
|---|---|---|
| Keep %1$s | Оставить значение: %1$s | Оставить %1$s |
| Changing to %1$s… | Новое значение: %1$s… | Изменение на %1$s… |
| Connecting to %1$s | Подключение: %1$s | Подключение к %1$s |
| Move %1$s | Переместить: %1$s | Переместить %1$s |

- Nothing may agree with a value: no adjective or verb gender before `%1$s`.

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
14. **English that misleads.** These read wrongly word for word, so translate what they mean, whatever the English says. "Rejected" on capacity means rides left out of the estimate. "Cleanup" of logging means putting logging back as it was, and logging may still be on. "Restore" in Experimental means setting a value back, not restoring a backup. "Full" of a battery means fully charged, never a bare word that could mean something else. "Decimal digits" means number digits. "OEM feature domain" means outside what the official app allows. "Stop the scooter" means bring it to a halt, not switch it off. "Below 3 km/h" on a kick-start setting means a setting value, not riding slowly.
15. **Tags after a value.** A tag such as "(raw)" or "(est.)" follows a value you can't see, so it must not need to agree with it. Use a form that needs no agreement, such as "(raw value)".
16. **When the check refuses good wording.** Don't change the meaning to please the check, and don't widen a glossary row just to make a string pass unless the wording is one this guide approves. Find a wording that passes and keeps the meaning, and list the better wording in your notes for the debate.

### Fixed words

| English concept | Meaning | This language |
|---|---|---|
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Повторите попытку**; *Повторите попытку чуть позже*. As a button, **Повторить**. Never *Попробуйте ещё раз* or *Попробовать снова*, which read as trying a control |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **нет данных**. Says only that no value came, not that anything is off or fine. Never *не сообщается*, *не сообщено* or *не передано* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **по данным самоката**; verb **показывает**: *самокат показывает 80 %*. A claim, not a measurement. Never *подтверждено*, *измерено* or *сообщено*. *отчёт* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **журнал отладки NAVEE**. Never *регистрация*, which reads as signing up for an account |
| restore logging | turning that logging back to how it was (usually off) | **вернуть журнал отладки в прежнее состояние**. Means putting that logging back as it was, usually off; logging may still be on afterwards. Never *восстановить журнал*: *Восстановить* is for a backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **служебный процесс Volt**; *процесс* alone once the screen makes clear which. Never *помощник* or *ассистент*. The script on the computer is the different **программа извлечения** |
| stock charger | the charger that came with the scooter | **комплектное зарядное устройство**; alone, *Комплектное*. Never *стандартное* |
| high-speed charger | a faster charger the rider may own | **быстрое зарядное устройство**; alone, *Быстрое*. Never *скоростное*: this is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **пропуск в записи**; *Пропуски в записи* in a list. Means no data was recorded, not that power or the link was cut. Never *разрыв* or *обрыв* |
| elevation | height above sea level on a route | **высота**: *Высота не измерена*. Never *подъём*, which is a climb |
| current direction | which way battery current flows | **направление тока**. Never bare *направление*, which reads as a route |
| throughput | how much energy has passed through the battery | **прошедшая энергия**; *энергия, прошедшая через батарею* in a sentence. An amount, not a rate. Never *пропускная способность* |
| battery gauge | the scooter's own battery percentage reading | **индикатор заряда**; *индикатор* alone where the screen is about the battery. The scooter's own figure, not a measurement. Never *датчик*, which reads as a sensor |
| raw | an unprocessed value, also used as a short tag | **без обработки**, which needs no agreement: *(без обработки)* as a tag, *значение без обработки* in a sentence. Where the English writes the badge RAW, keep **RAW** like LAB. **Debate item:** *без* counts as a negation word, so a check may warn |
| last seen | when Volt last had data from the scooter | **Последние данные**; with a value, *Последние данные: %1$s*. The data may be old, so never word it as current. Not *Последнее состояние*, which is the last known state |
| add | adding a widget, scooter or item | **добавить**: *Добавить виджет*, *Добавьте ещё один самокат* |
| enter (typing) | typing a value | **ввести**: *Введите новый PIN*. Never *набрать* or *вбить* |
| the app | one word for "app" used everywhere; name when "application" is allowed, if ever | **приложение**, neuter: *приложение NAVEE*, *официальное приложение*. Never *апп* or *прога* |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter is the object: **подключите самокат**, *Сначала подключите самокат*, *Подключить самокат снова*. Never *подключитесь*, which reads as going online. The button stays *Подключить*; plugging in says *подключите зарядное устройство* |
| set a value | one verb for setting a value on the scooter | **задать**: *Задать PIN*, *Задать значение: %1$s*. Never *установить*, which also means installing an app. The noun stays *настройка*, and setting something back stays *Вернуть* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Passive future or impersonal, in dialogs and hints alike: *Поездка будет удалена*, *Откроется карта*. Never *Удалите…* or *Откройте…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Резервное копирование и восстановление*. If the title is an action with no good noun, the infinitive: *Проверить зарядку по расписанию*. Never the imperative |
| sign (+ or −) | whether a value is positive or negative; never a calque meaning a signal or a road sign | **знак (плюс или минус)**; *знак значения* where *знак* alone could read as a symbol. Never *сигнал* or *признак* |
| window (of time) | the span of time a chart or widget covers; never reads as a window on screen | **период**: *Период времени*, *Период данных поездки*. Never *окно* |
| connected history | readings stored while connected, not a log of connections | **История показателей**. Never *История подключений*, which reads as a log of connections |
| app preferences | Volt's own settings section and its shortcut, the same word on both; scooter preferences saved in Volt keep a different name | **Настройки приложения**, the same on the shortcut and the section. *Параметры самоката* stays for scooter preferences saved in Volt, which aren't the settings on the scooter |
| a value you can't see | a question or sentence ending in an on, off or level word: name it without any agreement, with one example | Name it as a label: *При включённом поворотнике звук в таком состоянии: %1$s?* |
| NAVEE, meaning the app | the English says NAVEE alone for the app: write "the NAVEE app" if bare NAVEE would need gender, case or an article | **приложение NAVEE**: *Приложение NAVEE не подключилось*. Never bare *NAVEE* with a verb that must agree with it |

## Sources consulted

**NAVEE's official Russian: not found.** No public page, manual or app listing showing the official app's Russian strings was reachable, so *every safety term here is Claude's own proposal*, and the official app may differ. `ROADMAP.md`'s match-the-official-wording rule is unmet for this language. No APK was downloaded.

- Android's Russian, read from AOSP: [SettingsLib values-ru](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-ru/strings.xml) (*Отладка по USB*, *Для разработчиков*, *Подключить* for pairing) and [Settings values-ru](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-ru/strings.xml) (*Настройки*, *Номер сборки*, *Добавить устройство*, *Вкл.*, *Повторите попытку*, *PIN-код*). Settings writes quotes as straight quotes, so it doesn't back the «…» rule.
- [Microsoft Russian Localization Style Guide](https://download.microsoft.com/download/c/8/a/c8ab1532-a15d-4f94-96c2-9740bc230757/rus-rus-StyleGuide.pdf), sections read: abbreviations (*ч*, *мин*, *с*; don't abbreviate arbitrarily), sentence case, no capital after a colon, true ellipsis, en dash, no-break spaces (*10 000*, *10 %*, *5 °C*). It prescribes straight quotes in software UI; this guide follows Russian typography and `quote_style` instead. **Debate item.**
- Java's CLDR data for `ru`, run locally: `85 %` and `1 234,5`, both with U+00A0.
- Percent spacing: [ГОСТ 8.417 and the Russian tradition, Russian Wikipedia mirror](https://dic.academic.ru/dic.nsf/ruwiki/57875), read as a search summary only.
- Russian press on NAVEE, weak evidence, not official: [Mobile-review, NT5 Max](https://mobile-review.com/all/reviews/gadgets/elektrosamokat-navee-nt5-max-dlya-goroda-bezdorozhya-i-lyubyh-situaczij/) (*блокировка и разблокировка самоката в приложении*, *Круиз-контроль*, *режимы рекуперации*, *Поворотники*, *Запас хода*, *спортивный режим*); [Mobile-review, N65i](https://mobile-review.com/all/reviews/gadgets/obzor-navee-n65i-elektrosamokat-s-shipovannoj-rezinoj/) (*рекуперация*, *поворотники*, *экран*, *LED-фара*, *стоп-сигнал*). [iXBT's GT3 Pro review](https://www.ixbt.com/car/navee-gt3-pro-review.html) refused the fetch (403), and [Za Rulem on N65i](https://www.zr.ru/content/news/956099-ne-strashno-sekhat-s-asfalta/) and [E-Katalog](https://e-katalog.kz/NAVEE-N65.htm) were seen as search summaries only; those support *запас хода* and *электросамокат*.
- Checked but not Russian: [NAVEE GT3 manual on a distributor site](https://b2b.robotics-distribution.com/content/files/navee-electric-scooter-gt3-user-manual-1.pdf-81355390.pdf) is Ukrainian; [NAVEE's service site](https://service.naveetech.com/?p=2153) showed no Russian wording.
- **Judgment calls with no source:** *ключ владельца*, *Предел заряда*, *Пиковая скорость*, *Сигнал тревоги*, *Зарядка к сроку*, *Помощь при ведении* (recalled from e-bike manuals, not checked), *Функции на пробу*, *Неопробованные функции*, *Нет подтверждения*, *Строка блокировки*, *Голубой лёд*, *Журнал поездок телефона*, *файл с ключом владельца*, *Усиление мощности*, *Контроль тяги*, *Контроль спуска*, *Текущий пробег*, *контрольное считывание*, *пользователь с общим доступом*, *не связан с компанией NAVEE*, and all lighting names. The abbreviations *Макс.*, *Ср.*, *Темп.*, *Напряж.*, *Кол-во*, *Тек.* and *След.* come from general knowledge of Russian; no native speaker has confirmed them. *Одом.* in particular is rare. The estimate of 13 letters per tile line is not measured.
