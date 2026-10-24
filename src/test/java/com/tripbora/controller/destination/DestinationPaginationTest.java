package com.tripbora.controller.destination;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DestinationPaginationTest {
    @Test
    void windowsStayBoundedAtEveryPageAndAlwaysContainCurrentPage() {
        for (int total : new int[]{1, 7, 23, 100, 1000}) {
            for (int page = 1; page <= total; page++) {
                for (int limit : new int[]{3, 7}) {
                    var numbers = DestinationPagination.numbers(page, total, limit);
                    int current = page;
                    assertThat(numbers).contains(current);
                    assertThat(numbers.stream().filter(n -> n > 0).toList())
                            .hasSize(Math.min(total, limit)).isSorted().doesNotHaveDuplicates();
                    assertThat(numbers).allMatch(n -> n >= 0 && n <= total);
                    if (total <= limit) assertThat(numbers).doesNotContain(0);
                }
            }
        }
        assertThat(DestinationPagination.numbers(12, 23, 3)).containsExactly(0, 11, 12, 13, 0);
        assertThat(DestinationPagination.numbers(1, 100, 7)).containsExactly(1, 2, 3, 4, 5, 6, 7, 0);
        assertThat(DestinationPagination.numbers(100, 100, 7)).containsExactly(0, 94, 95, 96, 97, 98, 99, 100);
        assertThat(DestinationPagination.numbers(Integer.MAX_VALUE, Integer.MAX_VALUE, 3))
                .containsExactly(0, Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE);
    }
}
