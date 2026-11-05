# Volt style guide: Indonesian (id)

**Status: Claude-only proposal (2026-11-05), not final.** Claude drafted it alone from the sources in section 9; the three-vendor debate decides later. No native speaker has reviewed it. The Android folder is `values-in`, the legacy code, and the language tag is `id`.

## 1. Register and form of address

- The owner set **Anda**, always with a capital A: *skuter Anda*, *Anda dapat mencoba kontrol ini*. Never *kamu*, *kau*, *-mu*, *Bapak/Ibu* or *Saudara*.
- Leave *Anda* out wherever the sentence reads fine without it. An imperative needs no subject: *Hubungkan pengisi daya*.
- Calm, plain, standard Indonesian. No slang (*nggak*, *udah*, *aja*, *gimana*), no marketing tone, no exclamation marks in errors.
- **Buttons, toggles and menu actions use the bare verb, as Android does:** *Simpan*, *Hubungkan*, *Kunci*, *Hapus*. Never *Menyimpan* on a button: the meN- form reads as a process (*Menyimpan…* is "Saving…").
- **Instructions in sentences use the same bare imperative:** *Hubungkan ulang untuk memverifikasi.* Don't add *silakan*, *mohon* or *harap* unless the English says please; no softening is needed, and they lengthen labels.
- **Volt or the scooter as the subject uses the meN- form:** *Volt mengirim satu perubahan.* **What happened to a thing uses di-:** *Perubahan dikirim*, *Nilai ditolak*.
- **Indonesian has no tense, so add a time word only where the English needs it:** *akan* for will, *sudah* for already, *sedang* for in progress. A status that is simply true takes none: *Terhubung*.
- *Saya* is acceptable only where the English speaks as the rider: *Beri tahu saya*, *Skuter saya*.
- **Tidak versus belum:** *tidak* says no; *belum* says not yet. Keep them apart. Use *belum* only where this guide lists it or where the English says not yet. A firm English negative takes *tidak*: *Not supported* is *Tidak didukung*, never *Belum didukung*.

### Plurals

Indonesian has one plural category, `other`, and it covers every number, including 0 and 1. The form always keeps the count placeholder and must read correctly for any number.

- A noun after a number never changes: `%1$d perjalanan` reads right as *1 perjalanan* and as *12 perjalanan*.
- Never reduplicate after a number (*12 perjalanan-perjalanan*) and never use *beberapa*, *para* or *banyak* in place of the number.
- Where the English `one` form has no number and its `other` form does (*Deletes this ride*, *Deletes %1$d rides*), translate `other` with the placeholder: *Menghapus %1$d perjalanan*.
- Reduplication without a number is rarely needed. Plain *perjalanan* can mean one or many.

## 2. Punctuation and quotes

- Quotes: **“…”** first, **‘…’** inside. Never straight quotes.
- Name a screen label or button in “…” with the words exactly as that label shows them: *Ketuk “Simpan”*.
- A sentence-final period goes outside a quoted label, since it isn't part of the label: *Ketuk “Simpan”.* This is a judgment call; EYD's rule puts the period inside a quoted sentence, and that case rarely occurs in Volt.
- Keep the English's `·` separators and `…` as written. A verb in progress keeps `…` and takes meN-: *Menghubungkan…*
- No space before `:`, `;`, `?`, `!` or `%`. After a colon, use lower case unless a name follows: *Status kunci terakhir: terkunci.*
- Lists use commas. EYD puts a comma before *dan* and *atau* only in a list of three or more items: *baterai, lampu, dan suara*.
- A hyphen marks reduplication and affixes on a foreign or capitalized word: *rata-rata*, *ke-3*. The object suffix joins a word directly: *skuternya*.
- `&` may stay on a narrow label where the English has it: *Layar & suara*. In a sentence use *dan*.
- Keep placeholders, `**` marks, backticks, file names and commands unchanged.
- Saira has all of these marks, including U+00A0, and lacks U+202F. Don't use U+202F.

## 3. Capitalization

