# winnerCustomization

Приложение собирает события из Source-таблицы ALPR, строит последовательности по номеру автомобиля, формирует Excel-отчёт и подготавливает/отправляет Telegram-уведомления по правилам из `config.json`.

## Что умеет
- Обрабатывать три типа этапов: `real`, `transitional`, `single_camera`.
- Восстанавливать цепочку этапов по одному номеру с учётом дедупликации, sticky `Out`, partial-событий и таймаутов закрытия.
- Материализовывать transitional-этапы только после `candidateTimeoutSeconds`.
- Периодически пересчитывать alarm-события по правилам `notifications[]` и отправлять Telegram-сообщения, если таймаут ожидания уже истёк.
- Генерировать Excel-файл со структурой `Sequences` и `Events`, как в примере `results/sequences.xlsx`.

## Конфигурация
В production рядом с `.jar` должен лежать `config.json`. В репозиторий коммитится только `config.json.example`.

Ключевые поля:
- `sequenceCloseTimeoutMinutes` — общий таймаут закрытия последовательности.
- `notifications[]` — камеры/направления, которые создают alarm и текст уведомления.
- `realStages[]` — этапы с разделением на `In`/`Out`.
- `transitionalStages[]` — кандидаты переходных этапов с `allowedAfter`, `triggerCameras` и `candidateTimeoutSeconds`.
- `singleCameraStages[]` — sticky-этапы без разделения на `In`/`Out`.

Удалённые поля `allowTransitionalAfterSingleCamera` и `duplicateSuppressionSeconds` больше не используются: переходы после single-camera этапов определяются через `allowedAfter`, а одинаковые подряд `In` для уже активного real-этапа игнорируются логикой движка.

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

## Уведомления
Логика уведомлений вынесена в пакет `notifications`:
- planner вычисляет, какие события создали alarm;
- service регулярно перечитывает Source-данные и находит просроченные alarm;
- sender отправляет сообщения в Telegram, если `messaging.enabled = true`.
