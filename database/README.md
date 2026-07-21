# База и Firebase за Мали пречник

Овај директоријум садржи све што је потребно за службену базу, Firestore
предлоге, гласање, обавештења и уреднички ток.

Android апликација ради са локалном SQLite базом. Firestore није главна база
речника; Firestore служи за предлоге корисника, гласање и уредничка
обавештења.

## Зашто није Google Sheet

За јавну измену базе разматран је Google Sheet, али је за овај случај практичнији
Firestore:

- више корисника може истовремено да шаље предлоге;
- Android апликација може директно да упише предлог;
- корисници могу да гласају из саме апликације;
- уредник може да објављује кратка обавештења без нове APK/AAB верзије;
- Firestore правила могу да ограниче ко и шта сме да уписује;
- уредник и даље ручно одобрава предлоге пре него што уђу у службену базу.

Због тога је коначни ток:

1. SQLite остаје службена локална база у апликацији.
2. Firestore чува предлоге, гласове и објављена обавештења.
3. Python скрипта повлачи Firestore предлоге.
4. Уредник ручно брише неприхваћене предлоге.
5. Друга Python скрипта спаја прихваћене предлоге у службени JSON.
6. Из службеног JSON-а се гради нови `prechnik_seed.db`.
7. Нова база улази у следећу верзију Android апликације.

## Лично и јавно складиште

У апликацији постоје два појма:

- лично складиште: SQLite база на уређају, у којој корисник ради и чува своје
  измене;
- јавно складиште: `prechnik_seed.db` упакован у апликацију, који се освежава
  новим верзијама апликације.

Оба складишта обухватају и туђице и засебан списак старих српских и словенских
речи. Када се апликација ажурира, лично складиште се не преписује само од себе.
Корисник на екрану `Складиште` може ручно:

- да препише лично складиште јавним;
- да дода само нове туђице и старе речи из јавног складишта;
- да увезе или извезе JSON;
- да увезе или извезе SQLite `.db`.

## Тренутна SQLite шема

Шема је верзија `8`.

```sql
CREATE TABLE foreign_terms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    word TEXT NOT NULL,
    normalized_word TEXT NOT NULL UNIQUE,
    origin TEXT NOT NULL DEFAULT '',
    addendum TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
);

CREATE TABLE replacement_options (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    foreign_term_id INTEGER NOT NULL,
    replacement_word TEXT NOT NULL,
    normalized_replacement_word TEXT NOT NULL,
    explanation TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(foreign_term_id)
        REFERENCES foreign_terms(id)
        ON DELETE CASCADE,
    UNIQUE(foreign_term_id, normalized_replacement_word)
);

CREATE TABLE old_words (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    word TEXT NOT NULL,
    normalized_word TEXT NOT NULL UNIQUE,
    addendum TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
);

CREATE TABLE old_word_synonyms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    old_word_id INTEGER NOT NULL,
    synonym TEXT NOT NULL,
    normalized_synonym TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(old_word_id)
        REFERENCES old_words(id)
        ON DELETE CASCADE,
    UNIQUE(old_word_id, normalized_synonym)
);
```

Једна туђица има:

- једно поље `poreklo`;
- једно поље `dodatak`;
- више предлога у `predlozi`;
- сваки предлог има своје `pojasnjenje`;
- сваки предлог има `tezina`, где је `1` најважнији предлог, а већи бројеви
  иду ниже у приказу.

## Службени JSON

Ручно прихваћене речи стоје у:

```powershell
database\input\official_entries.json
```

Корен службеног JSON-а има две независне листе:

```json
{
  "format": "mali-precnik",
  "version": 7,
  "entries": [],
  "stare_reci": []
}
```

`entries` садржи туђице, а `stare_reci` старе речи. Стари JSON фајлови који
имају само `entries` и даље могу да се увезу; тада је списак старих речи празан.

Облик једне туђице у `entries`:

```json
{
  "tudjica": "Фајл",
  "poreklo": "енг",
  "dodatak": "Напомена која се односи на целу туђицу.",
  "predlozi": [
    {
      "srpskoslovenski": "записје",
      "pojasnjenje": "Записје као спис у коме је нешто записано.",
      "tezina": 1
    }
  ]
}
```

