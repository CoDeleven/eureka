package com.netflix.discovery;

import com.netflix.discovery.endpoint.EndpointUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DNS-based region mapper that discovers regions via DNS TXT records.
 *
 * 通过DNS TXT记录查询 基于DNS的区域、空间映射关系
 *
 * @author Nitesh Kant
 */
public class DNSBasedAzToRegionMapper extends AbstractAzToRegionMapper {

    public DNSBasedAzToRegionMapper(EurekaClientConfig clientConfig) {
        super(clientConfig);
    }

    /**
     * 根据region 获取 可用的空间
     * @param region the region whose zones you want
     * @return
     */
    @Override
    protected Set<String> getZonesForARegion(String region) {
        // clientConfig是EurekaClient的配置，region是要查询的区域
        // 这里主要看EndpointUtils的getZoneBasedDiscoveryUrlsFromRegion()方法
        Map<String, List<String>> zoneBasedDiscoveryUrlsFromRegion = EndpointUtils
                .getZoneBasedDiscoveryUrlsFromRegion(clientConfig, region);
        // 如果查询到的结果不为空，就返回
        if (null != zoneBasedDiscoveryUrlsFromRegion) {
            return zoneBasedDiscoveryUrlsFromRegion.keySet();
        }

        return Collections.emptySet();
    }
}
