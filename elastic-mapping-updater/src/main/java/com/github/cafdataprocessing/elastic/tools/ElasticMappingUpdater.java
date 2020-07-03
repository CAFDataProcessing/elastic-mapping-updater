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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.github.cafdataprocessing.elastic.tools.exceptions.GetTemplatesException;
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
     * @throws GetIndexException thrown if there is an error getting an index
     * @throws GetTemplatesException thrown if there is an error getting templates
     * @throws UnexpectedResponseException thrown if the elasticsearch response cannot be parsed
     */
    public static void update(
        final boolean dryRun,
        final String esHostNames,
        final String esProtocol,
        final int esRestPort,
        final int esConnectTimeout,
        final int esSocketTimeout
    ) throws IOException, GetIndexException, GetTemplatesException, UnexpectedResponseException
    {
        final ElasticMappingUpdater updater
            = new ElasticMappingUpdater(dryRun, esHostNames, esProtocol, esRestPort, esConnectTimeout, esSocketTimeout);
        LOGGER.info("Updating indexes on '{}'. {}", esHostNames,
                dryRun ? "This is a dry run. No indexes will actually be updated."
                       : "Indexes with no mapping conflicts will be updated.");
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
        throws IOException, GetIndexException, GetTemplatesException, UnexpectedResponseException
    {
        final List<IndexTemplateMetaData> templates = elasticRequestHandler.getTemplates();
        LOGGER.info("Templates found in Elasticsearch: {}",
                templates.stream().map(template -> template.name()).collect(Collectors.toList()));
        for (final IndexTemplateMetaData template : templates) {
            updateIndexesForTemplate(template);
        }
    }

    private void updateIndexesForTemplate(final IndexTemplateMetaData template)
        throws IOException, GetIndexException, GetTemplatesException, UnexpectedResponseException
    {
        final String templateName = template.name();
        LOGGER.info("---- Analyzing indexes matching template '{}' ----", templateName);

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
        LOGGER.info("Found {} index(es) that match template '{}'", indexes.size(), templateName);
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

                final Map<String, Object> mappingsRequest = new HashMap<>();
                mappingsRequest.put(MAPPING_PROPS_KEY, mappingsChanges);

                // Add all dynamic_templates in template to index mapping
                @SuppressWarnings("unchecked")
                final List<Object> dynamicTemplatesInTemplate = (List<Object>) Optional
                    .ofNullable(templateTypeMappings.get(MAPPING_DYNAMIC_TEMPLATES_KEY))
                    .orElseGet(Collections::emptyList); // Empty list will clear all existing dynamic_templates in index mapping

                @SuppressWarnings("unchecked")
                final List<Object> dynamicTemplatesInIndex = (List<Object>) Optional
                        .ofNullable(indexTypeMappings.get(MAPPING_DYNAMIC_TEMPLATES_KEY))
                        .orElseGet(Collections::emptyList);

                final List<Object> dynamicTemplatesUpdates = new ArrayList<>(dynamicTemplatesInTemplate);

                LOGGER.debug("Dynamic_templates: current: {} target: {}", dynamicTemplatesInIndex, dynamicTemplatesInTemplate);

                final boolean dynamicTemplatesHaveChanged = hasDynamicTemplateChanged(dynamicTemplatesInTemplate,
                                                                                      dynamicTemplatesInIndex);

                LOGGER.info("{}", dynamicTemplatesHaveChanged ? "Current dynamic_templates will be replaced with updated version."
                                                              : "No dynamic_template changes required.");

                mappingsRequest.put(MAPPING_DYNAMIC_TEMPLATES_KEY, dynamicTemplatesUpdates);

                LOGGER.info("'{}' comparison complete.", indexName);
                final boolean indexNeedsUpdates = !mappingsChanges.isEmpty() || dynamicTemplatesHaveChanged;
                if(!indexNeedsUpdates)
                {
                    LOGGER.info("No index mapping changes required.");
                }

                if(!dryRun)
                {
                    // Update the index mapping
                    if(indexNeedsUpdates)
                    {
                        LOGGER.info("Updating index mapping...");
                        elasticRequestHandler.updateIndexMapping(indexName, mappingsRequest);
                        LOGGER.info("Index mapping updated");
                    }
                }
            } catch (final UnsupportedMappingChangesException e) {
                LOGGER.warn("Index cannot be updated due to unsupported mapping changes.");
            }
        }
        LOGGER.info("---- Analysis of indexes matching template '{}' completed ----", templateName);
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
            entriesDiffering.forEach((key, value) -> LOGGER.warn("Unsupported mapping change : {} - current: {} target: {}",
                                                                  key, value.rightValue(), value.leftValue()));
            return false;
        }
    }

    private Map<String, Object> getMappingChanges(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
        throws JsonProcessingException, UnsupportedMappingChangesException
    {
        final Map<String, Object> mappingsChanges = new HashMap<>();
        final MapDifference<String, Object> diff = Maps.difference(templateMapping, indexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();

        if (!entriesDiffering.isEmpty()) {
            LOGGER.info("--Differences between template and index mapping--");
            entriesDiffering.forEach((key, value) -> LOGGER.info("  {} - current: {} target: {}",
                                                              key, value.rightValue(), value.leftValue()));
        }

        if (!isMappingChangeSafe(templateMapping, indexMapping)) {
            throw new UnsupportedMappingChangesException("Unsupported mapping changes");
        }

        final Set<String> fields = entriesDiffering.keySet();
        LOGGER.info("{}", fields.isEmpty() ?
                          "No changes required to existing properties."
                          : "Properties to be changed: " + fields);
        for (final String field : fields) {
            mappingsChanges.put(field, ((ValueDifference<?>) entriesDiffering.get(field)).leftValue());
        }

        final Map<String, Object> entriesOnlyInTemplate = diff.entriesOnlyOnLeft();
        final Set<String> newProperties = entriesOnlyInTemplate.keySet();
        LOGGER.info("{}", newProperties.isEmpty() ?
                          "No new properties to add."
                          : "Properties to be added: " + newProperties);
        mappingsChanges.putAll(entriesOnlyInTemplate);
        return mappingsChanges;
    }

    private boolean hasDynamicTemplateChanged(List<Object> dynamicTemplatesInTemplate, List<Object> dynamicTemplatesInIndex)
    {
        if(dynamicTemplatesInTemplate.size() != dynamicTemplatesInIndex.size())
        {
            return true;
        }
        return dynamicTemplatesInTemplate.retainAll(dynamicTemplatesInIndex);
    }

}
