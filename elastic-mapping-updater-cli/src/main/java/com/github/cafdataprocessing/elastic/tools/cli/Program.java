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
package com.github.cafdataprocessing.elastic.tools.cli;

import java.util.Objects;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.github.cafdataprocessing.elastic.tools.ElasticMappingUpdater;

@Command(name = "elastic-mapping-updater")
public final class Program implements Callable<Void>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Program.class);

    @Option(
        names = {"-d", "--dryRun"},
        paramLabel = "<dryRun>",
        defaultValue = "false",
        description = "If true, the tool lists the mapping changes to the indexes but does not apply them. Defaults to false."
    )
    private boolean dryRun;

    @Option(
        names = {"-i", "--reIndex"},
        paramLabel = "<reIndex>",
        defaultValue = "false",
        description = "If true, the tool reindexes data from the indexes that have unsupported mapping changes. Defaults to false."
    )
    private boolean reIndex;

    @Option(
        names = {"-n", "--esHostNames"},
        paramLabel = "<esHostNames>",
        required = true,
        description = "Comma separated list of Elasticsearch hostnames"
    )
    private String esHostNames;

    @Option(
        names = {"-p", "--esProtocol"},
        paramLabel = "<esProtocol>",
        defaultValue = "http",
        description = "The protocol to connect with Elasticsearch server.  Default http"
    )
    private String esProtocol;

    @Option(
        names = {"-r", "--esRestPort"},
        paramLabel = "<esRestPort>",
        defaultValue = "9200",
        description = "Elasticsearch REST API port. Default 9200"
    )
    private int esRestPort;

    @Option(
        names = {"-s", "--esConnectTimeout"},
        paramLabel = "<esConnectTimeout>",
        defaultValue = "5000",
        description = "Determines the timeout until a new connection is fully established. Default 5000 (5 seconds)"
    )
    private int esConnectTimeout;

    @Option(
        names = {"-t", "--esSocketTimeout"},
        paramLabel = "<esSocketTimeout>",
        defaultValue = "60000",
        description = "This is the time of inactivity to wait for packets[data] to be received. Default 60000 (1 minute)"
    )
    private int esSocketTimeout;

    private Program()
    {
    }

    public static void main(final String[] args)
    {
        int exitCode = new CommandLine(new Program()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Void call() throws Exception
    {
        if (Objects.isNull(esHostNames)) {
            LOGGER.error("Elasticsearch hostname must be specified.");
            CommandLine.usage(new Program(), System.out);
        } else {
            ElasticMappingUpdater.update(dryRun, reIndex, esHostNames, esProtocol, esRestPort, esConnectTimeout, esSocketTimeout);
        }
        return null;
    }
}
