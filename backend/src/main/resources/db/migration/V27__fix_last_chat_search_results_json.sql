ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_chat_search_results_json TEXT;