package com.koper.greenmarine.api.pack;

import java.util.Objects;
import java.util.function.Function;

/** One manifest and its private resource namespace. Resolvers run only on catalog reload. */
public record PackSource(String origin, String manifest, Function<String, String> resources,
                         Function<String, byte[]> binaries) {
    public PackSource {
        Objects.requireNonNull(origin); Objects.requireNonNull(manifest);
        Objects.requireNonNull(resources); Objects.requireNonNull(binaries);
    }
}
