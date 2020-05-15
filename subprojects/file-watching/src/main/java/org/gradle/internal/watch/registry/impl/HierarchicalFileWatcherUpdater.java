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

package org.gradle.internal.watch.registry.impl;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import net.rubygrapefruit.platform.file.FileWatcher;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.watch.registry.FileWatcherUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HierarchicalFileWatcherUpdater implements FileWatcherUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchicalFileWatcherUpdater.class);

    private final Map<String, ImmutableList<Path>> watchedRootsForSnapshot = new HashMap<>();
    private final Multiset<Path> shouldWatchDirectories = HashMultiset.create();
    private final Set<Path> watchedRoots = new HashSet<>();
    private final List<Path> projectRootDirectories = new ArrayList<>();
    private final List<String> projectRootDirectoryPrefixes = new ArrayList<>();
    private final FileWatcher watcher;

    public HierarchicalFileWatcherUpdater(FileWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public void changed(Collection<CompleteFileSystemLocationSnapshot> removedSnapshots, Collection<CompleteFileSystemLocationSnapshot> addedSnapshots) {
        removedSnapshots.forEach(snapshot -> {
            ImmutableList<Path> previouslyWatchedRootsForSnapshot = watchedRootsForSnapshot.remove(snapshot.getAbsolutePath());
            Multisets.removeOccurrences(shouldWatchDirectories, previouslyWatchedRootsForSnapshot);
        });
        addedSnapshots.forEach(snapshot -> {
            ImmutableList<Path> directoriesToWatch = WatchRootUtil.getDirectoriesToWatch(snapshot);
            shouldWatchDirectories.addAll(directoriesToWatch);
            watchedRootsForSnapshot.put(snapshot.getAbsolutePath(), directoriesToWatch);
        });
        updateWatchedDirectories();
    }

    @Override
    public void updateProjectRootDirectories(Collection<File> updatedProjectRootDirectories) {
        Set<Path> rootPaths = updatedProjectRootDirectories.stream()
            .map(File::toPath)
            .map(Path::toAbsolutePath)
            .collect(Collectors.toSet());
        projectRootDirectories.clear();
        projectRootDirectories.addAll(WatchRootUtil.resolveRootsToWatch(rootPaths));
        projectRootDirectoryPrefixes.clear();
        projectRootDirectories.stream()
            .map(path -> path.toString() + File.separator)
            .forEach(projectRootDirectoryPrefixes::add);

        updateWatchedDirectories();
    }

    private void updateWatchedDirectories() {
        Set<Path> directoriesToWatch = new HashSet<>();
        shouldWatchDirectories.elementSet().forEach(shouldWatchDirectory -> {
            for (int i = 0; i < projectRootDirectoryPrefixes.size(); i++) {
                String prefix = projectRootDirectoryPrefixes.get(i);
                if (shouldWatchDirectory.toString().startsWith(prefix)) {
                    directoriesToWatch.add(projectRootDirectories.get(i));
                }
            }
            directoriesToWatch.add(shouldWatchDirectory);
        });

        updateWatchedDirectories(WatchRootUtil.resolveRootsToWatch(directoriesToWatch));
    }

    private void updateWatchedDirectories(Set<Path> newWatchRoots) {
        Set<Path> watchRootsToRemove = new HashSet<>(watchedRoots);
        if (newWatchRoots.isEmpty()) {
            LOGGER.info("Not watching anything anymore");
        }
        watchRootsToRemove.removeAll(newWatchRoots);
        newWatchRoots.removeAll(watchedRoots);
        if (newWatchRoots.isEmpty() && watchRootsToRemove.isEmpty()) {
            return;
        }
        LOGGER.info("Watching {} directory hierarchies to track changes", newWatchRoots.size());
        if (!watchRootsToRemove.isEmpty()) {
            watcher.stopWatching(watchRootsToRemove.stream()
                .map(Path::toFile)
                .collect(Collectors.toList())
            );
            watchedRoots.removeAll(watchRootsToRemove);
        }
        if (!newWatchRoots.isEmpty()) {
            watcher.startWatching(newWatchRoots.stream()
                .map(Path::toFile)
                .collect(Collectors.toList())
            );
            watchedRoots.addAll(newWatchRoots);
        }
    }
}
