# Volt style guide: Ukrainian (uk)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 9; the three-vendor debate decides later. No native speaker has reviewed it. Ukrainian is in wave 3, which the owner wants only if it is easy, so this guide stays short and lists its open questions as **Debate item**.

Saira has no Cyrillic, so `font` is `system`. Width budgets (`volt:max`) can't be measured here: `check.py` flags them for a device run. Use the shortening rules in section 5 anyway.

Write standard Ukrainian following the 2019 orthography. Don't carry over Russian words or Russian grammar (*настройки*, *зарядка батареї*, *виключити*): a Russian calque reads as careless, and sometimes as worse.

## 1. Register and form of address

- Address the rider as **ви**, always lower case, including *ваш*: *Підключіть зарядний пристрій*, *ваш самокат*. Never *ти*.
- Calm, plain, direct. No exclamation marks in errors, no marketing tone. Leave out *будь ласка* unless the English needs the courtesy: *Please wait* is *Зачекайте…*.
- **Buttons, toggles and menu actions use the infinitive:** *Зберегти*, *Підключити*, *Заблокувати*. Android does the same (*Додати*, *Скасувати*).
- **Instructions in sentences use the ви imperative:** *Підключіться знову, щоб перевірити*. Don't mix the two in one string.
- **Labels and titles use nouns:** *Ліміт заряду*, *Резервна копія*.
- **A state uses the impersonal -но/-то form:** *Заблоковано*, *Підключено*, *Сигнал втрачено*. It has no gender or number, so it stays correct whatever the subject or placeholder is. Prefer it to *заблокований*. No *було* or *є* with it: *Не вдалося прочитати*, not *Читання не було виконано*.
- **A process in progress uses a noun and keeps the ellipsis:** *Підключення…*, *Вимірювання…*.
- Question dialogs are short: *Зберегти зміни?*, not *Чи бажаєте ви зберегти зміни?*
- Prefer a verb to verb plus noun: *Не вдалося видалити файл*, not *Не вдалося здійснити видалення файлу*.
- Avoid gendering the rider where a rewrite is easy. Role words use the generic masculine (*власник*).

## 2. Punctuation and quotes

- Quotes: **«…»** first, **„…“** inside. Never straight quotes, no spaces inside them.
- A sentence that names a button, tab or screen puts its exact label in «…»: *Натисніть «Зберегти»*, *відкрийте «Резервна копія та відновлення»*. The label keeps its form, so build the sentence so that it needs no case change. A feature mentioned in passing stays unquoted.
- **Apostrophe:** always the curly **’** (U+2019): *від’єднати*, *прив’язати*, *пам’ять*. Not `'` and not `ʼ`. A translator writes plain text, without XML escapes.
- **Dash:** an en dash with spaces in a sentence: *Volt – це додаток*. A range takes an en dash with no spaces: *15–30*. A hyphen links only the parts of a word: *PIN-код*, *круїз-контроль*.
- Ellipsis is one character, `…`. Keep the English's `·` separators.
- A colon is followed by lower case unless a name follows: *Останній відомий стан: заблоковано*.
- A warning word takes an exclamation mark rather than a colon (*Увага!*). A note takes a period (*Примітка.*).
- **& becomes a word:** *і*, *й* or *та*. *Display & sound* is *Дисплей і звук*.
- No period after a label or button. Lists use commas, with no comma before a single *і*, *й*, *та* or *або*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere:** only the first word and names take a capital. Button *Зберегти зміни*, label *Ліміт заряду*, title *Експериментальні функції*. Never English Title Case, even when the English has it (*Walk Assist* is *Допомога під час ходьби*).
- A word after a colon is lower case: *Стан: заблоковано*.
- Feature names are lower case mid-sentence: *увімкніть заряджання за розкладом*. Days and months are lower case.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android. Drive modes and level names aren't translated and keep the case of the English string: *режим Sport*, *ECO*, *Drive*.

## 4. Numbers, dates and units

