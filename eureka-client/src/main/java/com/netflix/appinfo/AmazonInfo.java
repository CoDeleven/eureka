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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.netflix.discovery.converters.jackson.builder.StringInterningAmazonInfoBuilder;
import com.netflix.discovery.internal.util.AmazonInfoUtils;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An AWS specific {@link DataCenterInfo} implementation.
 * AWS服务器上的DataCenterInfo实现（特指AWS上！！）
 *
 * <p>
 * Gets AWS specific information for registration with eureka by making a HTTP
 * call to an AWS service as recommended by AWS.
 * </p>
 *
 * 对于AWS的服务器实例来说 可以发送HTTP给自己本机从而获取当前所在实例的一些配置信息
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@JsonDeserialize(using = StringInterningAmazonInfoBuilder.class)
public class AmazonInfo implements DataCenterInfo, UniqueIdentifier {
    // AWS官方指定的版本，latest
    private static final String AWS_API_VERSION = "latest";
    // AWS官方指定的 获取实例配置信息的 地址
    private static final String AWS_METADATA_URL = "http://169.254.169.254/" + AWS_API_VERSION + "/meta-data/";
    // Eureka需要用到的实例配置项
    public enum MetaDataKey {
        instanceId("instance-id"),  // always have this first as we use it as a fail fast mechanism
        amiId("ami-id"),
        instanceType("instance-type"),
        localIpv4("local-ipv4"),
        localHostname("local-hostname"),
        availabilityZone("availability-zone", "placement/"),
        publicHostname("public-hostname"),
        publicIpv4("public-ipv4"),
        spotTerminationTime("termination-time", "spot/"),
        spotInstanceAction("instance-action", "spot/"),
        mac("mac"),  // mac is declared above vpcId so will be found before vpcId (where it is needed)
        vpcId("vpc-id", "network/interfaces/macs/") {
            @Override
            public URL getURL(String prepend, String mac) throws MalformedURLException {
                return new URL(AWS_METADATA_URL + this.path + mac + "/" + this.name);
            }
        },
        accountId("accountId") {
            private Pattern pattern = Pattern.compile("\"accountId\"\\s?:\\s?\\\"([A-Za-z0-9]*)\\\"");

            @Override
            public URL getURL(String prepend, String append) throws MalformedURLException {
                return new URL("http://169.254.169.254/" + AWS_API_VERSION + "/dynamic/instance-identity/document");
            }

            // no need to use a json deserializer, do a custom regex parse
            @Override
            public String read(InputStream inputStream) throws IOException {
                BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                try {
                    String toReturn = null;
                    String inputLine;
                    while ((inputLine = br.readLine()) != null) {
                        Matcher matcher = pattern.matcher(inputLine);
                        if (toReturn == null && matcher.find()) {
                            toReturn = matcher.group(1);
                            // don't break here as we want to read the full buffer for a clean connection close
                        }
                    }

                    return toReturn;
                } finally {
                    br.close();
                }
            }
        };

        protected String name;
        protected String path;
        // 枚举类的构造器
        MetaDataKey(String name) {
            this(name, "");
        }
        // 枚举类的构造器
        MetaDataKey(String name, String path) {
            this.name = name;
            this.path = path;
        }

        public String getName() {
            return name;
        }

        // override to apply prepend and append
        public URL getURL(String prepend, String append) throws MalformedURLException {
            return new URL(AWS_METADATA_URL + path + name);
        }

        /**
         * 读取HTTP响应的body，然后按照需要解析，这个是默认实现
         * @param inputStream
         * @return
         * @throws IOException
         */
        public String read(InputStream inputStream) throws IOException {
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String toReturn;
            try {
                String line = br.readLine();
                toReturn = line;

                while (line != null) {  // need to read all the buffer for a clean connection close
                    line = br.readLine();
                }

                return toReturn;
            } finally {
                br.close();
            }
        }

        public String toString() {
            return getName();
        }
    }

    public static final class Builder {
        private static final Logger logger = LoggerFactory.getLogger(Builder.class);
        private static final int SLEEP_TIME_MS = 100;

        @XStreamOmitField
        private AmazonInfo result;

        @XStreamOmitField
        private AmazonInfoConfig config;

        private Builder() {
            result = new AmazonInfo();
        }

