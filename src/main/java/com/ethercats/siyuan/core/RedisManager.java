package com.ethercats.siyuan.core;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public class RedisManager {
    private JedisPool pool;
    private final JavaPlugin plugin;
    private boolean enabled = false;
    private final AtomicLong nextWarningAt = new AtomicLong();

    public RedisManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("redis.enabled", false)) {
            plugin.getLogger().info("[Redis] Redis 未启用，跳过初始化");
            return true;
        }
        String host = cfg.getString("redis.host", "127.0.0.1");
        int port = cfg.getInt("redis.port", 6380);
        String password = System.getenv().getOrDefault("SIYUAN_REDIS_PASSWORD",
            cfg.getString("redis.password", ""));
        
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        
        try {
            if (password.isEmpty()) {
                pool = new JedisPool(poolConfig, host, port, 3000);
            } else {
                pool = new JedisPool(poolConfig, host, port, 3000, password);
            }
            try (Jedis j = pool.getResource()) {
                j.ping();
            }
            enabled = true;
            plugin.getLogger().info("[Redis] 连接成功");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Redis] 连接失败，禁用 Redis 功能: " + e.getMessage());
            enabled = false;
            return true;
        }
    }

    public boolean isEnabled() { return enabled; }

    public String get(String key) {
        if (!enabled) return null;
        try (Jedis j = pool.getResource()) { return j.get(key); }
        catch (Exception e) { warn("GET", e); return null; }
    }

    public void set(String key, String value) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.set(key, value); }
        catch (Exception e) { warn("SET", e); }
    }

    public void set(String key, String value, int seconds) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.setex(key, seconds, value); }
        catch (Exception e) { warn("SETEX", e); }
    }

    public void del(String key) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.del(key); }
        catch (Exception e) { warn("DEL", e); }
    }

    public boolean exists(String key) {
        if (!enabled) return false;
        try (Jedis j = pool.getResource()) { return j.exists(key); }
        catch (Exception e) { warn("EXISTS", e); return false; }
    }

    public void zincrby(String key, double score, String member) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.zincrby(key, score, member); }
        catch (Exception e) { warn("ZINCRBY", e); }
    }

    public void zadd(String key, double score, String member) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.zadd(key, score, member); }
        catch (Exception e) { warn("ZADD", e); }
    }

    public List<String> zrevrange(String key, long start, long end) {
        if (!enabled) return List.of();
        try (Jedis j = pool.getResource()) { return j.zrevrange(key, start, end); }
        catch (Exception e) { warn("ZREVRANGE", e); return List.of(); }
    }

    public Double zscore(String key, String member) {
        if (!enabled) return null;
        try (Jedis j = pool.getResource()) { return j.zscore(key, member); }
        catch (Exception e) { warn("ZSCORE", e); return null; }
    }

    public void zrem(String key, String... members) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.zrem(key, members); }
        catch (Exception e) { warn("ZREM", e); }
    }

    public void publish(String channel, String message) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.publish(channel, message); }
        catch (Exception e) { warn("PUBLISH", e); }
    }

    public void hincrby(String key, String field, long value) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.hincrBy(key, field, value); }
        catch (Exception e) { warn("HINCRBY", e); }
    }

    public void incrBy(String key, long value, int ttlSeconds) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) {
            j.incrBy(key, value);
            j.expire(key, ttlSeconds);
        } catch (Exception e) { warn("INCRBY", e); }
    }

    /**
     * Atomically consumes a bounded counter. Returns -1 when Redis is
     * unavailable, 0 when the cap is exhausted, and the consumed amount on
     * success. The Lua script makes the daily reward limit safe across servers.
     */
    public long consumeQuota(String key, long requested, long cap, int ttlSeconds) {
        if (!enabled || requested <= 0 || cap <= 0) return -1;
        String script = "local current=tonumber(redis.call('GET',KEYS[1]) or '0'); "
            + "local ask=tonumber(ARGV[1]); local limit=tonumber(ARGV[2]); "
            + "local allowed=math.min(ask, math.max(0, limit-current)); "
            + "if allowed <= 0 then return 0 end; "
            + "redis.call('INCRBY', KEYS[1], allowed); redis.call('EXPIRE', KEYS[1], ARGV[3]); return allowed;";
        try (Jedis j = pool.getResource()) {
            Object result = j.eval(script, Collections.singletonList(key),
                List.of(Long.toString(requested), Long.toString(cap), Integer.toString(Math.max(60, ttlSeconds))));
            return result instanceof Number number ? number.longValue() : -1;
        } catch (Exception e) {
            warn("QUOTA", e);
            return -1;
        }
    }

    public void releaseQuota(String key, long amount, int ttlSeconds) {
        if (!enabled || amount <= 0) return;
        try (Jedis j = pool.getResource()) {
            j.eval("local current=tonumber(redis.call('GET',KEYS[1]) or '0'); "
                    + "local next=math.max(0,current-tonumber(ARGV[1])); "
                    + "if next == 0 then redis.call('DEL',KEYS[1]) "
                    + "else redis.call('SET',KEYS[1],next); redis.call('EXPIRE',KEYS[1],ARGV[2]); end; return next;",
                Collections.singletonList(key), List.of(Long.toString(amount), Integer.toString(Math.max(60, ttlSeconds))));
        } catch (Exception e) {
            warn("QUOTA_RELEASE", e);
        }
    }

    public String hget(String key, String field) {
        if (!enabled) return null;
        try (Jedis j = pool.getResource()) { return j.hget(key, field); }
        catch (Exception e) { warn("HGET", e); return null; }
    }

    public void expire(String key, int seconds) {
        if (!enabled) return;
        try (Jedis j = pool.getResource()) { j.expire(key, seconds); }
        catch (Exception e) { warn("EXPIRE", e); }
    }

    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            plugin.getLogger().info("[Redis] 连接池已关闭");
        }
    }

    private void warn(String operation, Exception error) {
        long now = System.currentTimeMillis();
        long next = nextWarningAt.get();
        if (now >= next && nextWarningAt.compareAndSet(next, now + 60_000L)) {
            plugin.getLogger().warning("[Redis] " + operation + " 失败，功能将降级: " + error.getMessage());
        }
    }
}
