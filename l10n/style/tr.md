# Volt style guide: Turkish (tr)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 10; the three-vendor debate decides later. No native speaker has reviewed it, and the official app's Turkish wording was not found.

Three things go wrong in Turkish and in no other Volt language, so read them first: dotted and dotless i (section 3), suffixes on a placeholder (section 8), and words where a noun and "don't do it" are spelled the same (section 6).

## 1. Register and form of address

- Address the rider as **siz**, plural form, always: *Şarj cihazını takın*, *scooter’ınız*. Never sen forms in sentences (*tak*, *scooter’ın* meaning "your scooter"). The pronoun *siz* itself is rarely needed; the verb ending says it.
- **Buttons, toggles and menu actions use the bare verb stem,** as Android and Microsoft's Turkish software do: *Kaydet*, *Bağlan*, *Kilitle*. This is the software convention and doesn't break siz.
- **Instructions in sentences use the -in/-ın imperative:** *Kilidi açmak için yeniden bağlanın*. Don't mix the two in one string.
- Labels and titles are nouns: *Şarj sınırı*, *Yedekleme ve geri yükleme*. If a title is an action with no good noun, use the stem: *Zamanlanmış şarjı kontrol et*.
- Calm, plain, direct. *Lütfen* only where a request really needs it. No exclamation marks in errors, no marketing tone.
- Possessives only where they carry meaning. Microsoft's guide drops them (*Set Up Your Device* → *Cihazı Ayarla*), and they cost width: *Scooter ayarları*, not *Scooter’ınızın ayarları*.
- Prefer the simpler verb form and the fewest suffixes that keep the meaning.
- Volt and the scooter are third person: *Volt aygıt yazılımını asla değiştirmez*.

## 2. Punctuation and quotes

- Quotes: **“…”** first, **‘…’** inside (`quote_style`). Never straight quotes, and never «…» . Microsoft's guide asks for straight quotes in its own software; this guide follows TDK's typographic marks, which Saira has. **Debate item.**
- A sentence that names a button, tab or screen puts its exact label in “…” with **any suffix on a word after it**, not on the quote: *“Kaydet” düğmesine dokunun*, *“Yedekleme” ekranını açın*. Use *düğmesine*, *ekranını*, *sekmesini*, *seçeneğini*. Never *“Kaydet”e*.
- **Apostrophe: always `’` (U+2019), never `'`:** *PIN’i*, *Volt’un*. A straight one needs an escape and doesn't match the glossary. Saira has `’`.
- Suffixes on a name or acronym follow the way it is read, after an apostrophe: *Volt’u, Volt’a, Volt’un*; *NAVEE’nin, NAVEE’ye* (read "navi"); *PIN’i, PIN’e* (read "pin"); *GPS’i, USB’yi, ADB’yi, CSV’yi, GPX’i*; *Bluetooth’u*; *Android’i*; *Shizuku’yu*; *Model ID’si*. A file name takes a head noun instead: *navee-owner-setup.json dosyası*.
- No comma after the subject out of habit, except after a short subject that could be misread: *Volt, NAVEE ile ilişkili değil*. No serial comma before *ve* or *veya*.
- Keep the English's `·` separators and `…` as written. A verb in progress keeps `…`: *Bağlanıyor…*
- Don't use a dash for emphasis (Microsoft's guide rules out the em dash). Use a colon or a new sentence.
- A colon is followed by lower case unless a name follows: *Bilinen son durum: kilitli*. A colon is also the main tool for placeholders (section 8).
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.

## 3. Capitalization of buttons, labels and titles

- **Sentence case everywhere:** buttons, labels, titles, section headers. Only the first word and names take a capital: *Şarj sınırı*, *Deneysel özellikler*, *Düşük pil modu*. Never English Title Case, even when the English has it (*Walk Assist* → *Yürüme desteği*).
- Feature names are lower case mid-sentence: *zamanlanmış şarjı açın*. After a colon, see section 2.
- Keep capitals on Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android. Drive modes and level names are never translated and keep the case the English string uses in that string (*Sport*, *ECO*, *Drive*): *Sport modunda*.
- Months and days take a capital only as part of a full date (*5 Mart*); otherwise lower case. Language names are capitalized (*Türkçe*).

### Dotted and dotless i

