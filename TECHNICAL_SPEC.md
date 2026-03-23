# Technical specification

## Main modules

### `config`
- `RuntimeConfig`
  - читает `config.json` из `user.dir`;
  - валидирует обязательные поля, включая `sourceRefresh.intervalSeconds`;
  - хранит актуальный `AppConfig` в `AtomicReference`;
  - умеет `load()`, `reload()` и `save()`.
- `JacksonConfig`, `JdbcConfig`, `TimeConfig` создают инфраструктурные Spring beans.

### `model`
- `AppConfig` — корневая JSON-модель runtime-конфига.
  - `SourceRefreshConfig` описывает фоновый pull (`enabled`, `intervalSeconds`).
  - `RealStageConfig`, `TransitionalStageConfig`, `SingleCameraStageConfig` описывают правила построения этапов.
- `Detection` — входное событие из source table.
- `SequenceRecord` — итоговая последовательность по одному номеру.
- `SequenceRecord.StageWindow` — одно окно этапа в отчёте/sequence storage.

### `logic`
- `StageSequenceProcessor` — основной движок построения последовательностей.
- Внутри одной последовательности processor ведёт:
  - `activeStage` — один materialized stage (`real`, `transitional` или `single_camera`);
  - `candidates` — отдельные transitional candidates, которые ещё не материализовались;
  - `lastDetectionAt` — timestamp последнего detection для контроля sequence timeout.

### `service`
- `ReportService`
  - строит отчёт на текущий момент;
  - для исторической даты всегда перерабатывает полную историю detections и только потом фильтрует нужный день.
- `SourcePullTriggerService`
  - вручную запускает чтение detections, пересчёт sequences и `replaceAll()` в sequence repository;
  - возвращает `TriggerResult` со статусом, количеством detections, количеством пересчитанных sequences и sample detection IDs.
- `SourceRefreshSchedulerService`
  - запускается по `@Scheduled`;
  - использует `sourceRefresh.intervalSeconds` из runtime-конфига;
  - логирует старт, результат прохода и ошибки со следующим retry.
- `NotificationService`
  - пересчитывает due notifications;
  - сохраняет pending alarms;
  - отправляет due notifications через `NotificationSender`.
- `DatabaseBootstrapService` — инфраструктурный hook инициализации DB.

### `repository`
- `DetectionRepository` — читает source detections.
- `SequenceRepository` — хранит итоговые sequences/stages.
- `NotificationRepository` — хранит pending/sent notifications.

### `report`
- `SequenceReportWriter` формирует workbook `Sequences` + `Events` через Apache POI.

### `web`
- Контроллеры делегируют операции в сервисы и не содержат sequence-логики.

## Sequence processing rules

### Real stage
- Метод `startReal(...)`:
  - открывает новый real-этап по первому `In`;
  - игнорирует повторный `In`, пока активный этап не получил sticky `Out`;
  - после sticky `Out` позволяет открыть новый этап того же типа.
- Метод `applyRealOut(...)`:
  - обновляет `timeOut` активного real-этапа;
  - создаёт stage-end candidate для разрешённых transitional stages;
  - если активного real-этапа нет, пишет partial stage.
- `resolveOutForStageSwitch(...)`:
  - для `real` использует sticky `timeOut`, если он уже известен;
  - иначе завершает этап на `nextStageAt - 1 second`.

### Transitional stage
- Transitional stage проходит 2 фазы:
  1. `TransitionalCandidate`.
  2. materialized `StageRuntime` типа `TRANSITIONAL`.
- Candidate создаётся:
  - explicit detection на transitional camera;
  - либо окончанием предыдущего этапа из `allowedAfter`.
- Candidate хранит:
  - `firstTriggeredAt`;
  - `lastSourceAt`;
  - `previousStageName` / `previousStageOut` для stage-end сценария.
- Materialization происходит в `advanceTime(...)`, когда:
  - `candidate.dueAt() <= boundary`;
  - `allowedAfter` валиден;
  - активный stage можно корректно закрыть без перекрытия timeline.
- Время materialized transitional stage:
  - `timeIn = previousStageOut + 1 second` для stage-end candidate;
  - `timeIn = firstTriggeredAt` для explicit trigger candidate.
- Transitional stage больше не добавляется автоматически «между любыми двумя этапами».

### Single-camera stage
- `startOrUpdateSingle(...)`:
  - открывает визит поста по первой detection;
  - обновляет `lastSeen` по следующим detections той же камеры.
- `expireSingleStage(...)`:
  - закрывает визит на `lastSeen`, если прошёл `timeoutSeconds` без новых detections;
  - после закрытия создаёт stage-end transitional candidates по `allowedAfter`.

### Sequence close
- `advanceTime(...)` циклически обрабатывает:
  1. timeout single-camera stage;
  2. materialization due candidates;
  3. глобальный/stage-specific sequence close timeout.
- `closeSequence(...)`:
  - очищает незрелые candidates;
  - сохраняет незавершённые `real`/`single_camera` без `timeOut`;
  - сохраняет незавершённый `transitional` только если `showInReportIfIncomplete = true`.

## Historical reporting
- `ReportService.buildReport(LocalDate)` больше не использует day-bounded fetch как источник истины.
- Алгоритм:
  1. загружает полную историю `DetectionRepository.findAll()`;
  2. прогоняет `StageSequenceProcessor.process(...)` на весь объём;
  3. фильтрует `SequenceRecord`/`StageWindow`, пересекающие окно `[dayStart, nextDayStart)`.

## Scheduled source refresh and logging
- `SourceRefreshSchedulerService.refreshSequencesFromSource()` вызывается по fixed delay.
- Логирует:
  - старт обновления;
  - `detectionsLoaded`;
  - `sequencesPersisted`;
  - `sampleDetectionIds`;
  - ошибку и время следующей попытки при exception.
- Планировщик работает в отдельном scheduled pipeline Spring и не блокирует web request threads.

## Test coverage
- `StageSequenceProcessorTest` покрывает:
  - materialization transitional candidate;
  - отмену candidate при старте другого stage;
  - sticky `Out` и reopen real stage;
  - partial `Out` поверх active transitional;
  - повторный single-camera visit;
  - sequence close с удалением incomplete transitional.
- `ReportServiceTest` проверяет перерасчёт отчёта по полной истории.
- `RuntimeConfigTest` проверяет валидацию `sourceRefresh`.
- `SourceRefreshSchedulerServiceTest` проверяет scheduled refresh при enabled/disabled config.