- **Sentence case everywhere:** buttons, labels, titles and section headers. Only the first word and names take a capital. Button: *Buka kunci*. Label: *Batas pengisian daya*. Title: *Kontrol eksperimental*. Never English Title Case, even when the English has it (*Walk Assist* is *Bantuan berjalan*).
- Feature names are lower case mid-sentence: *aktifkan pengisian terjadwal*.
- After a colon, lower case unless a name follows.
- Always capitalized: **Anda**, days and months (*Senin*, *Maret*: unlike Spanish or French), and names. Language names take a capital too: *bahasa Indonesia*.
- Keep Volt, NAVEE, PIN, USB, GPS, Bluetooth, Android, GPX, CSV, TCS as written. Keep the drive modes WALK, ECO, DRIVE, SPORT in capitals and untranslated: *mode SPORT*.

## 4. Numbers, dates and units

- Decimal comma and thousands period in text you write: *1.234,5*, *3,7*. Volt's `Fmt` formats live numbers for the phone's region, so its output can differ; never rewrite a placeholder's number or add separators around one.
- **Percent takes no space:** `%1$s%%` gives *85%*. This is the common Indonesian form (Chicago, as Ivan Lanin applies it; EYD itself has no rule for the symbol). `formatted="false"` text writes *100%*. As a word it is *85 persen*, but don't spell it out where the English has the sign.
- Numbers in the English stay as digits. Don't write them out or change them.
- Unit symbols stay exactly as written, with a no-break space (U+00A0) after a number or placeholder: *25 km/h*, *36 V*, *2 h 5 min*, *25 °C*. Symbols take no period: `km`, `mi`, `mph`, `h`, `min`, `s`, `V`, `A`, `W`, `Wh`, `Ah`. The owner's default keeps SI symbols, so write `km/h`, never the everyday *km/jam*, and `h`, never *j*.
- Unit words are translated: *kilometer*, *mil*, *jam*, *menit*, *detik*. Spoken text writes them out: *%1$s kilometer per jam*.
- Dates and times come from Volt's formatters; don't hard-code an order. A fixed date in prose reads *5 Maret 2026*. A fixed time reads *pukul 14.30*, 24-hour with a period, and takes no a.m. or p.m. A placeholder for a time needs *pukul* before it only when the sentence does: *Selesai pukul %1$s*.

## 5. Shortening labels for ladder rungs

Order: full → `_short` → `_compact` → icon. Never break a word mid-way and never shrink the text. Measure in Saira (`tools/l10n/fontmetrics.py`); don't count letters. Try these in order:

1. Drop words the screen already supplies: *Setelan berkendara* → *Berkendara*; *Kesehatan dilaporkan* → *Kesehatan*; *Kunci elektronik* → *Kunci*.
2. Use the shorter natural word pair: *pengisian daya* → *isi daya*; *dan* → *&*. Drop *yang*, *dari*, *oleh* and the object words. *Ditolak oleh skuter* → *Ditolak skuter*.
3. Use an abbreviation from this table. Nothing else.

| Full | Short | Width at 12.5sp (dp) | Note |
|---|---|---|---|
| Kecepatan maksimum | **Kec. maks.** | 127.7 → 60.9 | The setting. Fits the 90dp tile. *Kecepatan maks.* at 98.2 does not |
| Kecepatan tertinggi | **Kec. tertinggi** | 113.9 → 76.6 | The measured value. Never *Kec. maks.* |
| Kecepatan rata-rata | **Kec. rata-rata** | → 77.9 | Never *rata2* |
| Odometer | **Odo.** | 57.7 → 26.5 | Riders know ODO from the display |
| Kapasitas | **Kap.** | 57.0 → 25.7 | Fits as a full word in most places; prefer that |
| Perkiraan, estimasi | **Est.**, **est.** | 29 at most | From *estimasi*, a standard word. *Est. jarak*, chip `est.` Not *perk.* |
| maksimum, minimum | **maks.**, **min.** | | *min.* with a period is minimum; `min` without one is the unit |
| informasi | **info** | | No period: *Info & izin* 58.8 |

*Kec.* normally abbreviates *kecamatan*, a district, so use it only directly before a speed word as above.

Real ladders:

