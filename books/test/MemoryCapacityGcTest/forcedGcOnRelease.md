# memoryCapacity.release forces GC; capacity.release does not

memoryCapacity forced gc on every release (10/10): true
regular capacity did not force gc (10 releases, delta < 10): true
memoryCapacity gc count strictly exceeds regular: true
