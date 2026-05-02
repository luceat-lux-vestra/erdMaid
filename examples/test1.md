```mermaid
erDiagram
    chat_file {
        bigint id
        bigint chat_id
        bigint file_id
        tinyint deleted
        datetime deleted_at
        datetime created_at
        datetime updated_at
    }
```