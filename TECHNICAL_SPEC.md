# Technical specification

## Main modules

### `config`
- `RuntimeConfig`
  - читает `config.json` из `user.dir`;
  - валидирует обязательные поля, включая `reports.outputDirectory` и `sourceRefresh.intervalSeconds`;
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
  - строит Excel-отчёт на текущий момент или для исторической даты;
  - сохраняет файл в `reports.outputDirectory`, создавая директорию при необходимости;
  - возвращает метаданные сохранённого файла вместе с `byte[]` отчёта, чтобы web-слой мог отдать attachment без повторной генерации;
  - относительный `reports.outputDirectory` резолвит относительно папки, где лежит `config.json`;
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
- Контроллеры делегируют операции в сервисы и не содержат sequence-логики. `ReportController` отдаёт xlsx как attachment, выставляет `X-Saved-Report-Path` с фактическим путём сохранения в нормализованном slash-формате (`/`) и использует тот же файл/байты, которые одновременно сохраняются на диск.

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
  - обновляет `lastSeen` по следующим detections той же камеры без отдельного stage-timeout.
- `closeActiveForStageStart(...)` + `resolveOutForStageSwitch(...)`:
  - при появлении следующего этапа закрывают active `single_camera` на `lastSeen`;
  - это убирает дробление постов из-за пауз между detections, пока машина остаётся на той же камере.

### Sequence close
- `advanceTime(...)` циклически обрабатывает:
  1. materialization due candidates;
  2. глобальный/stage-specific sequence close timeout.
- `closeSequence(...)`:
  - очищает незрелые candidates;
  - сохраняет незавершённые `real`/`single_camera` без `timeOut`;
  - сохраняет незавершённый `transitional` только если `showInReportIfIncomplete = true`.

## Historical reporting
- `ReportService.saveReport(LocalDate)` не использует day-bounded fetch как источник истины.
- Алгоритм:
  1. загружает полную историю `DetectionRepository.findAll()`;
  2. прогоняет `StageSequenceProcessor.process(...)` на весь объём;
  3. фильтрует `SequenceRecord`/`StageWindow`, пересекающие окно `[dayStart, nextDayStart)`;
  4. сохраняет итоговый `.xlsx` в `reports.outputDirectory` и возвращает те же байты в HTTP download response.

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
  - непрерывный single-camera визит без timeout-дробления и его закрытие при переходе на другую камеру;
  - sequence close с удалением incomplete transitional.
- `ReportServiceTest` проверяет перерасчёт отчёта по полной истории и совпадение сохранённого файла с байтами, отданными в web-слой.
- `ReportControllerTest` проверяет attachment-ответ, `Content-Disposition`, `Content-Length` и заголовок `X-Saved-Report-Path`.
- `RuntimeConfigTest` проверяет валидацию `sourceRefresh`.
- `SourceRefreshSchedulerServiceTest` проверяет scheduled refresh при enabled/disabled config.
