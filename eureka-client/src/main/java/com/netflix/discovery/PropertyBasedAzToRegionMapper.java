package com.netflix.discovery;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 通过配置文件获取 可用空间和区域的映射关系
 * 要使用这个类 shouldUseDnsForFetchingServiceUrls 要返回false
 * 也就是配置项 NAMESPACE.shouldUseDns 要为false
 *
 * @author Nitesh Kant
 */
public class PropertyBasedAzToRegionMapper extends AbstractAzToRegionMapper {

    public PropertyBasedAzToRegionMapper(EurekaClientConfig clientConfig) {
        super(clientConfig);
    }

    @Override
    protected Set<String> getZonesForARegion(String region) {
        // 通过DefaultEurekaClientConfig的实现来看，配置项需要设置为 NAMESPACE.REGION.availabilityZones 来指定某个区域的可用空间
        // NAMESPACE 表示配置的命名空间，REGION 是外部传入的区域参数
        return new HashSet<String>(Arrays.asList(clientConfig.getAvailabilityZones(region)));
    }
}