`dodatak` је један по туђици. Не уписује се уз сваки предлог.
`tezina` је једна по српскословенском предлогу. Подразумевана вредност је `1`;
ако за једну туђицу постоји више предлога, мања тежина се приказује пре веће.

Стара реч се чува засебно од туђица:

```json
{
  "stara_rec": "двиг",
  "dodatak": "Означава излазак из себе и двиг ка нечему узвишеном.",
  "slicnoznacnice": ["труд", "напор", "рад", "покрет"]
}
```

Она нема порекло ни појединачна појашњења. Има само необавезни `dodatak` и
једну или више `slicnoznacnice`, преко којих ради претрага савремене речи.

## Грађење службене seed базе

Из корена пројекта:

```powershell
python database\scripts\build_seed_database.py
```

Ово прави:

```powershell
database\generated\prechnik_seed.db
database\generated\prechnik_seed.json
```

Када база треба да уђе у Android апликацију:

```powershell
python database\scripts\build_seed_database.py --copy-to-android-assets
```

Ово копира фајлове у:

```powershell
app\src\main\assets\prechnik_seed.db
app\src\main\assets\prechnik_seed.json
```

После тога треба поново изградити Android апликацију.

## Грађење A5 PDF мини речника

Из исте службене базе може се направити и PDF у A5 формату, погодан за пробну
штампу као мала књижица. За то се користи скрипта:

```powershell
database\scripts\build_a5_pdf.py
```

Пошто PDF мора да угради фонт који подржава српску ћирилицу, потребан је Python
пакет `reportlab`:

```powershell
python -m pip install reportlab
```

Подразумевано се чита:

```powershell
database\input\official_entries.json
```

а добија се:

```powershell
database\generated\mali_precnik_a5.pdf
```

Покретање из корена пројекта:

```powershell
python database\scripts\build_a5_pdf.py
```

Скрипта може да чита и готову SQLite базу:

```powershell
python database\scripts\build_a5_pdf.py --input database\generated\prechnik_seed.db
```

Или да се наведе посебан излазни фајл:

```powershell
python database\scripts\build_a5_pdf.py --output database\generated\probni_precnik_a5.pdf
```

На Windows-у скрипта сама покушава да употреби фонтове из `C:\Windows\Fonts`,
најпре Arial, па Times New Roman. Ако се жели други фонт, може се проследити:

```powershell
python database\scripts\build_a5_pdf.py --font-regular C:\Windows\Fonts\times.ttf
```

## Firebase од нуле

Ови кораци су потребни ако се пројекат поставља од почетка.

### 1. Направити Firebase project

1. Отворити Firebase Console:

   ```text
   https://console.firebase.google.com/
   ```

2. Кликнути `Add project`.
3. Унети име, на пример:

   ```text
   MaliPrecnik
   ```

4. Google Analytics није неопходан за овај пројекат.
5. Завршити прављење пројекта.

### 2. Додати Android апликацију

У Firebase project-у:

1. Кликнути `Add app`.
2. Изабрати Android.
3. Унети package name:

   ```text
   rs.maliprecnik.app
   ```

4. App nickname може бити:

   ```text
   Мали пречник
   ```

5. Debug SHA-1 није неопходан за Anonymous Auth и Firestore.
6. Преузети `google-services.json`.
7. Ставити га у:

   ```powershell
   app\google-services.json
   ```

Званично Firebase Android упутство:

```text
https://firebase.google.com/docs/android/setup
```

### 3. Gradle подешавање

У овом пројекту је већ додато:

```kotlin
implementation(platform(libs.firebase.bom))
implementation(libs.firebase.auth)
implementation(libs.firebase.firestore)
```

И Google Services plugin се примењује само ако постоји:

```text
app/google-services.json
```

То омогућава да се локални речник гради и без Firebase фајла, али предлози и
гласање тада неће радити.

### 4. Укључити Anonymous Authentication

У Firebase Console:

1. Лево отворити `Security`.
2. Изабрати `Authentication`.
3. Ако се први пут отвара, кликнути `Get started`.
4. Отворити `Sign-in method`.
5. Изабрати `Anonymous`.
6. Укључити `Enable`.
7. Кликнути `Save`.

Ово је неопходно зато што Firestore правила траже `request.auth != null` за
уписе. Апликација зато пре слања предлога користи anonymous sign-in.

