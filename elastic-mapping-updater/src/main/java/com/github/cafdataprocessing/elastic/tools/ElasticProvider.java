/*
 * Copyright 2020-2026 Open Text.
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

import jakarta.annotation.Nonnull;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;

import java.util.concurrent.TimeUnit;

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
            eshosts[i] = new HttpHost(scheme, hosts[i].trim(), elasticSettings.getElasticSearchRestPort());
        }
        final RestClientBuilder restClientBuilder = RestClient.builder(eshosts);
        restClientBuilder.setRequestConfigCallback(builder -> builder
            .setConnectTimeout(Timeout.of(elasticSettings.getElasticSearchConnectTimeout(), TimeUnit.SECONDS))
            .setResponseTimeout(Timeout.of(elasticSettings.getElasticSearchSocketTimeout(), TimeUnit.SECONDS)));

        final String username = elasticSettings.getElasticSearchUsername();
        final String password = elasticSettings.getElasticSearchPassword();
        if (credentialsSupplied(username, password)) {
            final var credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(new AuthScope(null, -1),
                new UsernamePasswordCredentials(username, password.toCharArray()));

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
