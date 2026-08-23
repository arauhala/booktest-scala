# A corrupt or absent cache entry never matches

absent / malformed / stale-classpath / wrong-snapshot / empty all read as MISS
only a manifest whose SHARED digests and per-case digest both match is a hit
