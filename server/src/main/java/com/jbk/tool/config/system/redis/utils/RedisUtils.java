package com.jbk.tool.config.system.redis.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName RedisUtil
 * @Author xs
 * @Date 2024/6/7 11:48
 * @Version 1.0
 */
public class RedisUtils {
    // =============================common============================


    /**
     * 指定缓存失效时间
     *
     * @param key  键
     * @param time 时间(秒)
     * @return
     */
    public static <T> boolean expire(RedisTemplate<String, T> redis, String key, long time) {
        if (time > 0) {
            redis.expire(key, time, TimeUnit.SECONDS);
        }
        return true;
    }

    /**
     * 根据key 获取过期时间
     *
     * @param key 键 不能为null
     * @return 时间(秒) 返回0代表为永久有效
     */

    public static <T> long getExpire(RedisTemplate<String, T> redis, String key) {
        return redis.getExpire(key, TimeUnit.SECONDS);

    }

    /**
     * 判断key是否存在
     *
     * @param key 键
     * @return true 存在 false不存在
     */

    public static <T> boolean hasKey(RedisTemplate<String, T> redis, String key) {
        return redis.hasKey(key);
    }

    /**
     * 删除缓存
     *
     * @param key 可以传一个值 或多个
     */

    public static <T> void del(RedisTemplate<String, T> redis, String... key) {
        if (key != null && key.length > 0) {
            if (key.length == 1) {
                redis.delete(key[0]);
            } else {
                redis.delete((Collection<String>) CollectionUtils.arrayToList(key));
            }
        }
    }


    /**
     * 模糊删除key
     *
     * @param redis
     * @param prefix
     */
    public static <T> void deleteByPrefix(RedisTemplate<String, T> redis, String prefix) {
        Set<String> keys = redis.keys(prefix + "*");
        redis.delete(keys);
    }

    // ============================String=============================

    /**
     * 普通缓存获取
     *
     * @param key 键
     * @return 值
     */

    public static <T> T get(RedisTemplate<String, T> redis, String key) {
        return key == null ? null : redis.opsForValue().get(key);
    }

    /**
     * 原子领取并删除（GETDEL 语义，Redis 6.2+ / Spring Data Redis 2.6+）。
     * 用于一次性令牌（如绑定 bindTicket）：并发/重复领取只有一个拿到值，其余得 null；成功/失败领取后均不可再取。
     *
     * @param key 键
     * @return 领取到的值；键不存在或已被领取时为 null
     */
    public static <T> T getDel(RedisTemplate<String, T> redis, String key) {
        return key == null ? null : redis.opsForValue().getAndDelete(key);
    }

    /**
     * 普通缓存放入
     *
     * @param key   键
     * @param value 值
     * @return true成功 false失败
     */

    public static <T> boolean set(RedisTemplate<String, T> redis, String key, T value) {
        redis.opsForValue().set(key, value);
        return true;
    }

    /**
     * 如果为空就set值，并返回1如果存在(不为空)不进行操作
     *
     * @param key   键
     * @param value 值
     * @return true成功 false失败
     */

    public static <T> boolean setIfAbsent(RedisTemplate<String, T> redis, String key, T value, long time) {
        if (time > 0) {
            return redis.opsForValue().setIfAbsent(key, value, time, TimeUnit.SECONDS);
        } else {
            return set(redis, key, value);
        }
    }

    /**
     * 普通缓存放入并设置时间
     *
     * @param key   键
     * @param value 值
     * @param time  时间(秒) time要大于0 如果time小于等于0 将设置无限期
     * @return true成功 false 失败
     */

    public static <T> boolean set(RedisTemplate<String, T> redis, String key, T value, long time) {
        if (time > 0) {
            redis.opsForValue().set(key, value, time, TimeUnit.SECONDS);
        } else {
            set(redis, key, value);
        }
        return true;
    }

    /**
     * 递增
     *
     * @param key   键
     * @param delta 要增加几(大于0)
     * @return
     */

    public static <T> long incr(RedisTemplate<String, T> redis, String key, long delta) {
        if (delta < 0) {
            throw new RuntimeException("递增因子必须大于0");
        }
        return redis.opsForValue().increment(key, delta);
    }

    /**
     * 递减
     *
     * @param key   键
     * @param delta 要减少几(小于0)
     * @return
     */

    public static <T> long decr(RedisTemplate<String, T> redis, String key, long delta) {
        if (delta < 0) {
            throw new RuntimeException("递减因子必须大于0");
        }
        return redis.opsForValue().increment(key, -delta);
    }