Turkish has two different letters, and each keeps its own dot through a case change:

| Lower | Upper | Example |
|---|---|---|
| **i** | **İ** | *kilit* → *KİLİT*, *iptal* → *İptal* |
| **ı** | **I** | *sınır* → *SINIR*, *ışık* → *Işık* |

- **Write every string in sentence case with the right letters, and never type a word in capitals to get a look.** Volt upper-cases section labels itself (`Ui.sectionLabel`, `setAllCaps`, main group labels) using the language's rules, so *Şarj sınırı* shows as *ŞARJ SINIRI* and *Kilit durumu* as *KİLİT DURUMU*. The exception is a string the English itself writes in capitals: use the correct capitals (*LAB*, *HAM*, *DİKKAT*).
- **A first letter counts.** A word starting with i takes **İ** at the start of a sentence or label: *İptal*, *İzinler*, *İzlemeyi durdur*, *İleri*. *Iptal* is a misspelling. A word starting with ı takes **I**: *Işıklar*.
- **A lost dot can change the word:** *sınır* (limit) and *sinir* (nerve, anger); *ısı* (heat) and *isi* (soot); *kır* (break) and *kir* (dirt); *kıl* (hair) and *kil* (clay). So a wrong *I* is not only ugly. Because of this, never write Turkish without its letters, even where the budget is tight.
- **Avoid the *sıkış-* family (*sıkıştı*, *sıkışmış*) for "stuck".** With a lost dot it reads as a vulgar word. Use *takıldı* or *takılı kaldı*.
- **English names with a lower-case i break in capitals.** Under Turkish rules, *Drive* becomes *DRİVE* and *Shizuku* becomes *SHİZUKU*. Where a string goes to an upper-cased label, keep such names out of it if the sentence allows; otherwise leave them and say so in your notes. Never write them in capitals yourself.
- **The reverse goes wrong too:** lower-cased under Turkish rules, *PIN* would become *pın*. Volt's templates don't lower-case text, so write acronyms as they are. If a string's note says it gets lower-cased, flag it in your notes.

**Saira coverage, checked 2026-11-05** with `tools/l10n/fontmetrics.py` on all four weights: ı İ ş Ş ğ Ğ ç Ç ö Ö ü Ü â Â î Î û Û, U+00A0, “ ” ‘ ’ … · – % ° are all present; nothing is missing. Saira also has a Turkish `locl` feature that swaps i for a glyph that won't join *f* into a dotless-looking *ﬁ* ligature, which matters for *profil* and *filtre*. That needs the text to be tagged Turkish; check it on a device.

## 4. Numbers, dates and units

- Decimal comma and thousands point in text you write: *1.234,5* (`decimal_style`). Volt's `Fmt` formats live numbers; never rewrite a placeholder's number or add separators around one.
- **The percent sign comes before the number, with no space:** `%%%1$s` gives *%85*. Never *85%*, *85 %* or *yüzde 85* on a label. Every English `%1$s%%`, and `%1$s %%` (`controls_option_percent_spaced`), becomes `%%%1$s`; `formatted="false"` text writes *%100*.
- Numbers in the English stay as digits. Don't write them out or change them.
- A noun after a number stays singular: *5 dakika*, *3 sürüş*, never *5 dakikalar*. So `one` and `other` are usually the same text; still give both.
- Unit symbols stay and take a no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *25 °C*. Never U+202F, which Saira lacks. Symbols take no plural: `km`, `mph`, `V`, `A`, `W`, `Wh`, `Ah`. Keep `km/h` even though *km/s* and *km/sa* are common in Turkish listings: *km/s* also looks like kilometres per second.
- **Time units are translated, and take a period:** minutes **dk.**, hours **sa.**, seconds **sn.**, with U+00A0: *45 dk.*, *2 sa. 5 dk.* English `min`, `h`, `m`, `s` become these: `≈ %1$sh %2$sm` → `≈ %1$s sa. %2$s dk.` The note on `units_minute` asks for this.
- **Miles:** the unit word is **Mil**, and the symbol `mi` becomes **mil**, since *mi* is the Turkish question particle. **Debate item.**
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 Mart*. Don't write *ÖÖ/ÖS* yourself.
- Never attach a suffix to a number or percentage you can't see (section 8).

