/*
 * Copyright 2020 Micro Focus or one of its affiliates.
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

public final class ElasticSettings
{

    private final String elasticSearchProtocol;

    private final String elasticSearchHosts;

    private final int elasticSearchRestPort;

    private final int elasticSearchConnectTimeout;

    private final int elasticSearchSocketTimeout;

    public ElasticSettings(final String elasticSearchProtocol, final String elasticSearchHosts,
            final int elasticSearchRestPort, final int elasticSearchConnectTimeout,
            final int elasticSearchSocketTimeout)
    {
        this.elasticSearchProtocol = elasticSearchProtocol;
        this.elasticSearchHosts = elasticSearchHosts;
        this.elasticSearchRestPort = elasticSearchRestPort;
        this.elasticSearchConnectTimeout = elasticSearchConnectTimeout;
        this.elasticSearchSocketTimeout = elasticSearchSocketTimeout;
    }

    /**
     * Getter for property 'elasticSearchProtocol'.
     *
     * @return Value for property 'elasticSearchProtocol'.
     */
    public String getElasticSearchProtocol()
    {
        return elasticSearchProtocol;
    }

    /**
     * Getter for property 'elasticSearchHosts'.
     *
     * @return Value for property 'elasticSearchHosts'.
     */
    public String getElasticSearchHosts()
    {
        return elasticSearchHosts;
    }

    /**
     * Getter for property 'elasticSearchRestPort'.
     *
     * @return Value for property 'elasticSearchRestPort'.
     */
    public int getElasticSearchRestPort()
    {
        return elasticSearchRestPort;
    }

    public int getElasticSearchConnectTimeout()
    {
        return elasticSearchConnectTimeout;
    }

    public int getElasticSearchSocketTimeout()
    {
        return elasticSearchSocketTimeout;
    }

}
