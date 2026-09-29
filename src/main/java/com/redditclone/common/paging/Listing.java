package com.redditclone.common.paging;

import java.util.List;

/**
 * Reddit-style listing envelope: {"kind":"Listing","data":{"after":...,"before":null,"children":[...]}}
 */
public record Listing<T>(String kind, ListingData<T> data) {

    public record ListingData<T>(String after, String before, List<Thing<T>> children) {
    }

    public static <T> Listing<T> of(List<Thing<T>> children, String after) {
        return new Listing<>("Listing", new ListingData<>(after, null, children));
    }
}
