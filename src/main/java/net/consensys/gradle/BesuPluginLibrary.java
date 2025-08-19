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

import groovy.json.JsonSlurper;
import groovy.xml.DOMBuilder;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public abstract class BesuPluginLibrary implements Plugin<Project> {
  static final String BESU_PROVIDED_DEPENDENCIES = BesuPluginLibrary.class.getName() + ".besuBomDependencies";
  static final String BESU_BOM_DEPENDENCY_COORDINATES = "org.hyperledger.besu:bom";
  static final String BESU_MAIN_DEPENDENCY_COORDINATES = "org.hyperledger.besu.internal:besu-app";
  static final String BESU_ARTIFACTS_CATALOG_RESOURCE_NAME = "/META-INF/besu-artifacts-catalog.json";
  private final static Set<String> ANNOTATION_PROCESSOR_DEPENDENCIES = Set.of(
      "com.google.auto.service:auto-service"
  );

  @Override
  public void apply(final Project project) {
    project.getPluginManager().apply(JavaLibraryPlugin.class);

    String besuVersion = project.property("besuVersion").toString();
    String besuRepo = Optional.ofNullable(project.property("besuRepo"))
        .map(Object::toString)
        .orElse("https://hyperledger.jfrog.io/hyperledger/besu-maven/");

    configureRepositories(project, besuRepo);

    Configuration bomConfiguration = project.getConfigurations()
        .detachedConfiguration(project.getDependencies().create(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion + "@pom"));
    bomConfiguration.setCanBeResolved(true);
    File besuBom = bomConfiguration.getSingleFile();
    List<Dependency> bomDependencies;
    try {
      bomDependencies = parseBesuBOM(project, besuBom);
    } catch (ParserConfigurationException | IOException | SAXException e) {
      throw new RuntimeException(e);
    }

    Configuration besuDependencyCatalogConfiguration = project.getConfigurations()
        .detachedConfiguration(project.getDependencies().create(BESU_MAIN_DEPENDENCY_COORDINATES + ":" + besuVersion + "@jar"));
    besuDependencyCatalogConfiguration.setCanBeResolved(true);
    File besuMainJar = besuDependencyCatalogConfiguration.getSingleFile();
    String besuDependencyCatalog;
    try (FileSystem zipFs = FileSystems.newFileSystem(besuMainJar.toPath())) {
      besuDependencyCatalog = Files.readString(zipFs.getPath(BESU_ARTIFACTS_CATALOG_RESOURCE_NAME));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    List<BesuProvidedDependency> besuProvidedDependencies;
    try {
      besuProvidedDependencies = parseBesuDependencyCatalog(project, besuDependencyCatalog);
    } catch (ParserConfigurationException | IOException | SAXException e) {
      throw new RuntimeException(e);
    }

    List<Dependency> mergedDependencies = mergeDependencies(bomDependencies, besuProvidedDependencies);

    project.getExtensions().getExtraProperties().set(BESU_PROVIDED_DEPENDENCIES, List.copyOf(besuProvidedDependencies));

    addBesuDependencies(project, besuVersion, mergedDependencies);
    excludeOldCoordinatesBesuDependencies(project);
  }

  private void configureRepositories(final Project project, final String besuRepo) {
    project.getRepositories().maven(mavenArtifactRepository -> {
      mavenArtifactRepository.setUrl(URI.create(besuRepo));
      mavenArtifactRepository.mavenContent(mavenRepositoryContentDescriptor ->
          mavenRepositoryContentDescriptor.includeGroupAndSubgroups("org.hyperledger.besu"));
    });
    project.getRepositories().maven(mavenArtifactRepository -> {
      mavenArtifactRepository.setUrl(URI.create("https://hyperledger.jfrog.io/hyperledger/besu-maven/"));
      mavenArtifactRepository.mavenContent(mavenRepositoryContentDescriptor ->
          mavenRepositoryContentDescriptor.includeGroupAndSubgroups("org.hyperledger.besu"));
    });
    project.getRepositories().maven(mavenArtifactRepository -> {
      mavenArtifactRepository.setUrl(URI.create("https://artifacts.consensys.net/public/maven/maven/"));
      mavenArtifactRepository.mavenContent(mavenRepositoryContentDescriptor ->
          mavenRepositoryContentDescriptor.includeGroupAndSubgroups("tech.pegasys"));
    });
    project.getRepositories().maven(mavenArtifactRepository -> {
      mavenArtifactRepository.setUrl(URI.create("https://splunk.jfrog.io/splunk/ext-releases-local/"));
      mavenArtifactRepository.mavenContent(mavenRepositoryContentDescriptor ->
          mavenRepositoryContentDescriptor.includeGroupAndSubgroups("com.splunk"));
    });

    project.getRepositories().mavenCentral();
    project.getRepositories().mavenLocal();
  }

  private List<Dependency> mergeDependencies(final List<Dependency> bomDependencies, final List<BesuProvidedDependency> besuProvidedDependencies) {
    List<Dependency> mergedDependencies = new ArrayList<>(bomDependencies);
    for (BesuProvidedDependency providedDependency : besuProvidedDependencies) {
      if (bomDependencies.stream().noneMatch(bomDependency ->
          bomDependency.getGroup().equals(providedDependency.dependency().getGroup()) &&
              bomDependency.getName().equals(providedDependency.dependency().getName())
      )) {
        mergedDependencies.add(providedDependency.dependency());
      }
    }

    return mergedDependencies;
  }

  private List<BesuProvidedDependency> parseBesuDependencyCatalog(final Project project, final String besuDependencyCatalog) throws ParserConfigurationException, IOException, SAXException {
    List<BesuProvidedDependency> besuProvidedDependencies = new ArrayList<>();

    ArrayList json = (ArrayList) new JsonSlurper().parseText(besuDependencyCatalog);
    for (Object o : json) {
      Map<String, String> dependency = (Map<String, String>) o;
      besuProvidedDependencies.add(
          new BesuProvidedDependency(
              project.getDependencies().create(
                  dependency.get("group") + ":"
                      + dependency.get("name") + ":"
                      + dependency.get("version") + "!!"
                      + (dependency.containsKey("classifier") ? ":" + dependency.get("classifier") : ""))
              , dependency.get("filename"))
      );
    }
    return besuProvidedDependencies;
  }

  private List<Dependency> parseBesuBOM(final Project project, final File besuBom) throws ParserConfigurationException, IOException, SAXException {
    List<Dependency> bomDependencies = new ArrayList<>();
    Node dependencyManagementNode = DOMBuilder.parse(new FileReader(besuBom)).getDocumentElement().getElementsByTagName("dependencyManagement").item(0);

    Element dependenciesElement = getElement(dependencyManagementNode, "dependencies");

    List<Element> dependencyElements = getElements(dependenciesElement.getElementsByTagName("dependency"), "dependency");

    for (Element depElement : dependencyElements) {
      var typeElement = depElement.getElementsByTagName("type");
      boolean isBom = typeElement.getLength() > 0 && depElement.getElementsByTagName("type").item(0).getTextContent().equals("pom");
      if (!isBom) {
        var groupId = depElement.getElementsByTagName("groupId").item(0).getTextContent();
        var artifactId = depElement.getElementsByTagName("artifactId").item(0).getTextContent();
        var version = depElement.getElementsByTagName("version").item(0).getTextContent();
        var classifierElement = depElement.getElementsByTagName("classifier");

        bomDependencies.add(project.getDependencies().create(groupId + ":" + artifactId + ":" + version + "!!" + (classifierElement.getLength() > 0 ? ":" + classifierElement.item(0).getTextContent() : "")));
      }
    }
    return bomDependencies;
  }

  private void addBesuDependencies(final Project project, final String besuVersion, final List<Dependency> mergedDependencies) {
    project.getDependencies().add("annotationProcessor", project.getDependencies().enforcedPlatform(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion));
    project.getDependencies().add("implementation", project.getDependencies().enforcedPlatform(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion));
    project.getDependencies().add("testImplementation", project.getDependencies().enforcedPlatform(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion));
    project.getDependencies().add("compileOnly", project.getDependencies().enforcedPlatform(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion));
    project.getDependencies().add("testCompileOnly", project.getDependencies().enforcedPlatform(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion));
    project.getDependencies().add("runtimeOnly", project.getDependencies().enforcedPlatform(BESU_BOM_DEPENDENCY_COORDINATES + ":" + besuVersion));

    for (Dependency dependency : mergedDependencies) {
      project.getDependencies().add("compileOnly", dependency);
      project.getDependencies().add("testImplementation", dependency);

      if (ANNOTATION_PROCESSOR_DEPENDENCIES.contains(dependency.getGroup() + ":" + dependency.getName())) {
        project.getDependencies().add("annotationProcessor", dependency);
      }
    }
  }

  private void excludeOldCoordinatesBesuDependencies(final Project project) {
    project.getConfigurations().all(configuration -> {
      configuration.resolutionStrategy(strategy -> {
        strategy.getComponentSelection().all(selection -> {
          ModuleComponentIdentifier requested = selection.getCandidate();
          var groupId = requested.getGroup();
          var moduleId = requested.getModule();

          // Exclude Besu old coordinates
          if (isOldCoordinate(groupId, moduleId)) {
            selection.reject("Excluded Besu old coordinate: " + groupId + ":" + moduleId);
          }
        });
      });
    });
  }

  private boolean isOldCoordinate(String group, String module) {
    return BesuOld2NewCoordinatesMapping.getOld2NewCoordinates().containsKey(group + ":" +  module);
  }

  private Element getElement(Node node, String name) {
    for (int i = 0; i < node.getChildNodes().getLength(); i++) {
      if (node.getChildNodes().item(i).getNodeName().equals(name)) {
        return (Element) node.getChildNodes().item(i);
      }
    }
    throw new RuntimeException("Element %s not found in node %s".formatted(name, node.getNodeName()));
  }

  private List<Element> getElements(NodeList nodeList, String name) {
    List<Element> elements = new ArrayList<>(nodeList.getLength());
    for (int i = 0; i < nodeList.getLength(); i++) {
      if (nodeList.item(i).getNodeName().equals(name)) {
        elements.add((Element) nodeList.item(i));
      }
    }
    return elements;
  }

  record BesuProvidedDependency(Dependency dependency, String filename) {
  }
}
