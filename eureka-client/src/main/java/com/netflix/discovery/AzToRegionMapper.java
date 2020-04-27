package com.netflix.discovery;

/**
 * An interface that contains a contract of mapping availability zone to region mapping. An implementation will always
 * know before hand which zone to region mapping will be queried from the mapper, this will aid caching of this
 * information before hand.
 * 一个实现将始终在事前知道将从映射器查询哪个区域到区域的映射，这将有助于在事前缓存此信息。
 *
 * 该接口主要提供了 可用可用空间(zone) 和 区域(region)的映射 的关联操作。
 * 为什么要有这个接口呢？因为缓存信息的时候可能需要查询 可用空间（zone） 和 区域的映射
 *
 * @author Nitesh Kant
 */
public interface AzToRegionMapper {

    /**
     * Returns the region for the passed availability zone.
     *
     * 根据可用的空间（zone）获取该空间所在的区域
     *
     * @param availabilityZone Availability zone for which the region is to be retrieved.
     *
     * @return The region for the passed zone.
     */
    String getRegionForAvailabilityZone(String availabilityZone);

    /**
     * Update the regions that this mapper knows about.
     *
     * 设置需要拉取的区域
     *
     * @param regionsToFetch Regions to fetch. This should be the super set of all regions that this mapper should know.
     */
    void setRegionsToFetch(String[] regionsToFetch);

    /**
     * Updates the mappings it has if they depend on an external source.
     *
     * 刷新映射
     *
     */
    void refreshMapping();
}
