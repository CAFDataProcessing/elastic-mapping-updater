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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.indices.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetIndexException;
import com.github.cafdataprocessing.elastic.tools.exceptions.GetTemplateException;
import com.github.cafdataprocessing.elastic.tools.exceptions.TemplateNotFoundException;
import com.github.cafdataprocessing.elastic.tools.exceptions.UnexpectedResponseException;
import com.github.cafdataprocessing.elastic.tools.exceptions.UnsupportedMappingChangesException;
import com.github.cafdataprocessing.elastic.tools.utils.FlatMapUtil;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;

public final class ElasticMappingUpdater
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdater.class);

    private static final String MAPPING_PROPS_KEY = "properties";
    private static final String MAPPING_DYNAMIC_TEMPLATES_KEY = "dynamic_templates";

    private final ObjectMapper objectMapper;
    private final ElasticRequestHandler elasticRequestHandler;
    private final boolean dryRun;

    /**
     * Updates the mapping of indexes matching any templates on the Elasticsearch instances.
     *
     * @param dryRun If true, the tool lists the mapping changes to the indexes but does not apply them
     * @param esHostNames Comma separated list of Elasticsearch hostnames
     * @param esProtocol The protocol to connect with Elasticsearch server
     * @param esRestPort Elasticsearch REST API port
     * @param esConnectTimeout Timeout until a new connection is fully established
     * @param esSocketTimeout Time of inactivity to wait for packets[data] to be received
     * @throws IOException thrown if the elasticsearch request cannot be processed
     * @throws TemplateNotFoundException thrown if the template cannot be found
     * @throws GetIndexException thrown if there is an error getting an index
     * @throws GetTemplateException thrown if there is an error getting a template
     * @throws UnexpectedResponseException thrown if the elasticsearch response cannot be parsed
     */
    public static void update(
        final boolean dryRun,
        final String esHostNames,
        final String esProtocol,
        final int esRestPort,
        final int esConnectTimeout,
        final int esSocketTimeout
    ) throws IOException, TemplateNotFoundException, GetIndexException, GetTemplateException, UnexpectedResponseException
    {
        final ElasticMappingUpdater updater
            = new ElasticMappingUpdater(dryRun, esHostNames, esProtocol, esRestPort, esConnectTimeout, esSocketTimeout);
        updater.updateIndexes();
    }

    private ElasticMappingUpdater(
        final boolean dryRun,
        final String esHostNames,
        final String esProtocol,
        final int esRestPort,
        final int esConnectTimeout,
        final int esSocketTimeout)
    {
        this.dryRun = dryRun;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        final ElasticSettings elasticSettings
            = new ElasticSettings(esProtocol, esHostNames, esRestPort, esConnectTimeout, esSocketTimeout);

        final ElasticMappingUpdaterConfiguration schemaUpdaterConfiguration = new ElasticMappingUpdaterConfiguration(elasticSettings);

        elasticRequestHandler = new ElasticRequestHandler(schemaUpdaterConfiguration, objectMapper);
    }

    private void updateIndexes()
        throws IOException, TemplateNotFoundException, GetIndexException, GetTemplateException, UnexpectedResponseException
    {
        final List<String> templateNames = elasticRequestHandler.getTemplateNames();
        LOGGER.info("Templates found in Elasticsearch: {}", templateNames);
        for (final String templateName : templateNames) {
            updateIndexesForTemplate(templateName);
        }
    }

    private void updateIndexesForTemplate(final String templateName)
        throws IOException, TemplateNotFoundException, GetIndexException, GetTemplateException, UnexpectedResponseException
    {
        LOGGER.info("---------- Updating index(es) matching template '{}' ----------", templateName);

        final IndexTemplateMetaData template = elasticRequestHandler.getTemplate(templateName);
        final List<String> patterns = template.patterns();

        final MappingMetaData mapping = template.mappings();
        if (mapping == null) {
            LOGGER.info("No mappings in template '{}'. Indexes for this template will not be updated.", templateName);
            return;
        }

        final Map<String, Object> templateTypeMappings = mapping.getSourceAsMap();

        final Object templateProperties = Optional
                .ofNullable(templateTypeMappings.get(MAPPING_PROPS_KEY))
                .orElseGet(Collections::emptyMap);

        // Find all indices that match template patterns
        final List<String> indexes = elasticRequestHandler.getIndexNames(patterns);
        LOGGER.info("---- Got {} index(es) that match template '{}' ----", indexes.size(), templateName);
        for (final String indexName : indexes) {
            GetIndexResponse getIndexResponse = elasticRequestHandler.getIndex(indexName);
            MappingMetaData indexMappings = getIndexResponse.getMappings().get(indexName);
            Map<String, Object> indexTypeMappings = indexMappings.getSourceAsMap();

            LOGGER.info("Comparing index mapping for '{}'", indexName);

            try {
                final Object indexProperties = Optional
                        .ofNullable(indexTypeMappings.get(MAPPING_PROPS_KEY))
                        .orElseGet(Collections::emptyMap);

                @SuppressWarnings("unchecked")
                final Map<String, Object> mappingsChanges = getMappingChanges(
                    (Map<String, Object>) templateProperties,
                    (Map<String, Object>) indexProperties);
                LOGGER.debug("Property mapping changes for index '{}': {}", indexName, mappingsChanges);
                final Map<String, Object> mappingsRequest = new HashMap<>();
                mappingsRequest.put(MAPPING_PROPS_KEY, mappingsChanges);

                // Add all dynamic_templates in template to index mapping
                final Object dynamicTemplatesInTemplate = Optional
                    .ofNullable(templateTypeMappings.get(MAPPING_DYNAMIC_TEMPLATES_KEY))
                    .orElseGet(Collections::emptyList); // Empty list will clear all existing dynamic_templates in index mapping

                mappingsRequest.put(MAPPING_DYNAMIC_TEMPLATES_KEY, dynamicTemplatesInTemplate);

                if(dryRun)
                {
                    LOGGER.info("Mapping updates for index '{}' are {}", indexName, mappingsRequest);
                } else
                {
                    // Update the index mapping
                    elasticRequestHandler.updateIndexMapping(indexName, mappingsRequest);
    
                    // Get the updated index mapping
                    getIndexResponse = elasticRequestHandler.getIndex(indexName);
                    indexMappings = getIndexResponse.getMappings().get(indexName);
                    indexTypeMappings = indexMappings.getSourceAsMap();
                    LOGGER.info("Index mapping updated for '{}': {}", indexName, indexTypeMappings);
                }
            } catch (final UnsupportedMappingChangesException e) {
                LOGGER.warn("Unsupported mapping changes for index '{}'. Index mapping will not be updated.", indexName, e);
            }
        }
    }

    private static boolean isMappingChangeSafe(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
        throws JsonProcessingException
    {
        final Map<String, Object> ftemplateMapping = FlatMapUtil.flatten(templateMapping);
        final Map<String, Object> findexMapping = FlatMapUtil.flatten(indexMapping);
        final MapDifference<String, Object> diff = Maps.difference(ftemplateMapping, findexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
        if (entriesDiffering.isEmpty()) {
            return true;
        } else {
            // Elasticsearch would throw IllegalArgumentException if any such
            // change is included in the index mapping updates
            entriesDiffering.forEach((key, value) -> LOGGER.warn("Unsuported mapping changes : {}:{}", key, value));
            return false;
        }
    }

    private Map<String, Object> getMappingChanges(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
        throws JsonProcessingException, UnsupportedMappingChangesException
    {
        final Map<String, Object> mappingsChanges = new HashMap<>();
        final MapDifference<String, Object> diff = Maps.difference(templateMapping, indexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();

        LOGGER.debug("Entries differing in template and index mapping: {}", objectMapper.writeValueAsString(entriesDiffering));

        if (!isMappingChangeSafe(templateMapping, indexMapping)) {
            throw new UnsupportedMappingChangesException("Unsupported mapping changes");
        }

        final Map<String, Object> entriesOnlyInTemplate = diff.entriesOnlyOnLeft();
        LOGGER.debug("New entries in template: {}", objectMapper.writeValueAsString(entriesOnlyInTemplate));
        mappingsChanges.putAll(entriesOnlyInTemplate);

        final Set<String> fields = entriesDiffering.keySet();
        for (final String field : fields) {
            mappingsChanges.put(field, ((ValueDifference<?>) entriesDiffering.get(field)).leftValue());
        }

        return mappingsChanges;
    }
}
