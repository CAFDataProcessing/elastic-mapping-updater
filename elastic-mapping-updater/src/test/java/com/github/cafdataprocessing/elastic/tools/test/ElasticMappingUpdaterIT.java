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

import com.fasterxml.jackson.core.JsonFactory;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;
import org.opensearch.client.Request;
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
import jakarta.json.stream.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.apache.http.conn.ConnectTimeoutException;
import static org.junit.Assert.assertFalse;
import org.junit.Before;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson.JacksonJsonpGenerator;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch._types.mapping.DynamicTemplate;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TrackHits;
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
        protocol = System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_PROTOCOL");
        host = System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_HOSTNAMES");
        port = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_REST_PORT"));
        username = "";
        password = "";
        connectTimeout = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_CONNECT_TIMEOUT"));
        socketTimeout = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_SOCKET_TIMEOUT"));

        transport = new RestClientTransport(RestClient.builder(new HttpHost(host, port, protocol)).build(), new JacksonJsonpMapper());
        client = new OpenSearchClient(transport);
    }
    
    private void deleteAllIndexTemplates() throws IOException{
        LOGGER.info("Deleting all index templates");
        final RestClient restClient = transport.restClient();
        final String endpoint = "_template/*";
        final Request request = new Request("DELETE", endpoint);
        final Response response = restClient.performRequest(request);
        
        if (response.getStatusLine().getStatusCode() != 200) {
            fail();
        }
    }
    
    private void putIndexTemplate(final String templateName, final String fileLocation) throws IOException
    {
        final String origTemplateSource = readFile(fileLocation);

        final RestClient restClient = transport.restClient();
        final String endpoint = "_template/" + UrlEscapers.urlPathSegmentEscaper().escape(templateName);
        final Request request = new Request("PUT", endpoint);
        final StringBuilder jsonMapping = new StringBuilder();

        jsonMapping.append(origTemplateSource);
        request.setJsonEntity(jsonMapping.toString());
        final Response response = restClient.performRequest(request);

        if (response.getStatusLine().getStatusCode() != 200) {
            fail();
        }
    }
    
    private void createIndex(final String indexName, final String id, final String routing, final String document)
        throws InterruptedException
    {
        try (final JsonParser jsonValueParser = Json.createParser(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)))) {
            final JsonData jsonData = JsonData.from(jsonValueParser, new JacksonJsonpMapper());
            // Create an index with some data
            final IndexRequest<JsonData> request = new IndexRequest.Builder<JsonData>()
                .index(indexName)
                .id(id)
                .routing(routing)
                .document(jsonData)
                .refresh(Refresh.True)
                .build();

            final boolean needsRetries = indexDocumentWithRetry(request);
            if (needsRetries) {
                // Indexing has failed after multiple retries
                fail();
            }
        }
    }
    
    @Before
    public void init() throws IOException{
        deleteAllIndexTemplates();
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

        putIndexTemplate(templateName, origTemplateSourceFile);
        
        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Creating index matching template {}", templateName);
       
        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true," + "'PERSON':{ 'NAME':'person1' }" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);
        
        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);
        
        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating template {}", templateName);
        
        putIndexTemplate(templateName, updatedTemplateSourceFile);
        
        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateIndexesOfUpdatedTemplate", templateName);

        // Verify index mapping has new properties
        final TypeMapping indexTypeMappings = getIndexMapping("testUpdateIndexesOfUpdatedTemplate", indexName);
        
        // Verify new prop DATE_DISPOSED was added
        final Property dateDisposed = indexTypeMappings.properties().get("DATE_DISPOSED");
        
        assertNotNull("testUpdateIndexesOfUpdatedTemplate", dateDisposed);
        
        jsonString = "{"
            + "'TITLE':'doc2',"
            + "'DATE_PROCESSED':'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
         // Index more data
        createIndex(indexName, "2", "1", jsonString);
        
        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
    }

    @Test
    public void testUpdateUnsupportedChanges() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateUnsupportedChanges'...");
        final String templateName = "acme-sample-template";
        final String origTemplateSourceFile = "/template3.json";
        /*
         * Dynamic templates removed
         * 'strict' mapping introduced
         * New simple prop STACK added to prop FAILURES
         * New simple prop AGE added to prop PERSON
         * New nested prop HOLD_DETAILS added
         * New date prop DATE_DISPOSED added
         * This template has modified "type" param for IS_HEAD_OF_FAMILY and FAILURES/AJP_JOB_RUN_ID
         * IS_HEAD_OF_FAMILY: "ignore_malformed" added
         * FAILURES/AJP_JOB_RUN_ID: "ignore_above" removed and ignore_malformed added
         * LANGUAGE_CODES: type set to nested (changed from object), "include_in_parent" added
         */
        final String unsupportedTemplateSourceFile = "/template4.json";
        final String indexName = "test_acmesample-000001";

        LOGGER.info("testUpdateUnsupportedChanges - Creating initial template {}", templateName);

        putIndexTemplate(templateName, origTemplateSourceFile);
        
        LOGGER.info("testUpdateUnsupportedChanges - Creating index matching template {}", templateName);
        
        String jsonString = "{"
            + "'TITLE':'doc1',"
            + "'DATE_PROCESSED\":'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person1' }"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testUpdateUnsupportedChanges - Updating template {}", templateName);
        
        putIndexTemplate(templateName, unsupportedTemplateSourceFile);

        LOGGER.info("testUpdateUnsupportedChanges - Updating indexes matching template {}", templateName);
        // Unsupported mapping changes not applied to Index mapping
        updateIndex("testUpdateUnsupportedChanges", templateName);

        // Verify index mapping of unsupported field changes has not changed
        final TypeMapping indexTypeMappings = getIndexMapping("testUpdateUnsupportedChanges", indexName);
        
        final Property headOfFamilyProp = indexTypeMappings.properties().get("IS_HEAD_OF_FAMILY");
        // Verify property mapping value is same as before
        assertTrue("testUpdateUnsupportedChanges", headOfFamilyProp._kind().toString().equals("Boolean"));
        final Object propIgnoreMalformed = headOfFamilyProp.boolean_().properties().get("ignore_malformed");
        // Verify new param was not added
        assertNull("testUpdateUnsupportedChanges", propIgnoreMalformed);

        final Property failuresProp = indexTypeMappings.properties().get("FAILURES");
        final Property jrIdProp = failuresProp.object().properties().get("AJP_JOB_RUN_ID");
        // Verify type is same as before
        assertTrue("testUpdateUnsupportedChanges", jrIdProp._kind().toString().equals("Keyword"));
        // Verify param not removed
        final Object propjrIdIgnoreAbove = jrIdProp.keyword().ignoreAbove();
        assertNotNull("testUpdateUnsupportedChanges", propjrIdIgnoreAbove);
        // Verify param not added
        final Object propjrIdIgnoreMalformed = jrIdProp.keyword().properties().get("ignore_malformed");
        assertNull("testUpdateUnsupportedChanges", propjrIdIgnoreMalformed);

        final Property propLC =  indexTypeMappings.properties().get("LANGUAGE_CODES");
        // Verify type was not changed
        assertTrue("testUpdateUnsupportedChanges", propLC._kind().toString().equals("Object"));
        final Object propLCIncludeInParent = propLC.object().properties().get("include_in_parent");
        // Verify new param was not added
        assertNull("testUpdateUnsupportedChanges", propLCIncludeInParent);

        // Verify index mapping of allowed field changes has been updated
        final Property personProp = indexTypeMappings.properties().get("PERSON");
        final Property ageProp = personProp.object().properties().get("AGE");
        assertTrue("testUpdateUnsupportedChanges", ageProp._kind().toString().equals("Long"));
    }

    @Test
    public void testUpdateDynamicTemplateOverwrite() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateDynamicTemplateOverwrite'...");
        final String templateName = "sample-template";
        // This template has a dynamic_template called "EVERY_THING_ELSE_TEMPLATE"
        final String origTemplateSourceFile = "/template5.json";
        /* This template has a dynamic_template called "LONG_TEMPLATE"
         * New simple prop STACK added to prop FAILURES
         * New simple prop AGE added to prop PERSON
         * New nested prop HOLD_DETAILS added
         * New date prop DATE_DISPOSED added
         */
        final String updatedTemplateSourceFile = "/template6.json";
        final String indexName = "test_dynsample-000001";

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);
            
        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{"
            + "'TITLE':'doc1',"
            + "'DATE_PROCESSED\":'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person1' }"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);
        
        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating indexes matching template {}", templateName);
        
        updateIndex("testUpdateDynamicTemplateOverwrite", templateName);

        // Verify updated index mapping has only one dynamic_template from the new index template
        final TypeMapping indexTypeMappings = getIndexMapping("testUpdateDynamicTemplateOverwrite", indexName);
        
        final List<Map<String, DynamicTemplate>> dynamicTemplatesInTemplate = indexTypeMappings.dynamicTemplates();
        if (dynamicTemplatesInTemplate == null) {
            fail();
        } else {
            final Map<String, DynamicTemplate> dynTemplate = dynamicTemplatesInTemplate.get(0);
            assertTrue("testUpdateDynamicTemplateOverwrite", dynamicTemplatesInTemplate.size() == 1);
            assertNotNull("testUpdateDynamicTemplateOverwrite", dynTemplate.get("LONG_TEMPLATE"));
        }

        // Index more data
        jsonString = "{"
            + "'TITLE':'doc2',"
            + "'DATE_PROCESSED':'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "2", "1", jsonString);
        
        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
    }

    @Test
    public void testNoIndexesMatchingTemplate() throws IOException
    {
        LOGGER.info("Running test 'testNoIndexesMatchingTemplate'...");
        final String templateName = ".kibana_task_manager";
        /*
         * This template has "index_patterns" that would not match any indexes
         */
        final String origTemplateSourceFile = "/template7.json";

        LOGGER.info("testNoIndexesMatchingTemplate - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);

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
        /*
         * Dynamic templates removed
         * 'strict' mapping introduced
         * New simple prop STACK added to prop FAILURES
         * New object prop MATCH added to prop ENTITIES
         * New simple prop AGE added to prop PERSON
         * New nested prop HOLD_DETAILS added
         * New date prop DATE_DISPOSED added
         * ID: ignore_malformed removed
         * LANGUAGE_CODES: type changed to object, i.e. "type" removed, "include_in_parent" removed
         * New nested prop added to existing field TARGET_REFERENCES/DESTINATION_ID
         * TARGET_REFERENCES/TARGET_REFERENCE ignore_above and doc_values removed
         */
        final String updatedTemplateSourceFile = "/template9.json";
        final String indexName = "foo-com_lang-000001";

        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);

        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{"
            + "'TITLE':'doc1',"
            + "'DATE_PROCESSED\":'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person1' },"
            + "'LANGUAGE_CODES':{ 'CODE':'en', 'CONFIDENCE': 100}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);
        
        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testUpdateIndexesOfUnSupportedChangesInTemplate - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateIndexesOfUnSupportedChangesInTemplate", templateName);

        // Verify index mapping has new properties
        final TypeMapping indexTypeMappings = getIndexMapping("testUpdateIndexesOfUnSupportedChangesInTemplate", indexName);
        
        final Map<String, Property> props = indexTypeMappings.properties();
        final Property dateDisposedProp = props.get("DATE_DISPOSED");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", dateDisposedProp);

        // Verify allowed field changes has updated field mapping
        final Property idProp =  props.get("ID");
        LOGGER.info("idProp {} ", idProp);
        final Object idPropValue = idProp.long_().properties().get("ignore_malformed");
        // Verify property mapping parameter was removed
        assertNull("testUpdateIndexesOfUnSupportedChangesInTemplate", idPropValue);

        // Verify index mapping of unsupported field changes has not changed
        final Property langProp = props.get("LANGUAGE_CODES");
        // Verify property mapping value is same as before
        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", langProp._kind().toString().equals("Nested"));

        // Verify index mapping of unsupported field changes has been updated with allowed changes
        final Property targetRefProp = props.get("TARGET_REFERENCES");
        final Map<String, Property> targetRefProps = targetRefProp.object().properties();
        // Verify new property is added
        final Property tRefMapping = targetRefProps.get("TARGET_REFERENCE");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", tRefMapping);
        // Verify unsupported change to nested property is not applied
        final Boolean propDocValuesValue = tRefMapping.keyword().docValues();
        assertFalse("testUpdateIndexesOfUnSupportedChangesInTemplate", propDocValuesValue);
        // Verify change to nested property is not applied, param not removed
        final Integer propIgnoreAboveValue = tRefMapping.keyword().ignoreAbove();
        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", 10922 == propIgnoreAboveValue);
        // Verify new nested property is added
        final Property destProp = targetRefProps.get("DESTINATION_ID");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", destProp);

        // Index more data
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
        createIndex(indexName, "2", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
    }

    @Test
    public void testUpdateIndexesWithNestedFieldChanges() throws IOException, GetIndexException, InterruptedException
    {
        LOGGER.info("Running test 'testUpdateIndexesWithNestedFieldChanges'...");
        final String templateName = "sample-template";
        final String origTemplateSourceFile = "/template10.json";
        /*
         * * Dynamic templates removed
         * 'strict' mapping introduced
         * New simple prop AGE added to prop PERSON
         * New nested prop HOLD_DETAILS added
         * ENTITIES/GRAMMAR_ID null_value param added
         * New date type prop DATE_DISPOSED added
         * 'store' and ignore_above param removed from nested property LANGUAGE_CODES/CODE
         * 'store' param removed from nested property LANGUAGE_CODES/CONFIDENCE
         * nested property type='nested' is removed from LANGUAGE_CODES
         * include_in_parent param removed from LANGUAGE_CODES
         */
        final String updatedTemplateSourceFile = "/template11.json";
        final String indexName = "jan_blue-000001";

        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);
            
        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true," + "'PERSON':{ 'NAME':'person1' }" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);
       
        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testUpdateIndexesWithNestedFieldChanges - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateIndexesWithNestedFieldChanges", templateName);

        // Verify index mapping has new properties
        final TypeMapping indexTypeMappings = getIndexMapping("testUpdateIndexesWithNestedFieldChanges", indexName);
        final Map<String, Property> props =  indexTypeMappings.properties();
        
        final Property dateDisposedProp =  props.get("DATE_DISPOSED");
        assertNotNull("testUpdateIndexesWithNestedFieldChanges", dateDisposedProp);
        
        final Property entitiesProp = props.get("ENTITIES");
        LOGGER.info("entitiesPropMapping {} ", entitiesProp);
        final Map<String, Property> entitiesProps = entitiesProp.object().properties();
        final Property grammarIdProp =  entitiesProps.get("GRAMMAR_ID");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", grammarIdProp);
        // Verify change to nested property is applied, param not added (change not allowed)
        final Object nullValueProp = grammarIdProp.keyword().nullValue();
        assertNull("testUpdateIndexesOfUnSupportedChangesInTemplate", nullValueProp);

        // Verify index mapping of unsupported field changes to nested property has not changed
        final Property langProp = props.get("LANGUAGE_CODES");
        // Verify property mapping type and params are same as before
        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", langProp._kind().toString().equals("Nested"));
        final Boolean includeInParentProp = langProp.nested().includeInParent();
        assertTrue("testUpdateIndexesOfUnSupportedChangesInTemplate", includeInParentProp);

        final Map<String, Property> langProps = langProp.nested().properties();
        // Verify new property is added
        final Property codeProp = langProps.get("CODE");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", codeProp);
        // Verify unsupported change to nested property is not applied, param not removed
        final Object codeStoreProp = codeProp.keyword().store();
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", codeStoreProp);
        // Verify change to nested property is not applied, param not removed
        final Object ignoreAboveProp = codeProp.keyword().ignoreAbove();
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", ignoreAboveProp);

        final Property confidenceProp = langProps.get("CONFIDENCE");
        // Verify unsupported change to nested property is not applied, param not removed
        final Object confidenceStoreProp = confidenceProp.double_().store();
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", confidenceStoreProp);

        // Index more data
        jsonString = "{"
            + "'TITLE':'doc2',"
            + "'DATE_PROCESSED':'2020-02-11',"
            + "'CONTENT_PRIMARY':'just a test',"
            + "'IS_HEAD_OF_FAMILY':true,"
            + "'PERSON':{ 'NAME':'person2', 'AGE':5 },"
            + "'HOLD_DETAILS': {'FIRST_HELD_DATE':'2020-02-11', 'HOLD_HISTORY': '2020-02-11', 'HOLD_ID': '12'}"
            + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "2", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 2);
    }

    @Test
    public void testAttemptRemoveUnchangeableProperty() throws Exception
    {
        LOGGER.info("Running test 'testAttemptRemoveUnchangeableProperty'...");
        final String templateName = "sample-template";
        final String origTemplateSourceFile = "/template12.json";
        /*
         * PROCESSING_TIME: format param removed
         * ADD_PROCESSING_TIME: format param added
         * nested field PROCESSING/ID: null_value param removed
         * nested field PROCESSING/P_TIME: format param removed
         * nested field PROCESSING/REF: ignore_malformed param removed
         * nested field PROCESSING/CODE: ignore_malformed param added
         */
        final String updatedTemplateSourceFile = "/template13.json";
        final String indexName = "test_blue-000001";

        LOGGER.info("testAttemptRemoveUnchangeableProperty - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);
            
        LOGGER.info("testAttemptRemoveUnchangeableProperty - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{" + "'TITLE':'doc1'," + "'DATE_PROCESSED\":'2020-02-11'," + "'CONTENT_PRIMARY':'just a test',"
                + "'IS_HEAD_OF_FAMILY':true," + "'PROCESSING_TIME': 1610098464" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testAttemptRemoveUnchangeableProperty - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testAttemptRemoveUnchangeableProperty - Updating indexes matching template {}", templateName);
        updateIndex("testAttemptRemoveUnchangeableProperty", templateName);

        // Verify index mapping has new properties
        final TypeMapping indexTypeMappings = getIndexMapping("testAttemptRemoveUnchangeableProperty", indexName);
        @SuppressWarnings("unchecked")
        final Map<String, Property> props =  indexTypeMappings.properties();
        final Property processingTimeProp = props.get("PROCESSING_TIME");
        // Verify param not removed (unsupported change)
        assertNotNull(processingTimeProp.date().format());
        
        final Property addProcessingTimeProp = props.get("ADD_PROCESSING_TIME");
        // Verify param not added (unsupported change)
        assertNull(addProcessingTimeProp.date().format());

        // Verify index mapping of unsupported field changes has been updated with allowed changes
        final Property processingProp = props.get("PROCESSING");
        final Map<String, Property> processingProps = processingProp.object().properties();
        final Property idProp = processingProps.get("ID");
        // Verify param not removed (change not allowed)
        assertNotNull(idProp.keyword().nullValue());
        final Property pTimeProp = processingProps.get("P_TIME");
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", pTimeProp);
        // Verify unsupported change to nested property is not applied, param not removed
        final Object propFormatValue = pTimeProp.date().format();
        assertNotNull("testUpdateIndexesOfUnSupportedChangesInTemplate", propFormatValue);

        final Property refProp = processingProps.get("REF");
        // Verify change to nested property is applied, param removed
        final Object refIgnoreMalformedProp = refProp.integer().ignoreMalformed();
        assertNull("testUpdateIndexesOfUnSupportedChangesInTemplate", refIgnoreMalformedProp);

        final Property codeProp = processingProps.get("CODE");
        // Verify change to nested property is applied, param added
        final Boolean codeIgnoreMalformedProp = codeProp.integer().ignoreMalformed();
        assertFalse("testUpdateIndexesOfUnSupportedChangesInTemplate", codeIgnoreMalformedProp);
    }

    @Test
    public void testRemoveParams() throws Exception
    {
        LOGGER.info("Running test 'testRemoveParams'...");
        final String templateName = "prop-rem-template";
        final String origTemplateSourceFile = "/template14.json";
        final String updatedTemplateSourceFile = "/template15.json";
        final String indexName = "test_violet-000001";

        LOGGER.info("testRemoveParams - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);
        
        LOGGER.info("testRemoveParams - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{" + "'message':'doc1'," + "'status_code\":'complete'," + "'session_id':'44gdfg67',"
                + "'textdata1':'some text data'," + "'number_two': 16100" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testRemoveParams - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testRemoveParams - Updating indexes matching template {}", templateName);
        updateIndex("testRemoveParams", templateName);

        final TypeMapping indexTypeMappings = getIndexMapping("testRemoveParams", indexName);
        final Map<String, Property> props = indexTypeMappings.properties();

        // Verify params are not removed even though removal is allowed
        final Property titleYProp = props.get("title_y");
        assertNull(titleYProp.text().boost()); // has been removed

        final Property numberTwoProp = props.get("number_two");
        assertNull(numberTwoProp.integer().coerce()); // has been removed, as expected

        final Property firstNameProp = props.get("first_name");
        assertFalse(firstNameProp.text().copyTo().isEmpty());

        final Property tagsProp = props.get("tags");
        assertNull(tagsProp.keyword().eagerGlobalOrdinals()); // has been removed

        final Property titleWProp = props.get("titlew");
        assertNull(titleWProp.text().fielddata()); // has been removed

        final Property cityProp = props.get("city");
        assertFalse(cityProp.text().fields().isEmpty());

        final Property messageProp = props.get("message");
        assertNull(messageProp.keyword().ignoreAbove()); // has been removed

        final Property numberOneProp = props.get("number_one");
        assertNull(numberOneProp.integer().ignoreMalformed()); // has been removed, as expected

        final Property dummyMessage2Prop = props.get("dummy_message2");
        assertNotNull(dummyMessage2Prop.text().indexOptions()); // has not been removed, as expected

        final Property latencyProp = props.get("latency");
        assertFalse(latencyProp.long_().meta().isEmpty());

        final Property statusCodeProp = props.get("status_code");
        assertNotNull(statusCodeProp.keyword().nullValue()); // not removed, as expected

        final Property names2Mapping = props.get("names2");
        assertNotNull(names2Mapping.text().positionIncrementGap());

        final Property managerProp = props.get("manager");
        assertFalse(managerProp.object().properties().isEmpty());

        final Property titleZProp = props.get("title_z");
        assertNull(titleZProp.text().searchAnalyzer());

        final Property titleMProp = props.get("title_m");
        assertNotNull(titleMProp.text().searchQuoteAnalyzer());
        assertNotNull(titleMProp.text().analyzer());

        // Verify params not removed for those that are not allowed
        final Property sessionIdProp = props.get("session_id");
        assertNotNull(sessionIdProp.keyword().docValues());

        final Property someContentProp = props.get("some_content");
        assertNotNull(someContentProp.text().store());

        final Property sessionDataProp = props.get("session_data");
        assertNotNull(sessionDataProp.object().enabled());

        final Property dateProp = props.get("date");
        assertNotNull(dateProp.date().format());

        final Property booleanSimFieldProp = props.get("boolean_sim_field");
        assertNotNull(booleanSimFieldProp.text().similarity());

        final Property someContent2Prop = props.get("some_content2");
        assertNotNull(someContent2Prop.text().termVector());

        final Property textData1Prop = props.get("textdata1");
        assertNotNull(textData1Prop.text().indexPhrases());

        final Property bodyTextProp = props.get("body_text");
        assertNotNull(bodyTextProp.text().indexPrefixes());

        final Property dummyMessageProp = props.get("dummy_message");
        assertNotNull(dummyMessageProp.keyword().index());

        final Property fooProp = props.get("foo");
        assertNotNull(fooProp.keyword().normalizer());

        final Property titleXProp = props.get("titlex");
        assertNotNull(titleXProp.text().norms());
    }

    @Test
    public void testAddParams() throws Exception
    {
        LOGGER.info("Running test 'testAddParams'...");
        final String templateName = "prop-add-template";
        final String origTemplateSourceFile = "/template16.json";
        final String updatedTemplateSourceFile = "/template17.json";
        final String indexName = "test_pink-000001";

        LOGGER.info("testAddParams - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);
        
        LOGGER.info("testAddParams - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{" + "'message':'doc1'," + "'status_code\":'complete'," + "'session_id':'44gdfg67',"
                + "'textdata1':'some text data'," + "'number_two': 16100" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testAddParams - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testAddParams - Updating indexes matching template {}", templateName);
        updateIndex("testAddParams", templateName);

        final TypeMapping indexTypeMappings = getIndexMapping("testAddParams", indexName);
        final Map<String, Property> props = indexTypeMappings.properties();

        // Verify params are added when addition is allowed
        final Property titleYProp = props.get("title_y");
        assertNotNull(titleYProp.text().boost());

        final Property numberTwoProp = props.get("number_two");
        assertNotNull(numberTwoProp.integer().coerce());

        final Property firstNameProp = props.get("first_name");
        assertFalse(firstNameProp.text().copyTo().isEmpty());

        final Property tagsProp = props.get("tags");
        assertNotNull(tagsProp.keyword().eagerGlobalOrdinals());

        final Property prop18Mapping = props.get("titlew");
        assertNotNull(prop18Mapping.text().fielddata());

        final Property cityProp = props.get("city");
        assertTrue(cityProp.text().fields().isEmpty()); // has not been added

        final Property messageProp = props.get("message");
        assertNotNull(messageProp.keyword().ignoreAbove());

        final Property numberOneProp = props.get("number_one");
        assertNotNull(numberOneProp.integer().ignoreMalformed());

        final Property dummyMessage2Prop = props.get("dummy_message2");
        assertNull(dummyMessage2Prop.text().indexOptions()); // has not been added, as expected

        final Property latencyProp = props.get("latency");
        assertTrue(latencyProp.long_().meta().isEmpty()); // has not been added

        final Property statusCodeProp = props.get("status_code");
        assertNull(statusCodeProp.keyword().nullValue()); // not added, as expected

        final Property names2Prop = props.get("names2");
        assertNull(names2Prop.text().positionIncrementGap()); // not added, as expected

        final Property managerProp = props.get("manager");
        assertTrue(managerProp.text().properties().isEmpty()); // has not been added

        final Property titleZProp = props.get("title_z");
        assertNotNull(titleZProp.text().searchAnalyzer()); //has been removed

        final Property titleMProp = props.get("title_m");
        assertNull(titleMProp.text().searchQuoteAnalyzer()); // has not been added
        assertNull(titleMProp.text().analyzer());

        // Verify params not added for those that are not allowed
        final Property sessionIdProp = props.get("session_id");
        assertNull(sessionIdProp.keyword().docValues());

        final Property someContentProp = props.get("some_content");
        assertNull(someContentProp.text().store());

        final Property sessionDataProp = props.get("session_data");
        assertNull(sessionDataProp.object().enabled());

        final Property dateProp = props.get("date");
        assertNull(dateProp.date().format());

        final Property booleanSimFieldProp = props.get("boolean_sim_field");
        assertNull(booleanSimFieldProp.text().similarity());

        final Property someContent2Prop = props.get("some_content2");
        assertNull(someContent2Prop.text().termVector());

        final Property textData1Prop = props.get("textdata1");
        assertNull(textData1Prop.text().indexPhrases());

        final Property bodyTextProp = props.get("body_text");
        assertNull(bodyTextProp.text().indexPrefixes());

        final Property dummyMessageProp = props.get("dummy_message");
        assertNull(dummyMessageProp.keyword().index());

        final Property fooProp = props.get("foo");
        assertNull(fooProp.keyword().normalizer());

        final Property titleXProp = props.get("titlex");
        assertNull(titleXProp.text().norms());
    }

    @Test
    public void testAddRemoveParams() throws Exception
    {
        LOGGER.info("Running test 'testAddRemoveParams'...");
        final String templateName = "prop-add-template";
        final String origTemplateSourceFile = "/template18.json";
        final String updatedTemplateSourceFile = "/template19.json";
        final String indexName = "test_green-000001";

        LOGGER.info("testAddRemoveParams - Creating initial template {}", templateName);

        // Create a template
        putIndexTemplate(templateName, origTemplateSourceFile);
        
        LOGGER.info("testAddRemoveParams - Creating index matching template {}", templateName);
        // Create an index with some data
        String jsonString = "{" + "'some_content2':'doc1'" + "}";
        jsonString = jsonString.replaceAll("'", "\"");
        
        createIndex(indexName, "1", "1", jsonString);

        verifyIndexData(indexName, QueryBuilders.matchAll().build()._toQuery(), 1);

        LOGGER.info("testAddRemoveParams - Updating template {}", templateName);
        // Create a template
        putIndexTemplate(templateName, updatedTemplateSourceFile);

        LOGGER.info("testAddRemoveParams - Updating indexes matching template {}", templateName);
        updateIndex("testAddRemoveParams", templateName);

        final TypeMapping indexTypeMappings = getIndexMapping("testAddRemoveParams", indexName);
        final Map<String, Property> props = indexTypeMappings.properties();

        // Verify params are added when addition is allowed
        final Property processingProp = props.get("PROCESSING");

        final Map<String, Property> processingProps = processingProp.object().properties();

        final Map<String, Property> otherProps = processingProps.get("OTHER_PROPS").object().properties();

        final Property statusCodeProp = otherProps.get("status_code");
        assertNull(statusCodeProp.keyword().nullValue()); // not added, as expected

        final Property bodyTextProp = otherProps.get("BODY_TEXT");
        assertNotNull(bodyTextProp.text().indexPrefixes()); // not removed, as expected

        final Property refProp = otherProps.get("REF");
        assertNull(refProp.integer().ignoreMalformed()); // removed, as expected

        final Property someDateProp = otherProps.get("some_date");
        assertNull(someDateProp.date().format()); // not added, as expected

        final Property idProp = processingProps.get("ID");
        assertNull(idProp.keyword().eagerGlobalOrdinals()); // not added, unexpected (can be removed but not added?)
        assertNotNull(idProp.keyword().ignoreAbove());
        assertNotNull(idProp.keyword().nullValue()); // not removed, as expected

        final Map<String, Property> languageCodeProps = props.get("LANGUAGE_CODES").object().properties();

        final Property propCodeMapping = languageCodeProps.get("CODE");
        assertNull(propCodeMapping.keyword().ignoreAbove()); // removed, as expected

        final Property confidenceProp = languageCodeProps.get("CONFIDENCE");
        assertNotNull(confidenceProp.keyword().normalizer()); // not removed, as expected

        final Property propTxtMapping = languageCodeProps.get("textdata1");
        assertNull(propTxtMapping.text().indexPhrases()); // not added, as expected
    }

    private void updateIndex(final String testName, final String templateName)
    {
        LOGGER.info("{}: {}", testName, templateName);
        try {
            ElasticMappingUpdater.update(false, host, protocol, port, username, password, connectTimeout,
                                         socketTimeout);
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

    private void verifyIndexData(final String indexName, final Query query, final long expectedHitCount) throws IOException
    {
        final SearchRequest searchRequest = new SearchRequest.Builder()
            .index(indexName)
            .trackTotalHits(TrackHits.of(x -> x.enabled(Boolean.TRUE)))
            .query(query)
            .build();
        
        SearchResponse<JsonData> searchResponse = client.search(searchRequest, JsonData.class);
        final long totalDocs = searchResponse.hits().total().value();
        LOGGER.info("Hits : {}", totalDocs);
        
        final StringWriter writer = new StringWriter();
        try (final JacksonJsonpGenerator generator = new JacksonJsonpGenerator(new JsonFactory().createGenerator(writer))) {
            searchResponse.serialize(generator, new JacksonJsonpMapper());
        } catch (IOException ex) {
            LOGGER.error("Error parsing search response", ex);
        }
        LOGGER.info("Search response: " + writer.toString());

        assertTrue("Got test document", totalDocs == expectedHitCount);

        final List<Hit<JsonData>> searchHits = searchResponse.hits().hits();
        for (final Hit<JsonData> hit : searchHits) {
            writer.getBuffer().setLength(0);
            try (final JacksonJsonpGenerator generator = new JacksonJsonpGenerator(new JsonFactory().createGenerator(writer))) {
                hit.serialize(generator, new JacksonJsonpMapper());
            } catch (IOException ex) {
                LOGGER.error("Error parsing hit", ex);
            }
            LOGGER.info("hit : {}", writer.toString());
        }
    }

    private TypeMapping getIndexMapping(final String testName, final String indexName) throws IOException, GetIndexException
    {
        LOGGER.info("{} - Get index {}", testName, indexName);
        final Request request = new Request("GET", "/" + UrlEscapers.urlPathSegmentEscaper().escape(indexName));
        final Response response = transport.restClient().performRequest(request);

        final int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 200) {
            final JSONObject responseBody = new JSONObject(EntityUtils.toString(response.getEntity()));
            final JSONObject mappings = responseBody.getJSONObject(indexName).getJSONObject("mappings");
            final JsonParser jsonMappingParser = Json.createParser(new StringReader(mappings.toString()));
            final TypeMapping indexTypeMappings = TypeMapping._DESERIALIZER.deserialize(jsonMappingParser, new JacksonJsonpMapper());
            LOGGER.info("{}------Updated mapping for index '{}': {}", testName, indexName, indexTypeMappings);
            return indexTypeMappings;
        } else {
            throw new GetIndexException(String.format("Error getting index '%s'. Status code: %s, response: %s",
                                                      indexName, statusCode, EntityUtils.toString(response.getEntity())));
        }
    }

    private boolean indexDocumentWithRetry(final IndexRequest<JsonData> request) throws InterruptedException
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

    private boolean indexDocument(final IndexRequest<JsonData> request)
    {
        try {
            final IndexResponse response = client.index(request);
            assertTrue(response.result().equals(Result.Created));
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
