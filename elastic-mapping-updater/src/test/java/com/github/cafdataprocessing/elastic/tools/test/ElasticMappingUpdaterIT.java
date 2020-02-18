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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import org.apache.http.HttpHost;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.indices.PutIndexTemplateRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.cafdataprocessing.elastic.tools.ElasticMappingUpdater;
import com.github.cafdataprocessing.elastic.tools.exceptions.IndexNotFoundException;
import com.github.cafdataprocessing.elastic.tools.exceptions.TemplateNotFoundException;
import com.github.cafdataprocessing.elastic.tools.exceptions.UnexpectedResponseException;

public class ElasticMappingUpdaterIT
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdaterIT.class);
    private final ElasticMappingUpdater indexUpdater;
    private final RestHighLevelClient client;

    public ElasticMappingUpdaterIT()
    {
        final String host = System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_HOSTNAMES");
        final int port = Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_REST_PORT"));

        client = new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http")));

        indexUpdater = new ElasticMappingUpdater();
    }

    @Test
    public void testUpdateIndexesOfUpdatedTemplate() throws IOException
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
        if (!putTemplateResponse.isAcknowledged())
        {
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
        try
        {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e)
        {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged())
        {
            fail();
        }

        LOGGER.info("testUpdateIndexesOfUpdatedTemplate - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateIndexesOfUpdatedTemplate", templateName);

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

        try
        {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e)
        {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 2);
    }

    @Test
    public void testUpdateUnsupportedChanges() throws IOException
    {
        LOGGER.info("Running test 'testUpdateUnsupportedChanges'...");
        final String templateName = "acme-sample-template";
        final String origTemplateSourceFile = "/template3.json";
        final String unsupportedTemplateSourceFile = "/template4.json";
        final String indexName = "test_acmesample-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testUpdateUnsupportedChanges - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged())
        {
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
        try
        {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e)
        {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateUnsupportedChanges - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(unsupportedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged())
        {
            fail();
        }

        LOGGER.info("testUpdateUnsupportedChanges - Updating indexes matching template {}", templateName);
        // No changes to Index mapping
        updateIndex("testUpdateUnsupportedChanges", templateName);
    }

    @Test
    public void testUpdateDynamicTemplateOverwrite() throws IOException
    {
        LOGGER.info("Running test 'testUpdateDynamicTemplateOverwrite'...");
        final String templateName = "sample-template";
        final String origTemplateSourceFile = "/template5.json";
        final String updatedTemplateSourceFile = "/template6.json";
        final String indexName = "test_dynsample-000001";

        final String origTemplateSource = readFile(origTemplateSourceFile);
        LOGGER.info("testUpdateDynamicTemplateOverwrite - Creating initial template {}", templateName);

        // Create a template
        final PutIndexTemplateRequest trequest = new PutIndexTemplateRequest(templateName);
        trequest.source(origTemplateSource, XContentType.JSON);
        final AcknowledgedResponse putTemplateResponse = client.indices().putTemplate(trequest, RequestOptions.DEFAULT);
        if (!putTemplateResponse.isAcknowledged())
        {
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
        try
        {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e)
        {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 1);

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating template {}", templateName);
        final String updatedTemplateSource = readFile(updatedTemplateSourceFile);
        // Create a template
        final PutIndexTemplateRequest utrequest = new PutIndexTemplateRequest(templateName);
        utrequest.source(updatedTemplateSource, XContentType.JSON);
        final AcknowledgedResponse updateTemplateResponse = client.indices().putTemplate(utrequest, RequestOptions.DEFAULT);
        if (!updateTemplateResponse.isAcknowledged())
        {
            fail();
        }

        LOGGER.info("testUpdateDynamicTemplateOverwrite - Updating indexes matching template {}", templateName);
        updateIndex("testUpdateDynamicTemplateOverwrite", templateName);

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

        try
        {
            final IndexResponse response = client.index(request, RequestOptions.DEFAULT);
            assertTrue(response.status() == RestStatus.CREATED);
        } catch (final ElasticsearchException e)
        {
            fail();
        }

        verifyIndexData(indexName, QueryBuilders.matchAllQuery(), 2);
    }

    private void updateIndex(final String testName, final String templateName)
    {
        LOGGER.info("{}: {}", testName, templateName);
        try
        {
            indexUpdater.updateIndexesForTemplate(templateName);
        } catch (final IOException | UnexpectedResponseException | TemplateNotFoundException | IndexNotFoundException e)
        {
            LOGGER.error(testName, e);
            fail(testName + ":" + e);
        }
    }

    public static String readFile(final String path) throws IOException
    {
        InputStream is = null;
        BufferedReader br = null;

        try
        {
            is = ElasticMappingUpdaterIT.class.getResourceAsStream(path);

            final StringBuilder sb = new StringBuilder();
            String line;
            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null)
            {
                sb.append(line);
                sb.append("\n");
            }
            final String content = sb.toString();
            return content;
        } finally
        {
            if (is != null)
            {
                is.close();
            }
            if (br != null)
            {
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
        for (final SearchHit hit : searchHits)
        {
            final Map<String, Object> sourceAsMap = hit.getSourceAsMap();
            LOGGER.info("hit : {}", sourceAsMap);
        }
    }
}