| Rung | `readings_remaining_range_km` | `readings_lock_status` | `readings_battery_health_percent` | `dashboard_model_qa_ride_settings` |
|---|---|---|---|---|
| Full | Perkiraan sisa jarak tempuh skuter | Kunci elektronik | Kesehatan baterai yang dilaporkan | Setelan berkendara (the English is over this 64dp budget too, so the check only warns) |
| Short | Est. jarak tempuh | Kunci elektronik | Kesehatan dilaporkan | Berkendara (67.0, under 90) |
| Compact | Jarak tempuh | Kunci | Kesehatan | |

Other measured fits: *Kunci* 31.9, *Setelan* 44.2, *Baterai* 42.1 (all under the 62dp tab budget); *Batas isi daya* 81.1, *Target selesai* 81.1, *Eksperimental* 83.2, *Jadwal isi daya* 87.4 (`dashboard_model_tile_charge_schedule`), *Setelan baterai* 88.1, *Layar & suara* 80.8, *Data & riwayat* 86.9, *Tentang & izin* 82.8 (under 90dp); *Skuter saya* 66.5 at 12sp.

**Tight spots to expect:**

- `onboarding_welcome_tile_rides` and its `_short`, 62dp at 12.5sp: *Perjalanan* is 62.6, so it fails by 0.6dp. Use **Riwayat** (48.2) on this welcome tile only, with a note for the judge. Nowhere else: `dashboard_model_qa_rides` (60.1 at 12sp, budget 64) keeps *Perjalanan*.
- `dashboard_model_qa_live_stats`, 64dp at 12sp: *Statistik langsung* is too wide. Use **Statistik** (46.2) there and on the 90dp tile (*Statistik langsung* is 104.8).
- `dashboard_model_qa_line_lights` and `dashboard_model_qa_star_lights`, 64dp at 12sp: *Lampu garis* (68.4) and *Lampu bintang* (83.6) are too wide. Use **Garis** and **Bintang** there, and the full names on the 90dp tiles (71.3 and 87.1).
- Compact `more_role_shared`: **Bersama** (51.5). Never *Dibagikan*, which reads as a shared scooter.

**Never use** SMS-style cuts (*yg*, *dgn*, *utk*, *tdk*, *blm*, *sdh*, *rata2*) or invented cuts (*Perj.*, *Eksp.*, *Peng.*, *Bat.*, *Pengis.*). *Tdk* and *blm* would also hide a negation. *Pengisi* and *Pengisian* are different words (section 7), so neither may stand as a cut of the other.

**Never abbreviate:** *Kunci, Buka kunci, Terkunci, Tidak terkunci, Belum diverifikasi, Tidak diketahui, Tidak pernah, Selalu, Alarm, PIN*. If one of these doesn't fit, report it in your notes rather than shortening it. Falling back to English is the safe outcome.

## 6. Safety wording

Use exactly these words. They match the glossary; don't use synonyms.

| English | Indonesian | Rules |
|---|---|---|
| Lock (action, noun) | **Kunci** | Other forms: *mengunci*, *dikunci*, *penguncian*. Never *gembok* |
| Locked | **Terkunci** | The state. *dikunci* only for someone having locked it |
| Unlock (action) | **Buka kunci** | Both words, always. Other forms: *membuka kunci*, *pembukaan kunci* |
| Unlocked | **Tidak terkunci** | Never *Terbuka*, *Kunci terbuka*, *Bebas* |
| Lock state unverified | **Status kunci belum diverifikasi** | Must never read as confirmed. Keep *belum* |
| Lock state unknown | **Status kunci tidak diketahui** | Must never read as confirmed |
| Unknown (alone) | **Tidak diketahui** | |
| Unverified | **Belum diverifikasi** | |
| Never | **Tidak pernah** | As a value and in a statement: *Volt tidak pernah mengubah firmware.* See below |
| Always | **Selalu** | *Always on*: **Selalu menyala** |
| Alarm | **Alarm** | Never *peringatan*: alert is **Peringatan**, and they stay distinct |
| Alarm sound | **Suara alarm** | |
| Stop (a sounding alarm) | **Hentikan** | |
| PIN | **PIN** | Not *kode* or *sandi* |
| Handlebar PIN | **PIN setang** | A password is **sandi**; keep them separate |
| Owner key | **kode pemilik** | A secret. Never *kunci pemilik*, see below. "Not a password" is *Bukan sandi* |
| Charge limit | **Batas pengisian daya**, short **Batas isi daya** | Never *plafon* or *batas atas* |
| Experimental | **Eksperimental** | |
| Untested | **Belum diuji** | |
| Unconfirmed | **Belum dikonfirmasi** | |
| Not confirmed | **Tidak dikonfirmasi** | |
| not affiliated with NAVEE | **tidak berafiliasi dengan NAVEE** | Never soften: no *tidak resmi*, *independen dari*, *tidak terkait langsung*. In a sentence: *Volt tidak berafiliasi dengan NAVEE.* |

