-- 1. 拿到大妈买的券的ID 和 大妈的身份证号
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3] -- 🔥 新增：接收主线程提前生成好的订单号
-- 2. 组装 Redis 里的两个小黑板的名字
-- 库存黑板：seckill:stock:10086
local stockKey = 'seckill:stock:' .. voucherId
-- 订单名单黑板（使用 Set 结构）：seckill:order:10086
local orderKey = 'seckill:order:' .. voucherId

-- 3. 判断库存是否大于 0
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，直接返回 1
    return 1
end

-- 4. 判断大妈是不是已经买过了（在不在名单 Set 里）
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 已经买过了，直接返回 2
    return 2
end

-- 5. 校验全部通过！开始干活：
-- 扣库存：库存减 1
redis.call('incrby', stockKey, -1)
-- 记名字：把大妈的 ID 扔进名单 Set 里
redis.call('sadd', orderKey, userId)

-- ==========================================
-- 📦 6. 核心新增：将订单信息推入 List 消息队列
-- ==========================================
-- 拼接格式："订单号,用户ID,券ID"
-- local msg = orderId .. ',' .. userId ',' .. voucherId
-- 使用 LPUSH 将消息从左侧推入名为 "queue:seckill:order" 的 List 中

-- 从LPUSH 换成 XADD
redis.call('xadd', 'stream.order', '*', 'userId' , userId, 'voucherId' , voucherId, 'id' , orderId)

-- 6. 大功告成，返回 0
return 0