/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.junit;

import org.gradle.api.Action;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestClassRunInfo;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.internal.actor.Actor;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public abstract class AbstractJUnitTestClassProcessor<T extends AbstractJUnitSpec> implements TestClassProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractJUnitTestClassProcessor.class);

    protected final T spec;
    protected final IdGenerator<?> idGenerator;
    protected final Clock clock;
    private final ActorFactory actorFactory;
    private final List<TestClassRunInfo> testClasses;
    private final boolean reorderTests;
    private Actor resultProcessorActor;
    private Action<String> executor;

    public AbstractJUnitTestClassProcessor(T spec, IdGenerator<?> idGenerator, ActorFactory actorFactory, Clock clock, boolean reorderTests) {
        this.idGenerator = idGenerator;
        this.spec = spec;
        this.actorFactory = actorFactory;
        this.clock = clock;
        this.testClasses = new ArrayList<TestClassRunInfo>();
        this.reorderTests = reorderTests;
    }

    @Override
    public void startProcessing(TestResultProcessor resultProcessor) {
        TestResultProcessor resultProcessorChain = createResultProcessorChain(resultProcessor);
        // Wrap the result processor chain up in a blocking actor, to make the whole thing thread-safe
        resultProcessorActor = actorFactory.createBlockingActor(resultProcessorChain);
        executor = createTestExecutor(resultProcessorActor);
    }

    protected abstract TestResultProcessor createResultProcessorChain(TestResultProcessor resultProcessor);

    protected abstract Action<String> createTestExecutor(Actor resultProcessorActor);

    @Override
    public void processTestClass(TestClassRunInfo testClass) {
        if (reorderTests) {
            testClasses.add(testClass);
        } else {
            LOGGER.debug("Executing test class {}", testClass.getTestClassName());
            executor.execute(testClass.getTestClassName());
        }
    }

    @Override
    public void stop() {
        if (reorderTests) {
            for (TestClassRunInfo testClass : randomizeTestClasses(testClasses)) {
                LOGGER.debug("Executing test class {}", testClass.getTestClassName());
                executor.execute(testClass.getTestClassName());
            }
        }
        resultProcessorActor.stop();
    }

    private List<TestClassRunInfo> randomizeTestClasses(List<TestClassRunInfo> classSelectors) {
        List<TestClassRunInfo> sortedClassSelector = new ArrayList<TestClassRunInfo>(classSelectors);
        Collections.sort(sortedClassSelector, new Comparator<TestClassRunInfo>() {
            @Override
            public int compare(TestClassRunInfo o1, TestClassRunInfo o2) {
                return o1.getTestClassName().compareTo(o2.getTestClassName());
            }
        });

        long seed = getEnvironmentSeed();
        LOGGER.warn("Reordering tests with seed = {}", seed);
        // I tried using SecureRandom here but it was not deterministic? Not sure why!
        Random random = new Random(seed);
        Collections.shuffle(sortedClassSelector, random);

        return sortedClassSelector;
    }

    private long getEnvironmentSeed() {
        String junitSeed = System.getenv("JUNIT_SEED");
        if (junitSeed != null) {
            LOGGER.warn("Found seed {} from variable JUNIT_SEED", junitSeed);
            try {
                return Long.parseLong(junitSeed);
            } catch (NumberFormatException e) {
                LOGGER.error("Could not parse JUNIT_SEED environment variable '" + junitSeed + "' as number", e);
            }
        }
        LOGGER.warn("Generating new seed");
        return generateSeed();
    }

    private long generateSeed() {
        return ByteBuffer.wrap(new SecureRandom().generateSeed(8)).getLong();
    }

    @Override
    public void stopNow() {
        throw new UnsupportedOperationException("stopNow() should not be invoked on remote worker TestClassProcessor");
    }
}
