# winnerCustomization

Приложение читает события ALPR из source-таблицы, строит последовательности этапов по номеру автомобиля, сохраняет пересчитанные sequence records, формирует Excel-отчёт и отправляет уведомления по правилам из `config.json`.

## Что умеет
- Обрабатывать три типа этапов: `real`, `transitional`, `single_camera`.
- Строить `real`-этапы с sticky `Out`: повторные `In` дедуплицируются, `Out` обновляет время завершения, а переоткрытие возможно только после уже полученного `Out`.
- Строить `transitional`-этапы только через механизм кандидатов:
  - кандидат создаётся по explicit trigger-камере или после завершения разрешённого этапа из `allowedAfter`;
  - repeated detections того же transitional-источника продлевают candidate timeout;
  - кандидат материализуется только после `candidateTimeoutSeconds`;
  - если до истечения таймаута стартует другой этап, кандидат отменяется и в отчёт не попадает.
- Строить `single_camera`-этапы как непрерывный визит поста: все detections той же камеры остаются в одном этапе без stage-timeout, а `Out` фиксируется по последней detection поста только при переходе на другую камеру/этап.
- Закрывать последовательности по глобальному тайм-ауту или transitional override; незавершённые transitional-этапы скрываются из отчёта, если `showInReportIfIncomplete = false`.
- Пересчитывать исторические отчёты из полной истории detections, а затем фильтровать только этапы, пересекающие выбранный календарный день.
- Периодически опрашивать source database по настраиваемому интервалу, пересчитывать sequence records и логировать начало/конец фонового обновления.
- Пересчитывать и отправлять Telegram-уведомления по правилам `notifications[]`.

## Конфигурация
В production рядом с `.jar` должен лежать `config.json`. В репозиторий коммитится только `config.json.example`.

Основные поля:
- `sequenceCloseTimeoutMinutes` — общий тайм-аут закрытия последовательности.
- `sourceRefresh.enabled` — включает/выключает фоновый опрос source database.
- `sourceRefresh.intervalSeconds` — интервал фонового обновления и логирования pull-прохода.
- `notifications[]` — камеры/направления, которые создают alarm и текст уведомления.
- `realStages[]` — этапы с отдельными `In`/`Out`-триггерами.
- `transitionalStages[]` — переходные этапы с `triggerCameras`, `allowedAfter`, `candidateTimeoutSeconds`, опциональным `sequenceCloseTimeoutOverrideSeconds` и флагом `showInReportIfIncomplete`.
- `singleCameraStages[]` — этапы постов/камер, где визит держится открытым до появления другого этапа или закрытия sequence.

## Как работает построение этапов
### Real
- Первый `In` открывает этап.
- Повторный `In` того же этапа игнорируется, пока не пришёл `Out`.
- `Out` обновляет sticky `timeOut`, но не закрывает этап немедленно.
- Если новый `In` приходит после sticky `Out`, предыдущий этап сохраняется и открывается новый.
- `Out` без активного real-этапа создаёт partial stage.

### Transitional
- Transitional не открывается по первой detection сразу.
- Сначала создаётся кандидат с временем первого срабатывания и последнего события источника.
- Кандидат materializes только после истечения `candidateTimeoutSeconds`.
- Transitional может появиться только из кандидата; автоматические implicit вставки между соседними этапами не выполняются.
- Если последовательность закрылась по timeout, а этап transitional остался незавершённым и `showInReportIfIncomplete = false`, он удаляется из финального отчёта.

### Single camera
- Первый detection открывает этап поста.
- Следующие detections той же камеры обновляют только `lastSeen` и не закрывают этап по `timeoutSeconds`.
- Этап поста закрывается только при старте другого этапа; `timeOut` берётся из последней detection камеры поста.
- Если другая камера так и не появилась, этап остаётся открытым до закрытия sequence/момента отчёта.

## Запуск
```bash
./mvnw spring-boot:run
```

## Тесты
```bash
./mvnw -B test
```

## HTTP endpoints
- `GET /config` — текущее содержимое runtime-конфига.
- `POST /config` — сохранить новый конфиг.
- `GET /report/sequences.xlsx` — собрать актуальный Excel-отчёт.
- `GET /report/sequences.xlsx/{dd-MM-yyyy}` — отчёт по календарной дате.
- `GET /source/trigger-pull` — вручную пересчитать последовательности и записать их в sequence storage.

## Логи и фоновое обновление
Фоновый планировщик:
- каждые `sourceRefresh.intervalSeconds` секунд запускает pull из source DB;
- пишет в лог начало прохода;
- пишет в лог число загруженных detections, число пересчитанных sequence records и sample IDs;
- при ошибке пишет exception и время следующей попытки.

## Уведомления
Пакет `notifications` отвечает за alarm-логику:
- planner строит pending alarms по detections;
- service периодически перечитывает source-данные, обновляет pending notifications и отправляет due-сообщения;
- sender отправляет сообщения в Telegram, если `messaging.enabled = true`.
