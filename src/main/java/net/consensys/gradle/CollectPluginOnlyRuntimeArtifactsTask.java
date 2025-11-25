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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import groovy.json.JsonBuilder;
import net.consensys.gradle.BesuPluginLibrary.BesuProvidedDependency;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.TaskAction;

public abstract class CollectPluginOnlyRuntimeArtifactsTask extends DefaultTask {
  static final String TASK_NAME = "collectPluginOnlyRuntimeArtifacts";
  static final String BESU_PLUGIN_ONLY_RUNTIME_ARTIFACTS =
      CollectPluginOnlyRuntimeArtifactsTask.class.getName() + ".pluginOnlyRuntimeArtifacts";
  static final String PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH =
      "reports/dependencies/plugin-artifacts-catalog.json";

  @Classpath
  public abstract ConfigurableFileCollection getRuntimeArtifacts();

  @TaskAction
  public void collectRuntimeArtifacts() {
    Configuration runtimeClasspath = getProject().getConfigurations().getByName("runtimeClasspath");
    List<BesuProvidedDependency> besuProvidedDependencies =
        (List<BesuProvidedDependency>)
            getProject()
                .getExtensions()
                .getExtraProperties()
                .get(BesuPluginLibrary.BESU_PROVIDED_DEPENDENCIES);

    Set<ResolvedDependency> alreadyEvaluated = new HashSet<>();
    Map<File, ResolvedDependency> pluginOnlyRuntimeArtifacts = new HashMap<>();
    getLogger().lifecycle("Collecting pluginOnlyRuntimeArtifacts");
    Set<ResolvedDependency> firstLevelDeps =
        runtimeClasspath.getResolvedConfiguration().getFirstLevelModuleDependencies();

    // Process first-level dependencies
    for (ResolvedDependency dependency : firstLevelDeps) {
      alreadyEvaluated.add(dependency);
      getLogger().lifecycle("Processing {}", dependency.getChildren());
      if (!providedByBesu(besuProvidedDependencies, dependency)) {
        getLogger()
            .lifecycle(
                "Plugin only runtime dependency {}, artifacts {}",
                dependency,
                dependency.getModuleArtifacts());
        dependency
            .getModuleArtifacts()
            .forEach(artifact -> pluginOnlyRuntimeArtifacts.put(artifact.getFile(), dependency));
      }

      processTransitiveDependencies(
          besuProvidedDependencies, dependency, pluginOnlyRuntimeArtifacts, alreadyEvaluated);
    }

    getLogger()
        .lifecycle("Collected pluginOnlyRuntimeClasspath artifacts {}", pluginOnlyRuntimeArtifacts);

    generateArtifactsCatalog(pluginOnlyRuntimeArtifacts);

    getProject()
        .getExtensions()
        .getExtraProperties()
        .set(BESU_PLUGIN_ONLY_RUNTIME_ARTIFACTS, Map.copyOf(pluginOnlyRuntimeArtifacts));
  }

  private void processTransitiveDependencies(
      List<BesuProvidedDependency> besuProvidedDependencies,
      ResolvedDependency dependency,
      Map<File, ResolvedDependency> pluginOnlyRuntimeArtifacts,
      Set<ResolvedDependency> alreadyEvaluated) {
    for (ResolvedDependency child : dependency.getChildren()) {
      if (!alreadyEvaluated.contains(child)) {
        getLogger().lifecycle("Processing {}", child);
        alreadyEvaluated.add(child);
        if (!providedByBesu(besuProvidedDependencies, child)) {
          getLogger()
              .lifecycle(
                  "Plugin only runtime dependency {}, artifacts {}",
                  child,
                  child.getModuleArtifacts());
          child
              .getModuleArtifacts()
              .forEach(artifact -> pluginOnlyRuntimeArtifacts.put(artifact.getFile(), child));
        }
        // Recursively process children
        processTransitiveDependencies(
            besuProvidedDependencies, child, pluginOnlyRuntimeArtifacts, alreadyEvaluated);
      }
    }
  }

  private boolean providedByBesu(
      List<BesuProvidedDependency> besuProvidedDependencies, ResolvedDependency dependency) {
    String coordinate = dependency.getModuleGroup() + ":" + dependency.getModuleName();

    if (BesuOld2NewCoordinatesMapping.getOld2NewCoordinates().containsKey(coordinate)) {
      getLogger().lifecycle("Excluding old Besu dependency {}", dependency);
      return true;
    }

    var maybeBesuProvided =
        besuProvidedDependencies.stream()
            .filter(
                providedDependency ->
                    coordinate.startsWith(
                        providedDependency.dependency().getGroup()
                            + ":"
                            + providedDependency.dependency().getName()))
            .findAny();

    if (maybeBesuProvided.isPresent()) {
      getLogger()
          .lifecycle(
              "Excluding runtime dependency with coordinates {}({}) is already provided by Besu: '{}'",
              dependency,
              coordinate,
              maybeBesuProvided.get());
      return true;
    }

    return false;
  }

  private void generateArtifactsCatalog(
      final Map<File, ResolvedDependency> pluginOnlyRuntimeArtifacts) {
    List<Map<String, String>> jsonDependencies =
        pluginOnlyRuntimeArtifacts.entrySet().stream()
            .map(
                e ->
                    Map.of(
                        "group", e.getValue().getModuleGroup(),
                        "name", e.getValue().getModuleName(),
                        "version", e.getValue().getModuleVersion(),
                        "filename", e.getKey().getName()))
            .toList();

    Map<String, Object> doc =
        Map.of(
            "besuVersion",
            getProject().property("besuVersion").toString(),
            "dependencies",
            jsonDependencies);

    JsonBuilder jsonBuilder = new JsonBuilder(doc);

    String json = jsonBuilder.toPrettyString();
    getLogger().lifecycle("Generated artifacts catalog {}", json);
    var catalogFile =
        getProject()
            .getLayout()
            .getBuildDirectory()
            .file(PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH)
            .get()
            .getAsFile();
    catalogFile.getParentFile().mkdirs();
    try {
      Files.writeString(catalogFile.toPath(), json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(
          "Unable to write plugin artifacts catalog to file " + catalogFile, e);
    }
  }
}
