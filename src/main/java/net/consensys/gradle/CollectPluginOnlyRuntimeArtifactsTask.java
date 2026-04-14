/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.consensys.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import groovy.json.JsonBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class CollectPluginOnlyRuntimeArtifactsTask extends DefaultTask {
  static final String TASK_NAME = "collectPluginOnlyRuntimeArtifacts";

  /**
   * Kept for backward compatibility; no longer set internally. External consumers that read this
   * ext property should migrate to reading {@link #getCatalogFile()} instead.
   */
  static final String BESU_PLUGIN_ONLY_RUNTIME_ARTIFACTS =
      CollectPluginOnlyRuntimeArtifactsTask.class.getName() + ".pluginOnlyRuntimeArtifacts";

  static final String PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH =
      "reports/dependencies/plugin-artifacts-catalog.json";

  /** Runtime classpath artifact files — used for UP-TO-DATE checks. */
  @Classpath
  public abstract ConfigurableFileCollection getRuntimeArtifacts();

  /**
   * Maps each artifact filename to its full module coordinate ({@code group:name:version}).
   * Wired from {@code ArtifactCollection.getResolvedArtifacts()} in the plugin's {@code apply},
   * so only the resolved {@code Map<String,String>} is stored by the configuration cache.
   */
  @Input
  public abstract MapProperty<String, String> getArtifactToModule();

  /**
   * Module coordinates ({@code group:name}) of all artifacts already provided by the Besu runtime.
   * Populated lazily via a {@code Provider} so the resolved {@code List<String>} (not {@code
   * Project}) is what the configuration cache serializes.
   */
  @Input
  public abstract ListProperty<String> getBesuProvidedCoordinates();

  /** Besu version string used as metadata in the generated catalog file. */
  @Input
  public abstract Property<String> getBesuVersion();

  /** The JSON catalog file listing every plugin-only runtime artifact. */
  @OutputFile
  public abstract RegularFileProperty getCatalogFile();

  @TaskAction
  public void collectRuntimeArtifacts() {
    Set<String> besuCoordinates = Set.copyOf(getBesuProvidedCoordinates().get());
    Set<String> oldCoordinates =
        BesuOld2NewCoordinatesMapping.getOld2NewCoordinates().keySet();
    Map<String, String> artifactToModule = getArtifactToModule().get();

    getLogger().lifecycle("Collecting pluginOnlyRuntimeArtifacts");

    // ArtifactCollection already returns the full transitive closure, so a recursive tree walk
    // is no longer needed — just filter each artifact against the known Besu coordinates.
    Map<String, String> pluginOnlyArtifacts = new HashMap<>();
    for (Map.Entry<String, String> entry : artifactToModule.entrySet()) {
      String filename = entry.getKey();
      String coordinate = entry.getValue(); // group:name:version
      String groupAndName = toGroupAndName(coordinate);

      boolean providedByBesu =
          besuCoordinates.contains(groupAndName) || oldCoordinates.contains(groupAndName);

      if (!providedByBesu) {
        getLogger().lifecycle("Plugin only runtime dependency {} ({})", filename, coordinate);
        pluginOnlyArtifacts.put(filename, coordinate);
      } else {
        getLogger().lifecycle("Excluding Besu-provided artifact {} ({})", filename, coordinate);
      }
    }

    getLogger()
        .lifecycle("Collected pluginOnlyRuntimeClasspath artifacts {}", pluginOnlyArtifacts);
    generateArtifactsCatalog(pluginOnlyArtifacts);
  }

  /** Extracts {@code group:name} from a {@code group:name:version} coordinate string. */
  private static String toGroupAndName(String coordinate) {
    int lastColon = coordinate.lastIndexOf(':');
    return lastColon > 0 ? coordinate.substring(0, lastColon) : coordinate;
  }

  private void generateArtifactsCatalog(Map<String, String> pluginOnlyArtifacts) {
    List<Map<String, String>> jsonDependencies =
        pluginOnlyArtifacts.entrySet().stream()
            .map(
                e -> {
                  String[] parts = e.getValue().split(":", 3);
                  return Map.of(
                      "group", parts.length > 0 ? parts[0] : "",
                      "name", parts.length > 1 ? parts[1] : "",
                      "version", parts.length > 2 ? parts[2] : "",
                      "filename", e.getKey());
                })
            .collect(Collectors.toList());

    Map<String, Object> doc =
        Map.of("besuVersion", getBesuVersion().get(), "dependencies", jsonDependencies);

    JsonBuilder jsonBuilder = new JsonBuilder(doc);
    String json = jsonBuilder.toPrettyString();
    getLogger().lifecycle("Generated artifacts catalog {}", json);

    File catalogFile = getCatalogFile().get().getAsFile();
    catalogFile.getParentFile().mkdirs();
    try {
      Files.writeString(catalogFile.toPath(), json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to write plugin artifacts catalog to file " + catalogFile, e);
    }
  }
}