## 5. How to shorten labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure with Saira; don't count letters. Turkish compounds carry a suffix on the head noun, and it must stay: *Sürüş ayarı* or *Ayarlar*, never *Sürüş ayar*. Try these in order:

1. Drop words the screen already supplies: *Pil ayarları* → *Ayarlar* on the battery screen; *Hakkında ve izinler* → *Hakkında*.
2. Use the singular or a shorter common noun: *Çizgi ışıkları* → *Çizgi ışığı*; *Canlı veriler* → *Canlı veri*.
3. Replace *ve* with a comma: *Ekran ve ses* → *Ekran, ses*.
4. Use an accepted abbreviation from this table. Nothing else: no invented cuts such as *Ayr.*, *Den.*, *Bağ.*, *Şrj.*

| Full | Abbreviated | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Maksimum hız | **Maks. hız** | 84.1 → 54.6 | The setting. The full form fits the 90dp tile |
| En yüksek hız | no abbreviation | 79.9 | The measured value. Fits 90dp. Never *Maks. hız* |
| Ortalama hız | **Ort. hız** | 73.9 → 41.6 | |
| Kilometre sayacı | **Km sayacı** | 96.4 → 59.3 | The *Odo.* of this language. **Debate item:** Volt also offers miles, and *Km sayacı* over a value in miles names the wrong unit; *Odometre* (57.7) is unit-neutral but less everyday |
| Sıcaklık | **Sıc.** | 43.8 → 20.0 | Weak alone. Where the ladder has an icon, prefer it. **Debate item** |
| Pil sıcaklığı | **Pil sıc.**, then **Sıc.** | 63.8 → 36.1 → 20.0 | A real three-rung ladder |
| Tahmini | no abbreviation | 46.8 | Write *Tahmini*, or `≈` where the English allows it |
| Yaklaşık | **Yakl.** | | |
| maksimum, minimum | **maks.**, **min.** | | |

Real ladders, widths at the given size:

| Label | Full | Short | Compact |
|---|---|---|---|
| Battery temperature | *Pil sıcaklığı* | *Pil sıc.* | *Sıc.* |
| Ride settings | *Sürüş ayarı* (63.7 at 12sp, under 64) | *Sürüş ayarları* (81.5 at 12.5sp, under 90) | |
| Experimental controls | *Deneysel* (51.8 at 12sp) | *Deneysel özellikler* (109.3, so wrap at the space on the 90dp tile) or *Deneysel* (53.9) | |
| Charge done by | *Bitiş hedefi* (63.3 at 12sp) | *Şarj bitiş hedefi* (90.7, so wrap at a space) or *Bitiş hedefi* (65.9) | |
| App preferences | *Tercihler* (47.5 at 12sp) | *Tercihler* (49.5); *Uygulama tercihleri* (113.6) only wrapped | |

Measured words that fit as they are: *Kilit* 23.0, *Ayarlar* 43.0, *Pil* 14.7, *Sürüşler* 48.7 (all under the 62dp tab budget at 12.5sp). At the 64dp shortcut budget (12sp): *Işıklar* 33.4, *Pil ayarları* 60.0, *Scooter adı* 62.3, *Scooterlar* 57.0, *Canlı veri* 50.8, *Çizgi ışığı* 51.8, *Yıldız ışığı* 55.2, *Ekran, ses* 56.7. Over it: *Canlı veriler* 65.2, *Çizgi ışıkları* 65.5, *Sürüş ayarları* 78.2, *Scooterlarım* 70.6.

An abbreviation ends in a period, and the period counts toward the width. A two-word label may wrap at a space where the English also wraps.

**No positive word plus *değil* on a one-line label or a ladder rung.** A one-line view can show the first word and lose the second, so *Doğrulanmış değil* can appear as *Doğrulanmış* (verified) and *Bağlı değil* as *Bağlı* (connected). `MoreScreen.LadderPill` is one such view. Keep the negation inside the word, or use a noun that stays neutral when cut: *Doğrulanmadı* (75.4 at 11.5sp semibold), *Denenmedi* (60.6), *Onaylanmadı* (71.2), *Bağlantı yok*. The pill is tighter than the English (*Unverified* is 56.1), so report an overflow in your notes rather than cutting the word.

