package com.tripbora.controller.destination;

import java.util.ArrayList;
import java.util.List;

/** 0은 이동할 수 없는 생략 표시다. 첫/마지막 이동은 별도 버튼으로 제공한다. */
final class DestinationPagination {
    private DestinationPagination() {}

    static List<Integer> numbers(int page, int total, int limit) {
        if (total < 1) return List.of();
        int start = Math.max(1, Math.min(page - limit / 2, total - limit + 1));
        int end = (int) Math.min(total, (long) start + limit - 1);
        List<Integer> numbers = new ArrayList<>();
        if (start > 1) numbers.add(0);
        for (long number = start; number <= end; number++) numbers.add((int) number);
        if (end < total) numbers.add(0);
        return numbers;
    }

    static int page(int requested, int count, int size) {
        int last = (int) Math.max(1, (count + (long) size - 1) / size);
        return Math.max(1, Math.min(requested, last));
    }

    static String sort(String requested) {
        return List.of("default", "latest", "views", "bookmarks").contains(requested) ? requested : "default";
    }
}