Званично упутство:

```text
https://firebase.google.com/docs/auth/android/anonymous-auth
```

Ако овај корак није урађен, при слању предлога може се појавити:

```text
CONFIGURATION_NOT_FOUND
```

### 5. Направити Cloud Firestore базу

У Firebase Console:

1. Лево отворити `Databases & Storage`.
2. Изабрати `Firestore Database`.
3. Кликнути `Create database`.
4. За edition изабрати:

   ```text
   Standard edition
   ```

   `Enterprise edition` није потребан за овај пројекат.

5. За Database ID оставити:

   ```text
   (default)
   ```

   Kotlin код користи подразумевану базу преко `FirebaseFirestore.getInstance`.

6. За локацију изабрати европску локацију, на пример:

   ```text
   eur3
   europe-west1
   europe-west4
   ```

   Локација се после прављења базе не може лако мењати, па је треба изабрати
   пажљиво.

7. За почетни mode/rules изабрати `Production mode` или `Locked mode`.
8. Завршити прављење базе.

Званично упутство о локацијама:

```text
https://firebase.google.com/docs/firestore/locations
```

### 6. Објавити Firestore правила

У Firestore-у:

1. Отворити картицу `Rules`.
2. Налепити цео садржај фајла:

   ```powershell
   database\firestore.rules
   ```

3. Кликнути `Publish`.

Правила дозвољавају:

- јавно читање само група са `status = voting` и предлога са `status = approved`;
- упис нових предлога само као `status = pending`;
- један глас по anonymous корисничком id-ју за једну туђицу;
- увећање `votes_count` само за одобрен предлог и уз одговарајући запис у `votes`;
- пријаву појединачног одобреног предлога и његовог предлагача у подколекцију
  `reports`;
- читање само сопственог документа гласа или пријаве, без листања туђих гласова
  и пријава.

Званично упутство о `request.auth` у правилима:

```text
https://firebase.google.com/docs/firestore/security/rules-conditions
```

### 7. Проверити Android апликацију

1. Поново изградити апликацију:

   ```powershell
   .\gradlew.bat --no-daemon assembleDebug
   ```

2. Спустити је на телефон.
3. Отворити `Додај туђицу`.
4. Унети пробну туђицу и бар један предлог.
5. Кликнути `Предложи као нову реч`.
6. Треба да се појави:

   ```text
   Предлог је послат на уреднички преглед.
   ```

7. У Firebase Console отворити:

   ```text
   Firestore Database > Data
   ```

8. Треба да се појави колекција:

   ```text
   proposal_groups
   ```

9. Нови предлог се још неће видети на страници `Гласање`, јер прво мора да га
   одобри уредник.

## Firestore структура

Android апликација очекује овај облик:

```text
proposal_groups/{groupId}
  foreign_word
  normalized_foreign_word
  first_letter
  origin
  addendum
  type
  proposal_format = text
  status
  created_by
  created_at

proposal_groups/{groupId}/options/{optionId}
  proposal_text
  type
  votes_count
  status
  created_by
  created_at

proposal_groups/{groupId}/votes/{userId}
  option_id
  user_id
  created_at

proposal_groups/{groupId}/options/{optionId}/reports/{userId}
  user_id
  reported_author_id
  reason
  created_at

notifications/{notificationId}
  title
  author
  body
  status
  published_at
  updated_at
```

`groupId` је стабилан SHA-256 id нормализоване туђице.
`optionId` је стабилан SHA-256 id комбинације туђице и текстуалног предлога.

`notifications` је засебна колекција у истој Firestore бази. Android апликација
чита само документе где је `status = published`. Уредник преко Python скрипте
може да остави и `draft` или `hidden` обавештења, али она се не приказују
корисницима.

За обавештење се користи овај JSON облик:

```json
{
  "id": "2026-07-14-pocetno-obavestenje",
  "title": "Почетно обавештење",
  "author": "Уредништво",
  "published_at": "2026-07-14T12:00:00+02:00",
  "status": "published",
  "body": "Текст може да садржи **подебљано**, _искошено_, __подвучено__ и пуну интернет везу."
}
```

Подржана стања су:

