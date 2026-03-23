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
  - sticky `Out` для real stages;
  - timeout закрытия sequence;
  - implicit transitional bridges между завершённым stage и следующим stage.
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
- Explicit trigger-камера открывает transitional stage сразу на timestamp detection.
- Повторный detection той же transitional-камеры расширяет текущий stage, обновляя `lastSeen`.
- Если previous stage указан в `allowedAfter`, processor может создать implicit transitional bridge от `previousStageOut + 1s` до `nextStageStart - 1s`.
- `candidateTimeoutSeconds` используется как минимальная длина такого implicit bridge; если gap короче, bridge не добавляется.
- Transitional stage закрывается при старте следующего stage или при закрытии sequence на `lastSeen`.

### Single-camera stage
- Первый detection создаёт stage.
- Каждое следующее detection той же камеры обновляет `lastSeen`.
- Stage хранится как единый визит: `timeIn = first detection`, `timeOut = last detection`.
- При старте другого stage single-camera stage закрывается на timestamp последнего detection, а не на время следующего этапа.
- `timeoutSeconds` больше не дробит визит на отдельные report windows; завершение визита подтверждается следующим stage либо закрытием sequence.

### Sequence close
- Активная последовательность существует на один номер.
- Закрытие наступает по отсутствию detections в течение global timeout или stage-specific transitional timeout override.
- При sequence close active real stage без `Out` сохраняется без `Out`, а active single/transitional stages завершаются на `lastSeen`.

### Historical reporting
- `ReportService.buildReport(LocalDate)` теперь пересчитывает sequence state по полной истории detections (`findAll()`), чтобы старые дни знали о поздних `Out`, переходах и завершениях stage.
- После полного пересчёта сервис фильтрует только те `StageWindow`, которые пересекают календарное окно `[date 00:00, date+1 00:00)`.

## Persistence and integration
- `DetectionRepository` читает ALPR detections из source DB.
- `SequenceRepository` хранит итоговые последовательности и stages в sequence DB.
- `NotificationRepository` хранит pending/sent notifications.
- Web controllers only delegate to services and do not contain business logic.
