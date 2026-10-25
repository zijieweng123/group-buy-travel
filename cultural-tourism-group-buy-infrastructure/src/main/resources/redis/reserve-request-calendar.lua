-- KEYS: request idempotency key, date stock counter. ARGV: stock capacity, lease ms, invocation token.
-- The invocation token makes a transport-level replay safe without accepting a second HTTP request.
local previous = redis.call('GET', KEYS[1])
if previous then
    if previous == ARGV[3] then return 1 end
    return 0
end
local capacity = tonumber(ARGV[1])
local lease = tonumber(ARGV[2])
if not capacity or capacity <= 0 then return -1 end
if not lease or lease <= 0 or KEYS[1] == KEYS[2] then
    return redis.error_reply('invalid reservation arguments')
end
local occupied = tonumber(redis.call('GET', KEYS[2]) or '0')
if not occupied or occupied < 0 or occupied % 1 ~= 0 then
    return redis.error_reply('invalid calendar counter')
end
if occupied >= capacity then return -1 end
local granted = redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[2], 'NX')
if not granted then return 0 end
local incremented = redis.pcall('INCR', KEYS[2])
if type(incremented) == 'table' and incremented.err then
    redis.call('DEL', KEYS[1])
    return redis.error_reply(incremented.err)
end
return 1