        public static Builder newBuilder() {
            return new Builder();
        }
        // 手动增加metadata信息
        public Builder addMetadata(MetaDataKey key, String value) {
            result.metadata.put(key.getName(), value);
            return this;
        }
        // 设置AmazonInfoConfig
        public Builder withAmazonInfoConfig(AmazonInfoConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Build the {@link InstanceInfo} information.
         *
         * @return AWS specific instance information.
         */
        public AmazonInfo build() {
            return result;
        }

        /**
         * Build the {@link AmazonInfo} automatically via HTTP calls to instance
         * metadata API.
         *
         * 构建AmazonInfo实例，构建的时候自动调用 获取当前实例元数据的HTTP接口
         *
         * @param namespace the namespace to look for configuration properties.
         * @return the instance information specific to AWS.
         */
        public AmazonInfo autoBuild(String namespace) {
            if (config == null) {
                config = new Archaius1AmazonInfoConfig(namespace);
            }
            // 遍历每一项要查询的配置项的key
            for (MetaDataKey key : MetaDataKey.values()) {
                // 每个配置项允许重试的次数
                int numOfRetries = config.getNumRetries();
                while (numOfRetries-- > 0) {
                    try {
                        String mac = null;
                        // 如果当前查询的配置项的key 是 vpcId，需要查询mac地址
                        if (key == MetaDataKey.vpcId) {
                            mac = result.metadata.get(MetaDataKey.mac.getName());  // mac should be read before vpcId due to declaration order
                        }
                        // 对于vpcId的查询，需要使用到mac地址；其他配置项的查询不需要，所以直接给null就行
                        URL url = key.getURL(null, mac);
                        // 调用工具读取指定配置项的值
                        String value = AmazonInfoUtils.readEc2MetadataUrl(key, url, config.getConnectTimeout(), config.getReadTimeout());
                        // 如果存在指就设置
                        if (value != null) {
                            result.metadata.put(key.getName(), value);
                        }

                        break;
                    } catch (Throwable e) {
                        if (config.shouldLogAmazonMetadataErrors()) {
                            logger.warn("Cannot get the value for the metadata key: {} Reason :", key, e);
                        }
                        // 如果发生异常就休息一会再重试
                        if (numOfRetries >= 0) {
                            try {
                                Thread.sleep(SLEEP_TIME_MS);
                            } catch (InterruptedException e1) {

                            }
                            continue;
                        }
                    }
                }
                /*
                 * 快速失败机制
                 * 简单来说就是，我的instanceId在多次获取后都获取不到，后面的配置项估摸着也会获取不到，所以我先提前结束了
                 */
                // 如果当前查询的key是instanceId
                if (key == MetaDataKey.instanceId
                        // 开启了快速失败机制
                        && config.shouldFailFastOnFirstLoad()
                        // 在一番努力查询后，我的配置结果集中仍然没有包含 instanceId 这个配置项，就直接结束
                        && !result.metadata.containsKey(MetaDataKey.instanceId.getName())) {

                    logger.warn("Skipping the rest of AmazonInfo init as we were not able to load instanceId after " +
                                    "the configured number of retries: {}, per fail fast configuration: {}",
                            config.getNumRetries(), config.shouldFailFastOnFirstLoad());
                    break;  // break out of loop and return whatever we have thus far
                }
            }
            return result;
        }
    }

    // AmazonInfo的属性，保存 配置项名称 => 配置项值 的映射关系
    private Map<String, String> metadata;

    public AmazonInfo() {
        this.metadata = new HashMap<String, String>();
    }

    /**
     * Constructor provided for deserialization framework. It is expected that {@link AmazonInfo} will be built
     * programmatically using {@link AmazonInfo.Builder}.
     *
     * @param name this value is ignored, as it is always set to "Amazon"
     */
    @JsonCreator
    public AmazonInfo(
            @JsonProperty("name") String name,
            @JsonProperty("metadata") HashMap<String, String> metadata) {
        this.metadata = metadata;
    }
    
    public AmazonInfo(
            @JsonProperty("name") String name,
            @JsonProperty("metadata") Map<String, String> metadata) {
        this.metadata = metadata;
    }    

    @Override
    public Name getName() {
        return Name.Amazon;
    }

    /**
     * Get the metadata information specific to AWS.
     *
     * @return the map of AWS metadata as specified by {@link MetaDataKey}.
     */
    @JsonProperty("metadata")
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Set AWS metadata.
     *
     * @param metadataMap
     *            the map containing AWS metadata.
     */
    public void setMetadata(Map<String, String> metadataMap) {
        this.metadata = metadataMap;
    }

    /**
     * Gets the AWS metadata specified in {@link MetaDataKey}.
     *
     * @param key
     *            the metadata key.
     * @return String returning the value.
     */
    public String get(MetaDataKey key) {
        return metadata.get(key.getName());
    }

    @Override
    @JsonIgnore
    public String getId() {
        return get(MetaDataKey.instanceId);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AmazonInfo)) return false;

        AmazonInfo that = (AmazonInfo) o;

        if (metadata != null ? !metadata.equals(that.metadata) : that.metadata != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return metadata != null ? metadata.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "AmazonInfo{" +
                "metadata=" + metadata +
                '}';
    }
}
