package com.grocery.common.id;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void generatesVersion7Uuids() {
        assertThat(Ids.newId().version()).isEqualTo(7);
    }

    @Test
    void idsAreTimeOrderedAndUnique() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(Ids.newId());
        }
        List<String> asStrings = ids.stream().map(UUID::toString).toList();
        assertThat(asStrings).isSorted().doesNotHaveDuplicates();
    }
}