- `published` - видљиво у Android апликацији;
- `draft` - сачувано у Firestore-у, али није јавно видљиво;
- `hidden` - сакривено, али није обрисано.

## Service account за уредничке Python скрипте

Ово није потребно Android апликацији. Потребно је само уреднику који повлачи
Firestore предлоге.

У Firebase Console:

1. Отворити `Project settings`.
2. Отворити `Service accounts`.
3. Кликнути `Generate new private key`.
4. Преузети JSON.
5. Сачувати га, на пример:

   ```powershell
   database\private\service-account.json
   ```

Тај фајл је приватни кључ и не сме у git.

## Python окружење за Firestore скрипте

За `build_seed_database.py` нису потребни спољни пакети.

За `fetch_firestore_proposals.py` треба:

```powershell
python -m pip install firebase-admin
```

Ако на Windows-у команда `python` није доступна, проверити да ли је Python
инсталиран и да ли су искључени Microsoft Store execution aliases за `python.exe`.

## Уреднички ток

### 1. Повлачење pending предлога из Firestore-а

```powershell
python database\scripts\fetch_firestore_proposals.py --service-account database\private\service-account.json
```

Ово прави:

```powershell
database\generated\review_report.md
database\input\firestore_ready_for_review.json
database\generated\reported_proposals.md
database\input\firestore_reported_for_review.json
```

`review_report.md` је за читање.
`firestore_ready_for_review.json` је за ручно означавање прихваћених предлога.
`reported_proposals.md` и `firestore_reported_for_review.json` служе за преглед
предлога које су корисници пријавили као неприкладне.

Ако треба прегледати и предлоге других статуса:

```powershell
python database\scripts\fetch_firestore_proposals.py --service-account database\private\service-account.json --include-all-statuses
```

### 2. Ручни преглед

Отворити:

```powershell
database\input\firestore_ready_for_review.json
```

За предлоге који смеју да постану јавно видљиви поставити:

```json
"odobreno": true
```

Предлоге који нису у реду оставити са `"odobreno": false` или их уклонити из
review JSON-а.

### 3. Одобравање предлога за јавно гласање

```powershell
python database\scripts\approve_firestore_proposals.py --service-account database\private\service-account.json
```

Ова скрипта одобрене текстуалне proposal/option документе пребацује у `status = approved`, а њихову
групу у `status = voting`. Тек тада корисници могу да их виде на страници
`Гласање`.

### 4. Преглед пријављених предлога

Отворити:

```powershell
database\input\firestore_reported_for_review.json
```

За пријављене предлоге које треба уклонити из јавног гласања поставити:

```json
"sakriti": true
```

Потом покренути:

```powershell
python database\scripts\moderate_reported_proposals.py --service-account database\private\service-account.json
```

Скрипта тим предлозима поставља Firestore `status = hidden`. Апликације читају
само `status = approved`, па се такви предлози више не приказују у `Гласању`.

### 5. Повлачење изгласаних предлога за службени преглед

Када прође довољно времена за гласање:

```powershell
python database\scripts\export_voted_proposals.py --service-account database\private\service-account.json --min-votes 50
```

Ово прави извештај за ручни уреднички преглед. Предлози се и даље не усвајају
аутоматски. У JSON-у који се добије треба ручно поставити:

```json
"usvojiti": true
```

само за оне предлоге који стварно треба да уђу у службену базу.

### 6. Спајање прихваћених предлога

Прво пробно:

```powershell
python database\scripts\merge_reviewed_proposals.py --dry-run
```

Ако се спаја изгласани извештај:

```powershell
python database\scripts\merge_reviewed_proposals.py --reviewed database\input\firestore_voted_for_official_review.json --dry-run
```

Ако је све у реду:

```powershell
python database\scripts\merge_reviewed_proposals.py
```

или за изгласани извештај:

```powershell
python database\scripts\merge_reviewed_proposals.py --reviewed database\input\firestore_voted_for_official_review.json
```

Скрипта преписује:

```powershell
database\input\official_entries.json
```

и пре тога прави backup.

### 7. Грађење нове службене базе

```powershell
python database\scripts\build_seed_database.py --copy-to-android-assets
```

### 8. Нова Android верзија

После нове базе:

