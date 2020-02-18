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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.indices.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.microfocus.caf.elastic.tools.exceptions.IndexNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.TemplateNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.UnexpectedResponseException;
import com.microfocus.caf.elastic.tools.exceptions.UnsupportedMappingChangesException;
import com.microfocus.caf.elastic.tools.utils.FlatMapUtil;

public class ElasticMappingUpdater {

    private static Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdater.class);

    private static String MAPPING_PROPS_KEY = "properties";
    private static String MAPPING_DYNAMIC_TEMPLATES_KEY = "dynamic_templates";

    private final ObjectMapper objectMapper;
    private final ElasticRequestHandler elasticRequestHandler;

    public ElasticMappingUpdater() {
        this.objectMapper = new ObjectMapper();
        final ElasticSettings elasticSettings = new ElasticSettings(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_HOSTNAMES"),
                Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_REST_PORT")),
                Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_CONNECT_TIMEOUT")),
                Integer.parseInt(System.getenv("CAF_SCHEMA_UPDATER_ELASTIC_SOCKET_TIMEOUT")));

        final ElasticMappingUpdaterConfiguration schemaUpdaterConfiguration = new ElasticMappingUpdaterConfiguration(elasticSettings);

        elasticRequestHandler = new ElasticRequestHandler(schemaUpdaterConfiguration, this.objectMapper);
    }

    public static void main(final String[] args) throws Exception {
        final ElasticMappingUpdater updater = new ElasticMappingUpdater();
        updater.updateIndexes();
        System.exit(0);
    }

    public void updateIndexes() throws IOException, UnexpectedResponseException, TemplateNotFoundException, IndexNotFoundException {
        final List<String> templateNames = elasticRequestHandler.getTemplateNames();
        for (final String templateName : templateNames) {
            updateIndexesForTemplate(templateName);
        }
    }

    @SuppressWarnings("unchecked")
    public void updateIndexesForTemplate(final String templateName)
            throws IOException, TemplateNotFoundException, UnexpectedResponseException, IndexNotFoundException {
        LOGGER.info("Updating index(es) matching template '{}'", templateName);

        final IndexTemplateMetaData template = elasticRequestHandler.getTemplate(templateName);
        final List<String> patterns = template.patterns();

        final MappingMetaData mapping = template.mappings();
        if (mapping == null) {
            LOGGER.info("No mappings in template '{}'", templateName);
            return;
        }
        final Map<String, Object> templateTypeMappings = mapping.getSourceAsMap();

        // Find all indices that match template patterns
        final List<String> indexes = elasticRequestHandler.getIndexNames(patterns);
        LOGGER.info("Got {} index(es) that match template '{}'", indexes.size(), templateName);
        for (final String indexName : indexes) {
            GetIndexResponse getIndexResponse = elasticRequestHandler.getIndex(indexName);
            MappingMetaData indexMappings = getIndexResponse.getMappings().get(indexName);
            Map<String, Object> indexTypeMappings = indexMappings.getSourceAsMap();

            LOGGER.info("------Comparing IndexMapping for '{}'", indexName);

            try {
                final Map<String, Object> mappingsChanges = getMappingChanges((Map<String, Object>) templateTypeMappings.get(MAPPING_PROPS_KEY),
                        (Map<String, Object>) indexTypeMappings.get(MAPPING_PROPS_KEY));
                LOGGER.info("------Mapping changes for index '{}': {}", indexName, mappingsChanges);
                final Map<String, Object> mappingsRequest = new HashMap<>();
                mappingsRequest.put(MAPPING_PROPS_KEY, mappingsChanges);

                // Add all dynamic_templates in template to index mapping
                Object dynamicTemplatesInTemplate = templateTypeMappings.get(MAPPING_DYNAMIC_TEMPLATES_KEY);
                if(dynamicTemplatesInTemplate == null)
                {
                    dynamicTemplatesInTemplate= new ArrayList<>();
                }

                mappingsRequest.put(MAPPING_DYNAMIC_TEMPLATES_KEY, dynamicTemplatesInTemplate);

                // Update the index mapping
                elasticRequestHandler.updateIndexMapping(indexName, mappingsRequest);

                // Get the updated index mapping
                getIndexResponse = elasticRequestHandler.getIndex(indexName);
                indexMappings = getIndexResponse.getMappings().get(indexName);
                indexTypeMappings = indexMappings.getSourceAsMap();
                LOGGER.info("------Updated mapping for index '{}': {}", indexName, indexTypeMappings);
            } catch (final UnsupportedMappingChangesException e) {
                LOGGER.warn("Unsupported mapping changes for index : {}", indexName, e);
                // Continue with next index to be updated
                continue;
            }
        }
    }

    private boolean isMappingChangeSafe(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
            throws JsonProcessingException {
        final Map<String, Object> ftemplateMapping = FlatMapUtil.flatten(templateMapping);
        final Map<String, Object> findexMapping = FlatMapUtil.flatten(indexMapping);
        LOGGER.info("Flattened template mapping : {}", ftemplateMapping);
        LOGGER.info("Flattened index mapping : {}", findexMapping);
        final MapDifference<String, Object> diff = Maps.difference(ftemplateMapping, findexMapping);
        Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
        if(entriesDiffering.isEmpty())
        {
            return true;
        }
        else
        {
            // ElasticSearch would throw IllegalArgumentException if any such change is included in the index mapping updates
            entriesDiffering.forEach((key, value) -> LOGGER.info("Entries differing : {}:{}", key, value));
            return false;
        }
    }

    private Map<String, Object> getMappingChanges(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
            throws JsonProcessingException, UnsupportedMappingChangesException {
        final Map<String, Object> mappingsChanges = new HashMap<>();
        final MapDifference<String, Object> diff = Maps.difference(templateMapping, indexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();

        LOGGER.info("Differing entries: {}", objectMapper.writeValueAsString(entriesDiffering));

        if(!isMappingChangeSafe(templateMapping, indexMapping))
        {
            throw new UnsupportedMappingChangesException("Unsupported mapping changes");
        }

        final Map<String, Object> entriesOnlyInTemplate = diff.entriesOnlyOnLeft();
        LOGGER.info("Entries only in Template: {}", objectMapper.writeValueAsString(entriesOnlyInTemplate));
        mappingsChanges.putAll(entriesOnlyInTemplate);
        final Set<String> fields = entriesDiffering.keySet();
        for (final String field : fields) {
            mappingsChanges.put(field, ((ValueDifference<?>) entriesDiffering.get(field)).leftValue());
        }

        return mappingsChanges;
    }

}
