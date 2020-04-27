/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.netflix.appinfo;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.discovery.CommonConstants;
import com.netflix.discovery.internal.util.Archaius1Utils;
import org.apache.commons.configuration.Configuration;

import static com.netflix.appinfo.PropertyBasedInstanceConfigConstants.*;

/**
 * A properties based {@link InstanceInfo} configuration.
 *
 * <p>
 * The information required for registration with eureka server is provided in a
 * configuration file.The configuration file is searched for in the classpath
 * with the name specified by the property <em>eureka.client.props</em> and with
 * the suffix <em>.properties</em>. If the property is not specified,
 * <em>eureka-client.properties</em> is assumed as the default.The properties
 * that are looked up uses the <em>namespace</em> passed on to this class.
 * </p>
 *
 * <p>
 * If the <em>eureka.environment</em> property is specified, additionally
 * <em>eureka-client-<eureka.environment>.properties</em> is loaded in addition
 * to <em>eureka-client.properties</em>.
 * </p>
 *
 *
 *
 * @author Karthik Ranganathan
 *
 */
public abstract class PropertiesInstanceConfig extends AbstractInstanceConfig implements EurekaInstanceConfig {
    // 名称空间
    protected final String namespace;
    // Archaius工具，可以用来动态加载配置
    protected final DynamicPropertyFactory configInstance;
    // Eureka实例的应用分组名称的默认值
    private String appGrpNameFromEnv;

    public PropertiesInstanceConfig() {
        // 使用默认名称空间，即eureka
        this(CommonConstants.DEFAULT_CONFIG_NAMESPACE);
    }

    /**
     * 默认情况下使用，使用自定义的DataCenterInfo（MyOwn）；AWS情况下，使用AmazonInfo（Amazon）
     * TODO 目前接触不太到DataCenterInfo，等以后有新体会了再来补上作用
     *
     * @param namespace
     */
    public PropertiesInstanceConfig(String namespace) {
        this(namespace, new DataCenterInfo() {
            @Override
            public Name getName() {
                return Name.MyOwn;
            }
        });
    }