1. повећати `versionCode` и по потреби `versionName` у `app/build.gradle.kts`;
2. изградити APK/AAB;
3. тестирати локалну претрагу, складиште, предлоге и гласање;
4. објавити нову верзију апликације.

## Уреднички ток за обавештења

Обавештења се не уграђују у APK. Налазе се у Firestore колекцији
`notifications`, па корисници могу да их повуку притиском на `Освежи
обавештења` у Android апликацији.

### 1. Повлачење тренутних обавештења

```powershell
python database\scripts\fetch_firestore_notifications.py --service-account database\private\service-account.json
```

Ово прави или освежава:

```powershell
database\input\notifications.json
```

Ако је Firestore колекција још празна, може се кренути од постојећег пример
фајла `database\input\notifications.json`.

### 2. Ручно уређивање JSON-а

Отворити:

```powershell
database\input\notifications.json
```

Додати ново обавештење у листу `notifications`, на пример:

```json
{
  "id": "2026-07-14-novo-obavestenje",
  "title": "Ново обавештење",
  "author": "Уредништво",
  "published_at": "2026-07-14T18:30:00+02:00",
  "status": "published",
  "body": "Овде иде писаније обавештења."
}
```

`id` треба да буде стабилан и јединствен. Најпростији облик је датум и кратак
опис латиницом без размака.

### 3. Пробна провера

```powershell
python database\scripts\publish_firestore_notifications.py --service-account database\private\service-account.json --dry-run
```

Ово проверава JSON и исписује колико би обавештења било уписано, али не мења
Firestore.

### 4. Објављивање на Firestore

```powershell
python database\scripts\publish_firestore_notifications.py --service-account database\private\service-account.json
```

После овога корисници у апликацији могу да отворе `Обавештења` и притисну
`Освежи обавештења`.

### 5. Брисање обавештења

Подразумевано, скрипта само уписује и освежава оно што постоји у JSON-у; не
брише документе који су у Firestore-у, а нису више у JSON-у.

Ако баш треба да се Firestore стање потпуно изједначи са JSON-ом:

```powershell
python database\scripts\publish_firestore_notifications.py --service-account database\private\service-account.json --delete-missing
```

Ово треба користити пажљиво. Често је мирније поставити `status = hidden`
уместо брисања.

## Честе грешке

### CONFIGURATION_NOT_FOUND

Укључити:

```text
Authentication > Sign-in method > Anonymous
```

### PERMISSION_DENIED

Проверити:

- да је Firestore база направљена;
- да је Database ID `(default)`;
- да су објављена правила из `database/firestore.rules`;
- да Android апликација има `app/google-services.json` за исти Firebase project.

### Предлог је послат, али се не види у гласању

Проверити у Firestore `Data`:

- да постоји `proposal_groups`;
- да документ има `status = pending`, ако још није одобрен;
- да документ има `status = voting`, ако треба да буде јавно видљив;
- да постоји подколекција `options`;
- да бар један option има `proposal_text`;
- да option има `status = pending` пре уредничког одобрења;
- да option има `status = approved` ако треба да се види на гласању.

### Корисник је пријавио предлог и предлагача

Пријаве се уписују испод одобреног предлога и садрже и `reported_author_id`,
односно anonymous Firebase id предлагача чији је предлог пријављен:

```text
proposal_groups/{groupId}/options/{optionId}/reports/{userId}
```

Један anonymous корисник може једном да пријави исти предлог и предлагача.
Уредник може ручно да погледа ове подколекције у `Firestore Database > Data` и
да, ако је потребно, уклони или врати предлог из гласања кроз уреднички
поступак.

### Апликација ради локално, али Firestore не ради

Локални речник не зависи од Firebase-а. Ако претрага ради, а гласање не ради,
проблем је скоро сигурно у:

- `google-services.json`;
- Anonymous Authentication;
- Firestore database;
- Firestore rules.

## Безбедносна напомена

Firestore правила су неопходна зато што Android апликација директно приступа
Firestore-у. Правила у `database/firestore.rules` су почетна за овај облик
апликације, али пре јавне објаве треба још размотрити заштиту од злоупотребе:

- App Check;
- ограничење броја предлога по кориснику;
- Cloud Functions за сложенију проверу;
- ручно брисање неуредних Firestore предлога;
- неослањање на гласове као аутоматско одобрење.