**Words that look right and mean something else:**

- **"Kunci" means both lock and key.** This is why the owner key is **kode pemilik**: *Kunci pemilik ditolak* could read "Lock the owner: refused", and *Kunci* is also the lock button. Don't use *kunci* for any credential, for the proximity key or for a JSON key (*kunci JSON* is acceptable only in a technical string that has nothing to do with locking; prefer *nama kolom*).
- **"Buka" alone is Open.** *Buka* on its own opens an app or a file (`owner_shizuku_open`). It never stands for Unlock. *Buka* + *kunci* always travel together.
- **"Terbuka" is open, not unlocked.** It describes a page, a door or a file.
- **"Kunci dibuka", "Kunci dilepas", "Kunci dimatikan", "Kunci: nonaktif"** all say the lock came off. Use them only where the English says that, and word a plain unlock as *Buka kunci*.
- **"Tidak terkunci" holds the word "terkunci".** Always write *Tidak* in full and on the same line; in a narrow place, report it rather than shorten it. `check.py` warns on *tidak* for an English "un-": that warning is expected here and goes to the judge.
- **"Belum pernah" is softer than "Tidak pernah".** Use *Belum pernah* only for a past event that may yet happen, as the English "never connected" or "never tried" means: *Belum pernah terhubung*. A statement of what Volt or the scooter does stays *Tidak pernah*. A prohibition to the rider is *Jangan pernah*.
- **"Mati" means off and also dead:** *Baterai mati* can read as a dead battery. Motors and lights use *Mati*; settings use *Nonaktif*. *Sedang* is both Medium and "in progress": use it for the level only when the setting is named or the list shows it.
- **"Aman"** (safe): don't add it, or *dijamin*. Don't add warnings the English doesn't have.

**On and off:** settings use **Aktif** and **Nonaktif**, verbs **Aktifkan** and **Nonaktifkan**. Lights and motors use **Menyala** and **Mati**, verbs **Nyalakan** and **Matikan**. Never invert them and never use *Ya/Tidak* in their place. *Tidak aktif* is off, and is acceptable in a sentence.

Keep every negation. The checks count *tidak, tak, bukan, jangan, belum, tanpa*. Don't describe an unlock as removing the lock (*Lepaskan kunci*).

## 7. Scooter vocabulary

The glossary is authoritative; this explains the distinctions it can't.

