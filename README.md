# Мали пречник

Најпре Богу хвала за све, а потом наравно и Милану Обрадовићу на [_"Пречнику страним речима и изразима"_](https://www.prometej.rs/prodavnica/popularna-lingvistika/precnik-stranim-recima-izrazima/), пошто је по угледу на Миланову књигу, ово приложеније управо и настало.
`Мали пречник` је Андроид приложеније у виду малог пречника. Основна замисао је једноставна: корисник унесе туђицу и добије један или више српскословенских предлога, уз појашњење, порекло туђице и могући додатак.
Приложеније има местно (локално) SQLite складиште за стварни рад на надланику (моб. телефону), а Firebase Firestore се користи само за спонишне (онлајн) предлоге и гласање. Службено складиште се припрема уреднички кроз питонске почерке (скрипте) и уклапа у приложеније у виду `prechnik_seed.db`.

## Шта приложеније ради?

- претрага у смеру `туђица -> српслв`;
- претрага у смеру `српслв -> туђица`;
- преглед свих туђица сложених по почетном слову;
- додавање, измена и брисање личних уноса;
- увоз и извоз личног складишта као JSON или SQLite `.db`;
- лично складиште које корисник мења на свом уређају;
- јавно складиште упаковано у приложеније, које се освежава новим издањима;
- слање предлога у Firestore;
- гласање за предложене српскословенске предлоге;
- уредничко повлачење Firestore предлога и прављење новог службеног складишта.

## Потребни алати

За Android развој:

- Android Studio;
- Android SDK који Android Studio сам подеси;
- JDK, најлакше онај који долази уз Android Studio;
- Git, ако се код преузима или прати кроз издавање;
- Android телефон са укљученим USB debugging-ом, или опонашач;
- спонишна (интернет) веза ако се користи Firebase део.

За уредничке и складишне почерке:

- Python 3;
- `firebase-admin` следовање, само ако се повлаче Firestore предлози;
- `reportlab` следовање, само ако се гради A5 PDF речник;
- SQLite није потребно посебно пристављати за питонске почерке, јер се користи уобичајена питонска јединица - `sqlite3`.

На Виндоузу се подухват може градити кроз Android Studio, али и из PowerShell-а преко Gradle омотача који је већ у подухвату.

## Главна омотница (директоријум)

```text
app/
  Android приложеније: Kotlin, Jetpack Compose, SQLite, Firebase SDK.

app/src/main/assets/
  Почетно јавно складиште: prechnik_seed.db и prechnik_seed.json.

app/src/main/java/rs/maliprecnik/app/
  Kotlin изворна скритка приложенија.

database/
  Уредничко складиште, Python скрипте, Firestore правила и складиштна упутства.

mali_prechnik_py/
  PySide6 PC уредник за једноставно попуњавање `database/input/official_entries.json`.

docs/
  Додатни списи, на пример Firebase белешке и privacy policy.

art/
  Изворни сликовне ствари, нпр. сликице за приложеније.

gradle/
  Gradle омотач и списак зависности.
```

Приказ није прављен кроз XML layout записја (фајлове). Целокупан приказ је у Kotlin-у преко Jetpack Compose јединица.

## Најважнија Kotlin записја

```text
MainActivity.kt
  Улаз у приложеније, учитавање складишта, увоз/извоз SQLite записја.

PrechnikScreen.kt
  Главни Compose приказник (екран), навођење између страница и Android одабирачи записја.

PrechnikNavigation.kt
  Горња трака и садржај с' лева.

DictionaryDatabase.kt
  SQLiteOpenHelper, образац складишта, читање, упис, измена, брисање и увоз/извоз.

DictionaryEntry.kt
  Основне јединице врста података: DictionaryEntry и ReplacementOption.

DictionaryJson.kt
  JSON увоз и извоз.

SearchScreen.kt
  Претрага, приказ туђице и приказ траженог у оба смера.

EntryEditorScreen.kt
  Додавање и измена речи.

WordListScreen.kt
  Списак туђица по почетним словима.

StorageScreen.kt
  Лично/јавно складиште и JSON/DB увоз/извоз.

VotingScreen.kt
  Приказ Firestore предлога и гласање.

RemoteProposals.kt
  Firebase провере и Firestore приступ.
```

## Складиште у приложенију

Тренутни SQLite образац је `7`-мо издање.

Постоје две главне преграднице (табеле):

```text
foreign_terms
  id
  word
  normalized_word
  origin
  addendum
  updated_at

replacement_options
  id
  foreign_term_id
  replacement_word
  normalized_replacement_word
  explanation
  weight
  updated_at
```

То значи:

- једна туђица има једно `Порекло`;
- једна туђица има један `Додатак`;
- једна туђица има више српскословенских предлога;
- сваки српскословенски предлог има своје `Појашњење`;
- сваки српскословенски предлог има `weight`, односно тежину редоследа, где `1` значи најбоља међу тренутним понудама.

Корисник на уређају ради са личним SQLite складиштем `prechnik.db`. Јавно складиште је `app/src/main/assets/prechnik_seed.db` и преписује се при чистом пристављању приложенија на уређај. Каснија освежења приложенија не преписују самостално лично складиште корисника.

## Како отворити подухват

1. Преузети или повући подухват.
2. У Android Studio изабрати `Open`.
3. Отворити корену омотницу:

   ```text
   C:\Users\aleksandar.milosevic\Documents\poduhvati\vezbanye\mali_precnik
   ```

4. Сачекати да Android Studio заврши Gradle Sync.
5. Ако Android Studio направи `local.properties`, то записје не треба чувати у git-у.
6. Ако се користи Firebase, додати `app/google-services.json`.
7. Изабрати надланик или опонашач и покренути приложеније.

## Градња из PowerShell-а

На овом рачунару се може користити JDK из Android Studio-а:

```powershell
cd C:\Users\aleksandar.milosevic\Documents\poduhvati\vezbanye\mali_precnik
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon assembleDebug
.\gradlew.bat --stop
```

Рашчивијавашко приложеније се добија на:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Ако се промени SQLite образац, најчистије је у развоју обрисати старо приложеније са надланика и поново је спустити, да не остане старо местно складиште.

## Firebase подешавање укратко

Firebase није потребан за местни речник, али је потребан за предлоге и гласање.
Глагољив поступак је у:

```text
database/README.md
docs/firebase_setup.md
```

У најкраћем:

1. Направити Firebase подухват.
2. Додати Андроид приложеније са именом пошиљке:

   ```text
   rs.maliprecnik.app
   ```

3. Преузети `google-services.json`.
4. Ставити га у:

   ```text
   app/google-services.json
   ```

5. Укључити Firebase провере и `Anonymous` пријављивање.
6. Направити Cloud Firestore складиште.
7. Објавити правила из:

   ```text
   database/firestore.rules
   ```

Званична упутства:

- https://firebase.google.com/docs/android/setup
- https://firebase.google.com/docs/auth/android/anonymous-auth
- https://firebase.google.com/docs/firestore/security/rules-conditions
- https://firebase.google.com/docs/firestore/locations

## Уреднички рад са службеним складиштем

Службени унос је:

```text
database/input/official_entries.json
```

Из њега се праве:

```text
database/generated/prechnik_seed.db
database/generated/prechnik_seed.json
app/src/main/assets/prechnik_seed.db
app/src/main/assets/prechnik_seed.json
```

Наредба:

```powershell
python database\scripts\build_seed_database.py --copy-to-android-assets
```

Када се Firestore предлози повуку, ручно прегледају и споје, поново се покреће иста наредба, па се приложеније гради и објављује као ново издање.

Детаљан ток је у `database/README.md`.

## Play Store белешке

Безбедносна правила су у:

```text
docs/privacy_policy.md
```

При попуњавању Play Console `Data safety` обрасца не означавати да приложеније „не прикупља податке”, јер Firebase део чува скривени user id, корисничке предлоге, гласове и пријаве предлога/предлагача. Кратак подсетник је у:

```text
docs/play_store_data_safety.md
```

## Шта чувати у git-у

Чувати:

- `app/src/...` изворна скритка;
- `database/input/official_entries.json`;
- `database/scripts/...`;
- `database/firestore.rules`;
- `database/schema/prechnik_schema.sql`;
- `app/src/main/assets/prechnik_seed.db`;
- `app/src/main/assets/prechnik_seed.json`;
- `gradlew`, `gradlew.bat`, `gradle/wrapper/...`;
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`;
- списе и сликице.

Не чувати:

- `app/build/`, `build/`, `.gradle/`, `.kotlin/`;
- `local.properties`;
- лична Android Studio записја;
- кључеве и издавачка потписана записја;
- `app/google-services.json`;
- Firebase JSON за услуге налога;
- `database/private/`.

`app/google-services.json` не представља услуге налога и није исто што и лични кључ. 
Ипак, за јавно GitHub складиште ово записје се не чува у git-у. 
Приложеније се и без њега гради као местни речник; Firebase предлози и гласање раде тек када сарадник местно дода свој `app/google-services.json` из Firebase Console.

## Честе грешке

`CONFIGURATION_NOT_FOUND`

Firebase приложеније је пронађено, али није укључен `Anonymous` пријаву. Отворити Firebase приступник, па `Authentication` -> `Sign-in method` -> `Anonymous` -> `Enable`.

`PERMISSION_DENIED`

Firestore правила нису објављена или не одговарају скритки. Проверити правила у Firestore-у и налепити садржај из `database/firestore.rules`.

Нема нових речи после измене `prechnik_seed.db`

Ако је приложеније већ било пристављено, корисничко лично складиште се не преписује самостално. У развоју треба обрисати приложеније са телефона или у приложенију користити `Складиште` -> `Препиши лично складиште`.

Зелено дугме за покретање у Android Studio-у је сиво

Најчешће није завршен Gradle Sync или нема покретачких подешавања за `app` јединицу. Покренути `File` -> `Sync Project with Gradle Files`, па проверити `Run` -> `Edit Configurations`.

## Основни ток за новог сарадника

1. Приставити Android Studio.
2. Повући пројекат.
3. Отворити корену омотницу у Android Studio-у.
4. Сачекати Gradle Sync.
5. По потреби додати `app/google-services.json`.
6. Покренути `assembleDebug`.
7. Спустити приложеније на надланик.
8. Ако се мења службено складиште, изменити `database/input/official_entries.json`.
9. Покренути `database/scripts/build_seed_database.py --copy-to-android-assets`.
10. Поново изградити приложеније.
