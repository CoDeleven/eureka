package com.netflix.discovery;

import javax.annotation.Nullable;
import java.util.Map;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.InstanceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例区域检查器，主要的功能：
 * 1. 根据实例信息InstanceInfo 查询 实例所在的区域（有限制）
 * 2. 查看 要查询的region 和 本机所在的区域是否一致
 *
 * @author Nitesh Kant
 */
public class InstanceRegionChecker {
    private static Logger logger = LoggerFactory.getLogger(InstanceRegionChecker.class);
    // 可用空间 和 区域的映射
    private final AzToRegionMapper azToRegionMapper;
    // 本机所在的区域
    private final String localRegion;

    InstanceRegionChecker(AzToRegionMapper azToRegionMapper, String localRegion) {
        this.azToRegionMapper = azToRegionMapper;
        this.localRegion = localRegion;
    }

    @Nullable
    public String getInstanceRegion(InstanceInfo instanceInfo) {
        // 如果不存在就只返回本机所在的区域
        if (instanceInfo.getDataCenterInfo() == null || instanceInfo.getDataCenterInfo().getName() == null) {
            logger.warn("Cannot get region for instance id:{}, app:{} as dataCenterInfo is null. Returning local:{} by default",
                    instanceInfo.getId(), instanceInfo.getAppName(), localRegion);

            return localRegion;
        }
        // 数据中心配置需要用AWS
        if (DataCenterInfo.Name.Amazon.equals(instanceInfo.getDataCenterInfo().getName())) {
            // 获取数据中心
            AmazonInfo amazonInfo = (AmazonInfo) instanceInfo.getDataCenterInfo();
            // 获取数据中心（服务器）元数据，元数据的详情进AmazonInfo里面看
            Map<String, String> metadata = amazonInfo.getMetadata();
            // 通过DataCenterInfo查询 键名为availability-zone的值 (具体怎么查的可以看AmazonInfo里看）
            // 简单来说，就是通过DataCenterInfo 查询该实例所在的可用空间
            String availabilityZone = metadata.get(AmazonInfo.MetaDataKey.availabilityZone.getName());
            // 如果查询结果不为null
            if (null != availabilityZone) {
                // 就用这个可用空间去查询所在的区域
                return azToRegionMapper.getRegionForAvailabilityZone(availabilityZone);
            }
        }

        return null;
    }

    /**
     * 判断 实例所在的区域 是否为 本机所在的区域
     *
     * @param instanceRegion
     * @return
     */
    public boolean isLocalRegion(@Nullable String instanceRegion) {
        return null == instanceRegion || instanceRegion.equals(localRegion); // no region == local
    }

    public String getLocalRegion() {
        return localRegion;
    }

    public AzToRegionMapper getAzToRegionMapper() {
        return azToRegionMapper;
    }
}
