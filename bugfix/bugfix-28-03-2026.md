# ТЗ для Claude Code: Исправление багов в ALPR Sequence Engine

## Контекст

Приложение — Spring Boot state engine, который обрабатывает детекции ALPR (номерных знаков), строит последовательности этапов (stages) и пишет результаты в целевую БД. Основная логика сосредоточена в файлах:

- `service/logic/SequenceEngineServiceImpl.java` — ядро (процессинг детекций, maintenance, таймауты)
- `service/logic/TriggerMatcher.java` — матчинг детекций с триггерами
- `service/logic/DirectionMatcher.java` — круговая арифметика направлений
- `service/logic/SchedulerServiceImpl.java` — планировщик опроса и инициализации
- `model/PlateSequence.java` — модель последовательности
- `model/Stage.java` — модель этапа

Спецификация логики описана в файле `logic.md`.

---

## Баг 1: Переходные этапы остаются активными дольше своих таймаутов

### Симптомы
«Backyard» длительностью 48 ч 7 м 58 с при `sequenceCloseTimeoutOverrideMinutes=2880` (48 ч). «Test-Drive» длительностью 15 ч 40 м 44 с при `sequenceCloseTimeoutOverrideMinutes=60`.

### Причина в коде

**Файл:** `SequenceEngineServiceImpl.java`, метод `closeTimedOutSequences()` (строки 581–613).

Проблема в том, что для transitional-этапа с `sequenceCloseTimeoutOverrideMinutes > 0` код проверяет override-таймаут, и если он НЕ истёк — делает `continue`, полностью **пропуская** проверку глобального таймаута для этой последовательности:

```java
if (activeStage != null && "transitional".equals(activeStage.getType())
        && activeStage.getSequenceCloseTimeoutOverrideMinutes() > 0) {
    timeoutMinutes = activeStage.getSequenceCloseTimeoutOverrideMinutes();
    long minutesSinceStart = Duration.between(activeStage.getInTime(), now).toMinutes();
    if (minutesSinceStart >= timeoutMinutes) {
        platesToClose.add(entry.getKey());
        continue;  // <-- Если override сработал — закрывает. Но...
    }
}
// Глобальный таймаут проверяется корректно.
```

Этот код работает корректно для `sequenceCloseTimeoutOverrideMinutes > 0` (test_drive). Проблема в другом: для `backyard` с `sequenceCloseTimeoutOverrideMinutes = 0` блок `if` **не выполняется**, и закрытие зависит только от глобального `sequenceCloseTimeoutMinutes`. Если глобальный таймаут = 2880 мин (48 ч), а `lastDetectionTime` обновлена свежей детекцией, которая не была распознана как валидный триггер — таймаут не срабатывает.

Но главная проблема — этап Backyard **не закрывается приходом новой детекции** (см. Баг 2), из-за чего он продолжает висеть активным и его duration растёт.

Для Test-Drive (60 мин): проверка `minutesSinceStart >= timeoutMinutes` использует `toMinutes()`, которая усекает секунды. Это не вызывает 15-часовую задержку — проблема в том, что новые детекции НЕ закрывают transitional (Баг 2) и при этом **обновляют `lastDetectionTime`** (строка 98), сбрасывая глобальный таймаут.

### Что исправить

1. В методе `processOneDetection()` (строка 98): `lastDetectionTime` обновляется **для любой детекции**, даже если она не матчит ни один триггер. Это значит, что детекция, которая не распознана (из-за Бага 3 — ошибка в direction matching), всё равно сбрасывает глобальный таймаут. **Нужно обновлять `lastDetectionTime` только когда детекция реально обработана** — т.е. хотя бы один match (primary или singleCamera) сработал.

2. В методе `closeTimedOutSequences()`: для transitional с `sequenceCloseTimeoutOverrideMinutes = 0` необходимо использовать глобальный `sequenceCloseTimeoutMinutes`, но отсчитывать от `inTime` этапа (как описано в logic.md, секция «Sequence Close During Transitional»). Текущий код правильно делает fallthrough к глобальной проверке, но отсчитывает от `lastDetectionTime`, что корректно для глобального случая. Однако для активного transitional таймаут должен считаться от `inTime` transitional этапа.

**Файлы для изменения:**
- `SequenceEngineServiceImpl.java`: метод `processOneDetection` — перенести обновление `lastDetectionTime` внутрь условия, когда хотя бы один матч сработал.
- `SequenceEngineServiceImpl.java`: метод `closeTimedOutSequences` — для transitional с override=0, считать таймаут от `inTime` этапа, а не от `lastDetectionTime`.

---

## Баг 2: Переходный этап не закрывается при приходе новой валидной детекции

