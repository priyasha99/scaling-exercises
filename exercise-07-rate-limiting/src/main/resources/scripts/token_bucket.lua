--[[
  Token Bucket Rate Limiter (Redis Lua Script)

  WHY LUA IN REDIS?
  Rate limiting requires multiple operations that MUST be atomic:
    1. Read current token count and last refill time
    2. Calculate how many tokens to add (based on elapsed time)
    3. Check if there are enough tokens for this request
    4. Deduct a token if allowed
  If these were separate Redis commands, two requests arriving
  simultaneously could both read "1 token left" and both proceed.
  Lua scripts execute atomically in Redis — no race conditions.

  TOKEN BUCKET ALGORITHM:
  - Bucket has a maximum capacity (e.g., 100 tokens)
  - Tokens are added at a fixed rate (e.g., 10 per second)
  - Each request consumes 1 token
  - If bucket is empty, request is rejected (429)
  - Burst: a full bucket allows capacity requests instantly

  KEYS[1] = rate limit key (e.g., "rate_limit::user::alice" or "rate_limit::global")
  ARGV[1] = bucket capacity (max tokens)
  ARGV[2] = refill rate (tokens per second)
  ARGV[3] = current timestamp (seconds, with decimal precision)
  ARGV[4] = tokens to consume (usually 1)

  RETURNS: {allowed (0 or 1), tokens_remaining, retry_after_ms}
]]

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- Get current state (tokens, last_refill_time)
local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

-- Initialize if first request
if tokens == nil then
  tokens = capacity
  last_refill = now
end

-- Calculate tokens to add based on elapsed time
local elapsed = math.max(0, now - last_refill)
local new_tokens = elapsed * refill_rate
tokens = math.min(capacity, tokens + new_tokens)

-- Update last refill time
last_refill = now

-- Check if enough tokens
local allowed = 0
local retry_after_ms = 0

if tokens >= requested then
  -- Allow the request, consume tokens
  tokens = tokens - requested
  allowed = 1
else
  -- Reject — calculate when enough tokens will be available
  local deficit = requested - tokens
  local wait_seconds = deficit / refill_rate
  retry_after_ms = math.ceil(wait_seconds * 1000)
end

-- Save state back to Redis (expire after 2x the time to fill bucket)
local ttl = math.ceil(capacity / refill_rate * 2)
redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_refill', tostring(last_refill))
redis.call('EXPIRE', key, ttl)

return {allowed, math.floor(tokens), retry_after_ms}
