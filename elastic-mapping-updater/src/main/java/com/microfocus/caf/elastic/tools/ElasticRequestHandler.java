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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
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

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microfocus.caf.elastic.tools.exceptions.IndexNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.TemplateNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.UnexpectedResponseException;
import com.microfocus.caf.elastic.tools.model.ReindexRequest;

public class ElasticRequestHandler {

    private static Logger LOGGER = LoggerFactory.getLogger(ElasticRequestHandler.class);
    private final RestClient elasticClient;
    private final ObjectMapper objectMapper;
    private static final String TEMP_INDEX_NAME_PREFIX = "v141_";
    private String originalIndexName = null;
    private String tempIndexName = null;

    public ElasticRequestHandler(final ElasticMappingUpdaterConfiguration schemaUpdaterConfig, final ObjectMapper mapper) {
        this.objectMapper = mapper;
        objectMapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        this.elasticClient = ElasticProvider.getClient(schemaUpdaterConfig.getElasticSettings());
    }

    List<String> getTemplateNames() throws UnexpectedResponseException, IOException {
        LOGGER.info("Get template names");

        final Request request = new Request("GET", "/_cat/templates?h=name&s=name&format=json"); // Sort
                                                                                                 // by
                                                                                                 // index
                                                                                                 // name
        final JsonNode responseNode = performRequest(request);

        if (!responseNode.isArray()) {
            LOGGER.error("Get Templates Request Response: response node is present but is unexpectedly not an array");
            throw new UnexpectedResponseException(
                    "Get Templates Request Response: response node is present but is unexpectedly not an array");
        } else {
            final List<String> indexes = new ArrayList<>();
            final Iterator<JsonNode> it = responseNode.iterator();

            while (it.hasNext()) {
                JsonNode n = it.next();
                indexes.add(n.get("name").asText());
            }
            return indexes;
        }
    }