### Симптомы
Для KA8390PI этап «Backyard» длится с 2026-03-19 16:22:05 до 2026-03-21 16:30:04, хотя между этими датами были детекции analytics_id=20 с направлениями ~17°, 90°, 315°, которые должны запускать «Parking» и закрывать «Backyard».

### Причина в коде

Проблема складывается из двух факторов:

**Фактор A:** Баг 3 (direction matching) — направления 315°, 17° не распознаются как `in` для parking с direction=0°/360°. Из-за этого `findPrimaryMatch()` не возвращает match, и `handleInTrigger()` не вызывается.

**Фактор B:** Даже если direction matching починить, метод `handleInTrigger()` (строки 115–168) корректно закрывает предыдущий активный этап. Но есть нюанс: `getActiveStage()` в `PlateSequence.java` ищет `s.isActive() && !s.isCandidate()`. Если transitional этап материализовался (`candidate = false`), он IS the active stage, и `handleInTrigger` корректно его закроет. 

**Однако** если transitional был создан через камерный триггер (`handleInTrigger` для transitional type), он никогда не был кандидатом — он сразу `active=true, candidate=false`. В этом случае проблема только в direction matching (Баг 3).

### Что исправить

Исправление Бага 3 (direction matching) решит эту проблему. После исправления direction matching, детекции с направлениями 315°, 17° будут корректно распознаваться как `in` для parking (direction=0°, tolerance=90° → диапазон 270°–90°), что вызовет `handleInTrigger` → закрытие активного Backyard.

**Файлы для изменения:** см. Баг 3.

---

## Баг 3: Нарушены правила сравнения направлений

### Симптомы
Направления 315°, 17°, 10° не попадают в диапазон входа для parking (direction=0°/360°).

### Причина в коде

**Файл:** `TriggerMatcher.java`, метод `resolveAndAddTrigger()` (строки 102–143).

Для parking конфиг:
```json
{
  "name": "parking",
  "triggers": [
    { "type": "in", "analyticsId": X, "direction": 0 },
    { "type": "out", "analyticsId": X, "direction": 180 }
  ]
}
```

В `resolveAndAddTrigger()` для `in` триггера parking (`direction=0`) ищется `out` триггер на том же `analyticsId` (`direction=180`). Проверяется `rangesOverlap(0, 90, 180, 90)`:

```java
// DirectionMatcher.rangesOverlap:
int dist = angularDistance(0, 180); // = 180
return dist < (90 + 90); // 180 < 180 = false
```

Overlap возвращает `false`, поэтому tolerance остаётся стандартным (90). Пока что всё правильно.

**Проблема в `DirectionMatcher.matches()`:**

```java
public static boolean matches(int detectedDirection, int configuredDirection, int tolerance) {
    return angularDistance(detectedDirection, configuredDirection) <= tolerance;
}
```

Для `detectedDirection=315, configuredDirection=0, tolerance=90`:
- `angularDistance(315, 0) = min(315, 360-315) = min(315, 45) = 45`
- `45 <= 90` → `true` ✓

Для `detectedDirection=17, configuredDirection=0, tolerance=90`:
- `angularDistance(17, 0) = min(17, 343) = 17`
- `17 <= 90` → `true` ✓

**Итак, DirectionMatcher работает КОРРЕКТНО.** Проблема в другом месте.

**Реальная проблема — конфликт между in/out триггерами РАЗНЫХ этапов на одном analyticsId.**

Конфликтная ситуация: parking `in` (direction=0) и parking `out` (direction=180) проверяются только **внутри одного stage** (массив `allTriggersForStage`). Но если на том же `analyticsId` есть другой stage (например, service `out` с direction=180, или backyard `in` без direction), конфликт МЕЖДУ этапами не резолвится.

**Критическая проблема:** Метод `findPrimaryMatch()` (строки 189–210) при конфликте между `in` и `out` матчами **всегда отдаёт приоритет `out`**:

```java
if ("out".equals(m.triggerType) && "in".equals(best.triggerType)) {
    best = m; // out takes priority
}
```

Если детекция с direction=315° на analyticsId=20 матчит:
1. parking `in` (direction=0, tolerance=90) → angularDistance(315,0)=45 ≤ 90 ✓
2. service `out` (direction=180, tolerance=90) → angularDistance(315,180)=135 > 90 ✗

Тогда parking in сработает.

Но если есть матч с другим `out` триггером БЕЗ direction (direction=null, что означает «любое направление»), то он тоже сработает и перебьёт `in`.

**Нужно проверить реальный config.json** — какие `analyticsId` используются для каких этапов. Если у backyard `in` триггер имеет тот же `analyticsId` и direction=null, а у другого этапа `out` тоже без direction на том же `analyticsId`, то `out` перебивает `in` parking.

