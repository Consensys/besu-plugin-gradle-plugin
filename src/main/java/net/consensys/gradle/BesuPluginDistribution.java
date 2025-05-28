/*
 * Copyright 2010 the original author or authors.
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

package net.consensys.gradle;

import net.consensys.gradle.BesuPluginLibrary.BesuProvidedDependency;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.distribution.DistributionContainer;
import org.gradle.api.distribution.plugins.DistributionPlugin;
import org.gradle.api.file.CopySpec;
import org.gradle.api.plugins.internal.JavaPluginHelper;
import org.gradle.api.plugins.jvm.internal.JvmFeatureInternal;

import java.io.File;
import java.util.List;
import java.util.Map;

public abstract class BesuPluginDistribution implements Plugin<Project> {

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(BesuPluginLibrary.class);
    project.getPluginManager().apply(DistributionPlugin.class);

    List<BesuProvidedDependency> besuProvidedDependencies = (List<BesuProvidedDependency>) project.getExtensions().getExtraProperties()
        .get(BesuPluginLibrary.BESU_PROVIDED_DEPENDENCIES);

    // Register the task
    project.getTasks().register("collectRuntimeArtifacts", CollectRuntimeArtifactsTask.class);
    project.getTasks().named("installDist", task -> task.dependsOn("collectRuntimeArtifacts"));
    project.getTasks().named("distTar", task -> task.dependsOn("collectRuntimeArtifacts"));
    project.getTasks().named("distZip", task -> task.dependsOn("collectRuntimeArtifacts"));

    JvmFeatureInternal mainFeature = JavaPluginHelper.getJavaComponent(project).getMainFeature();

    DistributionContainer distributionContainer = (DistributionContainer) project.getExtensions().getByName("distributions");
    distributionContainer.named(DistributionPlugin.MAIN_DISTRIBUTION_NAME).configure(dist -> {
      CopySpec childSpec = project.copySpec();
      childSpec.from(mainFeature.getJarTask());
      childSpec.from(project.file("src/dist"));
      childSpec.from(mainFeature.getRuntimeClasspathConfiguration());
      childSpec.exclude(element -> providedByBesu(project, besuProvidedDependencies, element.getFile()));

      dist.getContents().with(childSpec);
    });
  }

  private boolean providedByBesu(Project project, List<BesuProvidedDependency> besuProvidedDependencies, File file) {
    Map<File, String> runtimeArtifacts = (Map<File, String>) project.getExtensions().getExtraProperties()
        .get(CollectRuntimeArtifactsTask.BESU_PLUGIN_RUNTIME_ARTIFACTS);

    String coordinate = runtimeArtifacts.get(file);
    if (coordinate != null) {
      String normalizedCoordinate = BesuOld2NewCoordinatesMapping.getOld2NewCoordinates().entrySet().stream()
          .filter(e -> coordinate.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(coordinate);

      var maybeBesuProvided = besuProvidedDependencies.stream().filter(providedDependency ->
          normalizedCoordinate.startsWith(providedDependency.dependency().getGroup() + ":" + providedDependency.dependency().getName())).findAny();

      if (maybeBesuProvided.isPresent()) {
        project.getLogger()
            .debug("Excluding runtime artifacts {} with coordinates {}({}) is already provided by Besu: '{}'",
                file, coordinate, normalizedCoordinate, maybeBesuProvided.get());
        return true;
      }
    }
    return false;
  }
}