- Decimal comma: *1,5*. Four-digit numbers take no separator (*1234,5*). From five digits a no-break space (U+00A0) groups thousands: *12 345,6*. Volt's formatter handles live numbers, so never rewrite a placeholder's number or add separators around one.
- **Spaces around numbers are U+00A0, never U+202F and never a plain space.** Percent takes one: `%1$s %%` gives *85 %*, as NAVEE's Ukrainian manual writes *20 %*. A `formatted="false"` literal is `100 %`.
- Keep digits as digits. Don't spell numbers out in the UI, since a spelled number changes with its case.
- **Unit symbols stay in Latin letters as written**, after U+00A0: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. They take no plural and no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. This is the project's default (`unit_symbols si`). Unit words are translated: *кілометри*, *милі*, *години*, *хвилини*.
- **Debate item, for the owner:** Ukrainian readers expect *км/год*, *год*, *хв*, *В*, *Вт·год*, and NAVEE's own manual uses them. Latin *km/h* in Cyrillic text looks foreign. If the owner allows it, this is the first thing to change for this language. Until then, don't mix: no *км/год* in a draft.
- Dates and times come from Volt's formatters; don't hard-code an order. The usual form is *05.11.2026* and 24-hour *14:30*. A fixed date in prose is *5 березня*.
- **Plurals:** `one` covers 1, 21, 31, 101 and so on, so it must keep its placeholder and never say "one": *%1$d поїздка*. `few` is 2-4, 22-24: *%1$d поїздки*. `many` is 0, 5-20, 25-30: *%1$d поїздок*. `other` is fractions and takes the genitive singular: *%1$s поїздки*. Fill all four.
- **Nothing may depend on a value you can't see.** *о/об*, *у/в*, *з/із/зі* and *і/й* change with the next sound, and case endings change with the number, so use a label form with a colon: *Час початку: %1$s*.

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word, never shrink the text, and never cut a word so far that it reads as another. Cyrillic words run long, so expect to need these rungs more often than English does. Try in this order:

