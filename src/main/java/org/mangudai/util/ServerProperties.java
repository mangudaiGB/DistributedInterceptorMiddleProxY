/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Created by neo on 23/03/17.
 */
public enum ServerProperties {
    INSTANCE;

    private Properties properties;
    private Logger LOGGER = LoggerFactory.getLogger(ServerProperties.class);

    ServerProperties() {
        properties = new Properties();
        try {
            properties.load(this.getClass().getClassLoader().getResourceAsStream("filter.properties"));
        } catch (Exception ex) {
            LOGGER.error("Error reading properties file", ex);
        }
    }

    public String getProperty(String name) {
        String value = properties.getProperty(name);
        return value == null ? "" : value;
    }

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }
}
