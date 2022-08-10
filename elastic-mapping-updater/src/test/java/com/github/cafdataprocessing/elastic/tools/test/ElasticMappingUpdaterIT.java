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
package com.github.cafdataprocessing.elastic.tools.test;

import static org.junit.Assert.assertFalse;
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
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.cafdataprocessing.elastic.tools.ElasticMappingUpdater;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetIndexException;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetTemplatesException;
import com.github.cafdataprocessing.elastic.tools.exceptions.UnexpectedResponseException;
import com.google.common.net.UrlEscapers;
import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.apache.http.conn.ConnectTimeoutException;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TrackHits;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexState;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateResponse;
import org.opensearch.client.transport.rest_client.RestClientTransport;

public final class ElasticMappingUpdaterIT
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdaterIT.class);
    private final OpenSearchClient client;
    private final RestClientTransport transport;
    private final String protocol;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int connectTimeout;
    private final int socketTimeout;

    public ElasticMappingUpdaterIT()
    {
        protocol = "http"; //System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_PROTOCOL");
        host = "localhost";//System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_HOSTNAMES");
        port = 8080;//Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_REST_PORT"));
        username = "";
        password = "";
        connectTimeout = 1000;//Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_CONNECT_TIMEOUT"));
        socketTimeout = 1000;//Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_SOCKET_TIMEOUT"));

        transport = new RestClientTransport(RestClient.builder(new HttpHost(host, port, "http")).build(), new JacksonJsonpMapper());
        client = new OpenSearchClient(transport);
    }

    @Test
    public void testUpdateIndexesOfUpdatedTemplate() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateIndexesOfUpdatedTemplate'...");
        final String templateName = "sample-template";
        final String origTemplateSourceFile = "/template1.json";
        /*
         * Dynamic templates removed
         * 'strict' mapping introduced
         * New simple prop STACK added to prop FAILURES
         * New object prop MATCH added to prop ENTITIES
         * New simple prop AGE added to prop PERSON
         * New nested prop HOLD_DETAILS added
         * New date prop DATE_DISPOSED added
         */
        final String updatedTemplateSourceFile = "/template2.json";
        final String indexName = "foo-com_sample-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);

//        JacksonJsonpMapper mapper = new JacksonJsonpMapper(new com.fasterxml.jackson.databind.json.JsonMapper());
//        JsonParser parser = mapper.jsonProvider().createParser(new StringReader(origTemplateSource));
//        PutIndexTemplateResponse.Builder deserialize = mapper.deserialize(parser, PutIndexTemplateResponse.Builder.class);
        
        try (final InputStream resultJsonStream = new ByteArrayInputStream(origTemplateSource.getBytes());
                 final JsonParser jsonValueParser = Json.createParser(resultJsonStream)) {
                                
            final PutIndexTemplateRequest trequest = PutIndexTemplateRequest._DESERIALIZER.deserialize(jsonValueParser, new JacksonJsonpMapper());
            final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
            if (!putTemplateResponse.acknowledged()) {
                fail();
            }
        }
        
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        
//        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true," + "'PERSON':{ 'NAME':'person1' }" + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating indexes matching template {}", templateName);
//        updateIndex("testUpdateIndexesOfUpdatedTemplate", templateName);
//
//        // Verify index mapping has new properties
//        final TypeMapping indexTypeMappings = getIndexMapping("testUpdateIndexesOfUpdatedTemplate", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.properties();
//        // Verify new prop DATE_DISPOSED was added
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propMapping = (Map<String, Object>) props.get("DATE_DISPOSED");
//        assertNotNull("testUpdateIndexesOfUpdatedTemplate", propMapping);
//
//        // Index more data
//        request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("2")
//            .routing("1");
//        
//        jsonString = "{"
//            + "'TITLE':'doc2',"
//            + "'DATE_PROCESSED':'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
//            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//
//        try {
//            final IndexResponse response = client.index(request.build());
//            assertTrue(response.result().equals(Result.Created));
//        } catch (final OpenSearchException e) {
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
    }

