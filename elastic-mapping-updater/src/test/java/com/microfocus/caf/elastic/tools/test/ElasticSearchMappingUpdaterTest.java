/*
 * Copyright 2015-2020 Micro Focus or one of its affiliates.
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
package com.microfocus.caf.elastic.tools.test;

import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microfocus.caf.elastic.tools.ElasticMappingUpdater;
import com.microfocus.caf.elastic.tools.exceptions.IndexNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.TemplateNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.UnexpectedResponseException;

@Ignore
public class ElasticSearchMappingUpdaterTest
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchMappingUpdaterTest.class);
    private final ElasticMappingUpdater indexUpdater;

    public ElasticSearchMappingUpdaterTest()
    {
        indexUpdater = new ElasticMappingUpdater();
    }
    @Test
    public void testItemTemplate()
    {
        updateIndexes("testItemTemplate");
    }

    private void updateIndexes(final String testName)
    {
        LOGGER.info("{}: {}", testName);
        try {
            indexUpdater.updateIndexes();
        } catch (final IOException | UnexpectedResponseException | TemplateNotFoundException | IndexNotFoundException e) {
            LOGGER.error(testName, e);
            fail(testName + ":" + e);
        }
    }

}
