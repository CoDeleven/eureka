package com.netflix.discovery;

import com.netflix.appinfo.ApplicationInfoManager;

/**
 * A handler that can be registered with an {@link EurekaClient} at creation time to execute
 * pre registration logic. The pre registration logic need to be synchronous to be guaranteed
 * to execute before registration.
 *
 * 会在注册之前调用该类，这个方法需要保证
 *
 */
public interface PreRegistrationHandler {
    void beforeRegistration();
}
