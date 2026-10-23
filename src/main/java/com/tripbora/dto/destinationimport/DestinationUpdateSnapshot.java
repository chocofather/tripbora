package com.tripbora.dto.destinationimport;

import com.tripbora.model.DestinationType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Raw, unlocalized values. Null values and absent language rows remain distinct. */
public record DestinationUpdateSnapshot(Long destinationId, DestinationType type,
                                        String sourceType, String externalContentId,
                                        Map<String, Object> values, Map<String, String> labels) {
    public DestinationUpdateSnapshot {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }
}
