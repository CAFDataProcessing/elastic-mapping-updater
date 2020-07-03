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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.indices.GetIndexTemplatesResponse;
import org.elasticsearch.client.indices.IndexTemplateMetaData;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetIndexException;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetTemplatesException;
import com.github.cafdataprocessing.elastic.tools.exceptions.UnexpectedResponseException;
import com.google.common.net.UrlEscapers;

final class ElasticRequestHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticRequestHandler.class);
    private final RestClient elasticClient;
    private final ObjectMapper objectMapper;

    public ElasticRequestHandler(final ElasticMappingUpdaterConfiguration schemaUpdaterConfig, final ObjectMapper mapper)
    {
        this.objectMapper = mapper;
        this.elasticClient = ElasticProvider.getClient(schemaUpdaterConfig.getElasticSettings());
    }

    List<IndexTemplateMetaData> getTemplates()
            throws IOException, GetTemplatesException
        {
        LOGGER.debug("Getting templates...");
        final Request request = new Request("GET", "/_template");
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            try (final InputStream resultJsonStream = response.getEntity().getContent();
                 final XContentParser parser
                 = XContentFactory.xContent(XContentType.JSON).createParser(NamedXContentRegistry.EMPTY, null, resultJsonStream)) {
                final GetIndexTemplatesResponse getTemplatesResponse = GetIndexTemplatesResponse.fromXContent(parser);
                final List<IndexTemplateMetaData> templates = getTemplatesResponse.getIndexTemplates();
                return templates;
            }
        } else {
            throw new GetTemplatesException(String.format("Error getting templates. Status code: %s, response: %s",
                                                         statusCode, EntityUtils.toString(response.getEntity())));
        }
    }

    List<String> getIndexNames(final List<String> indexNamePatterns) throws UnexpectedResponseException, IOException
    {
        LOGGER.debug("Getting index names matching pattern(s) : {}...", indexNamePatterns);
        if(indexNamePatterns == null || indexNamePatterns.isEmpty())
        {
            return Collections.emptyList();
        }
        final String filter = "/" + String.join(",", indexNamePatterns);
        final Request request = new Request("GET", filter);

        final JsonNode responseNode = performRequest(request);

        final Iterable<String> fieldNames = () -> responseNode.fieldNames();

        return StreamSupport.stream(fieldNames.spliterator(), false)
                .sorted()
                .collect(Collectors.toList());
    }

    public GetIndexResponse getIndex(final String indexName) throws IOException, GetIndexException
    {
        LOGGER.debug("Getting index {}...", indexName);
        final Request request = new Request("GET", "/" + UrlEscapers.urlPathSegmentEscaper().escape(indexName));
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            try (final InputStream resultJsonStream = response.getEntity().getContent();
                 final XContentParser parser
                 = XContentFactory.xContent(XContentType.JSON).createParser(NamedXContentRegistry.EMPTY, null, resultJsonStream)) {
                return GetIndexResponse.fromXContent(parser);
            }
        } else {
            throw new GetIndexException(String.format("Error getting index '%s'. Status code: %s, response: %s",
                                                      indexName, statusCode, EntityUtils.toString(response.getEntity())));
        }
    }

    boolean updateIndexMapping(final String indexName, final Map<String, Object> mappings)
        throws IOException, UnexpectedResponseException
    {
        LOGGER.debug("Updating mapping of index {} with these changes {}...", indexName, mappings);
        final String mappingSource = objectMapper.writeValueAsString(mappings);
        final Request request = new Request("PUT", "/" + UrlEscapers.urlPathSegmentEscaper().escape(indexName) + "/_mapping");
        request.setJsonEntity(mappingSource);

        final Response response = elasticClient.performRequest(request);

        // Get the response entity
        final HttpEntity responseEntity = response.getEntity();

        // Get the Content-Type header
        final ContentType contentType = ContentType.get(responseEntity);
        if (contentType == null) {
            throw new UnexpectedResponseException("Failed to get the content type from the response entity");
        }

        // Check that the response is JSON
        if (!ContentType.APPLICATION_JSON.getMimeType().equals(contentType.getMimeType())) {
            throw new UnexpectedResponseException("JSON response expected but response type was " + contentType.getMimeType());
        }

        // Parse the response
        final JsonNode responseNode = StandardCharsets.UTF_8.equals(contentType.getCharset())
            ? objectMapper.readTree(responseEntity.getContent())
            : objectMapper.readTree(EntityUtils.toString(responseEntity));

        // Check that it is not null
        if (responseNode == null) {
            throw new UnexpectedResponseException("JSON response deserialized to null");
        }

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            return true;
        } else {
            LOGGER.error("Index Mapping upadate status : {}", statusCode);
            return false;
        }
    }

    private JsonNode performRequest(final Request request) throws UnexpectedResponseException, IOException
    {
        final Response response = elasticClient.performRequest(request);

        // Get the response entity
        final HttpEntity responseEntity = response.getEntity();

        // Get the Content-Type header
        final ContentType contentType = ContentType.get(responseEntity);
        if (contentType == null) {
            throw new UnexpectedResponseException("Failed to get the content type from the response entity");
        }

        // Check that the response is JSON
        if (!ContentType.APPLICATION_JSON.getMimeType().equals(contentType.getMimeType())) {
            throw new UnexpectedResponseException("JSON response expected but response type was " + contentType.getMimeType());
        }

        // Parse the response
        final JsonNode responseNode = StandardCharsets.UTF_8.equals(contentType.getCharset())
            ? objectMapper.readTree(responseEntity.getContent())
            : objectMapper.readTree(EntityUtils.toString(responseEntity));

        // Check that it is not null
        if (responseNode == null) {
            throw new UnexpectedResponseException("JSON response deserialized to null");
        }

        return responseNode;
    }
}