**Never abbreviate:** *Kilitle, Kilit, Kilitli, Kilidi aç, Kilidi açık, Doğrulanmadı, Denenmedi, Onaylanmadı, Bilinmiyor, Hiçbir zaman, Asla, Her zaman, Alarm, PIN*. *Kil.* is not allowed. If one of these doesn't fit, write the whole word and report it in your notes.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Turkish | Rules |
|---|---|---|
| Lock (action) | **Kilitle** | Button or action. In a sentence: *Scooter’ı kilitleyin* |
| Lock (noun, card title) | **Kilit** | *Kilit durumu*, *Kilit süresi*. Keep to the form *kilit* with a noun after it |
| Locked | **Kilitli** | |
| Unlock (action) | **Kilidi aç** | In a sentence: *Kilidi açın*, *Scooter’ın kilidini açın*. Noun, only inside a compound: **kilit açma** |
| Unlocked | **Kilidi açık** | Never *Açık* alone, *Kilitsiz* or *serbest* |
| Lock state unverified | **Kilit durumu doğrulanmadı** | Must never read as confirmed |
| Lock state unknown | **Kilit durumu bilinmiyor** | Must never read as confirmed |
| Unknown (alone) | **Bilinmiyor** | As an adjective: *bilinmeyen* |
| Unverified | **Doğrulanmadı** | Short rung *Burada doğrulanmadı*. As an adjective: *doğrulanmamış*. *Could not be verified* is *doğrulanamadı* |
| Never | **Hiçbir zaman** | The standalone option, Android's own word. In a sentence: *Volt aygıt yazılımını asla değiştirmez*. Where a one-word rung is needed: **Asla** (25.1 at 12.5sp, against 75.5) |
| Always | **Her zaman** | *Always on* for a light: **Her zaman açık** |
| Alarm | **Alarm** | Never *uyarı*: alert is **Uyarı**, and they stay distinct |
| Alarm sound | **Alarm sesi** | |
| Stop (a sounding alarm) | **Durdur** | Never *Durdurma* (see below) |
| PIN | **PIN** | *PIN’i*, *PIN’iniz*. Not *kod* or *şifre* |
| Handlebar PIN | **gidon PIN’i** | *Gidon PIN’ini girin*. A password is **şifre**; keep them separate |
| Owner key | **sahip anahtarı** | A secret. *Anahtar* also means a physical key in Turkish, as it does in English. "Not a password" is *Şifre değil* |
| Charge limit | **Şarj sınırı** | Never *tavan*. *Limit* is not used. Check the ı: *sınır*, never *sinir* |
| Experimental | **Deneysel** | *Deneysel özellikler*. In Turkish *kontrol* first means a check, so *Deneysel kontroller* reads as "experimental checks". *Kontrol* stays in established feature names (*Çekiş kontrolü*, *Yokuş iniş kontrolü*) and for checks. **Debate item,** if a word closer to "controls" is wanted |
| Untested | **Denenmedi** | As an adjective: *denenmemiş* |
| Unconfirmed, Not confirmed | **Onaylanmadı** | As an adjective: *onaylanmamış*. *Could not be confirmed* is *onaylanamadı* |
| not affiliated with NAVEE | **NAVEE ile ilişkili değil** | In a sentence: *Volt, NAVEE ile ilişkili değil*. Never soften it: no *gayriresmî*, *resmî değil*, *doğrudan ilişkili değil* |

**Words that look right and mean the opposite or something else:**

- **"Lock: on" is never *Kilit: açık*.** *Kilit açık* says the lock is open, which is **unlocked**. And *Kapalı* beside a lock reads as closed, which is locked, while on a setting it means off. So on any lock-related setting use **Etkin** and **Devre dışı** for on and off, and never *Açık* or *Kapalı*: *Otomatik kilit: etkin*. In a lock key `check.py` reports *kilit* next to *açık* or *kapalı* as an error; the approved *Kilidi açık* isn't affected.
- **-me/-ma nouns are also negative commands.** *Kilitleme* is both "locking" and "don't lock". *Kilidi açma* is "don't unlock". *Durdurma* is "don't stop". *Silme* is "don't delete". So a button or standalone label is never a -me/-ma word: use the stem (*Kilitle*, *Durdur*, *Sil*) or put a noun after it (*Kilitleme süresi*, *kilit açma* with the unmarked *kilit*, *silme işlemi*). *Yedekleme* and *geri yükleme* are fine as titles only because nobody commands "don't back up" there; on a button use *Yedekle*, *Geri yükle*.
- **"Not connected" is not "not affiliated".** *NAVEE ile bağlantılı değil* and *bağlı değil* read like a Bluetooth status in this app. That is why the disclaimer says *ilişkili*. **Debate item:** *bağlantılı değil* is common in legal notices elsewhere.
- *Kilitsiz* means having no lock at all. Don't use it for Unlocked.
- *Doğrulanmadı* and *Onaylanmadı* differ from *Doğrulandı* and *Onaylandı* only by the letters *-ma-*, so check them letter by letter, and never let a budget cut the end of *Bilinmiyor* or any negated word.

