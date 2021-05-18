/*
 * Copyright 2021 Micro Focus or one of its affiliates.
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

import javax.annotation.Nonnull;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;

final class ElasticProvider
{
    /*
     * Returns an initialized instance of the Elasticsearch Rest Client
     */
    @Nonnull
    public static RestClient getClient(final ElasticSettings elasticSettings)
    {
        final String scheme = elasticSettings.getElasticSearchProtocol();
        final String[] hosts = elasticSettings.getElasticSearchHosts().split(",");
        final HttpHost eshosts[] = new HttpHost[hosts.length];
        for (int i = 0; i < hosts.length; i++) {
            eshosts[i] = new HttpHost(hosts[i].trim(), elasticSettings.getElasticSearchRestPort(), scheme);
        }
        final RestClientBuilder restClientBuilder = RestClient.builder(eshosts);
        restClientBuilder.setRequestConfigCallback(builder -> builder.setConnectTimeout(elasticSettings.getElasticSearchConnectTimeout())
            .setSocketTimeout(elasticSettings.getElasticSearchSocketTimeout()));

        final String username = elasticSettings.getElasticSearchUsername();
        final String password = elasticSettings.getElasticSearchPassword();
        if (credentialsSupplied(username, password)) {
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

            restClientBuilder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                .setDefaultCredentialsProvider(credentialsProvider));
        }

        return restClientBuilder.build();
    }

    private static boolean credentialsSupplied(final String username, final String password)
    {
        return username != null && !username.trim().isEmpty() && password != null;
    }
}
