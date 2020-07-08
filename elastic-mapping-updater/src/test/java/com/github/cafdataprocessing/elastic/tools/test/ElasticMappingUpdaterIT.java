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
package com.github.cafdataprocessing.elastic.tools.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.indices.PutIndexTemplateRequest;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.cafdataprocessing.elastic.tools.ElasticMappingUpdater;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetIndexException;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetTemplatesException;
import com.github.cafdataprocessing.elastic.tools.exceptions.UnexpectedResponseException;
import com.google.common.net.UrlEscapers;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.apache.http.conn.ConnectTimeoutException;

public final class ElasticMappingUpdaterIT
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdaterIT.class);
    private final RestHighLevelClient client;
    private final String protocol;
    private final String host;
    private final int port;
    private final int connectTimeout;
    private final int socketTimeout;

    public ElasticMappingUpdaterIT()
    {
        protocol = System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_PROTOCOL");
        host = System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_HOSTNAMES");
        port = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_REST_PORT"));
        connectTimeout = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_CONNECT_TIMEOUT"));
        socketTimeout = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_SOCKET_TIMEOUT"));

        client = new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http")));
    }

    @Test
    public void testUpdateIndexesOfUpdatedTemplate() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateIndexesOfUpdatedTemplate'...");
        final String templateName = "sample-template";
        final String origTemplateSourceFile = "/template1.json";
        final String updatedTemplateSourceFile = "/template2.json";
        final String indexName = "foo-com_sample-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged()) {
            fail();
        }
        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Creating index matching template {}", templateName);
        // Create an index with some data
        IndexRequest request = new IndexRequest(indexName);
        request.id("1");
        request.routing("1");
        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true," + "'PERSON':{ 'NAME':'person1' }" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        final boolean needsRetries = indexDocumentWithRetry(request);
        if (needsRetries) {
            // Indexing has failed after multiple retries
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged()) {
            fail();
        }

        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateIndexesOfUpdatedTemplate", templateName);

        // Verify index mapping has new properties
        final Map<String, Object> indexTypeMappings = getIndexMapping(indexName);
        @SuppressWarnings("unchecked")
        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
        @SuppressWarnings("unchecked")
        final Map<String, Object> propMapping = (Map<String, Object>) props.get("DATE_DISPOSED");
        assertNotNull("testUpdateIndexesOfUpdatedTemplate", propMapping);

        // Index more data
        request = new IndexRequest(indexName);
        request.id("2");
        request.routing("1");
        jsonString = "{"
            + "'TITLE':'doc2',"
            + "'DATE_PROCESSED':'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        try {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e) {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 2);
    }

    @Test
    public void testUpdateUnsupportedChanges() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateUnsupportedChanges'...");
        final String templateName = "acme-sample-template";
        final String origTemplateSourceFile = "/template3.json";
        // This template has modified "type" param for IS_HEAD_OF_FAMILY and FAILURES/AJP_JOB_RUN_ID
        final String unsupportedTemplateSourceFile = "/template4.json";
        final String indexName = "test_acmesample-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testUpdateUnsupportedChanges - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged()) {
            fail();
        }
        LOGGER.info("testUpdateUnsupportedChanges - Creating index matching template {}", templateName);
        // Create an index with some data
        IndexRequest request = new IndexRequest(indexName);
        request.id("1");
        request.routing("1");
        String jsonString = "{"
            + "'TITLE':'doc1',"
            + "'DATE_PROCESSED\":'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person1' }"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        final boolean needsRetries = indexDocumentWithRetry(request);
        if (needsRetries) {
            // Indexing has failed after multiple retries
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateUnsupportedChanges - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(unsupportedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged()) {
            fail();
        }

        LOGGER.info("testUpdateUnsupportedChanges - Updating indexes matching template {}", templateName);
        // Unsupported mapping changes not applied to Index mapping
        updateIndex("testUpdateUnsupportedChanges", templateName);

        // Verify index mapping of unsupported field changes has not changed
        final Map<String, Object> indexTypeMappings = getIndexMapping(indexName);
        @SuppressWarnings("unchecked")
        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
        @SuppressWarnings("unchecked")
        final Map<String, Object> propMapping = (Map<String, Object>) props.get("IS_HEAD_OF_FAMILY");
        final String propValue = (String) propMapping.get("type");
        // Verify property mapping value is same as before
        assertTrue("testUpdateUnsupportedChanges", propValue.equals("boolean"));

        // Verify index mapping of allowed field changes has been updated
        @SuppressWarnings("unchecked")
        final Map<String, Map<String, Object>> personPropMapping = (Map<String, Map<String, Object>>) props.get("PERSON");
        @SuppressWarnings("unchecked")
        final Map<String, Object> agePropMapping = (Map<String, Object>) personPropMapping.get("properties").get("AGE");
        final String ageTypeValue = (String) agePropMapping.get("type");
        assertTrue("testUpdateUnsupportedChanges", ageTypeValue.equals("long"));
    }

    @Test
    public void testUpdateDynamicTemplateOverwrite() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateDynamicTemplateOverwrite'...");
        final String templateName = "sample-template";
        // This template has a dynamic_templaten called "EVERY_THING_ELSE_TEMPLATE"
        final String origTemplateSourceFile = "/template5.json";
        // This template has a dynamic_templaten called "LONG_TEMPLATE"
        final String updatedTemplateSourceFile = "/template6.json";
        final String indexName = "test_dynsample-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged()) {
            fail();
        }
        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating index matching template {}", templateName);
        // Create an index with some data
        IndexRequest request = new IndexRequest(indexName);
        request.id("1");
        request.routing("1");
        String jsonString = "{"
            + "'TITLE':'doc1',"
            + "'DATE_PROCESSED\":'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person1' }"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        boolean needsRetries = indexDocumentWithRetry(request);
        if (needsRetries) {
            // Indexing has failed after multiple retries
            fail();
        }
        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged()) {
            fail();
        }

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateDynamicTemplateOverwrite", templateName);

        // Verify updated index mapping has only one dynamic_template from the new index template
        final Map<String, Object> indexTypeMappings = getIndexMapping(indexName);
        Object dynamicTemplatesInTemplate = indexTypeMappings.get("dynamic_templates");
        if (dynamicTemplatesInTemplate == null) {
            fail();
        } else {
            @SuppressWarnings("unchecked")
            final Map<String, Object> dynTemplate = (Map<String, Object>) ((List<Map<String, Object>>) dynamicTemplatesInTemplate).get(0);
            assertTrue("testUpdateDynamicTemplateOverwrite", dynTemplate.size() == 1);
            assertNotNull("testUpdateDynamicTemplateOverwrite", dynTemplate.get("LONG_TEMPLATE"));
        }

        // Index more data
        request = new IndexRequest(indexName);
        request.id("2");
        request.routing("1");
        jsonString = "{"
            + "'TITLE':'doc2',"
            + "'DATE_PROCESSED':'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        needsRetries = indexDocumentWithRetry(request);
        if (needsRetries) {
            // Indexing has failed after multiple retries
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 2);
    }

    @Test
    public void testNoIndexesMatchingTemplate() throws IOException
    {
        LOGGER.info("Running test 'testNoIndexesMatchingTemplate'...");
        final String templateName = ".kibana_task_manager";
        final String origTemplateSourceFile = "/template7.json";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testNoIndexesMatchingTemplate - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged()) {
            fail();
        }

        // Try updating indexes for template that has no matching indexes
        LOGGER.info("testNoIndexesMatchingTemplate - Updating indexes matching template {}", templateName);

        updateIndex("testNoIndexesMatchingTemplate", templateName);
    }

    @Test
    public void testUpdateIndexesOfUnSupportedChangesInTemplate() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateIndexesOfUnSupportedChangesInTemplate'...");
        final String templateName = "sample-template";
        final String origTemplateSourceFile = "/template8.json";
        final String updatedTemplateSourceFile = "/template9.json";
        final String indexName = "foo-com_lang-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged()) {
            fail();
        }
        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Creating index matching template {}", templateName);
        // Create an index with some data
        IndexRequest request = new IndexRequest(indexName);
        request.id("1");
        request.routing("1");
        String jsonString = "{"
                + "'TITLE':'doc1',"
                + "'DATE_PROCESSED\":'2020-02-11',"
                + "'CONTENT_PRIMARY':'just a test',"
                + "'IS_HEAD_OF_FAMILY':true,"
                + "'PERSON':{ 'NAME':'person1' },"
                + "'LANGUAGE_CODES':{ 'CODE':'en', 'CONFIDENCE': 100}"
                + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        final boolean needsRetries = indexDocumentWithRetry(request);
        if (needsRetries) {
            // Indexing has failed after multiple retries
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged()) {
            fail();
        }

        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateIndexesOfUnSupportedChangesInTemplate", templateName);

        // Verify index mapping has new properties
        final Map<String, Object> indexTypeMappings = getIndexMapping(indexName);
        @SuppressWarnings("unchecked")
        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
        @SuppressWarnings("unchecked")
        final Map<String, Object> propMapping = (Map<String, Object>) props.get("DATE_DISPOSED");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", propMapping);

        // Verify allowed field changes has updated field mapping
        @SuppressWarnings("unchecked")
        final Map<String, Object> idPropMapping = (Map<String, Object>) props.get("ID");
        LOGGER.info("idPropMapping {} ", idPropMapping);
        final Object idPropValue = idPropMapping.get("ignore_malformed");
        // Verify property mapping value has changed
        assertNull("testUpdateUnsupportedChanges", idPropValue);

        // Verify index mapping of unsupported field changes has not changed
        @SuppressWarnings("unchecked")
        final Map<String, Object> langPropMapping = (Map<String, Object>) props.get("LANGUAGE_CODES");
        final String propValue = (String) langPropMapping.get("type");
        // Verify property mapping value is same as before
        assertTrue("testUpdateUnsupportedChanges", propValue.equals("nested"));

        // Index more data
        request = new IndexRequest(indexName);
        request.id("2");
        request.routing("1");
        jsonString = "{"
            + "'TITLE':'doc2',"
            + "'DATE_PROCESSED':'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'},"
            + "'LANGUAGE_CODES':{ 'CODE':'ko', 'CONFIDENCE': 100}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        request.source(jsonString, XContentType.JSON);
        request.setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        try {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e) {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 2);
    }

    private void updateIndex(final String testName, final String templateName)
    {
        LOGGER.info("{}: {}", testName, templateName);
        try {
            ElasticMappingUpdater.update(false, host, protocol, port, connectTimeout, socketTimeout);
        } catch (final IOException | UnexpectedResponseException | GetIndexException | GetTemplatesException e) {
            LOGGER.error(testName, e);
            fail(testName + ":" + e);
        }
    }

    public static String readFile(final String path) throws IOException
    {
        InputStream is = null;
        BufferedReader br = null;

        try {
            is = ElasticMappingUpdaterIT.class.getResourceAsStream(path);

            final StringBuilder sb = new StringBuilder();
            String line;
            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append("\n");
            }
            final String content = sb.toString();
            return content;
        } finally {
            if (is != null) {
                is.close();
            }
            if (br != null) {
                br.close();
            }
        }
    }

    private void verifyIndexData(final String indexName, final QueryBuilder query, final long expectedHitCount) throws IOException
    {
        final SearchRequest searchRequest = new SearchRequest(indexName);
        final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.trackTotalHits(true);
        searchSourceBuilder.query(query);
        searchRequest.source(searchSourceBuilder);
        org.elasticsearch.action.search.SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
        final long totalDocs = searchResponse.getHits().getTotalHits().value;
        LOGGER.info("Hits : {}", totalDocs);
        LOGGER.info(searchResponse.toString());
        assertTrue("Got test document", totalDocs == expectedHitCount);

        final SearchHit[] searchHits = searchResponse.getHits().getHits();
        for (final SearchHit hit : searchHits) {
            final Map<String, Object> sourceAsMap = hit.getSourceAsMap();
            LOGGER.info("hit : {}", sourceAsMap);
        }
    }

    private Map<String, Object> getIndexMapping(final String indexName) throws IOException, GetIndexException
    {
        LOGGER.info("Get index {}", indexName);
        final Request request = new Request("GET", "/" + UrlEscapers.urlPathSegmentEscaper().escape(indexName));
        final Response response = client.getLowLevelClient().performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            try (final InputStream resultJsonStream = response.getEntity().getContent();
                 final XContentParser parser
                 = XContentFactory.xContent(XContentType.JSON).createParser(NamedXContentRegistry.EMPTY, null, resultJsonStream)) {
                final GetIndexResponse getIndexResponse = GetIndexResponse.fromXContent(parser);
                final MappingMetaData indexMappings = getIndexResponse.getMappings().get(indexName);
                final Map<String, Object> indexTypeMappings = indexMappings.getSourceAsMap();
                LOGGER.info("------Updated mapping for index '{}': {}", indexName, indexTypeMappings);
                return indexTypeMappings;
            }
        } else {
            throw new GetIndexException(String.format("Error getting index '%s'. Status code: %s, response: %s",
                                                      indexName, statusCode, EntityUtils.toString(response.getEntity())));
        }
    }

    private boolean indexDocumentWithRetry(final IndexRequest request) throws InterruptedException
    {
        boolean retry = true;
        for (int i = 0; i < 3; i++) {
            retry = indexDocument(request);
            if (!retry) {
                break;
            }
            Thread.sleep(3000);
        }
        return retry;
    }

    private boolean indexDocument(final IndexRequest request)
    {
        try {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final IOException ex) {
            return isServiceUnAvailableException(ex);
        }
        return false;
    }

    private static boolean isServiceUnAvailableException(final Exception ex)
    {
        final Throwable cause = ex.getCause();

        if (cause instanceof Exception && cause != ex && isServiceUnAvailableException((Exception) cause)) {
            return true;
        }

        if (ex instanceof ConnectException) {
            // Thrown to signal that an error occurred while attempting to connect a socket to a remote address and port.
            // Typically, the connection was refused remotely (e.g., no process is listening on the remote address/port)
            return true;
        }
        if (ex instanceof SocketException) {
            // Thrown to indicate that there is an error creating or accessing a Socket.
            return true;
        }
        if (ex instanceof SocketTimeoutException) {
            // Signals that a timeout has occurred on a socket read or accept.
            return true;
        }
        if (ex instanceof UnknownHostException) {
            // There is a possibility of IP address of host could not be determined because of some network issue.
            // This can be treated as transient
            return true;
        }
        if (ex instanceof HttpRetryException) {
            // Thrown to indicate that a HTTP request needs to be retried but cannot be retried automatically, due to streaming
            // mode being enabled.
            return true;
        }
        if (ex instanceof ConnectTimeoutException) {
            // ConnectionPoolTimeoutException < ConnectTimeoutException
            return true;
        }
        return false;
    }
}
