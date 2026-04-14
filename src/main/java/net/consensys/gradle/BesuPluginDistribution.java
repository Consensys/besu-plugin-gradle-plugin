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

import static net.consensys.gradle.CollectPluginOnlyRuntimeArtifactsTask.PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import groovy.json.JsonSlurper;
import javax.inject.Inject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.distribution.DistributionContainer;
import org.gradle.api.distribution.plugins.DistributionPlugin;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.internal.JavaPluginHelper;
import org.gradle.api.plugins.jvm.internal.JvmFeatureInternal;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.jvm.tasks.Jar;

public abstract class BesuPluginDistribution implements Plugin<Project> {

  @Inject
  protected abstract ProjectLayout getProjectLayout();

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(BesuPluginLibrary.class);
    project.getPluginManager().apply(DistributionPlugin.class);

    // Pre-compute catalog file provider using the injected layout (not project) so it can be
    // safely captured in the configureEach lambda without holding a Project reference.
    Provider<RegularFile> catalogFileProvider =
        getProjectLayout().getBuildDirectory().file(PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH);

    project
        .getTasks()
        .register(
            CollectPluginOnlyRuntimeArtifactsTask.TASK_NAME,
            CollectPluginOnlyRuntimeArtifactsTask.class,
            task -> {
              var artifacts =
                  project
                      .getConfigurations()
                      .getByName("runtimeClasspath")
                      .getIncoming()
                      .getArtifacts();

              task.getRuntimeArtifacts().from(artifacts.getArtifactFiles());

              // Map filename -> group:name:version, resolved lazily via Provider so
              // the resolved Map<String,String> (not Project) is what the config cache stores.
              task.getArtifactToModule()
                  .set(
                      artifacts
                          .getResolvedArtifacts()
                          .map(
                              resolvedArtifacts ->
                                  resolvedArtifacts.stream()
                                      .collect(
                                          Collectors.toMap(
                                              r -> r.getFile().getName(),
                                              r -> {
                                                var id =
                                                    r.getId().getComponentIdentifier();
                                                if (id instanceof ModuleComponentIdentifier mci) {
                                                  return mci.getGroup()
                                                      + ":"
                                                      + mci.getModule()
                                                      + ":"
                                                      + mci.getVersion();
                                                }
                                                return "";
                                              },
                                              (a, b) -> a))));

              // Besu-provided coordinates resolved after afterEvaluate via Provider.
              task.getBesuProvidedCoordinates()
                  .set(
                      project.provider(
                          () -> {
                            List<BesuPluginLibrary.BesuProvidedDependency> deps =
                                (List<BesuPluginLibrary.BesuProvidedDependency>)
                                    project
                                        .getExtensions()
                                        .getExtraProperties()
                                        .get(BesuPluginLibrary.BESU_PROVIDED_DEPENDENCIES);
                            return deps.stream()
                                .map(
                                    d ->
                                        d.dependency().getGroup()
                                            + ":"
                                            + d.dependency().getName())
                                .collect(Collectors.toList());
                          }));

              task.getBesuVersion()
                  .set(
                      project.provider(
                          () ->
                              project
                                  .getExtensions()
                                  .getExtraProperties()
                                  .get("besuVersion")
                                  .toString()));

              task.getCatalogFile().set(catalogFileProvider);
            });

    // The lambda below is stored by configureEach (lazy); capture the pre-computed Provider
    // rather than project itself to remain config-cache compatible.
    project
        .getTasks()
        .withType(Jar.class)
        .configureEach(
            jar -> {
              jar.dependsOn(CollectPluginOnlyRuntimeArtifactsTask.TASK_NAME);
              jar.from(catalogFileProvider, copySpec -> copySpec.into("META-INF/"));
            });

    JvmFeatureInternal mainFeature = JavaPluginHelper.getJavaComponent(project).getMainFeature();

    // Pre-compute values at configuration time to keep the configure lambda free of any
    // Project reference (the CopySpec exclude Spec is serialized by the config cache).
    var jarTaskProvider = mainFeature.getJarTask();
    var runtimeClasspath = mainFeature.getRuntimeClasspathConfiguration();
    File srcDistDir = project.file("src/dist");
    File catalogFile = catalogFileProvider.get().getAsFile();

    DistributionContainer distributionContainer =
        (DistributionContainer) project.getExtensions().getByName("distributions");
    distributionContainer
        .named(DistributionPlugin.MAIN_DISTRIBUTION_NAME)
        .configure(
            dist -> {
              dist.getContents().from(jarTaskProvider);
              dist.getContents().from(srcDistDir);
              dist.getContents()
                  .from(
                      runtimeClasspath,
                      copySpec ->
                          copySpec.exclude(new CatalogAwareExcludeSpec(catalogFile)));
            });
  }

  /**
   * Config-cache-compatible exclude spec for the distribution's runtime classpath. Reads the
   * plugin-only artifact catalog file (produced by {@link CollectPluginOnlyRuntimeArtifactsTask})
   * at execution time and excludes any file whose name does not appear in that catalog (i.e. files
   * already provided by Besu itself).
   *
   * <p>Implements {@link Serializable} so Gradle's configuration cache can safely persist it.
   * Holds only a {@link File} (the catalog path, known at configuration time) and a transient
   * cache of the parsed filenames (repopulated lazily on first use after cache load).
   */
  static final class CatalogAwareExcludeSpec implements Spec<FileTreeElement>, Serializable {

    private final File catalogFile;

    // Lazily populated at execution time; transient so it is not included in the serialized state.
    private transient Set<String> pluginOnlyFilenames;

    CatalogAwareExcludeSpec(File catalogFile) {
      this.catalogFile = catalogFile;
    }

    /** Returns {@code true} to exclude the element (i.e. it is provided by Besu, not plugin-only). */
    @Override
    public boolean isSatisfiedBy(FileTreeElement element) {
      return !getPluginOnlyFilenames().contains(element.getFile().getName());
    }

    private Set<String> getPluginOnlyFilenames() {
      if (pluginOnlyFilenames == null) {
        pluginOnlyFilenames = parseCatalogFilenames(catalogFile);
      }
      return pluginOnlyFilenames;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> parseCatalogFilenames(File catalogFile) {
      var json = (Map<String, Object>) new JsonSlurper().parse(catalogFile);
      var dependencies = (List<Map<String, String>>) json.get("dependencies");
      return dependencies.stream().map(dep -> dep.get("filename")).collect(Collectors.toSet());
    }
  }
}