**Другая критичная проблема:** В `resolveAndAddTrigger()` conflict resolution проверяет overlap только **внутри одного stage** (параметр `allTriggersForStage` — это триггеры одного этапа). Конфликты **между разными этапами** на одном `analyticsId` не обрабатываются. По спецификации `logic.md` (секция «In/Out Conflict Resolution on the Same Camera»): конфликт возникает, когда `in` и `out` триггеры на **одном analyticsId** имеют пересекающиеся диапазоны. Спецификация не ограничивает это одним stage — значит, нужно проверять конфликты и между этапами.

### Что исправить

1. **`TriggerMatcher.java`, метод `resolveAndAddTrigger()`:** Conflict resolution должен учитывать ВСЕ триггеры на данном `analyticsId` из всех этапов (real + transitional), а не только из текущего stage. Нужно собрать все триггеры с одинаковым `analyticsId` и проверять overlap между всеми парами in/out.

2. **`TriggerMatcher.java`, метод `findPrimaryMatch()`:** Приоритет `out` над `in` сейчас безусловный. По спецификации (секция «In/Out Conflict Resolution on the Same Camera»): «The out trigger always takes priority for any direction that falls within both ranges». Это значит, out приоритет только когда направление попадает в ОБА диапазона одновременно. Если направление попадает только в `in` range — должен сработать `in`. Нужно изменить логику: out побеждает in, только если оба матчат одну детекцию на одном `analyticsId`.

3. **`DirectionMatcher.java`**: Сама круговая арифметика корректна. Никаких изменений не требуется.

**Файлы для изменения:**
- `TriggerMatcher.java`: `buildResolvedTriggers()` — собирать ВСЕ триггеры по analyticsId и резолвить конфликты глобально.
- `TriggerMatcher.java`: `findPrimaryMatch()` — out приоритет только при реальном overlap на данной детекции.

---

## Баг 4: Кандидаты переходных этапов не удаляются при приходе другой детекции

### Симптомы
Backyard материализуется, несмотря на то что в течение 5 минут (`candidateTimeoutMinutes=5`) после окончания Parking/Service приходят новые детекции.

### Причина в коде

**Файл:** `SequenceEngineServiceImpl.java`, метод `invalidateCandidates()` (строки 331–340) вызывается в:
- `handleInTrigger()` (строка 133)
- `handleOutTrigger()` — для out на другой этап (строка 204)
- `processSingleCameraMatch()` (строка 252)

**Проблема:** `invalidateCandidates()` вызывается только когда детекция **матчит какой-то триггер**. Если детекция не матчит ни один триггер (из-за Бага 3 — direction matching), `invalidateCandidates()` не вызывается, и кандидат НЕ удаляется.

По спецификации `logic.md` (секция «Candidate invalidation rules»): 
> «If **any other detection** for the plate arrives before timeout ends (on any camera, any stage), the candidate is **deleted** without materializing.»

Это значит, что ЛЮБАЯ детекция для этого номера (даже не матчащая никакой триггер) должна инвалидировать кандидата.

### Что исправить

В методе `processOneDetection()` добавить вызов `invalidateCandidates()` ДО обработки триггеров, для любой детекции по данному plate, если есть активная последовательность с pending candidates.

**Но есть нюанс:** по спецификации, если `out` trigger того же stage (из `allowedAfter`) повторяется — кандидат не удаляется, а его таймаут **сбрасывается**. Это обрабатывается в `resetCandidateOnStageOut()`. Порядок должен быть:
1. Проверить, является ли детекция повторным `out` для того же этапа → если да, reset timeout кандидата.
2. Если нет — invalidate всех кандидатов.
3. Затем обрабатывать основные триггеры.

**Файлы для изменения:**
- `SequenceEngineServiceImpl.java`: метод `processOneDetection()` — добавить invalidation кандидатов для ЛЮБОЙ детекции (кроме повторного out для allowedAfter stage).

---

## Баг 5: Не закрываются последовательности по глобальному таймауту

### Симптомы
Последовательности длятся более двух суток без новых событий.

### Причина в коде

Связано с Багом 1. `closeTimedOutSequences()` вызывается в `performMaintenance()`, который вызывается каждый poll cycle. Глобальный таймаут проверяется по `lastDetectionTime`:

```java
long minutesSinceLastDetection = Duration.between(seq.getLastDetectionTime(), now).toMinutes();
if (minutesSinceLastDetection >= globalTimeoutMinutes) {
    platesToClose.add(entry.getKey());
}
```

Проблема: `lastDetectionTime` обновляется при **каждой** детекции для plate (строка 98 в `processOneDetection`), даже если детекция не распознана как валидный триггер. Если камера продолжает ловить номер (пусть даже с нераспознанным направлением), `lastDetectionTime` постоянно обновляется, и глобальный таймаут никогда не срабатывает.

### Что исправить

