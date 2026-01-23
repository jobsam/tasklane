# Tasklane Library

Tasklane is a small Clojure task-management starter library that provides
validated task workflows, pluggable storage (in-memory or SQLite), and an
optional HTTP API. It is intentionally minimal but fully functional so you can
embed it, extend it, or use it as a foundation for a larger system.

## Features
- JSON API with Ring + Reitit
- In-memory task store plus SQLite-backed persistence
- Validation and consistent error responses
- Query filtering and pagination
- Planner library for prioritizing work and spotting deadline risk
- Tests for service logic and HTTP endpoints

## Data model
Tasks are stored as maps with:
- `:id` integer
- `:name` string (required)
- `:description` string (optional)
- `:status` keyword (`:pending`, `:in-progress`, `:done`)
- `:priority` integer 1-5 (optional)
- `:due-at` ISO-8601 string (optional)
- `:created-at` ISO-8601 string
- `:updated-at` ISO-8601 string (optional)

## Endpoints
- `GET /health` -> `{ "status": "OK" }`
- `GET /tasks` -> list tasks
  - Optional query params: `status`, `limit`, `offset`
- `POST /tasks` -> create task
- `GET /tasks/:id` -> fetch task
- `PATCH /tasks/:id` -> update task
- `DELETE /tasks/:id` -> delete task

## Example requests
```bash
curl -s http://localhost:3000/health

curl -s -X POST http://localhost:3000/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"Write README","priority":2}'

curl -s http://localhost:3000/tasks?status=done&limit=10&offset=0

curl -s -X PATCH http://localhost:3000/tasks/1 \
  -H 'Content-Type: application/json' \
  -d '{"status":"done"}'
```

## Use as a library
Tasklane is designed to be embedded. You can choose the default in-memory store
or a SQLite-backed store for persistence.

### In-memory store
```clojure
(require '[tasklane.service :as service])

(service/create-task {:name "Write docs"})
(service/list-tasks)
```

### SQLite store
```clojure
(require '[tasklane.service :as service]
         '[tasklane.sqlite :as sqlite])

(def store (sqlite/open-store "jdbc:sqlite:/tmp/tasklane.db"))

(service/create-task store {:name "Ship release" :due-at "2024-01-05T00:00:00Z"})
(service/list-tasks store {:status "pending" :limit 10})
```

### Extending storage
Implement the `TaskStore` protocol to back tasks with any system:
```clojure
(require '[tasklane.store :as store])

(defrecord MyStore []
  store/TaskStore
  (create-task! [this task] ...)
  (list-tasks [this filters] ...)
  (get-task [this id] ...)
  (update-task! [this id updates] ...)
  (delete-task! [this id] ...)
  (reset-store! [this] ...))
```

## Planner library
Tasklane can also be used as a library to reduce missed deadlines by ranking
work based on urgency, age, and priority. Import `tasklane.planner` and call:

- `prioritize` to sort tasks and return scores with reasons
- `workload-report` to summarize overdue and due-soon work

Example:
```clojure
(require '[tasklane.planner :as planner])

(planner/prioritize tasks {:limit 3})
(planner/workload-report tasks {:horizon-hours 48})
```

## Error responses
Validation errors return `400` with:
```json
{
  "type": "validation",
  "message": "Task validation failed",
  "errors": [
    { "field": "name", "message": "name is required" }
  ]
}
```

Missing resources return `404` with:
```json
{
  "type": "not-found",
  "message": "Task not found",
  "errors": [
    { "field": "id", "message": "no task for that id" }
  ]
}
```

## Run locally
```bash
clj -M:run
```

### Run with SQLite persistence
```bash
TASKLANE_DB=/tmp/tasklane.db clj -M:run
```

## Run tests
```bash
clj -M:test
```

## Notes
This project is intentionally small but structured to reflect real interview
requirements: API hygiene, validation, lifecycle operations, and test coverage.
