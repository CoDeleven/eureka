/*
 * Copyright 2012 Netflix, Inc.
 *f
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

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.StatusChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that initializes information required for registration with
 * <tt>Eureka Server</tt> and to be discovered by other components.
 * 这个类保存了要注册到Eureka Server的数据，该信息可以被其他组件所发现。
 * 该对象是单例对象，一个Eureka实例只维护一个对象
 *
 * 上面说的数据主要来自EurekaInstanceConfig类，如果当前客户端是部署在AWS上的，那么就加载CloudInstanceConfig；
 * 如果当前客户端没有部署在AWS上的，就使用MyDataCenterInstanceConfig
 *
 * <p>
 * The information required for registration is provided by the user by passing
 * the configuration defined by the contract in {@link EurekaInstanceConfig}
 * }.AWS clients can either use or extend {@link CloudInstanceConfig
 * }. Other non-AWS clients can use or extend either
 * {@link MyDataCenterInstanceConfig} or very basic
 * {@link AbstractInstanceConfig}.
 * </p>
 *
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Singleton
public class ApplicationInfoManager {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationInfoManager.class);

    private static final InstanceStatusMapper NO_OP_MAPPER = new InstanceStatusMapper() {
        @Override
        public InstanceStatus map(InstanceStatus prev) {
            return prev;
        }
    };
    // 单例 应用实例信息管理器
    private static ApplicationInfoManager instance = new ApplicationInfoManager(null, null, null);
    // 状态变更监听器
    protected final Map<String, StatusChangeListener> listeners;
    // 实例状态映射器，输入InstanceStatus，返回匹配项
    private final InstanceStatusMapper instanceStatusMapper;
    // 实例数据，是数据！从EurekaInstanceConfig加载进来的
    private InstanceInfo instanceInfo;
    // 配置项
    private EurekaInstanceConfig config;

    /**
     * 可选参数，外部传入
     */
    public static class OptionalArgs {
        private InstanceStatusMapper instanceStatusMapper;

        @com.google.inject.Inject(optional = true)
        public void setInstanceStatusMapper(InstanceStatusMapper instanceStatusMapper) {
            this.instanceStatusMapper = instanceStatusMapper;
        }

        InstanceStatusMapper getInstanceStatusMapper() {
            return instanceStatusMapper == null ? NO_OP_MAPPER : instanceStatusMapper;
        }
    }

    /**
     * 该类应该被注入进来，不应该显示初始化；如果想显示初始化，请使用另外一个public构造器
     * public for DI use. This class should be in singleton scope so do not create explicitly.
     * Either use DI or create this explicitly using one of the other public constructors.
     */
    @Inject
    public ApplicationInfoManager(EurekaInstanceConfig config, InstanceInfo instanceInfo, OptionalArgs optionalArgs) {
        this.config = config;
        this.instanceInfo = instanceInfo;
        this.listeners = new ConcurrentHashMap<String, StatusChangeListener>();
        if (optionalArgs != null) {
            this.instanceStatusMapper = optionalArgs.getInstanceStatusMapper();
        } else {
            this.instanceStatusMapper = NO_OP_MAPPER;
        }

        // Hack to allow for getInstance() to use the DI'd ApplicationInfoManager
        instance = this;
    }

    public ApplicationInfoManager(EurekaInstanceConfig config, /* nullable */ OptionalArgs optionalArgs) {
        this(config, new EurekaConfigBasedInstanceInfoProvider(config).get(), optionalArgs);
    }

    public ApplicationInfoManager(EurekaInstanceConfig config, InstanceInfo instanceInfo) {
        this(config, instanceInfo, null);
    }

    /**
     * @deprecated 2016-09-19 prefer {@link #ApplicationInfoManager(EurekaInstanceConfig, com.netflix.appinfo.ApplicationInfoManager.OptionalArgs)}
     */
    @Deprecated
    public ApplicationInfoManager(EurekaInstanceConfig config) {
        this(config, (OptionalArgs) null);
    }

    /**
     * @deprecated please use DI instead
     */
    @Deprecated
    public static ApplicationInfoManager getInstance() {
        return instance;
    }

    /**
     * 初始化组件
     * @param config EurekaInstanceConfig 实例配置
     */
    public void initComponent(EurekaInstanceConfig config) {
        try {
            this.config = config;
            // 根据实例配置信息，获取InstanceInfo数据
            this.instanceInfo = new EurekaConfigBasedInstanceInfoProvider(config).get();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize ApplicationInfoManager", e);
        }
    }

    /**
     * Gets the information about this instance that is registered with eureka.
     *
     * 获取实例信息数据，实例信息数据是要注册到Eureka服务器上的
     *
     * @return information about this instance that is registered with eureka.
     */
    public InstanceInfo getInfo() {
        return instanceInfo;
    }

    public EurekaInstanceConfig getEurekaInstanceConfig() {
        return config;
    }

    /**
     * Register user-specific instance meta data. Application can send any other
     * additional meta data that need to be accessed for other reasons.The data
     * will be periodically sent to the eureka server.
     * 可以将用户指定的元数据信息放到实例信息中。应用可以发送任何 需要被其他应用访问的元数据。这些数据会定期被送到Eureka服务器上
     *
     * 注意通过这种方式保存的元数据信息可能并不会在初始化注册就被发送给Eureka服务器；
     * 如果想要一开始注册就发送，需要通过EurekaInstanceConfig.getMetadataMap()方法
     *
     * Please Note that metadata added via this method is not guaranteed to be submitted
     * to the eureka servers upon initial registration, and may be submitted as an update
     * at a subsequent time. If you want guaranteed metadata for initial registration,
     * please use the mechanism described in {@link EurekaInstanceConfig#getMetadataMap()}
     *
     * @param appMetadata application specific meta data.
     */
    public void registerAppMetadata(Map<String, String> appMetadata) {
        instanceInfo.registerRuntimeMetadata(appMetadata);
    }

    /**
     * 设置当前实例的状态。应用可以使用这个方法来决定是否 开始处理业务；设置状态的时候会发送状态变更的事件给所有已注册的监听器
     *
     * Set the status of this instance. Application can use this to indicate
     * whether it is ready to receive traffic. Setting the status here also notifies all registered listeners
     * of a status change event.
     *
     * @param status Status of the instance
     */
    public synchronized void setInstanceStatus(InstanceStatus status) {
        InstanceStatus next = instanceStatusMapper.map(status);
        if (next == null) {
            return;
        }

        InstanceStatus prev = instanceInfo.setStatus(next);
        if (prev != null) {
            for (StatusChangeListener listener : listeners.values()) {
                try {
                    listener.notify(new StatusChangeEvent(prev, next));
                } catch (Exception e) {
                    logger.warn("failed to notify listener: {}", listener.getId(), e);
                }
            }
        }
    }

    public void registerStatusChangeListener(StatusChangeListener listener) {
        listeners.put(listener.getId(), listener);
    }

    public void unregisterStatusChangeListener(String listenerId) {
        listeners.remove(listenerId);
    }

    /**
     * Refetches the hostname to check if it has changed. If it has, the entire
     * <code>DataCenterInfo</code> is refetched and passed on to the eureka
     * server on next heartbeat.
     *
     * see {@link InstanceInfo#getHostName()} for explanation on why the hostname is used as the default address
     */
    public void refreshDataCenterInfoIfRequired() {
        String existingAddress = instanceInfo.getHostName();

        String existingSpotInstanceAction = null;
        if (instanceInfo.getDataCenterInfo() instanceof AmazonInfo) {
            existingSpotInstanceAction = ((AmazonInfo) instanceInfo.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.spotInstanceAction);
        }

        String newAddress;
        if (config instanceof RefreshableInstanceConfig) {
            // Refresh data center info, and return up to date address
            newAddress = ((RefreshableInstanceConfig) config).resolveDefaultAddress(true);
        } else {
            newAddress = config.getHostName(true);
        }
        String newIp = config.getIpAddress();

        if (newAddress != null && !newAddress.equals(existingAddress)) {
            logger.warn("The address changed from : {} => {}", existingAddress, newAddress);
            updateInstanceInfo(newAddress, newIp);
        }

        if (config.getDataCenterInfo() instanceof AmazonInfo) {
            String newSpotInstanceAction = ((AmazonInfo) config.getDataCenterInfo()).get(AmazonInfo.MetaDataKey.spotInstanceAction);
            if (newSpotInstanceAction != null && !newSpotInstanceAction.equals(existingSpotInstanceAction)) {
                logger.info(String.format("The spot instance termination action changed from: %s => %s",
                        existingSpotInstanceAction,
                        newSpotInstanceAction));
                updateInstanceInfo(null , null );
            }
        }        
    }

    private void updateInstanceInfo(String newAddress, String newIp) {
        // :( in the legacy code here the builder is acting as a mutator.
        // This is hard to fix as this same instanceInfo instance is referenced elsewhere.
        // We will most likely re-write the client at sometime so not fixing for now.
        InstanceInfo.Builder builder = new InstanceInfo.Builder(instanceInfo);
        if (newAddress != null) {
            builder.setHostName(newAddress);
        }
        if (newIp != null) {
            builder.setIPAddr(newIp);
        }
        builder.setDataCenterInfo(config.getDataCenterInfo());
        instanceInfo.setIsDirty();
    }

    public void refreshLeaseInfoIfRequired() {
        LeaseInfo leaseInfo = instanceInfo.getLeaseInfo();
        if (leaseInfo == null) {
            return;
        }
        int currentLeaseDuration = config.getLeaseExpirationDurationInSeconds();
        int currentLeaseRenewal = config.getLeaseRenewalIntervalInSeconds();
        if (leaseInfo.getDurationInSecs() != currentLeaseDuration || leaseInfo.getRenewalIntervalInSecs() != currentLeaseRenewal) {
            LeaseInfo newLeaseInfo = LeaseInfo.Builder.newBuilder()
                    .setRenewalIntervalInSecs(currentLeaseRenewal)
                    .setDurationInSecs(currentLeaseDuration)
                    .build();
            instanceInfo.setLeaseInfo(newLeaseInfo);
            instanceInfo.setIsDirty();
        }
    }

    public static interface StatusChangeListener {
        String getId();

        void notify(StatusChangeEvent statusChangeEvent);
    }

    public static interface InstanceStatusMapper {

        /**
         * given a starting {@link com.netflix.appinfo.InstanceInfo.InstanceStatus}, apply a mapping to return
         * the follow up status, if applicable.
         *
         * @return the mapped instance status, or null if the mapping is not applicable.
         */
        InstanceStatus map(InstanceStatus prev);
    }

}