    IndexTemplateMetaData getTemplate(final String templateName) throws IOException, TemplateNotFoundException {
        LOGGER.info("Get template {}", templateName);
        final Request request = new Request("GET", "/_template/" + templateName);
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            try (final InputStream resultJsonStream = response.getEntity().getContent()) {
                final XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(NamedXContentRegistry.EMPTY, null,
                        resultJsonStream);
                final GetIndexTemplatesResponse getTemplatesResponse = GetIndexTemplatesResponse.fromXContent(parser);
                parser.close();
                final List<IndexTemplateMetaData> templates = getTemplatesResponse.getIndexTemplates();
                if (templates != null && templates.size() > 0) {
                    return templates.get(0);
                } else {
                    throw new TemplateNotFoundException(templateName + " not found");
                }
            }
        } else {
            // TODO get more info from response
            throw new TemplateNotFoundException(templateName + " not found");
        }
    }

    void deleteTemplates(final List<String> dynamicTemplatesInIndex) throws IOException {
        LOGGER.info("Deleting templates {}", dynamicTemplatesInIndex);
        String filter = "";
        if (dynamicTemplatesInIndex != null && dynamicTemplatesInIndex.size() > 0) {
            filter = String.join(",", dynamicTemplatesInIndex);
        }
        final Request request = new Request("DELETE", "/_template/" + filter);
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        LOGGER.info("Templates deletion status: {}", statusCode);
    }

    List<String> getIndexNames(final List<String> indexNamePatterns) throws UnexpectedResponseException, IOException {
        LOGGER.info("Get index names matching pattern(s) : {}", indexNamePatterns);
        String filter = "";
        if (indexNamePatterns != null && indexNamePatterns.size() > 0) {
            filter = "/" + String.join(",", indexNamePatterns);
        }
        final Request request = new Request("GET", "/_cat/indices" + filter + "?h=index&=index&format=json");

        final JsonNode responseNode = performRequest(request);

        if (!responseNode.isArray()) {
            LOGGER.error("Index Request Response: response node is present but is unexpectedly not an array");
            throw new UnexpectedResponseException("Index Request Response: response node is present but is unexpectedly not an array");
        } else {
            final List<String> indexes = new ArrayList<>();
            final Iterator<JsonNode> it = responseNode.iterator();

            while (it.hasNext()) {
                JsonNode n = it.next();
                indexes.add(n.get("index").asText());
            }
            return indexes;
        }
    }

    GetIndexResponse getIndex(final String indexName) throws IOException, IndexNotFoundException {
        LOGGER.info("Get index {}", indexName);
        final Request request = new Request("GET", "/" + indexName);
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {

            try (final InputStream resultJsonStream = response.getEntity().getContent()) {
                final XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(NamedXContentRegistry.EMPTY, null,
                        resultJsonStream);
                final GetIndexResponse getIndexResponse = GetIndexResponse.fromXContent(parser);
                parser.close();
                return getIndexResponse;
            }
        } else {
            throw new IndexNotFoundException(indexName + " not found");
        }
    }

    boolean indexExists(final String indexName) throws IOException {
        LOGGER.info("Checking if index exists {}", indexName);
        final Request request = new Request("HEAD", "/" + indexName);
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        return statusCode != 404;
    }

    boolean createIndex(final String indexName) throws IOException {
        LOGGER.info("Creating index {}", indexName);
        final Request request = new Request("PUT", "/" + indexName);
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        return statusCode == 200;
    }

    boolean updateIndexMapping(final String indexName, final Map<String, Object> mappings)
            throws IOException, UnexpectedResponseException {
        // Only for set of properties that are newly added.
        // TODO check adding new property to existing object/nested property
        LOGGER.info("Update mapping of index {} with changes {}", indexName, mappings);
        final String mappingSource = objectMapper.writeValueAsString(mappings);
        final Request request = new Request("PUT", "/" + indexName + "/_mapping");
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
                ? objectMapper.readTree(responseEntity.getContent()) : objectMapper.readTree(EntityUtils.toString(responseEntity));

        // Check that it is not null
        if (responseNode == null) {
            throw new UnexpectedResponseException("JSON response deserialized to null");
        }
        return response.getStatusLine().getStatusCode() == 200;
    }

    boolean deleteIndex(final String indexName) throws IOException {
        LOGGER.info("Deleting index {}", indexName);
        final Request request = new Request("DELETE", "/" + indexName);
        final Response response = elasticClient.performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        return statusCode == 200;
    }

    JsonNode reIndex(final String sourceIndexName, final String destinationIndexName) throws IOException, UnexpectedResponseException {
        LOGGER.info("Reindexing from {} to {}" + sourceIndexName, destinationIndexName);

        final ReindexRequest rendexRequest = new ReindexRequest(sourceIndexName, destinationIndexName);
        final Request request = new Request("POST", "/_reindex");
        request.setJsonEntity(objectMapper.writeValueAsString(rendexRequest));

        final JsonNode responseNode = performRequest(request);

        // Return the response
        return responseNode;
    }

    private boolean reindexOrigToTemp(final String templateName) throws IOException, UnexpectedResponseException {

        LOGGER.info("Reindexing from " + this.originalIndexName + " to " + this.tempIndexName);

        // Create a new Index
        String newIndexName = TEMP_INDEX_NAME_PREFIX + templateName;
        createIndex(newIndexName);

        // Re-index the data.
        Long start = System.currentTimeMillis();
        LOGGER.info("Reindexing...");
        JsonNode response = reIndex(this.originalIndexName, this.tempIndexName);
        final Long duration = System.currentTimeMillis() - start;
        final JsonNode failuresNode = response.get("failures");
        if (failuresNode == null) {
            LOGGER.error("Reindex Request Response: 'failures' node not found");
        } else if (!failuresNode.isArray()) {
            LOGGER.error("Reindex Request Response: 'failures' node present but is unexpectedly not an array");
        } else {
            final List<Failure> failures = new ArrayList<>();
            final Iterator<JsonNode> it = failuresNode.iterator();

            while (it.hasNext()) {
                JsonNode n = it.next();
                failures.add(new Failure(n.get("index").asText(), n.get("type").asText(), n.get("id").asText(), null));
            }

            if (!failures.isEmpty()) {
                LOGGER.error("Failed to reindex " + failures.size() + " items for template " + templateName);
                return false;
            }
        }

        LOGGER.info("Reindexing completed in {}s", duration / 1000);

        // Delete original index
        if (deleteIndex(this.originalIndexName)) {
            LOGGER.info("Successfully deleted index " + this.originalIndexName);
        }

        return true;
    }

    private boolean reindexTempToOrig(final String templateName) throws IOException, UnexpectedResponseException {
        LOGGER.info("Reindexing from new to original name");

        // TODO: delete any aliases
        /*
         * try { IndicesAliasesResponse resp =
         * client.getClient().admin().indices().prepareAliases()
         * .removeAlias(this.tempIndexName, this.originalIndexName).get(); }
         * catch (AliasesNotFoundException anf) {
         * logger.trace("No alias found"); }
         */
        // Create a new Index
        createIndex(this.originalIndexName);

        // Re-index the data.
        final Long start = System.currentTimeMillis();
        LOGGER.info("Reindexing...");
        final JsonNode response = reIndex(this.tempIndexName, this.originalIndexName);
        final Long duration = System.currentTimeMillis() - start;
        final JsonNode failuresNode = response.get("failures");
        if (failuresNode == null) {
            LOGGER.error("Reindex Request Response: 'failures' node not found");
        } else if (!failuresNode.isArray()) {
            LOGGER.error("Reindex Request Response: 'failures' node present but is unexpectedly not an array");
        } else {
            final List<Failure> failures = new ArrayList<>();
            final Iterator<JsonNode> it = failuresNode.iterator();

            while (it.hasNext()) {
                JsonNode n = it.next();
                failures.add(new Failure(n.get("index").asText(), n.get("type").asText(), n.get("id").asText(), null));
            }

            if (!failures.isEmpty()) {
                LOGGER.error("Failed to reindex " + failures.size() + " items for template " + templateName);
                return false;
            }
        }

        LOGGER.info("Reindexing completed in {}s", duration / 1000);

        // Delete original index
        if (deleteIndex(this.tempIndexName)) {
            LOGGER.info("Successfully deleted index " + this.tempIndexName);
        }

        return true;
    }

    private JsonNode performRequest(final Request request) throws UnexpectedResponseException, IOException{
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
                ? objectMapper.readTree(responseEntity.getContent()) : objectMapper.readTree(EntityUtils.toString(responseEntity));

        // Check that it is not null
        if (responseNode == null) {
            throw new UnexpectedResponseException("JSON response deserialized to null");
        }
        return responseNode;
    }

}
