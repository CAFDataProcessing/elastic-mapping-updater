# elastic-mapping-updater

This is a tool to update the mapping of existing indexes in Elasticsearch.

Indexes if created using index templates would need to be updated if the template changes. This tool can be used to update the mapping for all such indexes.
The tool first finds all index templates in an Elasticsearch instance and updates the mappings of all indexes that match each template pattern.

- Only new property additions are allowed in the index mapping updates. Property deletions are ignored, i.e. not removed from index mappings.
- Changes to mapping parameters like "type" for an existing property are not supported. The safe index mapping changes will be applied and the unsupported mapping changes will be ignored. 
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
            final boolean dryRun,
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

    Usage: elastic-mapping-updater [-d] -n=<esHostNames> [-p=<esProtocol>]
                               [-r=<esRestPort>] [-s=<esConnectTimeout>]
                               [-t=<esSocketTimeout>]
        -d, --dryRun   If true, the tool lists the mapping changes to the indexes but
                       does not apply them. Defaults to false.
        -n, --esHostNames=<esHostNames>
                     Comma separated list of Elasticsearch hostnames
        -p, --esProtocol=<esProtocol>
                     The protocol to connect with Elasticsearch server.  Default
                       http
        -r, --esRestPort=<esRestPort>
                     Elasticsearch REST API port. Default 9200
        -s, --esConnectTimeout=<esConnectTimeout>
                     Determines the timeout until a new connection is fully
                       established. Default 5000 (5 seconds)
        -t, --esSocketTimeout=<esSocketTimeout>
                     This is the time of inactivity to wait for packets[data] to be
                       received. Default 60000 (1 minute)

Set the `ELASTIC_MAPPING_UPDATER_LOG_LEVEL` environment variable to configure the log level for the tool. Default is `INFO`.

# elastic-mapping-updater-cli-image
This module builds a Docker image for the command-line interface, potentially allowing for simpler usage in some environments.

Here is an example command for a dry run:

```
docker container run --rm \
    --env ELASTIC_MAPPING_UPDATER_LOG_LEVEL=DEBUG \
    cafdataprocessing/elastic-mapping-updater \
    --dryRun \
    --esHostNames=<elasticsearch-hostname>
```
