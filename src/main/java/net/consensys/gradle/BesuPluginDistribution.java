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
import java.util.Map;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.distribution.DistributionContainer;
import org.gradle.api.distribution.plugins.DistributionPlugin;
import org.gradle.api.file.CopySpec;
import org.gradle.api.plugins.internal.JavaPluginHelper;
import org.gradle.api.plugins.jvm.internal.JvmFeatureInternal;
import org.gradle.jvm.tasks.Jar;

public abstract class BesuPluginDistribution implements Plugin<Project> {

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(BesuPluginLibrary.class);
    project.getPluginManager().apply(DistributionPlugin.class);

    // Register the task
    project
        .getTasks()
        .register(
            CollectPluginOnlyRuntimeArtifactsTask.TASK_NAME,
            CollectPluginOnlyRuntimeArtifactsTask.class,
            task ->
                task.getRuntimeArtifacts()
                    .from(project.getConfigurations().getByName("runtimeClasspath")));
    project
        .getTasks()
        .withType(Jar.class)
        .configureEach(
            jar -> {
              jar.dependsOn(CollectPluginOnlyRuntimeArtifactsTask.TASK_NAME);
              jar.from(
                  project
                      .getLayout()
                      .getBuildDirectory()
                      .file(PLUGIN_ARTIFACTS_CATALOG_RELATIVE_PATH),
                  copySpec -> copySpec.into("META-INF/"));
            });

    JvmFeatureInternal mainFeature = JavaPluginHelper.getJavaComponent(project).getMainFeature();

    DistributionContainer distributionContainer =
        (DistributionContainer) project.getExtensions().getByName("distributions");
    distributionContainer
        .named(DistributionPlugin.MAIN_DISTRIBUTION_NAME)
        .configure(
            dist -> {
              CopySpec childSpec = project.copySpec();
              childSpec.from(mainFeature.getJarTask());
              childSpec.from(project.file("src/dist"));
              childSpec.from(
                  mainFeature.getRuntimeClasspathConfiguration(),
                  copySpec ->
                      copySpec.exclude(element -> providedByBesu(project, element.getFile())));

              dist.getContents().with(childSpec);
            });
  }

  private boolean providedByBesu(Project project, File file) {
    Map<File, ResolvedDependency> pluginOnlyRuntimeArtifacts =
        (Map<File, ResolvedDependency>)
            project
                .getExtensions()
                .getExtraProperties()
                .get(CollectPluginOnlyRuntimeArtifactsTask.BESU_PLUGIN_ONLY_RUNTIME_ARTIFACTS);
    project.getLogger().lifecycle("is provided by Besu {}", file);
    return !pluginOnlyRuntimeArtifacts.containsKey(file);
  }
}