    // ================================Map=================================

    /**
     * HashGet
     *
     * @param key  键 不能为null
     * @param item 项 不能为null
     * @return 值
     */

    public static Object hget(RedisTemplate<String, Object> redis, String key, String item) {
        return redis.opsForHash().get(key, item);

    }

    /**
     * 获取hashKey对应的所有键值
     *
     * @param key 键
     * @return 对应的多个键值
     */

    public static Map<Object, Object> hmget(RedisTemplate<String, Object> redis, String key) {
        return redis.opsForHash().entries(key);
    }

    /**
     * HashSet
     *
     * @param key 键
     * @param map 对应多个键值
     * @return true 成功 false 失败
     */

    public static boolean hmset(RedisTemplate<String, Object> redis, String key, Map<String, Object> map) {
        redis.opsForHash().putAll(key, map);
        return true;
    }

    /**
     * HashSet 并设置时间
     *
     * @param key  键
     * @param map  对应多个键值
     * @param time 时间(秒)
     * @return true成功 false失败
     */

    public static boolean hmset(RedisTemplate<String, Object> redis, String key, Map<String, Object> map, long time) {
        redis.opsForHash().putAll(key, map);
        if (time > 0) {
            expire(redis, key, time);
        }
        return true;
    }

    /**
     * 向一张hash表中放入数据,如果不存在将创建
     *
     * @param key   键
     * @param item  项
     * @param value 值
     * @return true 成功 false失败
     */

    public static boolean hset(RedisTemplate<String, Object> redis, String key, String item, Object value) {
        redis.opsForHash().put(key, item, value);
        return true;
    }

    /**
     * 向一张hash表中放入数据,如果不存在将创建
     *
     * @param key   键
     * @param item  项
     * @param value 值
     * @param time  时间(秒) 注意:如果已存在的hash表有时间,这里将会替换原有的时间
     * @return true 成功 false失败
     */

    public static boolean hset(RedisTemplate<String, Object> redis, String key, String item, Object value, long time) {
        redis.opsForHash().put(key, item, value);
        if (time > 0) {
            expire(redis, key, time);
        }
        return true;
    }

    /**
     * 删除hash表中的值
     *
     * @param key  键 不能为null
     * @param item 项 可以使多个 不能为null
     */

    public static void hdel(RedisTemplate<String, Object> redis, String key, Object... item) {
        redis.opsForHash().delete(key, item);
    }

    /**
     * 判断hash表中是否有该项的值
     *
     * @param key  键 不能为null
     * @param item 项 不能为null
     * @return true 存在 false不存在
     */

    public static boolean hHasKey(RedisTemplate<String, Object> redis, String key, String item) {
        return redis.opsForHash().hasKey(key, item);
    }

    /**
     * hash递增 如果不存在,就会创建一个 并把新增后的值返回
     *
     * @param key  键
     * @param item 项
     * @param by   要增加几
     * @return
     */

    public static double hincr(RedisTemplate<String, Object> redis, String key, String item, double by) {
        return redis.opsForHash().increment(key, item, by);
    }

    /**
     * hash递减
     *
     * @param key  键
     * @param item 项
     * @param by   要减少记
     * @return
     */

    public static double hdecr(RedisTemplate<String, Object> redis, String key, String item, double by) {
        return redis.opsForHash().increment(key, item, -by);
    }

    // ============================set=============================

    /**
     * 根据key获取Set中的所有值
     *
     * @param key 键
     * @return
     */

    public static Set<Object> sGet(RedisTemplate<String, Object> redis, String key) {
        return redis.opsForSet().members(key);
    }

    /**
     * 根据value从一个set中查询,是否存在
     *
     * @param key   键
     * @param value 值
     * @return true 存在 false不存在
     */

    public static boolean sHasKey(RedisTemplate<String, Object> redis, String key, Object value) {
        return redis.opsForSet().isMember(key, value);
    }

    /**
     * 将数据放入set缓存
     *
     * @param key    键
     * @param values 值 可以是多个
     * @return 成功个数
     */

    public static long sSet(RedisTemplate<String, Object> redis, String key, Object... values) {
        return redis.opsForSet().add(key, values);
    }

    /**
     * 将set数据放入缓存
     *
     * @param key    键
     * @param time   时间(秒)
     * @param values 值 可以是多个
     * @return 成功个数
     */

