-- Atomic stock decrement script
-- KEYS[1] = stock:{productId}
-- Returns:
--   -1  = key does not exist (product not in cache)
--   -2  = sold out (stock was already 0)
--   >= 0 = remaining stock after successful decrement (0 means got the last item)
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -1 end
if stock <= 0 then return -2 end
local remaining = redis.call('DECR', KEYS[1])
return remaining
