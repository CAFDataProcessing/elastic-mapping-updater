# elastic-mapping-updater

This is a tool to update the mapping of existing indexes in Elasticsearch.

Indexes if created using index templates would need to be updated if the template changes. This tool can be used to update the mapping for all such indexes.
The tool first finds all index templates in an Elasticsearch instance and updates the mappings of all indexes that match each template pattern.

- Only new property additions are allowed in the index mapping updates. Property deletions are ignored, i.e. not removed from index mappings.
- If any of the mapping parameters like "type" has changed for an existing property the index mapping changes will not be applied.
- If the updated template has any "dynamic_templates" defined in the mapping they will overwrite the existing "dynamic_templates" in the index mapping.

It can be used from another Java project by including the following dependency:

```xml
<dependency>
    <groupId>com.github.cafdataprocessing.elastic</groupId>
    <artifactId>elastic-mapping-updater</artifactId>
</dependency>
```

It makes the following `static` method available in the `ElasticMappingUpdater` class:

```java
public static void update(
            final String esProtocol,
            final String esHostNames,
            final int esRestPort,
            final int esConnectTimeout,
            final int esSocketTimeout)
    throws IOException, TemplateNotFoundException, GetIndexException,
           GetTemplateException, UnexpectedResponseException
```

# elastic-mapping-updater-cli
This module provides a simple command-line interface which wraps the `ElasticMappingUpdater.update()` function.

    Usage: elastic-mapping-updater -n=<esHostNames> [-p=<esProtocol>]
                               [-r=<esRestPort>] [-s=<esConnectTimeout>]
                               [-t=<esSocketTimeout>]
        -n, --esHostNames=<esHostNames>
                Comma separated list of Elasticsearch hostnames
        -p, --esProtocol=<esProtocol>
                The protocol to connect with Elasticsearch server.  Default http
        -r, --esRestPort=<esRestPort>
                Elasticsearch REST API port. Default 9200
        -s, --esConnectTimeout=<esConnectTimeout>
                Determines the timeout until a new connection is fully established. Default 5000 (5 seconds)
        -t, --esSocketTimeout=<esSocketTimeout>
                This is the time of inactivity to wait for packets[data] to be received. Default 60000 (1 minute)
