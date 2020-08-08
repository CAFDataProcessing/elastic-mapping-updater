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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
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
import com.github.cafdataprocessing.elastic.tools.utils.FlatMapUtil;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;

public final class ElasticMappingUpdater
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdater.class);

    private static final String MAPPING_PROPS_KEY = "properties";
    private static final String MAPPING_DYNAMIC_TEMPLATES_KEY = "dynamic_templates";
    private static final String MAPPING_TYPE_KEY = "type";

    private static final Set<String> UNSUPPORTED_PARAMS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("type", "doc_values", "store")));

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
     * @param esUsername Elasticsearch username
     * @param esPassword Elasticsearch password
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
        final String esUsername,
        final String esPassword,
        final int esConnectTimeout,
        final int esSocketTimeout
    ) throws IOException, GetIndexException, GetTemplatesException, UnexpectedResponseException
    {
        final ElasticMappingUpdater updater
            = new ElasticMappingUpdater(dryRun, esHostNames, esProtocol, esRestPort, esUsername, esPassword,
                                        esConnectTimeout, esSocketTimeout);
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
        final String esUsername,
        final String esPassword,
        final int esConnectTimeout,
        final int esSocketTimeout)
    {
        this.dryRun = dryRun;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        final ElasticSettings elasticSettings
            = new ElasticSettings(esProtocol, esHostNames, esRestPort, esUsername, esPassword,
                                  esConnectTimeout, esSocketTimeout);

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
            if (!indexNeedsUpdates) {
                LOGGER.info("No index mapping changes required.");
            }

            if (!dryRun) {
                // Update the index mapping
                if (indexNeedsUpdates) {
                    LOGGER.info("Updating index mapping...");
                    elasticRequestHandler.updateIndexMapping(indexName, mappingsRequest);
                    LOGGER.info("Index mapping updated");
                }
            }
        }
        LOGGER.info("---- Analysis of indexes matching template '{}' completed ----", templateName);
    }

    private static boolean isMappingChangeSafe(
        final Map<String, Object> templateMapping,
        final Map<String, Object> indexMapping,
        final Set<String> allowedFieldDifferences,
        final Set<String> unSupportedFieldDifferences
    )
        throws JsonProcessingException
    {
        final Map<String, Object> ftemplateMapping = FlatMapUtil.flatten(templateMapping);
        final Map<String, Object> findexMapping = FlatMapUtil.flatten(indexMapping);
        final MapDifference<String, Object> diff = Maps.difference(ftemplateMapping, findexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
        boolean safeChangesOnly;
        if (entriesDiffering.isEmpty()) {
            safeChangesOnly = true;
        } else {
            // Elasticsearch would throw IllegalArgumentException if any such
            // change is included in the index mapping updates
            entriesDiffering.forEach((key, value) -> {
                LOGGER.warn("Unsupported mapping change : {} - current: {} target: {}",
                            key, value.rightValue(), value.leftValue());
                if(key.contains(MAPPING_PROPS_KEY))
                {
                    // nested field
                    unSupportedFieldDifferences.add(key);
                }
                else
                {
                    allowedFieldDifferences.remove(getFieldName(key));
                }
            });
            safeChangesOnly = false;
        }
        final Set<String> unsupportedParamChanges = new HashSet<>();
        final Map<String, Object> entriesOnlyInIndex = diff.entriesOnlyOnRight();
        LOGGER.debug("Fields in index whose param is being removed : {}", entriesOnlyInIndex);
        // Field parameters that are currently set on a field in the index are now being removed
        entriesOnlyInIndex.entrySet().stream()
                .filter(e -> isUnsupportedParam(e.getKey()))
                .forEach(e -> {
                        LOGGER.warn("Unsupported mapping change : field parameter being removed : {}:{}", e.getKey(), e.getValue());
                        unsupportedParamChanges.add(e.getKey());
                    }
                );
        if(!unsupportedParamChanges.isEmpty())
        {
            unSupportedFieldDifferences.addAll(unsupportedParamChanges);
            safeChangesOnly = false;
        }
        return safeChangesOnly;
    }

    private static boolean isUnsupportedParam(final String fieldPath)
    {
        final String paramName = getParamName(fieldPath);
        return UNSUPPORTED_PARAMS.contains(paramName);
    }

    private static String getFieldName(final String key)
    {
        return key.split(Pattern.quote("/"))[1];
    }

    private static String getParamName(final String key)
    {
        final String[] path = key.split(Pattern.quote("/"));
        return path[path.length - 1];
    }

    private Map<String, Object> getMappingChanges(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
        throws JsonProcessingException
    {
        final Map<String, Object> mappingsChanges = new HashMap<>();
        final MapDifference<String, Object> diff = Maps.difference(templateMapping, indexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
        final Set<String> allowedFieldDifferences = new HashSet<>(entriesDiffering.keySet());
        final Set<String> unSupportedFieldDifferences = new HashSet<>();

        boolean unsupportedObjectChanges = false;
        if (!entriesDiffering.isEmpty()) {
            // Template has mapping changes to existing properties
            LOGGER.info("--Differences between template and index mapping--");
            entriesDiffering.forEach((key, value) -> LOGGER.info("  {} - current: {} target: {}",
                                                                 key, value.rightValue(), value.leftValue()));

            // Check if 'type' has changed for object/nested properties
            final Map<String, ValueDifference<Object>> typeDifferences = entriesDiffering.entrySet().stream()
                .filter(e -> ((Map<?, ?>) (e.getValue().leftValue())).containsKey(MAPPING_PROPS_KEY)
                && (((Map<?, ?>) (e.getValue().leftValue())).containsKey(MAPPING_TYPE_KEY)
                || ((Map<?, ?>) (e.getValue().rightValue())).containsKey(MAPPING_TYPE_KEY)))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));

            // Check if any fields have parameters being removed that may not be reflected in the index mapping update
            final Map<String, ValueDifference<Object>> entriesBeingRemoved = entriesDiffering.entrySet().stream()
                .filter(e -> !typeDifferences.containsKey(e.getKey()))
                .filter(e -> ((Map<?, ?>) (e.getValue().rightValue())).size() > ((Map<?, ?>) (e.getValue().leftValue())).size())
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
            if (!entriesBeingRemoved.isEmpty()) {
                entriesBeingRemoved.forEach(
                    (key, value)
                    -> LOGGER.warn(
                        "Mapping changes with some parameters being removed may not be applied for : {} - current: {} target: {}",
                        key, value.rightValue(), value.leftValue())
                );
                unsupportedObjectChanges = true;
            }

            if (!typeDifferences.isEmpty()) {
                typeDifferences.forEach(
                    (key, value) -> {
                        LOGGER.warn("Unsupported object/nested mapping change : {} - current: {} target: {}",
                                    key, value.rightValue(), value.leftValue());
                        allowedFieldDifferences.remove(key);
                    });
                unsupportedObjectChanges = true;
            }
        }

        if (!isMappingChangeSafe(templateMapping, indexMapping, allowedFieldDifferences, unSupportedFieldDifferences)
                || unsupportedObjectChanges) {
            LOGGER.warn("Unsupported mapping changes will not be applied to the index.");
        }

        LOGGER.info("{}", allowedFieldDifferences.isEmpty()
                    ? "No changes required to existing properties."
                    : "Properties to be changed: " + allowedFieldDifferences);
        for (final String field : allowedFieldDifferences) {
            mappingsChanges.put(field, ((ValueDifference<?>) entriesDiffering.get(field)).leftValue());
        }

        // Remove any unsupportedMappings
        LOGGER.info("{}", unSupportedFieldDifferences.isEmpty()
                ? "No unsupported field changes."
                : "Unsupported field changes to be removed: " + unSupportedFieldDifferences);
        for (final String field : unSupportedFieldDifferences) {
            removeUnsupportedFieldChange(mappingsChanges, field);
        }

        // Add new properties defined in the template
        final Map<String, Object> entriesOnlyInTemplate = diff.entriesOnlyOnLeft();
        final Set<String> newProperties = entriesOnlyInTemplate.keySet();
        LOGGER.info("{}", newProperties.isEmpty()
                    ? "No new properties to add."
                    : "Properties to be added: " + newProperties);
        mappingsChanges.putAll(entriesOnlyInTemplate);
        return mappingsChanges;
    }

    private boolean hasDynamicTemplateChanged(List<Object> dynamicTemplatesInTemplate, List<Object> dynamicTemplatesInIndex)
    {
        if (dynamicTemplatesInTemplate.size() != dynamicTemplatesInIndex.size()) {
            return true;
        }
        return dynamicTemplatesInTemplate.retainAll(dynamicTemplatesInIndex);
    }

    @SuppressWarnings("unchecked")
    private void removeUnsupportedFieldChange(final Map<String, Object> mappingsChanges, final String fieldPath) {
        final List<String> path = Arrays.asList(StringUtils.split(fieldPath.trim(), "/"));
        final int size = path.size();
        int index = 0;
        if(size == 2)
        {
            // for a field path like, /LANGUAGE_CODES/properties/CODE/type, the 'fieldName' to be removed here is 'CODE'
            // remove property with unsupported mapping change
            final String fieldName = path.get(0);
            mappingsChanges.remove(fieldName);
        }
        else
        {
            while (index != size - 2) {
                final int i = index++;
                final String currentFieldName = path.get(i);
                final Object field = mappingsChanges.get(path.get(i));
                if (field instanceof Map<?, ?>) {
                    final Map<String, Object> currentField = (Map<String, Object>) field;
                    final String subPath = fieldPath.substring(fieldPath.indexOf(currentFieldName) + currentFieldName.length());
                    removeUnsupportedFieldChange(currentField, subPath);
                }
            }
        }
    }

}
