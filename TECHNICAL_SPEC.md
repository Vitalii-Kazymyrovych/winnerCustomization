# Technical specification

## Main modules

### `config`
- `RuntimeConfig` читает `config.json` из `user.dir`, валидирует обязательные поля и сохраняет обновлённую конфигурацию.
- `JacksonConfig`, `JdbcConfig`, `TimeConfig` создают инфраструктурные Spring beans.

### `model`
- `AppConfig` — корневая JSON-модель runtime-конфига.
- `Detection` — входное событие из source table.
- `SequenceRecord` — итоговая последовательность по одному номеру.
- `SequenceRecord.StageWindow` — одно окно этапа в отчёте/хранилище.

### `logic`
- `StageSequenceProcessor` — основной движок логики из `logic.txt`.
- Processor сортирует detections, ведёт активную последовательность на номер, отслеживает:
  - active real/single/transitional stage;
  - transitional candidate;
  - sticky `Out` для real stages;
  - timeout закрытия single-camera stage;
  - timeout закрытия sequence;
  - синтетический `nextStageStartHint` для правила `single_camera -> next stage starts at lastDetection + 1s`.
- На выходе processor отдаёт `ProcessingResult` со списком `SequenceRecord`.

### `notifications`
- `NotificationPlanner` пересчитывает pending alarms по входным detections.
  - alarm создаётся событием на заданной камере;
  - repeated detections с той же камеры не гасят alarm;
  - любое другое detection того же номера отменяет alarm;
  - если `dueAt <= now`, alarm попадает в список на отправку.
- `NotificationSender` — интерфейс канала доставки.
- `TelegramNotificationSender` — реализация для Telegram Bot API.

### `report`
- `SequenceReportWriter` формирует workbook `Sequences` + `Events` через Apache POI.
- Формат строк повторяет образец из `results/sequences.xlsx`:
  - лист `Sequences`: номер как заголовок блока, далее stages и строка `Sequence closed`;
  - лист `Events`: плоский список `Plate / Stage / In / Out / Duration / Alerts`.

### `service`
- `ReportService` объединяет `DetectionRepository`, `StageSequenceProcessor` и `SequenceReportWriter`.
- `NotificationService` перечитывает Source-таблицу, пересчитывает due notifications и передаёт их sender-у.
- `SourcePullTriggerService` вручную пересчитывает sequence records и сохраняет их через `SequenceRepository`.
- `DatabaseBootstrapService` оставлен как инфраструктурный hook для создания sequence DB вне unit-тестов.

## Sequence engine rules

### Real stage
- Первый `In` открывает stage.
- Повторный `In` для уже активного такого же stage игнорируется.
- `Out` обновляет `timeOut`, но не закрывает stage.
- Если новый `In` приходит после уже записанного `Out`, предыдущий stage завершается на `Out`, затем открывается новый.
- `Out` без активного stage создаёт partial stage.

### Transitional stage
- Candidate создаётся либо explicit trigger-камерой, либо после завершения stage из `allowedAfter`.
- Candidate materializes только если до `candidateTimeoutSeconds` не стартовал другой stage.
- Повторный trigger того же transitional stage сбрасывает таймер кандидата.
- Materialized transitional stage заканчивается при старте любого следующего stage.
- Если sequence закрывается на incomplete transitional stage и `showInReportIfIncomplete=false`, этап удаляется из отчёта.

### Single-camera stage
- Первый detection создаёт stage.
- Каждое следующее detection той же камеры обновляет `lastSeen`.
- После `timeoutSeconds` этап закрывается на timestamp последнего detection.
- Следующий stage стартует с `lastSeen + 1s`, чтобы сохранить непрерывную шкалу.

### Sequence close
- Активная последовательность существует на один номер.
- Закрытие наступает по отсутствию detections в течение global timeout или stage-specific transitional timeout override.
- При sequence close активные real/single stages сохраняются без `Out`.

## Persistence and integration
- `DetectionRepository` читает ALPR detections из source DB.
- `SequenceRepository` хранит итоговые последовательности и stages в sequence DB.
- `NotificationRepository` хранит pending/sent notifications.
- Web controllers only delegate to services and do not contain business logic.
