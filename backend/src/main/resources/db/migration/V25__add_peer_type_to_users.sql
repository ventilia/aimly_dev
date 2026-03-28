ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_chat_search_peer_type VARCHAR(50);