- **Three speeds:** the setting is **Kecepatan maksimum**, narrow **Kec. maks.** A limit below it is **Batas kecepatan**. The measured value on a trip or ride is **Kecepatan tertinggi**, narrow **Kec. tertinggi**. Never swap them, and don't call a limit *maksimum*.
- **Held until the English terminology fix (D3) merges:** the English of `dashboard_model_tile_top_speed` and `controls_experimental_speed_limit` says Top speed for the setting, and the `readings_maximum_speed_kmh` full rung says maximum speed for the measured value. The glossary rows `top-speed` and `maximum-speed` already follow D3, so those words have the opposite meaning here. These keys, the whole `readings_maximum_speed_kmh` ladder and any `main_speed_*` key stay in English until then. Flag them in your notes; a judge answers fallback.
- **Rides vs trips:** a recorded ride is a **perjalanan**. *Trip*, the distance since a reset, stays **Trip**, as on Indonesian motorcycle clusters. A path on the map is a **rute**. To ride is **berkendara**, and a rider is **pengendara**.
- **Range:** the remaining distance is **jarak tempuh**, and its estimate **Perkiraan jarak tempuh**. NAVEE's Indonesian press says *jarak tempuh* too.
- **Try vs test:** *Try* is **Coba**, noun **percobaan**. A guided test is **uji**, noun **pengujian**: *Uji pengisian terjadwal*. A compatibility check is **pemeriksaan kompatibilitas**.
- **Dashboard vs display:** Volt's Home layout is the **Dasbor**. The scooter's own screen is the **layar skuter**. They never swap. *Display & sound* is **Layar & suara**.
- **Settings vs setup:** settings are **Setelan**, Android's word. A one-time setup is **penyiapan**. Never *pengaturan* for either: it is common elsewhere but would add a third word.
- **Restore vs set back:** bringing back a backup is **Pulihkan**. Undoing a try is **Kembalikan**. **Pemulihan energi** is unrelated, so always write *energi* with it.
- **Readback:** **pembacaan ulang**, neutral, because a readback can also fail to confirm. Telemetry readings are **pembacaan**.
- **Pair vs bind vs connect:** Bluetooth pairing is **pasangkan**. NAVEE account binding is **tautkan**. A live link is **hubungkan**, and its state **Terhubung** or **Tidak terhubung**. To end the link is **Putuskan**. Android's own menu says *Sambungkan perangkat baru* for pairing; quote that only when pointing at it, because *sambungkan* sits too close to *hubungkan*.
- **Owner key parts:** the file is the **file penyiapan pemilik**, shortened to **file pemilik** on onboarding steps. `navee-owner-setup.json` stays as written. Website button names are English until the website changes, so quote them in English.
- **Roles:** owner is **pemilik**, shared rider is **pengendara bersama**, compact **Bersama**.
- **Charge words, one letter apart:** **pengisi daya** is the charger, **pengisian daya** is charging. **Mengisi daya** is the status Charging. Charged to a level is *Terisi hingga %1$s%%*.
- **Charge done by:** the feature name is **Target selesai isi daya**, narrow **Target selesai**. The feature only plans a start, so *Target* stays and it never reads as finished. In a sentence, quote the name: *“Target selesai isi daya” nonaktif*.
- **Alerts:** an alert is **peringatan**: *Peringatan baterai*, *Peringatan pengisian daya*, *Peringatan baterai lemah*. Stop watching is **Berhenti memantau**. **Berhenti** is also Parked (stationary): *saat skuter berhenti*. *Hentikan* is the action on an alarm.
- **Levels:** Energy recovery levels are *Nonaktif, Rendah, Sedang, Tinggi*. Battery low is **baterai lemah**, Android's phrase.
- **Eco, Turbo, Boost:** the *Eco* and *Turbo* acceleration profiles and the *Boost* mode stay in English, as the scooter shows them. **Perkiraan Eco** is a range option, translated.
- **Motor:** in everyday Indonesian *motor* also means a motorcycle. On a scooter screen it reads correctly, so keep *motor ganda*, *motor tunggal*.
- **Quick access:** **Akses cepat**. Never *Setelan cepat*: that is Android's pull-down panel.
- **Parts:** handlebar **setang**, headlight **lampu depan**, tail light **lampu belakang**, turn signal **lampu sein**, brake **rem**, tire **ban**, battery **baterai**, firmware **firmware**, phone **ponsel**, computer **komputer**, file **file**, report **laporan**, history **riwayat**.
- **Touch:** tap is **ketuk**, a physical press is **tekan**, slide is **geser**.
- **Common words:** Cancel *Batal*, OK *OK*, Close *Tutup*, Done *Selesai*, Save *Simpan*, Delete *Hapus*, Back *Kembali*, Try again *Coba lagi*, Selected *Terpilih*, Not reported *Tidak dilaporkan*, Share *Bagikan*, Skip *Lewati*, Copy *Salin*, Install *Pasang*.
- **Keep in English:** report contents, file names, Telemetry names, model names. Say the content is in English when the source does: *dalam bahasa Inggris*.
- **Experimental LAB badge:** **LAB** stays; TalkBack speaks **Eksperimental**.

## 8. Android's own wording

Quote Android's settings as Android writes them: **Proses debug USB**, **Opsi developer**, **Nomor build**, **Setelan**. The way to the build number is *Setelan › Tentang ponsel*. Phone makers rename these, so write generally where you can: *di setelan, buka informasi ponsel*.