    public PropertiesInstanceConfig(String namespace, DataCenterInfo info) {
        super(info);
        // 处理namespace，要求namespace后面有个"."
        this.namespace = namespace.endsWith(".")
                ? namespace
                : namespace + ".";
        // 和Archaius相关的配置获取，就是获取配置的 FALLBACK_APP_GROUP_KEY 变量，如果不存在就是用 UNKNOWN_APPLICATION 作为默认值
        // TODO Archaius不太熟，这一块不知道怎么说
        appGrpNameFromEnv = ConfigurationManager.getConfigInstance()
                .getString(FALLBACK_APP_GROUP_KEY, Values.UNKNOWN_APPLICATION);
        // 加载 eureka-client.properties 配置文件
        // TODO 熟悉Archaius后再补充吧
        this.configInstance = Archaius1Utils.initConfig(CommonConstants.CONFIG_FILE_NAME);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#isInstanceEnabledOnit()
     */
    @Override
    public boolean isInstanceEnabledOnit() {
        return configInstance.getBooleanProperty(namespace + TRAFFIC_ENABLED_ON_INIT_KEY,
                super.isInstanceEnabledOnit()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#getNonSecurePort()
     */
    @Override
    public int getNonSecurePort() {
        return configInstance.getIntProperty(namespace + PORT_KEY, super.getNonSecurePort()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#getSecurePort()
     */
    @Override
    public int getSecurePort() {
        return configInstance.getIntProperty(namespace + SECURE_PORT_KEY, super.getSecurePort()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#isNonSecurePortEnabled()
     */
    @Override
    public boolean isNonSecurePortEnabled() {
        return configInstance.getBooleanProperty(namespace + PORT_ENABLED_KEY, super.isNonSecurePortEnabled()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#getSecurePortEnabled()
     */
    @Override
    public boolean getSecurePortEnabled() {
        return configInstance.getBooleanProperty(namespace + SECURE_PORT_ENABLED_KEY,
                super.getSecurePortEnabled()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.appinfo.AbstractInstanceConfig#getLeaseRenewalIntervalInSeconds
     * ()
     */
    @Override
    public int getLeaseRenewalIntervalInSeconds() {
        return configInstance.getIntProperty(namespace + LEASE_RENEWAL_INTERVAL_KEY,
                super.getLeaseRenewalIntervalInSeconds()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#
     * getLeaseExpirationDurationInSeconds()
     */
    @Override
    public int getLeaseExpirationDurationInSeconds() {
        return configInstance.getIntProperty(namespace + LEASE_EXPIRATION_DURATION_KEY,
                super.getLeaseExpirationDurationInSeconds()).get();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#getVirtualHostName()
     */
    @Override
    public String getVirtualHostName() {
        if (this.isNonSecurePortEnabled()) {
            return configInstance.getStringProperty(namespace + VIRTUAL_HOSTNAME_KEY,
                    super.getVirtualHostName()).get();
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.netflix.appinfo.AbstractInstanceConfig#getSecureVirtualHostName()
     */
    @Override
    public String getSecureVirtualHostName() {
        if (this.getSecurePortEnabled()) {
            return configInstance.getStringProperty(namespace + SECURE_VIRTUAL_HOSTNAME_KEY,
                    super.getSecureVirtualHostName()).get();
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.appinfo.AbstractInstanceConfig#getASGName()
     */
    @Override
    public String getASGName() {
        return configInstance.getStringProperty(namespace + ASG_NAME_KEY, super.getASGName()).get();
    }

    /**
     * Gets the metadata map associated with the instance. The properties that
     * will be looked up for this will be <code>namespace + ".metadata"</code>.
     *
     * <p>
     * For instance, if the given namespace is <code>eureka.appinfo</code>, the
     * metadata keys are searched under the namespace
     * <code>eureka.appinfo.metadata</code>.
     * </p>
     *
     * 获取metadata集合，如果外部传入的namespace为eureka.appinfo，那么配置文件中就会
     * 加载eureka
     */
    @Override
    public Map<String, String> getMetadataMap() {
        String metadataNamespace = namespace + INSTANCE_METADATA_PREFIX + ".";
        Map<String, String> metadataMap = new LinkedHashMap<String, String>();
        // 加载配置
        Configuration config = (Configuration) configInstance.getBackingConfigurationSource();
        // 获取完整符合要求的metadataNamespace
        String subsetPrefix = metadataNamespace.charAt(metadataNamespace.length() - 1) == '.'
                ? metadataNamespace.substring(0, metadataNamespace.length() - 1)
                : metadataNamespace;
        // config.subset() 用来获取指定前缀的配置项；org.apache.commons.configuration.Configuration.getKeys()获取键名
        // 遍历
        for (Iterator<String> iter = config.subset(subsetPrefix).getKeys(); iter.hasNext(); ) {
            // 获取下一个配置项的key
            String key = iter.next();
            // 获取指定键名的值
            String value = config.getString(subsetPrefix + "." + key);
            metadataMap.put(key, value);
        }
        return metadataMap;
    }

    @Override
    public String getInstanceId() {
        String result = configInstance.getStringProperty(namespace + INSTANCE_ID_KEY, null).get();
        return result == null ? null : result.trim();
    }

    @Override
    public String getAppname() {
        return configInstance.getStringProperty(namespace + APP_NAME_KEY, Values.UNKNOWN_APPLICATION).get().trim();
    }

    @Override
    public String getAppGroupName() {
        return configInstance.getStringProperty(namespace + APP_GROUP_KEY, appGrpNameFromEnv).get().trim();
    }

    public String getIpAddress() {
        return super.getIpAddress();
    }


    @Override
    public String getStatusPageUrlPath() {
        return configInstance.getStringProperty(namespace + STATUS_PAGE_URL_PATH_KEY,
                Values.DEFAULT_STATUSPAGE_URLPATH).get();
    }

    @Override
    public String getStatusPageUrl() {
        return configInstance.getStringProperty(namespace + STATUS_PAGE_URL_KEY, null)
                .get();
    }


    @Override
    public String getHomePageUrlPath() {
        return configInstance.getStringProperty(namespace + HOME_PAGE_URL_PATH_KEY,
                Values.DEFAULT_HOMEPAGE_URLPATH).get();
    }

    @Override
    public String getHomePageUrl() {
        return configInstance.getStringProperty(namespace + HOME_PAGE_URL_KEY, null)
                .get();
    }

    @Override
    public String getHealthCheckUrlPath() {
        return configInstance.getStringProperty(namespace + HEALTHCHECK_URL_PATH_KEY,
                Values.DEFAULT_HEALTHCHECK_URLPATH).get();
    }

    @Override
    public String getHealthCheckUrl() {
        return configInstance.getStringProperty(namespace + HEALTHCHECK_URL_KEY, null)
                .get();
    }

    @Override
    public String getSecureHealthCheckUrl() {
        return configInstance.getStringProperty(namespace + SECURE_HEALTHCHECK_URL_KEY,
                null).get();
    }

    @Override
    public String[] getDefaultAddressResolutionOrder() {
        String result = configInstance.getStringProperty(namespace + DEFAULT_ADDRESS_RESOLUTION_ORDER_KEY, null).get();
        return result == null ? new String[0] : result.split(",");
    }

    /**
     * Indicates if the public ipv4 address of the instance should be advertised.
     * @return true if the public ipv4 address of the instance should be advertised, false otherwise .
     */
    public boolean shouldBroadcastPublicIpv4Addr() {
        return configInstance.getBooleanProperty(namespace + BROADCAST_PUBLIC_IPV4_ADDR_KEY, super.shouldBroadcastPublicIpv4Addr()).get();
    }

    @Override
    public String getNamespace() {
        return this.namespace;
    }
}
