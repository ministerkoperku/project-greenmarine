package com.koper.greenmarine.client;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DepthSnapshotsTest {
    @Test void packsWithoutDepthInputsDoNotRequireAHostTargetOrR32DeviceSupport() {
        try(var depths=new DepthSnapshots()) {
            assertDoesNotThrow(()->depths.prepare(Set.of(),null,null));
        }
    }
}
