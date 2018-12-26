/*
 * Copyright 2016 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.navercorp.pinpoint.bootstrap;

import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;
import com.navercorp.pinpoint.common.util.IdValidateUtils;


import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Properties;

/**
 * @author Woonduk Kang(emeroad)
 */
public class IdValidator {

    private final BootLogger logger = BootLogger.getLogger(IdValidator.class.getName());

    private final Properties property;
    private static final int MAX_ID_LENGTH = 24;

    private static final int MAX_HOSTNAME_LENGTH = 15;

    public IdValidator() {
        this(System.getProperties());
    }

    public IdValidator(Properties property) {
        if (property == null) {
            throw new NullPointerException("property must not be null");
        }
        this.property = property;
    }

    private String getValidId(String propertyName, int maxSize) {
        logger.info("check -D" + propertyName);
        String value = property.getProperty(propertyName);
        if (value == null) {
            logger.warn("-D" + propertyName + " is null. value:null");
            return null;
        }
        // blanks not permitted around value
        value = value.trim();
        if (value.isEmpty()) {
            logger.warn("-D" + propertyName + " is empty. value:''");
            return null;
        }

        if (!IdValidateUtils.validateId(value, maxSize)) {
            logger.warn("invalid Id. " + propertyName + " can only contain [a-zA-Z0-9], '.', '-', '_'. maxLength:" + maxSize + " value:" + value);
            return null;
        }

        if (logger.isInfoEnabled()) {
            logger.info("check success. -D" + propertyName + ":" + value + " length:" + IdValidateUtils.getLength(value));
        }
        return value;
    }


    private String getHostName() {
        if (System.getenv("COMPUTERNAME") != null) {
            return System.getenv("COMPUTERNAME");
        } else {
            try {
                return (InetAddress.getLocalHost()).getHostName();
            } catch (UnknownHostException uhe) {
                String host = uhe.getMessage(); // host = "hostname: hostname"
                if (host != null) {
                    int colon = host.indexOf(':');
                    if (colon > 0) {
                        return host.substring(0, colon);
                    }
                }
                return "UnknownHost";
            }
        }
    }


    public String getApplicationName(ProfilerConfig profilerConfig) {
        String applicationName = this.getValidId("pinpoint.applicationName", MAX_ID_LENGTH);
        if (applicationName == null) {
            applicationName = profilerConfig.getApplicationName();

            if (applicationName.length() > MAX_ID_LENGTH) {
                applicationName = applicationName.substring(0, MAX_ID_LENGTH);
            }
            property.setProperty("pinpoint.applicationName", applicationName);
            logger.warn("-Dpinpoint.applicationName" + " not set, use applicationName: " + applicationName);

        }
        return applicationName;
    }

    public String getAgentId(String applicationName) {
        String value = this.getValidId("pinpoint.agentId", MAX_ID_LENGTH);

        if (value != null && !value.trim().isEmpty()) {
            return value;

        } else {//vm参数为空时，取host由后往前15位+appName由前往后9位
            value = getHostName();

            if (value.length() > MAX_HOSTNAME_LENGTH) {
                value = value.substring(0, value.indexOf("."));
                StringBuilder sb = new StringBuilder();
                for (char c : value.toCharArray()) {
                    if ((48 <= c && c <= 57) || ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z'))
                        sb.append(c);
                }
                value = sb.toString();
                if (value.length() > MAX_HOSTNAME_LENGTH)
                    value.substring(value.length() - MAX_HOSTNAME_LENGTH);
            }

            value = value + applicationName.substring(0, Math.min(applicationName.length(), MAX_ID_LENGTH - MAX_HOSTNAME_LENGTH));

            property.setProperty("pinpoint.agentId", value);
            logger.warn("-Dpinpoint.agentId" + " not set, use hostname: " + value);

            if (!IdValidateUtils.validateId(value, MAX_ID_LENGTH)) {
                logger.warn("invalid Id. -Dpinpoint.agentId can only contain [a-zA-Z0-9], '.', '-', '_'. maxLength:" + MAX_ID_LENGTH + " value:" + value);
                return null;
            }
            return value;
        }
    }

}
