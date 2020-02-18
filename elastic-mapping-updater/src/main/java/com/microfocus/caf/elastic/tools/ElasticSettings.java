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
package com.microfocus.caf.elastic.tools;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class ElasticSettings
{

    @NotNull
    @Size(min = 1)
    private String elasticSearchHosts;

    @Min(1)
    private int elasticSearchRestPort;

    // connect timeout (defaults to 1 second)
    @NotNull
    private int elasticSearchConnectTimeout;

    // socket timeout (defaults to 30 seconds)
    @NotNull
    private int elasticSearchSocketTimeout;

    public ElasticSettings(final String elasticSearchHosts, final int elasticSearchRestPort, final int elasticSearchConnectTimeout,
            final int elasticSearchSocketTimeout) {
        this.elasticSearchHosts = elasticSearchHosts;
        this.elasticSearchRestPort = elasticSearchRestPort;
        this.elasticSearchConnectTimeout = elasticSearchConnectTimeout;
        this.elasticSearchSocketTimeout = elasticSearchSocketTimeout;
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
     * Setter for property 'elasticSearchHosts'.
     *
     * @param elasticSearchHosts Value to set for property 'elasticSearchHosts'.
     */
    public void setElasticSearchHosts(String elasticSearchHosts)
    {
        this.elasticSearchHosts = elasticSearchHosts;
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

    /**
     * Setter for property 'elasticSearchRestPort'.
     *
     * @param elasticSearchRestPort Value to set for property 'elasticSearchRestPort'.
     */
    public void setElasticSearchRestPort(final int elasticSearchRestPort)
    {
        this.elasticSearchRestPort = elasticSearchRestPort;
    }

    public int getElasticSearchConnectTimeout()
    {
        return elasticSearchConnectTimeout;
    }

    public void setElasticSearchConnectTimeout(int elasticSearchConnectTimeout)
    {
        this.elasticSearchConnectTimeout = elasticSearchConnectTimeout;
    }

    public int getElasticSearchSocketTimeout()
    {
        return elasticSearchSocketTimeout;
    }

    public void setElasticSearchSocketTimeout(int elasticSearchSocketTimeout)
    {
        this.elasticSearchSocketTimeout = elasticSearchSocketTimeout;
    }

}