//    @Test
//    public void testUpdateUnsupportedChanges() throws IOException, GetIndexException, InterruptedException
//    {
//        LOGGER.info("Running test 'testUpdateUnsupportedChanges'...");
//        final String templateName = "acme-sample-template";
//        final String origTemplateSourceFile = "/template3.json";
//        /*
//         * Dynamic templates removed
//         * 'strict' mapping introduced
//         * New simple prop STACK added to prop FAILURES
//         * New simple prop AGE added to prop PERSON
//         * New nested prop HOLD_DETAILS added
//         * New date prop DATE_DISPOSED added
//         * This template has modified "type" param for IS_HEAD_OF_FAMILY and FAILURES/AJP_JOB_RUN_ID
//         * IS_HEAD_OF_FAMILY: "ignore_malformed" added
//         * FAILURES/AJP_JOB_RUN_ID: "ignore_above" removed and ignore_malformed added
//         * LANGUAGE_CODES: type set to nested (changed from object), "include_in_parent" added
//         */
//        final String unsupportedTemplateSourceFile = "/template4.json";
//        final String indexName = "test_acmesample-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testUpdateUnsupportedChanges - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testUpdateUnsupportedChanges - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{"
//            + "'TITLE':'doc1',"
//            + "'DATE_PROCESSED\":'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person1' }"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testUpdateUnsupportedChanges - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(unsupportedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testUpdateUnsupportedChanges - Updating indexes matching template {}", templateName);
//        // Unsupported mapping changes not applied to Index mapping
//        updateIndex("testUpdateUnsupportedChanges", templateName);
//
//        // Verify index mapping of unsupported field changes has not changed
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testUpdateUnsupportedChanges", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propMapping = (Map<String, Object>) props.get("IS_HEAD_OF_FAMILY");
//        final String propValue = (String) propMapping.get("type");
//        // Verify property mapping value is same as before
//        assertTrue("testUpdateUnsupportedChanges", propValue.equals("boolean"));
//        final Object propIgnoreMalformed = propMapping.get("ignore_malformed");
//        // Verify new param was not added
//        assertNull("testUpdateUnsupportedChanges", propIgnoreMalformed);
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Map<String, Object>> failuresPropMapping = (Map<String, Map<String, Object>>) props.get("FAILURES");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> jrIdPropMapping = (Map<String, Object>) failuresPropMapping.get("properties").get("AJP_JOB_RUN_ID");
//        // Verify type is same as before
//        final String jrIdTypeValue = (String) jrIdPropMapping.get("type");
//        assertTrue("testUpdateUnsupportedChanges", jrIdTypeValue.equals("keyword"));
//        // Verify param not removed
//        final Object propjrIdIgnoreAbove = jrIdPropMapping.get("ignore_above");
//        assertNotNull("testUpdateUnsupportedChanges", propjrIdIgnoreAbove);
//        // Verify param not added
//        final Object propjrIdIgnoreMalformed = jrIdPropMapping.get("ignore_malformed");
//        assertNull("testUpdateUnsupportedChanges", propjrIdIgnoreMalformed);
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propLCMapping = (Map<String, Object>) props.get("LANGUAGE_CODES");
//        final Object propLCType = propLCMapping.get("type");
//        // Verify type not added
//        assertNull("testUpdateUnsupportedChanges", propLCType);
//        final Object propLCIncludeInParent = propLCMapping.get("include_in_parent");
//        // Verify new param was not added
//        assertNull("testUpdateUnsupportedChanges", propLCIncludeInParent);
//
//        // Verify index mapping of allowed field changes has been updated
//        @SuppressWarnings("unchecked")
//        final Map<String, Map<String, Object>> personPropMapping = (Map<String, Map<String, Object>>) props.get("PERSON");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> agePropMapping = (Map<String, Object>) personPropMapping.get("properties").get("AGE");
//        final String ageTypeValue = (String) agePropMapping.get("type");
//        assertTrue("testUpdateUnsupportedChanges", ageTypeValue.equals("long"));
//    }
//
//    @Test
//    public void testUpdateDynamicTemplateOverwrite() throws IOException, GetIndexException, InterruptedException
//    {
//        LOGGER.info("Running test 'testUpdateDynamicTemplateOverwrite'...");
//        final String templateName = "sample-template";
//        // This template has a dynamic_template called "EVERY_THING_ELSE_TEMPLATE"
//        final String origTemplateSourceFile = "/template5.json";
//        /* This template has a dynamic_template called "LONG_TEMPLATE"
//         * New simple prop STACK added to prop FAILURES
//         * New simple prop AGE added to prop PERSON
//         * New nested prop HOLD_DETAILS added
//         * New date prop DATE_DISPOSED added
//         */
//        final String updatedTemplateSourceFile = "/template6.json";
//        final String indexName = "test_dynsample-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{"
//            + "'TITLE':'doc1',"
//            + "'DATE_PROCESSED\":'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person1' }"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating indexes matching template {}", templateName);
//        updateIndex("testUpdateDynamicTemplateOverwrite", templateName);
//
//        // Verify updated index mapping has only one dynamic_template from the new index template
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testUpdateDynamicTemplateOverwrite", indexName);
//        Object dynamicTemplatesInTemplate = indexTypeMappings.get("dynamic_templates");
//        if (dynamicTemplatesInTemplate == null) {
//            fail();
//        } else {
//            @SuppressWarnings("unchecked")
//            final Map<String, Object> dynTemplate = (Map<String, Object>) ((List<Map<String, Object>>) dynamicTemplatesInTemplate).get(0);
//            assertTrue("testUpdateDynamicTemplateOverwrite", dynTemplate.size() == 1);
//            assertNotNull("testUpdateDynamicTemplateOverwrite", dynTemplate.get("LONG_TEMPLATE"));
//        }
//
//        // Index more data
//        request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("2")
//            .routing("1");
//        jsonString = "{"
//            + "'TITLE':'doc2',"
//            + "'DATE_PROCESSED':'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
//            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//
//        needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
//    }
//
//    @Test
//    public void testNoIndexesMatchingTemplate() throws IOException
//    {
//        LOGGER.info("Running test 'testNoIndexesMatchingTemplate'...");
//        final String templateName = ".kibana_task_manager";
//        /*
//         * This template has "index_patterns" that would not match any indexes
//         */
//        final String origTemplateSourceFile = "/template7.json";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testNoIndexesMatchingTemplate - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        // Try updating indexes for template that has no matching indexes
//        LOGGER.info("testNoIndexesMatchingTemplate - Updating indexes matching template {}", templateName);
//
//        updateIndex("testNoIndexesMatchingTemplate", templateName);
//    }
//
//    @Test
//    public void testUpdateIndexesOfUnSupportedChangesInTemplate() throws IOException, GetIndexException, InterruptedException
//    {
//        LOGGER.info("Running test 'testUpdateIndexesOfUnSupportedChangesInTemplate'...");
//        final String templateName = "sample-template";
//        final String origTemplateSourceFile = "/template8.json";
//        /*
//         * Dynamic templates removed
//         * 'strict' mapping introduced
//         * New simple prop STACK added to prop FAILURES
//         * New object prop MATCH added to prop ENTITIES
//         * New simple prop AGE added to prop PERSON
//         * New nested prop HOLD_DETAILS added
//         * New date prop DATE_DISPOSED added
//         * ID: ignore_malformed removed
//         * LANGUAGE_CODES: type changed to object, i.e. "type" removed, "include_in_parent" removed
//         * New nested prop added to existing field TARGET_REFERENCES/DESTINATION_ID
//         * TARGET_REFERENCES/TARGET_REFERENCE ignore_above and doc_values removed
//         */
//        final String updatedTemplateSourceFile = "/template9.json";
//        final String indexName = "foo-com_lang-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{"
//            + "'TITLE':'doc1',"
//            + "'DATE_PROCESSED\":'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person1' },"
//            + "'LANGUAGE_CODES':{ 'CODE':'en', 'CONFIDENCE': 100}"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Updating indexes matching template {}", templateName);
//        updateIndex("testUpdateIndexesOfUnSupportedChangesInTemplate", templateName);
//
//        // Verify index mapping has new properties
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testUpdateIndexesOfUnSupportedChangesInTemplate", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propMapping = (Map<String, Object>) props.get("DATE_DISPOSED");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", propMapping);
//
//        // Verify allowed field changes has updated field mapping
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> idPropMapping = (Map<String, Object>) props.get("ID");
//        LOGGER.info("idPropMapping {} ", idPropMapping);
//        final Object idPropValue = idPropMapping.get("ignore_malformed");
//        // Verify property mapping parameter was removed
//        assertNull("testUpdateIndexesOfUnSupportedChangesInTemplate", idPropValue);
//
//        // Verify index mapping of unsupported field changes has not changed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> langPropMapping = (Map<String, Object>) props.get("LANGUAGE_CODES");
//        final String propValue = (String) langPropMapping.get("type");
//        // Verify property mapping value is same as before
//        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", propValue.equals("nested"));
//
//        // Verify index mapping of unsupported field changes has been updated with allowed changes
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> targetRefPropMapping = (Map<String, Object>) props.get("TARGET_REFERENCES");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> targetRefProps = (Map<String, Object>) targetRefPropMapping.get("properties");
//        // Verify new property is added
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> tRefMapping = (Map<String, Object>) targetRefProps.get("TARGET_REFERENCE");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", tRefMapping);
//        // Verify unsupported change to nested property is not applied
//        final Boolean propDocValuesValue = (Boolean) tRefMapping.get("doc_values");
//        assertFalse("testUpdateIndexesOfUnSupportedChangesInTemplate", propDocValuesValue);
//        // Verify change to nested property is not applied, param not removed
//        final Integer propIgnoreAboveValue = (Integer) tRefMapping.get("ignore_above");
//        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", 10922 == propIgnoreAboveValue);
//        // Verify new nested property is added
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> destMapping = (Map<String, Object>) targetRefProps.get("DESTINATION_ID");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", destMapping);
//
//        // Index more data
//        request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("2")
//            .routing("1");
//        jsonString = "{"
//            + "'TITLE':'doc2',"
//            + "'DATE_PROCESSED':'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
//            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'},"
//            + "'LANGUAGE_CODES':{ 'CODE':'ko', 'CONFIDENCE': 100}"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//
//        try {
//            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
//            assertTrue(response.result().equals(Result.Created));
//        } catch (final OpenSearchException e) {
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
//    }
//
//    @Test
//    public void testUpdateIndexesWithNestedFieldChanges() throws IOException, GetIndexException, InterruptedException
//    {
//        LOGGER.info("Running test 'testUpdateIndexesWithNestedFieldChanges'...");
//        final String templateName = "sample-template";
//        final String origTemplateSourceFile = "/template10.json";
//        /*
//         * * Dynamic templates removed
//         * 'strict' mapping introduced
//         * New simple prop AGE added to prop PERSON
//         * New nested prop HOLD_DETAILS added
//         * ENTITIES/GRAMMAR_ID null_value param added
//         * New date type prop DATE_DISPOSED added
//         * 'store' and ignore_above param removed from nested property LANGUAGE_CODES/CODE
//         * 'store' param removed from nested property LANGUAGE_CODES/CONFIDENCE
//         * nested property type='nested' is removed from LANGUAGE_CODES
//         * include_in_parent param removed from LANGUAGE_CODES
//         */
//        final String updatedTemplateSourceFile = "/template11.json";
//        final String indexName = "jan_blue-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true," + "'PERSON':{ 'NAME':'person1' }" + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Updating indexes matching template {}", templateName);
//        updateIndex("testUpdateIndexesWithNestedFieldChanges", templateName);
//
//        // Verify index mapping has new properties
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testUpdateIndexesWithNestedFieldChanges", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propMapping = (Map<String, Object>) props.get("DATE_DISPOSED");
//        assertNotNull("testUpdateIndexesWithNestedFieldChanges", propMapping);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> entPropMapping = (Map<String, Object>) props.get("ENTITIES");
//        LOGGER.info("entitiesPropMapping {} ", entPropMapping);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> entitiesProps = (Map<String, Object>) entPropMapping.get("properties");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> gidMapping = (Map<String, Object>) entitiesProps.get("GRAMMAR_ID");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", gidMapping);
//        // Verify change to nested property is applied, param not added (change not allowed)
//        final Object gidProp1 = gidMapping.get("null_value");
//        assertNull("testUpdateIndexesOfUnSupportedChangesInTemplate", gidProp1);
//
//        // Verify index mapping of unsupported field changes to nested property has not changed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> langPropMapping = (Map<String, Object>) props.get("LANGUAGE_CODES");
//        final String propValue = (String) langPropMapping.get("type");
//        // Verify property mapping type and params are same as before
//        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", propValue.equals("nested"));
//        final Boolean propValue2 = (Boolean) langPropMapping.get("include_in_parent");
//        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", propValue2);
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> langProps = (Map<String, Object>) langPropMapping.get("properties");
//        // Verify new property is added
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> codeMapping = (Map<String, Object>) langProps.get("CODE");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", codeMapping);
//        // Verify unsupported change to nested property is not applied, param not removed
//        final Object prop1 = codeMapping.get("store");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", prop1);
//        // Verify change to nested property is not applied, param not removed
//        final Object prop2 = codeMapping.get("ignore_above");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", prop2);
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> confidenceMapping = (Map<String, Object>) langProps.get("CONFIDENCE");
//        // Verify unsupported change to nested property is not applied, param not removed
//        final Object confidenceProp1 = confidenceMapping.get("store");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", confidenceProp1);
//
//        // Index more data
//        request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("2")
//            .routing("1");
//        jsonString = "{"
//            + "'TITLE':'doc2',"
//            + "'DATE_PROCESSED':'2020-02-11',"
//            + "'CONTENT_PRIMARY':'just a test',"
//            + "'IS_HEAD_OF_FAMILY':true,"
//            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
//            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
//            + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//
//        try {
//            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
//            assertTrue(response.result().equals(Result.Created));
//        } catch (final OpenSearchException e) {
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
//    }
//
//    @Test
//    public void testAttemptRemoveUnchangeableProperty() throws Exception
//    {
//        LOGGER.info("Running test 'testAttemptRemoveUnchangeableProperty'...");
//        final String templateName = "sample-template";
//        final String origTemplateSourceFile = "/template12.json";
//        /*
//         * PROCESSING_TIME: format param removed
//         * ADD_PROCESSING_TIME: format param added
//         * nested field PROCESSING/ID: null_value param removed
//         * nested field PROCESSING/P_TIME: format param removed
//         * nested field PROCESSING/REF: ignore_malformed param removed
//         * nested field PROCESSING/CODE: ignore_malformed param added
//         */
//        final String updatedTemplateSourceFile = "/template13.json";
//        final String indexName = "test_blue-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testAttemptRemoveUnchangeableProperty - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testAttemptRemoveUnchangeableProperty - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
//                + "'IS_HEAD_OF_FAMILY':true," + "'PROCESSING_TIME': 1610098464" + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testAttemptRemoveUnchangeableProperty - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testAttemptRemoveUnchangeableProperty - Updating indexes matching template {}", templateName);
//        updateIndex("testAttemptRemoveUnchangeableProperty", templateName);
//
//        // Verify index mapping has new properties
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testAttemptRemoveUnchangeableProperty", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propMapping = (Map<String, Object>) props.get("PROCESSING_TIME");
//        // Verify param not removed (unsupported change)
//        assertTrue(propMapping.containsKey("format"));
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop2Mapping = (Map<String, Object>) props.get("ADD_PROCESSING_TIME");
//        // Verify param not added (unsupported change)
//        assertFalse(prop2Mapping.containsKey("format"));
//
//        // Verify index mapping of unsupported field changes has been updated with allowed changes
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> processingPropMapping = (Map<String, Object>) props.get("PROCESSING");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> processingProps = (Map<String, Object>) processingPropMapping.get("properties");
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propIdMapping = (Map<String, Object>) processingProps.get("ID");
//        // Verify param not removed (change not allowed)
//        assertTrue(propIdMapping.containsKey("null_value"));
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> pTimeMapping = (Map<String, Object>) processingProps.get("P_TIME");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", pTimeMapping);
//        // Verify unsupported change to nested property is not applied, param not removed
//        final Object propFormatValue = pTimeMapping.get("format");
//        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", propFormatValue);
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> refMapping = (Map<String, Object>) processingProps.get("REF");
//        // Verify change to nested property is applied, param removed
//        final Object propIgnoreMalformed = refMapping.get("ignore_malformed");
//        assertNull("testUpdateIndexesOfUnSupportedChangesInTemplate", propIgnoreMalformed);
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> codeMapping = (Map<String, Object>) processingProps.get("CODE");
//        // Verify change to nested property is applied, param added
//        final Boolean propIgnoreMalformed2 = (Boolean) codeMapping.get("ignore_malformed");
//        assertFalse("testUpdateIndexesOfUnSupportedChangesInTemplate", propIgnoreMalformed2);
//
//    }
//
//    @Test
//    public void testRemoveParams() throws Exception
//    {
//        LOGGER.info("Running test 'testRemoveParams'...");
//        final String templateName = "prop-rem-template";
//        final String origTemplateSourceFile = "/template14.json";
//        final String updatedTemplateSourceFile = "/template15.json";
//        final String indexName = "test_violet-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testRemoveParams - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testRemoveParams - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{" + "'message':'doc1'," + "'status_code\":'complete'," + "'session_id':'44gdfg67',"
//                + "'textdata1':'some text data'," + "'number_two': 16100" + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testRemoveParams - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testRemoveParams - Updating indexes matching template {}", templateName);
//        updateIndex("testRemoveParams", templateName);
//
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testRemoveParams", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//
//        // Verify params are not removed even though removal is allowed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop10Mapping = (Map<String, Object>) props.get("title_y");
//        assertFalse(prop10Mapping.containsKey("boost")); // has been removed
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop1Mapping = (Map<String, Object>) props.get("number_two");
//        assertFalse(prop1Mapping.containsKey("coerce")); // has been removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop16Mapping = (Map<String, Object>) props.get("first_name");
//        assertTrue(prop16Mapping.containsKey("copy_to"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop17Mapping = (Map<String, Object>) props.get("tags");
//        assertFalse(prop17Mapping.containsKey("eager_global_ordinals")); // has been removed
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop18Mapping = (Map<String, Object>) props.get("titlew");
//        assertFalse(prop18Mapping.containsKey("fielddata")); // has been removed
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop19Mapping = (Map<String, Object>) props.get("city");
//        assertTrue(prop19Mapping.containsKey("fields"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop3Mapping = (Map<String, Object>) props.get("message");
//        assertFalse(prop3Mapping.containsKey("ignore_above")); // has been removed
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop4Mapping = (Map<String, Object>) props.get("number_one");
//        assertFalse(prop4Mapping.containsKey("ignore_malformed")); // has been removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop20Mapping = (Map<String, Object>) props.get("dummy_message2");
//        assertTrue(prop20Mapping.containsKey("index_options")); // has not been removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop24Mapping = (Map<String, Object>) props.get("latency");
//        assertTrue(prop24Mapping.containsKey("meta"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop5Mapping = (Map<String, Object>) props.get("status_code");
//        assertTrue(prop5Mapping.containsKey("null_value")); // not removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop27Mapping = (Map<String, Object>) props.get("names2");
//        assertTrue(prop27Mapping.containsKey("position_increment_gap"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop28Mapping = (Map<String, Object>) props.get("manager");
//        assertTrue(prop28Mapping.containsKey("properties"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop6Mapping = (Map<String, Object>) props.get("title_z");
//        assertTrue(prop6Mapping.containsKey("search_analyzer"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop7Mapping = (Map<String, Object>) props.get("title_m");
//        assertTrue(prop7Mapping.containsKey("search_quote_analyzer"));
//
//        // Verify params not removed for those that are not allowed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop8Mapping = (Map<String, Object>) props.get("session_id");
//        assertTrue(prop8Mapping.containsKey("doc_values"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop9Mapping = (Map<String, Object>) props.get("some_content");
//        assertTrue(prop9Mapping.containsKey("store"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop11Mapping = (Map<String, Object>) props.get("session_data");
//        assertTrue(prop11Mapping.containsKey("enabled"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop12Mapping = (Map<String, Object>) props.get("date");
//        assertTrue(prop12Mapping.containsKey("format"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop13Mapping = (Map<String, Object>) props.get("boolean_sim_field");
//        assertTrue(prop13Mapping.containsKey("similarity"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop14Mapping = (Map<String, Object>) props.get("some_content2");
//        assertTrue(prop14Mapping.containsKey("term_vector"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop15Mapping = (Map<String, Object>) props.get("title_m");
//        assertTrue(prop15Mapping.containsKey("analyzer"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop21Mapping = (Map<String, Object>) props.get("textdata1");
//        assertTrue(prop21Mapping.containsKey("index_phrases"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop22Mapping = (Map<String, Object>) props.get("body_text");
//        assertTrue(prop22Mapping.containsKey("index_prefixes"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop23Mapping = (Map<String, Object>) props.get("dummy_message");
//        assertTrue(prop23Mapping.containsKey("index"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop25Mapping = (Map<String, Object>) props.get("foo");
//        assertTrue(prop25Mapping.containsKey("normalizer"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop26Mapping = (Map<String, Object>) props.get("titlex");
//        assertTrue(prop26Mapping.containsKey("norms"));
//
//    }
//
//    @Test
//    public void testAddParams() throws Exception
//    {
//        LOGGER.info("Running test 'testAddParams'...");
//        final String templateName = "prop-add-template";
//        final String origTemplateSourceFile = "/template16.json";
//        final String updatedTemplateSourceFile = "/template17.json";
//        final String indexName = "test_pink-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testAddParams - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testAddParams - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{" + "'message':'doc1'," + "'status_code\":'complete'," + "'session_id':'44gdfg67',"
//                + "'textdata1':'some text data'," + "'number_two': 16100" + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testAddParams - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testAddParams - Updating indexes matching template {}", templateName);
//        updateIndex("testAddParams", templateName);
//
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testAddParams", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//
//        // Verify params are added when addition is allowed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop10Mapping = (Map<String, Object>) props.get("title_y");
//        assertTrue(prop10Mapping.containsKey("boost"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop1Mapping = (Map<String, Object>) props.get("number_two");
//        assertTrue(prop1Mapping.containsKey("coerce"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop16Mapping = (Map<String, Object>) props.get("first_name");
//        assertTrue(prop16Mapping.containsKey("copy_to"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop17Mapping = (Map<String, Object>) props.get("tags");
//        assertTrue(prop17Mapping.containsKey("eager_global_ordinals"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop18Mapping = (Map<String, Object>) props.get("titlew");
//        assertTrue(prop18Mapping.containsKey("fielddata"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop19Mapping = (Map<String, Object>) props.get("city");
//        assertFalse(prop19Mapping.containsKey("fields")); // has not been added
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop3Mapping = (Map<String, Object>) props.get("message");
//        assertTrue(prop3Mapping.containsKey("ignore_above"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop4Mapping = (Map<String, Object>) props.get("number_one");
//        assertTrue(prop4Mapping.containsKey("ignore_malformed"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop20Mapping = (Map<String, Object>) props.get("dummy_message2");
//        assertFalse(prop20Mapping.containsKey("index_options")); // has not been added, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop24Mapping = (Map<String, Object>) props.get("latency");
//        assertFalse(prop24Mapping.containsKey("meta")); // has not been added
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop5Mapping = (Map<String, Object>) props.get("status_code");
//        assertFalse(prop5Mapping.containsKey("null_value")); // not added, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop27Mapping = (Map<String, Object>) props.get("names2");
//        assertFalse(prop27Mapping.containsKey("position_increment_gap")); // not added, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop28Mapping = (Map<String, Object>) props.get("manager");
//        assertFalse(prop28Mapping.containsKey("properties")); // has not been added
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop6Mapping = (Map<String, Object>) props.get("title_z");
//        assertTrue(prop6Mapping.containsKey("search_analyzer"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop7Mapping = (Map<String, Object>) props.get("title_m");
//        assertFalse(prop7Mapping.containsKey("search_quote_analyzer")); // has not been added
//
//        // Verify params not added for those that are not allowed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop8Mapping = (Map<String, Object>) props.get("session_id");
//        assertFalse(prop8Mapping.containsKey("doc_values"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop9Mapping = (Map<String, Object>) props.get("some_content");
//        assertFalse(prop9Mapping.containsKey("store"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop11Mapping = (Map<String, Object>) props.get("session_data");
//        assertFalse(prop11Mapping.containsKey("enabled"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop12Mapping = (Map<String, Object>) props.get("date");
//        assertFalse(prop12Mapping.containsKey("format"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop13Mapping = (Map<String, Object>) props.get("boolean_sim_field");
//        assertFalse(prop13Mapping.containsKey("similarity"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop14Mapping = (Map<String, Object>) props.get("some_content2");
//        assertFalse(prop14Mapping.containsKey("term_vector"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop15Mapping = (Map<String, Object>) props.get("title_m");
//        assertFalse(prop15Mapping.containsKey("analyzer"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop21Mapping = (Map<String, Object>) props.get("textdata1");
//        assertFalse(prop21Mapping.containsKey("index_phrases"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop22Mapping = (Map<String, Object>) props.get("body_text");
//        assertFalse(prop22Mapping.containsKey("index_prefixes"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop23Mapping = (Map<String, Object>) props.get("dummy_message");
//        assertFalse(prop23Mapping.containsKey("index"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop25Mapping = (Map<String, Object>) props.get("foo");
//        assertFalse(prop25Mapping.containsKey("normalizer"));
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop26Mapping = (Map<String, Object>) props.get("titlex");
//        assertFalse(prop26Mapping.containsKey("norms"));
//
//    }
//
//    @Test
//    public void testAddRemoveParams() throws Exception
//    {
//        LOGGER.info("Running test 'testAddRemoveParams'...");
//        final String templateName = "prop-add-template";
//        final String origTemplateSourceFile = "/template18.json";
//        final String updatedTemplateSourceFile = "/template19.json";
//        final String indexName = "test_green-000001";
//
//        final String origTemplateSource = readFile(origTemplateSourceFile);
//        LOGGER.info("testAddRemoveParams - Creating initial template {}", templateName);
//
//        // Create a template
//        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse putTemplateResponse = client.indices().putIndexTemplate(trequest);
//        if (!putTemplateResponse.acknowledged()) {
//            fail();
//        }
//        LOGGER.info("testAddRemoveParams - Creating index matching template {}", templateName);
//        // Create an index with some data
//        IndexRequest.Builder<JsonData> request = new IndexRequest.Builder<JsonData>()
//            .index(indexName)
//            .id("1")
//            .routing("1");
//        String jsonString = "{" + "'some_content2':'doc1'" + "}";
//        jsonString = jsonString.replaceAll("'", "\"");
//        request.source(jsonString, XContentType.JSON);
//        request.refresh(Refresh.True);
//        final boolean needsRetries = indexDocumentWithRetry(request.build());
//        if (needsRetries) {
//            // Indexing has failed after multiple retries
//            fail();
//        }
//
//        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
//
//        LOGGER.info("testAddRemoveParams - Updating template {}", templateName);
//        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
//        // Create a template
//        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest.Builder()
//            .name(templateName)
//            //figure out source
//            .build();
//            
//        final PutIndexTemplateResponse updateTemplateResponse = client.indices().putIndexTemplate(utrequest);
//        if (!updateTemplateResponse.acknowledged()) {
//            fail();
//        }
//
//        LOGGER.info("testAddRemoveParams - Updating indexes matching template {}", templateName);
//        updateIndex("testAddRemoveParams", templateName);
//
//        final Map<String, Object> indexTypeMappings = getIndexMapping("testAddRemoveParams", indexName);
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> props = (Map<String, Object>) indexTypeMappings.get("properties");
//
//        // Verify params are added when addition is allowed
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propProcessing = (Map<String, Object>) props.get("PROCESSING");
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propProcessingMappings = (Map<String, Object>) propProcessing.get("properties");
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propOtherProps = (Map<String, Object>) propProcessingMappings.get("OTHER_PROPS");
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propOtherPropsMappings = (Map<String, Object>) propOtherProps.get("properties");
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop10Mapping = (Map<String, Object>) propOtherPropsMappings.get("status_code");
//        assertFalse(prop10Mapping.containsKey("null_value")); // not added, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop11Mapping = (Map<String, Object>) propOtherPropsMappings.get("BODY_TEXT");
//        assertTrue(prop11Mapping.containsKey("index_prefixes")); // not removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop12Mapping = (Map<String, Object>) propOtherPropsMappings.get("REF");
//        assertFalse(prop12Mapping.containsKey("ignore_malformed")); // removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> prop13Mapping = (Map<String, Object>) propOtherPropsMappings.get("some_date");
//        assertFalse(prop13Mapping.containsKey("format")); // not added, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propId = (Map<String, Object>) propProcessingMappings.get("ID");
//        assertFalse(propId.containsKey("eager_global_ordinals")); // not added, unexpected (can be removed but not added?)
//        assertTrue(propId.containsKey("ignore_above"));
//        assertTrue(propId.containsKey("null_value")); // not removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propLang = (Map<String, Object>) props.get("LANGUAGE_CODES");
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propLangMappings = (Map<String, Object>) propLang.get("properties");
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propCodeMapping = (Map<String, Object>) propLangMappings.get("CODE");
//        assertFalse(propCodeMapping.containsKey("ignore_above")); // removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propConfMapping = (Map<String, Object>) propLangMappings.get("CONFIDENCE");
//        assertTrue(propConfMapping.containsKey("normalizer")); // not removed, as expected
//
//        @SuppressWarnings("unchecked")
//        final Map<String, Object> propTxtMapping = (Map<String, Object>) propLangMappings.get("textdata1");
//        assertFalse(propTxtMapping.containsKey("index_phrases")); // not added, as expected
//
//    }
//
//    private void updateIndex(final String testName, final String templateName)
//    {
//        LOGGER.info("{}: {}", testName, templateName);
//        try {
//            ElasticMappingUpdater.update(false, host, protocol, port, username, password, connectTimeout,
//                                         socketTimeout);
//        } catch (final IOException | UnexpectedResponseException | GetIndexException | GetTemplatesException e) {
//            LOGGER.error(testName, e);
//            fail(testName + ":" + e);
//        }
//    }
//
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
//
//    private void verifyIndexData(final String indexName, final Query query, final long expectedHitCount) throws IOException
//    {
//        final SearchRequest searchRequest = new SearchRequest.Builder()
//            .index(indexName)
//            .trackTotalHits(TrackHits.of(x -> x.enabled(Boolean.TRUE)))
//            .query(query)
//            .build();
//        
//        SearchResponse<JsonData> searchResponse = client.search(searchRequest, JsonData.class);
//        final long totalDocs = searchResponse.hits().total().value();
//        LOGGER.info("Hits : {}", totalDocs);
//        LOGGER.info(searchResponse.toString());
//        assertTrue("Got test document", totalDocs == expectedHitCount);
//
//        final List<Hit<JsonData>> searchHits = searchResponse.hits().hits();
//        for (final Hit<JsonData> hit : searchHits) {
//            final String sourceAsMap = hit.source().to(String.class, new JacksonJsonpMapper());
//            LOGGER.info("hit : {}", sourceAsMap);
//        }
//    }
//
//    private TypeMapping getIndexMapping(final String testName, final String indexName) throws IOException, GetIndexException
//    {
//        LOGGER.info("{} - Get index {}", testName, indexName);
//        final Request request = new Request("GET", "/" + UrlEscapers.urlPathSegmentEscaper().escape(indexName));
//        final Response response = transport.restClient().performRequest(request);
//
//        final int statusCode = response.getStatusLine().getStatusCode();
//        if (statusCode == 200) {
//            try (final InputStream resultJsonStream = response.getEntity().getContent();
//                 final JsonParser jsonValueParser = Json.createParser(resultJsonStream)) {
//                final GetIndexResponse getIndexResponse = GetIndexResponse._DESERIALIZER.deserialize(jsonValueParser, 
//                                                                                                     new JacksonJsonpMapper());
//                final IndexState indexMappings = getIndexResponse.result().get(indexName);
//                final TypeMapping indexTypeMappings = indexMappings.mappings();
//                LOGGER.info("{}------Updated mapping for index '{}': {}", testName, indexName, indexTypeMappings);
//                return indexTypeMappings;
//            }
//        } else {
//            throw new GetIndexException(String.format("Error getting index '%s'. Status code: %s, response: %s",
//                                                      indexName, statusCode, EntityUtils.toString(response.getEntity())));
//        }
//    }
//
//    private boolean indexDocumentWithRetry(final IndexRequest request) throws InterruptedException
//    {
//        boolean retry = true;
//        for (int i = 0; i < 3; i++) {
//            retry = indexDocument(request);
//            if (!retry) {
//                break;
//            }
//            Thread.sleep(3000);
//        }
//        return retry;
//    }
//
//    private boolean indexDocument(final IndexRequest<JsonData> request)
//    {
//        try {
//            final IndexResponse response = client.index(request);
//            assertTrue(response.result().equals(Result.Created));
//        } catch (final IOException ex) {
//            return isServiceUnAvailableException(ex);
//        }
//        return false;
//    }
//
//    private static boolean isServiceUnAvailableException(final Exception ex)
//    {
//        final Throwable cause = ex.getCause();
//
//        if (cause instanceof Exception && cause != ex && isServiceUnAvailableException((Exception) cause)) {
//            return true;
//        }
//
//        if (ex instanceof ConnectException) {
//            // Thrown to signal that an error occurred while attempting to connect a socket to a remote address and port.
//            // Typically, the connection was refused remotely (e.g., no process is listening on the remote address/port)
//            return true;
//        }
//        if (ex instanceof SocketException) {
//            // Thrown to indicate that there is an error creating or accessing a Socket.
//            return true;
//        }
//        if (ex instanceof SocketTimeoutException) {
//            // Signals that a timeout has occurred on a socket read or accept.
//            return true;
//        }
//        if (ex instanceof UnknownHostException) {
//            // There is a possibility of IP address of host could not be determined because of some network issue.
//            // This can be treated as transient
//            return true;
//        }
//        if (ex instanceof HttpRetryException) {
//            // Thrown to indicate that a HTTP request needs to be retried but cannot be retried automatically, due to streaming
//            // mode being enabled.
//            return true;
//        }
//        if (ex instanceof ConnectTimeoutException) {
//            // ConnectionPoolTimeoutException < ConnectTimeoutException
//            return true;
//        }
//        return false;
//    }
}
