# Tasklane Backend

Small, production-style Clojure service built to showcase clear structure,
validation, and a realistic task workflow.

## Features
- JSON API with Ring + Reitit
- In-memory task store (easy to swap for a DB)
- Validation and consistent error responses
- Query filtering and pagination
- Tests for service logic and HTTP endpoints

## Data model
Tasks are stored as maps with:
- `:id` integer
- `:name` string (required)
- `:description` string (optional)
- `:status` keyword (`:pending`, `:in-progress`, `:done`)
- `:priority` integer 1-5 (optional)
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

## Run tests
```bash
clj -M:test
```

## Notes
This project is intentionally small but structured to reflect real interview
requirements: API hygiene, validation, lifecycle operations, and test coverage.
