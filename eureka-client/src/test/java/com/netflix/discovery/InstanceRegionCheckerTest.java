package com.netflix.discovery;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.ConfigurationManager;
import com.netflix.discovery.endpoint.EndpointUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * @author Nitesh Kant
 */
public class InstanceRegionCheckerTest {

    @Test
    public void testDefaults() throws Exception {
        PropertyBasedAzToRegionMapper azToRegionMapper = new PropertyBasedAzToRegionMapper(
                new DefaultEurekaClientConfig());
        InstanceRegionChecker checker = new InstanceRegionChecker(azToRegionMapper, "us-east-1");
        azToRegionMapper.setRegionsToFetch(new String[]{"us-east-1"});
        AmazonInfo dcInfo = AmazonInfo.Builder.newBuilder().addMetadata(AmazonInfo.MetaDataKey.availabilityZone,
                "us-east-1c").build();
        InstanceInfo instanceInfo = InstanceInfo.Builder.newBuilder().setAppName("app").setDataCenterInfo(dcInfo).build();
        String instanceRegion = checker.getInstanceRegion(instanceInfo);

        Assert.assertEquals("Invalid instance region.", "us-east-1", instanceRegion);
    }

    /**
     *
     * 用来看看DnsBasedAzToRegionMapper到底在干什么贵
     * 我奉献出我的域名给大家爽爽，目前就开放了txt.region1.codeleven.cn
     * 所以区域要查 region1，域名要设置codeleven.cn
     * 我的记录值里保存了这些：
     * zone1.codeleven.cn zone2.codeleven.cn zone1.nice.codeleven.cn zone1.good.codeleven.cn
     *
     * @author codeleven
     */
    @Test
    public void testDnsBasedAzToRegionMapper(){
        // 设置域名~
        ConfigurationManager.getConfigInstance().setProperty("eureka.eurekaServer.domainName", "codeleven.cn");
        // 查询regioin1
        Map<String, List<String>> region1 = EndpointUtils.getZoneBasedDiscoveryUrlsFromRegion(new DefaultEurekaClientConfig(),
                "region1");
        System.out.println("region1的区域中有这些可用空间>>>>>\n" + region1);
        System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
    }

    @Test
    public void testDefaultOverride() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("eureka.us-east-1.availabilityZones", "abc,def");
        PropertyBasedAzToRegionMapper azToRegionMapper = new PropertyBasedAzToRegionMapper(new DefaultEurekaClientConfig());
        InstanceRegionChecker checker = new InstanceRegionChecker(azToRegionMapper, "us-east-1");
        azToRegionMapper.setRegionsToFetch(new String[]{"us-east-1"});
        AmazonInfo dcInfo = AmazonInfo.Builder.newBuilder().addMetadata(AmazonInfo.MetaDataKey.availabilityZone,
                "def").build();
        InstanceInfo instanceInfo = InstanceInfo.Builder.newBuilder().setAppName("app").setDataCenterInfo(
                dcInfo).build();
        String instanceRegion = checker.getInstanceRegion(instanceInfo);

        Assert.assertEquals("Invalid instance region.", "us-east-1", instanceRegion);
    }

    @Test
    public void testInstanceWithNoAZ() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("eureka.us-east-1.availabilityZones", "abc,def");
        PropertyBasedAzToRegionMapper azToRegionMapper = new PropertyBasedAzToRegionMapper(new DefaultEurekaClientConfig());
        InstanceRegionChecker checker = new InstanceRegionChecker(azToRegionMapper, "us-east-1");
        azToRegionMapper.setRegionsToFetch(new String[]{"us-east-1"});
        AmazonInfo dcInfo = AmazonInfo.Builder.newBuilder().addMetadata(AmazonInfo.MetaDataKey.availabilityZone,
                "").build();
        InstanceInfo instanceInfo = InstanceInfo.Builder.newBuilder().setAppName("app").setDataCenterInfo(
                dcInfo).build();
        String instanceRegion = checker.getInstanceRegion(instanceInfo);

        Assert.assertNull("Invalid instance region.", instanceRegion);
    }

    @Test
    public void testNotMappedAZ() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("eureka.us-east-1.availabilityZones", "abc,def");
        PropertyBasedAzToRegionMapper azToRegionMapper = new PropertyBasedAzToRegionMapper(new DefaultEurekaClientConfig());
        InstanceRegionChecker checker = new InstanceRegionChecker(azToRegionMapper, "us-east-1");
        azToRegionMapper.setRegionsToFetch(new String[]{"us-east-1"});
        AmazonInfo dcInfo = AmazonInfo.Builder.newBuilder().addMetadata(AmazonInfo.MetaDataKey.availabilityZone,
                "us-east-1x").build();
        InstanceInfo instanceInfo = InstanceInfo.Builder.newBuilder().setAppName("abc").setDataCenterInfo(dcInfo).build();
        String instanceRegion = checker.getInstanceRegion(instanceInfo);

        Assert.assertEquals("Invalid instance region.", "us-east-1", instanceRegion);
    }

    @Test
    public void testNotMappedAZNotFollowingFormat() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("eureka.us-east-1.availabilityZones", "abc,def");
        PropertyBasedAzToRegionMapper azToRegionMapper = new PropertyBasedAzToRegionMapper(new DefaultEurekaClientConfig());
        InstanceRegionChecker checker = new InstanceRegionChecker(azToRegionMapper, "us-east-1");
        azToRegionMapper.setRegionsToFetch(new String[]{"us-east-1"});
        AmazonInfo dcInfo = AmazonInfo.Builder.newBuilder().addMetadata(AmazonInfo.MetaDataKey.availabilityZone,
                "us-east-x").build();
        InstanceInfo instanceInfo = InstanceInfo.Builder.newBuilder().setAppName("abc").setDataCenterInfo(dcInfo).build();
        String instanceRegion = checker.getInstanceRegion(instanceInfo);

        Assert.assertNull("Invalid instance region.", instanceRegion);
    }
}
