INSERT INTO accounts (
    id,
    owner_user_id,
    currency,
    status,
    balance_minor,
    version,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000999',
    '00000000-0000-0000-0000-000000000001',
    'USD',
    'ACTIVE',
    0,
    0,
    now(),
    now()
);