
UPDATE users
SET subscription_plan = 'START'
WHERE subscription_plan = 'MINIMUM';

UPDATE users
SET subscription_plan = 'BUSINESS'
WHERE subscription_plan = 'START';

UPDATE subscription_expiry
SET expires_at = expires_at
WHERE user_id IN (
    SELECT id FROM users WHERE subscription_plan IN ('START', 'BUSINESS')
);