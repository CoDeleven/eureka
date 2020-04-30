/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter implementation is based on token bucket algorithm. There are two parameters:
 * <ul>
 * <li>
 *     burst size - maximum number of requests allowed into the system as a burst
 *     桶里允许放入的最大令牌数量
 * </li>
 * <li>
 *     average rate - expected number of requests per second (RateLimiters using MINUTES is also supported)
 *     令牌刷新间隔时间。如果设定的单位是分钟，那么就是每隔一分钟放入averageRate个令牌；
 *     如果设定的单位是秒，那么就是每隔一秒钟放入averageRate个令牌
 * </li>
 * </ul>
 *
 * @author Tomasz Bak
 */
public class RateLimiter {
    // 记时单位
    private final long rateToMsConversion;
    // 消耗的令牌数量
    private final AtomicInteger consumedTokens = new AtomicInteger();
    // 上一次填充令牌的时间
    private final AtomicLong lastRefillTime = new AtomicLong(0);

    @Deprecated
    public RateLimiter() {
        this(TimeUnit.SECONDS);
    }
    // 设置填充令牌速率的单位
    public RateLimiter(TimeUnit averageRateUnit) {
        switch (averageRateUnit) {
            // 如果单位是秒
            case SECONDS:
                // 设置基本时间单位
                rateToMsConversion = 1000;
                break;
            // 如果单位是分钟欧冠
            case MINUTES:
                // 设置基本事件单位
                rateToMsConversion = 60 * 1000;
                break;
            default:
                throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
        }
    }

    /**
     * 尝试获取一个令牌
     * @param burstSize 该桶允许容纳的最大令牌数量
     * @param averageRate 生成新令牌的频率
     * @return
     */
    public boolean acquire(int burstSize, long averageRate) {
        return acquire(burstSize, averageRate, System.currentTimeMillis());
    }

    /**
     * 尝试获取一个令牌
     * @param burstSize 该桶允许容纳的最大令牌数量
     * @param averageRate 生成新令牌的频率
     * @param currentTimeMillis 当前时间
     * @return
     */
    public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
        // 如果这两个参数小于0，就让流量全都通过
        if (burstSize <= 0 || averageRate <= 0) { // Instead of throwing exception, we just let all the traffic go
            return true;
        }
        // 填充令牌
        refillToken(burstSize, averageRate, currentTimeMillis);
        return consumeToken(burstSize);
    }

    /**
     * 填充令牌
     * @param burstSize 该桶允许容纳的最大令牌数量
     * @param averageRate 生成新令牌的频率
     * @param currentTimeMillis 当前时间
     */
    private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
        // 获取上一次填充的时间
        long refillTime = lastRefillTime.get();
        // 获取此时 和 之前填充时间的时间差
        long timeDelta = currentTimeMillis - refillTime;
        // 时间差（单位毫秒） * 生成速率（单位个/UNIT）/ UNIT * 1000
        // 代入一个例子，当前单位是分钟，时间差是30秒，生成速率是60（即每分钟60个）
        // 结果 30 * 1000 * 60 / 60 * 1000
        // 这段时间产生了30个新的令牌
        long newTokens = timeDelta * averageRate / rateToMsConversion;
        // 新的令牌数量大于0
        if (newTokens > 0) {
            // 计算新的填充令牌的时间
            long newRefillTime = refillTime == 0
                    ? currentTimeMillis
                    : refillTime + newTokens * rateToMsConversion / averageRate;
            // CAS
            if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) {
                while (true) {
                    // 获取当前已经消耗的令牌数量
                    int currentLevel = consumedTokens.get();
                    // 比较 当前消耗的令牌数量 和 新生成的令牌数量，取最小值
                    // 为什么取最小值？因为这一步只想获取当前已经消耗的令牌数量
                    // 消耗的令牌数量不能超过桶的最大值
                    // 如果消耗的令牌数量超过了此时指定的桶内最大令牌数（爆满）
                    // 就以此时桶内最大令牌数为基准来计算
                    int adjustedLevel = Math.min(currentLevel, burstSize); // In case burstSize decreased
                    // 如果adjustedLevel - newTokens < 0，说明新生成的令牌数量够用（并抵消了之前的已经被取走的令牌）
                    // 如果adjustedLevel - newTokens > 0，说明新生成的令牌数量被用掉了一部分
                    int newLevel = (int) Math.max(0, adjustedLevel - newTokens);
                    if (consumedTokens.compareAndSet(currentLevel, newLevel)) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * 消耗一个令牌
     * @param burstSize 此时允许桶内最大令牌数
     * @return
     */
    private boolean consumeToken(int burstSize) {
        while (true) {
            // 获取此时消耗的令牌数
            int currentLevel = consumedTokens.get();
            // 如果已经消耗的令牌数 大于等于 桶内可容纳最大令牌数，就返回false
            if (currentLevel >= burstSize) {
                return false;
            }
            // 如果允许再拿令牌，就增加一个消耗的令牌数
            if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
                return true;
            }
        }
    }

    /**
     * 重置限流器
     */
    public void reset() {
        consumedTokens.set(0);
        lastRefillTime.set(0);
    }
}
