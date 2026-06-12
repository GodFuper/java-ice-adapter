package com.faforever.iceadapter.util;

import lombok.experimental.UtilityClass;

import java.util.Collection;

@UtilityClass
public class CollectionUtils {

    public boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }
}