То же, что в Баге 1: обновлять `lastDetectionTime` только при успешном match. Если детекция не матчит ни один триггер (primary или singleCamera), `lastDetectionTime` не обновляется.

**Файлы для изменения:**
- `SequenceEngineServiceImpl.java`: метод `processOneDetection()` — условное обновление `lastDetectionTime`.

---

## Баг 6: Некорректно обновляется outTime при нескольких подряд out-событиях

### Симптомы
В отчёте несколько последовательных этапов одного типа вместо обновления outTime существующего этапа.

### Причина в коде

**Файл:** `SequenceEngineServiceImpl.java`, метод `handleOutTrigger()` (строки 172–221).

Проверка «is this out for the same active stage?» (строка 187):

```java
if (activeStage != null && activeStage.getName().equals(match.stageName)) {
    // Same stage out: overwrite outTime
    activeStage.setOutTime(detection.getCreatedAt());
    ...
    return;
}
```

Эта проверка работает только если `activeStage` — тот же этап. Но если предыдущий `out` создал **partial stage** (с `inTime=null`), то этот partial stage IS the active stage. Следующий `out` для того же этапа попадает в блок `if activeStage.getName().equals(match.stageName)`, и вместо промоции partial → full (что делает `createPartialStage`), просто перезаписывает `outTime`.

**Проблема в другом сценарии:** Если active stage — это full stage другого типа, и приходит `out` для stage X:
1. Код закрывает active stage (строки 202–213).
2. Создаёт partial stage X (строка 216).
3. Если потом приходит ещё один `out` для stage X, проверка на строке 187 сработает — activeStage (partial X) имеет то же имя. outTime перезаписывается. Но `createPartialStage` содержит логику промоции partial→full (строки 378–387), которая в этом случае НЕ вызывается.

Результат: partial stage остаётся partial (`inTime=null`), но с обновлённым `outTime`. По спецификации, второй `out` для того же partial должен промоутить его:
> «If the next trigger after the partial B open is also an out for stage B: Set stage B inTime = stage B's first outTime. Overwrite stage B outTime with this new detection timestamp. Stage B is now treated as a full stage.»

### Что исправить

В `handleOutTrigger()`, при совпадении имени с active stage, нужно проверить, является ли active stage partial (not full, inTime=null). Если да — промоутить его в full вместо простого overwrite:

```java
if (activeStage != null && activeStage.getName().equals(match.stageName)) {
    if (!activeStage.isFull() && activeStage.getInTime() == null) {
        // Partial stage: promote to full
        activeStage.setInTime(activeStage.getOutTime()); // first out becomes inTime
        activeStage.setOutTime(detection.getCreatedAt()); // new out becomes outTime
        activeStage.setFull(true);
    } else {
        // Full stage: just overwrite outTime
        activeStage.setOutTime(detection.getCreatedAt());
    }
    // ... rest of the logic (transitional auto-start, reset candidates)
    return;
}
```

**Файлы для изменения:**
- `SequenceEngineServiceImpl.java`: метод `handleOutTrigger()` — добавить проверку на partial stage при повторном out.

---

## Порядок исправления (рекомендуемый)

1. **Баг 3** (direction matching) — первоочередной, т.к. от него зависят баги 2, 4, 5.
2. **Баг 6** (partial stage promotion) — независимый, можно параллельно.
3. **Баг 4** (candidate invalidation) — после исправления direction matching.
4. **Баг 1 + 5** (lastDetectionTime + таймауты) — после исправления direction matching.
5. **Баг 2** — автоматически решается после исправления багов 3 и 1.

---

## Файлы, требующие изменений

| Файл | Баги |
|------|------|
| `service/logic/SequenceEngineServiceImpl.java` | 1, 4, 5, 6 |
| `service/logic/TriggerMatcher.java` | 3 |

`DirectionMatcher.java` — изменений не требует, круговая арифметика корректна.

---

## Тесты

После внесения исправлений необходимо обновить/добавить тесты:

1. **`DirectionMatcherTest.java`** — добавить тест для edge cases: `matches(315, 0)` должен вернуть `true`, `matches(17, 0)` должен вернуть `true`, `matches(10, 0)` должен вернуть `true`.

2. **`TriggerMatcherTest.java`** — добавить тесты:
   - Cross-stage conflict resolution (in/out конфликты между разными stages на одном analyticsId).
   - `findPrimaryMatch` не отдаёт `out` приоритет, когда `in` матчит, а `out` — нет.

3. **`SequenceEngineTest.java`** — добавить тесты:
   - Candidate invalidation при любой детекции (не только при matched detection).
   - Partial stage promotion при повторном out.
   - `lastDetectionTime` не обновляется при unmatched detection.
   - Transitional stage закрывается при приходе новой валидной детекции.
   - Sequence close по глобальному таймауту, когда `lastDetectionTime` не обновлялась unmatched детекциями.