## 9. Sources consulted

**NAVEE's official Indonesian: not found.** No NAVEE page, manual or app text in Indonesian turned up, and the official app's Indonesian wording, if it has one, is unknown. So the words for lock, charge limit, PIN, owner key and experimental features are Claude's own proposals, and the official app may differ. No APK was downloaded.

- NAVEE press in Indonesia, not NAVEE's own text: [iNews on RCTI+, "Ekspansi di Indonesia, Navee Boyong 4 Skuter Listrik Baru"](https://www.rctiplus.com/news/detail/otomotif/5400550/ekspansi-di-indonesia-navee-boyong-4-skuter-listrik-baru): *skuter listrik*, *kecepatan maksimum*, *jarak tempuh*, *pengisian daya*. It says PT Denka Pratama Indonesia distributes NAVEE there; **their manuals are the first place to look at the debate**, and I did not reach them.
- *Skuter listrik* as the general word: [Kontan](https://industri.kontan.co.id/news/ini-yang-bakal-diatur-kemenhub-soal-penggunaan-skuter-listrik), [Bisnis](https://ekonomi.bisnis.com/read/20200831/98/1285469/baca-ini-aturan-penggunaan-skuter-listrik-hingga-otoped), [detikOto](https://oto.detik.com/berita/d-5094271/ini-aturan-main-otoped-hingga-skuter-listrik-di-jalan).
- Android's own Indonesian, read from AOSP: [SettingsLib values-in](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values-in/strings.xml) (*Proses debug USB*, *Opsi developer*, *Terhubung*, *Tidak terhubung*, *Tidak diketahui*, *Tidak pernah*, *Sambungkan*) and [Settings values-in](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values-in/strings.xml), first part only (*Setelan*, *Sambungkan perangkat baru*, *sandi*, *Membuka kunci ponsel*, *tidak terkunci*). *Nomor build*, *Tentang ponsel*, *Pasangkan* and *ketuk* were not visible there and come from memory.
- Motorcycle manual usage, weaker evidence: [Indomobil electric motorcycle manual](https://indomobilemotor.co.id/cms-assets/documents/manuals/Bs8AMntMzhjbn0AfeSHv.pdf), seen as a search summary only (*Lampu Sein*, *Mileage/Odometer*).
- *Pengereman regeneratif* is the Indonesian technical term ([Mercu Buana](https://publikasi.mercubuana.ac.id/index.php/jte/article/view/3132)). Volt uses *Pemulihan energi* for the label, a literal match of Energy recovery; **debate item**.
- *Cruise control* and *Kontrol jelajah*: no Indonesian source confirmed either. Cruise control kept as the loan from general knowledge of car brochures; **debate item**.
- Percent, thousands and decimals: [Deepublish on percent](https://penerbitdeepublish.com/panduan-menulis/penulisan-persen-yang-benar/) (EYD has no rule for the symbol; Ivan Lanin follows Chicago, no space; ISO asks for one) and [Mamikos](https://mamikos.com/info/cara-penulisan-bilangan-mata-uang-qna-feb-pljr/) (EYD V, *Rp5.000,00*). Both are secondary. The official EYD V text from Badan Bahasa was not read; rules on quotes, capitals, list commas and times come from memory of it.
- [Microsoft's language resources page](https://learn.microsoft.com/en-us/globalization/reference/microsoft-language-resources) was read, but its Indonesian style guide was not, so nothing here cites it.
- Width and glyph figures: measured with `tools/l10n/fontmetrics.py`, java.awt, `res/font/saira_medium.ttf`. Saira has every mark this guide uses, and lacks U+202F. Wording checks: probes through `check.check_key`.

**Debate items, all judgment calls:** *kode pemilik* versus *kunci pemilik*; *Tidak terkunci* as the Unlocked word; *pengendara bersama* (it can read as riding together; *pengguna bersama* is the alternative); *Trip* left as a loan; *Pemulihan energi*; *Cruise control*; *pasangkan* versus Android's *sambungkan*; *Kecepatan start dorong*; *Kec.* for speed.
