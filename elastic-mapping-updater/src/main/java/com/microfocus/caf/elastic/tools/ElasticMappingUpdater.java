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
import java.util.stream.Collectors;

import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.indices.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Sets.SetView;
import com.microfocus.caf.elastic.tools.exceptions.IndexNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.TemplateNotFoundException;
import com.microfocus.caf.elastic.tools.exceptions.UnexpectedResponseException;
import com.microfocus.caf.elastic.tools.exceptions.UnsupportedMappingChangesException;
import com.microfocus.caf.elastic.tools.utils.FlatMapUtil;

public class ElasticMappingUpdater {

    private static Logger LOGGER = LoggerFactory.getLogger(ElasticMappingUpdater.class);
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
        /*
         * // check if a v141_ index already exists for this tenant. if
         * (indexExists(this.tempIndexName)) { logger.info("Index exists");
         * return reindexTempToOrig(); }
         * 
         * if (reindexOrigToTemp(templateName) != true) { logger.
         * error("Failed to reindex from original name to temp name for template "
         * + templateName); return false; }
         * 
         * if (reindexTempToOrig(templateName) != true) { logger.
         * error("Failed to reindex from temp name to original name for template "
         * + templateName); return false; }
         */
    }

    public void updateIndexesForTemplate(final String templateName)
            throws IOException, TemplateNotFoundException, UnexpectedResponseException, IndexNotFoundException {
        LOGGER.info("Updating index(es) matching template '{}'", templateName);
        /*
         * this.originalIndexName = templateName + "_item"; this.tempIndexName =
         * TEMP_INDEX_NAME_PREFIX + templateName + "_item";
         */
        final IndexTemplateMetaData template = elasticRequestHandler.getTemplate(templateName);
        final String name = template.name();
        final int order = template.order();
        // final int version = template.version();
        final List<String> patterns = template.patterns();
        final ImmutableOpenMap<String, AliasMetaData> aliases = template.aliases();
        final Settings settings = template.settings();
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
            final Settings indexSettings = getIndexResponse.getSettings().get(indexName);
            final List<AliasMetaData> indexAliases = getIndexResponse.getAliases().get(indexName);
            final String numberOfShardsString = getIndexResponse.getSetting(indexName, "index.number_of_shards");
            final Integer numberOfShards = indexSettings.getAsInt("index.number_of_shards", null);

            LOGGER.info("------Comparing IndexMapping for '{}'", indexName);

            // TODO: fail if 'type' has changed for existing fields

            Map<String, Object> mappingsChanges;
            try {
                mappingsChanges = getMappingChanges((Map<String, Object>) templateTypeMappings.get("properties"),
                        (Map<String, Object>) indexTypeMappings.get("properties"));
            } catch (final UnsupportedMappingChangesException e) {
                LOGGER.error("Unsupported mapping changes for index : {}", indexName, e);
                continue;
            }
            LOGGER.info("------Mapping changes for index '{}': {}", indexName, mappingsChanges);
            final Map<String, Object> mappingsRequest = new HashMap<>();
            mappingsRequest.put("properties", mappingsChanges);

            // Add all dynamic_templates in template to index mapping
            final Object dynamicTemplatesInTemplate = templateTypeMappings.get("dynamic_templates");
            if(dynamicTemplatesInTemplate == null)
            {
                mappingsRequest.put("dynamic_templates", new ArrayList<>());
            }
            else
            {
                mappingsRequest.put("dynamic_templates", dynamicTemplatesInTemplate);
            }

            elasticRequestHandler.updateIndexMapping(indexName, mappingsRequest);

            getIndexResponse = elasticRequestHandler.getIndex(indexName);
            indexMappings = getIndexResponse.getMappings().get(indexName);
            indexTypeMappings = indexMappings.getSourceAsMap();
            LOGGER.info("------Updated mapping for index '{}': {}", indexName, indexTypeMappings);
        }
    }

    private boolean isMappingChangeSafe(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
            throws JsonProcessingException {
        boolean isMappingChangeSafe = true;
        final Map<String, Object> ftemplateMapping = FlatMapUtil.flatten(templateMapping);
        final Map<String, Object> findexMapping = FlatMapUtil.flatten(indexMapping);
        final MapDifference<String, Object> diff = Maps.difference(ftemplateMapping, findexMapping);
        //final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
        //LOGGER.info("Entries differing : {}", entriesDiffering);
        diff.entriesDiffering().forEach((key, value) -> LOGGER.info("Entries differing : {}", key, value));
        return isMappingChangeSafe;
    }

    private boolean isMappingChangeSafe(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping,
            final String propKey) throws JsonProcessingException {
        final Set<String> templateKeys = templateMapping.keySet();
        final Set<String> indexKeys = indexMapping.keySet();
        final SetView<String> templateOnly = Sets.difference(templateKeys, indexKeys);
        final SetView<String> indexOnly = Sets.difference(indexKeys, templateKeys);
        LOGGER.info("Entries in template only for key {}: {}", propKey, objectMapper.writeValueAsString(templateOnly));
        if (propKey != null && templateOnly.size() > 1) {
            return false;
        }
        LOGGER.info("Entries in index only: key {}: {}", propKey, objectMapper.writeValueAsString(indexOnly));
        final SetView<String> union = Sets.union(templateKeys, indexKeys);
        final List<String> allKeys = union.stream().collect(Collectors.toList());
        if (allKeys.size() > 0) {
            for (final String key : allKeys) {
                Map<String, Object> templateProps = null;
                Map<String, Object> indexProps = null;
                boolean isParent = false;
                final Object templateValObj = templateMapping.get(key);
                if (templateValObj != null && templateValObj instanceof Map) {
                    templateProps = (Map<String, Object>) templateValObj;
                }
                final Object indexValObj = indexMapping.get(key);
                if (indexValObj != null && indexValObj instanceof Map) {
                    indexProps = (Map<String, Object>) indexValObj;
                }
                if (templateProps != null && indexProps != null) {
                    LOGGER.info("Differing entries for key : '{}'", key);
                    if (templateProps.containsKey("properties")) {
                        LOGGER.info("Object/Nested type key {}", key);
                        isParent = true;
                    }
                    if(!isMappingChangeSafe(templateProps, indexProps, key))
                    {
                        return false;
                    }
                }
            }
        }
        return false;
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

        // final Map<String, Object> entriesOnlyInIndex = diff.entriesOnlyOnRight();
        // LOGGER.info("Entries only in Index: {}", objectMapper.writeValueAsString(entriesOnlyInIndex));

        final Map<String, Object> entriesOnlyInTemplate = diff.entriesOnlyOnLeft();
        LOGGER.info("Entries only in Template: {}", objectMapper.writeValueAsString(entriesOnlyInTemplate));
        mappingsChanges.putAll(entriesOnlyInTemplate);
        final Set<String> fields = entriesDiffering.keySet();
        for (final String field : fields) {
            mappingsChanges.put(field, ((ValueDifference) entriesDiffering.get(field)).leftValue());
        }
        final Map<String, Object> entriesInCommon = diff.entriesInCommon();
        return mappingsChanges;
    }

    private Map<String, Object> getDynamicTemplateChanges(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping)
            throws JsonProcessingException {
        final Map<String, Object> mappingsChanges = new HashMap<>();
        final MapDifference<String, Object> diff = Maps.difference(templateMapping, indexMapping);
        final Map<String, ValueDifference<Object>> entriesDiffering = diff.entriesDiffering();
        LOGGER.info("Differing entries: {}", objectMapper.writeValueAsString(entriesDiffering));
        // final Map<String, Object> entriesOnlyInIndex =
        // diff.entriesOnlyOnRight();
        // LOGGER.info("Entries only in Index: {}",
        // objectMapper.writeValueAsString(entriesOnlyInIndex));
        final Map<String, Object> entriesOnlyInTemplate = diff.entriesOnlyOnLeft();
        LOGGER.info("Entries only in Template: {}", objectMapper.writeValueAsString(entriesOnlyInTemplate));
        mappingsChanges.putAll(entriesOnlyInTemplate);
        final Set<String> fields = entriesDiffering.keySet();
        for (final String field : fields) {
            mappingsChanges.put(field, ((ValueDifference) entriesDiffering.get(field)).leftValue());
        }
        final Map<String, Object> entriesInCommon = diff.entriesInCommon();
        return mappingsChanges;
    }

    private void compareMappingsKeysOnly(final Map<String, Object> templateMapping, final Map<String, Object> indexMapping,
            final String propKey, boolean isNestedField, final Map<String, Object> mappings) throws JsonProcessingException {
        final Set<String> templateKeys = templateMapping.keySet();
        final Set<String> indexKeys = indexMapping.keySet();
        final SetView<String> templateOnly = Sets.difference(templateKeys, indexKeys);
        final SetView<String> indexOnly = Sets.difference(indexKeys, templateKeys);
        LOGGER.info("Entries in template only for key {}: {}", propKey, objectMapper.writeValueAsString(templateOnly));
        if (templateOnly.size() > 0) {
            final List<Map<String, Object>> propList = new ArrayList<>();
            for (final String tKey : templateOnly) {
                final Map<String, Object> propMapping = new HashMap<>();
                if (propKey != null && propKey.equalsIgnoreCase("properties")) {
                    propMapping.put(tKey, templateMapping.get(tKey));
                    propList.add(propMapping);
                }
            }
            if (mappings.get("properties") == null) {
                mappings.put("properties", propList);
            } else {
                ((List) mappings.get("properties")).add(propList);
            }
        }
        LOGGER.info("Entries in index only: key {}: {}", propKey, objectMapper.writeValueAsString(indexOnly));
        final SetView<String> union = Sets.union(templateKeys, indexKeys);
        final List<String> allKeys = union.stream().collect(Collectors.toList());
        if (allKeys.size() > 0) {
            for (final String key : allKeys) {
                Map<String, Object> templateProps = null;
                Map<String, Object> indexProps = null;
                boolean isParent = false;
                final Object templateValObj = templateMapping.get(key);
                if (templateValObj != null && templateValObj instanceof Map) {
                    templateProps = (Map<String, Object>) templateValObj;
                }
                final Object indexValObj = indexMapping.get(key);
                if (indexValObj != null && indexValObj instanceof Map) {
                    indexProps = (Map<String, Object>) indexValObj;
                }
                if (templateProps != null && indexProps != null) {
                    LOGGER.info("Differing entries for key : '{}'", key);
                    if (templateProps.containsKey("properties")) {
                        LOGGER.info("Object/Nested type key {}", key);
                        isParent = true;
                    }
                    compareMappingsKeysOnly(templateProps, indexProps, key, false, mappings);
                }
            }
        }
    }
}