**Other rules:**

- *kilit* softens to *kilidi* before a vowel. **Use *kilidi* only in the unlock phrases.** For the locked side write *kilit* plus a noun (*kilit durumu*, *kilit çubuğu*) or the verb *kilitle*. Otherwise the term check can't tell the two sides apart. So where English *electronic lock* would take an ending, write *elektronik kilit özelliği* or *elektronik kilit ayarı*, not *elektronik kilidi*.
- Don't describe an unlock as removing the lock (*Kilidi kaldır*); say *Kilidi aç*.
- **On and off:** elsewhere, settings and lights use **Açık** and **Kapalı**. Never *Evet/Hayır* in their place.
- **Keep every negation. Use the natural negated verb, and on a narrow label keep the negation inside the word** (section 5). `check.py` reads the negated verb endings (*doğrulanamadı, denenmedi, okunamıyor, kilitlenmez, silinmeyecek, dokunmayın*) and the words *değil, değildir, yok, asla, hiç, hiçbir, olmadan, hayır*. It doesn't count the bare -me/-ma form, which is banned above anyway, *-madan/-meden*, which also says "before" (*kilitlemeden önce*), or a *-mediği* clause. If one of those is the correct wording, write it and say so in your notes; a failed safety key falls back to English safely.
- Don't add reassurance the English lacks (*güvenli*, *garantili*), and don't add warnings it doesn't have.

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **The word for scooter is *scooter*,** matching NAVEE's manual heading and Turkish retail. *Skuter*, the word in the regulation (*Elektrikli Skuter Yönetmeliği*), has simpler suffixes and is the **debate item**. Fixed forms: *scooter’ı, scooter’a, scooter’ın, scooter’da, scooter’dan, scooter’ınız, scooter’ınızı*; plural without an apostrophe: *scooterlar, Scooterlarım*.
- **Three speeds:** the setting is **Maksimum hız**. A limit below it is **Hız sınırı**. The measured value on a trip or ride is **En yüksek hız**. Never swap them.
- **`rides_tile_max_speed` is the measured value:** **En yüksek hız**, never *Maks. hız*. Its English is *Top speed*.
- **The English now follows the same split (D3, merged):** Maximum speed and Max. speed are the setting (`main_speed_*`, `dashboard_model_tile_top_speed`, `controls_experimental_speed_limit`), and top speed is the measured value (the `readings_maximum_speed_kmh` ladder). Translate them like any other key, with **Maksimum hız** / **Maks. hız** for the setting and **En yüksek hız** for the measured value.
- **Controls to try** (`more_lab_untested`) is **Denenecek özellikler**.
- **Rides vs trips:** a recorded ride is a **sürüş**. *Trip* (the distance since a reset) is **yolculuk**. A path on the map is a **rota**.
- **Battery is *pil*,** Android's word and much narrower (*Pil* 14.7, *Batarya* 46.6 at 12.5sp), and the glossary's. Riders and sellers mostly say *batarya* for a scooter, so this is a **debate item**. Where the phone's battery could also be meant, name it: *telefonun pili*.
- **Range is *menzil*,** the word NAVEE's Turkish retail listings use. *Menzil tahmini*. Never *aralık*.
- **Try vs retry vs test:** Trying a control (Experimental, Controls to try) is **Dene**; its results use **deneme**. An ordinary retry uses the phrase in Fixed words. A guided test is **test** (*Testi scooter dururken yapın*). *Test scheduled charging* is a check: **Zamanlanmış şarjı kontrol et**. A compatibility check is **uyumluluk kontrolü**.
- **Dashboard vs display:** Volt's Home layout is the **Gösterge paneli**, *panel* once clear. The scooter's own screen is the **scooter ekranı**. They never swap. Not *Pano*, which means the clipboard in Turkish software.
- **Settings vs setup:** settings are **Ayarlar**, a one-time setup is **kurulum**.
- **Restore vs set back:** bringing back a backup is **Geri yükle**. Undoing a try is **Geri al**, even when the English says restore. Energy recovery is **Enerji geri kazanımı**, unrelated to either.
- **Readback:** **geri okuma**, a neutral word, because a readback can also fail to confirm: *Geri okuma farklı*, *Ayar geri okumayla onaylandı*.
- **Verified vs confirmed:** verified is *doğrulandı*, confirmed is *onaylandı*. Keep each to its own English word.
- **Rejected:** *Rejected* is **Reddedildi**; *Refused by scooter* is **Scooter reddetti**.
- **Pair vs bind vs connect:** Bluetooth pairing is **eşleştir**. NAVEE account binding is **hesaba bağla**. A live link is **bağlan**, noun **bağlantı**. Bind and connect share a root, so always name the account when you mean binding.
- **Owner key parts:** the file is the **sahip kurulum dosyası**, shortened to **sahip dosyası** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Levels:** Energy recovery levels are ordinary words: *Kapalı, Düşük, Orta, Yüksek*.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English. **Eco tahmini** is a range option, translated.
- **Roles:** owner is **sahip**, shared rider is **ortak kullanıcı**. The compact role label (`more_role_shared`, English *Shared*) is **Ortak** (32.1dp at 12.5sp). Never *Paylaşılan*, which reads as a shared scooter.
- **Charge done by:** the feature name is **Şarj bitiş hedefi**, and its time setting **Bitiş saati**. The feature only plans a start, so never word it as a charge that has finished. In a sentence, quote the name: *“Şarj bitiş hedefi” kapalı*.
- **Alert vs prompt:** an alert is **uyarı**; the scooter's beeps are **Bip sesi**, so the two don't collide. Notification is **bildirim**; *Notify me* is **Bana bildir**.
- **Turn signal is always *Dönüş sinyali*,** so it can't be read as the Bluetooth signal in *Sinyal kesildi*.
- **Quick settings:** the Dashboard's shortcut row and its widget (*Quick access*, *Quick settings*) are **Hızlı erişim**. Never *Hızlı ayarlar*: that is Android's pull-down panel.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does.
- **Experimental LAB badge:** **LAB** stays. TalkBack speaks **Deneysel**.

