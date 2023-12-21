/*
 * Copyright 2020-2024 Open Text.
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
package com.github.cafdataprocessing.elastic.tools.utils;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This is a utility to help compare complex JSON documents, with nested objects and arrays.
 * <p>
 * This utility creates a simple map of all the nested JSON nodes as 'JSON pointer' keys for easier comparison.
 * <pre>
 * Sample JSON:
 *     {
 *       "name": {
 *         "first": "John",
 *         "last": "Doe"
 *       },
 *       "address": Baker st,
 *       "phones": [
 *         {
 *           "number": "5108887653",
 *           "type": "home"
 *         },
 *         {
 *           "number": "9253238126",
 *           "type": "mobile"
 *         }
 *       ],
 *       "groups": [
 *         "java",
 *         "machine-learning"
 *       ]
 *     }
 * </pre>
 *
 * Would be flattened to:
 * <pre>
 *     /name/first: John
 *     /name/last: Doe
 *     /address: Baker st
 *     /phones/1/number: 5108887653
 *     /phones/1/type: home
 *     /phones/1/number: 9253238126
 *     /phones/1/type: mobile
 *     /groups/0: java
 *     /groups/1: machine-learning
 * </pre>
 */
public final class FlatMapUtil
{
    private FlatMapUtil()
    {
    }

    /**
     * A JSON document with nested objects is represented as a map of maps.<br>
     * Convert a map of maps to a simple map with 'JSON pointer' keys to identify a specific value within a JSON document.
     *
     * @param map Map representing a JSON document to be flattened
     * @return Map with keys representing paths to all values in the JSON document
     */
    public static Map<String, Object> flatten(final Map<String, Object> map)
    {
        return map.entrySet().stream().flatMap(FlatMapUtil::flatten).collect(LinkedHashMap::new,
                                                                             (m, e) -> m.put("/" + e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    private static Stream<Map.Entry<String, Object>> flatten(final Map.Entry<String, Object> entry)
    {
        if (entry == null) {
            return Stream.empty();
        }

        if (entry.getValue() instanceof Map<?, ?>) {
            return ((Map<?, ?>) entry.getValue()).entrySet().stream()
                .flatMap(e -> flatten(new AbstractMap.SimpleEntry<>(entry.getKey() + "/" + e.getKey(), e.getValue())));
        }

        if (entry.getValue() instanceof List<?>) {
            List<?> list = (List<?>) entry.getValue();
            return IntStream.range(0, list.size())
                .mapToObj(i -> new AbstractMap.SimpleEntry<String, Object>(entry.getKey() + "/" + i, list.get(i)))
                .flatMap(FlatMapUtil::flatten);
        }

        return Stream.of(entry);
    }
}
