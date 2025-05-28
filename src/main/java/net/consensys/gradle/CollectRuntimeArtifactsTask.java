package net.consensys.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public abstract class CollectRuntimeArtifactsTask extends DefaultTask {
  static final String BESU_PLUGIN_RUNTIME_ARTIFACTS = CollectRuntimeArtifactsTask.class.getName() + ".runtimeArtifacts";

  @TaskAction
  public void collectRuntimeArtifacts() {

    // Resolve each configuration
    Configuration config = getProject().getConfigurations().getByName("runtimeClasspath");

    Set<ResolvedDependency> alreadyEvaluated = new HashSet<>();
    Map<File, String> runtimeArtifacts = new HashMap<>();
    getLogger().lifecycle("Resolving runtimeClasspath artifacts");
    // Get all resolved dependencies
    Set<ResolvedDependency> firstLevelDeps = config.getResolvedConfiguration().getFirstLevelModuleDependencies();

    // Process first-level dependencies
    for (ResolvedDependency dependency : firstLevelDeps) {
      alreadyEvaluated.add(dependency);
      String moduleId = dependency.getModuleGroup() + ":" + dependency.getModuleName() + ":" + dependency.getModuleVersion();
      getLogger().debug("{}, artifacts {}", moduleId, dependency.getModuleArtifacts());
      dependency.getModuleArtifacts().forEach(artifact ->
          runtimeArtifacts.put(artifact.getFile(), moduleId)
      );

      processTransitiveDependencies(dependency, runtimeArtifacts, alreadyEvaluated);
    }

    getProject().getExtensions().getExtraProperties().set(BESU_PLUGIN_RUNTIME_ARTIFACTS, Map.copyOf(runtimeArtifacts));
  }

  private void processTransitiveDependencies(ResolvedDependency dependency, Map<File, String> runtimeArtifacts, Set<ResolvedDependency> alreadyEvaluated) {
    for (ResolvedDependency child : dependency.getChildren()) {
      String moduleId = child.getModuleGroup() + ":" + child.getModuleName() + ":" + child.getModuleVersion();
      if (!alreadyEvaluated.contains(child)) {
        alreadyEvaluated.add(child);
        getLogger().debug("{}, artifacts {}", moduleId, child.getModuleArtifacts());
        child.getModuleArtifacts().forEach(artifact ->
            runtimeArtifacts.put(artifact.getFile(), moduleId)
        );
        // Recursively process children
        processTransitiveDependencies(child, runtimeArtifacts, alreadyEvaluated);
      }
    }
  }
}