## 8. Suffixes and placeholders

**Never attach a suffix to a placeholder.** Turkish case endings follow the vowels and last sound of the word before them, and Volt can't know those for a value: a scooter's name, a time, a number. *%1$s’e* is right for some values and wrong for others (*14.30’a*, *15.00’e*; *%80’e*, *%90’a*). Don't write bracketed endings either (*%1$s’(y)e*). Microsoft's guide: "Content of the placeholder is unknown, therefore no suffixes are allowed."

Phrase around it, in this order of preference:

1. **A label and a colon, with the value last.** The value stands alone with no case to agree with.
   - *Charging to %1$s%%* → `Şarj hedefi: %%%1$s`
   - *Starts at %1$s* → `Başlangıç saati: %1$s`
   - *Last known lock state: %1$s* → `Bilinen son kilit durumu: %1$s`
   - *%1$s is at %2$s%%.* → `%1$s: %%%2$s.`
2. **A head noun right after the placeholder, which carries the suffix.** The value then needs no ending.
   - name: `%1$s adlı scooter’a bağlanılıyor`, not `%1$s’e bağlanılıyor`
   - model: `%1$s modelini destekler`
   - setting: `%1$s ayarını deneyin`
   - level: `%%%1$s seviyesine şarj edilir`
   - time: `Saat %1$s itibarıyla` or `%1$s saatinde`
   - value: `%1$s değeri okundu`
3. **A subject that needs no ending.** The bare nominative takes nothing: `%1$s bağlandı`, `%1$s silinemiyor`.

More rules:

