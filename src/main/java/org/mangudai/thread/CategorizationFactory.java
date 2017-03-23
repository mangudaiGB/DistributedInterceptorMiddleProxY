/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.thread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by neo on 21/03/17.
 */
public class CategorizationFactory implements ThreadFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(CategorizationFactory.class);
    private final String name;
    private final String category;
    private final int uniqueServerGroupId;
    private AtomicInteger threadCount = new AtomicInteger(0);

    public CategorizationFactory(String name, String category, int uniqueServerGroupId) {
        this.category = category;
        this.name = name;
        this.uniqueServerGroupId = uniqueServerGroupId;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, name + "-" + uniqueServerGroupId + "-" + category + "-" + threadCount.getAndIncrement());
        thread.setUncaughtExceptionHandler((Thread t, Throwable e) -> LOGGER.error("Uncaught throwable error in thread: {}", t.getName(), e));
        return thread;
    }
}
