---
title: Firebase подешавање за „Мали пречник”
---

# Firebase подешавање за „Мали пречник”

Ови кораци су потребни када се жели стварно онлајн предлагање и гласање.

1. У Firebase Console направити нови project.
2. Додати Android апликацију са package name:

   ```text
   rs.maliprecnik.app
   ```

3. Преузети `google-services.json` и ставити га у:

   ```powershell
   app\google-services.json
   ```

4. Укључити Authentication и омогућити Anonymous sign-in.
5. Направити Cloud Firestore базу.
6. У Firestore Rules налепити садржај из:

   ```powershell
   database\firestore.rules
   ```

7. Поново изградити апликацију:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

Без `app\google-services.json` апликација се и даље гради и локални речник ради,
али ће страница „Гласање” и дугме „Предложи измену” приказати поруку да Firebase
није подешен.

Ако се при предлагању појави `CONFIGURATION_NOT_FOUND`, Android апликација је
пронашла Firebase project, али Firebase Authentication још није довршен. У том
случају треба у Firebase Console укључити `Authentication` -> `Sign-in method` ->
`Anonymous`.

За уреднички преглед предлога потребан је service account JSON. Тај
фајл не треба чувати у git-у. Пример путање:

```powershell
database\private\service-account.json
```
