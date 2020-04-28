package com.netflix.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.Supplier;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.discovery.DefaultEurekaClientConfig.DEFAULT_ZONE;

/**
 * AzToRegionMapper接口的抽象实现类
 *
 * @author Nitesh Kant
 */
public abstract class AbstractAzToRegionMapper implements AzToRegionMapper {

    private static final Logger logger = LoggerFactory.getLogger(InstanceRegionChecker.class);
    private static final String[] EMPTY_STR_ARRAY = new String[0];

    protected final EurekaClientConfig clientConfig;

    /**
     * 如果配置的远程区域没有可用的空间（也就是获取不到可用空间和区域的映射），就使用defaultRegionVsAzMap作为默认的映射关系。
     * 如果有的话，就不会使用这个属性。
     */
    private final Multimap<String, String> defaultRegionVsAzMap =
            Multimaps.newListMultimap(new HashMap<String, Collection<String>>(), new Supplier<List<String>>() {
                @Override
                public List<String> get() {
                    return new ArrayList<String>();
                }
            });
    // 可用空间 和 区域的映射关系
    private final Map<String, String> availabilityZoneVsRegion = new ConcurrentHashMap<String, String>();
    // 需要拉取注册信息的区域集合
    private String[] regionsToFetch;

    protected AbstractAzToRegionMapper(EurekaClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        // 生成默认的 可用空间和区域的映射关系
        populateDefaultAZToRegionMap();
    }

    @Override
    public synchronized void setRegionsToFetch(String[] regionsToFetch) {
        if (null != regionsToFetch) {
            this.regionsToFetch = regionsToFetch;
            logger.info("Fetching availability zone to region mapping for regions {}", (Object) regionsToFetch);
            // 清空本地的映射关系
            availabilityZoneVsRegion.clear();
            // 遍历要获取可用空间的  区域
            for (String remoteRegion : regionsToFetch) {
                // 获取该区域的可用空间，getZonesForARegion是一个抽象方法，由子类实现，官方提供了两种：
                // 一种是根据配置文件来获取（预先定义好），一种是根据DNS来获取
                Set<String> availabilityZones = getZonesForARegion(remoteRegion);
                // 如果没有获取到可用空间 或 可用空间里只有defaultZone
                if (null == availabilityZones
                        || (availabilityZones.size() == 1 && availabilityZones.contains(DEFAULT_ZONE))
                        || availabilityZones.isEmpty()) {
                    logger.info("No availability zone information available for remote region: {}"
                            + ". Now checking in the default mapping.", remoteRegion);
                    // 如果默认的关系中包含当前这个远程区域，就使用默认定义好的空间集合
                    if (defaultRegionVsAzMap.containsKey(remoteRegion)) {
                        Collection<String> defaultAvailabilityZones = defaultRegionVsAzMap.get(remoteRegion);
                        for (String defaultAvailabilityZone : defaultAvailabilityZones) {
                            availabilityZoneVsRegion.put(defaultAvailabilityZone, remoteRegion);
                        }
                    } else {
                        // 否则就炸裂
                        String msg = "No availability zone information available for remote region: " + remoteRegion
                                + ". This is required if registry information for this region is configured to be "
                                + "fetched.";
                        logger.error(msg);
                        throw new RuntimeException(msg);
                    }
                } else {
                    // 如果获取到可用的空间，就保存起来
                    for (String availabilityZone : availabilityZones) {
                        availabilityZoneVsRegion.put(availabilityZone, remoteRegion);
                    }
                }
            }

            logger.info("Availability zone to region mapping for all remote regions: {}", availabilityZoneVsRegion);
        } else {
            logger.info("Regions to fetch is null. Erasing older mapping if any.");
            availabilityZoneVsRegion.clear();
            this.regionsToFetch = EMPTY_STR_ARRAY;
        }
    }

    /**
     * Returns all the zones in the provided region.
     * @param region the region whose zones you want
     * @return a set of zones
     */
    protected abstract Set<String> getZonesForARegion(String region);

    /**
     * 简单的根据key查value
     * @param availabilityZone Availability zone for which the region is to be retrieved.
     *
     * @return
     */
    @Override
    public String getRegionForAvailabilityZone(String availabilityZone) {
        // 根据zone查询region
        String region = availabilityZoneVsRegion.get(availabilityZone);
        // 如果region不存在，就“模糊查询”zone
        if (null == region) {
            return parseAzToGetRegion(availabilityZone);
        }
        return region;
    }

    @Override
    public synchronized void refreshMapping() {
        logger.info("Refreshing availability zone to region mappings.");
        setRegionsToFetch(regionsToFetch);
    }

    /**
     * Tries to determine what region we're in, based on the provided availability zone.
     *
     * 根据可用空间名字 获取 所在的区域
     *
     * @param availabilityZone the availability zone to inspect
     * @return the region, if available; null otherwise
     */
    protected String parseAzToGetRegion(String availabilityZone) {
        // Here we see that whether the availability zone is following a pattern like <region><single letter>
        // If it is then we take ignore the last letter and check if the remaining part is actually a known remote
        // region. If yes, then we return that region, else null which means local region.
        if (!availabilityZone.isEmpty()) {
            String possibleRegion = availabilityZone.substring(0, availabilityZone.length() - 1);
            if (availabilityZoneVsRegion.containsValue(possibleRegion)) {
                return possibleRegion;
            }
        }
        return null;
    }

    /**
     * 默认提供的可用空间和区域的映射关系
     */
    private void populateDefaultAZToRegionMap() {
        defaultRegionVsAzMap.put("us-east-1", "us-east-1a");
        defaultRegionVsAzMap.put("us-east-1", "us-east-1c");
        defaultRegionVsAzMap.put("us-east-1", "us-east-1d");
        defaultRegionVsAzMap.put("us-east-1", "us-east-1e");

        defaultRegionVsAzMap.put("us-west-1", "us-west-1a");
        defaultRegionVsAzMap.put("us-west-1", "us-west-1c");

        defaultRegionVsAzMap.put("us-west-2", "us-west-2a");
        defaultRegionVsAzMap.put("us-west-2", "us-west-2b");
        defaultRegionVsAzMap.put("us-west-2", "us-west-2c");

        defaultRegionVsAzMap.put("eu-west-1", "eu-west-1a");
        defaultRegionVsAzMap.put("eu-west-1", "eu-west-1b");
        defaultRegionVsAzMap.put("eu-west-1", "eu-west-1c");
    }
}