- A placeholder that holds a number is followed by a singular noun: `%1$s dakika`, `%1$s sürüş`.
- A value inserted after a colon has to read in the nominative, so the word filling it must be too: *kilitli*, not *kilitli olarak*.
- Turkish puts the verb last, so placeholders move. Keep their indexes and reorder freely.
- A placeholder that holds a whole sentence stays a separate sentence: don't build it into a clause.
- The same applies to a word you can see but Volt fills in: don't add endings to a filled-in label.
- Words you can see take endings normally: *Volt’a*, *PIN’i*, *Bluetooth’u*.

## 9. Android's own wording

Quote Android's settings as Android writes them: **USB hata ayıklama**, **Geliştirici seçenekleri**, **Derleme numarası**. *Ayarlar › Telefon hakkında* can differ, and phone makers rename it, so write generally where you can: *ayarlarda telefon bilgilerini açın*.

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
| Try again, Try shortly | an ordinary retry after a failure; not the experimental "try" | **Yeniden deneyin**; *Birazdan yeniden deneyin*. As a button, **Yeniden dene**. Never bare *Dene* or *Test et* |
| unconfirmed clause | after a negated confirm or verify (rule 3); never presupposes the event | **-ip -mediği:** *Zamanlamanın kapanıp kapanmadığı doğrulanamadı*, or a separate noun: *Zamanlama durumu doğrulanamadı*. Never bare *-dığı/-diği* before a negated *doğrula-* or *onayla-*: *kapandığı doğrulanamadı* says it did turn off |
| not reported | the scooter gave no value; never reads as confirmed, and not a calque of "reported" | **veri yok**. Says only that no value came, not that anything is off or fine. Never *rapor edilmedi* |
| reported (by the scooter) | a value the scooter or controller says, not one that was measured or proven | **bildirilen**; verb **bildirir**: *Scooter bildiriyor*, *Scooter’ın bildirdiği*. A claim, not a measurement. Never *raporlanan* or *onaylanan*. *rapor* stays the word for a document |
| NAVEE logging | debug logging on the phone (activity log), never account registration | **NAVEE etkinlik günlüğü**. Never *kayıt* alone, which reads as signing up |
| restore logging | turning that logging back to how it was (usually off) | **etkinlik günlüğünü eski hâline getir**. It brings back no file or backup |
| Volt's helper | the small helper Volt starts inside Shizuku | **Volt yardımcı aracı**; *yardımcı araç* alone once clear. Never *asistan*. The script on the computer is the different **yakalama aracı** |
| stock charger | the charger that came with the scooter | **orijinal şarj cihazı**. Never *standart* |
| high-speed charger | a faster charger the rider may own | **hızlı şarj cihazı**; alone, *Hızlı*. This is about charging, not riding speed |
| gap (in a recording) | a stretch of a recording with no data | **veri boşluğu**. Means no data was recorded, not that power or the ride was cut |
| elevation | height above sea level on a route | **rakım** |
| current direction | which way battery current flows | **akım yönü**. Never *yön* alone, which reads as a route |
| throughput | how much energy has passed through the battery | **enerji akışı**; an amount that passed through, not a rate |
| battery gauge | the scooter's own battery percentage reading | **pil göstergesi**. The scooter's own figure, not a measurement |
| raw | an unprocessed value, also used as a short tag | **ham**; as a tag **HAM**, where the English writes RAW |
| last seen | when Volt last had data from the scooter | **Son okuma**; with a value, *Son okuma: %1$s*. The data may be old, so never word it as current |
| add | adding a widget, scooter or item | **ekle**: *Widget ekle*, *Başka bir scooter ekleyin* |
| enter (typing) | typing a value | **gir**: *Yeni PIN girin* |
| the app | one word for "app" used everywhere | **uygulama**: *NAVEE uygulaması*, *resmî uygulama*. *uygulama* can also mean "practice" elsewhere, not in Volt |
| connect the scooter vs connect yourself | connecting to the scooter is one verb form; state which | The scooter takes -a: **Scooter’a bağlanın**, *Önce scooter’a bağlanın*. The button stays *Bağlan*; plugging in says *şarj cihazını takın* |
| set a value | one verb for setting a value on the scooter | **ayarla**: *PIN ayarla*. The noun stays *ayar*, and setting something back stays *Geri al* |
| "Removes…", "Replaces…" in dialogs | the form of rule 2, with one example | Passive future, in dialogs and hints alike: *Bu sürüş silinecek*, *Harita açılacak*. Never *Sil…* or *Aç…*, which read as commands |
| screen and section titles | noun form or verb form, with one example | Noun form: *Yedekleme ve geri yükleme*. If the title is an action with no good noun, the stem: *Zamanlanmış şarjı kontrol et*. Never the -in imperative |