1. Drop words the screen already supplies: *Налаштування акумулятора* → *Акумулятор* on a settings screen.
2. Use a shorter whole word: *Акумулятор* → *Батарея* (both are NAVEE's words; on a tab or tile only).
3. Use an abbreviation from this table, ending in a period. Nothing else: no invented cuts such as *Налаш.*, *Експ.*, *Рекупер.*

| Full | Abbreviated | Note |
|---|---|---|
| Максимальна швидкість | **Макс. швидкість**, then **Макс. шв.** | The setting. *Макс. швидкість* is the NAVEE manual's own form |
| Пікова швидкість | **Пік швидкості**, then **Пік** | The measured value. Never *Макс.* |
| Середня швидкість | **Сер. швидкість**, then **Сер. шв.** | |
| Одометр | **Одом.** | Or the whole word **Пробіг**, the manual's *Загальний пробіг* |
| Температура | **Темп.** | |
| Номінальна | **Ном.** | *Ном. ємність* |
| Потужність | **Потужн.** | |
| Налаштування | **Налашт.** | Tabs and tiles only |
| приблизно | **прибл.** | The manual's form: *прибл. 40* |
| оцінка, орієнтовно | **орієнт.** | *Час до повного заряду (орієнт.)* |
| мінімальний | **мін.** | |
| Увімкнено, Вимкнено | **Увімк.**, **Вимк.** | Android's own forms. Only on a compact rung; they differ by their first letters, so never cut further |
| Інформація | **Інфо** | A short word, no period |

Real ladders:

- *Top speed* tile, after D3: **Максимальна швидкість** → **Макс. швидкість** → **Макс. шв.**
- `readings_temperature_c`: **Температура акумулятора** → **Темп. акумулятора** → **Темп.**
- `readings_rated_capacity_ah`: **Номінальна ємність акумулятора** → **Ном. ємність** → **Ємність**
- `readings_odometer_km`: **Загальний пробіг** → **Одометр** → **Одом.**
- `more_lab_status_untested`: **Не випробувано на цій моделі** → **Тут не випробувано** → **Не випробувано**
- Bottom tabs: **Налашт.**, **Батарея**, **Поїздки**, **Блокування**. *Блокування* may run past the 62dp budget; it stays whole.

An abbreviation's period counts toward the width. A two-word label may wrap at a space where the English also wraps.

**Never abbreviate:** *Заблокувати, Розблокувати, Заблоковано, Розблоковано, Блокування, Не перевірено, Не випробувано, Не підтверджено, Невідомо, Ніколи, Завжди, Звуковий сигнал*. No *Блок.*, *Заблок.*, *Розблок.*, *Невід.*, *Неперев.*, *Сигн.* Don't drop the *Не* from any state. If one of these doesn't fit, write the whole word and say so in your notes: overflow is a design problem that gets fixed, and a wrong state isn't noticed.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Ukrainian | Rules |
|---|---|---|
| Lock (action) | **Заблокувати** | Button or action. Imperative *заблокуйте* |
| Lock (noun, card title) | **Блокування** | Never *замок*, which reads as a physical lock |
| Locked | **Заблоковано** | The impersonal form. *заблокований* only with a named masculine subject |
| Unlock (action) | **Розблокувати** | Noun: **Розблокування** |
| Unlocked | **Розблоковано** | Never *відкрито*, *вільний* or *не заблоковано* |
| Lock state unverified | **Стан блокування не перевірено** | Must never read as confirmed. Keep *не* |
| Lock state unknown | **Стан блокування невідомий** | Must never read as confirmed |
| Unknown (alone) | **Невідомо** | *невідомий* where it agrees with a named noun |
| Unverified | **Не перевірено** | Two words; keep *Не* |
| Untested | **Не випробувано** | |
| Unconfirmed, Not confirmed | **Не підтверджено** | |
| Never | **Ніколи** | Never a bare *не* where the English says never |
| Always | **Завжди** | *Always on*: **Завжди ввімкнено** |
| Alarm, Alarm sound | **Звуковий сигнал** | The phone sounding an alert. Never *тривога* or *сигналізація* (see below) |
| Stop alarm | **Зупинити сигнал** | *Stop* alone is **Зупинити** |
| PIN | **PIN-код** | Latin PIN, hyphen, *код*. A tight chip may show *PIN ✓*. Never *пароль*, which stays the word for a backup password |
| Handlebar PIN | **PIN-код на кермі** | *PIN unlock* is **Розблокування PIN-кодом** |
| Owner key | **ключ власника** | A secret. Never *код*, which is the PIN, or *пароль*. "Not a password" is *Це не пароль* |
| Charge limit | **Ліміт заряду** | Never *стеля* or *максимум заряду* |
| Experimental | **Експериментальний**, agreeing with its noun | *Experimental controls*: **Експериментальні функції** |
| not affiliated with NAVEE | **не афілійовано з NAVEE** | Never soften it: no *неофіційний*, *незалежний від*, *не зовсім пов’язаний*. In a sentence: *Volt не афілійовано з NAVEE* |

Words that look right and aren't:

- **тривога** in Ukraine means an air-raid alert first of all. A battery message must never use it. **сигналізація** promises a theft alarm that this feature isn't. The feature is the phone playing its alarm sound (Android's *будильник*): *Your alarm sound* is *звук будильника телефона*, and only there.
- **Заблоковано** and **Розблоковано** differ only in the prefix. Keep both whole in every rung.
- **не заблоковано** says only "not locked" and may not be used for *Unlocked*. **не розблоковано** isn't *Locked* either.
- Don't describe an unlock as removing the lock (*зняти блокування*, *вимкнути блокування*). Say *Розблокувати*. Use *вимкнути блокування* only where the English itself says turn off.
- *Не перевірено* and *Не підтверджено* turn into their opposites without *Не*. Check every rung. `check.py` counts *не*, *без*, *ні*, *ніколи*, *немає*, and the *не-* prefix.
- **ключ** also means a wrench in NAVEE's manual. Always write *ключ власника* in full on first mention.
- On and off: settings and lights use **Увімкнено** and **Вимкнено**. Never swap them, never use *Так/Ні* for them.
- Don't add reassurance the English lacks (*безпечно*, *гарантовано*), and don't add warnings it doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Scooter:** **самокат**, as NAVEE's manual writes. Never *скутер*, which in Ukrainian is a moped.
- **App:** **додаток**, as NAVEE's manual and Android both write: *додаток NAVEE*, *офіційний додаток*. **Debate item:** *застосунок* is the word many Ukrainian editors prefer. Use one, not both.
- **Battery:** **акумулятор** in text, as Android and the manual write. *Батарея* only as a short rung (section 5).
- **Charge:** **заряд** is the level (*Рівень заряду*, *Ліміт заряду*). **заряджання** is the process (*Заряджання за розкладом*). *зарядка* is colloquial. The charger is **зарядний пристрій**.
- **Three speeds:** the setting is **Максимальна швидкість**. A limit below it is **Обмеження швидкості**. The measured value on a trip or ride is **Пікова швидкість**. Never swap them.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. Those words have the opposite meaning here, so these keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. The `top-speed` and `maximum-speed` glossary rows already carry D3's meanings, so following them in those keys today would swap the two speeds. Flag them in your notes; a judge answers fallback.
- **Rides vs trips:** a recorded ride is a **поїздка**, the screen is **Поїздки**. *Trip*, the scooter's distance since a reset, is **поточна поїздка**, and *Trip distance* is **Пробіг за поїздку**. *Odometer* is the total: never call a trip distance plain *Пробіг*. A path on a map is a **маршрут**.
- **Try vs retry vs test:** trying a control is **Випробувати**, so that it agrees with *Не випробувано*; its result is **випробування**. An ordinary retry is never *випробувати*: *Повторіть спробу*. A guided test is **тест**. *Test scheduled charging* is a check: **Перевірити заряджання за розкладом**. A compatibility check is **перевірка сумісності**. *Controls to try* is **Що можна випробувати**.
- **Dashboard vs display:** Volt's Home layout is the **Панель**. The scooter's own screen is the **дисплей самоката**. NAVEE's manual calls the scooter's screen *Приладова панель*, so never write that for Volt's screen, and never write bare *Панель* for the scooter's. *Display & sound* is **Дисплей і звук**.
- **Settings vs setup:** Ukrainian has one word, **налаштування**, for both. Where it matters, one-time setup is **початкове налаштування**. *Owner setup* is **налаштування власника**.
- **Restore vs set back:** bringing back a backup is **Відновити**. Undoing a try is **Повернути назад**. Energy recovery is **Рекуперація енергії**, unrelated to either.
- **Readback:** **контрольне зчитування**, a neutral term, because a readback can also fail to confirm.
- **Rejected:** *Rejected* is **Відхилено**, *Refused by scooter* **Відхилено самокатом**.
- **Pair vs bind vs connect:** Bluetooth pairing is **об’єднати в пару** (*об’єднання в пару*, *Об’єднано в пару ✓*). NAVEE account binding is **прив’язати**. A live link is **підключити**, ended by **від’єднати**. **Debate item:** current Android writes *Підключити* for pairing too. It's kept apart here because Volt shows both on one screen.
- **Alerts:** an alert is **попередження**, an Android notification is **сповіщення**, and *Notify me* is **Сповістити мене**. They stay distinct from *Звуковий сигнал*. *Prompt sound*, the scooter's beeps, is **Звук підказок**.
- **Lights:** **передня фара** and **задній ліхтар**, as in the manual. *Line lights*, *Star lights* and *Ambient light* are **підсвічування**. *Turn signal* is **покажчик повороту**. Colours are masculine: *Червоний*, *Крижаний блакитний*.
- **Levels:** Energy recovery levels are ordinary words and agree with *рекуперація*: *Вимкнено, Низька, Середня, Висока*. Elsewhere they agree with their own noun.
- **Eco, Turbo, Boost:** stay in English, as the scooter shows them. **Оцінка Eco** is a range option, translated. Today's English glossary has Eco and Turbo as translatable, so the rows repeat the English; D3 makes them do-not-translate.
- **Roles:** owner is **власник**, shared rider is **запрошений користувач**. The compact role label (`more_role_shared`, English *Shared*) is **Запрошений**. Never *Спільний*, which reads as a jointly owned scooter. **Debate item.**
- **Charge done by:** the feature name is **Завершення заряджання**, its time setting **Час завершення заряджання**. The feature only plans a start, so never word it as a charge that has finished (*Заряджено до*). In a sentence, quote the name.
- **Owner key parts:** the file is the **файл налаштування власника**, shortened to **файл власника** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **LAB badge:** **ЛАБ**. TalkBack speaks **Експериментальне**.
- **Android's own wording:** **Налагодження USB**, **Параметри розробника**, **Номер складання**. Phone makers rename the rest, so write generally: *у налаштуваннях відкрийте відомості про телефон*.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.

