-- Stable newest-first UI/API cursor reads without changing any financial history.
CREATE INDEX accounts_customer_cursor ON accounts(created_at DESC,id DESC) WHERE account_type='CUSTOMER';
CREATE INDEX transfers_monitor_cursor ON transfers(created_at DESC,id DESC);
