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

package com.netflix.eureka;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.netflix.appinfo.AbstractEurekaIdentity;
import com.netflix.appinfo.EurekaClientIdentity;
import com.netflix.eureka.util.EurekaMonitors;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rate limiting filter, with configurable threshold above which non-privileged clients
 * will be dropped. This feature enables cutting off non-standard and potentially harmful clients
 * in case of system overload. Since it is critical to always allow client registrations and heartbeats into
 * the system, which at the same time are relatively cheap operations, the rate limiting is applied only to
 * full and delta registry fetches. Furthermore, since delta fetches are much smaller than full fetches,
 * and if not served my result in following full registry fetch from the client, they have relatively
 * higher priority. This is implemented by two parallel rate limiters, one for overall number of
 * full/delta fetches (higher threshold) and one for full fetches only (low threshold).
 *
 * 客户端的ID由 DiscoveryIdentity-Name 标识，默认的特权组会包含 DefaultClient 和 DefaultServer；开发者可以
 * 通过getRateLimiterPrivilegedClients()自定义特权组。
 * <p>
 * The client is identified by {@link AbstractEurekaIdentity#AUTH_NAME_HEADER_KEY} HTTP header
 * value. The privileged group by default contains:
 * <ul>
 * <li>
 *     {@link EurekaClientIdentity#DEFAULT_CLIENT_NAME} - standard Java eureka-client. Applications using
 *     this client automatically belong to the privileged group.
 * </li>
 * <li>
 *     {@link com.netflix.eureka.EurekaServerIdentity#DEFAULT_SERVER_NAME} - connections from peer Eureka servers
 *     (internal only, traffic replication)
 * </li>
 * </ul>
 * 可以通过打开isRateLimiterThrottleStandardClients()设置来开启对特权客户端的验证
 *
 * It is possible to turn off privileged client filtering via
 * {@link EurekaServerConfig#isRateLimiterThrottleStandardClients()} property.
 * <p>
 * Rate limiting is not enabled by default, but can be turned on via configuration. Even when disabled,
 * the throttling statistics are still counted, although on a separate counter, so it is possible to
 * measure the impact of this feature before activation.
 *
 * <p>
 * Rate limiter implementation is based on token bucket algorithm. There are two configurable
 * parameters:
 * <ul>
 * <li>
 *     burst size - maximum number of requests allowed into the system as a burst
 * </li>
 * <li>
 *     average rate - expected number of requests per second
 * </li>
 * </ul>
 *
 * @author Tomasz Bak
 */
@Singleton
public class RateLimitingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final Set<String> DEFAULT_PRIVILEGED_CLIENTS = new HashSet<String>(
            Arrays.asList(EurekaClientIdentity.DEFAULT_CLIENT_NAME, EurekaServerIdentity.DEFAULT_SERVER_NAME)
    );

    private static final Pattern TARGET_RE = Pattern.compile("^.*/apps(/[^/]*)?$");

    enum Target {FullFetch, DeltaFetch, Application, Other}

    /**
     * Includes both full and delta fetches.
     * 全量和增量都支持的限流器？？！
     */
    private static final RateLimiter registryFetchRateLimiter = new RateLimiter(TimeUnit.SECONDS);

    /**
     * Only full registry fetches.
     * 用于全量拉取的限流器
     */
    private static final RateLimiter registryFullFetchRateLimiter = new RateLimiter(TimeUnit.SECONDS);

    private EurekaServerConfig serverConfig;

    @Inject
    public RateLimitingFilter(EurekaServerContext server) {
        this.serverConfig = server.getServerConfig();
    }

    // for non-DI use
    public RateLimitingFilter() {
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        if (serverConfig == null) {
            EurekaServerContext serverContext = (EurekaServerContext) filterConfig.getServletContext()
                    .getAttribute(EurekaServerContext.class.getName());
            serverConfig = serverContext.getServerConfig();
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        // 获取当前请求的类别
        Target target = getTarget(request);
        // 如果target为OTHER，直接放行
        if (target == Target.Other) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        // 判断该请求是否限速
        if (isRateLimited(httpRequest, target)) {
            incrementStats(target);
            if (serverConfig.isRateLimiterEnabled()) {
                ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 判断该请求是什么类型
     * @param request ServletRequest Http请求
     * @return Target：FullFetch、DeltaFetch、Application
     */
    private static Target getTarget(ServletRequest request) {
        Target target = Target.Other;
        if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String pathInfo = httpRequest.getRequestURI();

            if ("GET".equals(httpRequest.getMethod()) && pathInfo != null) {
                // 判断路径中是否带有/apps/
                Matcher matcher = TARGET_RE.matcher(pathInfo);
                if (matcher.matches()) {
                    //
                    if (matcher.groupCount() == 0 || matcher.group(1) == null || "/".equals(matcher.group(1))) {
                        // 路径是 /apps/ 的就FullFetch
                        target = Target.FullFetch;
                    } else if ("/delta".equals(matcher.group(1))) {
                        // 带有/delta的是DeltaFetch
                        target = Target.DeltaFetch;
                    } else {
                        // 其他的为Application请求
                        target = Target.Application;
                    }
                }
            }
            if (target == Target.Other) {
                logger.debug("URL path {} not matched by rate limiting filter", pathInfo);
            }
        }
        return target;
    }

    /**
     * 判断是否要限速
     * @param request 请求
     * @param target 请求类型
     * @return true为限速，false为不限速
     */
    private boolean isRateLimited(HttpServletRequest request, Target target) {
        // 判断是否有特权
        if (isPrivileged(request)) {
            logger.debug("Privileged {} request", target);
            return false;
        }
        // 检查是否限流
        if (isOverloaded(target)) {
            logger.debug("Overloaded {} request; discarding it", target);
            return true;
        }
        logger.debug("{} request admitted", target);
        return false;
    }

    /**
     * 判断是否有特权
     * @param request 请求
     * @return true为有特权；false为没有特权
     */
    private boolean isPrivileged(HttpServletRequest request) {
        // 是否限制标准的客户端；如果为true，说明所有客户端（标准或非标准）都要限制
        // 如果为false，说明只有非标准的客户端需要限制
        if (serverConfig.isRateLimiterThrottleStandardClients()) {
            return false;
        }
        // 获取标准的客户端列表，只有客户端名字在这个列表中的才算特权客户端
        Set<String> privilegedClients = serverConfig.getRateLimiterPrivilegedClients();
        // 获取Http请求头中的 DiscoveryIdentity-Name 字段
        String clientName = request.getHeader(AbstractEurekaIdentity.AUTH_NAME_HEADER_KEY);
        // 判断该客户端是否在用户定义的特权客户端组内；或者属于默认的特权客户端
        return privilegedClients.contains(clientName) || DEFAULT_PRIVILEGED_CLIENTS.contains(clientName);
    }

    /**
     * 判断是否限流
     * @param target 请求类型
     * @return true为限流；false表示不限流
     */
    private boolean isOverloaded(Target target) {
        // 获取桶内最大令牌数量（也是允许通过的最大请求数量）
        int maxInWindow = serverConfig.getRateLimiterBurstSize();
        // 获取拉取注册信息时的 令牌填充速率（包括增量和全量拉取，保证总的一个上限）
        int fetchWindowSize = serverConfig.getRateLimiterRegistryFetchAverageRate();
        // 判断增量拉取是否限速，如果acquire为false，就说明要限流
        boolean overloaded = !registryFetchRateLimiter.acquire(maxInWindow, fetchWindowSize);
        // 判断是否为全量拉取，如果是全量拉取，就判断全量拉取是否限速（仅针对全量拉取，保证全量拉取的上限，因为它耗费的资源多）
        if (target == Target.FullFetch) {
            int fullFetchWindowSize = serverConfig.getRateLimiterFullFetchAverageRate();
            overloaded |= !registryFullFetchRateLimiter.acquire(maxInWindow, fullFetchWindowSize);
        }
        return overloaded;
    }

    /**
     * 递增计数器用的
     * @param target
     */
    private void incrementStats(Target target) {
        if (serverConfig.isRateLimiterEnabled()) {
            EurekaMonitors.RATE_LIMITED.increment();
            if (target == Target.FullFetch) {
                EurekaMonitors.RATE_LIMITED_FULL_FETCH.increment();
            }
        } else {
            EurekaMonitors.RATE_LIMITED_CANDIDATES.increment();
            if (target == Target.FullFetch) {
                EurekaMonitors.RATE_LIMITED_FULL_FETCH_CANDIDATES.increment();
            }
        }
    }

    @Override
    public void destroy() {
    }

    // For testing purposes
    static void reset() {
        registryFetchRateLimiter.reset();
        registryFullFetchRateLimiter.reset();
    }
}
