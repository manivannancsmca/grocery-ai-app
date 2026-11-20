-- Compare-and-set for the price quote cache (PriceQuoteCache.java). Atomic: Redis runs a script as one step.
--
-- KEYS[i]                 price:v1:<productId>
-- ARGV[1]                 time to live in milliseconds
-- ARGV[2 + 3 * (i - 1)]   the new JSON entry for KEYS[i]
-- ARGV[3 + 3 * (i - 1)]   its priceVersion
-- ARGV[4 + 3 * (i - 1)]   its productVersion
--
-- An entry is only replaced by a NEWER one: a higher priceVersion, or the same priceVersion with a higher
-- productVersion (the product was archived meanwhile; its price cannot change any more). Equal or older writes
-- are ignored, so a slow reader that loaded MySQL before a change committed can never put the old price back.
-- An unreadable entry is replaced. Returns the number of entries written.
local ttl = ARGV[1]
local written = 0
for i, key in ipairs(KEYS) do
  local base = 2 + (i - 1) * 3
  local priceVersion = tonumber(ARGV[base + 1])
  local productVersion = tonumber(ARGV[base + 2])
  local newer = true
  local current = redis.call('GET', key)
  if current then
    local ok, entry = pcall(cjson.decode, current)
    if ok and type(entry) == 'table' and tonumber(entry['priceVersion']) then
      local currentPriceVersion = tonumber(entry['priceVersion'])
      local currentProductVersion = tonumber(entry['productVersion']) or 0
      if priceVersion < currentPriceVersion
          or (priceVersion == currentPriceVersion and productVersion <= currentProductVersion) then
        newer = false
      end
    end
  end
  if newer then
    redis.call('SET', key, ARGV[base], 'PX', ttl)
    written = written + 1
  end
end
return written