    public static long sSetAndTime(RedisTemplate<String, Object> redis, String key, long time, Object... values) {
        Long count = redis.opsForSet().add(key, values);
        if (time > 0) {
            expire(redis, key, time);
        }
        return count;
    }

    /**
     * 获取set缓存的长度
     *
     * @param key 键
     * @return
     */

    public static long sGetSetSize(RedisTemplate<String, Object> redis, String key) {
        return redis.opsForSet().size(key);
    }

    /**
     * 移除值为value的
     *
     * @param key    键
     * @param values 值 可以是多个
     * @return 移除的个数
     */

    public static long sRemove(RedisTemplate<String, Object> redis, String key, Object... values) {
        Long count = redis.opsForSet().remove(key, values);
        return count;
    }

    // ===============================list=================================

    /**
     * 获取list缓存的内容
     *
     * @param key   键
     * @param start 开始
     * @param end   结束 0 到 -1代表所有值
     * @return
     */

    public static List<Object> lGet(RedisTemplate<String, Object> redis, String key, long start, long end) {
        return redis.opsForList().range(key, start, end);
    }

    /**
     * 模糊查询key
     *
     * @param redis
     * @param prefix
     */

    public static Set<String> getKeysByPrefix(RedisTemplate<String, Object> redis, String prefix) {
        return redis.keys(prefix + "*");
    }

    /**
     * 获取list缓存的长度
     *
     * @param key 键
     * @return
     */

    public static long lGetListSize(RedisTemplate<String, Object> redis, String key) {
        return redis.opsForList().size(key);
    }

    /**
     * 通过索引 获取list中的值
     *
     * @param key   键
     * @param index 索引 index>=0时， 0 表头，1 第二个元素，依次类推；index<0时，-1，表尾，-2倒数第二个元素，依次类推
     * @return
     */

    public static Object lGetIndex(RedisTemplate<String, Object> redis, String key, long index) {
        return redis.opsForList().index(key, index);
    }

    /**
     * 将list放入缓存
     *
     * @param key   键
     * @param value 值
     * @return
     */

    public static boolean lSet(RedisTemplate<String, Object> redis, String key, Object value) {
        redis.opsForList().rightPush(key, value);
        return true;
    }

    /**
     * 将list放入缓存
     *
     * @param key   键
     * @param value 值
     * @return
     */

    public static boolean lLeftSet(RedisTemplate<String, Object> redis, String key, Object value) {
        redis.opsForList().leftPush(key, value);
        return true;
    }

    /**
     * 将list放入缓存
     *
     * @param key   键
     * @param value 值
     * @param time  时间(秒)
     * @return
     */

    public static boolean lSet(RedisTemplate<String, Object> redis, String key, Object value, long time) {
        redis.opsForList().rightPush(key, value);
        if (time > 0) {
            expire(redis, key, time);
        }
        return true;
    }

    /**
     * 将list放入缓存
     *
     * @param key   键
     * @param value 值
     * @return
     */

    public static boolean lSet(RedisTemplate<String, Object> redis, String key, List<Object> value) {
        redis.opsForList().rightPushAll(key, value);
        return true;
    }

    /**
     * 将list放入缓存
     *
     * @param key   键
     * @param value 值
     * @param time  时间(秒)
     * @return
     */

    public static boolean lSet(RedisTemplate<String, Object> redis, String key, List<Object> value, long time) {
        redis.opsForList().rightPushAll(key, value);
        if (time > 0) {
            expire(redis, key, time);
        }
        return true;

    }

    /**
     * 根据索引修改list中的某条数据
     *
     * @param key   键
     * @param index 索引
     * @param value 值
     * @return
     */

    public static boolean lUpdateIndex(RedisTemplate<String, Object> redis, String key, long index, Object value) {
        redis.opsForList().set(key, index, value);
        return true;
    }

    /**
     * 移除N个值为value
     *
     * @param key   键
     * @param count 移除多少个
     * @param value 值
     * @return 移除的个数
     */

    public static long lRemove(RedisTemplate<String, Object> redis, String key, long count, Object value) {
        return redis.opsForList().remove(key, count, value);
    }

    // 正向获取list-string
    public static List<Object> getListOnLeft(RedisTemplate<String, Object> redis, String key, long start, long end) {
        return redis.opsForList().range(key, start, end);
    }

    // ===========================其他
    public static Long toLong(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value cannot be converted to Long: " + value, e);
            }
        } else {
            throw new IllegalArgumentException("Unsupported value type: " + value.getClass().getName());
        }
    }
}