## 10. Sources consulted

**NAVEE's official Turkish: barely found, and only for the word *scooter*.** The official app's Turkish wording was **not found**: no public page shows its strings for lock, charge limit, PIN, owner key or experimental features, and it may not exist. Those words are Claude's own proposals, and the official app may differ. No APK was downloaded.

- NAVEE's Turkish manual, [V40i Pro / V50i Pro TR](https://service.naveetech.com/wp-content/uploads/2024/07/V40i-Pro-V50i-Pro-TR.pdf), and the multi-language [V40 EU manual](https://service.naveetech.com/wp-content/uploads/2024/07/NAVEE-V40-Instruction-Manual-EU-version.pdf): **search snippets only.** The TR PDF returned 404 when fetched. Snippets show the heading *Elektrikli Scooter / Kullanım Kılavuzu* and safety prose; nothing on lock, app features or settings.
- NAVEE retail listings in Turkey, titles and search summaries only (pages returned 403 or unrelated content): [MediaMarkt ST3 Pro](https://www.mediamarkt.com.tr/tr/product/_navee-st3-pro-1350w-75-km-menzil-elektrikli-scooter-1246808.html), [Turkcell GT3](https://www.turkcell.com.tr/pasaj/hobi-oyun/scooterlar-bisikletler/navee-gt3-elektrikli-scooter-700w-guc-50-km-menzil-tcs), [Vatan ST3](https://www.vatanbilgisayar.com/navee-st3-1350w-elektrikli-scooter.html): *elektrikli scooter*, *menzil*, TCS kept as *TCS*. These are sellers' words, not NAVEE's.
- [Elektrikli Skuter Yönetmeliği](https://www.alomaliye.com/2021/04/14/elektrikli-skuter-yonetmeligi/) (Resmî Gazete, 14 April 2021), for *skuter*; press uses both *skuter* and *scooter*.
- Xiaomi's Turkish pages, search summary only ([Mi Elektrikli Scooter 3](https://www.mi.com/tr/product/mi-electric-scooter-3/), [4 Pro 2nd Gen](https://www.mi.com/tr/product/xiaomi-electric-scooter-4-pro-2nd-gen/)): *Elektrikli Scooter*, energy recovery wording, *km/sa*. A competitor, used for established terms.
- [Microsoft Turkish localization style guide](https://download.microsoft.com/download/c/7/e/c7e1f405-1a5a-418d-a56d-3a8c183cabd9/tur-tur-StyleGuide.pdf), read in full text for these points: siz; "Percentage sign always leads the number" (`%%%d`); no suffixes on placeholders; singular noun after `%d`; acronym suffixes (*SMTP’ye*, *API’si*); no automatic case change for i and ı; *dk.*; no em dash. It asks for straight quotes, which this guide doesn't follow.
- Android's own Turkish, read from AOSP: [SettingsLib values-tr](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-tr/strings.xml) (*USB hata ayıklama*, *Geliştirici seçenekleri*, *Bilinmiyor*, *Her zaman*, *Bağlanıyor…*, and *Hiçbir zaman* as a standalone option, `zen_mode_settings_summary_off`). `core/res` values-tr has *Asla İzin Verme*. *Derleme numarası* and *Telefon hakkında* are from memory, unverified.
- [Dotted and dotless I in computing](https://en.wikipedia.org/wiki/Dotted_and_dotless_I_in_computing), background on case mapping.
- **TDK could not be consulted directly.** The rules on quotes, apostrophes and *dk./sa./sn.* come from memory of the *Yazım Kılavuzu*, and the word pairs in section 3 and every term not cited above (*gidon, hız sabitleyici, çekiş kontrolü, kilidi açık, sahip anahtarı, ortak kullanıcı*) are general knowledge and judgment, not checked by a native speaker.
- Width figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `saira_medium.ttf`. Case handling: read in `Ui.sectionLabel` and in B19's `MainActivity`.
