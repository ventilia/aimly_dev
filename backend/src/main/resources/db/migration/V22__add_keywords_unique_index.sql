CREATE UNIQUE INDEX IF NOT EXISTS keywords_user_keyword_idx
    ON keywords (user_id, keyword);