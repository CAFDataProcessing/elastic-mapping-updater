# elastic-mapping-updater

This is a tool to update the mapping of existing indexes in ElasticSearch.

Indexes if created using index templates would need to be updated if the template changes. This tool can be used to update the mapping for all such indexes.
The tool first finds all index templates in an ElasticSearch instance and updates the mappings of all indexes that match each template pattern.

- Only new property additions are allowed in the index mapping updates. Property deletions are ignored, i.e. not removed from index mappings.
- If any of the mapping parameters like "type" has changed for an existing property the index mapping changes will not be applied.
- If the updated template has any "dynamic_templates" defined in the mapping they will overwrite the existing "dynamic_templates" in the index mapping.
