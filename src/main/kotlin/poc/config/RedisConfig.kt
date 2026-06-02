package poc.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

@Configuration
class RedisConfig {
    @Bean(destroyMethod = "close")
    fun jedisPool(
        @Value("\${redis.host}") host: String,
        @Value("\${redis.port}") port: Int,
    ): JedisPool {
        val cfg = JedisPoolConfig().apply { maxTotal = 256; maxIdle = 64 }
        return JedisPool(cfg, host, port)
    }
}
