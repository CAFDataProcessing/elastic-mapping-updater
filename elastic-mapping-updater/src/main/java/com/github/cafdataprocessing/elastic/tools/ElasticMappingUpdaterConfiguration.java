/*
 * Copyright 2020-2022 Micro Focus or one of its affiliates.
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
package com.github.cafdataprocessing.elastic.tools;

final class ElasticMappingUpdaterConfiguration
{
    private final ElasticSettings elasticSettings;

    public ElasticMappingUpdaterConfiguration(final ElasticSettings elasticSettings)
    {
        this.elasticSettings = elasticSettings;
    }

    public ElasticSettings getElasticSettings()
    {
        return elasticSettings;
    }
}
