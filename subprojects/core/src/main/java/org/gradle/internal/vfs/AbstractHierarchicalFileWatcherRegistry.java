/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.vfs;

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.vfs.watch.WatchRootUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class AbstractHierarchicalFileWatcherRegistry extends AbstractEventDrivenFileWatcherRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractHierarchicalFileWatcherRegistry.class);

    private final Map<String, CompleteFileSystemLocationSnapshot> watchedSnapshots = new HashMap<>();
    private final Set<Path> watchedRoots = new HashSet<>();
    private final Set<Path> mustWatchDirectories = new HashSet<>();

    public AbstractHierarchicalFileWatcherRegistry(FileWatcherCreator watcherCreator, Predicate<String> watchFilter, ChangeHandler handler) {
        super(watcherCreator, watchFilter, handler);
    }

    @Override
    protected void handleChanges(Collection<CompleteFileSystemLocationSnapshot> removedSnapshots, Collection<CompleteFileSystemLocationSnapshot> addedSnapshots) {
        removedSnapshots.forEach(snapshot -> watchedSnapshots.remove(snapshot.getAbsolutePath()));
        addedSnapshots.forEach(snapshot -> watchedSnapshots.put(snapshot.getAbsolutePath(), snapshot));
        updateWatchedDirectories();
    }

    @Override
    public void updateMustWatchDirectories(Collection<File> updatedWatchDirectories) {
        Set<String> rootPaths = updatedWatchDirectories.stream()
            .map(File::getAbsolutePath)
            .collect(Collectors.toSet());
        mustWatchDirectories.clear();
        mustWatchDirectories.addAll(WatchRootUtil.resolveRootsToWatch(rootPaths));
        updateWatchedDirectories();
    }

    private void updateWatchedDirectories() {
        Set<String> mustWatchDirectoryPrefixes = ImmutableSet.copyOf(
            mustWatchDirectories.stream()
                .map(path -> path.toString() + File.separator)
                ::iterator
        );
        Set<Path> directoriesToWatch = watchedSnapshots.values().stream()
            .filter(snapshot -> getWatchFilter().test(snapshot.getAbsolutePath()) || startsWithAnyPrefix(snapshot.getAbsolutePath(), mustWatchDirectoryPrefixes))
            .flatMap(WatchRootUtil::getDirectoriesToWatch)
            .collect(Collectors.toSet());
        directoriesToWatch.addAll(mustWatchDirectories);

        updateWatchedDirectories(WatchRootUtil.resolveRootsToWatchFromPaths(directoriesToWatch));
    }

    private void updateWatchedDirectories(Set<Path> newWatchRoots) {
        Set<Path> watchRootsToRemove = new HashSet<>(watchedRoots);
        if (newWatchRoots.isEmpty()) {
            LOGGER.warn("Not watching anything anymore");
        }
        watchRootsToRemove.removeAll(newWatchRoots);
        newWatchRoots.removeAll(watchedRoots);
        if (newWatchRoots.isEmpty() && watchRootsToRemove.isEmpty()) {
            return;
        }
        LOGGER.warn("Watching {} directory hierarchies to track changes", newWatchRoots.size());
        getWatcher().startWatching(newWatchRoots.stream()
            .map(Path::toFile)
            .collect(Collectors.toList())
        );
        getWatcher().stopWatching(watchRootsToRemove.stream()
            .map(Path::toFile)
            .collect(Collectors.toList())
        );
        watchedRoots.addAll(newWatchRoots);
        watchedRoots.removeAll(watchRootsToRemove);
    }

    private static boolean startsWithAnyPrefix(String path, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