## 8. Open questions for the debate and the owner

1. Latin unit symbols against *км/год*, *год*, *хв* (section 4). The largest visible question.
2. *додаток* against *застосунок*.
3. *Звуковий сигнал* for alarm, chosen over *тривога* (section 6).
4. *запрошений користувач* for shared rider.
5. *об’єднати в пару* against Android's current *підключити*.
6. Drive mode names stay in English, though the manual translates *Режим ходьби* for WALK.

## 9. Sources consulted

**NAVEE's official Ukrainian: partly found, in a manual only.** The official **app's** Ukrainian wording was **not found**, and nothing shows that the app has a Ukrainian version. No public page gives Ukrainian for lock, charge limit, PIN, owner key or experimental features, so those words are Claude's own proposals. No APK was downloaded.

- [NAVEE N40 user manual, Ukrainian (document NKP21111-A25-EU-A00), hosted by the retailer Allo](https://i.allo.ua/media/manual/989621_elektrosamokat_navee_n40_350w_chornij_1378882_.pdf): *самокат*, *додаток*, *Круїз-контроль*, *Макс. швидкість*, *Максимальна швидкість*, *Загальний пробіг*, *Режим ходьби*, *Передня фара*, *Задній ліхтар*, *Стоп-сигнал*, *Приладова панель*, *Екран дисплея*, *кермо*, *Зарядний пристрій*, *заряджання*, *акумулятор*, *батарея*, *км/год*, *Вт·год*, *прибл.*, *20 %*. The translation is uneven, so it isn't a style model.
- [Jabko, NAVEE N65 product page](https://jabko.ua/gadzheti-i-drugoe/elektrotransport/jelektrosamokat-navee-n65--black---1378913-), a retailer: *Запас ходу*, *рекуперативне гальмо*, *Режим "круїз"*.
- [Microsoft Ukrainian Localization Style Guide](https://download.microsoft.com/download/6/4/a/64a8b98e-e69f-47c9-96dd-f8bc55c176c6/ukr-ukr-StyleGuide.pdf): lower-case *ваш*, UI items in «…», U+2019 apostrophe, dash and range rules, *Увага!* and *Примітка.*, no spelled numbers in UI, no overused *будь ласка*, short question dialogs, -но/-то forms without *було*, verbs over verb plus noun, & as a word.
- [Mozilla Ukrainian style guide](https://wiki.mozilla.org/L10n:Teams:uk/Style_Guide): decimal comma, space for thousands, *dd.mm.yyyy*.
- Android's own Ukrainian, read from AOSP: [Settings](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-uk/strings.xml) (*Додати*, *Готово*, *Скасувати*, *Зберегти*, *Налаштування*, *Акумулятор*, *Гучність будильника*, *Сповіщення*, *Номер складання*, *Підключити новий пристрій*, *Від’єднати пристрій?*; *додаток* appears 675 lines to none for *застосунок*) and [SettingsLib](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-uk/strings.xml) (*Налагодження USB*, *Параметри розробника*).
- Other makers' usage, weaker evidence: [ITC review, Segway Ninebot ZT3 Pro E](https://itc.ua/ua/articles/oglyad-segway-ninebot-zt3-pro-e/) (*рекуперація енергії*, *додаток*), [ITC, Acer ES Series 5](https://itc.ua/ua/blogs/5-prychyn-zakohatysya-v-novyj-elektrosamokat-acer-es-series-5-select/) (*круїз-контроль*), [E-Katalog](https://e-katalog.pl/ua/list/1237/pr-47818/) (*Стоп-сигнал*).
- **Judgment calls, with no source:** *Пікова швидкість*, *Ліміт заряду*, *Обмеження швидкості*, *Звуковий сигнал* for alarm, the *тривога* warning, *ключ власника*, *запрошений користувач*, *об’єднати в пару*, *Контроль тяги*, *Контроль спуску*, *Силова допомога*, the abbreviations not marked as the manual's or Android's, and the plural and quote rules, which come from general knowledge of CLDR and Ukrainian typography. The official text of the 2019 *Український правопис* could not be read; a search returned only teaching notes.
- No width figures: Saira has no Cyrillic, checked with `tools/l10n/fontmetrics.py`.
