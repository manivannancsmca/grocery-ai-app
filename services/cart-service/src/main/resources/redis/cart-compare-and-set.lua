-- Compare-and-set of one customer's cart (CartStore.java). Atomic: Redis runs a script as one step.
--
-- KEYS[1]  cart:v1:<customerId>   a hash with the fields "version" (integer) and "data" (the cart as JSON)
-- ARGV[1]  the version the caller read (0 = there was no cart)
-- ARGV[2]  the new JSON data
-- ARGV[3]  time to live in milliseconds (refreshed on every write: 30 days after the last change)
--
-- The cart is written only when its version is still the one the caller read; the version then goes up by one.
-- Returns the new version, or 0 when someone else changed the cart first (the caller re-reads and retries).
-- The key is never deleted by a write (an emptied cart is stored empty), so versions only ever increase while the
-- cart exists and an old read can never match a newer cart (no ABA). An unreadable version counts as 0.
local current = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') or 0
if current ~= tonumber(ARGV[1]) then
  return 0
end
local next = current + 1
redis.call('HSET', KEYS[1], 'version', next, 'data', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return next
