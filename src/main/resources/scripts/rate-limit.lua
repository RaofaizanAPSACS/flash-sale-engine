-- Sliding window rate limiter
-- KEYS[1] = rate limit key (e.g. rate:{userId})
-- ARGV[1] = window size in seconds
-- ARGV[2] = max requests allowed in window
-- ARGV[3] = current timestamp in milliseconds
-- Returns: 1 if allowed, 0 if rate limited

local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local windowStart = now - (window * 1000)

-- Remove entries outside the window
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)

-- Count current requests in window
local count = redis.call('ZCARD', key)

if count >= limit then
    return 0
end

-- Add current request
redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))

-- Set expiry on the key
redis.call('PEXPIRE', key, window * 1000)

return 1
